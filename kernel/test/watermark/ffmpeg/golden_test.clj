;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.ffmpeg.golden-test
  "Golden vectors for the FFmpeg plan compiler and parsers
  (kernel/test/golden/ffmpeg.edn): the same filtergraphs, argv and scratch
  files on the JVM and the Dart VM (kernel/dart)."
  (:require [clojure.test :refer [deftest]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]))

(set! *warn-on-reflection* true)

(deftest golden-ffmpeg
  (golden/check "ffmpeg" (inputs/ffmpeg-vectors)))
