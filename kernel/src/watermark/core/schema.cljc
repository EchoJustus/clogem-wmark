;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.schema
  "Domain schema for watermark settings.

  .cljc on purpose: the ClojureScript SPA validates forms with exactly these
  rules, and `json-schema` serves the same definition to any other UI (the
  TUI, a future web dashboard) over the API.

  The *shapes* of Pro modes live here, in the open core, so the community UI
  can render them as locked (\"Pro\") controls; their *implementations* live in
  the Pro source tree only.

  Portability: this is the only kernel namespace that needs malli. Hosts
  without malli (ClojureDart, Stage 3/4) validate against the JSON Schema that
  `json-schema` emits at build time instead."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]))

(def Anchor
  [:enum :top-left :top-center :top-right
   :center-left :center :center-right
   :bottom-left :bottom-center :bottom-right])

(def Offset
  [:map {:closed true}
   [:x {:optional true} [:int {:min -10000 :max 10000}]]
   [:y {:optional true} [:int {:min -10000 :max 10000}]]])

(def Seconds [:double {:min 0.0}])

(def LogoAnimation
  [:map {:closed true}
   [:type [:enum :none :flip-y]]
   [:every-s    {:optional true} [:double {:min 2.0}]]
   [:duration-s {:optional true} [:double {:min 0.2 :max 5.0}]]
   [:phase-s    {:optional true} Seconds]])

(def Logo
  [:map {:closed true}
   [:enabled     {:optional true} :boolean]
   [:path        {:optional true} [:string {:min 1}]]
   [:anchor      {:optional true} Anchor]
   [:offset      {:optional true} Offset]
   [:width-ratio {:optional true} [:double {:min 0.02 :max 0.6}]]
   [:opacity     {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:animation   {:optional true} LogoAnimation]])

(def ^:private text-common
  [[:id         {:optional true} [:string {:min 1 :max 40}]]
   [:content    [:string {:min 1 :max 500}]]
   [:font-path  {:optional true} [:string {:min 1}]]
   [:size-ratio {:optional true} [:double {:min 0.01 :max 0.3}]]   ; of video height
   [:color      {:optional true} [:re "^(#[0-9A-Fa-f]{6}|[a-zA-Z]+)$"]]
   [:opacity    {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:border     {:optional true} [:int {:min 0 :max 20}]]
   [:anchor     {:optional true} Anchor]
   [:offset     {:optional true} Offset]])

(defn- text-layer [mode tier extra]
  (into [:map {:closed true :json-schema/x-tier (name tier)}
         [:mode [:= mode]]]
        (concat text-common extra)))

(def TextLayer
  [:multi {:dispatch :mode
           ;; JSON arrives with "mode": "subliminal"; dispatch needs the keyword
           :decode/json (fn [x] (if (and (map? x) (string? (:mode x)))
                                  (update x :mode keyword)
                                  x))}
   [:continuous (text-layer :continuous :community [])]
   [:scheduled  (text-layer :scheduled :community
                            [[:at [:vector {:min 1 :max 500} Seconds]]
                             [:duration-s [:double {:min 0.04 :max 600.0}]]])]
   ;; PRO: 1-3 frame "canary" inserts at a keyed, per-video phase
   [:subliminal (text-layer :subliminal :pro
                            [[:every-s {:optional true} [:double {:min 1.0 :max 600.0}]]
                             [:frames  {:optional true} [:int {:min 1 :max 3}]]])]
   ;; PRO: visible text at unpredictable (keyed) times and places
   [:random     (text-layer :random :pro
                            [[:min-duration-s {:optional true} [:double {:min 0.04 :max 60.0}]]
                             [:max-duration-s {:optional true} [:double {:min 0.04 :max 60.0}]]
                             [:min-gap-s      {:optional true} [:double {:min 0.5 :max 3600.0}]]
                             [:max-gap-s      {:optional true} [:double {:min 0.5 :max 3600.0}]]])]])

(def Output
  [:map {:closed true}
   [:dir            {:optional true} [:string {:min 1}]]
   [:suffix         {:optional true} [:string {:max 40}]]
   [:container      {:optional true} [:enum "mp4" "mov" "mkv"]]
   [:overwrite?     {:optional true} :boolean]
   [:strip-metadata {:optional true} :boolean]])

(def FFmpegEncode
  "FFmpeg-only overrides. Engines other than FFmpeg ignore this block."
  [:map {:closed true :json-schema/x-engine "ffmpeg"}
   [:video-codec {:optional true}
    [:enum "libx264" "libx265" "h264_nvenc" "hevc_nvenc" "h264_qsv" "hevc_qsv"
     "h264_amf" "hevc_amf" "h264_videotoolbox" "hevc_videotoolbox"]]
   [:crf    {:optional true} [:int {:min 0 :max 51}]]
   [:preset {:optional true} [:string {:min 1 :max 20}]]])

(def Encode
  "Engine-neutral encoding intent. Profiles are persisted and move between
  platforms (a profile saved on Windows must still mean something on an iPad
  encoding with VideoToolbox), so they say *what* is wanted -- codec family,
  quality tier -- and each engine maps that to its own knobs (CRF for x264,
  bitrate for MediaCodec, preset for AVFoundation). Engine-specific overrides
  live in their own optional block."
  [:map {:closed true}
   [:codec   {:optional true} [:enum :h264 :hevc]]
   [:quality {:optional true} [:enum :archival :high :balanced :compact]]
   [:audio   {:optional true} [:enum :copy :aac :none]]
   [:ffmpeg  {:optional true} FFmpegEncode]])

(def Settings
  [:map {:closed true}
   [:output {:optional true} Output]
   [:encode {:optional true} Encode]
   [:logo   {:optional true} Logo]
   [:texts  {:optional true} [:vector {:max 8} TextLayer]]])

(def defaults
  "Built-in lowest layer of the resolution chain (see watermark.config)."
  {:output {:suffix "_wm" :container "mp4" :overwrite? false :strip-metadata true}
   :encode {:codec :h264 :quality :high :audio :copy}
   :logo   {:enabled     true
            :anchor      :bottom-right
            :offset      {:x 24 :y 24}
            :width-ratio 0.12
            :opacity     0.85
            :animation   {:type :flip-y :every-s 60.0 :duration-s 1.0}}
   :texts  []})

;; Compiled once; malli validators/decoders are plain closures (no eval),
;; so they are safe to build during native-image build-time initialisation.
(def ^:private validator (m/validator Settings))
(def ^:private explainer (m/explainer Settings))
(def ^:private json-decoder
  (m/decoder Settings (mt/transformer mt/json-transformer)))

(defn decode-json
  "JSON-shaped settings (string enums, integral doubles) -> Clojure settings."
  [x]
  (json-decoder x))

(defn validate!
  "Settings or an :invalid error with humanised messages per field."
  [settings]
  (if (validator settings)
    settings
    (throw (ex-info "Invalid settings."
                    {:wmark/error :invalid
                     :errors      (me/humanize (explainer settings))}))))

(defn json-schema
  "JSON Schema of the settings, for UIs that render forms from it.
  Pro-only text modes carry \"x-tier\": \"pro\"."
  []
  (mjs/transform Settings))
