;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.probe
  "ffprobe -> the engine-neutral media facts of watermark.engine/probe."
  (:require [clojure.data.json :as json]
            [watermark.engine.ffmpeg.process :as process]))

(defn args [input]
  ["-v" "error"
   "-show_entries" (str "stream=index,codec_type,width,height,r_frame_rate,avg_frame_rate,nb_frames,start_time"
                        ":stream_side_data=rotation:format=duration,format_name")
   "-of" "json" (str input)])

(defn- ratio [s]
  (when-let [[_ a b] (re-matches #"(\d+)/(\d+)" (str s))]
    (let [a (parse-long a) b (parse-long b)]
      (when (and (pos? a) (pos? b)) [a b]))))

(defn- still-image? [format-name]
  (boolean (re-find #"(^|,)(image2|[a-z0-9]+_pipe)(,|$)" (str format-name))))

(defn parse
  "ffprobe JSON -> media facts:
    {:kind :video|:image, :width :height (display size), :fps-num :fps-den,
     :frames, :duration-s, :start-s, :vfr?, :has-audio?, :rotation}

  :width/:height are *display* dimensions: phone footage stores a rotation in
  the display matrix and FFmpeg auto-rotates while decoding, so the
  filtergraph sees portrait video with width and height swapped."
  [json-str]
  (let [{:keys [streams format]} (json/read-str json-str :key-fn keyword)
        v (or (first (filter #(= "video" (:codec_type %)) streams))
              (throw (ex-info "No video stream." {:wmark/error :invalid})))
        rot      (or (some :rotation (:side_data_list v)) 0)
        quarter? (= 90 (mod (Math/abs (long rot)) 180))
        image?   (still-image? (:format_name format))
        [fa fb :as avg] (or (ratio (:avg_frame_rate v)) (ratio (:r_frame_rate v)))
        r        (ratio (:r_frame_rate v))
        fps      (when avg (/ (double fa) (double fb)))
        duration (some-> (:duration format) parse-double)]
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
             :frames     (or (some-> (:nb_frames v) parse-long)
                             (when duration (Math/round (* (double duration) fps))))
             :duration-s duration
             :start-s    (or (some-> (:start_time v) parse-double) 0.0)
             ;; r_frame_rate is the timebase-derived rate, avg the measured one;
             ;; when they disagree the stream is variable-rate
             :vfr?       (boolean (and r (> (Math/abs (- (/ (double (first r)) (double (second r))) fps))
                                            (* 0.005 fps))))))))

(defn probe
  "Probe `input` with the ffprobe executable at `ffprobe`."
  [ffprobe input]
  (let [{:keys [exit out err]} (process/exec (into [ffprobe] (args input)))]
    (if (zero? exit)
      (parse out)
      (throw (ex-info (str "Can't read " input ": " err)
                      {:wmark/error :invalid :path (str input)})))))
