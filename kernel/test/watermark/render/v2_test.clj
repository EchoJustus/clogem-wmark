;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render.v2-test
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.render :as render]
            [watermark.render.v2 :as v2]))

(set! *warn-on-reflection* true)

(def spec
  (render/build {:settings     (resolve/deep-merge schema/defaults
                                                   {:logo  {:path "/l.png" :anchor :center-left :width-ratio 0.25
                                                            :animation {:type :flip-y :every-s 1.0 :duration-s 0.5 :phase-s 0.5}}
                                                    :texts [{:mode :continuous :content "WM" :anchor :top-right}]})
                 :media        {:width 640 :height 360 :fps-num 30 :fps-den 1 :frames 120}
                 :logo-media   {:width 400 :height 160}
                 :seed-fn      (constantly 42)
                 :entitlements (features/community)
                 :font         "/f.ttf"}))

(def logo (first (render/layers-of spec :image)))

(deftest the-host-draws-one-card-per-frame-of-a-flip
  (let [reqs  (v2/raster-requests spec)
        {:keys [start duration]} (:animation logo)
        flips (filter #(number? (second (:key %))) reqs)]
    (is (= (+ 1 duration 1) (count reqs)) "the rest pose, every flip frame, the text")
    (is (= (range duration) (map (comp second :key) flips)))
    (doseq [{[_ p] :key :keys [quad box size card]} flips
            :let [corners (render/logo-corners logo (+ start p))]]
      (is (= [(:width (:box logo)) (:height (:box logo))] card) "the card keeps its unrotated size")
      (is (= size [(:width box) (:height box)]))
      (testing "the bitmap covers the reference quad, which it holds in its own pixel frame"
        (is (every? (fn [[x y]] (and (<= 0 x (:width box)) (<= 0 y (:height box)))) quad))
        (is (= corners (mapv (fn [[x y]] [(+ x (:x box)) (+ y (:y box))]) quad)))))))

(defn- assembled []
  ;; text bitmaps get their size from the host's rasterizer: pretend 120 x 40
  (v2/assemble spec (into {} (for [{:keys [key size kind]} (v2/raster-requests spec)
                                   :let [[w h] (if (= :text kind) [120 40] size)]]
                               [key {:bitmap (pr-str key) :width w :height h
                                     :path (str "/b/" (pr-str key))}]))))

(deftest reference-semantics-pick-the-right-bitmap
  (let [s2 (assembled)
        [book text] (:layers s2)
        {:keys [start period duration]} (:animation logo)]
    (is (= [:flipbook :bitmap] (map :kind (:layers s2))))
    (doseq [n (range 120)
            :let [{:keys [bitmap x y]} (v2/draw-at s2 book n)
                  p (when (>= n start) (mod (- n start) period))]]
      (if (and p (< p duration))
        (is (= (pr-str [(:id logo) p]) bitmap) (str "frame " n " shows flip frame " p))
        (is (= [(pr-str [(:id logo) :rest]) (get-in logo [:box :x]) (get-in logo [:box :y])] [bitmap x y])
            (str "frame " n " shows the rest pose"))))
    (testing "a bitmap sits where v1 would put a text box of its size, floored"
      (let [{:keys [width height]} ((:bitmaps s2) (:bitmap text))
            [rx ry] (render/text-origin text 0 [640 360] [width height])]
        (is (= {:bitmap (:bitmap text) :x (long (Math/floor rx)) :y (long (Math/floor ry))}
               (v2/draw-at s2 text 0)))))))

(deftest quad-boxes-are-whole-pixels-around-the-quad
  (is (= {:x 10 :y -4 :width 81 :height 85} (v2/quad-box [[10.2 5.0] [90.5 -3.9] [12.0 70.0] [85.0 80.1]])))
  (is (= {:x 3 :y 3 :width 1 :height 1} (v2/quad-box [[3 3] [3 3] [3 3] [3 3]])) "never empty"))
