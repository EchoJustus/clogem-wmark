;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.views
  "The built-in UI as data: pure functions from Core API results to hiccup.

  Where user text may appear:
  * as element text or an input's value: escaped by watermark.web.html;
  * in signals: only through datastar-patch-signals events, which the browser
    reads with JSON.parse.

  Never inside a data-* attribute: Datastar evaluates those as JavaScript.
  Expressions here contain only literals and `path-segment` output."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [watermark.core.features :as features]
            [watermark.server.routes :as routes])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn path-segment
  "URL-encode `s` for a URL inside a data-* expression. The result has only
  ASCII letters, digits and . - * _ %, so it can't end a JavaScript string."
  ^String [s]
  (str/replace (URLEncoder/encode (str s) StandardCharsets/UTF_8) "+" "%20"))

(defn act
  "A Datastar action expression, e.g. (act \"post\" \"/ui/jobs\")."
  [method & parts]
  (str "@" method "('" (apply str parts) "')"))

(defn- pretty
  "JSON with two-space indentation, one entry per line (as JSON.stringify
  with an indent writes it)."
  [x indent]
  (let [pad  (apply str (repeat indent " "))
        pad+ (str pad "  ")]
    (cond
      (and (map? x) (seq x))
      (str "{\n" (str/join ",\n" (for [[k v] x]
                                    (str pad+ (json/write-str (if (keyword? k) (name k) (str k)) :escape-slash false :escape-unicode false)
                                         ": " (pretty v (+ indent 2)))))
           "\n" pad "}")
      (and (sequential? x) (seq x))
      (str "[\n" (str/join ",\n" (for [v x] (str pad+ (pretty v (+ indent 2))))) "\n" pad "]")
      :else (json/write-str x :escape-slash false :escape-unicode false))))

