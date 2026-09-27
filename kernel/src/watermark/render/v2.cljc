;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render.v2
  "Render spec v2 (M2 prototype): the host renders every pixel, engines only
  composite bitmaps.

  A v1 spec asks engines to warp the logo (the flip) and to typeset text.
  v2 moves both to the host, so an engine needs nothing but \"draw bitmap B
  with its top-left at integer (x, y) on frame n\". FFmpeg's `overlay` does
  that, so an LGPL build without the GPL-only `perspective` filter becomes
  fully capable.

  The kernel stays graphics-free. It decides *what* the host draws and
  *where*: one bitmap per frame of a flip (the card's quad from the
  reference `watermark.render/logo-corners`, in the bitmap's own pixel
  frame), the static pose, and each text layer. The host
  (watermark.raster) fills in the pixels, and `assemble` builds the v2 spec
  from its answers.

    {:spec/version 2
     :canvas   {...} :timebase {...}                     ; as in v1
     :bitmaps  {\"<sha256>\" {:width :height :path}}     ; straight RGBA8, row-major
     :layers   [{:id \"logo\" :kind :flipbook :timing {:type :always}
                 :rest  {:bitmap id :x :y}                ; outside a flip
                 :cycle {:start :period                   ; frame p of each flip:
                         :frames [{:bitmap id :x :y} ...]}} ; mod(n-start, period) = p
                {:id \"text-0\" :kind :bitmap :bitmap id
                 :placement <v1 placement> :timing <v1 timing>}]}

  Opacity is baked into the bitmaps' alpha. Positions are integers; the host
  bakes sub-pixel offsets into the bitmap. Timings and placements are v1's,
  so their reference semantics carry over unchanged."
  (:require [watermark.render :as render]
            [watermark.util.num :as num]))

#?(:clj (set! *warn-on-reflection* true))

(def version 2)

;; ---------------------------------------------------------------------------
;; What the host must draw

(defn quad-box
  "Integer pixel box covering a quad: {:x :y :width :height}, top-left
  inclusive. Every corner lies inside [x, x + width) x [y, y + height)."
  [quad]
  (let [xs (map first quad) ys (map second quad)
        x0 (num/floor-int (apply min xs)) y0 (num/floor-int (apply min ys))
        x1 (num/ceil-int (apply max xs))  y1 (num/ceil-int (apply max ys))]
    {:x x0 :y y0 :width (max 1 (- x1 x0)) :height (max 1 (- y1 y0))}))

(defn- local-quad
  "The quad relative to a box's top-left: the bitmap's own pixel frame."
  [quad {:keys [x y]}]
  (mapv (fn [[qx qy]] [(- qx x) (- qy y)]) quad))

(defn image-requests
  "Raster requests for one v1 image layer: the static pose, and for a flip
  one warped card per frame of the flip. Each request says which source to
  draw, at what card size (the box, before any flip), into what bitmap size,
  and where the card's corners [top-left top-right bottom-left
  bottom-right] land in that bitmap."
  [{:keys [id source box opacity animation] :as layer}]
  (let [rest {:key     [id :rest]
              :kind    :image
              :source  source
              :card    [(:width box) (:height box)]
              :size    [(:width box) (:height box)]
              :opacity opacity
              :box     box
              :quad    [[0 0] [(:width box) 0] [0 (:height box)] [(:width box) (:height box)]]}]
    (into [rest]
          (when animation
            (for [p (range (:duration animation))
                  :let [quad (render/logo-corners layer (+ (:start animation) p))
                        b    (quad-box quad)]]
              {:key     [id p]
               :kind    :image
               :source  source
               :card    [(:width box) (:height box)]
               :size    [(:width b) (:height b)]
               :opacity opacity
               :box     b
               :quad    (local-quad quad b)})))))

(defn text-request
  "Raster request for one v1 text layer: the host measures and draws it."
  [{:keys [id text style]}]
  {:key [id :text] :kind :text :text text :style style})

(defn raster-requests
  "Everything the host must rasterize for a v1 spec, in layer order."
  [spec]
  (vec (mapcat (fn [layer]
                 (case (:kind layer)
                   :image (image-requests layer)
                   :text  [(text-request layer)]))
               (:layers spec))))

;; ---------------------------------------------------------------------------
;; The v2 spec from the host's bitmaps

(defn assemble
  "The v2 spec for a v1 `spec`, given the host's answer to each raster
  request: `results` maps request :key -> {:bitmap id :width :height :path}."
  [spec results]
  (let [boxes (into {} (map (juxt :key :box)) (raster-requests spec))
        at    (fn [k] (let [{:keys [x y]} (boxes k)]
                        {:bitmap (:bitmap (results k)) :x x :y y}))]
    {:spec/version version
     :canvas       (:canvas spec)
     :timebase     (:timebase spec)
     :bitmaps      (into (sorted-map) (for [{:keys [bitmap width height path]} (vals results)]
                                        [bitmap {:width width :height height :path path}]))
     :layers       (vec (for [{:keys [id kind animation] :as layer} (:layers spec)]
                          (case kind
                            :image (cond-> {:id id :kind :flipbook :timing (:timing layer) :rest (at [id :rest])}
                                     animation
                                     (assoc :cycle {:start  (:start animation)
                                                    :period (:period animation)
                                                    :frames (mapv #(at [id %]) (range (:duration animation)))}))
                            :text  {:id        id
                                    :kind      :bitmap
                                    :bitmap    (:bitmap (results [id :text]))
                                    :placement (:placement layer)
                                    :timing    (:timing layer)})))}))

;; ---------------------------------------------------------------------------
;; Reference semantics

(defn cycle-index
  "Which cycle frame shows at global frame n, or nil (the rest pose)."
  [{:keys [start period frames]} n]
  (when (>= n start)
    (let [p (mod (- n start) period)]
      (when (< p (count frames)) p))))

(defn bitmap-origin
  "Top-left pixel of a :bitmap layer at frame n: v1's text-origin with the
  bitmap as the text box, floored to whole pixels."
  [layer [W H] [w h] n]
  (let [[x y] (render/text-origin layer n [W H] [w h])]
    [(num/floor-int x) (num/floor-int y)]))

(defn draw-at
  "What a v2 layer draws at global frame n: {:bitmap id :x :y}, or nil."
  [{:keys [canvas bitmaps]} layer n]
  (when (render/active? (:timing layer) n)
    (case (:kind layer)
      :flipbook (if-let [p (some-> (:cycle layer) (cycle-index n))]
                  (get-in layer [:cycle :frames p])
                  (:rest layer))
      :bitmap   (let [{:keys [width height]} (bitmaps (:bitmap layer))
                      [x y] (bitmap-origin layer [(:width canvas) (:height canvas)] [width height] n)]
                  {:bitmap (:bitmap layer) :x x :y y}))))
