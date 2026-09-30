;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.adapters-golden-test
  "Golden vectors for what every host's adapters compute alike
  (kernel/test/golden/adapters.edn): SHA-256, media fingerprints, output
  names, the executable search and its warnings, and a realized v2 spec, on
  the JVM and the Dart VM (docs/adr/0014)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.util.task :as task])
  (:import (java.nio.file Files)))

(set! *warn-on-reflection* true)

(deftest golden-adapters
  (golden/check "adapters" (task/await (inputs/adapter-vectors-task
                                        (Files/readAllBytes (.toPath (io/file "resources/fonts/wmark.ttf")))))))
