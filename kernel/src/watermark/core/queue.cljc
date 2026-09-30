;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.queue
  "An in-process JobQueue (watermark.core.jobs/JobQueue) on every host
  (docs/adr/0015): the desktop server's on the JVM, and an app's that runs
  the core library on the Dart VM.

  Jobs run through a function, `:run` (fn [job opts] task-of-results):
  watermark.core.jobs/run-job! for a real queue (`local-queue`), a stand-in
  in the contract (watermark.queue-contract). Rendering is asynchronous
  (the pipeline returns tasks), so the queue holds no thread while a job
  renders: it counts the jobs running, and when one ends, starts the next.
  Starting a job, which plans and probes before it renders, happens off the
  caller's stack (`:spawn`), so `submit!` returns at once.

  Every change to a job is one event, {:type :job :job <the job>}. Events
  go out in the order of the changes, one at a time: they queue up under
  the lock and one caller delivers them, so a listener that changes a job
  in turn, or another thread, never overtakes an event already queued. A
  job's events therefore read queued, running, progress..., then done,
  failed or cancelled."
  (:require [watermark.core.jobs :as jobs]
            [watermark.engine :as engine]
            [watermark.util.host :as host]
            [watermark.util.task :as task]
            [watermark.util.time :as time]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private internal [:cancel? :handle :seq])

(defn- public [job] (apply dissoc job internal))

;; ---------------------------------------------------------------------------
;; Events

(defn- publish!
  "Queue an event for `job`. Called holding the lock, so events keep the
  order of the changes they report."
  [{:keys [outbox]} job]
  (swap! outbox update :events conj {:type :job :job (public job)}))

