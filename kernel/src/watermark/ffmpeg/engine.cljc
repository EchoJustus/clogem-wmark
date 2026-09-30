;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.ffmpeg.engine
  "What the FFmpeg engine decides, apart from running FFmpeg: the command
  lines it runs besides a render, the capabilities a build offers (from
  what it prints), the checks before planning, reading progress, and the
  outcome of a run (docs/adr/0013, section 4). Each host's engine adapter
  (watermark.engine.ffmpeg on the JVM) finds the binaries, runs the
  processes and hands their output here, so every host's FFmpeg engine
  decides the same way."
  (:require [clojure.string :as str]
            [watermark.engine :as engine]
            [watermark.ffmpeg.graph :as g]
            [watermark.ffmpeg.parse :as parse]
            [watermark.ffmpeg.plan :as plan]
            [watermark.files :as files]
            [watermark.util.task :as task]
            [watermark.util.text :as text]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Command lines besides a render (the executable first, as an absolute path)

(defn describe-argv
  "The commands whose output `describe` reads: version, filters, encoders."
  [ffmpeg]
  {:version  [ffmpeg "-hide_banner" "-version"]
   :filters  [ffmpeg "-hide_banner" "-filters"]
   :encoders [ffmpeg "-hide_banner" "-encoders"]})

(defn describe
  "Version, filters and video encoders from the outputs of `describe-argv`'s
  commands, by the same keys."
  [{:keys [version filters encoders]}]
  {:version  (parse/parse-version (first (str/split-lines (str version))))
   :filters  (parse/parse-filters (str filters))
   :encoders (parse/parse-encoders (str encoders))})

(defn trial-argv
  "Encode five frames of a test pattern with encoder arguments `video-args`
  and throw them away: for encoders a build lists but a machine may not run
  (a hardware encoder without its hardware). Hosts give it 30 seconds."
  [ffmpeg video-args]
  (vec (concat [ffmpeg "-hide_banner" "-v" "error" "-nostdin"
                "-f" "lavfi" "-i" "color=c=gray:s=256x144:r=30" "-frames:v" "5"]
               video-args ["-f" "null" "-"])))

(defn trial-video-args
  "The encoder arguments a trial of encoder `enc` for `codec` runs with."
  [codec enc]
  (plan/video-args {:codec codec} enc {:width 256 :height 144} 30))

(defn trial-encoders
  "Trial encodes for the `listed` encoders, as a task of {[codec encoder]
  works?}: per codec family, in preference order, until one works, which
  are exactly the questions `discover`'s :usable? gets. `trial` (fn [codec
  encoder]) returns a task of true when a trial encode (`trial-argv`)
  works. For hosts that time out a process only asynchronously (the Dart
  VM); a host that can wait answers :usable? directly."
  [listed trial]
  (task/reduce (fn [acc [codec names]]
                 (task/reduce (fn [acc enc]
                                (task/then (trial codec enc)
                                           (fn [ok?]
                                             (let [acc (assoc acc [codec enc] (boolean ok?))]
                                               (if ok? (reduced acc) acc)))))
                              acc names))
               {} (plan/encoder-candidates (set listed))))

(defn probe-argv
  "ffprobe on `input`; its JSON output, decoded by the host, goes to
  watermark.ffmpeg.parse/probe-facts."
  [ffprobe input]
  (into [ffprobe] (parse/probe-args input)))

(defn sample-argv
  "A calm clip for previews before a video is chosen: neutral grey with a
  faint grid, so marks of any colour show against it, in FFmpeg's own
  MPEG-4 encoder, which every build has (LGPL builds lack x264)."
  [ffmpeg {:keys [width height fps seconds]} path]
  (let [graph (g/render [(g/chain []
                                  [(g/f "color" :c "0x5f6b78" :s (str width "x" height) :r fps :d seconds)
                                   (g/f "drawgrid" :w 64 :h 64 :t 1 :c "white@0.14")]
                                  ["v"])])]
    [ffmpeg "-hide_banner" "-nostdin" "-y" "-loglevel" "error"
     "-filter_complex" graph "-map" "[v]"
     "-c:v" "mpeg4" "-q:v" "3" "-pix_fmt" "yuv420p" (str path)]))

