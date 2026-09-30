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

  JobQueue is the port for queueing: watermark.core.queue is the in-process
  one on every host (docs/adr/0015); a serverless deployment implements the
  same protocol over a durable queue (SQS, Cloud Tasks, a Postgres table)
  and runs `run-job!` in its workers.

  Part of the core library (docs/adr/0013, section 3). Planning is
  synchronous; rendering finishes later, so `render-input!` and `run-job!`
  return tasks (watermark.util.task): a CompletableFuture on the JVM, a
  Future on the Dart VM, which can't wait for one."
  (:require [watermark.core.seeds :as seeds]
            [watermark.engine :as engine]
            [watermark.media :as media]
            [watermark.raster :as raster]
            [watermark.render :as render]
            [watermark.util.host :as host]
            [watermark.util.num :as number]
            [watermark.util.task :as task]))

#?(:clj (set! *warn-on-reflection* true))

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

(defn- spec-of
  "The render spec for one probed input: v1, or v2 drawn by the host where
  the engine needs it."
  [{:keys [engine entitlements secret-for font] :as env} ctx settings src info]
  (let [logo      (:logo settings)
        logo-info (when (and (:enabled logo true) (:path logo))
                    (engine/probe engine (:path logo)))
        spec      (render/build {:settings     settings
                                 :media        info
                                 :logo-media   logo-info
                                 :seed-fn      (seeds/seed-fn (secret-for ctx) (:fingerprint src))
                                 :entitlements entitlements
                                 :font         (force font)})]
    (if (= 2 (spec-version (engine/info engine) spec (:spec-version env)))
      (host-render env spec)
      spec)))

(defn- open-video [{:keys [engine media]} ctx input]
  (let [src  (media/open-input media ctx input)
        info (engine/probe engine (:location src))]
    (when (not= :video (:kind info))
      (throw (ex-info (str input " is not a video.") {:wmark/error :invalid :path (str input)})))
    [src info]))

(defn- prepare!
  "The engine's plan; a v2 spec's bitmaps are released if planning fails."
  [{:keys [engine] :as env} spec request]
  (let [planned (volatile! false)]
    (try
      (let [plan (engine/prepare engine (assoc request :spec spec))]
        (vreset! planned true)
        plan)
      (finally
        (when-not @planned (release! env {:spec spec}))))))