(defn- drain!
  "Deliver queued events in order, one at a time, unless a delivery is
  already under way (on this thread or another): that one delivers them."
  [{:keys [outbox listeners lock]}]
  (loop []
    (when-let [event (host/serialized
                      lock
                      (fn []
                        (let [{:keys [events draining?]} @outbox]
                          (when (and (not draining?) (seq events))
                            (swap! outbox assoc :events (subvec events 1) :draining? true)
                            (first events)))))]
      (try
        (doseq [f (vals @listeners)]
          (try (f event) (catch #?(:clj Exception :cljd Object) _ nil)))
        (finally
          (host/serialized lock #(swap! outbox assoc :draining? false))))
      (recur))))

(defn- change!*
  "Apply (f job & args) to job `id` and queue its event. Holding the lock."
  [{:keys [state] :as q} id f & args]
  (when (get-in @state [:jobs id])
    (let [s (swap! state #(apply update-in % [:jobs id] f args))]
      (publish! q (get-in s [:jobs id])))))

(defn- change! [q id f & args]
  (host/serialized (:lock q) #(apply change!* q id f args))
  (drain! q))

;; ---------------------------------------------------------------------------
;; Running

(declare pump!)

(defn- error-of [e]
  {:message (or (ex-message e) (host/describe-error e))
   :kind    (:wmark/error (ex-data e))})

(defn- finish!
  "Job `id` has ended with `changes`: free its slot, then start the next."
  [q id changes]
  (host/serialized (:lock q)
                   (fn []
                     (change!* q id merge changes)
                     (swap! (:state q) update :running disj id)))
  (drain! q)
  (pump! q))

(defn- start!
  "Run job `id` (its slot is taken): a task that settles when it has ended."
  [{:keys [opts state] :as q} id]
  (let [{:keys [cancel? handle] :as job} (get-in @state [:jobs id])]
    (-> (task/attempt
         (fn []
           ((:run opts) (public job)
                        {:cancelled? (fn [] @cancel?)
                         ;; a cancel that came before the render began stops it now
                         :on-handle  (fn [h]
                                       (reset! handle h)
                                       (when @cancel? (engine/cancel! h)))
                         :on-event   (fn [e]
                                       (when (= :progress (:type e))
                                         (change! q id assoc :progress e)))})))
        (task/then (fn [results] [:ok results]))
        (task/recover (fn [e] [:error e]))
        (task/then (fn [[k v]]
                     (finish! q id (cond (= :error k) {:state :failed :error (error-of v)}
                                         @cancel?     {:state :cancelled :result v}
                                         :else        {:state :done :result v})))))))

(defn- pump!
  "Start queued jobs, oldest first, while there are free slots."
  [{:keys [opts state lock] :as q}]
  (let [started (host/serialized
                 lock
                 (fn []
                   (loop [started []]
                     (let [{:keys [pending running closed?]} @state]
                       (if (and (not closed?) (seq pending) (< (count running) (:concurrency opts)))
                         (let [id (first pending)]
                           (swap! state #(-> % (update :pending subvec 1) (update :running conj id)))
                           (change!* q id assoc :state :running)
                           (recur (conj started id)))
                         started)))))]
    (drain! q)
    (doseq [id started]
      ((:spawn opts) #(start! q id)))))

(defrecord Queue [opts state listeners lock outbox]
  jobs/JobQueue
  (submit! [q job]
    (let [id  (str (random-uuid))
          job (assoc job :id id :state :queued :created-at (time/now)
                     :cancel? (atom false) :handle (atom nil))]
      (host/serialized lock
                       (fn []
                         (when (:closed? @state)
                           (throw (ex-info "The job queue is shut down." {:wmark/error :unavailable})))
                         (let [s (swap! state (fn [s]
                                                (-> s
                                                    (assoc-in [:jobs id] (assoc job :seq (:seq s)))
                                                    (update :seq inc)
                                                    (update :pending conj id))))]
                           (publish! q (get-in s [:jobs id])))))
      (drain! q)
      (pump! q)
      (public job)))

  (cancel! [q id]
    (let [job (get-in @state [:jobs id])]
      (when-not job
        (throw (ex-info (str "No job " id) {:wmark/error :not-found})))
      (reset! (:cancel? job) true)
      ;; a job still waiting ends now, never having run
      (host/serialized lock
                       (fn []
                         (when (some #{id} (:pending @state))
                           (swap! state update :pending (fn [p] (filterv #(not= id %) p)))
                           (change!* q id assoc :state :cancelled))))
      (drain! q)
      (some-> @(:handle job) engine/cancel!)
      true))

  (list-jobs [_]
    (->> (vals (:jobs @state)) (sort-by :seq) (mapv public)))

  (subscribe! [_ k f] (swap! listeners assoc k f) k)
  (unsubscribe! [_ k] (swap! listeners dissoc k))

  (shutdown! [q]
    (let [{:keys [pending running]}
          (host/serialized lock
                           (fn []
                             (let [s @state]
                               (swap! state assoc :closed? true :pending [])
                               (doseq [id (:pending s)]
                                 (reset! (:cancel? (get-in s [:jobs id])) true)
                                 (change!* q id assoc :state :cancelled))
                               s)))]
      (drain! q)
      (doseq [id running]
        (let [{:keys [cancel? handle]} (get-in @state [:jobs id])]
          (reset! cancel? true)
          (some-> @handle engine/cancel!)))
      (when-let [f (:on-shutdown opts)] (f))
      nil)))

(defn queue
  "A JobQueue.
    :run          (fn [job opts] task of the job's results); opts carry
                  :cancelled?, :on-handle and :on-event, as
                  watermark.core.jobs/run-job! takes them
    :concurrency  jobs running at once (default 1)
    :spawn        (fn [f]) runs (f) off the caller's stack (default
                  watermark.util.task/later)
    :on-shutdown  (fn []) called by shutdown!, after the jobs are told"
  [{:keys [run] :as opts}]
  (assert (fn? run) ":run is the function that runs a job")
  (->Queue (merge {:concurrency 1 :spawn task/later} opts)
           (atom {:jobs {} :pending [] :running #{} :seq 0 :closed? false})
           (atom {})
           (host/lock)
           (atom {:events [] :draining? false})))

(defn local-queue
  "A JobQueue that renders against `env` (watermark.core.jobs describes it),
  with `opts` as `queue` takes them."
  [env opts]
  (queue (assoc opts :run (fn [job o] (jobs/run-job! env job o)))))
