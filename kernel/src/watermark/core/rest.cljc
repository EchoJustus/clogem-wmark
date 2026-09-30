;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.rest
  "The REST contract, /api/v1, as a function of the Core API (docs/adr/0016):
  which request gets which answer, how an answer becomes JSON-ready data,
  and which error is which status. No business logic lives here.

  Transport-neutral. The JVM's HTTP adapter (watermark.server.routes) reads
  the request, calls `respond`, and writes the answer as JSON; a program that
  embeds the core library can answer the same requests in its own process,
  with no HTTP, and get the same data.

  An answer is a task (watermark.util.task) of either
    {:status n :body data}              data ready for any JSON writer: every
                                        key and keyword a string, sets and
                                        lists as vectors
    {:status 200 :file path :content-type \"image/png\"}
                                        a file to send as it is (a preview)"
  (:require [clojure.string :as str]
            [watermark.core.api :as api]
            [watermark.core.schema :as schema]
            [watermark.util.chars :as chars]
            [watermark.util.host :as host]
            [watermark.util.task :as task]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; JSON-ready data

(defn- kw->str [k] (if (qualified-keyword? k) (subs (str k) 1) (name k)))

(defn- path-key
  "A provenance path, [:logo :anchor], as \"logo.anchor\"."
  [k]
  (str/join "." (map #(if (keyword? %) (kw->str %) (str %)) k)))

(defn- sorted-if-alike
  "A set's items in order when they can be compared (all text or all
  numbers), so its JSON is the same on every run and runtime."
  [xs]
  (cond (every? string? xs) (vec (sort xs))
        (every? number? xs) (vec (sort xs))
        :else               (vec xs)))

(defn jsonable
  "Data as any JSON writer takes it: keywords become strings *with* their
  namespace (:text.mode/subliminal is \"text.mode/subliminal\"), provenance
  paths become \"logo.anchor\", sets and lists become vectors."
  [x]
  (cond
    (keyword? x) (kw->str x)
    (map? x)     (into {} (map (fn [[k v]] [(cond (keyword? k) (kw->str k)
                                                   (vector? k)  (path-key k)
                                                   :else        k)
                                              (jsonable v)]))
                       x)
    (set? x)     (sorted-if-alike (map jsonable x))
    (or (vector? x) (seq? x)) (mapv jsonable x)
    :else        x))

;; ---------------------------------------------------------------------------
;; Paths

(defn- hex-value
  "0-15 for a hex digit (either case), else nil."
  [c]
  (when-let [i (str/index-of "0123456789abcdefABCDEF" c)]
    (if (< i 16) i (- i 6))))

(defn- utf8-code-points
  "The code points of UTF-8 `bytes` (0-255), or nil when they aren't UTF-8:
  no overlong forms, surrogates or values past U+10FFFF."
  [bytes]
  (loop [bs (seq bytes) out []]
    (if (empty? bs)
      out
      (let [b (first bs)
            [len init lo] (cond (< b 0x80) [1 b 0]
                                (< b 0xC2) nil
                                (< b 0xE0) [2 (- b 0xC0) 0x80]
                                (< b 0xF0) [3 (- b 0xE0) 0x800]
                                (< b 0xF5) [4 (- b 0xF0) 0x10000]
                                :else      nil)
            tail (when len (take (dec len) (rest bs)))]
        (when (and len (= (count tail) (dec len)))
          (let [cp (reduce (fn [acc c]
                             (when (and acc (= 0x80 (bit-and c 0xC0)))
                               (+ (* acc 64) (- c 0x80))))
                           init tail)]
            (when (and cp (>= cp lo) (<= cp 0x10FFFF) (not (<= 0xD800 cp 0xDFFF)))
              (recur (drop len bs) (conj out cp)))))))))

(defn decode-segment
  "A path segment with its %XX escapes decoded as UTF-8. '+' stays '+'
  (it means a space only in query strings). A malformed escape, or bytes
  that aren't UTF-8, are :invalid."
  [s]
  (let [cps (chars/code-points s)
        bad #(throw (ex-info (str "Malformed path segment: " s) {:wmark/error :invalid}))]
    (loop [cps cps out [] pending []]
      (let [flush (fn [out] (if (seq pending)
                              (into out (or (utf8-code-points pending) (bad)))
                              out))]
        (cond
          (empty? cps)
          (chars/from-code-points (flush out))

          (= 0x25 (first cps))                                ; %
          (let [[h l] (take 2 (rest cps))
                hv (some-> h vector chars/from-code-points hex-value)
                lv (some-> l vector chars/from-code-points hex-value)]
            (if (and hv lv)
              (recur (drop 3 cps) out (conj pending (+ (* 16 hv) lv)))
              (bad)))

          :else
          (recur (rest cps) (conj (flush out) (first cps)) []))))))

(defn- split-path [path]
  (vec (remove str/blank? (str/split (first (str/split (str path) #"\?")) #"/"))))

;; ---------------------------------------------------------------------------
;; Handlers: (fn [sys ctx params body]) -> an answer's body (status 200), or
;; [status body], or {::file path}; any of them may come as a task.

(defn- settings-in
  "JSON settings -> Clojure settings (string enums -> keywords, ints -> doubles)."
  [m]
  (some-> m schema/decode-json))

(defn- request-of [{:keys [profile clean settings inputs cover]}]
  {:profile  (if clean :none profile)
   :settings (settings-in settings)
   :inputs   inputs
   :cover    cover})                    ; {t}: the frame at t becomes the cover

(defn- h-health   [sys _ _ _] (api/health sys))
(defn- h-doctor   [sys _ _ _] (api/diagnose sys))
(defn- h-features [sys ctx _ _] (api/features sys ctx))
(defn- h-schema   [sys _ _ _] (api/settings-schema sys))

(defn- h-list-profiles [sys ctx _ _] {:profiles (api/list-profiles sys ctx)})
(defn- h-get-profile [sys ctx {:keys [name]} _] (api/get-profile sys ctx name))
(defn- h-create-profile [sys ctx _ {:keys [name settings]}]
  [201 (api/create-profile! sys ctx name (settings-in settings))])
(defn- h-save-profile [sys ctx {:keys [name]} body]
  (api/save-profile! sys ctx name (settings-in (:settings body))
                     {:overwrite? (true? (:overwrite body))
                      :if-rev     (get body (keyword "if-rev"))}))
(defn- h-rename-profile [sys ctx {:keys [name]} {:keys [to]}] (api/rename-profile! sys ctx name to))
(defn- h-copy-profile [sys ctx {:keys [name]} {:keys [to]}] [201 (api/copy-profile! sys ctx name to)])
(defn- h-delete-profile [sys ctx {:keys [name]} _] (api/delete-profile! sys ctx name) {:deleted name})

(defn- h-form [sys ctx {:keys [name]} _] (api/settings-form sys ctx name))

(defn- h-draft-form
  "{settings, edit?}: the form of an unsaved draft of the profile, after one
  more edit when given. Nothing is saved; the answer carries the draft's
  settings and whether it differs from what is saved."
  [sys ctx {:keys [name]} {:keys [settings edit]}]
  (api/draft-form sys ctx name {:settings (settings-in settings) :edit edit}))

(defn- h-edit
  "{op, id?, value?, mode?, index?, delta?, if-rev?}: one form edit, saved
  only if the profile is still at if-rev (else 409, reason stale). Answers
  with the saved profile and its new form."
  [sys ctx {:keys [name]} body]
  (let [doc (api/edit-profile! sys ctx name body)]    ; JSON "if-rev" arrives as :if-rev
    (assoc (api/settings-form sys ctx (:profile/slug doc)) :saved doc)))

(defn- h-preview [sys ctx _ {:keys [profile clean settings source t aspect]}]
  (task/then (api/preview-frame sys ctx {:profile  (if clean :none profile)
                                         :settings (settings-in settings)
                                         :source   source
                                         :t        (when (number? t) (double t))
                                         :aspect   aspect})
             (fn [p] (assoc p :url (str "/api/v1/previews/" (:id p))))))

(defn- h-preview-file [sys ctx {:keys [id]} _] {::file (api/preview-file sys ctx id)})

(defn- h-resolve [sys ctx _ body] (api/resolve-settings sys ctx (request-of body)))
(defn- h-plan [sys ctx _ body] (api/plan-batch sys ctx (request-of body)))
(defn- h-submit [sys ctx _ body] [202 (api/submit-job! sys ctx (request-of body))])
(defn- h-jobs [sys ctx _ _] {:jobs (api/list-jobs sys ctx)})
(defn- h-cancel [sys ctx {:keys [id]} _] {:cancelled (api/cancel-job! sys ctx id)})

(def routes
  "[method path handler]. GET /api/v1/events, the job events, is the
  transport's: an HTTP server streams them, an in-process caller subscribes
  (api/subscribe-jobs!); each event's data is (jsonable event)."
  [[:get    "/api/v1/health"                h-health]
   [:get    "/api/v1/doctor"                h-doctor]
   [:get    "/api/v1/features"              h-features]
   [:get    "/api/v1/schema/settings"       h-schema]
   [:get    "/api/v1/profiles"              h-list-profiles]
   [:post   "/api/v1/profiles"              h-create-profile]    ; {name, settings}
   [:get    "/api/v1/profiles/:name"        h-get-profile]
   [:put    "/api/v1/profiles/:name"        h-save-profile]      ; {settings, overwrite?, if-rev?}
   [:post   "/api/v1/profiles/:name/rename" h-rename-profile]    ; {to}
   [:post   "/api/v1/profiles/:name/copy"   h-copy-profile]      ; {to}
   [:delete "/api/v1/profiles/:name"        h-delete-profile]
   [:get    "/api/v1/profiles/:name/form"   h-form]              ; the settings form model
   [:post   "/api/v1/profiles/:name/form"   h-draft-form]        ; {settings, edit?}: an unsaved draft's form
   [:post   "/api/v1/profiles/:name/edit"   h-edit]              ; {op, id, value, if-rev}
   [:post   "/api/v1/preview"               h-preview]           ; {profile?, settings?, source?, t?, aspect?}
   [:get    "/api/v1/previews/:id"          h-preview-file]      ; image/png
   [:post   "/api/v1/resolve"               h-resolve]           ; {profile?, clean?, settings?}
   [:post   "/api/v1/plan"                  h-plan]              ; + inputs: dry run
   [:post   "/api/v1/jobs"                  h-submit]            ; {inputs, profile?, settings?, cover?}
   [:get    "/api/v1/jobs"                  h-jobs]
   [:delete "/api/v1/jobs/:id"              h-cancel]])

(defn- compiled []
  (mapv (fn [[method path handler]] [method (split-path path) handler]) routes))

(defn- match
  "[handler params] for `method` and `segments`, or nil."
  [method segments]
  (some (fn [[m pattern handler]]
          (when (and (= m method) (= (count pattern) (count segments)))
            (when-let [params (reduce (fn [acc [p s]]
                                        (cond (str/starts-with? p ":") (assoc acc (keyword (subs p 1)) (decode-segment s))
                                              (= p s) acc
                                              :else (reduced nil)))
                                      {} (map vector pattern segments))]
              [handler params])))
        (compiled)))

;; ---------------------------------------------------------------------------
;; Answers

(def status-of
  "The HTTP status of each kind of error (:wmark/error)."
  {:invalid 422 :unsupported 422 :not-found 404 :conflict 409 :feature-locked 402
   :feature-unavailable 402 :unavailable 503})

(defn error-answer
  "The answer for error `e`: its kind's status and body for a domain error
  (ex-info with :wmark/error), else 500."
  [e]
  (let [data (ex-data e)
        kind (:wmark/error data)]
    (if kind
      {:status (status-of kind 400)
       :body   (jsonable (merge {:error kind :message (ex-message e)}
                                (select-keys data [:errors :features :field :name :existing :missing :reason])))}
      {:status 500
       :body   {"error" "internal" "message" (if (some? data) (ex-message e) (host/describe-error e))}})))

(defn- answer-of [result]
  (cond
    (and (map? result) (contains? result ::file))
    {:status 200 :file (::file result) :content-type "image/png"}

    (vector? result)
    {:status (first result) :body (jsonable (second result))}

    :else
    {:status 200 :body (jsonable result)}))

(defn respond
  "The answer to `method` (:get, :post, :put, :delete) on `path` for the
  caller `ctx`, as a task. `read-body` is called only when a route matches,
  and returns the request's JSON with keyword keys (or throws :invalid).
  Errors become answers too; the task itself never fails."
  [sys ctx method path read-body]
  (-> (task/attempt
       (fn []
         (if-let [[handler params] (match method (split-path path))]
           (handler sys ctx params (read-body))
           [404 {:error "not-found" :message "No such endpoint."}])))
      (task/then answer-of)
      (task/recover error-answer)))

(defn event-data
  "What a job event sends: (jsonable event)."
  [event]
  (jsonable event))
