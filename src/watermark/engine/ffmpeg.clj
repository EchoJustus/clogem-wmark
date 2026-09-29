;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg
  "FFmpegProcessor: the watermark.engine/VideoEngine implementation that
  drives the ffmpeg and ffprobe executables bundled next to wmark (or found
  on PATH). The default engine for Windows, Linux, Android-as-a-server and
  the serverless backend.

  Everything FFmpeg-specific -- binary discovery, filtergraph compilation,
  version quirks, progress parsing -- stays behind this namespace; the job
  pipeline sees only the protocol."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.engine :as engine]
            [watermark.engine.ffmpeg.compile :as compile]
            [watermark.engine.ffmpeg.graph :as g]
            [watermark.engine.ffmpeg.probe :as probe]
            [watermark.engine.ffmpeg.process :as process]
            [watermark.util.locate :as locate])
  (:import (java.lang Process)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- exe [base]
  (if (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "windows")
    (str base ".exe")
    base))

(defn- executable? [^java.io.File f] (and (.isFile f) (.canExecute f)))

(defn locate-binaries
  "Resolve ffmpeg, then ffprobe -- preferring the ffprobe next to the chosen
  ffmpeg, so the two come from the same build whenever possible. `:cwd` and
  `:app-dir` override the working and install folders (tests)."
  [{:keys [ffmpeg search] :as opts}]
  (let [base    (select-keys opts [:cwd :app-dir])
        ff      (locate/locate (merge base {:names [(exe "ffmpeg")] :explicit ffmpeg :search search :ok? executable?}))
        sibling (when (:path ff) (io/file (.getParentFile (io/file (:path ff))) (exe "ffprobe")))
        fp      (if (and sibling (executable? sibling))
                  {:path (str sibling) :source (:source ff) :trail []}
                  (locate/locate (merge base {:names [(exe "ffprobe")] :search search :ok? executable?})))]
    {:ffmpeg ff :ffprobe fp}))

