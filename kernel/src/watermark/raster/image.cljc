;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.image
  "Pixel arithmetic for render spec v2: scaling, the projective warp of the
  flipping card, and quantization. Pure and portable: the same pixels on
  every host (docs/adr/0006).

  An image is {:width w :height h :px u8} with u8 a byte array of straight
  (non-premultiplied) RGBA, row-major. Intermediate results are
  premultiplied doubles in [0, 1], so averaging never darkens edges where
  colour meets transparency. Every rounding goes through watermark.util.num."
  (:require [watermark.util.num :as num]))

#?(:clj (set! *warn-on-reflection* true))

(defn u8-array
  "A zeroed byte array for n channel values."
  [n]
  (byte-array n))

(defn u8
  "Channel i of an RGBA byte array, as 0..255."
  [#?(:clj ^bytes a :default a) i]
  (bit-and 0xff (aget a i)))

(defn u8!
  "Set channel i of an RGBA byte array to v in 0..255."
  [#?(:clj ^bytes a :default a) i v]
  (aset a i #?(:clj (unchecked-byte v) :default v)))

(defn premultiply
  "Straight RGBA8 -> premultiplied doubles."
  [{:keys [px width height]}]
  (let [n   (* width height)
        out (double-array (* 4 n))]
    (dotimes [i n]
      (let [o (* 4 i)
            a (/ (u8 px (+ o 3)) 255.0)]
        (aset out o       (* a (/ (u8 px o) 255.0)))
        (aset out (+ o 1) (* a (/ (u8 px (+ o 1)) 255.0)))
        (aset out (+ o 2) (* a (/ (u8 px (+ o 2)) 255.0)))
        (aset out (+ o 3) a)))
    out))

(defn area-scale
  "Resample premultiplied `src` (sw x sh) to dw x dh by area averaging: each
  target pixel is the coverage-weighted mean of the source pixels under it."
  [#?(:clj ^doubles src :default src) sw sh dw dh]
  (let [out (double-array (* 4 dw dh))
        acc (double-array 4)
        fx  (/ (* 1.0 sw) dw)
        fy  (/ (* 1.0 sh) dh)
        area (* fx fy)]
    (dotimes [ty dh]
      (let [y0 (* ty fy) y1 (+ y0 fy)]
        (dotimes [tx dw]
          (let [x0 (* tx fx) x1 (+ x0 fx)]
            (dotimes [c 4] (aset acc c 0.0))
            (loop [sy (num/floor-int y0)]
              (when (and (< sy y1) (< sy sh))
                (let [wy (- (min y1 (inc sy)) (max y0 sy))]
                  (loop [sx (num/floor-int x0)]
                    (when (and (< sx x1) (< sx sw))
                      (let [wgt (* wy (- (min x1 (inc sx)) (max x0 sx)))
                            o   (* 4 (+ sx (* sy sw)))]
                        (dotimes [c 4]
                          (aset acc c (+ (aget acc c) (* wgt (aget src (+ o c)))))))
                      (recur (inc sx)))))
                (recur (inc sy))))
            (let [o (* 4 (+ tx (* ty dw)))]
              (dotimes [c 4] (aset out (+ o c) (/ (aget acc c) area))))))))
    out))

(defn homography
  "3x3 matrix [a b c d e f g h 1] mapping the unit square onto a quad given
  as [top-left top-right bottom-left bottom-right]: (u, v) -> (x, y) with
  x = (a u + b v + c) / (g u + h v + 1), y likewise (Heckbert 1989, 2.2.3)."
  [[[x0 y0] [x1 y1] [x3 y3] [x2 y2]]]
  (let [dx1 (- x1 x2) dx2 (- x3 x2) dx3 (+ (- x0 x1) (- x2 x3))
        dy1 (- y1 y2) dy2 (- y3 y2) dy3 (+ (- y0 y1) (- y2 y3))]
    (if (and (zero? dx3) (zero? dy3))
      [(* 1.0 (- x1 x0)) (* 1.0 (- x3 x0)) (* 1.0 x0) (* 1.0 (- y1 y0)) (* 1.0 (- y3 y0)) (* 1.0 y0) 0.0 0.0 1.0]
      (let [det (- (* dx1 dy2) (* dx2 dy1))
            g   (/ (- (* dx3 dy2) (* dx2 dy3)) det)
            h   (/ (- (* dx1 dy3) (* dx3 dy1)) det)]
        [(+ (- x1 x0) (* g x1)) (+ (- x3 x0) (* h x3)) (* 1.0 x0)
         (+ (- y1 y0) (* g y1)) (+ (- y3 y0) (* h y3)) (* 1.0 y0)
         g h 1.0]))))

(defn invert3
  "Inverse (up to scale) of a 3x3 matrix, by its adjugate."
  [[a b c d e f g h i]]
  [(- (* e i) (* f h)) (- (* c h) (* b i)) (- (* b f) (* c e))
   (- (* f g) (* d i)) (- (* a i) (* c g)) (- (* c d) (* a f))
   (- (* d h) (* e g)) (- (* b g) (* a h)) (- (* a e) (* b d))])

(defn- sample!
  "Bilinear sample of premultiplied `src` at (x, y) in pixel-centre
  coordinates into `out`. Outside the image counts as transparent, so the
  card's edges come out antialiased."
  [#?(:clj ^doubles src :default src) sw sh x y #?(:clj ^doubles out :default out)]
  (let [x0 (num/floor-int x) y0 (num/floor-int y)
        fx (- x x0) fy (- y y0)]
    (dotimes [c 4] (aset out c 0.0))
    (doseq [[xx yy wgt] [[x0 y0 (* (- 1.0 fx) (- 1.0 fy))] [(inc x0) y0 (* fx (- 1.0 fy))]
                         [x0 (inc y0) (* (- 1.0 fx) fy)] [(inc x0) (inc y0) (* fx fy)]]]
      (when (and (pos? wgt) (<= 0 xx) (< xx sw) (<= 0 yy) (< yy sh))
        (let [o (* 4 (+ xx (* yy sw)))]
          (dotimes [c 4] (aset out c (+ (aget out c) (* wgt (aget src (+ o c)))))))))))

(defn warp
  "Draw premultiplied `src` (sw x sh), stretched over the unit square, into a
  dw x dh bitmap so that its corners land on `quad` (bitmap coordinates,
  [top-left top-right bottom-left bottom-right]). Returns premultiplied
  doubles."
  [src sw sh dw dh quad]
  (let [[a b c d e f g h i] (invert3 (homography quad))
        out (double-array (* 4 dw dh))
        px  (double-array 4)]
    (dotimes [py dh]
      (dotimes [pxl dw]
        (let [X (+ pxl 0.5) Y (+ py 0.5)
              w (+ (* g X) (* h Y) i)]
          (when-not (zero? w)
            (let [u (/ (+ (* a X) (* b Y) c) w)
                  v (/ (+ (* d X) (* e Y) f) w)]
              (when (and (< -0.01 u 1.01) (< -0.01 v 1.01))
                (sample! src sw sh (- (* u sw) 0.5) (- (* v sh) 0.5) px)
                (let [o (* 4 (+ pxl (* py dw)))]
                  (dotimes [k 4] (aset out (+ o k) (aget px k))))))))))
    out))

(defn- q8
  "A [0, 1] value as 0..255, halves up."
  [v]
  (max 0 (min 255 (num/round-half-up (* 255.0 v)))))

(defn to-rgba8
  "Premultiplied doubles -> a straight RGBA8 image, alpha times `opacity`."
  [#?(:clj ^doubles pm :default pm) w h opacity]
  (let [n  (* w h)
        px (u8-array (* 4 n))]
    (dotimes [i n]
      (let [o (* 4 i)
            a (aget pm (+ o 3))]
        (when (pos? a)
          (dotimes [k 3] (u8! px (+ o k) (q8 (/ (aget pm (+ o k)) a))))
          (u8! px (+ o 3) (q8 (* a opacity))))))
    {:width w :height h :px px}))

(defn scale-card
  "A decoded source area-scaled to the card size [cw ch], premultiplied."
  [decoded [cw ch]]
  (area-scale (premultiply decoded) (:width decoded) (:height decoded) cw ch))

(defn card
  "One image raster request (watermark.render.v2/image-requests) as RGBA8,
  from its source scaled to the card's size (`scale-card`): warped onto the
  request's quad when it isn't the flat box, with the layer's opacity."
  [{:keys [card size quad opacity]} scaled]
  (let [[cw ch] card
        [dw dh] size]
    (if (= quad [[0 0] [cw 0] [0 ch] [cw ch]])
      (to-rgba8 scaled dw dh opacity)
      (to-rgba8 (warp scaled cw ch dw dh quad) dw dh opacity))))
