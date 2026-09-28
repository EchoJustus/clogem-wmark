;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg-preview-test
  "Previews are the output (docs/adr/0011, section 5): frame n of a preview
  equals frame n of a full render. The render is lossless H.264, the
  preview a PNG, so they're compared in luma, where the only difference is
  the rounding of two colour conversions. Skipped without ffmpeg."
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.engine.ffmpeg-conformance-test :as v1]))

(set! *warn-on-reflection* true)

(defn- differences
  "{:mean :within-3} of the luma difference of two frames."
  [^bytes a ^bytes b]
  (let [n (alength a)
        d (fn [i] (Math/abs (- (bit-and 0xff (aget a (int i))) (bit-and 0xff (aget b (int i))))))
        ds (map d (range n))]
    {:mean     (/ (double (reduce + ds)) n)
     :within-3 (/ (double (count (filter #(<= % 3) ds))) n)}))

(defn- check [eng dir clip settings frames spec-version label]
  (let [{:keys [output outcome]} (c/render! eng settings clip {:out-dir dir :spec-version spec-version})
        rendered (c/gray-frames output 640 360)]
    (is (= {:status :done} outcome))
    (doseq [n frames]
      (let [{:keys [png] :as p} (c/preview! eng settings clip n {:out-dir dir :spec-version spec-version})
            [still]  (c/gray-frames png 640 360)]
        (testing (str label ", frame " n)
          (is (= {:status :done} (:outcome p)))
          (is (some? still) "one PNG frame")
          (when still
            (let [{:keys [mean within-3]} (differences still (nth rendered n))]
              (is (< mean 1.0) (str "mean luma difference " mean))
              (is (> within-3 0.999) (str "share of pixels within 3 levels " within-3)))))))
    (testing (str label ": the comparison notices a different frame")
      (let [{:keys [png]} (c/preview! eng settings clip 0 {:out-dir dir :spec-version spec-version})
            [still] (c/gray-frames png 640 360)
            flip    (some #(when (> (:mean (differences still (nth rendered %))) 1.0) %) (range 1 (count rendered)))]
        (is (some? flip) "some frame of the render differs from frame 0, and the check sees it")))))

(deftest a-preview-is-that-frame-of-the-render
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [dir (c/tmp-dir)
          eng (ffmpeg/ffmpeg-engine {:work-root dir})
          {:keys [clip logo]} (c/make-media! dir {})
          settings (assoc-in (v1/settings-for logo) [:texts 0 :opacity] 1.0)]
      (is (contains? (get-in (engine/info eng) [:capabilities :preview]) :frame))
      ;; frames before, during and after the first flip, and inside and
      ;; outside the text's window (settings-for's schedule)
      (check eng dir clip settings [0 31 45 60 100] 2 "render spec v2")
      (when (contains? (get-in (engine/info eng) [:capabilities :spec-versions]) 1)
        (check eng dir clip settings [0 45 100] 1 "render spec v1")))))

(deftest the-sample-clip-is-a-video-to-preview-on
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [dir  (c/tmp-dir)
          eng  (ffmpeg/ffmpeg-engine {:work-root dir})
          path (engine/sample-video eng {:width 720 :height 1280 :fps 25 :seconds 2} (str dir "/sample.mp4"))
          info (engine/probe eng path)]
      (is (= [:video 720 1280 50] ((juxt :kind :width :height :frames) info))))))
