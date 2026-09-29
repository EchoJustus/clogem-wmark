;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.golden-inputs
  "What the golden vectors (kernel/test/golden/*.edn) are computed from, on
  every runtime: the JVM tests check them with watermark.golden, and the
  Dart VM harness (kernel/dart) checks the same files with the same inputs.

  Each function takes what only the host can provide (the font's bytes) and
  returns the value its golden file holds."
  (:require [watermark.core.features :as features]
            [watermark.core.form :as form]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.core.seeds :as seeds]
            [watermark.ffmpeg.graph :as graph]
            [watermark.ffmpeg.parse :as parse]
            [watermark.ffmpeg.plan :as plan]
            [watermark.raster :as raster]
            [watermark.raster.image :as image]
            [watermark.render :as render]
            [watermark.render.schema :as spec-schema]
            [watermark.render.v2 :as v2]
            [watermark.util.num :as number]
            [watermark.util.prng :as prng]))

#?(:clj (set! *warn-on-reflection* true))

(defn mismatch
  "Where a and b first differ, as [path a b], or nil when they are the same
  data. Numbers must also be of the same kind: the JVM's = tells 24 from
  24.0, the Dart VM's doesn't, and golden files are data for ports."
  ([a b] (mismatch [] a b))
  ([path a b]
   (cond
     (and (map? a) (map? b))
     (some (fn [k]
             (if (and (contains? a k) (contains? b k))
               (mismatch (conj path k) (get a k) (get b k))
               [(conj path k) (get a k ::missing) (get b k ::missing)]))
           (distinct (concat (keys a) (keys b))))
     (and (sequential? a) (sequential? b))
     (if (= (count a) (count b))
       (some identity (map-indexed (fn [i [x y]] (mismatch (conj path i) x y)) (map vector a b)))
       [path (str (count a) " items") (str (count b) " items")])
     (and (number? a) (number? b))
     (when-not (and (= (integer? a) (integer? b)) (= a b)) [path a b])
     :else
     (when-not (= a b) [path a b]))))

(defn strict= [a b] (nil? (mismatch a b)))

(defn- r3 [v] (/ (number/round-half-up (* 1000.0 v)) 1000.0))

;; ---------------------------------------------------------------------------
;; prng.edn

