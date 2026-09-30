;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.queue-contract
  "Behaviour every in-process JobQueue (watermark.core.jobs/JobQueue) must
  show (docs/adr/0015): jobs run in turn, as many at once as allowed; each
  job's events arrive in order; a queued job cancels at once and never
  runs; a running one is stopped through its render's handle; failures say
  why; shutdown ends what is left.

  Portable, so the Dart VM runs it too. Jobs end on other threads on the
  JVM and later on the event loop on the Dart VM, so the contract is a task,
  and it records its checks as data: clojure.test counts an assertion only
  on the test's own thread. Run it as

    JVM:      (assert-all (task/await (run-all make)))
    Dart VM:  (task/then (run-all make) assert-all)

  where (make opts) returns a queue for opts {:run :concurrency}, as
  watermark.core.queue/queue takes them. M4's hosted queues add their own
  checks (leases, retries, a dead-letter path) to these."
  (:require [clojure.test :refer [is]]
            [watermark.core.jobs :as jobs]
            [watermark.engine :as engine]
            [watermark.util.task :as task]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; A stand-in for run-job!: each job ends when the contract says so

(defn- entry! [r id]
  (get (swap! (:jobs r) (fn [m] (if (m id) m (assoc m id {:started (task/deferred) :ended (task/deferred)
                                                         :settled (atom false) :cancelled (atom false)}))))
       id))

(defn- settle! [r id f]
  (let [{:keys [ended settled]} (entry! r id)]
    (when (compare-and-set! settled false true) (f ended))))

(defn release!
  "Let job `id` end with `results`."
  [r id results]
  (settle! r id #(task/complete! % results)))

(defn fail!
  "Let job `id` end with error `e`."
  [r id e]
  (settle! r id #(task/fail! % e)))

(defn- runner
  "Jobs whose input is \"boom\" throw at once; with `late-handle?`, a job
  hands its render's handle over only when `hand-over!` is called."
  ([] (runner false))
  ([late-handle?]
   (let [r {:jobs (atom {}) :handles (atom {}) :runs (atom [])}]
     (assoc r :run
            (fn [job opts]
              (let [id (:id job)]
                (swap! (:runs r) conj id)
                (when (= ["boom"] (:inputs job))
                  (throw (ex-info "boom" {:wmark/error :failed})))
                (let [{:keys [started ended cancelled]} (entry! r id)
                      handle (reify engine/RenderHandle
                               (cancel! [_] (reset! cancelled true) (release! r id [:partial]) nil)
                               (outcome [_] (task/task-of ended)))]
                  (swap! (:handles r) assoc id #((:on-handle opts) handle))
                  (when-not late-handle? ((:on-handle opts) handle))
                  ((:on-event opts) {:type :progress :pct 0.5})
                  ((:on-event opts) {:type :started})
                  (task/complete! started true)
                  (task/task-of ended))))))))

(defn- hand-over! [r id] ((get @(:handles r) id)))

(defn- started [r id] (task/task-of (:started (entry! r id))))

(defn- ran? [r id] (boolean (some #{id} @(:runs r))))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- state-of [q id] (:state (first (filter #(= id (:id %)) (jobs/list-jobs q)))))

(defn- watch
  "Record every event of `q` from now on, in order, and resolve waits on
  them: when a wait resolves, the log holds every event before it."
  [q]
  (let [w {:log (atom []) :waiters (atom [])}]
    (jobs/subscribe! q (keyword "watermark.queue-contract" (str "watch-" (random-uuid)))
                     (fn [{:keys [job]}]
                       (swap! (:log w) conj job)
                       (doseq [[pred d done] @(:waiters w)]
                         (when (and (pred job) (compare-and-set! done false true))
                           (task/complete! d job)))))
    w))

(defn- until
  "A task of job `id`'s event once one satisfies `pred`, logged or to come."
  [w id pred]
  (let [d    (task/deferred)
        done (atom false)
        p    #(and (= id (:id %)) (pred %))]
    (swap! (:waiters w) conj [p d done])
    (when-let [j (last (filter p @(:log w)))]
      (when (compare-and-set! done false true) (task/complete! d j)))
    (task/task-of d)))

(defn- in-state [w id state] (until w id #(= state (:state %))))

(defn- states [w id] (vec (dedupe (keep #(when (= id (:id %)) (:state %)) @(:log w)))))

(defn- kind [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljd cljd.core/ExceptionInfo) e (:wmark/error (ex-data e)))))

(defn- job [& inputs] {:ctx {:tenant "t" :user "u"} :settings {} :inputs (vec inputs)})

(defn- steps
  "Call the functions `fs` one after another, each once the task the one
  before returned has settled."
  [& fs]
  (task/reduce (fn [_ f] (f)) nil fs))

;; ---------------------------------------------------------------------------
;; The contract: each case is (fn [make check]) returning a task

(defn- runs-to-done [make check]
  (let [r  (runner)
        q  (make {:run (:run r)})
        w  (watch q)
        j  (jobs/submit! q (job "a.mov"))
        id (:id j)]
    (check (= :queued (:state j)) "submit! returns the job, queued" j)
    (check (and (string? id) (string? (:created-at j))) "a job has an id and a time" j)
    (check (not-any? #(contains? j %) [:cancel? :handle :seq]) "jobs show their public fields only" (keys j))
    (steps #(started r id)
           #(release! r id [:result])
           #(task/then (in-state w id :done)
                       (fn [done]
                         (check (= [:result] (:result done)) "a finished job carries its results" done)
                         (check (= {:type :progress :pct 0.5} (:progress done))
                                "progress events are kept; others aren't" done)
                         (check (= [:queued :running :done] (states w id)) "a job's events arrive in order" (states w id))
                         (check (every? (fn [e] (not-any? (fn [k] (contains? e k)) [:cancel? :handle :seq])) @(:log w))
                                "events show public fields only" nil)
                         (jobs/shutdown! q))))))

(defn- one-at-a-time [make check]
  (let [r (runner)
        q (make {:run (:run r) :concurrency 1})
        w (watch q)
        a (:id (jobs/submit! q (job "a.mov")))
        b (:id (jobs/submit! q (job "b.mov")))]
    (steps #(started r a)
           (fn []
             (check (= :queued (state-of q b)) "the second job waits while the first runs" (state-of q b))
             (check (not (ran? r b)) "and hasn't been started" @(:runs r)))
           #(release! r a [:a])
           #(started r b)
           #(release! r b [:b])
           #(in-state w b :done)
           (fn []
             (check (= [a b] @(:runs r)) "jobs run oldest first" @(:runs r))
             (check (= [a b] (mapv :id (jobs/list-jobs q))) "list-jobs lists them oldest first" nil)
             (jobs/shutdown! q)))))

(defn- n-at-a-time [make check]
  (let [r (runner)
        q (make {:run (:run r) :concurrency 2})
        w (watch q)
        [a b c] (mapv #(:id (jobs/submit! q (job %))) ["a.mov" "b.mov" "c.mov"])]
    (steps #(started r a)
           #(started r b)
           (fn [] (check (= :queued (state-of q c)) "a third job waits for a free slot" (state-of q c)))
           #(release! r b [:b])
           #(started r c)
           #(release! r a [:a])
           #(release! r c [:c])
           #(in-state w c :done)
           #(in-state w a :done)
           (fn []
             (check (= #{:done} (set (map :state (jobs/list-jobs q)))) "all done" (jobs/list-jobs q))
             (jobs/shutdown! q)))))

(defn- cancel-queued [make check]
  (let [r (runner)
        q (make {:run (:run r) :concurrency 1})
        w (watch q)
        a (:id (jobs/submit! q (job "a.mov")))
        b (:id (jobs/submit! q (job "b.mov")))]
    (steps #(started r a)
           (fn []
             (check (true? (jobs/cancel! q b)) "cancel! answers true" nil)
             (check (= :cancelled (state-of q b)) "a queued job is cancelled at once" (state-of q b)))
           #(release! r a [:a])
           #(in-state w a :done)
           #(task/later (fn [] nil))
           (fn []
             (check (not (ran? r b)) "and never runs" @(:runs r))
             (check (= [:queued :cancelled] (states w b)) "its events: queued, cancelled" (states w b))
             (jobs/shutdown! q)))))

(defn- cancel-running [make check]
  (let [r (runner)
        q (make {:run (:run r)})
        w (watch q)
        a (:id (jobs/submit! q (job "a.mov")))]
    (steps #(started r a)
           #(jobs/cancel! q a)
           #(task/then (in-state w a :cancelled)
                       (fn [done]
                         (check @(:cancelled (entry! r a)) "a running job is stopped through its render's handle" nil)
                         (check (= [:partial] (:result done)) "and keeps what it finished" done)
                         (jobs/shutdown! q))))))

(defn- cancel-before-the-render [make check]
  (let [r (runner true)
        q (make {:run (:run r)})
        w (watch q)
        a (:id (jobs/submit! q (job "a.mov")))]
    (steps #(started r a)
           #(jobs/cancel! q a)
           #(hand-over! r a)
           #(task/then (in-state w a :cancelled)
                       (fn [_]
                         (check @(:cancelled (entry! r a))
                                "a render that begins after its job was cancelled is stopped" nil)
                         (jobs/shutdown! q))))))

(defn- failures [make check]
  (let [r (runner)
        q (make {:run (:run r) :concurrency 1})
        w (watch q)
        a (:id (jobs/submit! q (job "a.mov")))
        b (:id (jobs/submit! q (job "boom")))
        c (:id (jobs/submit! q (job "c.mov")))]
    (steps #(started r a)
           #(fail! r a (ex-info "Can't read a.mov" {:wmark/error :invalid}))
           #(task/then (in-state w a :failed)
                       (fn [failed]
                         (check (= {:message "Can't read a.mov" :kind :invalid} (:error failed))
                                "a failed job says why, and what kind of failure" failed)))
           #(task/then (in-state w b :failed)
                       (fn [failed]
                         (check (= "boom" (get-in failed [:error :message])) "a runner that throws fails its job" failed)))
           #(started r c)
           #(release! r c [:c])
           #(task/then (in-state w c :done)
                       (fn [_]
                         (check true "failures free their slot" nil)
                         (jobs/shutdown! q))))))

(defn- subscriptions [make check]
  (let [r    (runner)
        q    (make {:run (:run r)})
        w    (watch q)
        seen (atom 0)
        k    (jobs/subscribe! q ::counting (fn [_] (swap! seen inc)))]
    (check (= ::counting k) "subscribe! answers its key" k)
    (let [a (:id (jobs/submit! q (job "a.mov")))]
      (steps #(started r a)
             (fn [] (jobs/unsubscribe! q ::counting) (reset! seen 0))
             #(release! r a [:a])
             #(task/then (in-state w a :done)
                         (fn [_]
                           (check (zero? @seen) "an unsubscribed listener hears nothing more" @seen)
                           (check (= :not-found (kind (fn [] (jobs/cancel! q "no-such-job")))) "an unknown job is :not-found" nil)
                           (jobs/shutdown! q)))))))

(defn- shutdown [make check]
  (let [r (runner)
        q (make {:run (:run r) :concurrency 1})
        w (watch q)
        a (:id (jobs/submit! q (job "a.mov")))
        b (:id (jobs/submit! q (job "b.mov")))]
    (steps #(started r a)
           #(jobs/shutdown! q)
           (fn [] (check (= :cancelled (state-of q b)) "shutdown cancels the queued jobs at once" (state-of q b)))
           #(task/then (in-state w a :cancelled)
                       (fn [_]
                         (check @(:cancelled (entry! r a)) "and stops the running ones" nil)
                         (check (not (ran? r b)) "which never start" @(:runs r))
                         (check (= :unavailable (kind (fn [] (jobs/submit! q (job "c.mov"))))) "a shut-down queue takes no job" nil))))))

(def cases
  [runs-to-done one-at-a-time n-at-a-time cancel-queued cancel-running
   cancel-before-the-render failures subscriptions shutdown])

(defn run-all
  "A task of the contract's checks, [{:ok? :what :detail}], for queues from
  (make opts)."
  [make]
  (let [results (atom [])
        check   (fn [ok? what detail] (swap! results conj {:ok? (boolean ok?) :what what :detail detail}))]
    (task/then (task/reduce (fn [_ c] (c make check)) nil cases)
               (fn [_] @results))))

(defn assert-all
  "Report each check with clojure.test, on the caller's thread."
  [results]
  (is (<= 30 (count results)) "every case ran")
  (doseq [{:keys [ok? what detail]} results]
    (is ok? (str what (when (some? detail) (str ": " (pr-str detail)))))))
