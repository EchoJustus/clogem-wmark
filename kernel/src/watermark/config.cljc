;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.config
  "Configuration profiles: CRUD, the auto-saved `latest` profile, and the
  fallback resolution every entry point (CLI, REST, web UI, GUI) goes through.

  Resolution, lowest to highest precedence:

      built-in defaults  <  base profile  <  explicit overrides

  The base profile is
    * the profile the caller names (unknown name => :not-found; an explicit
      request never silently falls back to something else),
    * otherwise `latest`, when one exists (the auto-fallback),
    * otherwise nothing.

  Part of the core library (docs/adr/0013): the same rules run on the JVM
  and the Dart VM. Where configuration lives on disk is the host's
  business (watermark.home on the JVM).

  Design notes
  * Every rule here runs on the watermark.store port, so the same code serves
    local files (desktop), memory (tests, workers) and PostgreSQL (the
    serverless backend). Storage never validates settings -- the domain
    schema is the caller's business.
  * Every document carries :profile/rev and every write is compare-and-set
    against the revision that was read, so concurrent editors (two tabs, two
    serverless instances) get a clean :conflict instead of a lost update.
    Callers may pass :if-rev to insist on the revision *they* loaded.
  * Text rules (normal forms, lowercase, whitespace, letters) come from the
    library's own Unicode tables (watermark.util.text), so a profile's file
    name is the same on every host. Times come from watermark.util.host's
    clock."
  (:require [clojure.string :as str]
            [watermark.core.resolve :as resolve]
            [watermark.store :as store]
            [watermark.util.chars :as chars]
            [watermark.util.host :as host]
            [watermark.util.text :as text]
            [watermark.util.time :as time]
            [watermark.util.unicode :as unicode]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Constants

(def format-version store/format-version)

(def latest-slug "latest")

(def transient-keys
  "Top-level keys that describe one run, never a reusable preference. They are
  stripped before anything is persisted, so `latest` can't make the next run
  silently re-process the previous batch or reuse its random seed."
  #{:inputs :job})

(def ^:private max-slug-length 64)
(def ^:private max-name-length 80)

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- error!
  "All failures carry :wmark/error, which the HTTP layer maps to a status code
  (:not-found 404, :conflict 409, :invalid 422)."
  [kind msg data]
  (throw (ex-info msg (assoc data :wmark/error kind))))

(defn- not-found! [name]
  (error! :not-found (str "No profile named \"" name "\".") {:name name}))

(defn- lower
  "Locale-independent lowercasing (a Turkish locale would turn \"I\" into a
  dotless i)."
  [s]
  (text/lower s))

(defn- now-str [] (time/now))

;; ---------------------------------------------------------------------------
;; Names and slugs

(def ^:private windows-reserved
  (into #{"con" "prn" "aux" "nul"}
        (for [p ["com" "lpt"] i (range 1 10)] (str p i))))

(defn normalize-name
  "Canonical display name: Unicode NFC, trimmed, inner whitespace collapsed."
  [s]
  (-> (text/nfc (str s))
      text/trim
      text/collapse-spaces))

