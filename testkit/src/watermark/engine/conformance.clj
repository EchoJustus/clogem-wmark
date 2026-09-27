;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.conformance
  "Engine conformance harness: render with a real engine, decode the frames,
  measure them, and compare with the kernel's reference semantics
  (watermark.render). Engine-agnostic -- it goes through the VideoEngine
  protocol only -- so the same harness will check the native Apple, Android
  or GPU-core engines. Frames are decoded with ffmpeg (test tooling only)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [watermark.core.features :as features]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.engine :as engine]
            [watermark.raster :as raster]
            [watermark.raster.local :as raster-local]
            [watermark.render :as render])
  (:import (java.io DataInputStream File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn tmp-dir [] (str (Files/createTempDirectory "wmark-conf" (make-array FileAttribute 0))))

(defn ffmpeg! [& args]
  (let [{:keys [exit err]} (apply sh/sh "ffmpeg" "-hide_banner" "-loglevel" "error" "-y" args)]
    (when-not (zero? exit) (throw (ex-info (str "ffmpeg failed: " err) {})))))

(defn ffmpeg-available? []
  (try (zero? (:exit (sh/sh "ffmpeg" "-version"))) (catch Exception _ false)))

(defn system-font []
  (some #(when (.isFile (io/file %)) %)
        ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
         "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"
         "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
         "C:/Windows/Fonts/arialbd.ttf"]))

(def ^:private bundled-font
  (delay (when-let [r (io/resource "fonts/wmark.ttf")]
           (let [f (io/file (tmp-dir) "wmark.ttf")]
             (with-open [in (io/input-stream r)] (io/copy in f))
             (str f)))))

(defn font
  "A TrueType font file for text layers: a common system font, else the
  bundled one (fonts/wmark.ttf on the classpath), so text is measured
  everywhere."
  []
  (or (system-font) @bundled-font))

(defn make-media!
  "White test clip (with a silent audio track) and a two-colour opaque logo.
  `start-s` > 0 produces a file whose video starts at that timestamp."
  [dir {:keys [w h fps seconds start-s] :or {w 640 h 360 fps 30 seconds 4 start-s 0}}]
  (let [clip (str (io/file dir (str "clip-" start-s ".mp4")))
        ;; white, so a lost alpha channel would show as a dark box
        logo (str (io/file dir "logo.png"))]
    (apply ffmpeg! (concat ["-f" "lavfi" "-i" (str "color=c=white:s=" w "x" h ":r=" fps)
                            "-f" "lavfi" "-i" "anullsrc=r=48000:cl=stereo"
                            "-t" (str seconds) "-c:v" "libx264" "-preset" "ultrafast" "-crf" "10"
                            "-pix_fmt" "yuv420p" "-c:a" "aac" "-shortest"]
                           (when (pos? start-s) ["-output_ts_offset" (str start-s)])
                           [clip]))
    (ffmpeg! "-f" "lavfi" "-i"
             (str "color=c=black@0:s=400x160,format=rgba,"
                  "drawbox=x=0:y=0:w=200:h=160:color=0x2255dd@1:t=fill:replace=1,"
                  "drawbox=x=200:y=0:w=200:h=160:color=0xff8800@1:t=fill:replace=1")
             "-frames:v" "1" logo)
    {:clip clip :logo logo}))

(defn render!
  "Plan and render `input` with `settings` through the engine protocol only.
  Returns {:spec :output :outcome :media :plan}. With :spec-version 2 the host
  rasterizes (watermark.raster, the local adapter) and the engine, which
  also decodes the logo, gets the v2 spec, returned as
  :v2; :spec stays the v1 spec, whose reference geometry the frames are
  measured against. The output is lossless H.264 in MP4 with the audio
  copied, unless :container, :codec and :audio say otherwise."
  [eng settings input {:keys [entitlements seed-fn out-dir spec-version container codec audio]
                       :or   {container "mp4" codec :h264 audio :copy}}]
  (let [media  (engine/probe eng input)
        logo   (get-in settings [:logo :path])
        spec   (render/build {:settings     (resolve/deep-merge schema/defaults settings)
                              :media        media
                              :logo-media   (when logo (engine/probe eng logo))
                              :seed-fn      (or seed-fn (constantly 42))
                              :entitlements (or entitlements (features/community))
                              :font         (font)})
        v2     (when (= 2 spec-version)
                 (raster/realize! (raster-local/local-rasterizer {:work-root out-dir}) eng spec))
        out    (str (io/file out-dir (str (.getName (io/file input)) ".out." container)))
        plan   (engine/prepare eng {:spec (or v2 spec) :source input :media media
                                    :output {:path out :container container}
                                    :encode {:codec codec :quality :archival :audio audio}
                                    :strip-metadata? true})
        result (deref (engine/outcome (engine/execute! eng plan nil)) 120000 {:status :timeout})]
    {:spec spec :v2 v2 :output out :outcome result :media media :plan plan}))

