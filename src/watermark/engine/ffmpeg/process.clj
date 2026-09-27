;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.process
  "FFmpeg as a child process: capability discovery and supervised runs.
  Plain ProcessBuilder with absolute paths -- no shell, so no quoting problems,
  no injection, and no implicit OS search path."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io BufferedReader InputStreamReader)
           (java.lang ProcessBuilder$Redirect)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent TimeUnit)))

(set! *warn-on-reflection* true)

(def required-filters
  "Filters any plan may use. Minimal FFmpeg builds often lack drawtext (it
  needs libfreetype, and libharfbuzz since FFmpeg 7.0); without it the engine
  still renders logo-only specs and reports :text as unsupported."
  #{"perspective" "overlay" "colorchannelmixer" "scale" "format" "fps" "null" "setpts"
    "split" "crop" "drawbox"})

(def required-filters-v2
  "Filters a render spec v2 plan may use: host-rendered bitmaps only need
  compositing, which LGPL builds (no `perspective`) have."
  #{"overlay" "fps" "null"})

(defn exec
  "Run to completion, capturing stdout and stderr as UTF-8. Short commands only."
  [argv]
  (let [p   (.start (ProcessBuilder. ^java.util.List (mapv str argv)))
        err (future (slurp (.getErrorStream p) :encoding "UTF-8"))
        out (slurp (.getInputStream p) :encoding "UTF-8")]
    {:exit (.waitFor p) :out out :err @err}))

(defn parse-version
  "\"ffmpeg version 6.1.1-3ubuntu5 ...\" -> {:major 6 :minor 1}. Nightly builds
  (\"N-118896-g...\", \"2026-09-01-git-...\") have no release number: :major nil,
  treated as newest."
  [first-line]
  (if-let [[_ ma mi] (re-find #"version n?(\d+)\.(\d+)" (str first-line))]
    {:major (parse-long ma) :minor (parse-long mi) :raw first-line}
    {:major nil :raw first-line}))

(defn parse-filters
  "Names from `ffmpeg -filters`. Up to FFmpeg 8 each line has three flag
  columns (\" T.C drawtext  V->V  Draw text...\"); FFmpeg 9 dropped the
  command-support column (\" T. drawtext  V->V  ...\"). The in->out column
  keeps the legend lines (\" T.. = Timeline support\") out."
  [out]
  (into #{} (keep #(second (re-find #"^\s*[T.][S.][C.]?\s+(\S+)\s+\S*->\S*\s" %)))
        (str/split-lines out)))

(defn parse-encoders
  "Video encoder names from `ffmpeg -encoders` (lines like \" V....D libx264  ...\"),
  minus the legend (\" V..... = Video\")."
  [out]
  (into #{} (keep #(second (re-find #"^\s*V[.A-Z]{5}\s+([^\s=]\S*)\s" %))) (str/split-lines out)))

(defn describe-binary
  "Version, filters and video encoders of an ffmpeg executable."
  [ffmpeg]
  (let [v (exec [ffmpeg "-hide_banner" "-version"])
        f (exec [ffmpeg "-hide_banner" "-filters"])
        e (exec [ffmpeg "-hide_banner" "-encoders"])]
    {:version  (parse-version (first (str/split-lines (:out v))))
     :filters  (parse-filters (:out f))
     :encoders (parse-encoders (:out e))}))

(defn script-args
  "How to hand FFmpeg a filtergraph file. `-filter_complex_script` was
  deprecated in FFmpeg 7.0 in favour of the generic file-option prefix
  `-/filter_complex`; older releases only know the former."
  [{:keys [major]} graph-file]
  (if (or (nil? major) (>= (long major) 7))
    ["-/filter_complex" (str graph-file)]
    ["-filter_complex_script" (str graph-file)]))

(defn parse-progress
  "One -progress block (key -> value strings) -> {:out-us :fraction :speed :frame :done?}."
  [block total-us]
  (let [us (some-> (get block "out_time_us") parse-long)]
    {:out-us   us
     :frame    (some-> (get block "frame") parse-long)
     :fraction (when (and us total-us (pos? (long total-us)))
                 (max 0.0 (min 1.0 (/ (double us) (double total-us)))))
     :speed    (get block "speed")
     :done?    (= "end" (get block "progress"))}))

(defn run!
  "Run one encode to completion. `argv` includes the executable.

  stdout carries `-progress pipe:1` key=value blocks; stderr goes to
  `log-file` -- a file rather than a pipe, so FFmpeg can never block on a full
  pipe buffer, and the log survives for support requests. `on-start` receives
  the Process (so a canceller can destroy it at once); `cancelled?` is polled
  after every progress block.

  Returns {:exit n} or {:cancelled true}."
  [argv {:keys [log-file total-us on-progress on-start cancelled?]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str argv))
             (.redirectError (ProcessBuilder$Redirect/to (io/file log-file))))
        p  (.start pb)]
    (when on-start (on-start p))
    (try
      (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream p) StandardCharsets/UTF_8))]
        (loop [block {}]
          (when-let [line (.readLine r)]
            (let [[k v] (str/split line #"=" 2)
                  block (assoc block k v)]
              (if (= "progress" k)
                (do (when on-progress (on-progress (parse-progress block total-us)))
                    (when-not (and cancelled? (cancelled?)) (recur {})))
                (recur block))))))
      (if (and cancelled? (cancelled?))
        (do (.destroy p)                                 ; SIGTERM / TerminateProcess
            (when-not (.waitFor p 5 TimeUnit/SECONDS) (.destroyForcibly p))
            {:cancelled true})
        {:exit (.waitFor p)})
      (finally
        (when (.isAlive p) (.destroyForcibly p))))))

(defn log-tail
  "Last lines of an FFmpeg log, for error messages."
  [log-file n]
  (let [f (io/file log-file)]
    (when (.isFile f)
      (->> (str/split-lines (slurp f :encoding "UTF-8")) (take-last n) (str/join "\n")))))
