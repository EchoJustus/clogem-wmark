;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.resolve-test
  (:require [clojure.test :refer [deftest is]]
            [watermark.core.resolve :as resolve]))

(set! *warn-on-reflection* true)

(deftest layering
  (let [{:keys [settings provenance]}
        (resolve/layer [[:defaults  {:logo {:anchor :bottom-right :offset {:x 24 :y 24}} :texts []}]
                        [:profile   {:logo {:offset {:x 8}} :texts [{:mode :continuous :content "a"}]}]
                        [:overrides {:logo {:anchor nil :offset {:y 99}}}]])]
    (is (= {:x 8 :y 99} (get-in settings [:logo :offset])) "maps merge deeply")
    (is (= :bottom-right (get-in settings [:logo :anchor])) "nil means not given")
    (is (= [{:mode :continuous :content "a"}] (:texts settings)) "vectors replace")
    (is (= {[:logo :anchor] :defaults [:logo :offset :x] :profile
            [:logo :offset :y] :overrides [:texts] :profile}
           provenance))))
