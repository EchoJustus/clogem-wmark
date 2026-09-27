;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render.layout
  "Settings -> render-spec building blocks: geometry, placement, timing.

  Pure and host-independent. Every quantity a render engine needs is resolved
  here into pixels and frame indices, so engines never interpret user-facing
  settings (anchors, seconds, ratios) themselves -- two engines given the same
  spec have nothing left to disagree about except pixel rendering."
  (:require [watermark.util.num :as num]))

(def anchor-fractions
  "Anchor -> [fx fy]: where a box sits in the free space around it
  (0 = left/top edge, 1 = right/bottom edge)."
  {:top-left    [0.0 0.0] :top-center    [0.5 0.0] :top-right    [1.0 0.0]
   :center-left [0.0 0.5] :center        [0.5 0.5] :center-right [1.0 0.5]
   :bottom-left [0.0 1.0] :bottom-center [0.5 1.0] :bottom-right [1.0 1.0]})

(defn fixed-placement
  "Placement of a box of unknown size (text) at an anchor. Offsets are
  measured inward from the anchored edges: the rendered origin is
  x = fx * (W - w) + px, y = fy * (H - h) + py."
  [anchor {:keys [x y] :or {x 0 y 0}}]
  (let [[fx fy] (anchor-fractions anchor [0.0 1.0])]
    {:type :fixed
     :fx fx :fy fy
     :px (if (== fx 1.0) (- x) x)
     :py (if (== fy 1.0) (- y) y)}))

(defn box-origin
  "Top-left pixel of a w*h box placed by `placement` in a W*H canvas."
  [{:keys [fx fy px py]} W H w h]
  [(+ (num/floor-int (* fx (- W w))) px)
   (+ (num/floor-int (* fy (- H h))) py)])

;; ---------------------------------------------------------------------------
;; Time

(defn fps
  "Frame rate of a timebase as a double."
  [{:keys [fps-num fps-den]}]
  (/ (* 1.0 fps-num) fps-den))

(defn frames-of
  "Whole frames in `seconds`, at least 1."
  [seconds fps]
  (max 1 (num/round-half-up (* seconds fps))))

(defn seconds->window
  "Frame window covering [at, at + duration) seconds: every frame whose start
  time falls inside. Inclusive frame indices; nil when empty. The tiny
  epsilon absorbs floating error at exact boundaries (1.0 s at 30 fps must
  start at frame 30, not 31)."
  [fps at duration]
  (let [start (num/ceil-int (- (* at fps) 1e-9))
        end   (dec (num/ceil-int (- (* (+ at duration) fps) 1e-9)))]
    (when (<= start end) {:start start :end end})))

(defn normalize-windows
  "Sort, clip to [0, frames), and merge overlapping or touching windows."
  [windows frames]
  (->> windows
       (keep (fn [{:keys [start end]}]
               (let [s (max 0 start) e (min (dec frames) end)]
                 (when (<= s e) {:start s :end e}))))
       (sort-by :start)
       (reduce (fn [acc {:keys [start end] :as w}]
                 (let [prev (peek acc)]
                   (if (and prev (<= start (inc (:end prev))))
                     (conj (pop acc) (assoc prev :end (max end (:end prev))))
                     (conj acc w))))
               [])))

;; ---------------------------------------------------------------------------
;; Logo

(def flip-focal
  "Camera distance for the flip, in logo widths. Smaller = stronger
  perspective; must stay well above 0.5 or the near edge would pass the camera."
  2.5)

(defn flip-animation
  "Frame-domain parameters of the periodic Y-axis flip. The first flip happens
  one period in (at t = every-s), unless phase-s says otherwise."
  [fps logo-width {:keys [every-s duration-s phase-s] :or {every-s 60.0 duration-s 1.0}}]
  (let [period (max 2 (num/round-half-up (* every-s fps)))]
    {:type     :flip-y
     :easing   :cosine
     :start    (num/round-half-up (* (or phase-s every-s) fps))
     :period   period
     :duration (min (dec period) (max 2 (num/round-half-up (* duration-s fps))))
     :distance (* flip-focal logo-width)
     :min-cos  0.02}))

(defn logo-layer
  "Image layer for the logo: scaled to `width-ratio` of the canvas width,
  aspect preserved, sizes even, anchored with inward offsets."
  [{:keys [canvas timebase]} {:keys [path anchor offset width-ratio opacity animation]
                              :or {anchor :bottom-right width-ratio 0.12 opacity 1.0}}
   {img-w :width img-h :height}]
  (let [{W :width H :height} canvas
        lw     (max 2 (num/even (* W width-ratio)))
        lh     (max 2 (num/even (/ (* lw img-h) (* 1.0 img-w))))
        [x y]  (box-origin (fixed-placement anchor offset) W H lw lh)]
    (cond-> {:id      "logo"
             :kind    :image
             :source  {:path path :width img-w :height img-h}
             :box     {:x x :y y :width lw :height lh}
             :opacity opacity
             :timing  {:type :always}}
      (= :flip-y (:type animation))
      (assoc :animation (flip-animation (fps timebase) lw animation)))))

;; ---------------------------------------------------------------------------
;; Text

(defn text-style
  "Resolved text appearance: pixel size from the canvas height, font path from
  the layer or the host's default font."
  [{:keys [canvas font]} {:keys [font-path size-ratio color opacity border]
                          :or {size-ratio 0.035 color "white" opacity 0.8 border 2}}]
  {:font           (or font-path font)
   :size           (max 8 (num/round-half-up (* (:height canvas) size-ratio)))
   :color          color
   :opacity        opacity
   :border         border
   :border-color   "black"
   :border-opacity (* 0.6 opacity)})

(defn text-layer
  "Common shell of a text layer; modes fill in :timing and :placement."
  [{:keys [index] :as ctx} {:keys [id content mode anchor offset] :as layer}]
  {:id        (or id (str "text-" index))
   :kind      :text
   :mode      mode
   :text      content
   :style     (text-style ctx layer)
   :placement (fixed-placement (or anchor :bottom-left) (or offset {:x 24 :y 24}))
   :timing    {:type :always}})
