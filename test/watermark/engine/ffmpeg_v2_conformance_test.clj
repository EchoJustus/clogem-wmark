;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg-v2-conformance-test
  "Render spec v2 (M2): the host draws the flip and the text, FFmpeg only
  composites. Measured with v1's harness and tolerances against v1's
  reference geometry, on the ffmpeg on PATH and on the pinned LGPL build the
  bundles ship (`bb ffmpeg`, or WMARK_FFMPEG_LGPL=<its bin dir>). M2's exit
  criterion is that the LGPL build passes. Skipped without ffmpeg."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.engine.ffmpeg-conformance-test :as v1])
  (:import (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(defn- lgpl-bin []
  ;; `bb ffmpeg` fetches the LGPL build the bundles ship (a GPL fetch, from
  ;; before LGPL became the default or with :variant :gpl, has no LGPLv3 text)
  (or (System/getenv "WMARK_FFMPEG_LGPL")
      (let [d (io/file "target/ffmpeg/linux-x64")]
        (when (.isFile (io/file d "licenses" "COPYING.LGPLv3")) (str (io/file d "bin"))))))

(defn- check-v2 [eng dir clip logo label]
  (let [{:keys [spec v2 output outcome]} (c/render! eng (v1/settings-for logo) clip {:out-dir dir :spec-version 2})
        frames (c/gray-frames output 640 360)]
    (testing label
      (is (= 2 (:spec/version v2)))
      (is (= {:status :done} outcome))
      (is (= 120 (count frames)) "4 s at 30 fps")
      (let [{:keys [width height centre]} (c/logo-deviation spec frames 640 v1/logo-region)]
        (is (<= (:dw width) 3) (str "logo width within 3 px of the reference at every frame, worst " width))
        (is (<= (:dh height) 3) (str "logo height within 3 px, worst " height))
        (is (<= (:dcx centre) 2) (str "rotation axis stays put, worst " centre)))
      (when (c/font)
        (let [{:keys [measured reference]} (c/visible-frames spec frames 640 v1/text-region "text-0")]
          (is (= reference measured) "text shows on exactly the reference frames"))))))

(defn- both-clips [eng dir label]
  (let [{:keys [clip logo]} (c/make-media! dir {})
        offset (:clip (c/make-media! dir {:start-s 0.5}))]
    (check-v2 eng dir clip logo (str label ", a clip starting at t=0"))
    (check-v2 eng dir offset logo (str label ", a clip whose video starts at t=0.5 s"))))

(deftest v2-renders-match-the-reference-semantics
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [dir (c/tmp-dir)]
      (both-clips (ffmpeg/ffmpeg-engine {:work-root dir}) dir "the ffmpeg on PATH"))))

(defn- moving-text
  "Two text layers that move: keyed-looking windows with a place per window
  (ending mid-clip, where overlay's frame counter once put them at 0,0), and
  bursts at a place per burst."
  [spec]
  (let [[a b] (filter #(= :text (:kind %)) (:layers spec))
        others (remove #(= :text (:kind %)) (:layers spec))]
    (assoc spec :layers
           (into (vec others)
                 [(assoc a :timing {:type :windows :windows [{:start 9 :end 30} {:start 42 :end 59} {:start 69 :end 92}]}
                           :placement {:type :per-window :points [[0.44 0.07] [0.34 0.21] [0.91 0.41]]})
                  (assoc b :timing {:type :periodic :offset 5 :period 30 :length 30}
                           :placement {:type :burst-scatter :margin 0.05 :modulus 997
                                       :x {:a 331 :b 17} :y {:a 509 :b 101}})]))))

(deftest moving-text-lands-where-draw-at-says
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [dir (c/tmp-dir)
          {:keys [clip]} (c/make-media! dir {})]
      (doseq [[label eng] (cond-> [["the ffmpeg on PATH" (ffmpeg/ffmpeg-engine {:work-root dir})]]
                            (lgpl-bin) (conj ["the pinned LGPL build" (ffmpeg/ffmpeg-engine {:work-root dir :ffmpeg (lgpl-bin)})]))
              keep-id [0 1]]
        (let [only (fn [spec] (let [s (moving-text spec)
                                    texts (filter #(= :text (:kind %)) (:layers s))]
                                (assoc s :layers (vec (concat (remove #(= :text (:kind %)) (:layers s))
                                                              [(nth texts keep-id)])))))
              {:keys [v2 output outcome]} (c/render! eng {:logo {:enabled false}
                                                          :texts [{:mode :continuous :content "PER WINDOW" :opacity 1.0}
                                                                  {:mode :continuous :content "BURST" :opacity 1.0}]}
                                                     clip {:out-dir dir :spec-version 2 :edit-spec only})
              frames (c/gray-frames output 640 360)
              layer  (first (:layers v2))]
          (testing (str label ", " (name (get-in layer [:placement :type])))
            (is (= {:status :done} outcome))
            (is (= 120 (count frames)))
            (is (= [] (c/v2-layer-problems v2 frames 640 (:id layer))))))))))

(deftest an-lgpl-ffmpeg-passes-v2-conformance
  (if-not (and (c/ffmpeg-available?) (lgpl-bin))
    (if (System/getenv "WMARK_REQUIRE_LGPL")
      (is (lgpl-bin) "CI fetches the pinned LGPL FFmpeg: this test must run there, not skip")
      (println "  (skipped: no pinned LGPL FFmpeg; run bb ffmpeg)"))
    (let [dir (c/tmp-dir)
          eng (ffmpeg/ffmpeg-engine {:work-root dir :ffmpeg (lgpl-bin)})
          {:keys [clip logo]} (c/make-media! dir {})]
      (is (:available? (engine/info eng)) "an LGPL build is a usable engine now")
      (is (= #{2} (get-in (engine/info eng) [:capabilities :spec-versions]))
          "no perspective: it takes host-drawn v2 specs only")
      (is (= :unsupported
             (try (c/render! eng (v1/settings-for logo) clip {:out-dir dir}) nil
                  (catch ExceptionInfo e (:wmark/error (ex-data e)))))
          "a v1 flip is refused up front, never approximated")
      (both-clips eng dir "the pinned LGPL build"))))
