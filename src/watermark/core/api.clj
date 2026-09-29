;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.api
  "The Core API -- the one contract every presentation layer uses.

  HTTP routes, the CLI, the web UI and GUI shells (through HTTP) call these
  functions; none of them touch profile files, engines or entitlements
  directly. That is the Clash-style split: a headless engine with a stable
  API, and any number of clients.

  Every function takes the system map `sys` and a request context `ctx`
  ({:tenant .. :user ..}). The system map carries the ports, so the same
  functions serve every deployment:

    port            desktop                         serverless (Stage 5)
    :profiles-for   file store                      Postgres store per tenant/user
    :engine         FFmpeg (or native via C ABI)    FFmpeg in the worker image
    :media          local files                     object storage
    :jobs           in-process queue                durable queue + workers
    :entitlements   offline license                 account plan
    :secret-for     <home>/secret.key               per-tenant secret (KMS)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.config :as config]
            [watermark.core.features :as features]
            [watermark.core.form :as form]
            [watermark.core.jobs :as jobs]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.engine :as engine])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- store [sys ctx] ((:profiles-for sys) ctx))

;; ---------------------------------------------------------------------------
;; System

(defn health [sys]
  (let [info (engine/info (:engine sys))]
    {:status  "ok"
     :version (:version sys)
     :edition (:edition sys)
     :home    (str (:home sys))
     :engine  (-> (select-keys info [:engine/id :engine/version :available? :problems :warnings :capabilities])
                  (assoc :binaries (into {} (for [[k v] (:binaries info)] [k (select-keys v [:path :source])]))))}))

(defn diagnose
  "Health plus the full binary-resolution trail -- `wmark doctor`."
  [sys]
  (assoc (health sys) :binaries (:binaries (engine/info (:engine sys)))))

(defn features [sys _ctx] (features/report (:entitlements sys)))

(defn settings-schema [_sys]
  {:schema (schema/json-schema) :defaults schema/defaults})

;; ---------------------------------------------------------------------------
;; Profiles (validated with the domain schema before they reach the store)

(defn list-profiles [sys ctx] (config/list-profiles (store sys ctx)))
(defn get-profile [sys ctx name] (config/get-profile! (store sys ctx) name))

(defn create-profile! [sys ctx name settings]
  (config/create-profile! (store sys ctx) name (schema/validate! settings)))

(defn save-profile! [sys ctx name settings opts]
  (config/save-profile! (store sys ctx) name (schema/validate! settings) opts))

(defn rename-profile! [sys ctx from to] (config/rename-profile! (store sys ctx) from to))
(defn copy-profile! [sys ctx from to] (config/copy-profile! (store sys ctx) from to))
(defn delete-profile! [sys ctx name] (config/delete-profile! (store sys ctx) name))

;; ---------------------------------------------------------------------------
;; Resolution: fallback chain -> schema -> entitlements

(defn resolve-settings
  "Effective settings for a request {:profile .. :settings overrides}, with
  per-field provenance and the feature ids the current plan doesn't cover
  (:locked) -- the UI shows those as upgrade prompts instead of failing."
  [sys ctx {:keys [profile settings]}]
  (let [r (config/resolve-settings (store sys ctx) {:profile   profile
                                                    :overrides settings
                                                    :defaults  schema/defaults})
        s (schema/validate! (:settings r))]
    (assoc r
           :settings s
           :locked (->> (features/required-features s)
                        (remove #(features/entitled? (:entitlements sys) %))
                        sort vec))))

(defn- entitled-fn [sys] (fn [feature] (features/entitled? (:entitlements sys) feature)))

(defn settings-form
  "The settings form of profile `name` (docs/adr/0011, section 3): a row per
  setting with its value and where it came from, text layers as cards, Pro
  choices marked. Every UI renders this same model (watermark.core.form).
  `settings`, when given, are unsaved edits shown on top."
  ([sys ctx name] (settings-form sys ctx name nil))
  ([sys ctx name settings]
   (let [doc (get-profile sys ctx name)
         r   (resolve-settings sys ctx {:profile (:profile/slug doc) :settings settings})]
     {:profile  (select-keys doc [:profile/name :profile/slug :profile/rev :profile/auto?])
      :form     (form/model r {:entitled? (entitled-fn sys)})
      :locked   (:locked r)
      :warnings (:warnings r)})))

(defn edit-profile!
  "Apply one form edit (watermark.core.form/edit: set or reset one setting,
  add, remove or move a text layer) to profile `name` and save it, only if
  it's still at revision `if-rev` when given (a stale edit is a 409). Returns
  the saved document."
  [sys ctx name {:keys [if-rev] :as edit}]
  (let [doc       (get-profile sys ctx name)
        effective (:settings (resolve-settings sys ctx {:profile (:profile/slug doc)}))
        settings  (form/edit (:settings doc) effective edit)]
    (save-profile! sys ctx (:profile/slug doc) settings {:if-rev (or if-rev (:profile/rev doc))})))