(defn slug
  "Filesystem-safe, case-folded identity of a profile name.

  Profile names are user-facing (\"16:9 Video Profile\"); file names have to
  survive Windows, macOS and Linux. The colon in that very example is illegal
  on Windows, and NTFS/APFS are case-insensitive, so names that differ only in
  case or punctuation deliberately map to the same file -- and then collide
  loudly (see `save-profile!`) instead of overwriting each other.

    \"16:9 Video Profile\" -> \"16-9-video-profile\"
    \"CON\"                -> \"_con\"       (reserved device name on Windows)
    \"横屏 16:9\"           -> \"横屏-16-9\"   (letters of any script survive)"
  [s]
  (let [base (-> (normalize-name s)
                 text/nfkc ; fullwidth, ¹ -> 1
                 lower
                 (text/replace-runs (complement unicode/letter-mark-number?) "-")
                 (str/replace #"^-+|-+$" "")
                 (text/take-code-points max-slug-length)
                 (str/replace #"-+$" ""))]
    (cond
      (text/blank? base)
      (error! :invalid "A profile name needs at least one letter or digit."
              {:name s})

      (windows-reserved base) (str "_" base)
      :else base)))

(defn- check-name!
  "Validated, normalised display name."
  [s]
  (let [n (normalize-name s)]
    (cond
      (text/blank? n)
      (error! :invalid "Profile name must not be blank." {:name s})

      (> (text/code-point-count n) max-name-length)
      (error! :invalid (str "Profile names are limited to " max-name-length " characters.")
              {:name s})

      (some unicode/control? (chars/code-points n))
      (error! :invalid "Profile name contains control characters." {:name s})

      :else n)))

(defn- check-user-slug!
  "`latest` belongs to the auto-save; users can't create or rename onto it."
  [s display]
  (when (= s latest-slug)
    (error! :invalid
            "\"latest\" is reserved for the auto-saved profile. Save it under another name."
            {:name display}))
  s)

;; ---------------------------------------------------------------------------
;; Settings

(defn- persistable [settings]
  (when-not (or (nil? settings) (map? settings))
    (error! :invalid "Settings must be a map." {:settings settings}))
  (apply dissoc (resolve/prune-nils (or settings {})) transient-keys))

(def deep-merge
  "Right-biased recursive merge (see watermark.core.resolve)."
  resolve/deep-merge)

;; ---------------------------------------------------------------------------
;; CRUD

(defn- summary [slug doc]
  (cond-> {:name       (:profile/name doc)
           :slug       slug
           :rev        (store/rev-of doc)
           :auto?      (boolean (:profile/auto? doc))
           :updated-at (:profile/updated-at doc)}
    (:profile/derived-from doc) (assoc :derived-from (:profile/derived-from doc))))

(defn list-profiles
  "Summaries of every stored profile: `latest` first, then by name. A damaged
  document shows up with an :error entry instead of breaking the whole list."
  [store]
  (->> (store/-read-all store)
       (keep (fn [[slug doc]]
               (cond (:error doc) {:name slug :slug slug :error (:error doc)}
                     doc          (summary slug doc))))
       (sort-by (juxt #(if (= latest-slug (:slug %)) 0 1)
                      #(lower (str (:name %)))))
       vec))

(defn get-profile
  "Profile by display name or slug -- case- and punctuation-insensitive -- or
  nil. \"latest\" returns the auto-saved profile."
  [store name]
  (let [s (slug name)]
    (some-> (store/-read store s) (assoc :profile/slug s))))

(defn get-profile! [store name]
  (or (get-profile store name) (not-found! name)))

(defn- check-if-rev! [slug existing if-rev]
  (when (and if-rev (not= if-rev (store/rev-of existing)))
    (store/conflict! slug "This profile changed since it was loaded. Reload it to see the latest version."
                     {:reason :stale :expected if-rev :actual (store/rev-of existing)})))

(defn- save* [store name settings {:keys [create? overwrite? if-rev]}]
  (let [given (check-name! name)
        s     (check-user-slug! (slug given) given)
        data  (persistable settings)]
    (store/-transact
     store
     (fn [tx]
       (let [existing (store/-read tx s)
             ;; addressing a profile by its slug (PUT /profiles/16-9-video-profile)
             ;; updates it without renaming it
             display  (if (and existing (= given s)) (:profile/name existing) given)]
         (when (and existing create?)
           (error! :conflict
                   (str "A profile named \"" (:profile/name existing) "\" already exists.")
                   {:name display :existing (:profile/name existing) :slug s}))
         (when (and existing
                    (not overwrite?)
                    (not= (lower (:profile/name existing)) (lower display)))
           (error! :conflict
                   (str "\"" display "\" and the existing profile \"" (:profile/name existing)
                        "\" share a file name. Pick another name or overwrite explicitly.")
                   {:name display :existing (:profile/name existing) :slug s}))
         (check-if-rev! s existing if-rev)
         (let [now (now-str)
               doc {:wmark/format       format-version
                    :profile/name       display
                    :profile/rev        (inc (store/rev-of existing))
                    :profile/created-at (or (:profile/created-at existing) now)
                    :profile/updated-at now
                    :settings           data}]
           (store/-put! tx s doc (when existing (store/rev-of existing)))
           (assoc doc :profile/slug s)))))))

(defn create-profile!
  "New profile; :conflict if the name (or an alias of it) is taken."
  [store name settings]
  (save* store name settings {:create? true}))

(defn save-profile!
  "Create or replace. Replacing requires the same display name up to case;
  a *different* name that happens to map to the same file (\"16:9 Video\" vs
  \"16-9 video\") is a :conflict unless `:overwrite? true`. `:if-rev n` fails
  with :conflict when the stored revision is no longer n."
  ([store name settings] (save* store name settings {}))
  ([store name settings {:keys [overwrite? if-rev]}]
   (save* store name settings {:overwrite? overwrite? :if-rev if-rev})))

(defn rename-profile!
  "Rename `from` to `to`. When both map to the same file (fixing capitalisation)
  only the display name changes. `latest` can't be renamed -- copy it."
  [store from to]
  (let [src     (slug from)
        display (check-name! to)
        dst     (check-user-slug! (slug display) display)]
    (when (= src latest-slug)
      (error! :invalid
              "The auto-saved \"latest\" profile can't be renamed; copy it to a new name instead."
              {:name from}))
    (store/-transact
     store
     (fn [tx]
       (let [doc (or (store/-read tx src) (not-found! from))]
         (when (not= src dst)
           (when-let [taken (store/-read tx dst)]
             (error! :conflict
                     (str "A profile named \"" (:profile/name taken) "\" already exists.")
                     {:name display :existing (:profile/name taken)})))
         (let [renamed (assoc doc
                              :profile/name display
                              :profile/rev (inc (store/rev-of doc))
                              :profile/updated-at (now-str))]
           (if (= src dst)
             (store/-put! tx dst renamed (store/rev-of doc))
             (do (store/-put! tx dst renamed nil)             ; new name first: a crash
                 (store/-delete! tx src (store/rev-of doc)))) ; leaves a copy, never a loss
           (assoc renamed :profile/slug dst)))))))

(defn copy-profile!
  "Duplicate `from` as a new profile `to`. `from` may be \"latest\" -- this is
  how the last run's settings get promoted to a named profile."
  [store from to]
  (create-profile! store to (:settings (get-profile! store from))))

(defn delete-profile!
  "Delete by name. Deleting \"latest\" is allowed: it resets the auto-fallback."
  [store name]
  (let [s (slug name)]
    (or (store/-transact store #(store/-delete! % s nil))
        (not-found! name))
    true))

(defn record-latest!
  "Auto-save the settings a run actually used as `latest`. The job pipeline
  calls this once a request is resolved and validated, *before* encoding, so a
  crashed run can be retried with identical parameters. Last writer wins.
  `derived-from` names the base profile, for display."
  ([store settings] (record-latest! store settings nil))
  ([store settings derived-from]
   (let [data (persistable settings)]
     (store/-transact
      store
      (fn [tx]
        (let [[existing] (host/attempt #(store/-read tx latest-slug))
              now      (now-str)
              doc      (cond-> {:wmark/format       format-version
                                :profile/name       latest-slug
                                :profile/auto?      true
                                :profile/rev        (inc (store/rev-of existing))
                                :profile/created-at (or (:profile/created-at existing) now)
                                :profile/updated-at now
                                :settings           data}
                         derived-from (assoc :profile/derived-from derived-from))]
          (store/-put! tx latest-slug doc ::store/any)
          (assoc doc :profile/slug latest-slug)))))))

;; ---------------------------------------------------------------------------
;; Resolution: the fallback logic

(defn- base-layer
  "[kind doc warnings] for the requested base profile."
  [store profile]
  (cond
    (= profile :none)
    [:none nil []]

    (some? profile)
    (let [doc (get-profile! store profile)]
      [(if (= latest-slug (:profile/slug doc)) :latest :named) doc []])

    :else ; auto-fallback to latest -- tolerant: a damaged latest must not block a run
    (let [[doc e] (host/attempt #(store/-read store latest-slug))]
      (cond e   [:none nil [(str "Ignored an unreadable latest profile: " (ex-message e))]]
            doc [:latest doc []]
            :else [:none nil []]))))

(defn resolve-settings
  "Effective settings for a run: `defaults` < base profile < `overrides`.

  `:profile` picks the base layer:
    nil     -> `latest` if it exists, else none (the auto-fallback)
    \"name\"  -> that profile; unknown => :not-found, never a silent fallback
    :none   -> no base layer (a clean run from defaults)

  nil values in `overrides` mean \"not given\" and fall through.

  Returns
    {:settings   merged settings
     :base       {:kind :latest|:named|:none, :name \"...\"}
     :provenance {[:logo :anchor] :profile, ...}  ; which layer won each leaf
     :warnings   [...]}"
  [store {:keys [profile overrides defaults]}]
  (let [[kind doc warnings] (base-layer store profile)
        {:keys [settings provenance]} (resolve/layer [[:defaults defaults]
                                                      [:profile (:settings doc)]
                                                      [:overrides overrides]])]
    {:settings   settings
     :base       (cond-> {:kind kind} doc (assoc :name (:profile/name doc)))
     :provenance provenance
     :warnings   warnings}))
