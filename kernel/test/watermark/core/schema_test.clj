;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.schema-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [watermark.core.schema :as schema]))

(set! *warn-on-reflection* true)

(deftest the-exported-settings-schema-is-the-code's
  ;; native/settings.schema.json ships in the engine SDK for hosts without
  ;; malli; a schema change must update it (plus its top-level title)
  (is (= (json/read-str (slurp "native/settings.schema.json"))
         (assoc (json/read-str (json/write-str (schema/json-schema))) "title" "wmark settings (profiles)"))
      "regenerate native/settings.schema.json from watermark.core.schema/json-schema"))
