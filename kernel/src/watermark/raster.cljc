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
  (watermark.raster.local) writes bitmaps to scratch, named by the SHA-256
  of their pixels, and deletes them after the render."
  (:require [watermark.raster.image :as image]
            [watermark.raster.text :as text]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol Rasterizer
  (realize! [r engine spec]
    "The v2 spec for the v1 `spec`: every bitmap drawn and stored where
    `engine` can read it (stills decoded by `engine`).")
  (release! [r spec2]
    "Delete what realize! stored for `spec2`."))

(defn draw
  "One raster request as a straight RGBA8 image {:width :height :px}.
  `decoded`: (fn [path] image) for stills; `font`: (fn [path] parsed font)."
  [{:keys [kind source style text] :as request} {:keys [decoded font]}]
  (case kind
    :image (image/card request (decoded (:path source)))
    :text  (text/render (font (:font style)) style text)))
