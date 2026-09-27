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
  "Every gateable capability. Keep ids stable: they appear in license payloads."
  {:logo/static          {:tier :community :title "Static logo"}
   :logo/flip            {:tier :community :title "Periodic logo flip"}
   :text.mode/continuous {:tier :community :title "Continuous text"}
   :text.mode/scheduled  {:tier :community :title "Scheduled text"}
   :text.mode/subliminal {:tier :pro       :title "Flash-frame canaries"}
   :text.mode/random     {:tier :pro       :title "Randomized text"}
   :jobs/parallel        {:tier :pro       :title "Parallel encodes"}})

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
      true (into (map #(keyword "text.mode" (name (:mode %)))) (:texts settings)))))

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

(defn report
  "Catalog annotated with the current entitlement, for UIs to render locks."
  [ent]
  {:entitlements (describe ent)
   :features     (vec (for [[id m] (sort-by key catalog)]
                        (assoc m :id id :entitled (entitled? ent id))))})
