;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.prng-test
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.golden :as golden]
            [watermark.util.prng :as prng])
  (:import (java.util SplittableRandom)))

(deftest matches-splittable-random-draw-for-draw
  (doseq [seed (concat [0 1 -1 42 Long/MAX_VALUE Long/MIN_VALUE]
                       (let [r (java.util.Random. 7)] (repeatedly 300 #(.nextLong r))))]
    (let [ref (SplittableRandom. (long seed)) g (prng/generator seed)]
      (dotimes [_ 3]
        (is (= (.nextLong ref) (prng/next-long! g)))
        (doseq [bound [1 2 7 16 997 4000 (bit-shift-left 1 40) (+ 3 (bit-shift-left 1 40)) Long/MAX_VALUE]]
          (is (= (.nextLong ref (long bound)) (prng/next-below! g bound)) (str "bound " bound)))
        (is (= (.nextDouble ref) (prng/next-double! g)))))))

(deftest golden-sequences
  (golden/check "prng"
                (into (sorted-map)
                      (for [seed [0 1 -1 42 7046029254386353131]]
                        (let [g (prng/generator seed)]
                          [seed {:longs   (vec (repeatedly 4 #(prng/next-long! g)))
                                 :below   (vec (for [b [7 16 997 1000003]] (prng/next-below! g b)))
                                 :doubles (vec (repeatedly 2 #(prng/next-double! g)))}])))))
