;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render
  "The render spec: an engine-neutral description of one watermarked video,
  plus its reference semantics.

  Planning happens here, once, on any host; rendering happens in an engine
  (FFmpeg on desktop and servers, AVFoundation/Metal on Apple devices, Media3
  on Android, perhaps a custom GPU core later). Engines receive a spec in which
  everything is already resolved to pixels and frame indices, and the
  functions at the bottom of this namespace define what every engine must
  produce at frame n. The FFmpeg engine compiles the spec to filter
  expressions; the conformance test renders with a real engine and measures
  the frames against these functions.

  Shape (see watermark.render.schema for the full schema):

    {:spec/version 1
     :canvas   {:width 1920 :height 1080}       ; display size (rotation applied)
     :timebase {:fps-num 30000 :fps-den 1001    ; constant frame rate of the output
                :frames 14385                   ; frames in the whole programme
                :first-frame 0}                 ; global index of the first frame
                                                ; this render produces (segments)
     :layers   [image-layer text-layer ...]}    ; bottom to top

  Frame n is the global 0-based frame index. Timings are inclusive frame
  windows; positions are pixels with the origin at the top-left."
  (:require [watermark.core.features :as features]
            [watermark.core.modes :as modes]
            [watermark.render.layout :as layout]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

(def version 1)

(defn build
  "Render spec for one input.

    :settings      resolved, validated settings
    :media         engine probe of the input:
                   {:width :height :fps-num :fps-den :frames ...}
    :logo-media    engine probe of the logo image {:width :height}
    :seed-fn       (fn [layer-index layer] seed)
    :entitlements  watermark.core.features/Entitlements
    :font          default font file for text layers
    :first-frame   global index of the first rendered frame (default 0)"
  [{:keys [settings media logo-media seed-fn entitlements font first-frame]}]
  (let [{:keys [logo texts]} settings
        canvas   {:width (:width media) :height (:height media)}
        timebase {:fps-num     (:fps-num media)
                  :fps-den     (:fps-den media)
                  :frames      (:frames media)
                  :first-frame (or first-frame 0)}
        ctx      {:canvas       canvas
                  :timebase     timebase
                  :fps          (layout/fps timebase)
                  :font         font
                  :entitlements entitlements}
        logo?    (and (:enabled logo true) (:path logo))]
    {:spec/version version
     :canvas       canvas
     :timebase     timebase
     :layers       (vec (concat
                         (when logo? [(layout/logo-layer ctx logo logo-media)])
                         (map-indexed (fn [i layer]
                                        ;; the seed hashes the wire id, so an alias
                                        ;; must resolve before it is computed
                                        (let [layer (update layer :mode features/canonical-mode)]
                                          (modes/layer-spec (assoc ctx :index i :seed (seed-fn i layer))
                                                            layer)))
                                      texts)))}))

;; ---------------------------------------------------------------------------
;; Reference semantics

(defn window-index
  "Index of the window containing frame n, or nil."
  [windows n]
  (first (keep-indexed (fn [i {:keys [start end]}] (when (<= start n end) i)) windows)))

(defn active?
  "Is a layer with this timing visible at frame n?"
  [{:keys [type windows offset period length]} n]
  (case type
    :always   true
    :windows  (some? (window-index windows n))
    :periodic (and (>= n offset) (< (mod (- n offset) period) length))))

(defn- scatter
  "Keyed fraction in [margin, 1 - margin] for burst number `burst`."
  [{:keys [a b]} burst margin modulus]
  (+ margin (* (- 1.0 (* 2.0 margin))
               (/ (* 1.0 (mod (+ (* burst a) b) modulus)) modulus))))

(defn placement-fractions
  "[fx fy] of a text layer at frame n (the layer must be active at n)."
  [{:keys [placement timing]} n]
  (case (:type placement)
    :fixed         [(:fx placement) (:fy placement)]
    :burst-scatter (let [{:keys [margin modulus x y]} placement
                         burst (quot (- n (:offset timing)) (:period timing))]
                     [(scatter x burst margin modulus) (scatter y burst margin modulus)])
    :per-window    (get (:points placement) (window-index (:windows timing) n))))

(defn text-origin
  "Top-left pixel of the text box at frame n, given the canvas size [W H] and
  the rendered text box size [tw th] (which depends on the engine's font
  rasterizer)."
  [layer n [W H] [tw th]]
  (let [[fx fy] (placement-fractions layer n)
        {:keys [px py] :or {px 0 py 0}} (:placement layer)]
    [(+ (* fx (- W tw)) px) (+ (* fy (- H th)) py)]))

(defn flip-angle
  "Rotation of a :flip-y animation at frame n, in radians: 0 outside a flip,
  an eased sweep 0 -> 2pi lasting `duration` frames, starting at frame
  `start` and repeating every `period` frames."
  [{:keys [start period duration]} n]
  (if (and (>= n start) (< (mod (- n start) period) duration))
    (let [p (mod (- n start) period)]
      (* number/pi (- 1.0 (number/cos (/ (* number/pi p) duration)))))
    0.0))

(defn logo-corners
  "Screen positions of the image layer's corners at frame n:
  [top-left top-right bottom-left bottom-right], each [x y].

  The flip is a card rotating about its vertical centre line, seen through a
  pinhole camera `distance` pixels in front of it. A point X pixels right of
  the axis and Y below the centre projects to

      x = cx + X cos(theta) w,   y = cy + Y w,   w = D / (D - X sin(theta))

  with cos(theta) kept at least `min-cos` in magnitude (an edge-on card is a
  degenerate quad). When cos < 0 the edges swap sides, so the back of the card
  shows mirrored."
  [{:keys [box animation]} n]
  (let [{:keys [x y width height]} box
        hw (/ width 2.0) hh (/ height 2.0)
        cx (+ x hw)      cy (+ y hh)]
    (if-not animation
      [[x y] [(+ x width) y] [x (+ y height)] [(+ x width) (+ y height)]]
      (let [theta (flip-angle animation n)
            c0    (number/cos theta)
            m     (:min-cos animation 0.0)
            c     (if (< (if (neg? c0) (- c0) c0) m) (if (neg? c0) (- m) m) c0)
            s     (number/sin theta)
            d     (:distance animation)
            corner (fn [X Y]
                     (let [w (/ d (- d (* X s)))]
                       [(+ cx (* X c w)) (+ cy (* Y w))]))]
        [(corner (- hw) (- hh)) (corner hw (- hh)) (corner (- hw) hh) (corner hw hh)]))))

(defn logo-bounds
  "Axis-aligned bounds [x0 y0 x1 y1] of the image layer at frame n."
  [layer n]
  (let [pts (logo-corners layer n)
        xs  (map first pts) ys (map second pts)]
    [(apply min xs) (apply min ys) (apply max xs) (apply max ys)]))

(defn layers-of [spec kind] (filter #(= kind (:kind %)) (:layers spec)))
