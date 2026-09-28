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

  Design notes
  * Every rule here runs on the watermark.store port, so the same code serves
    local files (desktop), memory (tests, workers) and PostgreSQL (the
    serverless backend). Storage never validates settings -- the domain
    schema is the caller's business.
  * Every document carries :profile/rev and every write is compare-and-set
    against the revision that was read, so concurrent editors (two tabs, two
    serverless instances) get a clean :conflict instead of a lost update.
    Callers may pass :if-rev to insist on the revision *they* loaded.
  * GraalVM native-image initialises Clojure namespaces at *build* time, so
    nothing environment-dependent (home dir, env vars, clock, RNG) may sit in
    a top-level def -- it would be frozen into the binary from the build
    machine. Everything environment-dependent here is a function."
  (:require [clojure.string :as str]
            [watermark.core.resolve :as resolve]
            [watermark.store :as store]
            [watermark.store.file :as file-store])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.file Files LinkOption Path Paths)
           (java.text Normalizer Normalizer$Form)
           (java.time Instant)
           (java.util Locale)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Constants (pure data -- safe to initialise at image build time)

(def format-version store/format-version)

(def latest-slug "latest")

(def transient-keys
  "Top-level keys that describe one run, never a reusable preference. They are
  stripped before anything is persisted, so `latest` can't make the next run
  silently re-process the previous batch or reuse its random seed."
  #{:inputs :job})

(def ^:private max-slug-length 64)
(def ^:private max-name-length 80)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-links
  (make-array LinkOption 0))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- error!
  "All failures carry :wmark/error, which the HTTP layer maps to a status code
  (:not-found 404, :conflict 409, :invalid 422)."
  [kind msg data]
  (throw (ex-info msg (assoc data :wmark/error kind))))

(defn- not-found! [name]
  (error! :not-found (format "No profile named \"%s\"." name) {:name name}))

(defn- lower
  "Locale-independent lower-casing (the default locale would turn \"I\" into a
  dotless i on Turkish systems)."
  ^String [^String s]
  (.toLowerCase s Locale/ROOT))

(defn- path ^Path [^String p & more]
  (Paths/get p (into-array String more)))

(defn- env ^String [^String k] (System/getenv k))

(defn- now-str [] (str (Instant/now)))

;; ---------------------------------------------------------------------------
;; Where configuration lives (evaluated at run time, never at build time)

(defn- os-family []
  (let [os (lower (System/getProperty "os.name" ""))]
    (cond (str/starts-with? os "windows") :windows
          (str/starts-with? os "mac")     :macos
          :else                           :unix)))

(defn default-home
  "Per-user config directory following each platform's convention. Only plain
  environment and system-property reads -- no OS-specific APIs."
  ^Path []
  (let [home (System/getProperty "user.home")]
    (case (os-family)
      :windows (path (or (not-empty (env "APPDATA"))
                         (str home "\\AppData\\Roaming"))
                     "wmark")
      :macos   (path home "Library" "Application Support" "wmark")
      (path (or (not-empty (env "XDG_CONFIG_HOME")) (str home "/.config"))
            "wmark"))))

(defn resolve-home
  "Config home, first match wins:
     1. explicit `:home` (the --home CLI flag)
     2. the WMARK_HOME environment variable
     3. `./wmark-data` when that directory exists: portable mode, for
        unzip-and-run installs. Explorer starts a double-clicked .exe with the
        exe's folder as working directory, so this also works on Windows.
     4. the OS default."
  ^Path [{:keys [home]}]
  (let [portable (path (System/getProperty "user.dir") "wmark-data")
        ^Path chosen (cond
                       home                                  (path (str home))
                       (not-empty (env "WMARK_HOME"))        (path (env "WMARK_HOME"))
                       (Files/isDirectory portable no-links) portable
                       :else                                 (default-home))]
    (.toAbsolutePath chosen)))

;; ---------------------------------------------------------------------------
;; Names and slugs

(def ^:private windows-reserved
  (into #{"con" "prn" "aux" "nul"}
        (for [p ["com" "lpt"] i (range 1 10)] (str p i))))

(defn- code-point-count ^long [^String s] (.codePointCount s 0 (.length s)))

(defn- truncate-code-points ^String [^String s ^long n]
  (if (<= (code-point-count s) n)
    s
    (subs s 0 (.offsetByCodePoints s 0 (int n)))))

(defn normalize-name
  "Canonical display name: Unicode NFC, trimmed, inner whitespace collapsed."
  ^String [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFC)
      str/trim
      (str/replace #"\s+" " ")))

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
  ^String [s]
  (let [base (-> (normalize-name s)
                 (Normalizer/normalize Normalizer$Form/NFKC) ; fullwidth, ¹ -> 1
                 lower
                 (str/replace #"[^\p{L}\p{M}\p{N}]+" "-")
                 (str/replace #"^-+|-+$" "")
                 (truncate-code-points max-slug-length)
                 (str/replace #"-+$" ""))]
    (cond
      (str/blank? base)
      (error! :invalid "A profile name needs at least one letter or digit."
              {:name s})

      (windows-reserved base) (str "_" base)
      :else base)))

(defn- check-name!
  "Validated, normalised display name."
  ^String [s]
  (let [n (normalize-name s)]
    (cond
      (str/blank? n)
      (error! :invalid "Profile name must not be blank." {:name s})

      (> (code-point-count n) max-name-length)
      (error! :invalid (format "Profile names are limited to %d characters."
                               max-name-length)
              {:name s})

      (re-find #"\p{Cc}" n)
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
;; Stores

(defn file-store
  "Local store under `<home>/profiles/`; see `resolve-home` for `opts`."
  ([] (file-store {}))
  ([opts] (file-store/file-store (.resolve (resolve-home opts) "profiles"))))

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
                   (format "A profile named \"%s\" already exists." (:profile/name existing))
                   {:name display :existing (:profile/name existing) :slug s}))
         (when (and existing
                    (not overwrite?)
                    (not= (lower (:profile/name existing)) (lower display)))
           (error! :conflict
                   (format "\"%s\" and the existing profile \"%s\" share a file name. Pick another name or overwrite explicitly."
                           display (:profile/name existing))
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
                     (format "A profile named \"%s\" already exists." (:profile/name taken))
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
        (let [existing (try (store/-read tx latest-slug) (catch ExceptionInfo _ nil))
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
    (try
      (if-let [doc (store/-read store latest-slug)]
        [:latest doc []]
        [:none nil []])
      (catch ExceptionInfo e
        [:none nil [(str "Ignored an unreadable latest profile: " (ex-message e))]]))))

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
