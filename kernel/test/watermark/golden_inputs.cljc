;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.golden-inputs
  "What the golden vectors (kernel/test/golden/*.edn) are computed from, on
  every runtime: the JVM tests check them with watermark.golden, and the
  Dart VM harness (kernel/dart) checks the same files with the same inputs.

  Each function takes what only the host can provide (the font's bytes) and
  returns the value its golden file holds."
  (:require [clojure.string :as str]
            [watermark.config :as config]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.core.form :as form]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.core.seeds :as seeds]
            [watermark.engine :as engine]
            [watermark.ffmpeg.engine :as fengine]
            [watermark.ffmpeg.graph :as graph]
            [watermark.ffmpeg.parse :as parse]
            [watermark.ffmpeg.plan :as plan]
            [watermark.files :as files]
            [watermark.media :as media]
            [watermark.raster :as raster]
            [watermark.raster.image :as image]
            [watermark.render :as render]
            [watermark.render.schema :as spec-schema]
            [watermark.render.v2 :as v2]
            [watermark.store :as store]
            [watermark.store.memory :as memory]
            [watermark.util.chars :as chars]
            [watermark.util.host :as host]
            [watermark.util.num :as number]
            [watermark.util.prng :as prng]
            [watermark.util.task :as task]
            [watermark.util.text :as text]
            [watermark.util.unicode :as unicode]
            [watermark.util.unicode-data :as unicode-data]))

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

(defn- result
  "What `f` returns, or the kind, message and reason of the error it throws."
  [f]
  (let [[v e] (host/attempt f)]
    (if e
      (cond-> {:error (:wmark/error (ex-data e)) :message (ex-message e)}
        (:reason (ex-data e)) (assoc :reason (:reason (ex-data e))))
      v)))

