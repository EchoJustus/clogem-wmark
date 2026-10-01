;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.features
  "Feature catalog and the entitlement gate.

  Gating is enforced here, in the core, when a request is planned -- never
  only in a UI. Two independent locks protect Pro features:

    1. Code absence: the community binary is compiled without the Pro source
       tree, so Pro text modes have no implementation to run at all
       (see watermark.core.modes, :default method).
    2. Entitlement: the Pro binary contains the implementations but asks an
       `Entitlements` provider before using them -- an offline-verified
       license locally, the account's plan in the SaaS deployment. Both
       deployments share this protocol, so the gate code is identical."
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(def catalog
  "Every gateable capability. Keep ids stable: they appear in license payloads.

  A text mode's id is :text.mode/<wire id>. :display-name is what users type
  and see for a mode whose wire id reads differently: the canary mode's wire
  id stays `subliminal` because it is part of the keyed seed (see
  `mode-aliases`), but wmark never shows that word (docs/adr/0005)."
  {:logo/static          {:tier :community :title "Static logo"}
   :logo/flip            {:tier :community :title "Periodic logo flip"}
   :text.mode/continuous {:tier :community :title "Continuous text"}
   :text.mode/scheduled  {:tier :community :title "Scheduled text"}
   :text.mode/subliminal {:tier :pro       :title "Flash-frame canaries" :display-name "canary"}
   :text.mode/random     {:tier :pro       :title "Randomized text"}
   :jobs/parallel        {:tier :pro       :title "Parallel encodes"}})

;; ---------------------------------------------------------------------------
;; Text-mode names: the wire id travels, the display name is shown

(def mode-display-names
  "Wire id -> display name, for the text modes that have one."
  (into {} (for [[id {:keys [display-name]}] catalog
                 :when (and display-name (= "text.mode" (namespace id)))]
             [(keyword (name id)) display-name])))

(def mode-aliases
  "Display name -> wire id. Settings, profiles, the REST API and render specs
  carry the wire id, and watermark.core.seeds hashes it into every keyed
  schedule, so it never changes: renaming it would move every existing
  schedule. Users type the display name instead, and it resolves here."
  (into {} (for [[wire shown] mode-display-names] [(keyword shown) wire])))

(defn canonical-mode
  "The wire id of a mode as typed (keyword or string); aliases resolve."
  [mode]
  (let [k (if (string? mode) (keyword mode) mode)]
    (get mode-aliases k k)))

(defn mode-display-name
  "The name users see for a mode, given its wire id or an alias."
  [mode]
  (let [k (canonical-mode mode)]
    (get mode-display-names k (if (keyword? k) (name k) (str k)))))

(defn- update-modes [settings f]
  (let [texts (:texts settings)]
    (if (sequential? texts)
      (assoc settings :texts (mapv #(if (and (map? %) (contains? % :mode)) (update % :mode f) %) texts))
      settings)))

(defn canonical-settings
  "Settings whose text layers name their modes by wire id. Every entry point
  applies this before validation, planning or seeding."
  [settings]
  (update-modes settings canonical-mode))

(defn display-settings
  "Settings as users read them: text modes by their display names."
  [settings]
  (update-modes settings (comp keyword mode-display-name)))

(defn tier [feature-id] (get-in catalog [feature-id :tier]))

(defprotocol Entitlements
  (plan      [this]            "Active plan keyword, e.g. :community or :pro.")
  (entitled? [this feature-id] "May `feature-id` be used right now?")
  (describe  [this]            "Data for the API/UI: plan, status, expiry, licensee."))

(defrecord CommunityEntitlements []
  Entitlements
  (plan [_] :community)
  (entitled? [_ f] (= :community (tier f)))
  (describe [_] {:plan :community :status :community}))

(defn community [] (->CommunityEntitlements))

(defn required-features
  "Feature ids a settings map would use."
  [settings]
  (let [logo (:logo settings)]
    (cond-> #{}
      (and (:enabled logo true) (:path logo))                 (conj :logo/static)
      (= :flip-y (get-in logo [:animation :type]))            (conj :logo/flip)
      true (into (map #(keyword "text.mode" (name (canonical-mode (:mode %))))) (:texts settings)))))

(defn check!
  "Throw :feature-locked (HTTP 402) if `settings` need anything not entitled."
  [ent settings]
  (let [locked (->> (required-features settings) (remove #(entitled? ent %)) sort vec)]
    (when (seq locked)
      (throw (ex-info (str "Not available on the " (name (plan ent)) " plan: "
                           (->> locked (map #(get-in catalog [% :title] (str %)))
                                (str/join ", ")))
                      {:wmark/error :feature-locked
                       :features    locked
                       :plan        (plan ent)})))
    settings))

(defn assert!
  "Defence in depth for Pro implementations: refuse to run unentitled, even if
  a caller skipped `check!`."
  [ent feature-id]
  (when-not (and ent (entitled? ent feature-id))
    (throw (ex-info (str (get-in catalog [feature-id :title] (str feature-id))
                         " is not included in the current plan.")
                    {:wmark/error :feature-locked :features [feature-id]}))))

;; ---------------------------------------------------------------------------
;; Render allowance: how many renders may still run

(defprotocol RenderAllowance
  "An optional port beside Entitlements, in the system map as :allowance:
  how many renders may still run (a trial, a plan's quota). Entitlements
  say which features a render may use; an allowance says whether it may
  run at all. Without one, every render may run. The core checks it when a
  job is submitted, and again as each render starts, so no client can go
  around it (docs/adr/0017)."
  (renders-left    [this ctx]       "Renders still allowed for ctx, the running ones taken off; nil when unlimited.")
  (reserve-render! [this ctx]       "Take one render's turn as it starts: truthy when one was left.")
  (settle-render!  [this ctx done?] "A reserved render ended: done? uses its turn up, else it comes back."))

(defn left
  "Renders `allowance` still allows for ctx; nil when there is no limit."
  [allowance ctx]
  (when allowance (renders-left allowance ctx)))

(defn render-limit
  "The error for a render the allowance has no turn left for (HTTP 402)."
  []
  (ex-info "No renders left: every render this plan allows is used."
           {:wmark/error :render-limit :renders-left 0}))

(defn check-renders!
  "Throw :render-limit when `allowance` has no render left for ctx: what
  submitting a job checks, before anything is queued."
  [allowance ctx]
  (let [n (left allowance ctx)]
    (when (and (some? n) (not (pos? n)))
      (throw (render-limit)))))

(defn report
  "Catalog annotated with the current entitlement, for UIs to render locks."
  [ent]
  {:entitlements (describe ent)
   :features     (vec (for [[id m] (sort-by key catalog)]
                        (assoc m :id id :entitled (entitled? ent id))))})
