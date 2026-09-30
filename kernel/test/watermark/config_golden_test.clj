;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.config-golden-test
  "Golden vectors for the profile rules and the store codec
  (kernel/test/golden/profiles.edn): the same slugs, conflicts, fallback,
  provenance and document text on the JVM and the Dart VM (kernel/dart)."
  (:require [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]))

(set! *warn-on-reflection* true)

(deftest golden-profiles
  (golden/check "profiles" (inputs/profile-vectors)))
