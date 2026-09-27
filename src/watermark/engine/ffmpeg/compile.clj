;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg.compile
  "Render spec -> one FFmpeg invocation (filtergraph + argv + scratch files).

  Pure: the engine writes the files and runs the process. Every construct
  here implements a function of the kernel's reference semantics
  (watermark.render); the conformance test renders real frames and measures
  them against those functions.

  Frame indices: the spec counts frames globally (first-frame + i). drawtext
  exposes the local 0-based count as `n`; perspective exposes a 1-based `in`
  (inlink->frame_count_out + 1 in FFmpeg 6.1, 7.1 and master), hence (in-1).
  overlay's per-frame x and y see a 1-based `n` too (framesync counts the
  main frame as consumed before the blend; vf_overlay.c, 9.0.2), hence (n-1)
  there, while its `enable` timeline sees the 0-based `n`."
  (:require [clojure.string :as str]
            [watermark.engine.ffmpeg.graph :as g]
            [watermark.engine.ffmpeg.process :as process])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn ffmpeg-path
  "Forward slashes: FFmpeg accepts them on Windows too, and they need no
  escaping inside a filtergraph."
  [p]
  (str/replace (str p) "\\" "/"))

(defn- frame-var
  "Expression for the global frame index given the filter's local counter."
  [local first-frame]
  (if (pos? first-frame) (str "(" local "+" first-frame ")") local))

;; ---------------------------------------------------------------------------
;; Timing and placement

(defn timing-expr
  "enable= expression for a spec timing; nil for :always."
  [{:keys [type windows offset period length]} n]
  (case type
    :always   nil
    :windows  (if (empty? windows)
                "0"
                ;; flat sum, never nested if(): the parser caps nesting at 100
                (str/join "+" (for [{:keys [start end]} windows] (g/fmt "between(%s,%d,%d)" n start end))))
    :periodic (g/fmt "gte(%s,%d)*lt(mod(%s-%d,%d),%d)" n offset n offset period length)))

(defn- scatter-expr [{:keys [a b]} {:keys [margin modulus]} {:keys [offset period]} n]
  (g/fmt "(%s+%s*mod(floor((%s-%d)/%d)*%d+%d,%d)/%d)"
         (g/num-str margin) (g/num-str (- 1.0 (* 2.0 margin))) n offset period a b modulus modulus))

(defn- per-window-expr [windows points axis n]
  (if (empty? windows)
    "0"
    (str "(" (str/join "+" (for [[{:keys [start end]} pt] (map vector windows points)]
                             (g/fmt "between(%s,%d,%d)*%s" n start end (g/num-str (nth pt axis)))))
         ")")))

(defn placement-exprs
  "x and y expressions: x = f(n) * free-x + px, likewise y. The free space is
  (w-tw) for drawtext (the default) and (W-w) for overlay."
  ([layer n] (placement-exprs layer n ["(w-tw)" "(h-th)"]))
  ([{:keys [placement timing]} n [free-x free-y]]
  (let [{:keys [type fx fy px py] :or {px 0 py 0}} placement
        lin (fn [free frac off]
              (let [frac-part (cond (and (number? frac) (zero? frac)) nil
                                    (and (number? frac) (== frac 1.0)) free
                                    :else (str free "*" (if (number? frac) (g/num-str frac) frac)))]
                (cond (nil? frac-part) (str "(" (g/num-str off) ")")
                      (zero? off)      frac-part
                      :else            (str frac-part "+(" (g/num-str off) ")"))))]
    (case type
      :fixed         [(lin free-x fx px) (lin free-y fy py)]
      :burst-scatter [(lin free-x (scatter-expr (:x placement) placement timing n) 0)
                      (lin free-y (scatter-expr (:y placement) placement timing n) 0)]
      :per-window    [(lin free-x (per-window-expr (:windows timing) (:points placement) 0 n) 0)
                      (lin free-y (per-window-expr (:windows timing) (:points placement) 1 n) 0)]))))

;; ---------------------------------------------------------------------------
;; Image layers: scale, opacity, transparent canvas, flip

(defn canvas-padding
  "Transparent margin around an animated image so its perspective bulge fits:
  the near edge grows by at most D/(D - w/2)."
  [{:keys [width height]} {:keys [distance]}]
  (let [grow (/ distance (- distance (/ width 2.0)))]
    {:pad-x (+ 2 (long (Math/ceil (* width 0.03))))
     :pad-y (+ 2 (long (Math/ceil (* (/ height 2.0) (- grow 1.0)))))}))