(defn decode-argv
  "The first frame of `source` as straight RGBA8 on stdout. PNG decoding is
  lossless, so the pixels don't depend on the FFmpeg version."
  [ffmpeg source]
  [ffmpeg "-v" "error" "-nostdin" "-i" (str source) "-frames:v" "1" "-f" "rawvideo" "-pix_fmt" "rgba" "-"])

;; ---------------------------------------------------------------------------
;; Capabilities

(defn- parent-dir
  "The folder part of a path, whichever separator it uses."
  [path]
  (let [p (str path)
        i (max (or (str/last-index-of p "/") -1) (or (str/last-index-of p "\\") -1))]
    (when (pos? i) (subs p 0 i))))

(defn split-build-warning
  "A warning when ffprobe was found somewhere other than ffmpeg's folder: it
  works on this machine, but a bundle missing its own ffprobe would fail on a
  clean one."
  [{:keys [ffmpeg ffprobe]}]
  (let [a (parent-dir (:path ffmpeg))
        b (parent-dir (:path ffprobe))]
    (when (and (:path ffmpeg) (:path ffprobe) (not= a b))
      (str "ffprobe was found in " b ", not next to ffmpeg in " a
           ". Ship both together: a machine without the second copy can't probe media."))))

(def not-found
  "What a host reports when it finds no FFmpeg."
  "FFmpeg not found. Put ffmpeg and ffprobe next to wmark (or in its bin/ folder), on PATH, or pass --ffmpeg.")

