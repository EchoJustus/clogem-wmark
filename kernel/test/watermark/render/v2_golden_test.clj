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
            [watermark.core.features :as features]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.golden :as golden]
            [watermark.raster :as raster]
            [watermark.raster.image :as image]
            [watermark.raster.truetype :as tt]
            [watermark.render :as render]
            [watermark.render.schema :as spec-schema]
            [watermark.render.v2 :as v2])
  (:import (java.nio.file Files)))

(set! *warn-on-reflection* true)

(def spec
  ;; the render-basic golden spec (watermark.render-test/golden-render-spec)
  (render/build {:settings     (resolve/deep-merge
                                schema/defaults
                                {:logo  {:path "/logos/l.png" :anchor :top-right :offset {:x 30 :y 20}
                                         :animation {:type :flip-y :every-s 5.0 :duration-s 0.8}}
                                 :texts [{:mode :continuous :content "(c) Studio" :anchor :bottom-center}
                                         {:mode :scheduled :content "Scheduled" :at [2.0 90.5] :duration-s 1.5}]})
                 :media        {:width 1280 :height 720 :fps-num 30000 :fps-den 1001 :frames 5400}
                 :logo-media   {:width 400 :height 160}
                 :seed-fn      (constantly 42)
                 :entitlements (features/community)
                 :font         "/fonts/a.ttf"}))

(defn- synthetic-logo []
  (let [w 400 h 160 px (image/u8-array (* 4 w h))]
    (dotimes [y h]
      (dotimes [x w]
        (let [o (* 4 (+ x (* y w)))]
          (image/u8! px o       (mod (* 5 x) 256))
          (image/u8! px (+ o 1) (mod (* 11 y) 256))
          (image/u8! px (+ o 2) (mod (+ x y) 256))
          (image/u8! px (+ o 3) (+ 128 (mod (+ x (* 2 y)) 128))))))
    {:width w :height h :px px}))

(def font (delay (tt/parse (Files/readAllBytes (.toPath (io/file "resources/fonts/wmark.ttf"))))))

(defn- r3 [v] (/ (Math/round (* 1000.0 (double v))) 1000.0))

(defn- geometry
  "A raster request without its source and style: what the kernel decides."
  [{:keys [kind] :as req}]
  (case kind
    :image (-> (select-keys req [:key :card :size :box :opacity])
               (assoc :quad (mapv #(mapv r3 %) (:quad req))))
    :text  (select-keys req [:key :text])))

(deftest golden-render-spec-v2
  (let [logo    (synthetic-logo)
        reqs    (v2/raster-requests spec)
        results (into {} (for [req reqs
                               :let [img (raster/draw req {:decoded (constantly logo) :font (constantly @font)})
                                     id  (raster/bitmap-id img)]]
                           [(:key req) {:bitmap id :width (:width img) :height (:height img)
                                        :path (str id ".rgba")}]))
        s2      (spec-schema/validate! (v2/assemble spec results))
        shared  (raster/draw-all reqs {:decoded (constantly logo) :font (constantly @font)})]
    (is (= (map (comp :bitmap results :key) reqs) (map raster/bitmap-id shared))
        "draw-all, which shares a flip's scaled card, draws exactly what draw does")
    (is (= (:spec (edn/read-string (slurp "kernel/test/golden/render-basic.edn"))) spec)
        "the same v1 spec as render-basic")
    (golden/check "render-v2"
                  {:requests (mapv geometry reqs)
                   :spec     s2
                   ;; what each layer draws on sample frames: the rest pose,
                   ;; frames inside the first flip, a scheduled text window
                   :draw-at  (into (sorted-map)
                                   (for [n [0 59 60 103 104 149 150 151 160 173 174 2713 2756 2757]]
                                     [n (into (sorted-map)
                                              (for [layer (:layers s2)
                                                    :let [d (v2/draw-at s2 layer n)]
                                                    :when d]
                                                [(:id layer) d]))]))})))