(defn flip-corners
  "The eight `perspective` corner expressions (sense=destination) mapping the
  padded canvas like the reference projection in watermark.render/logo-corners.
  Registers per expression: 0 = theta, 1 = cos (clamped), 2 = sin. W is the
  canvas width; D the camera distance in pixels."
  [{:keys [start period duration distance min-cos]} first-frame]
  (let [n   (frame-var "(in-1)" first-frame)
        p   (g/fmt "mod(%s-%d,%d)" n start period)
        m   (g/num-str min-cos)
        pre (g/fmt (str "st(0,if(gte(%s,%d)*lt(%s,%d),PI*(1-cos(PI*%s/%d)),0));st(1,cos(ld(0)));"
                        "st(1,if(lt(abs(ld(1)),%s),if(gte(ld(1),0),%s,-%s),ld(1)));st(2,sin(ld(0)));")
                   n start p duration p duration m m m)
        d   (g/num-str distance)
        right (g/fmt "%s/(%s-0.5*W*ld(2))" d d)
        left  (g/fmt "%s/(%s+0.5*W*ld(2))" d d)
        e     #(g/expr (str pre %))]
    [:x0 (e (str "W/2*(1-ld(1)*" left ")"))  :y0 (e (str "H/2*(1-" left ")"))
     :x1 (e (str "W/2*(1+ld(1)*" right ")")) :y1 (e (str "H/2*(1-" right ")"))
     :x2 (e (str "W/2*(1-ld(1)*" left ")"))  :y2 (e (str "H/2*(1+" left ")"))
     :x3 (e (str "W/2*(1+ld(1)*" right ")")) :y3 (e (str "H/2*(1+" right ")"))]))

(defn image-chains
  "Filter chains compositing one image layer (input `idx`, a single still
  frame) onto the stream labelled `prev`, producing the stream `out`.

  Frame alignment by construction: the flipping card is not a separate
  looped stream paired with the video by timestamps -- files whose video
  doesn't start at t=0 (AAC priming, edit lists, trimmed files) would pair
  the wrong frames. Instead each video frame is split off, cropped to the
  card's canvas, made fully transparent, and the still logo is composited on
  it. The card therefore has exactly one frame per video frame with the same
  timestamp, and perspective's frame counter is the video's frame counter."
  [{:keys [box opacity animation]} idx prev out first-frame]
  (let [{:keys [width height x y]} box
        still  (str "still" idx)
        prep   (cond-> [(g/f "format" :pix_fmts "rgba")
                        (g/f "scale" :w width :h height :flags "lanczos")]
                 (< opacity 1.0) (conj (g/f "colorchannelmixer" :aa opacity))
                 true            (conj (g/f "setpts" :expr (g/expr "PTS-STARTPTS"))))
        still-chain (g/chain [(str idx ":v")] prep [still])]
    (if-not animation
      [still-chain
       (g/chain [prev still] [(g/f "overlay" :x x :y y :eof_action "repeat")] [out])]
      (let [{:keys [pad-x pad-y]} (canvas-padding box animation)
            cw (+ width (* 2 pad-x)) ch (+ height (* 2 pad-y))
            main (str "main" idx) tick (str "tick" idx) canvas (str "canvas" idx) card (str "card" idx)]
        [still-chain
         (g/chain [prev] [(g/f "split")] [main tick])
         (g/chain [tick] [(g/f "crop" :w cw :h ch :x 0 :y 0)
                          (g/f "format" :pix_fmts "rgba")
                          (g/f "drawbox" :x 0 :y 0 :w "iw" :h "ih" :color "black@0" :t "fill" :replace 1)]
                  [canvas])
         (g/chain [canvas still] [(g/f "overlay" :x pad-x :y pad-y :eof_action "repeat" :format "auto")] [card])
         (g/chain [card] [(g/f "format" :pix_fmts "yuva444p")
                          (apply g/f "perspective"
                                 (concat (flip-corners animation first-frame)
                                         [:interpolation "linear" :sense "destination" :eval "frame"]))]
                  [(str "img" idx)])
         (g/chain [main (str "img" idx)] [(g/f "overlay" :x (- x pad-x) :y (- y pad-y))] [out])]))))

