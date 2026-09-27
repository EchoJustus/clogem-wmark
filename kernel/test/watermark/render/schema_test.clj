;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render.schema-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [watermark.render.schema :as spec-schema]
            [watermark.render.v2 :as v2]
            [watermark.render.v2-test :as v2-test]))

(set! *warn-on-reflection* true)

(defn- as-json
  "The schema as the C ABI's JSON sees it: keywords keep their namespace
  (\"spec/version\"), like watermark.engine.native's encoder."
  [x]
  (json/read-str (json/write-str (walk/postwalk #(if (keyword? %) (subs (str %) 1) %) x))))

(deftest the-exported-render-spec-schemas-are-the-code's
  ;; both ship in the engine SDK for engines without malli; a schema change
  ;; must update the file (its $id and title are added at export)
  (doseq [[version file] [[1 "native/render-spec.schema.json"] [2 "native/render-spec-v2.schema.json"]]]
    (is (= (json/read-str (slurp file))
           (assoc (as-json (spec-schema/json-schema version))
                  "$id"   (str "https://wmark.example/schemas/render-spec-" version ".json")
                  "title" (str "wmark render spec, version " version)))
        (str "regenerate " file " from watermark.render.schema/json-schema"))))

(defn- hex-id [k] (format "%064x" (Math/abs (long (hash k)))))

(defn- spec2 []
  (v2/assemble v2-test/spec
               (into {} (for [{:keys [key size kind]} (v2/raster-requests v2-test/spec)
                              :let [[w h] (if (= :text kind) [120 40] size)]]
                          [key {:bitmap (hex-id key) :width w :height h :path (str "/b/" (hex-id key) ".rgba")}]))))

(deftest v2-specs-validate
  (let [s (spec2)]
    (is (= s (spec-schema/validate! s)))
    (testing "malformed parts are named"
      (doseq [[bad where] [[(assoc-in s [:layers 0 :rest :bitmap] "logo.png") :layers]
                           [(assoc s :bitmaps {"ABC" {:width 1 :height 1 :path "/x"}}) :bitmaps]
                           [(assoc-in s [:layers 1 :kind] :text) :layers]
                           [(dissoc s :bitmaps) :bitmaps]]]
        (is (contains? (try (spec-schema/validate! bad) nil
                            (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))
                       where))))
    (testing "a v1 spec isn't a v2 spec, nor the reverse"
      (is (thrown? clojure.lang.ExceptionInfo (spec-schema/validate! (assoc s :spec/version 1))))
      (is (thrown? clojure.lang.ExceptionInfo (spec-schema/validate! (assoc v2-test/spec :spec/version 2)))))
    (testing "an unknown version says which versions exist"
      (is (= {:spec/version ["should be 1 or 2, not 3"]}
             (:errors (ex-data (try (spec-schema/validate! (assoc s :spec/version 3))
                                    (catch clojure.lang.ExceptionInfo e e)))))))))
