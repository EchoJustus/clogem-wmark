;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine
  "The video-engine port.

  Orchestration (watermark.core.jobs) talks to rendering only through these
  protocols, so it cannot tell whether frames come from an FFmpeg child
  process, a native library behind a C ABI (AVFoundation/Metal on Apple,
  Media3 on Android, a Rust GPU core), or a test double.

  Contract

  (info e)          -> data: {:engine/id :ffmpeg, :engine/version \"6.1.1\",
                              :available? bool, :problems [\"...\"],
                              :capabilities {...}, :details {...}}
                       Cheap after the first call; never throws.
  (probe e source)  -> media facts for a local path or URL, video or still:
                       {:kind :video|:image, :width, :height (display size,
                       rotation applied), :fps-num, :fps-den, :frames,
                       :duration-s, :start-s, :vfr?, :has-audio?, :rotation}
  (prepare e req)   -> an engine plan: plain, serialisable data. Validates the
                       request against the engine's capabilities and compiles
                       it; nothing is written or started. Dry runs print it.
  (execute! e plan listener)
                    -> a RenderHandle, returned immediately. `listener`
                       receives event maps on an engine thread:
                       {:event :progress, :fraction 0.42, :frame 1234}.

  RenderHandle
  (cancel! h)       ask the engine to stop; idempotent.
  (outcome h)       deferred final outcome -- derefable on the JVM (deref
                    blocks), a Future on Dart -- resolving to
                    {:status :done|:failed|:cancelled, :error {...}, :stats {...}}

  Render request (input of `prepare`)

    {:spec   <watermark.render spec>
     :source \"/abs/in.mov\"                   ; local path or URL
     :media  <probe of :source>
     :output {:path \"/abs/out.part.mp4\" :container \"mp4\"}
     :encode {:codec :h264 :quality :high :audio :copy :ffmpeg {...}}
     :strip-metadata? true}

  Capabilities (data, from `info`)

    {:layers #{:image :text}  :animations #{:flip-y}
     :timing #{:always :windows :periodic}
     :placement #{:fixed :burst-scatter :per-window}
     :codecs #{:h264 :hevc}  :containers #{\"mp4\" \"mov\" \"mkv\"}
     :audio #{:copy :aac :none}  :sources #{:file :url}}

  An engine that lacks :text can still serve specs whose text layers were
  lowered to image layers first (text rasterised by the host) -- the escape
  hatch for minimal GPU cores.")

#?(:clj (set! *warn-on-reflection* true))

(defprotocol VideoEngine
  (info     [engine])
  (probe    [engine source])
  (prepare  [engine request])
  (execute! [engine plan listener]))

(defprotocol RenderHandle
  (cancel! [handle])
  (outcome [handle]))

(defprotocol StillDecoder
  (decode-still [engine source]
    "A still image (the logo) decoded by the engine, as {:width :height :px}:
    straight RGBA8 bytes, row-major. Hosts draw render spec v2's bitmaps
    from it, so image formats stay the engine's business (docs/adr/0006)."))

;; ---------------------------------------------------------------------------
;; Capability negotiation

(defn requirements
  "What a render request needs from an engine, as [capability value] pairs."
  [{:keys [spec encode output]}]
  (distinct
   (concat
    ;; v1 is the baseline every engine takes; newer versions are negotiated
    (when (not= 1 (:spec/version spec 1)) [[:spec-versions (:spec/version spec)]])
    (for [l (:layers spec)] [:layers (:kind l)])
    (for [l (:layers spec) :when (:animation l)] [:animations (get-in l [:animation :type])])
    (for [l (:layers spec)] [:timing (get-in l [:timing :type])])
    (for [l (:layers spec) :when (:placement l)] [:placement (get-in l [:placement :type])])
    [[:codecs (:codec encode :h264)]
     [:containers (:container output "mp4")]
     [:audio (:audio encode :copy)]])))

(defn missing
  "Requirements of `request` that `capabilities` doesn't cover."
  [capabilities request]
  (vec (remove (fn [[k v]] (contains? (get capabilities k #{}) v))
               (requirements request))))

(defn check!
  "Throw :unsupported unless the engine can render `request`."
  [{:keys [capabilities] :as engine-info} request]
  (let [gaps (missing capabilities request)]
    (when (seq gaps)
      (throw (ex-info (str "The " (name (:engine/id engine-info :engine)) " engine can't render this: "
                           (apply str (interpose ", " (map (fn [[k v]] (str (name k) " " (if (keyword? v) (name v) v)))
                                                          gaps)))
                           ".")
                      {:wmark/error :unsupported
                       :engine      (:engine/id engine-info)
                       :missing     gaps})))
    request))

(defn failed
  "Outcome for a failed render."
  ([message] (failed message nil))
  ([message details]
   {:status :failed :error (cond-> {:message message} details (assoc :details details))}))
