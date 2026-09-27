;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.image-test
  (:require [clojure.test :refer [deftest is]]
            [watermark.raster.image :as image]))

(set! *warn-on-reflection* true)

(defn- solid
  "A w x h image of one straight-alpha colour."
  [w h [r g b a]]
  {:width w :height h :px (byte-array (mapcat (fn [_] (map unchecked-byte [r g b a])) (range (* w h))))})

(defn- channels [{:keys [px]}] (map #(bit-and 0xff %) px))

(defn- alpha-sum ^double [{:keys [^bytes px]}]
  (loop [i 3 acc 0.0] (if (< i (alength px)) (recur (+ i 4) (+ acc (/ (bit-and 0xff (aget px i)) 255.0))) acc)))

(defn- shoelace [[[x0 y0] [x1 y1] [x2 y2] [x3 y3]]]
  ;; corners are TL TR BL BR; walk TL TR BR BL
  (let [pts [[x0 y0] [x1 y1] [x3 y3] [x2 y2]]]
    (Math/abs (double (/ (reduce + (map (fn [[ax ay] [bx by]] (- (* ax by) (* bx ay))) pts (concat (rest pts) [(first pts)])))
                         2.0)))))

(deftest the-homography-maps-the-card-exactly
  (let [q [[10.0 5.0] [90.0 -3.0] [12.0 70.0] [85.0 80.0]]
        [a b c d e f g h i] (image/homography q)
        at (fn [u v] (let [w (+ (* g u) (* h v) i)] [(/ (+ (* a u) (* b v) c) w) (/ (+ (* d u) (* e v) f) w)]))]
    (doseq [[[u v] [x y]] (map vector [[0 0] [1 0] [0 1] [1 1]] q)]
      (is (< (Math/abs (double (- x (first (at u v))))) 1e-9))
      (is (< (Math/abs (double (- y (second (at u v))))) 1e-9)))))

(deftest a-warped-card-covers-its-quad
  (let [card (image/area-scale (image/premultiply (solid 40 20 [255 0 0 255])) 40 20 40 20)]
    (doseq [quad [[[2.3 1.7] [37.9 4.2] [3.1 18.4] [36.2 15.0]]      ; a flip in progress
                  [[21.0 1.0] [19.0 3.0] [21.0 19.0] [19.0 17.0]]]]  ; nearly edge-on, mirrored
      (let [img (image/to-rgba8 (image/warp card 40 20 40 20 quad) 40 20 1.0)]
        (is (< (Math/abs (- (alpha-sum img) (shoelace quad))) (* 0.05 (shoelace quad)))
            "coverage equals the quad's area within 5% (antialiased edges)")))))

(deftest area-scaling-keeps-colour-and-coverage
  (let [src {:width 4 :height 2 :px (byte-array (map unchecked-byte (concat [255 0 0 255] [255 0 0 255] [0 0 0 0] [0 0 0 0]
                                                                            [255 0 0 255] [255 0 0 255] [0 0 0 0] [0 0 0 0])))}]
    (is (= [255 0 0 255 0 0 0 0] (channels (image/to-rgba8 (image/area-scale (image/premultiply src) 4 2 2 1) 2 1 1.0)))
        "premultiplied averaging: no dark fringe where colour meets transparency"))
  (is (= [0 0 255 128] (channels (image/to-rgba8 (image/area-scale (image/premultiply (solid 2 2 [0 0 255 255])) 2 2 1 1) 1 1 0.5)))
      "opacity lands in alpha only, halves rounded up"))

(deftest a-card-request-is-the-flat-box-or-its-warp
  (let [logo (solid 8 4 [10 20 30 255])
        flat (image/card {:card [4 2] :size [4 2] :quad [[0 0] [4 0] [0 2] [4 2]] :opacity 1.0}
                         (image/scale-card logo [4 2]))]
    (is (= (apply concat (repeat 8 [10 20 30 255])) (channels flat)) "the rest pose is the box, scaled"))
  (let [flip (image/card {:card [4 2] :size [6 4] :quad [[1.0 0.5] [5.0 1.0] [1.0 3.5] [5.0 3.0]] :opacity 1.0}
                         (image/scale-card (solid 8 4 [10 20 30 255]) [4 2]))]
    (is (= [6 4] [(:width flip) (:height flip)]))
    (is (zero? (nth (channels flip) 3)) "outside the card stays transparent")))
