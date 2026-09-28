;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.form
  "The settings form and the preview panel as hiccup (docs/adr/0011,
  sections 3 and 5), from the Core API's form model
  (watermark.core.api/settings-form), which a GUI app renders too.

  Click to edit: a row shows its value as a button; the button asks the
  server for the row in edit mode, whose control is bound to the `fv`
  signal. Enter, Save or a changed choice sends it with @put; the server
  saves that one path with the revision the page read (`rev`) and answers
  with the whole form, back in view mode. Escape or Cancel asks for the
  view again.

  As everywhere in this UI, user text reaches the page as escaped text or
  through signals (JSON). data-* expressions hold only literals and
  path-segment output: the row id comes from the schema's catalog, and even
  so it's URL-encoded."
  (:require [clojure.string :as str]
            [watermark.web.views :as v])
  (:import (java.util Locale)))

(set! *warn-on-reflection* true)

(defn dom-id
  "An element id for row `id` (\"logo.offset.x\" -> \"row-logo-offset-x\")."
  [id]
  (str "row-" (str/replace (str id) #"[^A-Za-z0-9-]" "-")))

(defn- field-url [slug id & more]
  (apply str "/ui/profiles/" (v/path-segment slug) "/fields/" (v/path-segment id) more))

(def ^:private searchable
  ;; the row's own text (titles and values) decides; nothing is evaluated
  "!$q || el.textContent.toLowerCase().includes($q.toLowerCase())")

(defn- badge [source sources]
  [:span {:class (str "badge " (name source))} (get sources source)])

(defn- label [{:keys [id title description]}]
  [:div {:class "row-main"}
   [:div [:span {:class "row-title"} title] [:code {:class "row-path"} id]]
   (when description [:p {:class "row-desc"} description])])

(def ^:private whole-layer
  "Layer fields that can't be reset on their own: a layer needs its kind and
  its text."
  #{"mode" "content"})

(defn row-view
  "A setting showing its value. Inside a text layer (`layer?`) the card
  shows where the layers came from, and a field resets when it's set."
  ([slug row sources] (row-view slug row sources false))
  ([slug {:keys [id kind title text value source] :as row} sources layer?]
  (let [set? (if layer?
               (and (some? value) (not (whole-layer (subs id (inc (str/last-index-of id "."))))))
               (#{:set-here :last-run} source))]
    [:div {:class "row" :id (dom-id id) "data-show" searchable}
     (label row)
     [:div {:class "row-control"}
      (if (= :boolean kind)
        [:label {:class "switch"}
         [:input {:type "checkbox" :role "switch" :checked (true? value) :aria-label title
                  "data-on:change" (str "$fv = el.checked; " (v/act "put" (field-url slug id)))}]
         [:span {:class "track" :aria-hidden "true"}]]
        [:button {:type "button" :class (if (str/blank? text) "value empty" "value")
                  :aria-label (str "Change " title)
                  "data-on:click" (v/act "get" (field-url slug id "/edit"))}
         (if (str/blank? text) "Not set" text)])
      (when-not layer? (badge source sources))
      (when set?
        [:button {:type "button" :class "link" :title (str "Reset " title " to what it inherits")
                  "data-on:click" (v/act "delete" (field-url slug id))}
         "Reset"])]])))

(defn- keys-expr
  "Enter saves, Escape cancels."
  [slug id]
  (str "evt.key === 'Enter' && (evt.preventDefault(), " (v/act "put" (field-url slug id)) "); "
       "evt.key === 'Escape' && " (v/act "get" (field-url slug id))))

(defn- control [slug {:keys [id kind title min max step slider options multiline]}]
  (let [common {"data-bind:fv" true :aria-label title "data-init" "el.focus()"}]
    (case kind
      :enum
      [:select (assoc common "data-on:change" (v/act "put" (field-url slug id)))
       (for [{:keys [value label locked]} options]
         [:option {:value value :disabled (true? locked)} (if locked (str label " (Pro)") label)])]

      (:number :integer)
      (list
       [:input (merge common {:type "number" :min min :max max :step (or step (if (= :integer kind) 1 "any"))
                              :class "num" "data-on:keydown" (keys-expr slug id)})]
       (when slider
         [:input {:type "range" "data-bind:fv" true :min min :max max :step (or step "any")
                  :aria-label (str title " slider")}]))

      [:input (merge common {:type "text" :maxlength (when (= :text kind) (if multiline 500 80))
                             :spellcheck "false"
                             :placeholder (case kind :file "Full path to the file" :folder "Full path to the folder" nil)
                             "data-on:keydown" (keys-expr slug id)})])))

(defn row-edit
  "A setting in edit mode; `error` is shown under it."
  [slug {:keys [id] :as row} sources error]
  [:div {:class "row editing" :id (dom-id id)}
   (label row)
   [:div {:class "row-control"}
    (control slug row)
    [:button {:type "button" :class "primary small" "data-on:click" (v/act "put" (field-url slug id))} "Save"]
    [:button {:type "button" :class "small" "data-on:click" (v/act "get" (field-url slug id))} "Cancel"]]
   (when error [:p {:class "field-error" :role "alert"} error])])

(defn find-row
  "The row with `id` in a form model, from any section or layer."
  [form id]
  (some #(when (= id (:id %)) %)
        (concat (mapcat :rows (:categories form))
                (mapcat :rows (get-in form [:texts :layers])))))

;; ---------------------------------------------------------------------------
;; Sections

(defn- layer-url [slug i & more]
  (apply str "/ui/profiles/" (v/path-segment slug) "/layers/" i more))

(defn- layer-card [slug {:keys [index title subtitle tier locked rows]} n sources]
  [:div {:class "layer" :id (str "layer-" index)}
   [:div {:class "layer-head"}
    [:h4 title " · " [:span {:class "kind"} subtitle]]
    (when tier [:span {:class "badge pro" :title (when locked "Saved with the profile; runs need wmark Pro")} "Pro"])
    [:button {:type "button" :class "icon" :title "Move up" :aria-label (str "Move " title " up")
              :disabled (zero? index) "data-on:click" (v/act "post" (layer-url slug index "/up"))} "↑"]
    [:button {:type "button" :class "icon" :title "Move down" :aria-label (str "Move " title " down")
              :disabled (= index (dec n)) "data-on:click" (v/act "post" (layer-url slug index "/down"))} "↓"]
    [:button {:type "button" :class "small danger" :aria-label (str "Remove " title)
              "data-on:click" (str "confirm('Remove this text layer?') && " (v/act "delete" (layer-url slug index)))}
     "Remove"]]
   (for [r rows :when (not (:advanced r))] (row-view slug r sources true))
   (let [adv (filter :advanced rows)]
     (when (seq adv)
       [:details {:class "advanced"} [:summary "More"] (for [r adv] (row-view slug r sources true))]))])

(defn- texts-card [slug {:keys [layers modes source]} {:keys [title description]} sources]
  [:section {:class "card" :id "cat-texts" :aria-labelledby "cat-texts-title"}
   [:div {:class "card-head"}
    [:h3 {:id "cat-texts-title"} title]
    (when (seq layers) (badge source sources))]
   [:p {:class "card-desc"} description]
   (if (empty? layers)
     [:p {:class "hint"} "No text layers. Add one to put a warning on every video."]
     (for [l layers] (layer-card slug l (count layers) sources)))
   [:div {:class "add-layer"}
    [:select {"data-bind:nl" true :aria-label "Kind of the new text layer"}
     (for [{:keys [value label tier]} modes]
       [:option {:value value} (if tier (str label " (Pro)") label)])]
    [:button {:type "button" :disabled (>= (count layers) 8)
              "data-on:click" (v/act "post" "/ui/profiles/" (v/path-segment slug) "/layers")}
     "Add text layer"]]])

(defn- category-card [slug {:keys [id title description rows]} sources]
  (let [plain (remove :advanced rows)
        adv   (filter :advanced rows)]
    [:section {:class "card" :id (str "cat-" (name id)) :aria-labelledby (str "cat-" (name id) "-title")}
     [:div {:class "card-head"} [:h3 {:id (str "cat-" (name id) "-title")} title]]
     [:p {:class "card-desc"} description]
     (for [r plain] (row-view slug r sources))
     (when (seq adv)
       [:details {:class "advanced"} [:summary "Advanced"] (for [r adv] (row-view slug r sources))])]))

(defn settings-form
  "The whole form for profile `slug`, from (api/settings-form ...); nil
  shows the empty state."
  [slug {:keys [form]}]
  [:div {:id "settings-form" :class "form"}
   (if-not form
     [:div {:class "card"} [:p {:class "hint"} "Choose a profile on the left, or create one, to edit its settings."]]
     (let [sources (:sources form)
           cats    (:categories form)]
       (list
        [:div {:class "form-toolbar"}
         [:input {:type "search" "data-bind:q" true :placeholder "Search settings" :aria-label "Search settings"}]
         [:nav {:class "toc" :aria-label "Sections"}
          (for [{:keys [id title]} cats] [:a {:href (str "#cat-" (name id))} title])]]
        (for [c cats]
          (if (= :texts (:id c))
            (texts-card slug (:texts form) c sources)
            (category-card slug c sources))))))])

;; ---------------------------------------------------------------------------
;; Preview (docs/adr/0011, section 5)

(def aspects
  "The sample shapes, by the index the `pa` signal holds."
  ["16:9" "9:16" "1:1"])

(defn preview-frame
  "The drawn frame, or why there is none."
  ([] (preview-frame nil nil))
  ([p error]
   [:figure {:id "preview-frame" :class "frame" "data-class:busy" "$previewing"}
    (cond
      error [:div {:class "frame-empty" :role "status"} error]
      p     [:img {:src (str "/api/v1/previews/" (:id p)) :width (:width p) :height (:height p)
                   :alt (str "Frame " (:frame p) " with the watermark, as the render draws it")}]
      :else [:div {:class "frame-empty"} "The preview appears here."])
    (when p
      [:figcaption
       [:p {:class "frame-caption"}
        (str "Frame " (:frame p) " · " (String/format Locale/ROOT "%.2f" (to-array [(double (:t p))])) " s · "
             (if (:sample? p) (str "sample " (:aspect p)) (str (:width p) "×" (:height p))))]
       (for [n (:notes p)] [:p {:class "frame-note"} n])])]))

(defn preview-panel
  "The preview card. It asks for a frame when it appears and whenever the
  shape (`pa`), the time (`pt`), the video (`pu` counts its changes) or the
  saved settings (`pv`) change. The request goes inside @peek: @post reads
  every signal to send it, and the effect must not subscribe to those (the
  request's own `previewing` flag would start it again, for ever)."
  [slug]
  [:aside {:id "preview" :class "preview card" :aria-labelledby "preview-title"
           "data-effect" (when slug (str "$pv; $pa; $pt; $pu; @peek(() => "
                                         (v/act "post" "/ui/frame/" (v/path-segment slug)) ")"))
           "data-indicator:previewing" true}
   [:div {:class "card-head"}
    [:h2 {:id "preview-title"} "Preview"]
    [:span {:class "hint"} "The render's own frame"]]
   (preview-frame)
   [:div {:class "preview-controls"}
    [:div {:class "segmented" :role "group" :aria-label "Sample shape"}
     (for [[i a] (map-indexed vector aspects)]
       [:button {:type "button" "data-on:click" (str "$pa = " i) "data-class:active" (str "$pa === " i)
                 "data-attr:aria-pressed" (str "$pa === " i)} a])]
    [:label {:class "scrub"}
     [:span "Time"]
     [:input {:type "range" :min "0" :max "12" :step "0.04" :value "0" :aria-label "Time in the clip"
              "data-attr:max" "$pmax" "data-on:input__debounce.250ms" "$pt = +el.value"}]
     [:output {"data-text" "(+$pt).toFixed(2) + ' s'"} "0.00 s"]]
    [:div {:class "source"}
     [:input {:type "text" :id "preview-source" "data-bind:psdraft" true :spellcheck "false"
              :placeholder "Or preview on a video: its full path" :aria-label "Video to preview on"}]
     [:button {:type "button" "data-on:click" "$ps = $psdraft; $pu = $pu + 1"} "Use"]]]])
