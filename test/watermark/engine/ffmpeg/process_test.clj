;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.process-test
  "Parsing what `ffmpeg -version`, `-filters` and `-encoders` print, with lines
  copied from real builds."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.engine.ffmpeg.process :as process]))

(set! *warn-on-reflection* true)

(def ^:private filters-6-1
  ;; Ubuntu 24.04's 6.1.1: three flag columns
  (str/join "\n" ["Filters:"
                  "  T.. = Timeline support"
                  "  .S. = Slice threading"
                  "  ..C = Command support"
                  "  A = Audio input/output"
                  "  | = Source or sink filter"
                  " ... abench            A->A       Benchmark part of a filtergraph."
                  " T.C drawtext          V->V       Draw text on top of video frames using libfreetype library."
                  " TSC overlay           VV->V      Overlay a video source on top of the input."
                  " TS. perspective       V->V       Correct the perspective of video."
                  " ... color             |->V       Provide an uniformly colored input."]))

(def ^:private filters-9-0
  ;; BtbN's n9.0.1 build: the command-support column is gone
  (str/join "\n" ["Filters:"
                  "  T.. = Timeline support"
                  "  .S. = Slice threading"
                  "  A = Audio input/output"
                  "  ------"
                  " TS aap               AA->A      Apply Affine Projection algorithm to first audio stream."
                  " .. abench            A->A       Benchmark part of a filtergraph."
                  " T. drawtext          V->V       Draw text on top of video frames using libfreetype library."
                  " TS overlay           VV->V      Overlay a video source on top of the input."
                  " TS colorchannelmixer V->V       Adjust colors by mixing color channels."
                  " .. color             |->V       Provide an uniformly colored input."]))

(deftest filter-lists-from-every-supported-release
  (testing "FFmpeg up to 8: three flag columns"
    (is (= #{"abench" "drawtext" "overlay" "perspective" "color"} (process/parse-filters filters-6-1))))
  (testing "FFmpeg 9: two flag columns; the legend never counts as a filter"
    (is (= #{"aap" "abench" "drawtext" "overlay" "colorchannelmixer" "color"}
           (process/parse-filters filters-9-0)))))

(deftest encoder-lists-and-versions
  (is (= #{"libx264" "libopenh264" "h264_nvenc"}
         (process/parse-encoders (str/join "\n" ["Encoders:"
                                                 " V..... = Video"
                                                 " ------"
                                                 " V....D libx264              libx264 H.264 / AVC"
                                                 " V....D libopenh264          OpenH264 H.264 / AVC"
                                                 " V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)"
                                                 " A....D aac                  AAC (Advanced Audio Coding)"]))))
  (is (= {:major 9 :minor 0} (select-keys (process/parse-version "ffmpeg version n9.0.1-11-ge47273f4d9-20260831 Copyright")
                                          [:major :minor])))
  (is (= {:major 6 :minor 1} (select-keys (process/parse-version "ffmpeg version 6.1.1-3ubuntu5 Copyright")
                                          [:major :minor])))
  (is (nil? (:major (process/parse-version "ffmpeg version N-126342-gf88b741dbf-20260831 Copyright")))
      "nightly builds have no release number"))

(deftest a-trial-encode-tells-a-working-encoder-from-a-listed-one
  (if-let [ffmpeg (try (let [{:keys [exit out]} (sh/sh "sh" "-c" "command -v ffmpeg")]
                         (when (zero? exit) (str/trim out)))
                       (catch Exception _ nil))]
    (do (is (process/trial-encode? ffmpeg ["-c:v" "mpeg4"]) "FFmpeg's own encoder always works")
        (is (not (process/trial-encode? ffmpeg ["-c:v" "no_such_encoder"]))))
    (println "  (skipped: ffmpeg not installed)")))
