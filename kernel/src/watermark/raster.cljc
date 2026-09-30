;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster
  "Render spec v2's drawing, and the port hosts implement around it.

  The kernel draws: `draw` turns one raster request
  (watermark.render.v2/raster-requests) into a straight RGBA8 image with
  portable arithmetic (watermark.raster.image, .text, .truetype), so every
  host produces the same pixels. A host supplies only what needs I/O: the
  decoded source image, which the engine decodes
  (watermark.engine/StillDecoder), and the parsed font file.

  `Rasterizer` is the port the job pipeline calls. The local adapter
  (watermark.raster.local) writes bitmaps to scratch, named by `bitmap-id`,
  and deletes them after the render."
  (:require [watermark.raster.image :as image]
            [watermark.raster.text :as text]
            [watermark.raster.truetype :as tt]
            [watermark.render.schema :as spec-schema]
            [watermark.render.v2 :as v2]
            [watermark.util.digest :as digest]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol Rasterizer
  (realize! [r engine spec]
    "The v2 spec for the v1 `spec`: every bitmap drawn and stored where
    `engine` can read it (stills decoded by `engine`).")
  (release! [r spec2]
    "Delete what realize! stored for `spec2`."))

(defn bitmap-id
  "A bitmap's name in a v2 spec: lowercase hex SHA-256 of the UTF-8 bytes
  \"<width>x<height>:\" followed by its straight RGBA8 pixels, so equal
  bitmaps get equal names on every host."
  [{:keys [width height px]}]
  (digest/sha256-hex [(str width "x" height ":") px]))

(defn draw
  "One raster request as a straight RGBA8 image {:width :height :px}.
  `decoded`: (fn [path] image) for stills; `font`: (fn [path] parsed font);
  optionally `scaled`: (fn [path card-size] scaled card), which `draw-all`
  shares between requests."
  [{:keys [kind source style text card] :as request} {:keys [decoded font scaled]}]
  (case kind
    :image (image/card request (if scaled
                                 (scaled (:path source) card)
                                 (image/scale-card (decoded (:path source)) card)))
    :text  (text/render (font (:font style)) style text)))

(defn draw-all
  "Every raster request drawn, in order. The frames of a flip share one
  scaled card (area scaling is most of the work), so this draws exactly
  what `draw` draws, faster."
  [requests {:keys [decoded] :as sources}]
  (let [scaled (memoize (fn [path size] (image/scale-card (decoded path) size)))]
    (mapv #(draw % (assoc sources :scaled scaled)) requests)))

(defn realize
  "The v2 spec for the v1 `spec`: every raster request drawn, each bitmap
  stored once under its `bitmap-id`, and the result checked against the
  published v2 schema before any engine sees it. The host supplies the I/O:
    :decode      (fn [path] image), normally the engine's decode-still
    :font-bytes  (fn [path] bytes) of a TrueType file
    :store!      (fn [id image] path) where the bitmap's raw RGBA8 lives,
                 written unless it is already there
  Every host's rasterizer (watermark.raster.local on the JVM, the Dart
  host's) is this function plus those three."
  [spec {:keys [decode font-bytes store!]}]
  (let [requests (v2/raster-requests spec)
        font     (memoize (fn [path]
                            (when-not path
                              (throw (ex-info "Text layers need a font file (the layer's font-path, or the bundled default)."
                                              {:wmark/error :invalid})))
                            (tt/parse (font-bytes path))))
        images   (draw-all requests {:decoded (memoize decode) :font font})
        results  (reduce (fn [acc [req img]]
                           (let [id (bitmap-id img)]
                             (assoc acc (:key req) {:bitmap id :width (:width img) :height (:height img)
                                                    :path (store! id img)})))
                         {} (map vector requests images))]
    (spec-schema/validate! (v2/assemble spec results))))
