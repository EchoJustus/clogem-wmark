;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.compile-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.engine.ffmpeg.compile :as compile]
            [watermark.engine.ffmpeg.process :as process]
            [watermark.render :as render]))

(def media {:kind :video :width 1920 :height 1080 :fps-num 25 :fps-den 1 :frames 250
            :duration-s 10.0 :start-s 0.0 :vfr? false :has-audio? true})

(def x264-build #{"libx264" "libx265" "h264_nvenc"})

(defn- spec-for [settings & {:as more}]
  (render/build (merge {:settings     (resolve/deep-merge schema/defaults settings)
                        :media        media
                        :logo-media   {:width 400 :height 160}
                        :seed-fn      (constantly 42)
                        :entitlements (features/community)
                        :font         "/fonts/a.ttf"}
                       more)))

(defn- plan [settings & {:keys [media-overrides version encoders workdir spec-overrides]}]
  (let [m    (merge media media-overrides)
        spec (cond-> (spec-for settings :media m) spec-overrides spec-overrides)]
    (compile/compile-request {:spec spec :source "/in/clip.mov" :media m
                              :output {:path "/out/clip_wm.part.mp4" :container "mp4"}
                              :encode (:encode (resolve/deep-merge schema/defaults settings))
                              :strip-metadata? true}
                             {:ffmpeg "/opt/wmark/bin/ffmpeg" :version (or version {:major 7})
                              :encoders (or encoders x264-build) :workdir (or workdir "/tmp/job")})))

(defn- arg-after [argv flag] (second (drop-while #(not= flag %) argv)))

(deftest invocation-shape
  (let [p (plan {:logo {:path "/logos/l.png"}})]
    (is (= "/opt/wmark/bin/ffmpeg" (first (:argv p))) "absolute binary path, never a PATH lookup")
    (is (= "/out/clip_wm.part.mp4" (last (:argv p))))
    (is (some #{"-/filter_complex"} (:argv p)))
    (is (= ["-i" "/in/clip.mov" "-i" "/logos/l.png"]
           (->> (:argv p) (drop-while #(not= "-i" %)) (take 4)))
        "the logo is a single still frame, not a looped stream")
    (is (some #{"-map_metadata"} (:argv p)))
    (is (= 10000000 (:total-us p)))
    (is (= "passthrough" (arg-after (:argv p) "-fps_mode:v")) "no duplicated or dropped frames"))
  (is (= "passthrough" (arg-after (:argv (plan {} :version {:major 4 :minor 4})) "-vsync")))
  (is (some #{"-filter_complex_script"} (:argv (plan {} :version {:major 6}))))
  (is (some #{"-/filter_complex"} (:argv (plan {} :version {:major nil})))
      "nightly builds without a release number count as new"))

(deftest graph-shapes
  (testing "no logo, no text: a pass-through graph"
    (let [p (plan {:logo {:enabled false}})]
      (is (= "[0:v]null[vout]" (:graph p)))
      (is (= 1 (count (filter #{"-i"} (:argv p)))))))
  (testing "variable frame rate is normalised first"
    (is (str/starts-with? (:graph (plan {} :media-overrides {:vfr? true})) "[0:v]fps=fps=25/1[base]")))
  (testing "static logos skip the canvas and perspective"
    (let [g (:graph (plan {:logo {:path "/l.png" :animation {:type :none}}}))]
      (is (not (str/includes? g "perspective")))
      (is (str/includes? g "overlay=x=1666:y=964:eof_action=repeat") "1080 - 92 - 24; the still repeats")))
  (testing "the flip counts perspective's 1-based frames from 0"
    (is (str/includes? (:graph (plan {:logo {:path "/l.png"}})) "gte((in-1),1500)")))
  (testing "text goes through a file, never through the graph"
    (let [p (plan {:texts [{:mode :continuous :content "(c) Studio: don't; [strip]"}]}
                  :workdir "C:\\Users\\me\\job")]
      (is (= "(c) Studio: don't; [strip]"
             (some (fn [[k v]] (when (str/ends-with? k "text-0.txt") v)) (:files p))))
      (is (str/includes? (:graph p) "textfile=C\\\\:/Users/me/job/text-0.txt:expansion=none"))
      (is (not (str/includes? (:graph p) "don't"))))))

(deftest frame-alignment
  (testing "the flipping card is built from the video's own frames: no timestamp pairing"
    (let [g (:graph (plan {:logo {:path "/l.png"}} :media-overrides {:start-s 0.5}))]
      (is (str/includes? g "[0:v]split[main1][tick1]"))
      (is (str/includes? g "[tick1]crop="))
      (is (str/includes? g "[canvas1][still1]overlay="))
      (is (not (re-find #"setpts=expr='PTS\+" g)) "no dependence on the input's start time")))
  (testing "segment renders count frames globally"
    (let [g (:graph (plan {:logo {:path "/l.png"}
                           :texts [{:mode :scheduled :content "s" :at [1.0] :duration-s 1.0}]}
                          :spec-overrides #(assoc-in % [:timebase :first-frame] 100)))]
      (is (str/includes? g "gte(((in-1)+100),1500)"))
      (is (str/includes? g "between((n+100),25,49)")))))

(deftest timing-and-placement-expressions
  (is (nil? (compile/timing-expr {:type :always} "n")))
  (is (= "between(n,25,49)+between(n,100,124)"
         (compile/timing-expr {:type :windows :windows [{:start 25 :end 49} {:start 100 :end 124}]} "n")))
  (is (= "0" (compile/timing-expr {:type :windows :windows []} "n")))
  (is (= "gte(n,20)*lt(mod(n-20,45),2)" (compile/timing-expr {:type :periodic :offset 20 :period 45 :length 2} "n")))
  (is (not (str/includes? (compile/timing-expr {:type :windows :windows (for [i (range 300)] {:start (* i 10) :end (+ 5 (* i 10))})} "n")
                          "if("))
      "flat sums: FFmpeg's expression parser caps nesting depth at 100")
  (is (= ["(24)" "(h-th)+(-24)"]
         (compile/placement-exprs {:placement {:type :fixed :fx 0.0 :fy 1.0 :px 24 :py -24}} "n")))
  (is (= ["(w-tw)*0.5+(-5)" "(0)"]
         (compile/placement-exprs {:placement {:type :fixed :fx 0.5 :fy 0.0 :px -5 :py 0}} "n")))
  (is (= "(w-tw)*(0.05+0.9*mod(floor((n-20)/45)*3121+55,997)/997)"
         (first (compile/placement-exprs {:placement {:type :burst-scatter :margin 0.05 :modulus 997
                                                      :x {:a 3121 :b 55} :y {:a 1 :b 2}}
                                          :timing {:type :periodic :offset 20 :period 45 :length 2}}
                                         "n"))))
  (is (= "(w-tw)*(between(n,10,20)*0.25+between(n,40,50)*0.75)"
         (first (compile/placement-exprs {:placement {:type :per-window :points [[0.25 0.1] [0.75 0.9]]}
                                          :timing {:type :windows :windows [{:start 10 :end 20} {:start 40 :end 50}]}}
                                         "n")))))

(deftest encoding
  (testing "quality tiers map to CRF for x264/x265"
    (is (= "18" (arg-after (:argv (plan {})) "-crf")))
    (is (= "26" (arg-after (:argv (plan {:encode {:quality :compact}})) "-crf")))
    (let [argv (:argv (plan {:encode {:codec :hevc}}))]
      (is (= ["libx265" "23" "hvc1"] [(arg-after argv "-c:v") (arg-after argv "-crf") (arg-after argv "-tag:v")]))))
  (testing "engine-specific overrides win"
    (let [argv (:argv (plan {:encode {:ffmpeg {:video-codec "h264_nvenc" :crf 20}}}))]
      (is (= ["h264_nvenc" "20"] [(arg-after argv "-c:v") (arg-after argv "-cq")]))))
  (testing "an LGPL build without x264 falls back to the OS encoder with a bitrate target"
    (let [argv (:argv (plan {} :encoders #{"h264_mf"}))]
      (is (= "h264_mf" (arg-after argv "-c:v")))
      (is (= "6220800" (arg-after argv "-b:v")) "0.12 bits/pixel * 1920*1080 * 25 fps")))
  (testing "audio"
    (is (= "copy" (arg-after (:argv (plan {})) "-c:a")))
    (is (= "aac" (arg-after (:argv (plan {:encode {:audio :aac}})) "-c:a")))
    (is (not (some #{"-c:a"} (:argv (plan {} :media-overrides {:has-audio? false})))) "silent input: no audio map")))

(deftest codec-families
  (is (= #{:h264 :hevc} (compile/codecs-available #{"libx264" "hevc_nvenc"})))
  (is (= #{:h264} (compile/codecs-available #{"h264_mf"})))
  (is (= #{} (compile/codecs-available #{"mpeg4"}))))

(deftest reference-and-compiled-flip-agree-on-timing
  (let [spec (spec-for {:logo {:path "/l.png" :animation {:type :flip-y :every-s 2.0 :duration-s 1.0}}})
        logo (first (render/layers-of spec :image))
        {:keys [start period duration]} (:animation logo)
        g    (:graph (plan {:logo {:path "/l.png" :animation {:type :flip-y :every-s 2.0 :duration-s 1.0}}}))]
    (is (str/includes? g (format "gte((in-1),%d)*lt(mod((in-1)-%d,%d),%d)" start start period duration)))
    (is (= 575.0 (get-in logo [:animation :distance])) "2.5 logo widths")
    (is (str/includes? g "575/(575-0.5*W*ld(2))")
        "the camera distance comes from the spec, in pixels")))

(defn- filters-in
  "Filter names in a rendered graph: names follow a label, a comma or the
  start, once quoted expressions (which contain commas) are blanked out."
  [graph]
  (->> (re-seq #"(?:^|[\];,])([a-z][a-z0-9_]*)(?==|\[|,|;|$)" (str/replace graph #"'[^']*'" "''"))
       (map second)
       set))

(deftest the-capability-check-covers-every-filter-the-compiler-emits
  (let [used (->> [(plan {:logo {:path "/l.png"}} :media-overrides {:vfr? true})
                   (plan {:logo {:path "/l.png" :animation {:type :none}}})
                   (plan {:logo {:enabled false} :texts [{:mode :continuous :content "x"}]})
                   (plan {:logo {:enabled false}})]
                  (map (comp filters-in :graph))
                  (apply set/union))]
    (is (set/subset? #{"fps" "split" "crop" "drawbox" "perspective" "overlay" "drawtext" "null"} used)
        "the sample plans exercise every branch of the compiler")
    (is (empty? (set/difference used process/required-filters #{"drawtext"}))
        "a build missing any of these must be reported by `wmark doctor`, not fail mid-render")))
