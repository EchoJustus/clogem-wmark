;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.jobs.local
  "In-process JobQueue: a bounded worker pool, job state, and events for the
  UI (served as SSE by the desktop server).

  The executor is created by `local-queue` at run time: a thread pool in a
  top-level def would be initialised during the native-image build."
  (:require [watermark.core.jobs :as jobs]
            [watermark.engine :as engine]
            [watermark.util.task :as task])
  (:import (java.time Instant)
           (java.util UUID)
           (java.util.concurrent ExecutorService Executors ThreadFactory)))

(set! *warn-on-reflection* true)

(defn- emit! [listeners event]
  (doseq [f (vals @listeners)]
    (try (f event) (catch Exception _ nil))))

(defn- public [job] (dissoc job :cancel? :handle))

(defrecord LocalQueue [env ^ExecutorService pool jobs listeners]
  jobs/JobQueue
  (submit! [_ job]
    (let [id      (str (UUID/randomUUID))
          cancel? (atom false)
          handle  (atom nil)
          job     (assoc job :id id :state :queued :cancel? cancel? :handle handle
                         :created-at (str (Instant/now)))
          update! (fn [f & args]
                    (let [j (get (apply swap! jobs update id f args) id)]
                      (emit! listeners {:type :job :job (public j)})
                      j))]
      (swap! jobs assoc id job)
      (emit! listeners {:type :job :job (public job)})
      (.execute pool
                (fn []
                  (if @cancel?
                    (update! assoc :state :cancelled)
                    (try
                      (update! assoc :state :running)
                      (let [results (task/await (jobs/run-job! env job
                                                   {:cancelled? #(deref cancel?)
                                                    :on-handle  #(reset! handle %)
                                                    :on-event   (fn [e]
                                                                  (when (= :progress (:type e))
                                                                    (update! assoc :progress e)))}))]
                        (update! assoc
                                 :state (if @cancel? :cancelled :done)
                                 :result results))
                      (catch Throwable e
                        (update! assoc :state :failed
                                 :error {:message (ex-message e) :kind (:wmark/error (ex-data e))}))))))
      (public job)))

  (cancel! [_ id]
    (if-let [job (get @jobs id)]
      (do (reset! (:cancel? job) true)
          (some-> @(:handle job) engine/cancel!)
          true)
      (throw (ex-info (str "No job " id) {:wmark/error :not-found}))))

  (list-jobs [_]
    (->> (vals @jobs) (map public) (sort-by :created-at) vec))

  (subscribe! [_ k f] (swap! listeners assoc k f) k)
  (unsubscribe! [_ k] (swap! listeners dissoc k))
  (shutdown! [_] (.shutdownNow pool)))

(defn local-queue
  "Queue running `concurrency` jobs at a time against `env` (see watermark.core.jobs)."
  [env {:keys [concurrency] :or {concurrency 1}}]
  (let [n (atom 0)]
    (->LocalQueue env
                  (Executors/newFixedThreadPool
                   (int concurrency)
                   (reify ThreadFactory
                     (newThread [_ r]
                       (doto (Thread. r (str "wmark-job-" (swap! n inc)))
                         (.setDaemon true)))))
                  (atom {})
                  (atom {}))))
