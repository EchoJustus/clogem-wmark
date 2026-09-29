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
            [watermark.render :as render]
            [watermark.render.v2 :as v2]))

(set! *warn-on-reflection* true)

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

;; ---------------------------------------------------------------------------
;; Render spec v2: overlay only

(defn- v2-plan
  "Compile the v2 spec of `settings`, with made-up bitmaps of the requested
  sizes (text bitmaps are 120 x 40)."
  [settings & {:keys [media-overrides encoders]}]
  (let [m    (merge media media-overrides)
        spec (spec-for settings :media m)
        s2   (v2/assemble spec (into {} (for [{:keys [key size kind]} (v2/raster-requests spec)
                                              :let [[w h] (if (= :text kind) [120 40] size)]]
                                          [key {:bitmap (str "b" (hash key)) :width w :height h
                                                :path (str "/scratch/b" (hash key) ".rgba")}])))]
    (compile/compile-request-v2 {:spec s2 :source "/in/clip.mov" :media m
                                 :output {:path "/out/clip_wm.part.mp4" :container "mp4"}
                                 :encode (:encode (resolve/deep-merge schema/defaults settings))
                                 :strip-metadata? true}
                                {:ffmpeg "/opt/wmark/bin/ffmpeg" :version {:major 9}
                                 :encoders (or encoders x264-build) :workdir "/tmp/job"})))

(def flip {:path "/l.png" :animation {:type :flip-y :every-s 2.0 :duration-s 0.4}})

