;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.render.schema
  "Schema of the render spec -- the contract between planning and engines.

  `json-schema` is exported to native/render-spec.schema.json (version 1)
  and native/render-spec-v2.schema.json (version 2) so engine authors
  working in Swift, Kotlin or Rust (behind the C ABI in
  native/include/wmark_engine.h) validate against the same definition. Kept
  apart from watermark.render so hosts without malli can still plan renders."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private Frame [:int {:min 0}])

(def Window [:map {:closed true} [:start Frame] [:end Frame]])

(def Timing
  [:multi {:dispatch :type}
   [:always   [:map {:closed true} [:type [:= :always]]]]
   [:windows  [:map {:closed true}
               [:type [:= :windows]]
               [:windows [:vector Window]]]]
   [:periodic [:map {:closed true}
               [:type [:= :periodic]]
               [:offset Frame]
               [:period [:int {:min 1}]]
               [:length [:int {:min 1}]]]]])

(def ^:private Fraction [:double {:min 0.0 :max 1.0}])
(def ^:private Scatter [:map {:closed true} [:a :int] [:b :int]])

(def Placement
  [:multi {:dispatch :type}
   [:fixed [:map {:closed true}
            [:type [:= :fixed]] [:fx Fraction] [:fy Fraction] [:px :int] [:py :int]]]
   [:burst-scatter [:map {:closed true}
                    [:type [:= :burst-scatter]]
                    [:margin Fraction] [:modulus [:int {:min 2}]]
                    [:x Scatter] [:y Scatter]]]
   [:per-window [:map {:closed true}
                 [:type [:= :per-window]]
                 [:points [:vector [:tuple Fraction Fraction]]]]]])

(def FlipY
  [:map {:closed true}
   [:type [:= :flip-y]]
   [:easing [:= :cosine]]
   [:start Frame]
   [:period [:int {:min 2}]]
   [:duration [:int {:min 1}]]
   [:distance [:double {:min 1.0}]]
   [:min-cos [:double {:min 0.0 :max 1.0}]]])

(def ImageLayer
  [:map {:closed true}
   [:id :string]
   [:kind [:= :image]]
   [:source [:map {:closed true} [:path :string] [:width pos-int?] [:height pos-int?]]]
   [:box [:map {:closed true} [:x :int] [:y :int] [:width pos-int?] [:height pos-int?]]]
   [:opacity [:double {:min 0.0 :max 1.0}]]
   [:timing Timing]
   [:animation {:optional true} FlipY]])

(def TextLayer
  [:map {:closed true}
   [:id :string]
   [:kind [:= :text]]
   [:mode :keyword]
   [:text [:string {:min 1}]]
   [:style [:map {:closed true}
            [:font [:maybe :string]]
            [:size pos-int?]
            [:color :string]
            [:opacity [:double {:min 0.0 :max 1.0}]]
            [:border [:int {:min 0}]]
            [:border-color :string]
            [:border-opacity [:double {:min 0.0 :max 1.0}]]]]
   [:placement Placement]
   [:timing Timing]])

(def Spec
  [:map {:closed true}
   [:spec/version [:= 1]]
   [:canvas [:map {:closed true} [:width pos-int?] [:height pos-int?]]]
   [:timebase [:map {:closed true}
               [:fps-num pos-int?] [:fps-den pos-int?]
               [:frames [:int {:min 1}]] [:first-frame Frame]]]
   [:layers [:vector [:multi {:dispatch :kind}
                      [:image ImageLayer]
                      [:text TextLayer]]]]])

;; ---------------------------------------------------------------------------
;; Render spec v2: host-drawn bitmaps (watermark.render.v2, docs/adr/0006)

(def ^:private BitmapId                                     ; SHA-256 of size and pixels
  [:re {:json-schema/pattern "^[0-9a-f]{64}$"} #"^[0-9a-f]{64}$"])

(def ^:private Placed
  [:map {:closed true} [:bitmap BitmapId] [:x :int] [:y :int]])

(def FlipbookLayer
  [:map {:closed true}
   [:id :string]
   [:kind [:= :flipbook]]
   [:timing Timing]
   [:rest Placed]
   [:cycle {:optional true}
    [:map {:closed true}
     [:start Frame]
     [:period [:int {:min 1}]]
     [:frames [:vector {:min 1} Placed]]]]])

(def BitmapLayer
  [:map {:closed true}
   [:id :string]
   [:kind [:= :bitmap]]
   [:bitmap BitmapId]
   [:placement Placement]
   [:timing Timing]])

(def SpecV2
  [:map {:closed true}
   [:spec/version [:= 2]]
   [:canvas [:map {:closed true} [:width pos-int?] [:height pos-int?]]]
   [:timebase [:map {:closed true}
               [:fps-num pos-int?] [:fps-den pos-int?]
               [:frames [:int {:min 1}]] [:first-frame Frame]]]
   [:bitmaps [:map-of {:json-schema/propertyNames {:pattern "^[0-9a-f]{64}$"}}
              BitmapId
              [:map {:closed true}                                    ; straight RGBA8, row-major
               [:width pos-int?] [:height pos-int?] [:path [:string {:min 1}]]]]]
   [:layers [:vector [:multi {:dispatch :kind}
                      [:flipbook FlipbookLayer]
                      [:bitmap BitmapLayer]]]]])

(def ^:private validators {1 (m/validator Spec) 2 (m/validator SpecV2)})
(def ^:private explainers {1 (m/explainer Spec) 2 (m/explainer SpecV2)})

(defn validate!
  "The spec (version 1 or 2), or an :invalid error saying which part is
  malformed."
  [spec]
  (let [v (:spec/version spec)]
    (if ((validators v (constantly false)) spec)
      spec
      (throw (ex-info "Malformed render spec."
                      {:wmark/error :invalid
                       :errors      (if-let [e (explainers v)]
                                      (me/humanize (e spec))
                                      {:spec/version [(str "should be 1 or 2, not " (pr-str v))]})})))))

(defn json-schema
  "JSON Schema of render spec `version` (default 1): native/render-spec.schema.json
  and native/render-spec-v2.schema.json."
  ([] (json-schema 1))
  ([version] (mjs/transform (if (= 2 version) SpecV2 Spec))))
