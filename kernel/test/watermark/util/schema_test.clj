;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.schema-test
  "watermark.util.schema against malli, which reads the same schemas: the
  same verdicts, messages and decoded values on the golden corpus
  (kernel/test/golden/schema.edn), except where it differs on purpose."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [watermark.core.features :as features]
            [watermark.core.schema :as schema]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.render.schema :as spec-schema]
            [watermark.util.schema :as s]))

(set! *warn-on-reflection* true)

(deftest golden-schema
  (golden/check "schema" (inputs/schema-vectors)))

(def ^:private on-purpose
  "Cases where watermark.util.schema and malli disagree by design (see its
  docstring): malli finds the colour pattern in \"red\\n\"."
  #{:colour-with-newline})

(defn- malli-settings [x]
  (let [d (m/decode schema/Settings x (mt/transformer mt/json-transformer))]
    {:decoded d
     :errors  (me/humanize (m/explain schema/Settings (features/canonical-settings d)))}))

(deftest agrees-with-malli
  (let [{:keys [settings specs]} (inputs/schema-vectors)]
    (testing "settings: decoding and validation"
      (doseq [[k x] inputs/settings-cases
              :let [ours (settings k) theirs (malli-settings x)]]
        (if (on-purpose k)
          (is (and (:errors ours) (nil? (:errors theirs))) (str k ": stricter than malli, on purpose"))
          (is (= theirs ours) (str k)))))
    (testing "render specs"
      (doseq [[k x] @inputs/spec-cases
              :let [schema ({1 spec-schema/Spec 2 spec-schema/SpecV2} (:spec/version x))]
              :when schema]
        (is (= (me/humanize (m/explain schema x)) (:errors (specs k))) (str k))))))

(deftest reads-only-what-it-knows
  (doseq [unknown [:any [:or :int :string] [:set :int] [:and :int [:> 1]] even?]]
    (is (thrown? clojure.lang.ExceptionInfo (s/schema unknown)) (pr-str unknown))))

(deftest messages
  (let [errors (fn [schema x] (s/errors (s/schema schema) x))]
    (is (nil? (errors [:map [:a :int]] {:a 1})))
    (is (= {:a ["missing required key"]} (errors [:map [:a :int]] {})))
    (is (= ["should be a positive int"] (errors pos-int? 0)))
    (is (= ["should be 1"] (errors [:= 1] 1.0)) "an integer, not an equal float")
    (is (= ["should have at least 1 elements"] (errors [:vector {:min 1} :int] [])))
    (is (= [nil ["invalid tuple size 1, expected 2"]] (errors [:vector [:tuple :int :int]] [[1 2] [1]])))))
