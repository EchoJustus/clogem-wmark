;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.process
  "FFmpeg as a child process on the JVM: short commands, trials with a
  time limit, and supervised renders. Plain ProcessBuilder with absolute
  paths -- no shell, so no quoting problems, no injection, and no implicit
  OS search path. The command lines and what FFmpeg prints are the core
  library's (watermark.ffmpeg.engine, watermark.ffmpeg.parse); this is the
  JVM's process side."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.ffmpeg.engine :as fe])
  (:import (java.io BufferedReader InputStreamReader)
           (java.lang ProcessBuilder$Redirect)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent TimeUnit)))

(set! *warn-on-reflection* true)

(defn exec
  "Run to completion, capturing stdout and stderr as UTF-8. Short commands only."
  [argv]
  (let [p   (.start (ProcessBuilder. ^java.util.List (mapv str argv)))
        err (future (slurp (.getErrorStream p) :encoding "UTF-8"))
        out (slurp (.getInputStream p) :encoding "UTF-8")]
    {:exit (.waitFor p) :out out :err @err}))

(defn passes?
  "Does `argv` exit 0 within `timeout-ms`? Output is thrown away; a command
  still running then is killed."
  [argv timeout-ms]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str argv))
                    (.redirectErrorStream true)
                    (.redirectOutput ProcessBuilder$Redirect/DISCARD)))]
    (if (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
      (zero? (.exitValue p))
      (do (.destroyForcibly p) false))))

(defn trial-encode?
  "Does `ffmpeg` encode a few frames of a test pattern with the encoder
  arguments `video-args` (watermark.ffmpeg.engine/trial-argv)? Gives up
  after 30 s."
  [ffmpeg video-args]
  (passes? (fe/trial-argv ffmpeg video-args) 30000))

(defn describe-binary
  "Version, filters and video encoders of an ffmpeg executable
  (watermark.ffmpeg.engine/describe of its outputs)."
  [ffmpeg]
  (fe/describe (update-vals (fe/describe-argv ffmpeg) (comp :out exec))))

(defn run!
  "Run one encode to completion. `argv` includes the executable.

  stdout carries `-progress pipe:1` key=value lines, each handed to
  `on-line` (watermark.ffmpeg.engine/progress-reader reads them); stderr
  goes to `log-file` -- a file rather than a pipe, so FFmpeg can never block
  on a full pipe buffer, and the log survives for support requests.
  `on-start` receives the Process (so a canceller can destroy it at once);
  `cancelled?` is polled after every line.

  Returns {:exit n} or {:cancelled true}."
  [argv {:keys [log-file on-line on-start cancelled?]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str argv))
             (.redirectError (ProcessBuilder$Redirect/to (io/file log-file))))
        p  (.start pb)]
    (when on-start (on-start p))
    (try
      (with-open [r (BufferedReader. (InputStreamReader. (.getInputStream p) StandardCharsets/UTF_8))]
        (loop []
          (when-let [line (.readLine r)]
            (when on-line (on-line line))
            (when-not (and cancelled? (cancelled?)) (recur)))))
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