(defn- engine-vectors
  "What the FFmpeg engine decides (watermark.ffmpeg.engine), for ffmpeg.edn."
  []
  (let [version (parse/parse-version "ffmpeg version 7.1-full_build-www.gyan.dev Copyright (c) 2000-2024")
        all     (into #{"drawtext"} (concat plan/required-filters plan/required-filters-v2 plan/preview-filters))
        builds  {:gpl         all
                 :lgpl        (disj all "perspective")
                 :no-drawtext (disj all "drawtext")
                 :bare        #{"overlay"}}
        bins    {:ffmpeg  {:path "/opt/wmark/bin/ffmpeg" :source :app-bin}
                 :ffprobe {:path "/opt/wmark/bin/ffprobe" :source :app-bin}}
        ;; the preferred H.264 encoders fail here, so the next one is used
        usable? (fn [_ enc] (not (#{"libx264" "h264_nvenc"} enc)))
        encs    #{"libx264" "h264_nvenc" "libopenh264" "mjpeg" "mpeg4"}
        info    (fn [filters] (fengine/discover {:binaries bins :warnings ["from the host"] :usable? usable?
                                                 :described {:version version :filters filters :encoders encs}}))
        gpl     (info all)
        request (fn [more] (merge {:spec (basic-spec) :source "/in/a.mov" :media media
                                   :output {:path "/out/a.part.mp4" :container "mp4"}
                                   :encode {:codec :h264 :quality :high}}
                                  more))
        lines   ["frame=12" "out_time_us=400400" "progress=continue"
                 "frame=24" "out_time_us=800800" "speed=1.5x" "progress=continue"
                 "frame=30" "out_time_us=1001000" "progress=end"]
        events  (let [seen (atom [])
                      read (fengine/progress-reader 1001000 #(swap! seen conj %))]
                  (doseq [l lines] (read l))
                  @seen)]
    {:argv     {:describe (fengine/describe-argv "/bin/ffmpeg")
                :trial    (fengine/trial-argv "/bin/ffmpeg" (fengine/trial-video-args :h264 "libx264"))
                :probe    (fengine/probe-argv "/bin/ffprobe" "/in/a b.mov")
                :sample   (fengine/sample-argv "/bin/ffmpeg" {:width 720 :height 1280 :fps 25 :seconds 12} "/p/sample.mp4")
                :decode   (fengine/decode-argv "/bin/ffmpeg" "/logos/l.png")}
     :describe (let [d (fengine/describe {:version  "ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023\nbuilt with gcc"
                                          :filters  (apply str (interpose "\n" (:ffmpeg-6-1 filters-text)))
                                          :encoders (apply str (interpose "\n" encoders-text))})]
                 (-> d (update :filters (comp vec sort)) (update :encoders (comp vec sort))))
     :discover (into (sorted-map)
                     (concat
                      (for [[k filters] builds]
                        [k (-> (info filters)
                               (select-keys [:engine/version :available? :problems :warnings :capabilities :encoders])
                               (update :encoders (comp vec sort))
                               (update :capabilities #(into (sorted-map) (for [[ck v] %] [ck (vec (sort-by str v))]))))])
                      [[:not-found (fengine/discover {:binaries {:ffmpeg {:path nil}} :described nil})]
                       [:split (fengine/split-build-warning {:ffmpeg  {:path "C:\\wmark\\bin\\ffmpeg.exe"}
                                                             :ffprobe {:path "C:\\tools\\ffprobe.exe"}})]]))
     :checks   (vec (for [[k i r] [[:unavailable (info #{"overlay"}) (request {})]
                                   [:cover-in-mov gpl (request {:cover {:path "/c.png"}
                                                                :output {:path "/out/a.part.mov" :container "mov"}})]
                                   [:forced-encoder gpl (request {:encode {:codec :h264 :ffmpeg {:video-codec "h264_qsv"}}})]
                                   [:unusable-codec gpl (request {:encode {:codec :hevc}})]]]
                        [k (result #(fengine/plan-render i r "/work/x"))]))
     :progress events
     :outcomes [(fengine/outcome {:cancelled? true :exit nil})
                (fengine/outcome {:exit 0})
                (fengine/outcome {:exit 183 :log "/work/x/ffmpeg.log" :log-tail "Error while filtering"})]
     :scratch  [(fengine/log-path {:workdir "/work/x"}) (fengine/log-path {:workdir "C:\\work\\x"})]
     :still    [(select-keys (fengine/still {:width 2 :height 1} "/l.png" {:exit 0 :px :pixels :err ""} 8) [:width :height :px])
                (result #(fengine/still {:width 2 :height 1} "/l.png" {:exit 1 :px nil :err " Invalid data \n"} 0))]}))

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
   :engine   (engine-vectors)
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

;; ---------------------------------------------------------------------------
;; text.edn: the library's own Unicode (watermark.util.unicode, text)

(def ^:private general-categories
  [:Lu :Ll :Lt :Lm :Lo :Mn :Mc :Me :Nd :Nl :No :Pc :Pd :Ps :Pe :Pi :Pf :Po
   :Sm :Sc :Sk :So :Zs :Zl :Zp :Cc :Cf :Cs :Co :Cn])

(def ^:private text-alphabet
  "Code points where normalization, casing and word rules meet: Greek with
  capital, small and final sigma, Latin with dotted and dotless i,
  ligatures, fullwidth and superscripts, combining marks of several
  classes, Hangul jamo and syllables, digits and number punctuation, word
  punctuation, spaces and separators, format characters, CJK and kana, and
  code points outside the BMP (letters, a digit, an emoji, a mark, format
  characters, an unassigned one after a CJK block)."
  [0x3A3 0x3C3 0x3C2 0x391 0x392 0x39F 0x3B1 0x386 0x3AC 0x390 0x345 0x37A
   0x41 0x61 0x5A 0x49 0x69 0x130 0x131 0xC5 0x212B 0x1E0A 0x1E0B 0xFB01 0xFF21 0xB9 0x2075
   0x300 0x301 0x307 0x308 0x315 0x316 0x323 0x327 0x334 0x5B0 0x93C 0x1DCE 0x20DD
   0x1100 0x1112 0x1161 0x1175 0x11A8 0x11C2 0xAC00 0xAC01 0xD7A3 0x3131
   0x30 0x31 0x661 0xBD 0x2160 0x24B6 0x25 0x23 0x24 0xA2 0x66B 0x66A
   0x2E 0x27 0x22 0x2C 0x3A 0x2D 0x5F 0xB7 0x2019 0x2027 0xFF0E 0x20 0x9 0xA 0xD 0xA0 0x3000
   0x2028 0x200B 0x200D 0xAD 0x964 0x2060
   0x4E00 0x3005 0x30A2 0x3042 0x3099 0x30FC 0xF900
   0x10400 0x10428 0x1D400 0x1D7CE 0x1F600 0x1D167 0xE0041 0xE007F 0x13430 0x2A6E0])

(defn- digest
  "FNV-1a over integers (64 bits, wrapping): a fingerprint of a long
  sequence that both runtimes compute alike."
  [xs]
  (reduce (fn [h x] (number/mul-wrap (bit-xor h x) 1099511628211)) -3750763034362895579 xs))

(defn- text-digest
  "The digest of `f` applied to each string: its code points, then -1."
  [f strs]
  (digest (mapcat #(conj (chars/code-points (f %)) -1) strs)))

(defn- random-texts [seed n max-len]
  (let [g (prng/generator seed)]
    (vec (repeatedly n (fn []
                         (let [len (inc (prng/next-below! g max-len))]
                           (chars/from-code-points
                            (vec (repeatedly len #(text-alphabet (prng/next-below! g (count text-alphabet))))))))))))

(def ^:private text-ops
  [[:lower unicode/lower] [:nfd unicode/nfd] [:nfkd unicode/nfkd] [:nfc unicode/nfc] [:nfkc unicode/nfkc]])

(defn text-vectors
  "text.edn."
  []
  (let [every-cp (vec (concat (range 0 0xD800) (range 0xE000 0x110000)))
        one      (mapv #(chars/from-code-points [%]) every-cp)
        index    (zipmap general-categories (range))
        corpus   (random-texts 20260930 3000 12)
        samples  (subvec corpus 0 24)]
    {:version    unicode-data/version
     :categories (digest (map #(index (unicode/category %)) (range 0x110000)))
     :every-code-point (into (sorted-map) (for [[k f] text-ops] [k (text-digest f one)]))
     :random     (into (sorted-map) (for [[k f] text-ops] [k (text-digest f corpus)]))
     :word-boundaries (digest (mapcat #(conj (vec (sort (unicode/word-boundaries (chars/code-points %)))) -1) corpus))
     :samples    (vec (for [s samples]
                        (into {:text (chars/code-points s)
                               :word-boundaries (vec (sort (unicode/word-boundaries (chars/code-points s))))}
                              (for [[k f] text-ops] [k (chars/code-points (f s))]))))
     :sigma      (vec (for [s ["ΑΣ" "Σ" "ΑΣ Β" "ΑΣ-Β" "ΑΣ.Β" "ΑΣ'Β" "ΑΣ:Β" "ΑΣ_Β" "ΑΣ1" "1Σ" "Α1Σ"
                               "Α.Σ" "ΑΣ." "A Σ" "aΣ" "ΑΣ·Β" "ΑΣ,Β" "16:9 ΟΔΟΣ" "ΟΔΟΣ ΤΕΣΤ" "Α'Σ"
                               "ΑΣ’Β" "ΑΣ．Β" "ΣΣ" "ΑΣΣ" "ⒶΣ" "ⅠΣ" "ΑΣ%" "$1Σ" "Α𐐨Σ" "𐐨Σ" "İSTANBUL"]]
                       [s (text/lower s)]))
     :whitespace (vec (filter text/whitespace? (range 0x110000)))
     :trim       (vec (for [s [" a " "\u00A0a\u00A0" "\u3000a\u2028" "\uFEFFa" "\t\n" ""]]
                        [s (text/trim s) (text/blank? s)]))
     :collapse   (text/collapse-spaces "a \t\n\u000B\f\r b  c\u00A0d\u3000e")
     :runs       (vec (for [s ["16:9 Video Profile" "横屏 16:9" "a--b__c" "  x  " "Ⅻ·ⅰ·①"]]
                        [s (text/replace-runs s (complement unicode/letter-mark-number?) "-")]))}))

;; ---------------------------------------------------------------------------
;; profiles.edn: the profile rules on the memory store, with a fixed clock

(def ^:private profile-names
  "Names as people type them: punctuation a file system refuses, case,
  Windows device names, superscripts and fullwidth forms, other scripts,
  final sigma, dotted capital I, combining marks, invisible and odd
  whitespace, a name too long for a file."
  ["16:9 Video Profile" "  16-9   VIDEO profile " "CON" "com¹" "Lpt9" "横屏 16:9" "ＡＢＣ" "::" "-a-"
   "ΟΔΟΣ ΤΕΣΤ" "İstanbul" "Straße" "ﬁle №5" "Cafe\u0301" "Café" "e\u200Bx" "tab\tname"
   "\u00A0nbsp\u00A0" "ǅungla" "Ⅻ Chapter" "٣ Arabic-Indic" "𝐁old 😀 emoji" "a\u2028b"
   (apply str (repeat 70 "ab"))])

(defn profile-vectors
  "profiles.edn."
  []
  (binding [host/*clock* (let [t (atom 1790726400000)] (fn [] (swap! t + 1500)))]
    (let [st        (memory/memory-store)
          defaults  {:logo {:anchor :bottom-right :opacity 0.85 :width-ratio 0.12}
                     :text [{:mode :continuous :content "(c) Studio"}]}
          ;; each step in turn: ClojureDart evaluates a map literal's values
          ;; in another order than the JVM
          created   (result #(config/create-profile! st "16:9 Video Profile"
                                                     {:logo {:anchor :top-left :opacity nil} :inputs ["a.mp4"]}))
          taken     (result #(config/create-profile! st "16-9 video profile" {}))
          by-case   (result #(config/save-profile! st "16:9 VIDEO PROFILE" {:logo {:anchor :center}}))
          alias     (result #(config/save-profile! st "16 9 video profile!" {}))
          by-slug   (result #(config/save-profile! st "16-9-video-profile" {:logo {:anchor :top-right}}))
          stale     (result #(config/save-profile! st "16:9 Video Profile" {} {:if-rev 1}))
          renamed   (result #(config/rename-profile! st "16:9 Video Profile" "Wide"))
          missing   (result #(config/rename-profile! st "missing" "x"))
          reserved  (result #(config/create-profile! st "latest" {}))
          latest    (result #(config/record-latest! st {:logo {:opacity 0.5} :job {:id 1}} "Wide"))
          copied    (result #(config/copy-profile! st "latest" "From last run"))
          invalid   (vec (for [n ["" "   " "a\u0007b" (apply str (repeat 81 "x")) "::"]]
                           (result #(config/create-profile! st n {}))))
          listed    (config/list-profiles st)
          resolved  (vec (for [p [nil "wide" :none "nope"]]
                           (result #(config/resolve-settings st {:profile p :defaults defaults
                                                                 :overrides {:logo {:opacity nil :width-ratio 0.2}}}))))
          docs      (into (sorted-map) (for [[slug doc] (store/-read-all st)] [slug (store/encode-doc doc)]))
          decoded   (vec (for [[slug text] docs] [slug (= (store/-read st slug) (store/decode-doc text slug))]))
          deleted   (result #(config/delete-profile! st "Wide"))
          again     (result #(config/delete-profile! st "Wide"))]
      {:slugs       (vec (for [n profile-names] [n (result #(config/slug n))]))
       :names       (vec (for [n profile-names] [n (config/normalize-name n)]))
       :steps       {:created created :taken taken :by-case by-case :alias alias :by-slug by-slug
                     :stale stale :renamed renamed :missing missing :reserved reserved :latest latest
                     :copied copied :invalid invalid :deleted deleted :deleted-again again}
       :listed      listed
       :resolved    resolved
       :documents   docs
       :decoded     decoded
       :bad-documents (vec (for [text ["{:a" "[1 2]" "{:profile/name \"x\" :settings {} :wmark/format 9}" ""]]
                             (result #(store/decode-doc text "p.edn"))))})))

;; ---------------------------------------------------------------------------
;; pipeline.edn: the use cases (watermark.core.api) end to end on fake ports

(def ^:private golden-video
  {:kind :video :width 1280 :height 720 :fps-num 30000 :fps-den 1001 :frames 300
   :duration-s 10.01 :start-s 0.0 :vfr? false :has-audio? true :rotation 0})

(def ^:private golden-capabilities
  {:spec-versions #{1} :layers #{:image :text} :animations #{:flip-y}
   :timing #{:always :windows :periodic} :placement #{:fixed :burst-scatter :per-window}
   :codecs #{:h264 :hevc} :containers #{"mp4" "mov" "mkv"} :audio #{:copy :aac :none}
   :sources #{:file :url} :extras #{:metadata :cover} :preview #{:frame :sample}})

(defn- golden-files
  "The files port over an atom of path -> modification time."
  [files]
  (reify files/Files
    (file? [_ p] (contains? @files p))
    (make-dirs! [_ _] nil)
    (list-files [_ dir]
      (vec (for [[p t] (sort @files) :when (str/starts-with? p (str dir "/"))]
             {:path p :name (subs p (inc (count dir))) :modified-ms t})))
    (delete! [_ p] (let [there? (contains? @files p)] (swap! files dissoc p) there?))))

(defn- golden-engine
  "An engine that renders nothing: it checks capabilities, returns a plan
  naming what the pipeline asked for, reports progress once, and fails any
  source whose name says so. What it writes appears in `files`."
  [log files]
  (reify
    engine/VideoEngine
    (info [_] {:engine/id :golden :engine/version "1" :available? true :capabilities golden-capabilities})
    (probe [_ source] (if (str/ends-with? (str source) ".png") {:kind :image :width 400 :height 160} golden-video))
    (prepare [this request]
      (engine/check! (engine/info this) request)
      (cond-> {:engine   :golden
               :source   (:source request)
               :output   (:output request)
               :encode   (:encode request)
               :layers   (mapv (fn [l] [(:kind l) (get-in l [:timing :type])]) (get-in request [:spec :layers]))}
        (:metadata request) (assoc :metadata (:metadata request))
        (:cover request)    (assoc :cover (:cover request))))
    (execute! [_ plan listener]
      (let [failing? (str/includes? (:source plan) "fail")
            out      (get-in plan [:output :path])]
        (swap! log conj [:execute (:source plan) out])
        (when listener (listener {:event :progress :fraction 0.5 :frame 150}))
        (when-not failing? (swap! files assoc out (count @files)))
        (reify engine/RenderHandle
          (cancel! [_] nil)
          (outcome [_] (task/resolved (if failing?
                                        (engine/failed "The golden engine fails this input.")
                                        {:status :done}))))))
    engine/SampleSource
    (sample-video [_ opts path]
      (swap! log conj [:sample opts path])
      (swap! files assoc path (count @files))
      path)))

(defn- golden-media
  "Inputs under /in, outputs next to them under /out; `missing` inputs
  aren't there, and an `exists` output is already taken."
  [log]
  (reify media/MediaIO
    (open-input [_ _ input]
      (when (str/includes? (str input) "missing")
        (throw (ex-info (str "No such file: " input) {:wmark/error :not-found})))
      {:id (str input) :location (str "/in/" input) :fingerprint (str "fp-" input)})
    (open-output [_ _ input _]
      (when (str/includes? (str input) "exists")
        (throw (ex-info (str "The output for " input " already exists.") {:wmark/error :conflict})))
      {:final (str "/out/" input) :temp (str "/out/" input ".part") :container "mp4"})
    (commit! [_ _ output] (swap! log conj [:commit (:final output)]) (:final output))
    (discard! [_ _ output] (swap! log conj [:discard (:temp output)]) nil)))

(defn- scrub-ids
  "`x` with every UUID in its strings written as <id>: previews are named
  by random ids."
  [x]
  (cond (string? x) (str/replace x #"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}" "<id>")
        (map? x)    (into {} (map (fn [[k v]] [(scrub-ids k) (scrub-ids v)])) x)
        (vector? x) (mapv scrub-ids x)
        (set? x)    (set (map scrub-ids x))
        (seq? x)    (map scrub-ids x)
        :else       x))

(defn- events-of
  "An on-event function that records into atom `a`."
  [a]
  (fn [e] (swap! a conj e)))

(defn pipeline-vectors
  "pipeline.edn, as a task (a Future on the Dart VM): the Core API on fake
  ports: planning, a batch with a failing, a taken and a missing input and a
  cover, a cancelled batch, and previews on a video and on the sample clip.
  Nothing in it depends on the clock or on random ids."
  []
  (let [log      (atom [])
        fs       (atom {})
        st       (memory/memory-store)
        sys      {:profiles-for (constantly st)
                  :entitlements (features/community)
                  :engine       (golden-engine log fs)
                  :media        (golden-media log)
                  :files        (golden-files fs)
                  :secret-for   (constantly (secret))
                  :font         "/fonts/wmark.ttf"
                  :preview-dir  "/previews"}
        ctx      {:tenant "t" :user "u"}
        ;; each step in turn: ClojureDart evaluates a map literal's values
        ;; in another order than the JVM
        _        (api/create-profile! sys ctx "Clip"
                                      {:logo   {:path "/logos/l.png" :anchor :top-right
                                                :animation {:type :flip-y :every-s 5.0 :duration-s 0.8}}
                                       :texts  [{:mode :continuous :content "(c) Studio"}
                                                {:mode :scheduled :content "Scheduled" :at [2.0] :duration-s 1.5}]
                                       :output {:metadata {:title "A title" :comment "  "}}})
        planned  (api/plan-batch sys ctx {:profile "clip" :inputs ["a.mp4" "b.mov"] :cover {:t 1.0}})
        refused  (vec (for [req [{:profile "clip" :inputs []}
                                 {:profile "clip" :inputs ["a.mp4"] :cover {:t -1}}
                                 {:profile "nope" :inputs ["a.mp4"]}
                                 {:profile "clip" :inputs ["a.mp4"] :settings {:texts [{:mode :random :content "x"}]}}]]
                       (result #(api/plan-batch sys ctx req))))
        run-log  (atom [])
        cut-log  (atom [])
        cut?     (atom false)]
    (-> (api/run-batch! sys ctx {:profile "clip" :inputs ["a.mp4" "fail.mp4" "exists.mp4" "missing.mp4" "b.mov"]
                                 :cover {:t 1.0}}
                        {:on-event (events-of run-log)})
        (task/then
         (fn [ran]
           (let [latest (:settings (config/get-profile st "latest"))]
             (task/then (api/run-batch! sys ctx {:profile "clip" :inputs ["c.mp4" "d.mp4" "e.mp4"]}
                                        {:on-event   (fn [e]
                                                       (swap! cut-log conj e)
                                                       (when (= :finished (:type e)) (reset! cut? true)))
                                         :cancelled? #(deref cut?)})
                        (fn [cut] [ran latest cut])))))
        (task/then
         (fn [[ran latest cut]]
           (task/then (api/preview-frame sys ctx {:profile "clip" :source "a.mp4" :t 2.0})
                      (fn [frame] [ran latest cut frame]))))
        (task/then
         (fn [[ran latest cut frame]]
           (task/then (api/preview-frame sys ctx {:profile "clip" :aspect "9:16"})
                      (fn [sample]
                        (scrub-ids
                        {:plan     (select-keys planned [:base :locked :plans :warnings])
                         :refused  refused
                         :ran      {:results (:results ran) :events @run-log :latest latest}
                         :cancelled {:results (:results cut) :events @cut-log}
                         :previews (vec (for [p [frame sample]]
                                          (-> (dissoc p :id)
                                              (assoc :file (api/preview-file sys ctx (:id p))))))
                         :ports    {:log @log :files (vec (sort (map scrub-ids (keys @fs))))}}))))))))
