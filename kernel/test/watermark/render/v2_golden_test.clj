;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render.v2-golden-test
  "Golden vectors for render spec v2 (kernel/test/golden/render-v2.edn).

  A port of the kernel (ClojureDart, Swift, Rust) reproduces the whole file:
  the raster requests of the render-basic spec (geometry), the bitmaps the
  kernel draws for them (named by watermark.raster/bitmap-id, so a single
  differing pixel changes a name), the assembled v2 spec, and what the
  reference semantics draw on sample frames.

  Inputs, reproducible without this repository's code:
  - the logo is synthetic, 400 x 160 straight RGBA8, pixel (x, y) =
    [(5x) mod 256, (11y) mod 256, (x + y) mod 256, 128 + (x + 2y) mod 128];
  - text uses the bundled font, resources/fonts/wmark.ttf (Fira Sans Bold,
    SIL OFL 1.1), whatever the layer's font path says."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.raster.truetype :as tt])
  (:import (java.nio.file Files)))

(set! *warn-on-reflection* true)

(def font (delay (tt/parse (Files/readAllBytes (.toPath (io/file "resources/fonts/wmark.ttf"))))))

(deftest golden-render-spec-v2
  (let [{::inputs/keys [draw-all] :as v} (inputs/render-v2 @font)]
    (is (= (:draw draw-all) (:draw-all draw-all))
        "draw-all, which shares a flip's scaled card, draws exactly what draw does")
    (is (= (:spec (edn/read-string (slurp "kernel/test/golden/render-basic.edn"))) (inputs/basic-spec))
        "the same v1 spec as render-basic")
    (golden/check "render-v2" (dissoc v ::inputs/draw-all))))