(defn prng []
  (into (sorted-map)
        (for [seed [0 1 -1 42 7046029254386353131]]
          ;; draws in this order: ClojureDart evaluates a map literal's
          ;; values in another order than the JVM
          (let [g       (prng/generator seed)
                longs   (vec (repeatedly 4 #(prng/next-long! g)))
                below   (vec (for [b [7 16 997 1000003]] (prng/next-below! g b)))
                doubles (vec (repeatedly 2 #(prng/next-double! g)))]
            [seed {:longs longs :below below :doubles doubles}]))))

;; ---------------------------------------------------------------------------
;; seeds.edn

(defn secret
  "The golden secret: 32 bytes, 0..31."
  []
  (let [a (image/u8-array 32)]
    (dotimes [i 32] (image/u8! a i i))
    a))

(defn seeds []
  (let [k (secret)]
    {:secret-bytes "00 01 02 ... 1f (32 bytes, 0..31)"
     :seeds (into (sorted-map)
                  (for [ctx [(seeds/context "56f87b09" 0 {:mode :subliminal :content "(c) Studio"})
                             (seeds/context "56f87b09" 1 {:mode :random :content "Ünïcödé ✓"})
                             (seeds/context "" 0 {:mode :continuous :content "x"})]]
                    [ctx (seeds/keyed-seed k ctx)]))}))

;; ---------------------------------------------------------------------------
;; render-basic.edn and render-v2.edn

(def ^:private media {:width 1280 :height 720 :fps-num 30000 :fps-den 1001 :frames 5400})

(defn basic-spec
  "The render-basic spec: a flipping logo top right, a continuous and a
  scheduled text. `more`: render/build arguments to add (:first-frame)."
  ([] (basic-spec {}))
  ([more]
  (render/build (merge
                {:settings     (resolve/deep-merge
                                schema/defaults
                                {:logo  {:path "/logos/l.png" :anchor :top-right :offset {:x 30 :y 20}
                                         :animation {:type :flip-y :every-s 5.0 :duration-s 0.8}}
                                 :texts [{:mode :continuous :content "(c) Studio" :anchor :bottom-center}
                                         {:mode :scheduled :content "Scheduled" :at [2.0 90.5] :duration-s 1.5}]})
                 :media        media
                 :logo-media   {:width 400 :height 160}
                 :seed-fn      (constantly 42)
                 :entitlements (features/community)
                 :font         "/fonts/a.ttf"}
                more))))

(defn- layer [spec id] (first (filter #(= id (:id %)) (:layers spec))))

(defn render-basic []
  (let [spec (basic-spec)
        logo (layer spec "logo")]
    {:spec    spec
     ;; reference samples every engine must reproduce
     :logo-bounds (into (sorted-map)
                        (for [n [0 149 150 155 160 165 170 173 174 300]]
                          [n (mapv r3 (render/logo-bounds logo n))]))
     :scheduled-frames (let [t (layer spec "text-1")]
                         (vec (filter #(render/active? (:timing t) %) (range (:frames media)))))}))

(defn synthetic-logo
  "400 x 160 straight RGBA8, pixel (x, y) = [(5x) mod 256, (11y) mod 256,
  (x + y) mod 256, 128 + (x + 2y) mod 128]."
  []
  (let [w 400 h 160 px (image/u8-array (* 4 w h))]
    (dotimes [y h]
      (dotimes [x w]
        (let [o (* 4 (+ x (* y w)))]
          (image/u8! px o       (mod (* 5 x) 256))
          (image/u8! px (+ o 1) (mod (* 11 y) 256))
          (image/u8! px (+ o 2) (mod (+ x y) 256))
          (image/u8! px (+ o 3) (+ 128 (mod (+ x (* 2 y)) 128))))))
    {:width w :height h :px px}))

(defn- geometry
  "A raster request without its source and style: what the kernel decides."
  [{:keys [kind] :as req}]
  (case kind
    :image (-> (select-keys req [:key :card :size :box :opacity])
               (assoc :quad (mapv #(mapv r3 %) (:quad req))))
    :text  (select-keys req [:key :text])))

(defn render-v2
  "render-v2.edn, with `font` the parsed resources/fonts/wmark.ttf. Also
  returns, under ::bitmaps, the ids draw-all gives (not in the file)."
  [font]
  (let [spec    (basic-spec)
        logo    (synthetic-logo)
        sources {:decoded (constantly logo) :font (constantly font)}
        reqs    (v2/raster-requests spec)
        results (into {} (for [req reqs
                               :let [img (raster/draw req sources)
                                     id  (raster/bitmap-id img)]]
                           [(:key req) {:bitmap id :width (:width img) :height (:height img)
                                        :path (str id ".rgba")}]))
        s2      (spec-schema/validate! (v2/assemble spec results))]
    {:requests (mapv geometry reqs)
     :spec     s2
     ;; what each layer draws on sample frames: the rest pose, frames inside
     ;; the first flip, a scheduled text window
     :draw-at  (into (sorted-map)
                     (for [n [0 59 60 103 104 149 150 151 160 173 174 2713 2756 2757]]
                       [n (into (sorted-map)
                                (for [layer (:layers s2)
                                      :let [d (v2/draw-at s2 layer n)]
                                      :when d]
                                  [(:id layer) d]))]))
     ::draw-all {:draw (mapv (comp :bitmap results :key) reqs)
                 :draw-all (mapv raster/bitmap-id (raster/draw-all reqs sources))}}))

;; ---------------------------------------------------------------------------
;; form.edn: the settings form every UI draws, and edits to a profile

(def ^:private form-profile
  {:logo   {:opacity 0.5 :anchor :top-left :path "/logos/l.png"}
   :output {:suffix "_mark" :metadata {:copyright "(c) Studio"}}
   :texts  [{:mode :continuous :content "(c) Studio" :size-ratio 0.04}
            {:mode :scheduled :content "Scheduled" :at [2.0 90.5] :duration-s 1.5}
            {:mode :subliminal :content "Canary" :frames 2}]})

(defn- resolved [profile base]
  (assoc (resolve/layer [[:defaults schema/defaults] [:profile profile]]) :base {:kind base}))

(def ^:private form-edits
  [{:op :set :id "logo.opacity" :value "70"}
   {:op :set :id "logo.animation.every-s" :value "30"}
   {:op :set :id "output.dir" :value " /out "}
   {:op :set :id "output.container" :value "mkv"}
   {:op :set :id "texts.1.at" :value "5, 12.5"}
   {:op :set :id "texts.0.color" :value "#FF8800"}
   {:op :set :id "texts.1.mode" :value "continuous"}
   {:op :unset :id "logo.opacity"}
   {:op :unset :id "output.metadata.copyright"}
   {:op :add-layer :mode "random"}
   {:op :remove-layer :index 0}
   {:op :move-layer :index 1 :delta -1}
   ;; refused
   {:op :set :id "logo.opacity" :value "150"}
   {:op :set :id "logo.offset.x" :value "1.5"}
   {:op :set :id "texts.0.color" :value "red;"}
   {:op :set :id "logo.bogus" :value "1"}
   {:op :unset :id "texts.0.content"}
   {:op :set :id "texts.5.content" :value "x"}])

(defn- outcome [f]
  (try {:ok (f)}
       (catch #?(:clj clojure.lang.ExceptionInfo :cljd cljd.core/ExceptionInfo) e
         {:error (ex-message e)})))

(defn form-vectors
  "form.edn: the model of a profile with every kind of row (all entitled,
  then the community edition's locks), and what each edit makes of it."
  []
  (let [r         (resolved form-profile :named)
        effective (:settings r)
        community (features/community)]
    {:model     (form/model r)
     :community (form/model r {:entitled? #(features/entitled? community %)})
     ;; from the last run: only where each value comes from changes
     :last-run  (let [m (form/model (resolved {:logo {:opacity 0.5}} :latest))]
                  (into (sorted-map) (for [c (:categories m) row (:rows c)] [(:id row) (:source row)])))
     :edits     (vec (for [e form-edits]
                       [e (outcome #(schema/validate! (form/edit form-profile effective e)))]))}))

;; ---------------------------------------------------------------------------
;; ffmpeg.edn: FFmpeg plans (filtergraph, argv, files), what FFmpeg prints
;; read into data, and numbers as FFmpeg is given them

(def ^:private decimal-cases
  [0.0 -0.0 1.0 24.0 0.85 0.12 0.05 0.9 1e-4 1e-7 1e7 1e21 123456789.125
   (+ 0.1 0.2) (/ 2.0 3.0) (/ 30000.0 1001.0) 29.97 -2.5 2.2250738585072014e-308 1.7976931348623157e308
   24 -3 1000003])

(def ^:private filters-text
  {:ffmpeg-6-1 [" T.. = Timeline support"
                " ... abench            A->A       Benchmark part of a filtergraph."
                " T.C drawtext          V->V       Draw text on top of video frames."
                " TSC overlay           VV->V      Overlay a video source on top of the input."
                " TS. perspective       V->V       Correct the perspective of video."
                " ... color             |->V       Provide an uniformly colored input."]
   :ffmpeg-9-0 [" T.. = Timeline support"
                " TS aap               AA->A      Apply Affine Projection algorithm."
                " T. drawtext          V->V       Draw text on top of video frames."
                " TS overlay           VV->V      Overlay a video source on top of the input."
                " .. color             |->V       Provide an uniformly colored input."]})

(def ^:private encoders-text
  ["Encoders:" " V..... = Video" " ------"
   " V....D libx264              libx264 H.264 / AVC"
   " V....D libopenh264          OpenH264 H.264 / AVC"
   " V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)"
   " A....D aac                  AAC (Advanced Audio Coding)"])

(def ^:private probes
  "ffprobe output, decoded with keyword keys."
  {:phone-portrait {:streams [{:index 0 :codec_type "video" :width 1920 :height 1080
                               :r_frame_rate "30/1" :avg_frame_rate "30/1" :nb_frames "300"
                               :start_time "0.000000" :side_data_list [{:rotation -90}]}
                              {:index 1 :codec_type "audio"}]
                    :format {:duration "10.010000" :format_name "mov,mp4,m4a,3gp,3g2,mj2"}}
   :variable-rate  {:streams [{:index 0 :codec_type "video" :width 1280 :height 720
                               :r_frame_rate "60/1" :avg_frame_rate "2997/100" :start_time "0.021333"}]
                    :format {:duration "4.500000" :format_name "matroska,webm"}}
   :still          {:streams [{:index 0 :codec_type "video" :width 400 :height 160
                               :r_frame_rate "25/1" :avg_frame_rate "0/0"}]
                    :format {:format_name "png_pipe"}}})

(defn- synthetic-v2
  "`spec` as render spec v2 with made-up bitmaps (the plan needs their names
  and sizes, not their pixels)."
  [spec]
  (let [reqs (v2/raster-requests spec)]
    (v2/assemble spec (into {} (map-indexed
                                (fn [i {:keys [key size kind]}]
                                  (let [id     (str (apply str (repeat (- 64 (count (str i))) "0")) i)
                                        [w h]  (if (= :text kind) [120 40] size)]
                                    [key {:bitmap id :width w :height h :path (str "/work/bitmaps/" i ".rgba")}]))
                                reqs)))))

(defn- plan-cases []
  (let [spec    (basic-spec)
        media'  (assoc media :kind :video :duration-s 180.18 :start-s 0.0 :vfr? false :has-audio? true)
        request {:spec spec :source "/in/clip.mov" :media media'
                 :output {:path "/out/clip_wm.part.mp4" :container "mp4"}
                 :encode (:encode schema/defaults) :strip-metadata? true}
        env     {:ffmpeg "/opt/wmark/bin/ffmpeg" :version {:major 7 :minor 1}
                 :encoders #{"libx264" "libx265" "h264_nvenc"} :workdir "/tmp/job"}
        v2spec  (synthetic-v2 spec)
        tags    {:title "Clip \"one\"" :author "Studio" :copyright "(c) 2026 A=B; #1"
                 :comment "line one\nline two \\ ©"}]
    [[:v1 request env]
     [:v1-segment (assoc request :spec (basic-spec {:first-frame 150})) env]
     [:v1-variable-rate-ffmpeg-6-lgpl
      (-> request (assoc-in [:media :vfr?] true) (assoc-in [:media :has-audio?] false)
          (assoc :encode {:codec :h264 :quality :compact :audio :copy}))
      (assoc env :version {:major 6 :minor 1} :encoders #{"h264_mf" "libopenh264"})]
     [:v1-ffmpeg-4-hevc
      (assoc request :encode {:codec :hevc :quality :archival :audio :aac}
                     :output {:path "/out/clip_wm.part.mov" :container "mov"})
      (assoc env :version {:major 4 :minor 4})]
     [:v1-overrides
      (assoc request :encode {:codec :h264 :quality :balanced :audio :none
                              :ffmpeg {:video-codec "h264_nvenc" :crf 25 :preset "p6"}})
      env]
     [:v2-tags-cover-windows
      (assoc request :spec v2spec :metadata tags :cover {:path "C:\\Temp\\cover.png"}
                     :strip-metadata? false)
      (assoc env :version {:major nil} :encoders #{"libx264"}
                 :workdir "C:\\Users\\A\\AppData\\Local\\Temp\\wmark-1")]
     [:v2-videotoolbox
      (assoc request :spec v2spec :metadata tags)
      (assoc env :encoders #{"h264_videotoolbox" "hevc_videotoolbox"})]
     [:v1-preview
      (assoc request :output {:path "/work/previews/p.png" :frame 45} :cover {:path "/work/c.png"})
      env]
     [:v2-preview
      (assoc request :spec v2spec :output {:path "/work/previews/p.png" :frame 45} :metadata tags)
      env]]))

(defn ffmpeg-vectors
  "ffmpeg.edn."
  []
  {:numbers  (vec (for [x decimal-cases] [x (number/decimal-str x)]))
   :parsed   {:ints     (vec (for [t ["12" "-3" "+4" "0x10" " 5" "1.0" "N/A" "99999999999999999999"]]
                               [t (number/parse-int t)]))
              :decimals (vec (for [t ["10.5" "-3" ".25" "1e-3" "5." "NaN" "Infinity" " 1" "0x1p3"]]
                               [t (number/parse-decimal t)]))
              :versions (vec (for [l ["ffmpeg version n9.0.1-11-ge47273f4d9-20260831 Copyright"
                                      "ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023"
                                      "ffmpeg version N-126342-gf88b741dbf-20260831 Copyright"]]
                               (parse/parse-version l)))
              :filters  (into (sorted-map)
                              (for [[k lines] filters-text]
                                [k (vec (sort (parse/parse-filters (apply str (interpose "\n" lines)))))]))
              :encoders (vec (sort (parse/parse-encoders (apply str (interpose "\n" encoders-text)))))
              :progress (vec (for [block [{"frame" "120" "out_time_us" "4004000" "speed" "2.01x" "progress" "continue"}
                                          {"frame" "5400" "out_time_us" "180180000" "progress" "end"}
                                          {"out_time_us" "N/A" "progress" "continue"}]]
                               (parse/parse-progress block 180180000)))
              :probes   (into (sorted-map) (for [[k p] probes] [k (parse/probe-facts p)]))}
   :encoding {:video-args (vec (for [[encode enc] [[{:codec :h264 :quality :high} "libx264"]
                                                   [{:codec :hevc :quality :archival} "libx265"]
                                                   [{:codec :h264 :quality :compact} "h264_nvenc"]
                                                   [{:codec :h264 :quality :balanced} "h264_videotoolbox"]
                                                   [{:codec :hevc :quality :high} "hevc_mf"]
                                                   [{:codec :h264 :quality :high :ffmpeg {:crf 30 :preset "slow"}} "libx264"]]]
                                 [encode enc (plan/video-args encode enc {:width 1280 :height 720} 29.97)]))
              :usable     (vec (sort (plan/usable-encoders
                                      #{"h264_mf" "libopenh264" "h264_nvenc" "hevc_mf" "hevc_nvenc" "mpeg4"}
                                      (fn [_ enc] (not (#{"h264_mf" "h264_nvenc"} enc))))))
              :codecs     (vec (sort (plan/codecs-available #{"libx264" "hevc_nvenc"})))
              :ffmetadata (plan/ffmetadata {:title "T" :author " A " :copyright "a=b;c#d\\e" :comment "x\r\ny"})}
   :plans    (into (sorted-map)
                   (for [[k request env] (plan-cases)]
                     [k (select-keys ((if (= 2 (get-in request [:spec :spec/version]))
                                        plan/compile-request-v2
                                        plan/compile-request)
                                      request env)
                                     [:argv :graph :files :total-us :output :workdir])]))
   :graph    (graph/render [(graph/chain ["0:v"]
                                         [(graph/f "drawtext" :fontfile "C:/f.ttf" :x (graph/expr "w-tw-(24)")
                                                   :fontcolor "white@0.85" :enable nil)
                                          (graph/f "fps" :fps "30000/1001")]
                                         ["out"])])})

;; ---------------------------------------------------------------------------
;; schema.edn: validation, messages and JSON decoding

(def settings-cases
  "JSON-shaped settings, as the API receives them (keyword keys, string
  enums, whole numbers where doubles go), named."
  [[:empty {}]
   [:defaults {:output {:suffix "_wm" :container "mp4" :overwrite? false :strip-metadata true}
               :encode {:codec "h264" :quality "high" :audio "copy"}
               :logo   {:enabled true :anchor "bottom-right" :offset {:x 24 :y 24}
                        :width-ratio 0.12 :opacity 0.85
                        :animation {:type "flip-y" :every-s 60 :duration-s 1}}
               :texts  []}]
   [:every-text-mode {:texts [{:mode "continuous" :content "(c) Studio" :size-ratio 0.04 :color "#FFFFFF"
                               :opacity 1 :border 2 :anchor "top-left" :offset {:x 0 :y -3}}
                              {:mode "scheduled" :content "Scheduled" :at [2 90.5] :duration-s 1.5 :id "s"}
                              {:mode "canary" :content "Canary" :every-s 30 :frames 2}
                              {:mode "subliminal" :content "Wire id"}
                              {:mode "random" :content "Random" :min-duration-s 1 :max-duration-s 3
                               :min-gap-s 10 :max-gap-s 20}]}]
   [:metadata {:output {:metadata {:title "T" :author "A" :copyright "(c) 2026" :comment ""}}}]
   [:ffmpeg {:encode {:codec "hevc" :quality "archival" :audio "none"
                      :ffmpeg {:video-codec "libx265" :crf 23.0 :preset "slow"}}}]
   ;; invalid
   [:opacity-too-high {:logo {:opacity 1.5}}]
   [:opacity-a-string {:logo {:opacity "high"}}]
   [:width-too-small {:logo {:width-ratio 0.001}}]
   [:crf-not-whole {:encode {:ffmpeg {:crf 23.5}}}]
   [:unknown-anchor {:logo {:anchor "middle"}}]
   [:unknown-container {:output {:container "avi"}}]
   [:unknown-key {:logo {:size 3} :extra true}]
   [:empty-path {:logo {:path ""}}]
   [:suffix-too-long {:output {:suffix (apply str (repeat 41 "x"))}}]
   [:not-a-map {:logo "logo.png"}]
   [:texts-not-a-vector {:texts {:mode "continuous"}}]
   [:too-many-texts {:texts (vec (repeat 9 {:mode "continuous" :content "x"}))}]
   [:unknown-mode {:texts [{:mode "hologram" :content "x"}]}]
   [:no-mode {:texts [{:content "x"}]}]
   [:text-not-a-map {:texts ["x"]}]
   [:missing-content {:texts [{:mode "continuous"}]}]
   [:second-text-bad {:texts [{:mode "continuous" :content "ok"}
                              {:mode "scheduled" :content "" :at [] :duration-s 0.01}]}]
   [:bad-colour {:texts [{:mode "continuous" :content "x" :color "#12345"}]}]
   [:colour-with-newline {:texts [{:mode "continuous" :content "x" :color "red\n"}]}]
   [:negative-time {:texts [{:mode "scheduled" :content "x" :at [-1] :duration-s 1}]}]
   [:frames-out-of-range {:texts [{:mode "canary" :content "x" :frames 4}]}]
   [:offset-out-of-range {:logo {:offset {:x 10001 :y "0"}}}]
   [:animation-without-type {:logo {:animation {:every-s 10}}}]
   [:several-errors {:logo {:opacity -1 :anchor "top" :enabled "yes"}
                     :output {:overwrite? 1}
                     :texts [{:mode "scheduled" :content "x"}]}]])

(def spec-cases
  "Render specs, named: the golden v1 spec and damaged copies of it."
  (delay
    (let [s (basic-spec)]
      [[:v1 s]
       [:no-version (dissoc s :spec/version)]
       [:version-3 (assoc s :spec/version 3)]
       [:version-float (assoc s :spec/version 1.0)]
       [:negative-width (assoc-in s [:canvas :width] -1)]
       [:fps-a-float (assoc-in s [:timebase :fps-num] 30.0)]
       [:unknown-kind (assoc-in s [:layers 0 :kind] :video)]
       [:window-missing-end (update-in s [:layers 2 :timing] assoc :windows [{:start 1}])]
       [:bad-easing (assoc-in s [:layers 0 :animation :easing] :linear)]
       [:extra-key (assoc s :comment "x")]
       [:placement-points (assoc-in s [:layers 1 :placement] {:type :per-window :points [[0.5 0.5] [0.5] [2.0 0.0]]})]])))

(defn- errors-of [f x]
  (try (f x) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljd cljd.core/ExceptionInfo) e
         (:errors (ex-data e)))))

(defn schema-vectors
  "schema.edn: for each settings case what decoding gives and the errors of
  validating it; for each spec case the errors."
  []
  {:settings (into (sorted-map)
                   (for [[k x] settings-cases
                         :let [d (schema/decode-json x)]]
                     [k {:decoded d :errors (errors-of schema/validate! d)}]))
   :specs    (into (sorted-map)
                   (for [[k x] @spec-cases]
                     [k {:errors (errors-of spec-schema/validate! x)}]))})
