;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster
  "Host rasterizer for render spec v2 (M2 prototype): turns the kernel's
  raster requests (watermark.render.v2) into straight-alpha RGBA8 bitmaps,
  named by the SHA-256 of their pixels, and writes them for the engine.

  The geometry is pure arithmetic on byte arrays, identical on every JVM:
  - the logo is area-averaged to its box size;
  - each flip frame is an exact projective warp of that image into the
    kernel's quad (inverse homography, bilinear sampling, premultiplied
    alpha, transparent outside the card);
  - opacity is multiplied into alpha.

  Two steps still use java.desktop, marked PROTOTYPE below: decoding the logo
  file (ImageIO) and typesetting text (Java2D). GraalVM native images can't
  load AWT on macOS (oracle/graal#13272, GraalVM 25.0.2), so the formal M2
  must replace or isolate them before this runs inside the native engine
  (docs/adr/0006)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.render.v2 :as v2])
  (:import (java.awt BasicStroke Color Font RenderingHints)
           (java.awt.font FontRenderContext TextLayout)
           (java.awt.geom AffineTransform)
           (java.awt.image BufferedImage)
           (java.security MessageDigest)
           (java.util HexFormat)
           (javax.imageio ImageIO)))

(set! *warn-on-reflection* true)

;; An image is {:width w :height h :px bytes}, straight RGBA8, row-major.

(defn- u8 ^long [^bytes a ^long i] (bit-and 0xff (aget a i)))

;; ---------------------------------------------------------------------------
;; PROTOTYPE (java.desktop): decoding and typesetting

(defn- from-argb
  "An ARGB BufferedImage as a straight RGBA8 image."
  [^BufferedImage img]
  (let [w (.getWidth img) h (.getHeight img)
        argb (.getRGB img 0 0 w h nil 0 w)
        px (byte-array (* 4 w h))]
    (dotimes [i (* w h)]
      (let [c (aget argb i) o (* 4 i)]
        (aset px o       (unchecked-byte (bit-and 0xff (bit-shift-right c 16))))
        (aset px (+ o 1) (unchecked-byte (bit-and 0xff (bit-shift-right c 8))))
        (aset px (+ o 2) (unchecked-byte (bit-and 0xff c)))
        (aset px (+ o 3) (unchecked-byte (bit-and 0xff (unsigned-bit-shift-right c 24))))))
    {:width w :height h :px px}))

(defn decode-image
  "The image file at `path` as RGBA8. PROTOTYPE: ImageIO (java.desktop)."
  [path]
  (let [^BufferedImage img (or (ImageIO/read (io/file (str path)))
                              (throw (ex-info (str "Can't read the image " path) {:wmark/error :invalid :path (str path)})))
        argb (BufferedImage. (.getWidth img) (.getHeight img) BufferedImage/TYPE_INT_ARGB)
        g    (.createGraphics argb)]
    (.drawImage g img (int 0) (int 0) ^java.awt.image.ImageObserver (identity nil))
    (.dispose g)
    (from-argb argb)))

(def ^:private named-colors
  {"white" 0xffffff "black" 0x000000 "red" 0xff0000 "green" 0x008000 "blue" 0x0000ff
   "yellow" 0xffff00 "cyan" 0x00ffff "magenta" 0xff00ff "gray" 0x808080 "grey" 0x808080
   "orange" 0xffa500})