(defn split-build-warning
  "A warning when ffprobe was found somewhere other than ffmpeg's folder: it
  works on this machine, but a bundle missing its own ffprobe would fail on a
  clean one."
  [{:keys [ffmpeg ffprobe]}]
  (let [dir #(some-> % :path io/file .getAbsoluteFile .getParent)]
    (when (and (:path ffmpeg) (:path ffprobe) (not= (dir ffmpeg) (dir ffprobe)))
      (str "ffprobe was found in " (dir ffprobe) ", not next to ffmpeg in " (dir ffmpeg)
           ". Ship both together: a machine without the second copy can't probe media."))))

(defn- discover
  "Everything `info` reports. Runs the binaries once; never throws."
  [opts]
  (let [{:keys [ffmpeg ffprobe] :as bins} (locate-binaries opts)]
    (if-not (and (:path ffmpeg) (:path ffprobe))
      {:engine/id :ffmpeg :available? false :binaries bins
       :problems ["FFmpeg not found. Put ffmpeg and ffprobe next to wmark (or in its bin/ folder), on PATH, or pass --ffmpeg."]
       :capabilities {}}
      (let [{:keys [version filters] listed :encoders} (process/describe-binary (:path ffmpeg))
            encoders  (compile/usable-encoders
                       listed
                       (fn [codec enc]
                         (process/trial-encode? (:path ffmpeg)
                                                (compile/video-args {:codec codec} enc {:width 256 :height 144} 30))))
            unusable  (sort (remove encoders listed))
            missing   (remove filters process/required-filters)
            v1?       (empty? missing)
            ;; spec v2 (host-rendered bitmaps) needs compositing only: LGPL
            ;; builds, which lack the GPL-only perspective filter, qualify
            v2?       (every? filters process/required-filters-v2)
            text?     (contains? filters "drawtext")
            codecs    (compile/codecs-available encoders)]
        {:engine/id      :ffmpeg
         :engine/version (or (second (re-find #"version\s+n?(\S+)" (str (:raw version)))) "unknown")
         :available?     (and (or v1? v2?) (seq codecs) true)
         :problems       (cond-> []
                           (not (or v1? v2?)) (conj (str "This FFmpeg build lacks required filters: " (str/join ", " missing)))
                           (and v1? (not text?) (not v2?)) (conj "This FFmpeg build has no drawtext filter (needs libfreetype/libharfbuzz): text layers are unavailable.")
                           (empty? codecs) (conj "This FFmpeg build has no H.264 or HEVC encoder."))
         :warnings       (vec (concat (keep locate/working-dir-warning [ffmpeg ffprobe])
                                      (some-> (split-build-warning bins) vector)
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
         :binaries       bins
         :version        version
         :encoders       encoders
         :capabilities   {:spec-versions (cond-> #{} v1? (conj 1) v2? (conj 2))
                          :layers     (cond-> #{}
                                        v1?           (conj :image)
                                        (and v1? text?) (conj :text)
                                        v2?           (into #{:flipbook :bitmap}))
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
                          :preview    (if (every? filters process/preview-filters) #{:frame :sample} #{})}}))))

(defrecord FFmpegRender [result cancelled ^clojure.lang.Atom process]
  engine/RenderHandle
  (cancel! [_]
    (reset! cancelled true)
    (when-let [^Process p @process] (.destroy p)))
  (outcome [_] result))

(defn- scratch-dir [work-root]
  (str (io/file (str work-root) (str (UUID/randomUUID)))))

(defn- delete-tree! [dir]
  (doseq [^java.io.File f (reverse (file-seq (io/file dir)))] (.delete f)))

(defrecord FFmpegProcessor [opts state]
  engine/VideoEngine
  (info [_]
    (force (:discovery state)))

  (probe [this source]
    (let [{:keys [available? binaries problems]} (engine/info this)]
      (when-not (get-in binaries [:ffprobe :path])
        (throw (ex-info (first problems) {:wmark/error :unavailable})))
      (probe/probe (get-in binaries [:ffprobe :path]) source)))

  (prepare [this request]
    (let [info (engine/info this)]
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
      ((if (= 2 (get-in request [:spec :spec/version])) compile/compile-request-v2 compile/compile-request)
       request {:ffmpeg   (get-in info [:binaries :ffmpeg :path])
                :version  (:version info)
                :encoders (:encoders info)
                :workdir  (scratch-dir (:work-root opts))})))

  (execute! [_ plan listener]
    (let [result    (promise)
          cancelled (atom false)
          proc      (atom nil)
          handle    (->FFmpegRender result cancelled proc)
          {:keys [workdir files argv total-us]} plan
          log       (str (io/file workdir "ffmpeg.log"))]
      (doto (Thread.
             ^Runnable
             (fn []
               (deliver
                result
                (try
                  (doseq [[path content] files]
                    (io/make-parents path)
                    (spit path content :encoding "UTF-8"))
                  (let [r (process/run! argv {:log-file    log
                                              :total-us    total-us
                                              :on-start    #(reset! proc %)
                                              :cancelled?  #(deref cancelled)
                                              :on-progress #(when listener (listener (assoc % :event :progress)))})]
                    (cond
                      (or (:cancelled r) @cancelled)
                      (do (delete-tree! workdir) {:status :cancelled})

                      (zero? (long (:exit r)))
                      (do (delete-tree! workdir) {:status :done})

                      :else ; keep the scratch dir: graph + log are what support needs
                      (engine/failed (str "FFmpeg failed (exit " (:exit r) "):\n" (process/log-tail log 12))
                                     {:log log :exit (:exit r)})))
                  (catch Throwable t
                    (engine/failed (str "Could not run FFmpeg: " (ex-message t)))))))
               (str "wmark-ffmpeg-" (.getName (io/file workdir))))
        (.setDaemon true)
        (.start))
      handle)))

(defn- decode-still
  "The first frame of `source` as straight RGBA8 at its probed size, from one
  `ffmpeg ... -f rawvideo -pix_fmt rgba -` (PNG decoding is lossless, so the
  pixels don't depend on the FFmpeg version)."
  [eng source]
  (let [{:keys [width height]} (engine/probe eng source)
        ffmpeg (or (get-in (engine/info eng) [:binaries :ffmpeg :path])
                   (throw (ex-info "FFmpeg not found." {:wmark/error :unavailable})))
        ^java.util.List argv [ffmpeg "-v" "error" "-nostdin" "-i" (str source)
                              "-frames:v" "1" "-f" "rawvideo" "-pix_fmt" "rgba" "-"]
        p   (.start (ProcessBuilder. argv))
        ^bytes px (with-open [in (.getInputStream p)] (.readAllBytes in))
        err (slurp (.getErrorStream p))]
    (when-not (and (zero? (.waitFor p)) (= (alength px) (* 4 (long width) (long height))))
      (throw (ex-info (str "FFmpeg couldn't decode the image " source
                           (when-not (str/blank? err) (str ": " (str/trim err))))
                      {:wmark/error :invalid :path (str source)})))
    {:width width :height height :px px}))

(defn- sample-video
  "A calm clip for previews before a video is chosen: neutral grey with a
  faint grid, so marks of any colour show against it. FFmpeg's own MPEG-4
  encoder, which every build has (LGPL builds lack x264)."
  [eng {:keys [width height fps seconds]} path]
  (let [ffmpeg (or (get-in (engine/info eng) [:binaries :ffmpeg :path])
                   (throw (ex-info "FFmpeg not found." {:wmark/error :unavailable})))
        graph  (g/render [(g/chain []
                                   [(g/f "color" :c "0x5f6b78" :s (str width "x" height) :r fps :d seconds)
                                    (g/f "drawgrid" :w 64 :h 64 :t 1 :c "white@0.14")]
                                   ["v"])])
        r      (process/exec [ffmpeg "-hide_banner" "-nostdin" "-y" "-loglevel" "error"
                              "-filter_complex" graph "-map" "[v]"
                              "-c:v" "mpeg4" "-q:v" "3" "-pix_fmt" "yuv420p" (str path)])]
    (when-not (zero? (long (:exit r)))
      (throw (ex-info (str "FFmpeg couldn't make the sample clip: " (str/trim (str (:err r))))
                      {:wmark/error :unavailable})))
    (str path)))

(extend-type FFmpegProcessor
  engine/StillDecoder
  (decode-still [eng source] (decode-still eng source))
  engine/SampleSource
  (sample-video [eng opts path] (sample-video eng opts path)))

(defn ffmpeg-engine
  "FFmpeg engine.
    :ffmpeg    explicit ffmpeg path or directory (--ffmpeg / WMARK_FFMPEG)
    :search    locate order (default watermark.util.locate/default-search)
    :work-root directory for per-render scratch dirs (graph, text files, log)
  Discovery runs lazily, once, on first use."
  [opts]
  (->FFmpegProcessor opts {:discovery (delay (discover opts))}))
