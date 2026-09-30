;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.process-test
  "FFmpeg as a process on the JVM. What it prints is parsed by the core
  library (kernel/test/watermark/ffmpeg/parse_test.clj)."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.engine.ffmpeg.process :as process]))

(set! *warn-on-reflection* true)

(deftest a-trial-encode-tells-a-working-encoder-from-a-listed-one
  (if-let [ffmpeg (try (let [{:keys [exit out]} (sh/sh "sh" "-c" "command -v ffmpeg")]
                         (when (zero? exit) (str/trim out)))
                       (catch Exception _ nil))]
    (do (is (process/trial-encode? ffmpeg ["-c:v" "mpeg4"]) "FFmpeg's own encoder always works")
        (is (not (process/trial-encode? ffmpeg ["-c:v" "no_such_encoder"]))))
    (println "  (skipped: ffmpeg not installed)")))
