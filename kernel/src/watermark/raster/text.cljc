;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.text
  "Text layers as bitmaps for render spec v2: layout, outline coverage, the
  border, colour. Pure arithmetic, so a text layer rasterizes to the same
  pixels on every host (docs/adr/0006).

  - Layout: one line per \\n, glyphs placed by their advance widths (no
    kerning or shaping), baselines `ascender` apart plus the line gap, all
    from the font's hhea table. Size is the pixel size of the em, like
    FreeType's pixel sizes (FFmpeg drawtext's fontsize).
  - Coverage: outlines flattened to lines, then exact area coverage by
    signed-area accumulation (the technique Raph Levien describes for
    font-rs): each edge adds its signed area to the cells it crosses, and a
    running sum gives each pixel's coverage. Unhinted, so no platform's
    hinting engine is involved.
  - Border: the coverage dilated by a disc of radius `border` with a soft
    edge, drawn under the text (drawtext strokes the outline by borderw).
  - The bitmap is tight around the ink plus the border and a pixel of
    antialiasing; it is the text box that v1's placement refers to."
  (:require [clojure.string :as str]
            [watermark.raster.color :as color]
            [watermark.raster.image :as image]
            [watermark.raster.truetype :as tt]
            [watermark.util.chars :as chars]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

(defn codepoints
  "The Unicode code points of a string."
  [s]
  (chars/code-points s))

;; ---------------------------------------------------------------------------
;; Outlines -> line segments in pixels

(defn- flatten-quad
  "Points of a quadratic Bezier after p0, enough that the chord stays within
  about a third of a pixel of the curve."
  [[x0 y0] [cx cy] [x1 y1]]
  (let [dx (+ x0 (* -2.0 cx) x1) dy (+ y0 (* -2.0 cy) y1)
        n  (+ 1 (number/floor-int (number/sqrt (number/sqrt (* 3.0 (+ (* dx dx) (* dy dy)))))))]
    (for [i (range 1 (inc n))
          :let [t (/ (* 1.0 i) n) u (- 1.0 t)]]
      [(+ (* u u x0) (* 2.0 t u cx) (* t t x1))
       (+ (* u u y0) (* 2.0 t u cy) (* t t y1))])))

(defn- contour-polyline
  "A closed contour of [x y on?] points as a polyline of [x y], quadratic
  segments flattened; consecutive off-curve points imply an on-curve point
  midway."
  [pts]
  (let [n (count pts)
        k (first (filter #(nth (pts %) 2) (range n)))
        ring (if k
               (vec (concat (subvec pts k) (subvec pts 0 k)))
               (let [[ax ay] (pts 0) [bx by] (pts (dec n))]
                 (into [[(/ (+ ax bx) 2.0) (/ (+ ay by) 2.0) true]] pts)))
        start (subvec (ring 0) 0 2)]
    (loop [[q & more] (conj (subvec ring 1) (ring 0)), cur start, ctrl nil, out [start]]
      (if-not q
        out
        (let [[qx qy on?] q p [qx qy]]
          (cond
            (and on? (nil? ctrl)) (recur more p nil (conj out p))
            on?                   (recur more p nil (into out (flatten-quad cur ctrl p)))
            (nil? ctrl)           (recur more cur p out)
            :else (let [m [(/ (+ (first ctrl) qx) 2.0) (/ (+ (second ctrl) qy) 2.0)]]
                    (recur more m p (into out (flatten-quad cur ctrl m))))))))))

(defn- layout
  "Polylines of `text` in pixels (y down, first baseline at the ascender),
  from the font at pixel size `size`."
  [font size text]
  (let [scale (/ (* 1.0 size) (:units-per-em font))
        line-h (* scale (+ (- (:ascender font) (:descender font)) (:line-gap font)))]
    (vec
     (apply concat
            (map-indexed
             (fn [row line]
               (let [baseline (+ (* scale (:ascender font)) (* row line-h))]
                 (loop [[cp & more] (codepoints line), pen 0.0, out []]
                   (if-not cp
                     out
                     (let [g (tt/glyph-id font cp)
                           polys (for [contour (tt/outline font g) :when (seq contour)]
                                   (contour-polyline
                                    (mapv (fn [{:keys [x y on?]}] [(+ pen (* scale x)) (- baseline (* scale y)) on?])
                                          contour)))]
                       (recur more (+ pen (* scale (tt/advance font g))) (into out polys)))))))
             (str/split-lines text))))))

;; ---------------------------------------------------------------------------
;; Coverage by signed-area accumulation

(defn- add! [#?(:clj ^doubles a :cljd ^List a :default a) i v]
  (when (< -1 i (alength a)) (aset a i (+ (aget a i) v))))

(defn- edge!
  "Accumulate the signed area of the edge (x0,y0)-(x1,y1) into `a` (w x h,
  plus slack). Coordinates are pixels, x >= 0."
  [a w h x0 y0 x1 y1]
  (when-not (== y0 y1)
    (let [[dir ax ay bx by] (if (< y0 y1) [1.0 x0 y0 x1 y1] [-1.0 x1 y1 x0 y0])
          dxdy (/ (- bx ax) (- by ay))]
      (loop [y (max 0 (number/floor-int ay))
             x (if (< ay 0.0) (- ax (* ay dxdy)) ax)]
        (when (< y (min h (number/ceil-int by)))
          (let [row   (* y w)
                dy    (- (min (+ y 1.0) by) (max (* 1.0 y) ay))
                xnext (+ x (* dxdy dy))
                d     (* dy dir)
                lo    (min x xnext) hi (max x xnext)
                lo-f  (number/floor-int lo) hi-c (number/ceil-int hi)]
            (if (<= hi-c (inc lo-f))
              (let [xm (- (* 0.5 (+ x xnext)) lo-f)]
                (add! a (+ row lo-f) (- d (* d xm)))
                (add! a (+ row lo-f 1) (* d xm)))
              (let [s   (/ 1.0 (- hi lo))
                    lof (- lo lo-f)
                    a0  (* 0.5 s (- 1.0 lof) (- 1.0 lof))
                    hif (+ (- hi hi-c) 1.0)
                    am  (* 0.5 s hif hif)]
                (add! a (+ row lo-f) (* d a0))
                (if (= hi-c (+ lo-f 2))
                  (add! a (+ row lo-f 1) (* d (- 1.0 a0 am)))
                  (let [a1 (* s (- 1.5 lof))]
                    (add! a (+ row lo-f 1) (* d (- a1 a0)))
                    (doseq [xi (range (+ lo-f 2) (dec hi-c))]
                      (add! a (+ row xi) (* d s)))
                    (add! a (+ row (dec hi-c)) (* d (- 1.0 (+ a1 (* s (- hi-c lo-f 3))) am)))))
                (add! a (+ row hi-c) (* d am))))
            (recur (inc y) xnext)))))))

(defn coverage
  "Per-pixel coverage in [0, 1] of closed polylines in a w x h grid."
  [polylines w h]
  (let [a   (double-array (+ (* w h) 4))
        out (double-array (* w h))]
    (doseq [poly polylines
            [[x0 y0] [x1 y1]] (partition 2 1 poly)]
      (edge! a w h x0 y0 x1 y1))
    (loop [i 0 acc 0.0]
      (when (< i (* w h))
        (let [acc (+ acc (aget a i))]
          (aset out i (min 1.0 (abs acc)))
          (recur (inc i) acc))))
    out))

(defn dilate
  "Coverage grown by a disc of radius r px with a one-pixel soft edge: each
  pixel takes the strongest coverage within reach, weighted by distance."
  [#?(:clj ^doubles cov :cljd ^List cov :default cov) w h r]
  (let [reach  (inc r)
        taps   (vec (for [dy (range (- reach) (inc reach)) dx (range (- reach) (inc reach))
                          :let [wt (min 1.0 (max 0.0 (- (+ r 0.5) (number/sqrt (+ (* dx dx) (* dy dy))))))]
                          :when (pos? wt)]
                      [dx dy wt]))
        n      (count taps)
        dxs    (int-array (map first taps))
        dys    (int-array (map second taps))
        wts    (double-array (map #(nth % 2) taps))
        out    (double-array (* w h))]
    (dotimes [y h]
      (dotimes [x w]
        (loop [k 0 m 0.0]
          (if (< k n)
            (let [xx (+ x (aget dxs k)) yy (+ y (aget dys k))]
              (recur (inc k)
                     (if (and (< -1 xx w) (< -1 yy h))
                       (max m (* (aget wts k) (aget cov (+ xx (* yy w)))))
                       m)))
            (aset out (+ x (* y w)) m)))))
    out))

;; ---------------------------------------------------------------------------
;; A text layer

(defn render
  "A v1 text style and text as a straight RGBA8 image, tight around the ink
  plus the border. `font` is watermark.raster.truetype/parse's result."
  [font {:keys [size color opacity border border-color border-opacity]} text]
  (let [polys (layout font size text)
        pts   (apply concat polys)]
    (if (empty? pts)
      {:width 1 :height 1 :px (image/u8-array 4)}
      (let [pad (+ (or border 0) 2)
            x0 (number/floor-int (apply min (map first pts)))  y0 (number/floor-int (apply min (map second pts)))
            x1 (number/ceil-int (apply max (map first pts)))   y1 (number/ceil-int (apply max (map second pts)))
            w  (+ (- x1 x0) (* 2 pad)) h (+ (- y1 y0) (* 2 pad))
            shifted (mapv (fn [poly] (mapv (fn [[x y]] [(+ (- x x0) pad) (+ (- y y0) pad)]) poly)) polys)
            #?(:clj ^doubles cov :cljd ^List cov :default cov) (coverage shifted w h)
            halo?  (pos? (or border 0))
            #?(:clj ^doubles halo :cljd ^List halo :default halo) (if halo? (dilate cov w h border) (double-array 0))
            [tr tg tb] (color/rgb color)
            [br bg bb] (if halo? (color/rgb border-color) [0 0 0])
            px     (image/u8-array (* 4 w h))
            q8     (fn [v] (max 0 (min 255 (number/round-half-up (* 255.0 v)))))]
        (dotimes [i (* w h)]
          (let [ta (* (aget cov i) opacity)
                ba (if halo? (* (aget halo i) border-opacity) 0.0)
                a  (+ ta (* ba (- 1.0 ta)))]
            (when (pos? a)
              (let [mix (fn [t b] (/ (+ (* t ta) (* b ba (- 1.0 ta))) a))
                    o   (* 4 i)]
                (image/u8! px o       (q8 (/ (mix tr br) 255.0)))
                (image/u8! px (+ o 1) (q8 (/ (mix tg bg) 255.0)))
                (image/u8! px (+ o 2) (q8 (/ (mix tb bb) 255.0)))
                (image/u8! px (+ o 3) (q8 a))))))
        {:width w :height h :px px}))))
