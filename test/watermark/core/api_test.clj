;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.api-test
  "Core API rules that hold for every client: jobs are per tenant."
  (:require [clojure.test :refer [deftest is]]
            [watermark.core.api :as api]
            [watermark.core.jobs :as jobs]))

(set! *warn-on-reflection* true)

(defrecord ListQueue [items listeners cancelled]
  jobs/JobQueue
  (submit! [_ job] job)
  (cancel! [_ id] (swap! cancelled conj id) true)
  (list-jobs [_] items)
  (subscribe! [_ k f] (swap! listeners assoc k f) k)
  (unsubscribe! [_ k] (swap! listeners dissoc k))
  (shutdown! [_] nil))

(def acme {:tenant "acme" :user "ann"})
(def other {:tenant "other" :user "olu"})

(defn- sys []
  {:jobs (->ListQueue [{:id "a1" :ctx acme :state :running}
                       {:id "o1" :ctx other :state :queued}]
                      (atom {}) (atom []))})

(deftest jobs-belong-to-their-tenant
  (let [s (sys)]
    (is (= ["a1"] (map :id (api/list-jobs s acme))))
    (is (= ["o1"] (map :id (api/list-jobs s (assoc other :user "someone-else"))))
        "shared by the tenant's users")
    (is (true? (api/cancel-job! s acme "a1")))
    (is (= :not-found (try (api/cancel-job! s acme "o1") nil
                           (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e)))))
        "another tenant's job looks like no job at all")
    (is (= ["a1"] @(:cancelled (:jobs s))))))

(deftest subscriptions-only-see-their-tenant
  (let [s    (sys)
        seen (atom [])]
    (api/subscribe-jobs! s acme ::k #(swap! seen conj (get-in % [:job :id])))
    (doseq [f (vals @(:listeners (:jobs s)))]
      (f {:type :job :job {:id "a1" :ctx acme}})
      (f {:type :job :job {:id "o1" :ctx other}}))
    (is (= ["a1"] @seen))
    (api/unsubscribe-jobs! s acme ::k)
    (is (empty? @(:listeners (:jobs s))))))