;; ---------------------------------------------------------------------------
;; Encoding

(def ^:private encoder-preference
  "Encoders per codec family, best first: software x264/x265 (deterministic),
  then OS encoders (LGPL-only builds: Media Foundation, VideoToolbox), then the
  LGPL-compatible software encoders (OpenH264, Kvazaar), which always work,
  and last vendor hardware, which may be listed yet absent at run time (an
  LGPL build lists NVENC, QSV and AMF on machines without the hardware)."
  {:h264 ["libx264" "h264_videotoolbox" "h264_mf" "libopenh264" "h264_nvenc" "h264_qsv" "h264_amf"]
   :hevc ["libx265" "hevc_videotoolbox" "hevc_mf" "libkvazaar" "hevc_nvenc" "hevc_qsv" "hevc_amf"]})

(def ^:private x264-crf {:archival 14 :high 18 :balanced 22 :compact 26})
(def ^:private bits-per-pixel {:archival 0.20 :high 0.12 :balanced 0.08 :compact 0.05})

(defn pick-encoder [{:keys [codec ffmpeg]} encoders]
  (or (:video-codec ffmpeg)
      (some encoders (encoder-preference (or codec :h264)))))

(defn codecs-available
  "Codec families some encoder in `encoders` can produce."
  [encoders]
  (set (for [[codec names] encoder-preference :when (some encoders names)] codec)))