(defn- rgb ^long [color]
  (let [c (str/lower-case (str color))]
    (or (named-colors c)
        (when-let [[_ hex] (re-matches #"#([0-9a-f]{6})" c)] (Long/parseLong hex 16))
        (throw (ex-info (str "Unknown colour " color) {:wmark/error :invalid})))))

(defn- awt-color ^Color [color opacity]
  (let [c (rgb color)]
    (Color. (int (bit-and 0xff (bit-shift-right c 16))) (int (bit-and 0xff (bit-shift-right c 8)))
            (int (bit-and 0xff c)) (int (Math/round (* 255.0 (double opacity)))))))

(defn render-text
  "A text layer's style and text as a tight RGBA8 bitmap: the glyph outlines
  stroked with the border, then filled. The bitmap is the text box the
  placement refers to. PROTOTYPE: Java2D (java.desktop)."
  [{:keys [font size color opacity border border-color border-opacity]} ^String text]
  (when-not font
    (throw (ex-info "Text layers need a font file." {:wmark/error :invalid})))
  (let [f       (.deriveFont (Font/createFont Font/TRUETYPE_FONT (io/file (str font))) (float size))
        frc     (FontRenderContext. (AffineTransform.) RenderingHints/VALUE_TEXT_ANTIALIAS_ON
                                    RenderingHints/VALUE_FRACTIONALMETRICS_ON)
        outline (.getOutline (TextLayout. text f frc) nil)
        b       (.getBounds2D outline)
        pad     (+ (long border) 1)
        w       (max 1 (+ (long (Math/ceil (.getWidth b))) (* 2 pad)))
        h       (max 1 (+ (long (Math/ceil (.getHeight b))) (* 2 pad)))
        img     (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        g       (.createGraphics img)]
    (doto g
      (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setRenderingHint RenderingHints/KEY_STROKE_CONTROL RenderingHints/VALUE_STROKE_PURE)
      (.setRenderingHint RenderingHints/KEY_FRACTIONALMETRICS RenderingHints/VALUE_FRACTIONALMETRICS_ON)
      (.translate (- (double pad) (.getX b)) (- (double pad) (.getY b))))
    (when (pos? (long border))
      (doto g
        (.setColor (awt-color border-color border-opacity))
        (.setStroke (BasicStroke. (float (* 2 (long border))) BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
        (.draw outline)))
    (doto g
      (.setColor (awt-color color opacity))
      (.fill outline)
      (.dispose))
    (from-argb img)))

;; ---------------------------------------------------------------------------
;; Pure pixel arithmetic

(defn premultiply
  "RGBA8 straight -> float premultiplied [r g b a] per pixel, 0..1."
  ^floats [{:keys [^bytes px width height]}]
  (let [n (* (long width) (long height)) out (float-array (* 4 n))]
    (dotimes [i n]
      (let [o (* 4 i) a (/ (u8 px (+ o 3)) 255.0)]
        (aset out o       (float (* a (/ (u8 px o) 255.0))))
        (aset out (+ o 1) (float (* a (/ (u8 px (+ o 1)) 255.0))))
        (aset out (+ o 2) (float (* a (/ (u8 px (+ o 2)) 255.0))))
        (aset out (+ o 3) (float a))))
    out))

(defn area-scale
  "Resample premultiplied `src` (sw x sh) to dw x dh by area averaging: each
  target pixel is the coverage-weighted mean of the source pixels under it."
  ^floats [^floats src sw sh dw dh]
  (let [sw (long sw) sh (long sh) dw (long dw) dh (long dh)
        out (float-array (* 4 dw dh))
        fx (/ (double sw) dw) fy (/ (double sh) dh)]
    (dotimes [ty dh]
      (let [y0 (* ty fy) y1 (+ y0 fy)]
        (dotimes [tx dw]
          (let [x0 (* tx fx) x1 (+ x0 fx)
                acc (double-array 4)]
            (loop [sy (long (Math/floor y0))]
              (when (and (< sy y1) (< sy sh))
                (let [wy (- (min y1 (inc sy)) (max y0 sy))]
                  (loop [sx (long (Math/floor x0))]
                    (when (and (< sx x1) (< sx sw))
                      (let [wx (- (min x1 (inc sx)) (max x0 sx))
                            wgt (* wx wy) o (* 4 (+ sx (* sy sw)))]
                        (dotimes [c 4]
                          (aset acc c (+ (aget acc c) (* wgt (aget src (+ o c)))))))
                      (recur (inc sx)))))
                (recur (inc sy))))
            (let [o (* 4 (+ tx (* ty dw))) area (* fx fy)]
              (dotimes [c 4] (aset out (+ o c) (float (/ (aget acc c) area)))))))))
    out))

(defn homography
  "3x3 matrix [a b c d e f g h 1] mapping the unit square to a quad given as
  [top-left top-right bottom-left bottom-right]: (u,v) -> (x,y) with
  x = (a u + b v + c) / (g u + h v + 1), y likewise (Heckbert 1989)."
  [[[x0 y0] [x1 y1] [x3 y3] [x2 y2]]]
  (let [x0 (double x0) y0 (double y0) x1 (double x1) y1 (double y1)
        x2 (double x2) y2 (double y2) x3 (double x3) y3 (double y3)
        dx1 (- x1 x2) dx2 (- x3 x2) dx3 (+ (- x0 x1) (- x2 x3))
        dy1 (- y1 y2) dy2 (- y3 y2) dy3 (+ (- y0 y1) (- y2 y3))]
    (if (and (zero? dx3) (zero? dy3))
      [(- x1 x0) (- x3 x0) x0 (- y1 y0) (- y3 y0) y0 0.0 0.0 1.0]
      (let [det (- (* dx1 dy2) (* dx2 dy1))
            g   (/ (- (* dx3 dy2) (* dx2 dy3)) det)
            h   (/ (- (* dx1 dy3) (* dx3 dy1)) det)]
        [(+ (- x1 x0) (* g x1)) (+ (- x3 x0) (* h x3)) x0
         (+ (- y1 y0) (* g y1)) (+ (- y3 y0) (* h y3)) y0
         g h 1.0]))))

(defn invert3
  "Inverse (up to scale) of a 3x3 matrix, by its adjugate."
  [[a b c d e f g h i]]
  (let [a (double a) b (double b) c (double c) d (double d) e (double e)
        f (double f) g (double g) h (double h) i (double i)]
    [(- (* e i) (* f h)) (- (* c h) (* b i)) (- (* b f) (* c e))
     (- (* f g) (* d i)) (- (* a i) (* c g)) (- (* c d) (* a f))
     (- (* d h) (* e g)) (- (* b g) (* a h)) (- (* a e) (* b d))]))

(defn- sample
  "Bilinear sample of premultiplied `src` at pixel-centre coordinates (x, y);
  outside the image counts as transparent, so card edges are antialiased."
  [^floats src sw sh x y ^doubles out]
  (let [sw (long sw) sh (long sh) x (double x) y (double y)
        x0 (long (Math/floor x)) y0 (long (Math/floor y))
        fx (- x x0) fy (- y y0)]
    (java.util.Arrays/fill out 0.0)
    (doseq [[xx yy wgt] [[x0 y0 (* (- 1.0 fx) (- 1.0 fy))] [(inc x0) y0 (* fx (- 1.0 fy))]
                         [x0 (inc y0) (* (- 1.0 fx) fy)] [(inc x0) (inc y0) (* fx fy)]]]
      (let [xx (long xx) yy (long yy) wgt (double wgt)]
        (when (and (pos? wgt) (<= 0 xx) (< xx sw) (<= 0 yy) (< yy sh))
          (let [o (* 4 (+ xx (* yy sw)))]
            (dotimes [c 4] (aset out c (+ (aget out c) (* wgt (aget src (+ o c))))))))))))

(defn warp
  "Draw premultiplied `src` (sw x sh), stretched over the unit square, into a
  dw x dh bitmap so that its corners land on `quad` (bitmap coordinates).
  Returns premultiplied floats."
  ^floats [^floats src sw sh dw dh quad]
  (let [dw (long dw) dh (long dh) sw (long sw) sh (long sh)
        [a b c d e f g h i] (invert3 (homography quad))
        a (double a) b (double b) c (double c) d (double d) e (double e)
        f (double f) g (double g) h (double h) i (double i)
        out (float-array (* 4 dw dh)) px (double-array 4)]
    (dotimes [py dh]
      (dotimes [pxl dw]
        (let [X (+ pxl 0.5) Y (+ py 0.5)
              w (+ (* g X) (* h Y) i)]
          (when-not (zero? w)
            (let [u (/ (+ (* a X) (* b Y) c) w)
                  v (/ (+ (* d X) (* e Y) f) w)]
              (when (and (< -0.01 u 1.01) (< -0.01 v 1.01))
                (sample src sw sh (- (* u sw) 0.5) (- (* v sh) 0.5) px)
                (let [o (* 4 (+ pxl (* py dw)))]
                  (dotimes [k 4] (aset out (+ o k) (float (aget px k)))))))))))
    out))

(defn to-rgba8
  "Premultiplied floats -> straight RGBA8, with `opacity` applied to alpha."
  [^floats pm w h opacity]
  (let [n (* (long w) (long h)) px (byte-array (* 4 n)) op (double opacity)
        q (fn ^long [^double v] (max 0 (min 255 (Math/round (* 255.0 v)))))]
    (dotimes [i n]
      (let [o (* 4 i) a (double (aget pm (+ o 3)))]
        (when (pos? a)
          (dotimes [k 3] (aset px (+ o k) (unchecked-byte (q (/ (aget pm (+ o k)) a)))))
          (aset px (+ o 3) (unchecked-byte (q (* a op)))))))
    {:width w :height h :px px}))

;; ---------------------------------------------------------------------------
;; Requests -> bitmaps -> v2 spec

(defn bitmap-id ^String [{:keys [width height ^bytes px]}]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str width "x" height ":") "UTF-8"))
    (.update md px)
    (.formatHex (HexFormat/of) (.digest md))))

(defn rasterize
  "RGBA8 image for one raster request. `decoded` memoizes source images."
  [{:keys [kind source card size quad opacity text style]} decoded]
  (case kind
    :image (let [{sw :width sh :height :as img} (decoded (:path source))
                 [cw ch] card
                 [dw dh] size
                 ;; area-scale to the card's size once; a flip frame warps that
                 scaled  (area-scale (premultiply img) sw sh cw ch)]
             (if (= quad [[0 0] [cw 0] [0 ch] [cw ch]])
               (to-rgba8 scaled dw dh opacity)
               (to-rgba8 (warp scaled cw ch dw dh quad) dw dh opacity)))
    :text  (render-text style text)))

(defn realize
  "A v2 spec for the v1 `spec`: rasterizes every request, writes each
  distinct bitmap once to `dir` as <sha256>.rgba, and assembles the spec."
  [spec dir]
  (let [cache   (atom {})
        decoded (fn [path] (or (@cache path) ((swap! cache assoc path (decode-image path)) path)))
        results (into {}
                      (for [req (v2/raster-requests spec)
                            :let [img (rasterize req decoded)
                                  id  (bitmap-id img)
                                  f   (io/file (str dir) (str id ".rgba"))]]
                        (do (when-not (.exists f)
                              (io/make-parents f)
                              (with-open [o (io/output-stream f)] (.write o ^bytes (:px img))))
                            [(:key req) {:bitmap id :width (:width img) :height (:height img)
                                         :path (str (.getAbsolutePath f))}])))]
    (v2/assemble spec results)))
