;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.dart-cli-test
  "wmark-dart on real frames (docs/adr/0014, section 4): the Dart host's
  adapters render a clip through the command line, and the conformance
  harness measures the frames against the reference semantics and against
  what the JVM's wmark renders from the same profile, secret and FFmpeg.

  Needs wmark-dart (`bb dart-cli`, or WMARK_DART_CLI) and ffmpeg; skipped
  without them, unless WMARK_REQUIRE_DART_CLI=1 (CI's dart job)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.app :as app]
            [watermark.config :as config]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.home :as home]
            [watermark.util.fs :as wfs])
  (:import (java.nio.file Files)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:private edition {:edition :community :entitlements-fn (fn [_] (features/community))})
(def ^:private ctx {:tenant "local" :user "local"})

(defn- dart-cli []
  (let [f (io/file (or (System/getenv "WMARK_DART_CLI") "target/dart-cli/wmark-dart"))]
    (when (.canExecute f) (.getAbsolutePath f))))

(defn- lgpl-ffmpeg []
  (let [f (io/file (or (System/getenv "WMARK_FFMPEG_LGPL") "target/ffmpeg/linux-x64/bin/ffmpeg"))]
    (when (.canExecute f) (.getAbsolutePath f))))

(defn- settings [logo]
  {:logo   {:path logo :anchor :center-left :offset {:x 40 :y 0} :width-ratio 0.25 :opacity 1.0
            :animation {:type :flip-y :every-s 2.0 :duration-s 0.5 :phase-s 0.5}}
   :texts  [{:mode :scheduled :content "WM" :anchor :top-right :offset {:x 20 :y 20}
             :at [1.0 2.5] :duration-s 0.5 :opacity 1.0 :size-ratio 0.1}]
   :encode {:quality :archival}})

(def ^:private logo-region [0 0 320 360])
(def ^:private text-region [320 0 640 120])

(defn- homes!
  "Two homes with one studio secret and one profile, \"Conformance\": what
  wmark-dart and wmark each render from."
  [dir logo]
  (let [a (str (io/file dir "home-dart"))
        b (str (io/file dir "home-jvm"))]
    (wfs/studio-secret! a)
    (io/make-parents (io/file b "secret.key"))
    (io/copy (io/file a "secret.key") (io/file b "secret.key"))
    (doseq [h [a b]]
      (config/create-profile! (home/file-store {:home h}) "Conformance" (settings logo)))
    [a b]))

(defn- same-frames? [xs ys]
  (and (= (count xs) (count ys))
       (every? true? (map #(Arrays/equals ^bytes %1 ^bytes %2) xs ys))))

(defn- run-args [ff spec-version out clip]
  ["--ffmpeg" ff "--render-spec" (str spec-version)
   "run" "-p" "Conformance" "--progress" "none" "-o" out clip])

(defn- check [dart ff spec-version dir label]
  (let [{:keys [clip logo]} (c/make-media! dir {})
        [a b]  (homes! dir logo)
        out-a  (str (io/file dir "out-dart"))
        out-b  (str (io/file dir "out-jvm"))
        ;; the reference: the render spec the JVM plans for the same request,
        ;; as v1 (a v2 spec is drawn from it; its geometry is the v1 spec's)
        spec   (:spec (first (:plans (api/plan-batch (app/system edition {:home b :render-spec 1})
                                                     ctx {:profile "Conformance" :inputs [clip]
                                                          :settings {:output {:dir (str (io/file dir "planned"))}}}))))
        ran    (apply sh/sh dart "--home" a (run-args ff spec-version out-a clip))
        jvm    (binding [*out* (java.io.StringWriter.)]
                 (app/run-cli edition (into ["--home" b] (run-args ff spec-version out-b clip))))
        frames (c/gray-frames (str (io/file out-a "clip-0_wm.mp4")) 640 360)]
    (testing label
      (is (= 0 (:exit ran)) (str (:out ran) (:err ran)))
      (is (= 0 jvm))
      (is (= 120 (count frames)) "4 s at 30 fps")
      (is (same-frames? frames (c/gray-frames (str (io/file out-b "clip-0_wm.mp4")) 640 360))
          "wmark-dart's frames are the JVM's, pixel for pixel")
      (let [{:keys [width height centre]} (c/logo-deviation spec frames 640 logo-region)]
        (is (<= (:dw width) 3) (str "logo width within 3 px of the reference at every frame, worst " width))
        (is (<= (:dh height) 3) (str "logo height within 3 px, worst " height))
        (is (<= (:dcx centre) 2) (str "rotation axis stays put, worst " centre)))
      (let [{:keys [measured reference]} (c/visible-frames spec frames 640 text-region "text-0")]
        (is (= reference measured) "text shows on exactly the reference frames")
        (is (= (concat (range 30 45) (range 75 90)) reference)))
      (is (empty? (.list (io/file a "work"))) "no scratch folder is left"))))

(deftest wmark-dart-renders-what-the-reference-and-the-jvm-say
  (let [dart (dart-cli)
        ff   (:path (:ffmpeg (ffmpeg/locate-binaries {})))]
    (cond
      (and dart ff)
      (do
        (testing "doctor: the engine is found and ready"
          (let [{:keys [exit out]} (sh/sh dart "--home" (c/tmp-dir) "--ffmpeg" ff "doctor")]
            (is (= 0 exit) out)
            (is (str/includes? out "[ready]") out)))
        (check dart ff 1 (c/tmp-dir) "render spec 1: FFmpeg draws, on the ffmpeg on PATH")
        (check dart ff 2 (c/tmp-dir) "render spec 2: the Dart host draws, FFmpeg composites")
        (if-let [lgpl (lgpl-ffmpeg)]
          (check dart lgpl 2 (c/tmp-dir) "render spec 2 on the pinned LGPL FFmpeg the downloads ship")
          (println "  (skipped the LGPL build: run bb ffmpeg)")))

      (System/getenv "WMARK_REQUIRE_DART_CLI")
      (is false (str "wmark-dart or ffmpeg is missing (wmark-dart: " dart ", ffmpeg: " ff "); run bb dart-cli"))

      :else
      (println "  (skipped: build wmark-dart with bb dart-cli, and install ffmpeg)"))))