(defn usable-encoders
  "`encoders` minus the ones that fail on this machine. A build lists what it
  was compiled with, not what runs here: vendor hardware that isn't there,
  Media Foundation on Windows N and Server editions, VideoToolbox's hardware
  encoder in a VM. Per codec family, the listed encoders are tried in
  preference order, `(works? codec encoder)`, until one works; later ones
  are never picked, so they aren't tried."
  [encoders works?]
  (let [failed (reduce (fn [failed [codec names]]
                         (into failed (reduce (fn [acc enc]
                                                (if (works? codec enc) (reduced acc) (conj acc enc)))
                                              [] (filter encoders names))))
                       #{} encoder-preference)]
    (reduce disj (set encoders) failed)))

(defn video-args
  "Encoder arguments. Quality tiers map to CRF for x264/x265 and CQ for NVENC;
  other encoders get a bitrate target from the tier and the frame size."
  [{:keys [codec quality ffmpeg] :or {codec :h264 quality :high}} encoder {:keys [width height]} fps]
  (let [crf    (or (:crf ffmpeg) (cond-> (x264-crf quality 18) (= codec :hevc) (+ 5)))
        preset (:preset ffmpeg)]
    (cond
      (#{"libx264" "libx265"} encoder)
      (cond-> ["-c:v" encoder "-crf" (str crf) "-preset" (or preset "medium")]
        (= encoder "libx265") (into ["-tag:v" "hvc1"]))           ; plays in QuickTime/iOS

      (str/ends-with? encoder "_nvenc")
      ["-c:v" encoder "-rc" "vbr" "-cq" (str crf) "-preset" (or preset "p5")]

      :else
      (let [bps (* (bits-per-pixel quality 0.12) width height fps (if (= codec :hevc) 0.6 1.0))]
        (cond-> ["-c:v" encoder "-b:v" (str (Math/round (double bps)))]
          ;; the hardware encoder when there is one, else Apple's software
          ;; encoder (FFmpeg otherwise demands hardware, which VMs lack)
          (str/ends-with? encoder "_videotoolbox") (into ["-allow_sw" "1"]))))))

(defn frame-sync-args
  "Pass frames through exactly as the filtergraph produced them. FFmpeg's
  default constant-rate sync duplicates or drops frames to fill timestamp
  gaps -- e.g. it repeats frame 0 when AAC priming makes the audio start a
  few milliseconds before the video -- which would shift every output frame
  against the watermark schedule and break frame-exact evidence."
  [{:keys [major minor]}]
  (if (or (nil? major) (> major 5) (and (= major 5) (>= (or minor 0) 1)))
    ["-fps_mode:v" "passthrough"]          ; FFmpeg 5.1+
    ["-vsync" "passthrough"]))

(defn- audio-args [audio has-audio?]
  (cond
    (or (= audio :none) (not has-audio?)) []
    (= audio :aac) ["-map" "0:a?" "-c:a" "aac" "-b:a" "192k"]
    :else          ["-map" "0:a?" "-c:a" "copy"]))

;; ---------------------------------------------------------------------------
;; The whole invocation

(defn compile-request
  "Engine plan for a render request (see watermark.engine). `env`:
    :ffmpeg the executable, :version / :encoders from describe-binary,
    :workdir a fresh scratch directory for the graph and text files."
  [{:keys [spec source media output encode strip-metadata?]} {:keys [ffmpeg version encoders workdir]}]
  (let [{:keys [canvas timebase layers]} spec
        first-frame (:first-frame timebase 0)
        fps-str     (str (:fps-num timebase) "/" (:fps-den timebase))
        fps         (/ (double (:fps-num timebase)) (:fps-den timebase))
        n           (frame-var "n" first-frame)
        images      (filter #(= :image (:kind %)) layers)
        texts       (filter #(= :text (:kind %)) layers)
        base        (if (:vfr? media) "base" "0:v")
        normalize   (when (:vfr? media) [(g/chain ["0:v"] [(g/f "fps" :fps fps-str)] ["base"])])
        image-chains (loop [[img & more] images, i 1, prev base, out []]
                       (if-not img
                         {:chains out :last prev}
                         (let [label (str "v" i)]
                           (recur more (inc i) label
                                  (into out (image-chains img i prev label first-frame))))))
        textfiles   (vec (for [i (range (count texts))] (ffmpeg-path (File. (str workdir) (str "text-" i ".txt")))))
        drawtexts   (vec (for [[i {:keys [style] :as layer}] (map-indexed vector texts)]
                           (let [[x y] (placement-exprs layer n)
                                 enable (timing-expr (:timing layer) n)]
                             (g/f "drawtext"
                                  :fontfile    (some-> (:font style) ffmpeg-path)
                                  :textfile    (textfiles i)
                                  :expansion   "none"
                                  :fontsize    (:size style)
                                  :fontcolor   (str (:color style) "@" (g/num-str (:opacity style)))
                                  :borderw     (:border style)
                                  :bordercolor (str (:border-color style) "@" (g/num-str (:border-opacity style)))
                                  :x           (g/expr x)
                                  :y           (g/expr y)
                                  :enable      (some-> enable g/expr)))))
        graph       (g/render (concat normalize
                                      (:chains image-chains)
                                      [(g/chain [(:last image-chains)]
                                                (if (seq drawtexts) drawtexts [(g/f "null")])
                                                ["vout"])]))
        graph-file  (str (File. (str workdir) "graph.txt"))
        encoder     (pick-encoder encode encoders)
        container   (:container output "mp4")]
    {:engine   :ffmpeg
     :workdir  (str workdir)
     :output   (:path output)
     :graph    graph
     :files    (into {graph-file graph} (map vector textfiles (map :text texts)))
     :total-us (some-> (:duration-s media) (* 1e6) long)
     :argv     (vec (concat [ffmpeg "-hide_banner" "-nostdin" "-y" "-loglevel" "error"
                             "-progress" "pipe:1" "-nostats"
                             "-i" (str source)]
                            (mapcat (fn [img] ["-i" (get-in img [:source :path])]) images)
                            (process/script-args version graph-file)
                            ["-map" "[vout]"]
                            (audio-args (:audio encode :copy) (:has-audio? media true))
                            (video-args encode encoder canvas fps)
                            ["-pix_fmt" "yuv420p"]
                            (frame-sync-args version)
                            (when strip-metadata? ["-map_metadata" "-1"])
                            (when (#{"mp4" "mov"} container) ["-movflags" "+faststart"])
                            [(str (:path output))]))}))

;; ---------------------------------------------------------------------------
;; Render spec v2: composite host-rendered bitmaps with overlay only (docs/adr/0006)

(defn- all-of
  "Product of enable expressions (nil = always)."
  [& exprs]
  (let [es (remove nil? exprs)]
    (when (seq es) (str/join "*" (map #(str "(" % ")") es)))))

(defn- flipbook-draws
  "Draws for a :flipbook layer: the rest pose outside flips, and one overlay
  per frame of the flip, each enabled on exactly its frames."
  [{:keys [timing rest cycle]} n]
  (let [base (timing-expr timing n)
        {:keys [start period frames]} cycle
        in-flip (when cycle (g/fmt "gte(%s,%d)*lt(mod(%s-%d,%d),%d)" n start n start period (count frames)))]
    (into [{:bitmap (:bitmap rest) :x (:x rest) :y (:y rest)
            :enable (all-of base (some->> in-flip (str "1-")))}]
          (map-indexed (fn [p {:keys [bitmap x y]}]
                         {:bitmap bitmap :x x :y y
                          :enable (all-of base (g/fmt "gte(%s,%d)*eq(mod(%s-%d,%d),%d)" n start n start period p))})
                       frames))))

(defn- bitmap-draw
  "Draw for a :bitmap layer: v1 placement with the bitmap as the text box,
  floored to whole pixels (watermark.render.v2/bitmap-origin). `n` is the
  frame for `enable`, `n-xy` the same frame as overlay's x and y count it."
  [{:keys [bitmap placement timing] :as layer} n n-xy]
  (let [[x y] (placement-exprs layer n-xy ["(W-w)" "(H-h)"])]
    {:bitmap bitmap
     :x (g/expr (str "floor(" x ")")) :y (g/expr (str "floor(" y ")"))
     :per-frame? (not= :fixed (:type placement))
     :enable (timing-expr timing n)}))

(defn compile-request-v2
  "Engine plan for a v2 render request: every draw is one `overlay` of a
  still RGBA bitmap (rawvideo input), in yuv444 so positions stay exact
  (yuv420 would round them to even pixels)."
  [{:keys [spec source media output encode strip-metadata?]} {:keys [ffmpeg version encoders workdir]}]
  (let [{:keys [canvas timebase layers bitmaps]} spec
        first-frame (:first-frame timebase 0)
        fps-str     (str (:fps-num timebase) "/" (:fps-den timebase))
        fps         (/ (double (:fps-num timebase)) (:fps-den timebase))
        n           (frame-var "n" first-frame)
        n-xy        (frame-var "(n-1)" first-frame)
        draws       (vec (mapcat (fn [layer]
                                   (case (:kind layer)
                                     :flipbook (flipbook-draws layer n)
                                     :bitmap   [(bitmap-draw layer n n-xy)]))
                                 layers))
        base        (if (:vfr? media) "base" "0:v")
        normalize   (when (:vfr? media) [(g/chain ["0:v"] [(g/f "fps" :fps fps-str)] ["base"])])
        overlays    (map-indexed
                     (fn [i {:keys [x y enable per-frame?]}]
                       (g/chain [(if (zero? i) base (str "d" i)) (str (inc i) ":v")]
                                [(g/f "overlay" :x x :y y :format "yuv444" :eof_action "repeat"
                                      :eval (when per-frame? "frame")
                                      :enable (some-> enable g/expr))]
                                [(if (= i (dec (count draws))) "vout" (str "d" (inc i)))]))
                     draws)
        graph       (g/render (concat normalize
                                      (if (seq draws)
                                        overlays
                                        [(g/chain [base] [(g/f "null")] ["vout"])])))
        graph-file  (str (File. (str workdir) "graph.txt"))
        encoder     (pick-encoder encode encoders)
        container   (:container output "mp4")]
    {:engine   :ffmpeg
     :workdir  (str workdir)
     :output   (:path output)
     :graph    graph
     :files    {graph-file graph}
     :total-us (some-> (:duration-s media) (* 1e6) long)
     :argv     (vec (concat [ffmpeg "-hide_banner" "-nostdin" "-y" "-loglevel" "error"
                             "-progress" "pipe:1" "-nostats"
                             "-i" (str source)]
                            (mapcat (fn [{:keys [bitmap]}]
                                      (let [{:keys [width height path]} (bitmaps bitmap)]
                                        ["-f" "rawvideo" "-pix_fmt" "rgba" "-video_size" (str width "x" height)
                                         "-framerate" "1" "-i" (str path)]))
                                    draws)
                            (process/script-args version graph-file)
                            ["-map" "[vout]"]
                            (audio-args (:audio encode :copy) (:has-audio? media true))
                            (video-args encode encoder canvas fps)
                            ["-pix_fmt" "yuv420p"]
                            (frame-sync-args version)
                            (when strip-metadata? ["-map_metadata" "-1"])
                            (when (#{"mp4" "mov"} container) ["-movflags" "+faststart"])
                            [(str (:path output))]))}))