(defn settings-text
  "Settings as the pretty JSON the editor shows. Text modes appear by their
  display names (\"canary\"); saving resolves them back to wire ids."
  [settings]
  (str (pretty (routes/jsonable (features/display-settings (or settings {}))) 0) "\n"))

;; ---------------------------------------------------------------------------
;; Header

(defn- engine-status [{:keys [engine]}]
  (let [id      (:engine/id engine)
        label   (if (= :ffmpeg id) "FFmpeg" (str "Engine " (some-> id name)))
        release (some->> (:engine/version engine) str (re-find #"^\d+(?:\.\d+){0,2}"))
        notes   (str/join "\n" (concat (:problems engine) (:warnings engine)))]
    (if (:available? engine)
      {:text (str label (when release (str " " release)) " ready")
       :bad? (boolean (seq (:warnings engine)))
       :title notes}
      {:text (or (first (:problems engine)) (str label " is not available"))
       :bad? true
       :title notes})))

(def themes
  "The theme switcher's choices, by the index the `theme` signal holds."
  ["system" "light" "dark"])

(defn theme-switcher []
  [:div {:class "segmented" :role "group" :aria-label "Theme"}
   (for [[i t] (map-indexed vector themes)]
     [:button {:type "button" :title (str (str/capitalize t) " theme")
               "data-class:active" (str "$theme === " i)
               "data-on:click" (act "post" "/ui/theme/" i)}
      (str/capitalize t)])])

(defn header [health]
  (let [{:keys [text bad? title]} (engine-status health)]
    [:header {:class "topbar"}
     [:div {:class "brand"}
      [:div {:class "mark" :aria-hidden "true"} [:img {:src "/wmark.svg" :alt "" :width "30" :height "30"}]]
      [:h1 "wmark"]]
     [:span {:class "badge" :id "edition"}
      (str (if (= :pro (:edition health)) "Pro" "Community") " " (:version health))]
     [:div {:class "spacer"}]
     [:div {:class "status-line"}
      [:span {:class (if bad? "badge bad engine" "badge ok engine") :id "engine" :title (not-empty title)} text]]
     (theme-switcher)]))

;; ---------------------------------------------------------------------------
;; Profiles

(defn profile-list [items selected-slug]
  [:ul {:id "profiles"}
   (if (empty? items)
     [:li {:class "empty"}
      "No profiles yet. Every run saves its settings as “latest”, so there will be one after your first job."]
     (for [{:keys [name slug auto? error]} items]
       [:li [:button {:type "button" :aria-current (str (= slug selected-slug))
                      "data-on:click" (act "get" "/ui/profiles/" (path-segment slug))}
             name
             (when auto? [:span {:class "auto"} " (last run)"])
             (when error [:span {:class "auto"} " (can't be read)"])]]))])

(defn message
  "The status line under the inspector's title."
  ([] (message nil nil))
  ([kind text]
   [:p {:id "message" :class (if (= kind :bad) "message bad" "message") :role "status" :aria-live "polite"}
    text]))

(defn inspector-head
  "Title and actions for the selected profile document (nil: none)."
  [doc]
  (let [slug  (:profile/slug doc)
        seg   (some-> slug path-segment)
        on?   (some? doc)
        auto? (true? (:profile/auto? doc))]
    [:div {:id "inspector-head" :class "profile-head"}
     [:div
      [:h2 {:id "profile-title"} (if on? (:profile/name doc) "No profile selected")]
      (when auto? [:p {:class "hint"} "Saved by every run: what the last run used."])]
     [:div {:class "actions"}
      [:input {:type "text" :id "name" "data-bind:name" true :maxlength "80"
               :placeholder "New name" :aria-label "New name for rename or duplicate" :disabled (not on?)}]
      [:button {:type "button" :id "rename" :disabled (or (not on?) auto?)
                "data-on:click" (when (and on? (not auto?)) (act "post" "/ui/profiles/" seg "/rename"))} "Rename"]
      [:button {:type "button" :id "duplicate" :disabled (not on?)
                "data-on:click" (when on? (act "post" "/ui/profiles/" seg "/copy"))} "Duplicate"]
      [:button {:type "button" :id "delete" :class "danger" :disabled (not on?)
                "data-on:click" (when on? (str "confirm('Delete this profile? It cannot be undone.') && "
                                               (act "delete" "/ui/profiles/" seg)))} "Delete"]]]))

(defn editor
  "The settings as JSON, for bulk edits. Its text comes from the `settings`
  signal once the page is live; the initial text here seeds that signal."
  [doc]
  (let [seg (some-> (:profile/slug doc) path-segment)]
    [:div {:class "editor" :id "editor"}
     [:label {:for "settings"}
      [:span "Settings as JSON. Anything left out is inherited from the defaults."]]
     [:textarea {:id "settings" :spellcheck "false" "data-bind:settings" true :disabled (nil? doc)
                 "data-on:input__debounce.300ms" (when seg (act "post" "/ui/preview/" seg))}
      (when doc (settings-text (:settings doc)))]
     [:div {:class "actions"}
      [:button {:type "button" :class "primary" :id "save" :disabled (nil? doc)
                "data-on:click" (when seg (act "put" "/ui/profiles/" seg))} "Save JSON"]]]))

;; ---------------------------------------------------------------------------
;; Effective settings

(defn- leaves
  "[[path value]] for every leaf of nested maps; vectors are leaves."
  ([m] (leaves [] m))
  ([prefix m]
   (mapcat (fn [[k v]]
             (let [p (conj prefix k)]
               (if (and (map? v) (seq v)) (leaves p v) [[p v]])))
           (sort-by (comp str key) m))))

(defn- show-value [v]
  (cond (keyword? v) (name v)
        (or (map? v) (vector? v)) (json/write-str (routes/jsonable v) :escape-slash false)
        :else (str v)))

(defn- source-label [r path preview?]
  (case (get-in r [:provenance path])
    :profile   (if (= :latest (get-in r [:base :kind])) "last run" "this profile")
    :overrides (if preview? "this profile (unsaved)" "override")
    "default"))

(defn effective
  "What a run uses: every leaf, its value and which layer it came from.
  `preview?` marks a resolution of unsaved editor text."
  ([] [:div {:id "effective" :class "effective"}])
  ([r titles preview?]
   [:div {:id "effective" :class "effective"}
    [:h3 (if preview? "What a run would use with your edits" "What a run with this profile uses")]
    [:p {:class "locked"}
     (when (seq (:locked r))
       (str "Needs wmark Pro to run: " (str/join ", " (map #(get titles % (subs (str %) 1)) (:locked r))) "."))]
    [:table
     [:thead [:tr [:th {:scope "col"} "Setting"] [:th {:scope "col"} "Value"] [:th {:scope "col"} "From"]]]
     [:tbody
      (for [[path v] (leaves (features/display-settings (:settings r)))
            :let [label (source-label r path preview?)]]
        [:tr {:class (when (not= "default" label) "set")}
         [:td (str/join ".​" (map name path))]      ; line breaks only after dots
         [:td (show-value v)]
         [:td label]])]]]))

;; ---------------------------------------------------------------------------
;; Render queue

(defn run-button
  "Queues the paths in the `inputs` signal, based on the selected profile."
  [slug]
  [:button {:type "button" :class "primary" :id "run" "data-indicator:queueing" true
            "data-attr:disabled" "$queueing"
            "data-on:click" (if slug (act "post" "/ui/jobs/" (path-segment slug)) (act "post" "/ui/jobs"))}
   "Watermark files"])

(defn- pct [job]
  (when (and (= :running (:state job)) (number? (get-in job [:progress :fraction])))
    (Math/round (* 100.0 (double (get-in job [:progress :fraction]))))))

(defn job-row [{:keys [id inputs state progress error result] :as job}]
  (let [label   (if (= 1 (count inputs)) (first inputs) (str (count inputs) " files"))
        p       (pct job)
        active? (#{:running :queued} state)]
    [:li {:id (str "job-" id) :class (name state)}
     [:span {:class "file"} (if (and (= :running state) (< 1 (count inputs)) (:input progress))
                              (str label ": " (:input progress))
                              label)]
     [:span {:class "state"} (if p (str p "%") (name state))]
     (if (= :running state)
       [:progress {:max "100" :value (str (or p 0)) :aria-label (str "Progress for " label)}]
       [:span])
     (if active?
       [:button {:type "button" "data-on:click" (act "post" "/ui/jobs/" (path-segment id) "/cancel")} "Cancel"]
       [:span])
     (when error [:p {:class "error"} (:message error)])
     (for [{:keys [input error]} result :when error]
       [:p {:class "error"} (str input ": " error)])]))

(defn job-list [jobs]
  [:ol {:id "jobs"} (map job-row (sort-by :created-at #(compare %2 %1) jobs))])

(defn activity [lines]
  [:ol {:id "activity" :aria-label "Activity"} (for [l lines] [:li l])])

(defn queue-panel [jobs slug]
  [:section {:class "queue card" :aria-labelledby "queue-title"
             ;; a stream per visible tab: Datastar closes GET streams in hidden
             ;; tabs and reopens them, and every (re)open re-renders the queue
             "data-init" "@get('/ui/stream', {retry: 'always', retryMaxCount: 1000000})"}
   [:div {:class "card-head"} [:h2 {:id "queue-title"} "Render queue"]]
   [:div {:class "queue-grid"}
    [:div
     [:label {:class "inputs"}
      [:span "Files to watermark, one full path per line. On Windows, Shift + right-click a file and choose “Copy as path”."]
      [:textarea {:id "inputs" :rows "3" :spellcheck "false" "data-bind:inputs" true}]]
     (run-button slug)]
    [:div
     (job-list jobs)
     [:h3 "Activity"]
     (activity [])]]])

;; ---------------------------------------------------------------------------
;; The page

(defn page
  "The whole UI. `nonce` switches Datastar to CSP mode (no eval). `form` is
  the selected profile's settings form (watermark.core.api/settings-form);
  `theme` the index into `themes`. The settings form and the preview panel
  are rendered by `form-view` and `preview-view`, passed in so this
  namespace doesn't depend on theirs."
  [{:keys [nonce health profiles doc resolved titles jobs theme form-view preview-view]}]
  (let [slug  (:profile/slug doc)
        theme (if (contains? #{0 1 2} theme) theme 0)]
    [:html {:lang "en" "data-nonce" nonce}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:meta {:name "color-scheme" :content "light dark"}]
      [:title "wmark"]
      [:link {:rel "icon" :type "image/svg+xml" :href "/wmark.svg"}]
      [:link {:rel "stylesheet" :href "/app.css"}]
      [:script {:type "module" :src "/datastar.js"}]]
     ;; only numbers go into data-signals; text signals are created by
     ;; data-bind from the escaped element values
     [:body {"data-signals" (json/write-str {:rev (or (:profile/rev doc) 0) :theme theme
                                             :pv 0 :pa 0 :pt 0 :pu 0 :pmax 12})
             :data-theme (themes theme)
             "data-attr:data-theme" "['system', 'light', 'dark'][$theme]"}
      (header health)
      [:main {:class "shell"}
       [:nav {:class "rail card" :aria-labelledby "bin-title"}
        [:h2 {:id "bin-title"} "Profiles"]
        (profile-list profiles slug)
        [:div {:class "namebar"}
         [:input {:type "text" :id "newname" "data-bind:newname" true :maxlength "80"
                  :placeholder "New profile name" :aria-label "New profile name"}]
         [:button {:type "button" :id "new-profile" "data-on:click" (act "post" "/ui/profiles")} "New profile"]]]
       [:section {:class "work" :aria-labelledby "profile-title"}
        [:div {:class "card"}
         (inspector-head doc)
         (message)]
        form-view
        [:details {:class "json card"}
         [:summary "Edit as JSON"]
         [:div {:class "panes"}
          (editor doc)
          (if resolved (effective resolved titles false) (effective))]]]
       preview-view
       (queue-panel jobs slug)]]]))
