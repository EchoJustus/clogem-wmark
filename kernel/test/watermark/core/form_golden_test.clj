;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.form-golden-test
  "Golden vectors for the settings form (kernel/test/golden/form.edn): the
  rows every UI draws and what edits make of a profile. The Dart VM harness
  (kernel/dart) checks the same file."
  (:require [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]))

(set! *warn-on-reflection* true)

(deftest golden-form
  (golden/check "form" (inputs/form-vectors)))
