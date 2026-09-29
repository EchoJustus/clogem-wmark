;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.schema :as schema]
            [watermark.core.resolve :as resolve]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.render :as render]
            [watermark.render.layout :as layout]
            [watermark.render.schema :as spec-schema]))

(set! *warn-on-reflection* true)

(def media-30 {:width 1920 :height 1080 :fps-num 30 :fps-den 1 :frames 3600})

(defn- spec-for [settings & {:as more}]
  (render/build (merge {:settings     (resolve/deep-merge schema/defaults settings)
                        :media        media-30
                        :logo-media   {:width 400 :height 160}
                        :seed-fn      (constantly 42)
                        :entitlements (features/community)
                        :font         "/fonts/a.ttf"}
                       more)))

(defn- layer [spec id] (first (filter #(= id (:id %)) (:layers spec))))

(deftest logo-geometry
  (doseq [[w h] [[1920 1080] [1080 1920] [3840 2160] [640 360]]]
    (let [{:keys [box]} (layer (spec-for {:logo {:path "/l.png"}}
                                         :media (assoc media-30 :width w :height h))
                               "logo")]
      (is (every? even? ((juxt :width :height) box)) "yuv420 needs even sizes")
      (is (= (- w (:width box) 24) (:x box)) "bottom-right, 24 px inward")
      (is (= (- h (:height box) 24) (:y box)))))
  (let [{:keys [box]} (layer (spec-for {:logo {:path "/l.png" :anchor :center :offset {:x -5 :y 10}}}) "logo")]
    (is (= [(+ (quot (- 1920 230) 2) -5) (+ (quot (- 1080 92) 2) 10)] [(:x box) (:y box)]))))

(deftest flip-parameters
  (is (= {:start 1800 :period 1800 :duration 30}
         (select-keys (layout/flip-animation 30.0 200 {:every-s 60.0 :duration-s 1.0}) [:start :period :duration]))
      "first flip one period in, not on frame 0")
  (is (zero? (:start (layout/flip-animation 30.0 200 {:every-s 60.0 :phase-s 0.0}))))
  (is (< (:duration (layout/flip-animation 30.0 200 {:every-s 2.0 :duration-s 5.0})) 60)
      "a flip can never fill its whole period")
  (is (= 500.0 (:distance (layout/flip-animation 30.0 200 {})))))

(deftest scheduled-windows-are-half-open-frame-ranges
  (let [fps-ntsc (/ 30000.0 1001)]
    (is (= {:start 30 :end 89} (layout/seconds->window 30.0 1.0 2.0)) "2 s at 30 fps = 60 frames")
    (is (= {:start 30 :end 89} (layout/seconds->window fps-ntsc 1.0 2.0)) "NTSC: t(30)=1.001 s, t(90)=3.003 s")
    (is (= {:start 30 :end 30} (layout/seconds->window 30.0 1.0 0.01)) "frame 30 starts exactly at 1.0 s")
    (is (nil? (layout/seconds->window 30.0 1.01 0.01)) "no frame starts inside [1.01, 1.02)"))
  (is (= [{:start 0 :end 20} {:start 40 :end 99}]
         (layout/normalize-windows [{:start 60 :end 120} {:start -5 :end 10} {:start 40 :end 70} {:start 11 :end 20}] 100))
      "sorted, clipped, merged when overlapping or touching")
  (let [t (layer (spec-for {:texts [{:mode :scheduled :content "x" :at [5.0 1.0 1.5] :duration-s 1.0}]}) "text-0")]
    (is (= {:type :windows :windows [{:start 30 :end 74} {:start 150 :end 179}]} (:timing t)))))

(deftest reference-semantics
  (let [spec (spec-for {:logo {:path "/l.png" :anchor :center
                               :animation {:type :flip-y :every-s 2.0 :duration-s 1.0 :phase-s 1.0}}
                        :texts [{:mode :continuous :content "c"}]})
        logo (layer spec "logo")
        {:keys [start duration]} (:animation logo)
        width (fn [n] (let [[x0 _ x1 _] (render/logo-bounds logo n)] (- x1 x0)))
        full  (get-in logo [:box :width])]
    (is (= [30 30] [start duration]))
    (is (== full (width 0)) "at rest before the first flip")
    (is (== full (width (+ start duration))) "and after it")
    (is (< (width (+ start 10)) (* 0.03 full)) "edge-on a third of the way through (theta = pi/2)")
    (is (< (Math/abs (double (- full (width (+ start 15))))) (* 0.02 full)) "halfway: facing away, full width")
    (let [[[tlx] [trx]] (render/logo-corners logo (+ start 15))]
      (is (> tlx trx) "the back face is mirrored"))
    (let [[_ y0 _ y1] (render/logo-bounds logo (+ start 5))]
      (is (> (- y1 y0) (get-in logo [:box :height])) "the near edge grows in perspective"))
    (testing "text"
      (let [t (layer spec "text-0")]
        (is (render/active? (:timing t) 12345))
        (is (= [24.0 (- 1080.0 40 24)] (render/text-origin t 0 [1920 1080] [300 40])))))))

(deftest specs-validate
  (let [spec (spec-for {:logo {:path "/l.png"}
                        :texts [{:mode :continuous :content "c"}
                                {:mode :scheduled :content "s" :at [1.0] :duration-s 2.0}]})]
    (is (= spec (spec-schema/validate! spec)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec-schema/validate! (assoc-in spec [:layers 0 :box :width] -1))))
    (is (map? (spec-schema/json-schema)))))

(deftest pro-modes-are-unavailable-without-the-pro-tree
  (is (= :feature-unavailable
         (try (spec-for {:texts [{:mode :hologram :content "x"}]}) nil
              (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e)))))))

(deftest golden-render-spec
  (golden/check "render-basic" (inputs/render-basic)))
