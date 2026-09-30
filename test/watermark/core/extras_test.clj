;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.extras-test
  "Tags and a cover picture on a real render (the FFmpeg on PATH): the copy
  carries the profile's tags and, when asked, the render's frame at t as its
  cover, which file browsers show as its thumbnail; the video itself is
  untouched. Skipped without ffmpeg."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.config :as config]
            [watermark.home :as home]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.media.local :as local-media]
            [watermark.raster.local :as raster-local])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ctx {:tenant "local" :user "local"})

(defn- sys []
  (let [home (c/tmp-dir)]
    {:home         home
     :profiles-for (constantly (home/file-store {:home home}))
     :entitlements (features/community)
     :engine       (ffmpeg/ffmpeg-engine {:work-root (str home "/work")})
     :media        (local-media/local-media)
     :rasterizer   (raster-local/local-rasterizer {:work-root (str home "/work")})
     :secret-for   (constantly (byte-array 32))
     :font         (delay (c/font))
     :preview-dir  (str home "/work/previews")}))

(defn- ffprobe-json
  "Streams (with dispositions) and container tags of `path`, from the
  engine's own ffprobe."
  [s path]
  (let [ffprobe (get-in (engine/info (:engine s)) [:binaries :ffprobe :path])
        ^java.util.List cmd [ffprobe "-v" "error" "-show_entries"
                             "stream=index,codec_name:stream_disposition=attached_pic:format_tags"
                             "-of" "json" (str path)]
        p       (.start (ProcessBuilder. cmd))
        out     (slurp (.getInputStream p))]
    (.waitFor p)
    (json/read-str out :key-fn keyword)))

(defn- tag [probe k]
  (some (fn [[tk v]] (when (= (str/lower-case (name tk)) k) v)) (get-in probe [:format :tags])))

(deftest a-copy-carries-its-tags-and-cover
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [s    (sys)
          dir  (io/file (:home s) "videos")
          clip (str (io/file dir "clip.mp4"))]
      (.mkdirs dir)
      (engine/sample-video (:engine s) {:width 320 :height 180 :fps 25 :seconds 2} clip)
      (api/create-profile! s ctx "Tagged" {:logo {:enabled false}
                                           :output {:metadata {:title "Reel" :author "Studio A"
                                                               :copyright "© 2026 Studio A" :comment "  "}}})
      (let [{[result] :results} (api/run-batch! s ctx {:profile "tagged" :inputs [clip] :cover {:t 1.0}} {})
            out   (:output result)
            probe (ffprobe-json s out)]
        (is (= :done (:state result)) (pr-str result))
        (testing "the tags, the original's own dropped (Remove metadata is on by default)"
          (is (= ["Reel" "Studio A" "© 2026 Studio A"] (map #(tag probe %) ["title" "artist" "copyright"])))
          (is (nil? (tag probe "comment")) "a blank value isn't written"))
        (testing "the cover: a JPEG marked as the attached picture, after the video"
          (is (= [["h264" 0] ["mjpeg" 1]]
                 (for [st (:streams probe) :when (#{"h264" "mjpeg"} (:codec_name st))]
                   [(:codec_name st) (get-in st [:disposition :attached_pic])]))))
        (testing "the video is untouched: every frame, frame for frame"
          (is (= 50 (:frames (engine/probe (:engine s) out)))))
        (testing "the cover's still is gone"
          (is (empty? (filter #(str/ends-with? (.getName ^File %) ".cover.png") (.listFiles dir)))))
        (testing "latest keeps the settings, never the cover (it belongs to the video)"
          (is (nil? (:cover (:settings (api/get-profile s ctx config/latest-slug))))))))))

(deftest a-cover-needs-mp4-and-a-sane-time
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [s    (sys)
          clip (str (io/file (:home s) "clip.mp4"))]
      (engine/sample-video (:engine s) {:width 320 :height 180 :fps 25 :seconds 1} clip)
      (api/create-profile! s ctx "Mov" {:logo {:enabled false} :output {:container "mov"}})
      (let [err (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e [(:wmark/error (ex-data e)) (ex-message e)])))]
        (testing "MOV can't carry a cover: refused at planning, nothing rendered"
          (let [[kind msg] (err #(api/plan-batch s ctx {:profile "mov" :inputs [clip] :cover {:t 0.5}}))]
            (is (= :unsupported kind))
            (is (str/includes? msg "MP4"))))
        (testing "the same profile without a cover plans"
          (is (= 1 (count (:plans (api/plan-batch s ctx {:profile "mov" :inputs [clip]}))))))
        (testing "a time that isn't seconds from 0 is refused before anything is recorded"
          (doseq [t [-1 "12" nil ##NaN]]
            (is (= :invalid (first (err #(api/run-batch! s ctx {:profile "mov" :inputs [clip] :cover {:t t}} {}))))
                (pr-str t)))
          (is (= :not-found (first (err #(api/get-profile s ctx config/latest-slug))))
              "latest untouched"))))))
