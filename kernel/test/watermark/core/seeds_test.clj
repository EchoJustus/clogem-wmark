;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.seeds-test
  (:require [clojure.test :refer [deftest is]]
            [watermark.core.seeds :as seeds]
            [watermark.golden :as golden]))

(def layer {:mode :subliminal :content "(c) Studio"})

(deftest seeds-are-keyed-and-reproducible
  (let [k1 (byte-array (range 32)) k2 (byte-array (reverse (range 32)))
        s  (fn [k fp i] ((seeds/seed-fn k fp) i layer))]
    (is (= (s k1 "fp-a" 0) (s k1 "fp-a" 0)) "same master + secret -> same schedule")
    (is (not= (s k1 "fp-a" 0) (s k1 "fp-b" 0)) "every master gets its own schedule")
    (is (not= (s k1 "fp-a" 0) (s k1 "fp-a" 1)) "every layer too")
    (is (not= (s k1 "fp-a" 0) (s k2 "fp-a" 0)) "unpredictable without the studio secret")))

(deftest golden-seeds
  (let [secret (byte-array (range 32))]
    (golden/check "seeds"
                  {:secret-bytes "00 01 02 ... 1f (32 bytes, 0..31)"
                   :seeds (into (sorted-map)
                                (for [ctx [(seeds/context "56f87b09" 0 layer)
                                           (seeds/context "56f87b09" 1 {:mode :random :content "Ünïcödé ✓"})
                                           (seeds/context "" 0 {:mode :continuous :content "x"})]]
                                  [ctx (seeds/keyed-seed secret ctx)]))})))