(defn metadata-of
  "The tags to write: the settings' output metadata, blank values left out."
  [settings]
  (not-empty (into {} (keep (fn [[k v]] (when (and (string? v) (re-find #"[^ \t\n\u000B\f\r]" v)) [k v])))
                   (get-in settings [:output :metadata]))))

(defn plan-input
  "Everything up to (not including) rendering, for one input: probe, render
  spec, output location, engine plan. Also what dry runs show. A v2 plan
  holds bitmaps on disk until `release!`. `cover` ({:t seconds}) asks for a
  cover picture: the render's frame at t, which `render-input!` draws to
  the plan's :cover path before rendering."
  ([env ctx settings input] (plan-input env ctx settings input nil))
  ([{:keys [media] :as env} ctx settings input {:keys [cover]}]
   (let [[src info] (open-video env ctx input)
         spec       (spec-of env ctx settings src info)
         out        (media/open-output media ctx input settings)
         cover      (when cover {:t (:t cover) :path (str (:temp out) ".cover.png")})
         plan       (prepare! env spec (cond-> {:source          (:location src)
                                                :media           info
                                                :output          {:path (:temp out) :container (:container out)}
                                                :encode          (:encode settings)
                                                :strip-metadata? (get-in settings [:output :strip-metadata] true)}
                                         (metadata-of settings) (assoc :metadata (metadata-of settings))
                                         cover                  (assoc :cover {:path (:path cover)})))]
     (cond-> {:input (str input) :media info :spec spec :output out :plan plan}
       cover (assoc :cover cover)))))

(defn frame-at
  "The frame showing at `t` seconds (the one whose start is at or before t),
  within the clip."
  [{:keys [fps-num fps-den frames duration-s]} t]
  (let [fps (/ (double fps-num) fps-den)
        end (dec (or frames (number/ceil-int (* fps (or duration-s 0.0)))))]
    (number/clamp 0 (max 0 end) (number/floor-int (* fps (max 0.0 (double t)))))))

(defn plan-frame
  "A preview (docs/adr/0011, section 5): the render of `input` with
  `settings`, cut to the frame at `t` seconds, as a PNG at `out`. Same spec,
  same engine plan, one frame. Release it with `release!` like any plan."
  [env ctx settings input {:keys [t out]}]
  (let [[src info] (open-video env ctx input)
        frame      (frame-at info (or t 0.0))
        spec       (spec-of env ctx settings src info)
        plan       (prepare! env spec {:source          (:location src)
                                       :media           info
                                       :output          {:path (str out) :frame frame}
                                       :encode          (:encode settings)
                                       :strip-metadata? true})]
    {:input (str input) :media info :spec spec :frame frame :plan plan}))

(defn- draw-cover!
  "A task that renders the cover picture of `planned` (the render's frame at
  its time) to its path, before the render that embeds it."
  [{:keys [engine] :as env} ctx settings input {:keys [cover]}]
  (let [framed (plan-frame env ctx settings input {:t (:t cover) :out (:path cover)})]
    (-> (task/attempt #(engine/outcome (engine/execute! engine (:plan framed) nil)))
        (task/then (fn [outcome]
                     (when-not (= :done (:status outcome))
                       (throw (ex-info (str "The cover picture failed. " (get-in outcome [:error :message]))
                                       {:wmark/error :failed})))
                     nil))
        (task/always #(release! env framed)))))

(defn- failure
  "The result of an input that failed with error `e`."
  [input e]
  (if-let [data (ex-data e)]
    {:state :failed :input (str input) :error (ex-message e) :kind (:wmark/error data)}
    ;; I/O while publishing, say: still this input's failure
    {:state :failed :input (str input) :error (host/describe-error e)}))

(defn- render-planned
  "A task that renders a planned input and publishes or discards the
  output; the plan's bitmaps and cover are released whatever happens."
  [{:keys [media engine] :as env} ctx settings input {:keys [output plan] :as planned}
   {:keys [on-event cancelled? on-handle]}]
  (-> (task/attempt
       (fn []
         (on-event {:type :started :input (str input) :output (:final output)})
         (when (and (:cover planned) (not (cancelled?)))
           (draw-cover! env ctx settings input planned))))
      (task/then
       (fn [_]
         (if (cancelled?)
           {:state :cancelled :input (str input)}
           (let [handle (engine/execute! engine plan
                                         (fn [e] (on-event (assoc e :type :progress :input (str input)))))]
             (when on-handle (on-handle handle))
             (task/then (engine/outcome handle)
                        (fn [outcome]
                          (case (:status outcome)
                            :done      {:state :done :input (str input) :output (media/commit! media ctx output)}
                            :cancelled (do (media/discard! media ctx output)
                                           {:state :cancelled :input (str input)})
                            (do (media/discard! media ctx output)
                                {:state :failed :input (str input) :error (get-in outcome [:error :message])}))))))))
      (task/always (fn []
                     (release! env planned)
                     (when-let [c (:cover planned)] (media/discard! media ctx {:temp (:path c)}))))))

(defn render-input!
  "Plan, render and publish one input. Returns a task that never fails: it
  resolves to {:state :done|:failed|:cancelled, :input, :output | :error}.
  `cover` ({:t seconds}) embeds the render's frame at t as the file's
  cover."
  [env ctx settings input {:keys [on-event cancelled? cover] :as opts}]
  (let [opts (assoc opts
                    :on-event   (or on-event (fn [_]))
                    :cancelled? (or cancelled? (constantly false)))]
    (-> (task/attempt #(plan-input env ctx settings input {:cover cover}))
        (task/then #(render-planned env ctx settings input % opts))
        (task/recover #(failure input %)))))

(defn run-job!
  "Render every input of a job in order. One failure doesn't stop the batch;
  cancellation does. Returns a task of one result per input attempted."
  [env {:keys [ctx settings inputs cover]} {:keys [on-event cancelled?] :as opts}]
  (let [on-event   (or on-event (fn [_]))
        cancelled? (or cancelled? (constantly false))]
    (task/reduce (fn [results [i input]]
                   (if (cancelled?)
                     (reduced results)
                     (task/then (render-input! env ctx settings input
                                               (assoc opts
                                                      :cover cover
                                                      :cancelled? cancelled?
                                                      :on-event #(on-event (assoc % :index i))))
                                (fn [result]
                                  (let [result (assoc result :index i)]
                                    (on-event (assoc result :type :finished))
                                    (conj results result))))))
                 []
                 (map-indexed vector inputs))))
