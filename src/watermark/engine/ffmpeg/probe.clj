;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.probe
  "ffprobe -> the engine-neutral media facts of watermark.engine/probe. The
  JVM runs ffprobe and decodes its JSON; the core library reads the facts
  (watermark.ffmpeg.parse/probe-facts)."
  (:require [clojure.data.json :as json]
            [watermark.engine.ffmpeg.process :as process]
            [watermark.ffmpeg.parse :as parse]))

(set! *warn-on-reflection* true)

(defn args [input] (parse/probe-args input))

(defn parse
  "ffprobe JSON text -> media facts (watermark.ffmpeg.parse/probe-facts)."
  [json-str]
  (parse/probe-facts (json/read-str json-str :key-fn keyword)))

(defn probe
  "Probe `input` with the ffprobe executable at `ffprobe`."
  [ffprobe input]
  (let [{:keys [exit out err]} (process/exec (into [ffprobe] (args input)))]
    (if (zero? exit)
      (parse out)
      (throw (ex-info (str "Can't read " input ": " err)
                      {:wmark/error :invalid :path (str input)})))))
