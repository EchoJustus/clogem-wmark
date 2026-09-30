;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.text-golden-test
  "Golden vectors for the library's own Unicode (kernel/test/golden/text.edn):
  the same categories, normal forms, lowercase and word boundaries on the
  JVM and the Dart VM (kernel/dart)."
  (:require [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]))

(set! *warn-on-reflection* true)

(deftest golden-text
  (golden/check "text" (inputs/text-vectors)))
