;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.pipeline-golden-test
  "Golden vectors for the use cases on fake ports (kernel/test/golden/pipeline.edn):
  the same plans, results, events and port calls on the JVM and the Dart VM
  (kernel/dart), where the pipeline runs on Futures."
  (:require [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.util.task :as task]))

(set! *warn-on-reflection* true)

(deftest golden-pipeline
  (golden/check "pipeline" (task/await (inputs/pipeline-vectors))))
