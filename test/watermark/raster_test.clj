;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [watermark.raster :as raster]))

(set! *warn-on-reflection* true)

(defn- solid
  "A w x h image of one straight-alpha colour."
  [w h [r g b a]]
  {:width w :height h
   :px (byte-array (mapcat (fn [_] (map unchecked-byte [r g b a])) (range (* w h))))})

(defn- alpha-sum ^double [{:keys [^bytes px]}]
  (loop [i 3 acc 0.0] (if (< i (alength px)) (recur (+ i 4) (+ acc (/ (bit-and 0xff (aget px i)) 255.0))) acc)))

(defn- shoelace [[[x0 y0] [x1 y1] [x2 y2] [x3 y3]]]
  ;; corners are TL TR BL BR; walk TL TR BR BL
  (let [pts [[x0 y0] [x1 y1] [x3 y3] [x2 y2]]]
    (Math/abs (/ (reduce + (map (fn [[ax ay] [bx by]] (- (* ax by) (* bx ay))) pts (concat (rest pts) [(first pts)]))) 2.0))))

(deftest the-homography-maps-the-card-exactly
  (let [q [[10.0 5.0] [90.0 -3.0] [12.0 70.0] [85.0 80.0]]
        [a b c d e f g h i] (raster/homography q)
        at (fn [u v] (let [w (+ (* g u) (* h v) i)] [(/ (+ (* a u) (* b v) c) w) (/ (+ (* d u) (* e v) f) w)]))]
    (doseq [[[u v] [x y]] (map vector [[0 0] [1 0] [0 1] [1 1]] q)]
      (is (< (Math/abs (double (- x (first (at u v))))) 1e-9))
      (is (< (Math/abs (double (- y (second (at u v))))) 1e-9)))))

(deftest a-warped-card-covers-its-quad
  (let [card  (raster/area-scale (raster/premultiply (solid 40 20 [255 0 0 255])) 40 20 40 20)]
    (doseq [quad [[[2.3 1.7] [37.9 4.2] [3.1 18.4] [36.2 15.0]]      ; a flip in progress
                  [[21.0 1.0] [19.0 3.0] [21.0 19.0] [19.0 17.0]]]]  ; nearly edge-on, mirrored
      (let [img (raster/to-rgba8 (raster/warp card 40 20 40 20 quad) 40 20 1.0)]
        (is (< (Math/abs (- (alpha-sum img) (shoelace quad))) (* 0.05 (shoelace quad)))
            "coverage equals the quad's area within 5% (antialiased edges)")))))

(deftest area-scaling-keeps-colour-and-coverage
  (let [src {:width 4 :height 2 :px (byte-array (map unchecked-byte (concat [255 0 0 255] [255 0 0 255] [0 0 0 0] [0 0 0 0]
                                                                            [255 0 0 255] [255 0 0 255] [0 0 0 0] [0 0 0 0])))}
        img (raster/to-rgba8 (raster/area-scale (raster/premultiply src) 4 2 2 1) 2 1 1.0)]
    (is (= [255 0 0 255 0 0 0 0] (map #(bit-and 0xff %) (:px img)))
        "premultiplied averaging: no dark fringe where colour meets transparency"))
  (let [half (raster/to-rgba8 (raster/area-scale (raster/premultiply (solid 2 2 [0 0 255 255])) 2 2 1 1) 1 1 0.5)]
    (is (= [0 0 255 128] (map #(bit-and 0xff %) (:px half))) "opacity lands in alpha only")))

(deftest bitmaps-are-named-by-their-pixels
  (let [a (solid 2 2 [1 2 3 4])]
    (is (= (raster/bitmap-id a) (raster/bitmap-id (solid 2 2 [1 2 3 4]))))
    (is (not= (raster/bitmap-id a) (raster/bitmap-id (solid 4 1 [1 2 3 4]))) "the size is part of the name")
    (is (re-matches #"[0-9a-f]{64}" (raster/bitmap-id a)))))

(deftest text-is-a-tight-bitmap-with-its-border
  (if-let [font (some #(when (.isFile (io/file %)) %)
                      ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"])]
    (let [style {:font font :size 36 :color "white" :opacity 1.0 :border 2 :border-color "black" :border-opacity 0.6}
          img   (raster/render-text style "WM")]
      (is (< 60 (:width img) 110) (str "two bold capitals at 36 px, width " (:width img)))
      (is (< 25 (:height img) 45) (str "cap height plus border, height " (:height img)))
      (is (= (raster/bitmap-id img) (raster/bitmap-id (raster/render-text style "WM")))
          "the same text rasterizes to the same bitmap"))
    (println "  (skipped: no DejaVu font)")))
