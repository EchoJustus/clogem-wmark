;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg
  "FFmpegProcessor: the watermark.engine/VideoEngine implementation that
  drives the ffmpeg and ffprobe executables bundled next to wmark (or found
  on PATH). The default engine for Windows, Linux, Android-as-a-server and
  the serverless backend.

  This is the JVM's side: finding the binaries and running them. What they
  print and what it means (capabilities, request checks, plans, progress,
  outcomes) is the core library's (watermark.ffmpeg.engine), so every host's
  FFmpeg engine decides the same way. The job pipeline sees only the
  protocol."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.engine :as engine]
            [watermark.engine.ffmpeg.probe :as probe]
            [watermark.engine.ffmpeg.process :as process]
            [watermark.ffmpeg.engine :as fe]
            [watermark.util.os :as os]
            [watermark.util.task :as task])
  (:import (java.lang Process)))

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
        ff      (os/locate (merge base {:names [(exe "ffmpeg")] :explicit ffmpeg :search search :ok? executable?}))
        sibling (when (:path ff) (io/file (.getParentFile (io/file (:path ff))) (exe "ffprobe")))
        fp      (if (and sibling (executable? sibling))
                  {:path (str sibling) :source (:source ff) :trail []}
                  (os/locate (merge base {:names [(exe "ffprobe")] :search search :ok? executable?})))]
    {:ffmpeg ff :ffprobe fp}))

(defn- discover
  "Everything `info` reports: the binaries this host finds, what they print,
  and trial encodes, read by watermark.ffmpeg.engine/discover. Runs the
  binaries once; never throws."
  [opts]
  (let [{:keys [ffmpeg ffprobe] :as bins} (locate-binaries opts)
        found? (and (:path ffmpeg) (:path ffprobe))]
    (fe/discover {:binaries  bins
                  :warnings  (vec (keep os/working-dir-warning [ffmpeg ffprobe]))
                  :described (when found? (process/describe-binary (:path ffmpeg)))
                  :usable?   (fn [codec enc]
                               (process/trial-encode? (:path ffmpeg) (fe/trial-video-args codec enc)))})))

(defrecord FFmpegRender [result cancelled ^clojure.lang.Atom process]
  engine/RenderHandle
  (cancel! [_]
    (reset! cancelled true)
    (when-let [^Process p @process] (.destroy p)))
  (outcome [_] result))

(defn- delete-tree! [dir]
  (doseq [^java.io.File f (reverse (file-seq (io/file dir)))] (.delete f)))

(defrecord FFmpegProcessor [opts state]
  engine/VideoEngine
  (info [_]
    (force (:discovery state)))

  (probe [this source]
    (probe/probe (fe/binary! (engine/info this) :ffprobe) source))

  (prepare [this request]
    (fe/plan-render (engine/info this) request (fe/scratch-dir (str (:work-root opts)))))

  (execute! [_ plan listener]
    (let [result    (task/deferred)
          cancelled (atom false)
          proc      (atom nil)
          handle    (->FFmpegRender result cancelled proc)
          {:keys [workdir files argv total-us]} plan
          log       (fe/log-path plan)]
      (doto (Thread.
             ^Runnable
             (fn []
               (task/complete!
                result
                (try
                  (doseq [[path content] files]
                    (io/make-parents path)
                    (spit path content :encoding "UTF-8"))
                  (let [r          (process/run! argv {:log-file   log
                                                       :on-start   #(reset! proc %)
                                                       :cancelled? #(deref cancelled)
                                                       :on-line    (fe/progress-reader total-us #(when listener (listener %)))})
                        cancelled? (boolean (or (:cancelled r) @cancelled))
                        outcome    (fe/outcome {:cancelled? cancelled?
                                                :exit       (:exit r)
                                                :log        log
                                                :log-tail   (when-not (or cancelled? (zero? (long (:exit r))))
                                                              (process/log-tail log 12))})]
                    (when-not (fe/keep-scratch? outcome) (delete-tree! workdir))
                    outcome)
                  (catch Throwable t
                    (engine/failed (str "Could not run FFmpeg: " (ex-message t)))))))
             (str "wmark-ffmpeg-" (.getName (io/file workdir))))
        (.setDaemon true)
        (.start))
      handle)))

(defn- decode-still
  "The first frame of `source` as straight RGBA8 at its probed size, from one
  FFmpeg run (watermark.ffmpeg.engine/decode-argv)."
  [eng source]
  (let [size (engine/probe eng source)
        ^java.util.List argv (fe/decode-argv (fe/binary! (engine/info eng) :ffmpeg) source)
        p   (.start (ProcessBuilder. argv))
        err (future (slurp (.getErrorStream p)))
        ^bytes px (with-open [in (.getInputStream p)] (.readAllBytes in))]
    (fe/still size source {:exit (.waitFor p) :px px :err @err} (alength px))))

(defn- sample-video
  "The sample clip for previews (watermark.ffmpeg.engine/sample-argv)."
  [eng opts path]
  (let [r (process/exec (fe/sample-argv (fe/binary! (engine/info eng) :ffmpeg) opts path))]
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