(defn- changed-paths
  "The setting paths where `a` and `b` differ: every leaf of either, a list
  of text layers as one."
  [a b]
  (->> (keys (:provenance (resolve/layer [[:a a] [:b b]])))
       (filter #(not= (get-in a %) (get-in b %)))))

(defn draft-form
  "The form of profile `name` as an unsaved draft, for a UI that saves only
  when asked (docs/ARCHITECTURE.md, \"Drafts\"). `settings` are the profile's own
  settings as edited so far (they replace the saved ones; they are not
  overrides on top), and `edit`, when given, is one more form edit
  (watermark.core.form/edit) applied to them first. Nothing is saved.
  Rows whose value differs from the saved profile's say so (source
  :unsaved). Returns what settings-form does, plus :settings (the draft
  after the edit, validated) and :unsaved? (whether it differs from the
  saved profile). A UI saves the draft with save-profile! and the revision
  it read, or as a new profile with create-profile!."
  [sys ctx name {:keys [settings edit]}]
  (let [doc      (get-profile sys ctx name)
        layered  (fn [draft] (resolve/layer [[:defaults schema/defaults] [:profile draft]]))
        draft    (schema/validate! (or settings {}))
        draft    (if edit
                   (schema/validate! (form/edit draft (schema/validate! (:settings (layered draft))) edit))
                   draft)
        {:keys [settings provenance]} (layered draft)
        effective (schema/validate! settings)
        changed  (changed-paths (:settings doc) draft)
        r        {:settings   effective
                  :provenance (reduce #(assoc %1 %2 :overrides) provenance changed)
                  :base       {:kind (if (:profile/auto? doc) :latest :named) :name (:profile/name doc)}}
        locked   (->> (features/required-features effective)
                      (remove #(features/entitled? (:entitlements sys) %))
                      sort vec)]
    {:profile  (select-keys doc [:profile/name :profile/slug :profile/rev :profile/auto?])
     :form     (form/model r {:entitled? (entitled-fn sys)})
     :locked   locked
     :warnings []
     :settings draft
     :unsaved? (boolean (seq changed))}))

;; ---------------------------------------------------------------------------
;; Preview: one frame through the real pipeline (docs/adr/0011, section 5)

(def sample-aspects
  "Sample clips shown before a video is chosen, by aspect: the common
  delivery shapes (landscape, vertical, square, 4:3 and its portrait,
  portrait social 4:5, cinema 21:9). Even sizes, as yuv420p needs."
  {"16:9" {:width 1280 :height 720}
   "9:16" {:width 720 :height 1280}
   "1:1"  {:width 1080 :height 1080}
   "4:3"  {:width 960 :height 720}
   "3:4"  {:width 720 :height 960}
   "4:5"  {:width 864 :height 1080}
   "21:9" {:width 1680 :height 720}})

(def ^:private sample-seconds 12)

(def ^:private ^bytes sample-secret
  "The demonstration key for keyed layers on the sample clip. The sample isn't
  anyone's evidence, and the studio's own key stays out of it."
  (.getBytes "wmark sample preview: not a studio key" StandardCharsets/UTF_8))

(defn- preview-dir ^File [sys]
  (or (some-> (:preview-dir sys) str io/file)
      (throw (ex-info "Previews aren't set up in this deployment." {:wmark/error :unsupported}))))

(defn- sample-clip!
  "The sample clip for `aspect`, made once by the engine."
  [sys ^File dir aspect]
  (let [{:keys [width height]} (or (sample-aspects aspect) (sample-aspects "16:9"))
        f (io/file dir (str "sample-" width "x" height ".mp4"))]
    (when-not (.isFile f)
      (when-not (satisfies? engine/SampleSource (:engine sys))
        (throw (ex-info "This engine can't make a sample clip; choose a video to preview on."
                        {:wmark/error :unsupported})))
      (.mkdirs dir)
      (engine/sample-video (:engine sys) {:width width :height height :fps 25 :seconds sample-seconds} (str f)))
    (str f)))

(def ^:private preview-id #"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

(defn- prune-previews!
  "Keep the newest `keep-n` previews."
  [^File dir keep-n]
  (let [pngs (->> (.listFiles dir) (filter #(re-matches #".*\.png" (.getName ^File %)))
                  (sort-by #(.lastModified ^File %) >))]
    (doseq [^File f (drop keep-n pngs)] (.delete f))))

(defn preview-frame
  "One frame of what a run would produce: the settings of `profile` (plus
  unsaved `settings`) drawn on the frame at `t` seconds of `source`, a video
  path, or of the sample clip for `aspect` (a key of sample-aspects).
  It is the render's own plan cut to one frame, so the preview is the output.
  Returns {:id :frame :t :width :height :duration-s :sample? :aspect :notes};
  the PNG is
  `preview-file`. Pro layers the plan doesn't cover are left out and named in
  :notes; on the sample clip, keyed layers use a demonstration key."
  [sys ctx {:keys [profile settings source t aspect]}]
  (let [dir     (preview-dir sys)
        caps    (get-in (engine/info (:engine sys)) [:capabilities :preview] #{})
        _       (when-not (contains? caps :frame)
                  (throw (ex-info "This engine can't draw previews." {:wmark/error :unsupported})))
        r       (resolve-settings sys ctx {:profile profile :settings settings})
        locked  (set (:locked r))
        shown?  #(not (locked (keyword "text.mode" (name (features/canonical-mode (:mode %))))))
        s       (update (:settings r) :texts #(vec (filter shown? %)))
        sample? (str/blank? (str source))
        aspect  (if (sample-aspects aspect) aspect "16:9")
        input   (if sample? (sample-clip! sys dir aspect) (str source))
        env     (cond-> sys sample? (assoc :secret-for (constantly sample-secret)))
        id      (str (UUID/randomUUID))
        out     (io/file dir (str id ".png"))
        _       (.mkdirs dir)
        planned (jobs/plan-frame env ctx s input {:t (or t 0.0) :out out})]
    (try
      (let [outcome (deref (engine/outcome (engine/execute! (:engine sys) (:plan planned) nil)))]
        (when-not (and (= :done (:status outcome)) (.isFile out))
          (throw (ex-info (str "The preview failed. " (get-in outcome [:error :message]))
                          {:wmark/error :unavailable})))
        (prune-previews! dir 24)
        (let [{:keys [fps-num fps-den width height]} (:media planned)
              keyed? (some #(#{:subliminal :random} (features/canonical-mode (:mode %))) (:texts s))]
          {:id      id
           :frame   (:frame planned)
           :t       (/ (* (double (:frame planned)) fps-den) fps-num)
           :width   width
           :height  height
           :duration-s (:duration-s (:media planned))
           :sample? sample?
           :aspect  (when sample? aspect)
           :notes   (cond-> []
                      (seq locked) (conj (str "Not shown, because they need wmark Pro: "
                                              (str/join ", " (map #(get-in features/catalog [% :title] (str %)) (sort locked)))
                                              "."))
                      (and sample? keyed?) (conj "Keyed layers use a sample key on the sample clip; each real video gets its own times."))}))
      (finally (jobs/release! sys planned)))))

(defn preview-file
  "The PNG of preview `id`, or :not-found."
  ^File [sys _ctx id]
  (let [f (when (re-matches preview-id (str id)) (io/file (preview-dir sys) (str id ".png")))]
    (if (and f (.isFile ^File f))
      f
      (throw (ex-info "No such preview." {:wmark/error :not-found})))))

(defn- prepare!
  "Everything that must hold before any encoding starts."
  [sys ctx {:keys [inputs] :as req}]
  (when (empty? inputs)
    (throw (ex-info "No input files." {:wmark/error :invalid :field :inputs})))
  (let [r (resolve-settings sys ctx req)]
    (features/check! (:entitlements sys) (:settings r))
    r))

(defn- cover-of
  "A request's cover ({:t seconds}): the render's frame at t becomes each
  copy's cover picture (its thumbnail in file browsers). Per run, never
  saved in a profile or in `latest`: it belongs to the video, not the
  settings."
  [{:keys [cover]}]
  (when (some? cover)
    (let [t (:t cover)]
      (when-not (and (number? t) (<= 0.0 (double t) 86400.0))
        (throw (ex-info "The cover's time must be a number of seconds from 0." {:wmark/error :invalid :field :cover})))
      {:t (double t)})))

(defn- derived-from [r]
  (when (= :named (get-in r [:base :kind])) (get-in r [:base :name])))

;; ---------------------------------------------------------------------------
;; Planning and rendering

(defn plan-batch
  "Dry run: resolution plus, per input, the render spec and the engine's
  plan (for FFmpeg: the exact argv and filtergraph). No output is written
  (a v2 plan's bitmaps are drawn, then deleted), and `latest` is left alone
  -- a dry run is not an execution."
  [sys ctx req]
  (let [r (prepare! sys ctx req)]
    (assoc r :plans (mapv (fn [input]
                            (let [p (jobs/plan-input sys (assoc ctx :dry-run? true) (:settings r) input
                                                     {:cover (cover-of req)})]
                              (jobs/release! sys p)          ; a dry run keeps no bitmaps
                              {:input  (:input p)
                               :output (get-in p [:output :final])
                               :spec   (:spec p)
                               :engine (select-keys (:plan p) [:engine :argv :graph])}))
                          (:inputs req)))))

(defn run-batch!
  "Synchronous batch (the CLI). Records the resolved settings as `latest`
  before encoding -- \"every execution\" -- so a crashed batch can be re-run
  with identical parameters."
  [sys ctx req opts]
  (let [r     (prepare! sys ctx req)
        cover (cover-of req)]
    (config/record-latest! (store sys ctx) (:settings r) (derived-from r))
    (assoc r :results (jobs/run-job! sys (cond-> {:ctx ctx :settings (:settings r) :inputs (:inputs req)}
                                           cover (assoc :cover cover))
                                     opts))))

(defn submit-job!
  "Validate synchronously (so the client gets 4xx right away), record
  `latest`, then queue."
  [sys ctx req]
  (let [r     (prepare! sys ctx req)
        cover (cover-of req)]
    (config/record-latest! (store sys ctx) (:settings r) (derived-from r))
    (jobs/submit! (:jobs sys) (cond-> {:ctx ctx :inputs (mapv str (:inputs req)) :settings (:settings r)}
                                cover (assoc :cover cover)))))

(defn- own?
  "Jobs belong to the tenant that submitted them. A hosted queue is shared,
  so every read path filters: one studio never sees another's jobs."
  [ctx job]
  (= (:tenant ctx) (get-in job [:ctx :tenant] (:tenant ctx))))

(defn list-jobs [sys ctx] (filterv #(own? ctx %) (jobs/list-jobs (:jobs sys))))

(defn cancel-job! [sys ctx id]
  (if (some #(= id (:id %)) (list-jobs sys ctx))
    (jobs/cancel! (:jobs sys) id)
    (throw (ex-info (str "No job " id) {:wmark/error :not-found}))))

(defn subscribe-jobs!
  "Call (f event) for each event about the caller's jobs, until
  `unsubscribe-jobs!` with the same key. What UIs stream from."
  [sys ctx k f]
  (jobs/subscribe! (:jobs sys) k (fn [event] (when (own? ctx (:job event)) (f event)))))

(defn unsubscribe-jobs! [sys _ctx k] (jobs/unsubscribe! (:jobs sys) k))
