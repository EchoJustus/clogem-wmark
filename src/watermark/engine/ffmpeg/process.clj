;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.process
  "FFmpeg as a child process: capability discovery and supervised runs.
  Plain ProcessBuilder with absolute paths -- no shell, so no quoting problems,
  no injection, and no implicit OS search path. What FFmpeg prints is read by
  the core library (watermark.ffmpeg.parse); this is the JVM's process side."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.ffmpeg.parse :as parse])
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

(defn trial-encode?
  "Does `ffmpeg` encode a few frames of a test pattern with the encoder
  arguments `video-args`? For encoders a build lists but a machine may not
  run. Gives up after 30 s."
  [ffmpeg video-args]
  (let [argv (concat [ffmpeg "-hide_banner" "-v" "error" "-nostdin"
                      "-f" "lavfi" "-i" "color=c=gray:s=256x144:r=30" "-frames:v" "5"]
                     video-args ["-f" "null" "-"])
        p    (.start (doto (ProcessBuilder. ^java.util.List (mapv str argv))
                       (.redirectErrorStream true)
                       (.redirectOutput ProcessBuilder$Redirect/DISCARD)))]
    (if (.waitFor p 30 TimeUnit/SECONDS)
      (zero? (.exitValue p))
      (do (.destroyForcibly p) false))))

(defn describe-binary
  "Version, filters and video encoders of an ffmpeg executable."
  [ffmpeg]
  (let [v (exec [ffmpeg "-hide_banner" "-version"])
        f (exec [ffmpeg "-hide_banner" "-filters"])
        e (exec [ffmpeg "-hide_banner" "-encoders"])]
    {:version  (parse/parse-version (first (str/split-lines (:out v))))
     :filters  (parse/parse-filters (:out f))
     :encoders (parse/parse-encoders (:out e))}))

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
                (do (when on-progress (on-progress (parse/parse-progress block total-us)))
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
