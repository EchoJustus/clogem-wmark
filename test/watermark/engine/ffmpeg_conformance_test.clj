;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg-conformance-test
  "Real FFmpeg renders, measured against the reference semantics. Skipped
  when ffmpeg isn't installed."
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]))

(def settings-for
  (fn [logo]
    {:logo  {:path logo :anchor :center-left :offset {:x 40 :y 0} :width-ratio 0.25 :opacity 1.0
             :animation {:type :flip-y :every-s 1.0 :duration-s 0.5 :phase-s 0.5}}
     :texts [{:mode :scheduled :content "WM" :anchor :top-right :offset {:x 20 :y 20}
              :at [1.0 2.5] :duration-s 0.5 :opacity 1.0 :size-ratio 0.1}]}))

(def logo-region [0 0 320 360])
(def text-region [320 0 640 120])

(defn- check-clip [eng dir clip logo label]
  (let [{:keys [spec output outcome]} (c/render! eng (settings-for logo) clip {:out-dir dir})
        frames (c/gray-frames output 640 360)]
    (testing label
      (is (= {:status :done} outcome))
      (is (= 120 (count frames)) "4 s at 30 fps")
      (let [{:keys [width height centre]} (c/logo-deviation spec frames 640 logo-region)]
        (is (<= (:dw width) 3) (str "logo width within 3 px of the reference at every frame, worst " width))
        (is (<= (:dh height) 3) (str "logo height within 3 px, worst " height))
        (is (<= (:dcx centre) 2) (str "rotation axis stays put, worst " centre)))
      (when (c/system-font)
        (let [{:keys [measured reference]} (c/visible-frames spec frames 640 text-region "text-0")]
          (is (= reference measured) "text shows on exactly the reference frames")
          (is (= (concat (range 30 45) (range 75 90)) reference) "[1.0 s, 1.5 s) and [2.5 s, 3.0 s) at 30 fps"))))))

(deftest ffmpeg-renders-match-the-reference-semantics
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [dir (c/tmp-dir)
          eng (ffmpeg/ffmpeg-engine {:work-root dir})
          {:keys [clip logo]} (c/make-media! dir {})
          offset (:clip (c/make-media! dir {:start-s 0.5}))]
      (is (:available? (engine/info eng)))
      (check-clip eng dir clip logo "a clip starting at t=0")
      (is (= 0.5 (:start-s (engine/probe eng offset))))
      (check-clip eng dir offset logo "a clip whose video starts at t=0.5 s (frame-exact flip needs the setpts fix)"))))
