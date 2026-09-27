;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.text-test
  "The bundled font (resources/fonts/wmark.ttf, Fira Sans Bold) through the
  kernel's TrueType reader and text rasterizer."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [watermark.raster :as raster]
            [watermark.raster.color :as color]
            [watermark.raster.text :as text]
            [watermark.raster.truetype :as tt])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.file Files)))

(set! *warn-on-reflection* true)

(def font (delay (tt/parse (Files/readAllBytes (.toPath (io/file "resources/fonts/wmark.ttf"))))))

(def style {:size 36 :color "white" :opacity 1.0 :border 2 :border-color "black" :border-opacity 0.6})

(defn- alpha [{:keys [width px]} x y] (bit-and 0xff (aget ^bytes px (+ 3 (* 4 (+ x (* y width)))))))

(deftest the-font-reads-like-fonttools-reads-it
  ;; expected values from fontTools 4.x on the same file
  (let [f @font]
    (is (= {:units-per-em 1000 :ascender 935 :descender -265 :line-gap 0 :metrics 2707 :glyphs 2708}
           (select-keys f [:units-per-em :ascender :descender :line-gap :metrics :glyphs])))
    (is (= [301 847 1 13] (let [g (tt/glyph-id f (int \W))] [g (tt/advance f g) (count (tt/outline f g)) (count (first (tt/outline f g)))])))
    (is (= [418 3 33] (let [g (tt/glyph-id f 0xE9) o (tt/outline f g)] [g (count o) (reduce + (map count o))]))
        "é is a composite glyph: base plus accent, transformed")
    (is (= [1109 905] (let [g (tt/glyph-id f 0x416)] [g (tt/advance f g)])) "Cyrillic Ж")
    (is (zero? (tt/glyph-id f 0x4E2D)) "no CJK: the missing-glyph box")))

(deftest cff-fonts-are-refused-plainly
  (is (thrown-with-msg? ExceptionInfo #"CFF"
                        (tt/parse (byte-array (map unchecked-byte (concat (map int "OTTO") (repeat 12 0))))))))

(deftest text-is-a-tight-bitmap-with-its-border
  (let [img (text/render @font style "WM")]
    ;; W and M advance 847 + 786 units: about 59 px at 36 px, plus 4 px of padding each side
    (is (< 58 (:width img) 72) (str "two bold capitals at 36 px, width " (:width img)))
    (is (< 28 (:height img) 40) (str "cap height plus border, height " (:height img)))
    (is (zero? (alpha img 0 0)) "the corner is padding")
    (is (= (seq (:px img)) (seq (:px (text/render @font style "WM")))) "deterministic")
    (testing "the border grows the ink"
      (let [bare (text/render @font (assoc style :border 0) "WM")]
        (is (< (:width bare) (:width img)))))
    (testing "lines stack, a line height apart"
      (let [two (text/render @font style "WM\nWM")]
        (is (< (* 1.9 (- (:height img) 8)) (- (:height two) 8)))))
    (is (= [1 1] ((juxt :width :height) (text/render @font style "   "))) "no ink: an empty 1x1 bitmap")))

(deftest colours-are-css-names-or-hex
  (is (= [255 255 255] (color/rgb "White")))
  (is (= [0x1e 0x90 0xff] (color/rgb "dodgerblue")))
  (is (= [0x12 0xab 0xef] (color/rgb "#12ABef")))
  (is (thrown-with-msg? ExceptionInfo #"Unknown colour" (color/rgb "notacolour"))))

(deftest draw-dispatches-on-the-request
  (let [img (raster/draw {:kind :text :text "x" :style (assoc style :font "f.ttf")}
                         {:font (fn [path] (is (= "f.ttf" path)) @font)})]
    (is (pos? (:width img)))))
