;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.cli-golden-test
  "Golden vectors for the command line (kernel/test/golden/cli.edn): every
  command's output and exit code, the progress printer and the text helpers,
  the same on the JVM and the Dart VM (kernel/dart), so wmark and wmark-dart
  are one program (docs/adr/0014)."
  (:require [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.util.task :as task]))

(set! *warn-on-reflection* true)

(deftest golden-cli
  (golden/check "cli" (task/await (inputs/cli-vectors))))