(defn discover
  "Everything the engine's `info` reports.
    :binaries   {:ffmpeg {:path :source :trail} :ffprobe {...}}, as the host found them
    :warnings   the host's own warnings (a binary from the working folder, say)
    :described  `describe`'s result, or nil when there is no ffmpeg
    :usable?    (fn [codec encoder]) true when a trial encode works on this machine"
  [{:keys [binaries warnings described usable?]}]
  (let [{:keys [ffmpeg ffprobe]} binaries]
    (if-not (and (:path ffmpeg) (:path ffprobe) described)
      {:engine/id :ffmpeg :available? false :binaries binaries :problems [not-found] :capabilities {}}
      (let [{:keys [version filters] listed :encoders} described
            encoders (plan/usable-encoders listed usable?)
            unusable (sort (remove encoders listed))
            missing  (remove filters plan/required-filters)
            v1?      (empty? missing)
            ;; spec v2 (host-rendered bitmaps) needs compositing only: LGPL
            ;; builds, which lack the GPL-only perspective filter, qualify
            v2?      (every? filters plan/required-filters-v2)
            text?    (contains? filters "drawtext")
            codecs   (plan/codecs-available encoders)]
        {:engine/id      :ffmpeg
         :engine/version (or (second (re-find #"version[ \t]+n?([^ \t]+)" (str (:raw version)))) "unknown")
         :available?     (boolean (and (or v1? v2?) (seq codecs)))
         :problems       (cond-> []
                           (not (or v1? v2?)) (conj (str "This FFmpeg build lacks required filters: " (str/join ", " missing)))
                           (and v1? (not text?) (not v2?)) (conj "This FFmpeg build has no drawtext filter (needs libfreetype/libharfbuzz): text layers are unavailable.")
                           (empty? codecs) (conj "This FFmpeg build has no H.264 or HEVC encoder."))
         :warnings       (vec (concat warnings
                                      (some-> (split-build-warning binaries) vector)
                                      (when (seq unusable)
                                        [(str "This FFmpeg lists encoders that fail on this machine, so wmark won't use them: "
                                              (str/join ", " unusable) ".")])
                                      (when (and v1? v2? (not text?))
                                        ;; information: the job pipeline gives text to v2
                                        ["This FFmpeg build has no drawtext filter, so wmark draws text layers itself (render spec v2)."])
                                      (when (and v2? (not v1?))
                                        ;; information, not a fault: the job pipeline gives it v2
                                        [(str "This FFmpeg build lacks " (str/join ", " missing)
                                              " (an LGPL build?), so wmark draws the logo flip and the text itself"
                                              " and FFmpeg only composites them (render spec v2).")])))
         :binaries       binaries
         :version        version
         :encoders       encoders
         :capabilities   {:spec-versions (cond-> #{} v1? (conj 1) v2? (conj 2))
                          :layers     (cond-> #{}
                                        v1?             (conj :image)
                                        (and v1? text?) (conj :text)
                                        v2?             (into #{:flipbook :bitmap}))
                          :animations (if v1? #{:flip-y} #{})
                          :timing     #{:always :windows :periodic}
                          :placement  #{:fixed :burst-scatter :per-window}
                          :codecs     codecs
                          :containers #{"mp4" "mov" "mkv"}
                          :audio      #{:copy :aac :none}
                          :sources    #{:file :url}
                          ;; written into the container: tags always; a cover
                          ;; frame as an attached JPEG, where mjpeg is built in
                          :extras     (cond-> #{:metadata} (contains? encoders "mjpeg") (conj :cover))
                          :preview    (if (every? filters plan/preview-filters) #{:frame :sample} #{})}}))))

(defn binary!
  "The path of binary `k` (:ffmpeg, :ffprobe) in `info`, else :unavailable."
  [info k]
  (or (get-in info [:binaries k :path])
      (throw (ex-info (or (first (:problems info)) not-found) {:wmark/error :unavailable}))))

;; ---------------------------------------------------------------------------
;; Planning

(defn plan-render
  "The engine plan for `request`, after the checks FFmpeg needs, with its
  scratch files under `workdir` (a folder of its own per render)."
  [info request workdir]
  (when-not (:available? info)
    (throw (ex-info (str/join " " (:problems info)) {:wmark/error :unavailable})))
  (engine/check! info request)
  ;; FFmpeg's MOV muxer drops an attached picture, and its Matroska
  ;; muxer turns one into a video track: only MP4 carries a real cover
  (when (and (:cover request) (not (:frame (:output request)))
             (not= "mp4" (get-in request [:output :container] "mp4")))
    (throw (ex-info "A cover picture needs an MP4 file: choose MP4 as the file format, or leave the cover out."
                    {:wmark/error :unsupported :missing [[:extras :cover]]})))
  (when-let [forced (get-in request [:encode :ffmpeg :video-codec])]
    (when-not (contains? (:encoders info) forced)
      (throw (ex-info (str "This FFmpeg build has no " forced " encoder.")
                      {:wmark/error :unsupported :missing [[:encoders forced]]}))))
  ((if (= 2 (get-in request [:spec :spec/version])) plan/compile-request-v2 plan/compile-request)
   request {:ffmpeg   (binary! info :ffmpeg)
            :version  (:version info)
            :encoders (:encoders info)
            :workdir  (str workdir)}))

(defn scratch-dir
  "A new scratch folder's path under `work-root`."
  [work-root]
  (files/join work-root (str (random-uuid))))

(defn log-path
  "Where a render's FFmpeg log goes: its scratch folder."
  [plan]
  (files/join (:workdir plan) "ffmpeg.log"))

;; ---------------------------------------------------------------------------
;; Running

(defn progress-reader
  "A function to call with each line FFmpeg writes with `-progress pipe:1`.
  At the end of each block it calls (on-progress event), the block read by
  watermark.ffmpeg.parse/parse-progress."
  [total-us on-progress]
  (let [block (volatile! {})]
    (fn [line]
      (let [[k v] (str/split (str line) #"=" 2)]
        (vswap! block assoc k v)
        (when (= "progress" k)
          (let [b @block]
            (vreset! block {})
            (on-progress (assoc (parse/parse-progress b total-us) :event :progress))))))))

(defn outcome
  "A render's outcome from how FFmpeg ended: `cancelled?` (the host stopped
  it), else its `exit` code, with the end of its log when it failed."
  [{:keys [cancelled? exit log-tail log]}]
  (cond cancelled?   {:status :cancelled}
        (zero? exit) {:status :done}
        :else        (engine/failed (str "FFmpeg failed (exit " exit "):\n" log-tail)
                                    {:log log :exit exit})))

(defn keep-scratch?
  "Whether a render's scratch folder stays: after a failure, its graph and
  log are what support needs."
  [outcome]
  (= :failed (:status outcome)))

(defn still
  "The decoded still of `decode-argv`'s run, or :invalid: `px` must hold
  width x height RGBA pixels."
  [{:keys [width height]} source {:keys [exit px err]} byte-count]
  (when-not (and (zero? exit) (= byte-count (* 4 width height)))
    (throw (ex-info (str "FFmpeg couldn't decode the image " source
                         (when-not (text/blank? err) (str ": " (text/trim err))))
                    {:wmark/error :invalid :path (str source)})))
  {:width width :height height :px px})
