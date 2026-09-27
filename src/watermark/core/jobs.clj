;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.jobs
  "Job orchestration: resolved settings + inputs -> published outputs.

  This namespace talks to rendering only through watermark.engine and to
  files only through watermark.media. It can't tell -- and must not care --
  whether frames come from an FFmpeg child process, a native library behind
  the C ABI, or a test double. test/watermark/architecture_test.clj enforces
  that dependency rule.

  `env` is the system map (or any map) with
    :engine        a watermark.engine/VideoEngine
    :media         a watermark.media/MediaIO
    :entitlements  a watermark.core.features/Entitlements
    :secret-for    (fn [ctx] studio-secret-bytes) -- per tenant in the SaaS
    :font          default font path (or a delay of one)

  JobQueue is the port for queueing: watermark.core.jobs.local runs jobs on
  an in-process executor; a serverless deployment implements the same
  protocol over a durable queue (SQS, Cloud Tasks, a Postgres table) and runs
  `run-job!` in its workers."
  (:require [watermark.core.seeds :as seeds]
            [watermark.engine :as engine]
            [watermark.media :as media]
            [watermark.render :as render])
  (:import (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(defprotocol JobQueue
  (submit!      [q job]   "Queue {:ctx :settings :inputs}; returns the job with :id and :state.")
  (cancel!      [q id]    "Request cancellation; running renders are stopped.")
  (list-jobs    [q]       "Jobs known to this queue, oldest first.")
  (subscribe!   [q k f]   "Call (f event) for every job event.")
  (unsubscribe! [q k])
  (shutdown!    [q]))

(defn plan-input
  "Everything up to (not including) rendering, for one input: probe, render
  spec, output location, engine plan. Also what dry runs show."
  [{:keys [engine media entitlements secret-for font]} ctx settings input]
  (let [src       (media/open-input media ctx input)
        info      (engine/probe engine (:location src))
        _         (when (not= :video (:kind info))
                    (throw (ex-info (str input " is not a video.") {:wmark/error :invalid :path (str input)})))
        logo      (:logo settings)
        logo-info (when (and (:enabled logo true) (:path logo))
                    (engine/probe engine (:path logo)))
        spec      (render/build {:settings     settings
                                 :media        info
                                 :logo-media   logo-info
                                 :seed-fn      (seeds/seed-fn (secret-for ctx) (:fingerprint src))
                                 :entitlements entitlements
                                 :font         (force font)})
        out       (media/open-output media ctx input settings)
        plan      (engine/prepare engine {:spec            spec
                                          :source          (:location src)
                                          :media           info
                                          :output          {:path (:temp out) :container (:container out)}
                                          :encode          (:encode settings)
                                          :strip-metadata? (get-in settings [:output :strip-metadata] true)})]
    {:input (str input) :media info :spec spec :output out :plan plan}))

(defn render-input!
  "Plan, render and publish one input. Never throws: returns a result map
  {:state :done|:failed|:cancelled, :input, :output | :error}."
  [env ctx settings input {:keys [on-event cancelled? on-handle]}]
  (let [on-event   (or on-event (fn [_]))
        cancelled? (or cancelled? (constantly false))]
    (try
      (let [{:keys [output plan]} (plan-input env ctx settings input)
            {:keys [media engine]} env]
        (on-event {:type :started :input (str input) :output (:final output)})
        (if (cancelled?)
          {:state :cancelled :input (str input)}
          (let [handle  (engine/execute! engine plan
                                         (fn [e] (on-event (assoc e :type :progress :input (str input)))))
                _       (when on-handle (on-handle handle))
                outcome (deref (engine/outcome handle))]
            (case (:status outcome)
              :done      {:state :done :input (str input) :output (media/commit! media ctx output)}
              :cancelled (do (media/discard! media ctx output)
                             {:state :cancelled :input (str input)})
              (do (media/discard! media ctx output)
                  {:state :failed :input (str input) :error (get-in outcome [:error :message])})))))
      (catch ExceptionInfo e
        {:state :failed :input (str input) :error (ex-message e) :kind (:wmark/error (ex-data e))})
      (catch Exception e                  ; I/O while publishing, say: still this input's failure
        {:state :failed :input (str input) :error (str (.getSimpleName (class e)) ": " (ex-message e))}))))

(defn run-job!
  "Render every input of a job in order. One failure doesn't stop the batch;
  cancellation does. Returns one result per input attempted."
  [env {:keys [ctx settings inputs]} {:keys [on-event cancelled?] :as opts}]
  (let [on-event   (or on-event (fn [_]))
        cancelled? (or cancelled? (constantly false))]
    (loop [[input & more] inputs, i 0, results []]
      (if (or (nil? input) (cancelled?))
        results
        (let [result (assoc (render-input! env ctx settings input
                                           (assoc opts
                                                  :cancelled? cancelled?
                                                  :on-event #(on-event (assoc % :index i))))
                            :index i)]
          (on-event (assoc result :type :finished))
          (recur more (inc i) (conj results result)))))))
