;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.api
  "The Core API -- the one contract every presentation layer uses.

  HTTP routes, the CLI, the TUI (through HTTP) and future GUIs call these
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
  (:require [watermark.config :as config]
            [watermark.core.features :as features]
            [watermark.core.jobs :as jobs]
            [watermark.core.schema :as schema]
            [watermark.engine :as engine]))

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

(defn- prepare!
  "Everything that must hold before any encoding starts."
  [sys ctx {:keys [inputs] :as req}]
  (when (empty? inputs)
    (throw (ex-info "No input files." {:wmark/error :invalid :field :inputs})))
  (let [r (resolve-settings sys ctx req)]
    (features/check! (:entitlements sys) (:settings r))
    r))

(defn- derived-from [r]
  (when (= :named (get-in r [:base :kind])) (get-in r [:base :name])))

;; ---------------------------------------------------------------------------
;; Planning and rendering

(defn plan-batch
  "Dry run: resolution plus, per input, the render spec and the engine's
  plan (for FFmpeg: the exact argv and filtergraph). Nothing is written, and
  `latest` is left alone -- a dry run is not an execution."
  [sys ctx req]
  (let [r (prepare! sys ctx req)]
    (assoc r :plans (mapv (fn [input]
                            (let [p (jobs/plan-input sys (assoc ctx :dry-run? true) (:settings r) input)]
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
  (let [r (prepare! sys ctx req)]
    (config/record-latest! (store sys ctx) (:settings r) (derived-from r))
    (assoc r :results (jobs/run-job! sys {:ctx ctx :settings (:settings r) :inputs (:inputs req)} opts))))

(defn submit-job!
  "Validate synchronously (so the client gets 4xx right away), record
  `latest`, then queue."
  [sys ctx req]
  (let [r (prepare! sys ctx req)]
    (config/record-latest! (store sys ctx) (:settings r) (derived-from r))
    (jobs/submit! (:jobs sys) {:ctx ctx :inputs (mapv str (:inputs req)) :settings (:settings r)})))

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
