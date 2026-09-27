;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.native-v2-conformance-test
  "Render spec v2 through the C ABI: native/mock composites the host's
  bitmaps (it decodes the logo too, as PAM), and its frames are measured
  with the same harness and tolerances as FFmpeg's against the reference
  geometry. Part of M2's exit criterion. Skipped without a C compiler or
  ffmpeg (which makes the test media and decodes the frames)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg-conformance-test :as v1]
            [watermark.engine.native :as native]
            [watermark.engine.native-test :as native-test]))

(set! *warn-on-reflection* true)

(deftest the-c-mock-passes-v2-conformance
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (when-let [lib @native-test/mock-library]
      (let [dir  (c/tmp-dir)
            eng  (native/native-engine {:library lib})
            {:keys [clip logo]} (c/make-media! dir {})
            pam  (str (io/file dir "logo.pam"))
            _    (c/ffmpeg! "-i" logo "-pix_fmt" "rgba" pam)
            {:keys [spec v2 output outcome]}
            (c/render! eng (v1/settings-for pam) clip
                       {:out-dir dir :spec-version 2 :container "y4m" :codec :rawvideo :audio :none})
            frames (c/gray-frames output 640 360)]
        (is (= #{1 2} (get-in (engine/info eng) [:capabilities :spec-versions])))
        (is (= 2 (:spec/version v2)))
        (is (= {:status :done} outcome))
        (is (= 90 (count frames)) "the mock's probe: 3 s at 30 fps, on a white canvas")
        (let [{:keys [width height centre]} (c/logo-deviation spec frames 640 v1/logo-region)]
          (is (<= (:dw width) 3) (str "logo width within 3 px of the reference at every frame, worst " width))
          (is (<= (:dh height) 3) (str "logo height within 3 px, worst " height))
          (is (<= (:dcx centre) 2) (str "rotation axis stays put, worst " centre)))
        (testing "text"
          (let [{:keys [measured reference]} (c/visible-frames spec frames 640 v1/text-region "text-0")]
            (is (= reference measured) "text shows on exactly the reference frames")
            (is (= (concat (range 30 45) (range 75 90)) reference))))))))