(deftest v2-composites-with-overlay-only
  (let [used (->> [(v2-plan {:logo flip} :media-overrides {:vfr? true})
                   (v2-plan {:logo flip :texts [{:mode :continuous :content "x"}]})
                   (v2-plan {:logo {:enabled false}})]
                  (map (comp filters-in :graph))
                  (apply set/union))]
    (is (= #{"fps" "overlay" "null"} used) "the sample plans exercise every branch")
    (is (set/subset? used process/required-filters-v2)
        "an LGPL build (no perspective, no drawtext needed) can run every v2 plan")))

(deftest v2-draws-one-overlay-per-flip-frame
  (let [{:keys [graph argv]} (v2-plan {:logo flip})
        spec  (spec-for {:logo flip})
        {:keys [start period duration]} (:animation (first (render/layers-of spec :image)))]
    (is (= 10 duration) "0.4 s at 25 fps")
    (is (= (inc duration) (count (re-seq #"overlay=" graph))) "the rest pose plus one per frame of the flip")
    (is (= (inc duration) (count (filter #{"rawvideo"} argv))) "each overlay reads its own still bitmap")
    (is (str/includes? graph (format "1-gte(n,%d)*lt(mod(n-%d,%d),%d)" start start period duration))
        "the rest pose hides exactly while a flip frame shows")
    (doseq [p [0 (dec duration)]]
      (is (str/includes? graph (format "gte(n,%d)*eq(mod(n-%d,%d),%d)" start start period p))))
    (is (str/includes? graph "format=yuv444") "whole-pixel positions: yuv420 would round them to even")))

(deftest v2-text-sits-where-v1-text-would-floored
  (let [g (:graph (v2-plan {:logo {:enabled false}
                            :texts [{:mode :continuous :content "x" :anchor :bottom-right :offset {:x 24 :y 24}}]}))]
    (is (str/includes? g "x='floor((W-w)+(-24))'"))
    (is (str/includes? g "y='floor((H-h)+(-24))'"))))

(deftest v2-moving-text-counts-frames-the-way-overlay-does
  ;; overlay's per-frame x/y see n one ahead of its enable timeline (framesync
  ;; counts the frame as consumed first): positions use (n-1), enable uses n
  (let [spec {:spec/version 2 :canvas {:width 640 :height 360}
              :timebase {:fps-num 30 :fps-den 1 :frames 120 :first-frame 0}
              :bitmaps {"b" {:width 100 :height 40 :path "/scratch/b.rgba"}}
              :layers [{:id "t" :kind :bitmap :bitmap "b"
                        :placement {:type :per-window :points [[0.25 0.5]]}
                        :timing {:type :windows :windows [{:start 9 :end 30}]}}]}
        g    (:graph (compile/compile-request-v2
                      {:spec spec :source "/in/clip.mov" :media media
                       :output {:path "/out/o.part.mp4" :container "mp4"} :encode {:codec :h264}}
                      {:ffmpeg "/opt/wmark/bin/ffmpeg" :version {:major 9} :encoders x264-build :workdir "/tmp/job"}))]
    (is (str/includes? g "x='floor((W-w)*(between((n-1),9,30)*0.25))'"))
    (is (str/includes? g "enable='between(n,9,30)'"))
    (is (str/includes? g "eval=frame"))))

(deftest an-lgpl-build-encodes-with-its-software-encoders
  (testing "hardware encoders an LGPL build lists may be absent at run time"
    (is (= "libopenh264" (arg-after (:argv (plan {} :encoders #{"libopenh264" "h264_nvenc" "h264_qsv"})) "-c:v")))
    (is (= "libkvazaar" (arg-after (:argv (plan {:encode {:codec :hevc}} :encoders #{"libkvazaar" "hevc_nvenc"})) "-c:v"))))
  (is (= "libx264" (arg-after (:argv (plan {} :encoders #{"libx264" "libopenh264" "h264_nvenc"})) "-c:v"))
      "x264 still comes first where it exists"))

(deftest encoders-a-machine-cant-run-are-dropped
  (let [tried (atom [])
        works #{"libopenh264" "hevc_nvenc"}
        usable (compile/usable-encoders #{"h264_mf" "libopenh264" "h264_nvenc" "hevc_mf" "hevc_nvenc" "mpeg4"}
                                        (fn [codec enc] (swap! tried conj [codec enc]) (contains? works enc)))]
    (is (= #{"libopenh264" "h264_nvenc" "hevc_nvenc" "mpeg4"} usable)
        "Media Foundation fails here (Windows N or Server): the next encoder takes over")
    (is (= [[:h264 "h264_mf"] [:h264 "libopenh264"] [:hevc "hevc_mf"] [:hevc "hevc_nvenc"]] @tried)
        "in preference order, stopping at the first that works; unlisted encoders are never tried")
    (is (= "libopenh264" (compile/pick-encoder {} usable))))
  (testing "VideoToolbox may fall back to Apple's software encoder (VMs have no hardware one)"
    (is (= ["-c:v" "h264_videotoolbox" "-b:v" "110592" "-allow_sw" "1"]
           (compile/video-args {:codec :h264} "h264_videotoolbox" {:width 256 :height 144} 25)))))

;; ---------------------------------------------------------------------------
;; Previews: one frame of the same plan, as a PNG (docs/adr/0011, section 5)

(defn- still [settings frame & {:keys [v2?]}]
  (let [spec (spec-for settings :media media)]
    ((if v2? compile/compile-request-v2 compile/compile-request)
     {:spec (if v2?
              (v2/assemble spec (into {} (for [{:keys [key size kind]} (v2/raster-requests spec)
                                               :let [[w h] (if (= :text kind) [120 40] size)]]
                                           [key {:bitmap (str "b" (hash key)) :width w :height h
                                                 :path (str "/scratch/b" (hash key) ".rgba")}])))
              spec)
      :source "/in/clip.mov" :media media
      :output {:path "/work/previews/p.png" :frame frame}
      :encode (:encode (resolve/deep-merge schema/defaults settings))
      :strip-metadata? true}
     {:ffmpeg "/opt/wmark/bin/ffmpeg" :version {:major 7} :encoders x264-build :workdir "/tmp/job"})))

(deftest a-preview-is-the-render-cut-to-one-frame
  (doseq [v2? [false true]]
    (let [settings {:logo flip :texts [{:mode :continuous :content "x"}]}
          full     ((if v2? v2-plan plan) settings)
          p        (still settings 45 :v2? v2?)]
      (testing (if v2? "v2" "v1")
        (is (str/starts-with? (:graph p) (:graph full)) "the same graph, with one chain added")
        (is (str/ends-with? (:graph p) "[vout]trim=start_frame=45:end_frame=46[still]"))
        (is (= ["-map" "[still]" "-an" "-frames:v" "1" "-c:v" "png" "-pix_fmt" "rgb24"]
               (->> (:argv p) (drop-while #(not= "-map" %)) (take 9))))
        (is (= "/work/previews/p.png" (last (:argv p))))
        (is (= "passthrough" (arg-after (:argv p) "-fps_mode:v")) "frames pass through, so trim counts n")
        (is (not-any? #{"-c:a" "-crf" "-movflags"} (:argv p)) "no encoding, no audio")
        (is (empty? (set/difference (filters-in (:graph p))
                                    (if v2? process/required-filters-v2 process/required-filters)
                                    process/preview-filters #{"drawtext"}))
            "every filter a preview adds is one :preview checks for")))))

;; ---------------------------------------------------------------------------
;; Extras: tags and a cover picture (docs/FFMPEG_STRATEGY.md)

(defn- extras-plan
  "The v1 (or v2) plan of `settings` for a request with `extras` added."
  [settings extras & {:keys [v2? output]}]
  (let [spec (spec-for settings :media media)
        env  {:ffmpeg "/opt/wmark/bin/ffmpeg" :version {:major 9} :encoders x264-build :workdir "/tmp/job"}
        req  (merge {:source "/in/clip.mov" :media media
                     :output (or output {:path "/out/clip_wm.part.mp4" :container "mp4"})
                     :encode (:encode (resolve/deep-merge schema/defaults settings))
                     :strip-metadata? true}
                    extras)]
    (if v2?
      (compile/compile-request-v2
       (assoc req :spec (v2/assemble spec (into {} (for [{:keys [key size kind]} (v2/raster-requests spec)
                                                         :let [[w h] (if (= :text kind) [120 40] size)]]
                                                     [key {:bitmap (str "b" (hash key)) :width w :height h
                                                           :path (str "/scratch/b" (hash key) ".rgba")}]))))
       env)
      (compile/compile-request (assoc req :spec spec) env))))

(defn- inputs-of [argv] (->> argv (partition 2 1) (keep (fn [[a b]] (when (= "-i" a) b))) vec))

(defn- after [argv flag] (vec (take 4 (drop-while #(not= flag %) argv))))

(deftest tags-reach-ffmpeg-in-a-file-never-on-the-command-line
  (let [md   {:comment "line one\nline two" :copyright "© 2026 Studio A=x;y" :author "工作室 A" :title "  "}
        p    (extras-plan {} {:metadata md})
        argv (:argv p)
        [meta-file text] (some (fn [[f t]] (when (str/ends-with? f "metadata.txt") [f t])) (:files p))]
    (is (= ";FFMETADATA1\nartist=工作室 A\ncopyright=© 2026 Studio A\\=x\\;y\ncomment=line one\\\nline two\n" text)
        "a fixed order, author as artist, special characters escaped, blank values left out")
    (is (= "/tmp/job/metadata.txt" meta-file))
    (is (not-any? #(re-find #"Studio|工作室" %) argv) "no user text in argv (a non-UTF-8 locale would mangle it)")
    (let [i (.indexOf ^java.util.List argv "ffmetadata")]
      (is (= ["-f" "ffmetadata" "-i" meta-file] (subvec argv (dec i) (+ i 3))) "read as FFmpeg's ffmetadata format"))
    (testing "the original's metadata removed: the tags file's alone"
      (is (= ["-map_metadata" "1"] (take 2 (after argv "-map_metadata"))))
      (is (not= "-map_metadata" (nth (after argv "-map_metadata") 2 nil))))
    (testing "the original's kept: the tags file first, so the tags win a clash"
      (is (= ["-map_metadata" "1" "-map_metadata" "0"]
             (after (:argv (extras-plan {} {:metadata md :strip-metadata? false})) "-map_metadata"))))
    (testing "no tags: no file, and the mapping is what it was"
      (let [q (extras-plan {} {:metadata {:title " "}})]
        (is (not-any? #(str/ends-with? (key %) "metadata.txt") (:files q)))
        (is (= ["-map_metadata" "-1"] (take 2 (after (:argv q) "-map_metadata"))))))))

(deftest a-cover-is-the-input-after-the-others-embedded-as-the-second-video-stream
  (doseq [v2? [false true]
          :let [settings {:logo {:path "/logo.png"}}
                p        (extras-plan settings {:cover {:path "/out/clip_wm.part.mp4.cover.png"}} :v2? v2?)
                argv     (:argv p)
                ins      (inputs-of argv)]]
    (testing (if v2? "v2" "v1")
      (is (= "/out/clip_wm.part.mp4.cover.png" (peek ins)) "the last input")
      (is (= (compile/cover-args (dec (count ins)))
             (->> argv (drop-while #(not= (str (dec (count ins)) ":v") %)) (cons "-map")
                  (take (count (compile/cover-args 0)))))
          "mapped, as JPEG, marked attached_pic, overriding the video's own codec, pixel format and tag")
      (is (< (.indexOf ^java.util.List argv "-c:v") (.indexOf ^java.util.List argv "-c:v:1"))
          "the stream-specific options come after the general ones they override")
      (is (= "/out/clip_wm.part.mp4" (last argv)))))
  (testing "no cover asked: the argv doesn't change"
    (is (= (:argv (plan {})) (:argv (extras-plan {} {})))))
  (testing "a preview never embeds a cover"
    (let [argv (:argv (extras-plan {} {:cover {:path "/c.png"}} :output {:path "/work/previews/p.png" :frame 3}))]
      (is (not-any? #{"/c.png" "-disposition:v:1"} argv)))))
