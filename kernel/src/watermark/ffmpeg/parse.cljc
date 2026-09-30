;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.ffmpeg.parse
  "What FFmpeg and ffprobe print, read into data: the version line, the
  `-filters` and `-encoders` lists, `-progress` blocks and ffprobe's media
  facts. Pure and part of the core library, so every host reads FFmpeg the
  same way (kernel/test/golden/ffmpeg.edn); the host runs the processes and
  decodes ffprobe's JSON.

  The patterns use explicit ASCII classes ([ \\t], [0-9]) rather than \\s or
  \\d, which the Dart VM reads more widely than the JVM."
  (:require [clojure.string :as str]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

(defn parse-version
  "\"ffmpeg version 6.1.1-3ubuntu5 ...\" -> {:major 6 :minor 1}. Nightly builds
  (\"N-118896-g...\", \"2026-09-01-git-...\") have no release number: :major nil,
  treated as newest."
  [first-line]
  (if-let [[_ ma mi] (re-find #"version n?([0-9]+)\.([0-9]+)" (str first-line))]
    {:major (number/parse-int ma) :minor (number/parse-int mi) :raw first-line}
    {:major nil :raw first-line}))

(defn parse-filters
  "Names from `ffmpeg -filters`. Up to FFmpeg 8 each line has three flag
  columns (\" T.C drawtext  V->V  Draw text...\"); FFmpeg 9 dropped the
  command-support column (\" T. drawtext  V->V  ...\"). The in->out column
  keeps the legend lines (\" T.. = Timeline support\") out."
  [out]
  (into #{} (keep #(second (re-find #"^[ \t]*[T.][S.][C.]?[ \t]+([^ \t]+)[ \t]+[^ \t]*->[^ \t]*[ \t]" %)))
        (str/split-lines out)))

(defn parse-encoders
  "Video encoder names from `ffmpeg -encoders` (lines like \" V....D libx264  ...\"),
  minus the legend (\" V..... = Video\")."
  [out]
  (into #{} (keep #(second (re-find #"^[ \t]*V[.A-Z]{5}[ \t]+([^ \t=][^ \t]*)[ \t]" %)))
        (str/split-lines out)))

(defn parse-progress
  "One -progress block (key -> value strings) -> {:out-us :fraction :speed :frame :done?}."
  [block total-us]
  (let [us (number/parse-int (get block "out_time_us"))]
    {:out-us   us
     :frame    (number/parse-int (get block "frame"))
     :fraction (when (and us total-us (pos? total-us))
                 (max 0.0 (min 1.0 (/ (* 1.0 us) total-us))))
     :speed    (get block "speed")
     :done?    (= "end" (get block "progress"))}))

;; ---------------------------------------------------------------------------
;; ffprobe

(def probe-entries
  "What ffprobe is asked for (`-show_entries`), JSON out."
  (str "stream=index,codec_type,width,height,r_frame_rate,avg_frame_rate,nb_frames,start_time"
       ":stream_side_data=rotation:format=duration,format_name"))

(defn probe-args
  "ffprobe's arguments for `input`, after the executable."
  [input]
  ["-v" "error" "-show_entries" probe-entries "-of" "json" (str input)])

(defn- ratio [s]
  (when-let [[_ a b] (re-matches #"([0-9]+)/([0-9]+)" (str s))]
    (let [a (number/parse-int a) b (number/parse-int b)]
      (when (and (pos? a) (pos? b)) [a b]))))

(defn- still-image? [format-name]
  (boolean (re-find #"(^|,)(image2|[a-z0-9]+_pipe)(,|$)" (str format-name))))

(defn probe-facts
  "ffprobe's JSON, decoded with keyword keys, -> media facts:
    {:kind :video|:image, :width :height (display size), :fps-num :fps-den,
     :frames, :duration-s, :start-s, :vfr?, :has-audio?, :rotation}

  :width/:height are *display* dimensions: phone footage stores a rotation in
  the display matrix and FFmpeg auto-rotates while decoding, so the
  filtergraph sees portrait video with width and height swapped."
  [{:keys [streams format]}]
  (let [v (or (first (filter #(= "video" (:codec_type %)) streams))
              (throw (ex-info "No video stream." {:wmark/error :invalid})))
        rot      (or (some :rotation (:side_data_list v)) 0)
        quarter? (= 90 (mod (abs (number/floor-int rot)) 180))
        image?   (still-image? (:format_name format))
        [fa fb :as avg] (or (ratio (:avg_frame_rate v)) (ratio (:r_frame_rate v)))
        r        (ratio (:r_frame_rate v))
        fps      (when avg (/ (* 1.0 fa) fb))
        duration (number/parse-decimal (:duration format))]
    (when-not (or image? avg)
      (throw (ex-info "Unknown frame rate." {:wmark/error :invalid})))
    (cond-> {:kind       (if image? :image :video)
             :width      (if quarter? (:height v) (:width v))
             :height     (if quarter? (:width v) (:height v))
             :rotation   rot
             :has-audio? (boolean (some #(= "audio" (:codec_type %)) streams))}
      (not image?)
      (assoc :fps-num    fa
             :fps-den    fb
             :frames     (or (number/parse-int (:nb_frames v))
                             (when duration (number/round-half-up (* duration fps))))
             :duration-s duration
             :start-s    (or (number/parse-decimal (:start_time v)) 0.0)
             ;; r_frame_rate is the timebase-derived rate, avg the measured one;
             ;; when they disagree the stream is variable-rate
             :vfr?       (boolean (and r (> (abs (- (/ (* 1.0 (first r)) (second r)) fps))
                                            (* 0.005 fps))))))))