(defn gray-frames
  "Every frame of `path` as a luma byte array, decoded by ffmpeg."
  [path w h]
  (let [p  (.start (ProcessBuilder. ^java.util.List (vector "ffmpeg" "-v" "error" "-i" (str path) "-fps_mode" "passthrough"
                                                           "-f" "rawvideo" "-pix_fmt" "gray" "-")))
        in (DataInputStream. (.getInputStream p))
        size (* w h)]
    (loop [out []]
      (let [buf (byte-array size)
            ok  (try (.readFully in buf) true (catch java.io.EOFException _ false))]
        (if ok (recur (conj out buf)) (do (.waitFor p) out))))))

(defn- luma ^long [^bytes frame w x y] (bit-and 0xff (aget frame (int (+ x (* y w))))))

(defn bbox
  "[x0 y0 x1 y1] (inclusive) of pixels darker than `threshold` inside the
  region [rx0 ry0 rx1 ry1), or nil. (Test clips are white.)"
  [frame w [rx0 ry0 rx1 ry1] threshold]
  (let [pts (for [y (range ry0 ry1) x (range rx0 rx1) :when (< (luma frame w x y) threshold)] [x y])]
    (when (seq pts)
      [(apply min (map first pts)) (apply min (map second pts))
       (apply max (map first pts)) (apply max (map second pts))])))

(defn dark-count [frame w [rx0 ry0 rx1 ry1] threshold]
  (count (for [y (range ry0 ry1) x (range rx0 rx1) :when (< (luma frame w x y) threshold)] 1)))

(def sliver-px
  "Below this projected width the card is edge-on: a few anti-aliased pixels
  whose tips fall under any brightness threshold, so only the width (that the
  card really is edge-on, at the right frame) is meaningful there."
  8.0)

(defn logo-deviation
  "Worst |measured - reference| over all frames for the image layer's width,
  height and centre (pixels), with the frame where each occurs. Edge-on
  frames are checked for being edge-on only."
  [spec frames w region]
  (let [layer (first (render/layers-of spec :image))
        first-frame (get-in spec [:timebase :first-frame])
        rows (for [[i f] (map-indexed vector frames)
                   :let [n (+ first-frame i)
                         [ex0 ey0 ex1 ey1] (render/logo-bounds layer n)
                         [mx0 my0 mx1 my1 :as m] (bbox f w region 200)
                         ew (- ex1 ex0) sliver? (< ew sliver-px)]]
               (if (nil? m)
                 {:n n :dw ##Inf :dh ##Inf :dcx ##Inf :missing true}
                 {:n n
                  :dw  (if sliver? (max 0.0 (- (inc (- mx1 mx0)) sliver-px)) (Math/abs (double (- ew (inc (- mx1 mx0))))))
                  :dh  (if sliver? 0.0 (Math/abs (double (- (- ey1 ey0) (inc (- my1 my0))))))
                  :dcx (if sliver? 0.0 (Math/abs (double (- (/ (+ ex0 ex1) 2.0) (/ (+ mx0 mx1 1) 2.0)))))}))]
    {:width  (apply max-key :dw rows)
     :height (apply max-key :dh rows)
     :centre (apply max-key :dcx rows)
     :frames (count rows)}))

(defn visible-frames
  "Frames in which a text layer shows, measured vs reference."
  [spec frames w region layer-id]
  (let [layer (first (filter #(= layer-id (:id %)) (:layers spec)))
        first-frame (get-in spec [:timebase :first-frame])]
    ;; white text with a black@0.6 border on white (luma ~100): count border pixels
    {:measured  (vec (keep-indexed (fn [i f] (when (> (dark-count f w region 160) 15) (+ first-frame i))) frames))
     :reference (vec (filter #(render/active? (:timing layer) %) (range first-frame (+ first-frame (count frames)))))}))
