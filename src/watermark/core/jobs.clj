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
    :rasterizer    a watermark.raster/Rasterizer, for render spec v2
    :spec-version  1 or 2 to insist on one; by default v1 where the engine
                   can render the spec that way, else v2 (host-drawn
                   bitmaps, docs/adr/0006)

  JobQueue is the port for queueing: watermark.core.jobs.local runs jobs on
  an in-process executor; a serverless deployment implements the same
  protocol over a durable queue (SQS, Cloud Tasks, a Postgres table) and runs
  `run-job!` in its workers."
  (:require [watermark.core.seeds :as seeds]
            [watermark.engine :as engine]
            [watermark.media :as media]
            [watermark.raster :as raster]
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

(def ^:private drawing-capabilities
  "What a spec version changes: v2 needs none of these from the engine."
  #{:layers :animations :timing :placement})

(defn spec-version
  "The render spec version to give an engine for the v1 `spec`: `wanted` if
  set; else 1 where the engine takes v1 and can draw all of it; else 2 where
  it takes v2 (the host then draws what it couldn't, exactly, which is no
  approximation); else 1, whose refusal names what's missing. Engines that
  don't list :spec-versions take 1."
  [{:keys [capabilities]} spec wanted]
  (let [vs      (:spec-versions capabilities #{1})
        v1-gaps (filter (comp drawing-capabilities first) (engine/missing capabilities {:spec spec}))]
    (or wanted
        (cond (and (contains? vs 1) (empty? v1-gaps)) 1
              (contains? vs 2)                        2
              :else                                   1))))

(defn release!
  "Delete what planning stored for a render (a v2 spec's bitmaps)."
  [{:keys [rasterizer]} {:keys [spec]}]
  (when (and rasterizer (= 2 (:spec/version spec)))
    (raster/release! rasterizer spec)))

(defn- host-render
  "The v2 spec of a v1 spec, drawn by the host's rasterizer."
  [{:keys [engine rasterizer]} spec]
  (when-not rasterizer
    (throw (ex-info "This engine takes host-drawn specs (render spec v2), and no rasterizer is configured."
                    {:wmark/error :unsupported})))
  (raster/realize! rasterizer engine spec))

(defn plan-input
  "Everything up to (not including) rendering, for one input: probe, render
  spec, output location, engine plan. Also what dry runs show. A v2 plan
  holds bitmaps on disk until `release!`."
  [{:keys [engine media entitlements secret-for font] :as env} ctx settings input]
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
        spec      (if (= 2 (spec-version (engine/info engine) spec (:spec-version env)))
                    (host-render env spec)
                    spec)
        out       (media/open-output media ctx input settings)
        plan      (try
                    (engine/prepare engine {:spec            spec
                                            :source          (:location src)
                                            :media           info
                                            :output          {:path (:temp out) :container (:container out)}
                                            :encode          (:encode settings)
                                            :strip-metadata? (get-in settings [:output :strip-metadata] true)})
                    (catch Throwable t
                      (release! env {:spec spec})
                      (throw t)))]
    {:input (str input) :media info :spec spec :output out :plan plan}))

(defn render-input!
  "Plan, render and publish one input. Never throws: returns a result map
  {:state :done|:failed|:cancelled, :input, :output | :error}."
  [env ctx settings input {:keys [on-event cancelled? on-handle]}]
  (let [on-event   (or on-event (fn [_]))
        cancelled? (or cancelled? (constantly false))]
    (try
      (let [{:keys [output plan] :as planned} (plan-input env ctx settings input)
            {:keys [media engine]} env]
        (try
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
                    {:state :failed :input (str input) :error (get-in outcome [:error :message])}))))
          (finally (release! env planned))))
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
