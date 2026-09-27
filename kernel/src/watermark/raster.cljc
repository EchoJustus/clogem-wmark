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
            [watermark.raster.text :as text])
  #?(:clj (:import (java.nio.charset StandardCharsets)
                   (java.security MessageDigest)
                   (java.util HexFormat))))

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
  #?(:clj  (let [md (MessageDigest/getInstance "SHA-256")]
             (.update md (.getBytes (str width "x" height ":") StandardCharsets/UTF_8))
             (.update md ^bytes px)
             (.formatHex (HexFormat/of) (.digest md)))
     ;; Dart: package:crypto's sha256.convert(utf8.encode(prefix) + px).toString()
     :default (throw (ex-info "bitmap-id is not implemented on this host yet."
                              {:wmark/error :unavailable}))))

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
