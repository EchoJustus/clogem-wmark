;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.routes
  "REST adapter: HTTP <-> watermark.core.api. No business logic lives here.

  Transport- and auth-neutral: a plain Ring handler that any server can host
  -- http-kit on the desktop (watermark.server.http adds loopback security
  and server-sent events), a function adapter in the serverless backend (with
  real authentication in front). The caller's identity arrives as
  (:wmark/ctx request), set by whichever auth middleware wraps this handler.

  Routes are plain data; matching is a tiny segment matcher. A router
  library would work too, but this keeps the native image small and the
  reflection surface at zero."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [watermark.core.api :as api]
            [watermark.core.schema :as schema])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; JSON

(defn- kw->str [k] (if (qualified-keyword? k) (subs (str k) 1) (name k)))

(defn jsonable
  "Keywords -> strings *with* their namespace (data.json would write
  :text.mode/subliminal as \"subliminal\"); provenance paths -> \"logo.anchor\"."
  [x]
  (walk/postwalk
   (fn [v]
     (cond
       (keyword? v) (kw->str v)
       (and (map? v) (some vector? (keys v)))
       (into {} (map (fn [[k x]] [(if (vector? k) (str/join "." (map #(if (keyword? %) (name %) (str %)) k)) k) x])) v)
       :else v))
   x))

(defn json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
   :body    (json/write-str (jsonable body))})

(defn- read-json [req]
  (when-let [body (:body req)]
    (let [s (slurp (io/reader body :encoding "UTF-8"))]
      (when-not (str/blank? s)
        (try (json/read-str s :key-fn keyword)
             (catch Exception _
               (throw (ex-info "Request body is not valid JSON." {:wmark/error :invalid}))))))))

(defn- settings-in
  "JSON settings -> Clojure settings (string enums -> keywords, ints -> doubles)."
  [m]
  (some-> m schema/decode-json))

;; ---------------------------------------------------------------------------
;; Handlers: (fn [sys ctx req params body] -> result)
;;   map            -> 200 JSON
;;   [status body]  -> that status, JSON
;;   {::raw resp}   -> a Ring response as-is. Explicit, because a domain map
;;                     may legitimately contain :status (see /health).

(defn- h-health   [sys _ _ _ _] (api/health sys))
(defn- h-doctor   [sys _ _ _ _] (api/diagnose sys))
(defn- h-features [sys ctx _ _ _] (api/features sys ctx))
(defn- h-schema   [sys _ _ _ _] (api/settings-schema sys))

(defn- h-list-profiles [sys ctx _ _ _] {:profiles (api/list-profiles sys ctx)})
(defn- h-get-profile [sys ctx _ {:keys [name]} _] (api/get-profile sys ctx name))
(defn- h-create-profile [sys ctx _ _ {:keys [name settings]}]
  [201 (api/create-profile! sys ctx name (settings-in settings))])
(defn- h-save-profile [sys ctx _ {:keys [name]} body]
  (api/save-profile! sys ctx name (settings-in (:settings body))
                     {:overwrite? (true? (:overwrite body))
                      :if-rev     (get body (keyword "if-rev"))}))
(defn- h-rename-profile [sys ctx _ {:keys [name]} {:keys [to]}] (api/rename-profile! sys ctx name to))
(defn- h-copy-profile [sys ctx _ {:keys [name]} {:keys [to]}] [201 (api/copy-profile! sys ctx name to)])
(defn- h-delete-profile [sys ctx _ {:keys [name]} _] (api/delete-profile! sys ctx name) {:deleted name})

(defn- request-of [{:keys [profile clean settings inputs]}]
  {:profile  (if clean :none profile)
   :settings (settings-in settings)
   :inputs   inputs})

(defn- h-resolve [sys ctx _ _ body] (api/resolve-settings sys ctx (request-of body)))
(defn- h-plan [sys ctx _ _ body] (api/plan-batch sys ctx (request-of body)))
(defn- h-submit [sys ctx _ _ body] [202 (api/submit-job! sys ctx (request-of body))])
(defn- h-jobs [sys ctx _ _ _] {:jobs (api/list-jobs sys ctx)})
(defn- h-cancel [sys ctx _ {:keys [id]} _] {:cancelled (api/cancel-job! sys ctx id)})

(def routes
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
   [:post   "/api/v1/resolve"               h-resolve]           ; {profile?, clean?, settings?}
   [:post   "/api/v1/plan"                  h-plan]              ; + inputs: dry run
   [:post   "/api/v1/jobs"                  h-submit]            ; {inputs, profile?, settings?}
   [:get    "/api/v1/jobs"                  h-jobs]
   [:delete "/api/v1/jobs/:id"              h-cancel]])

;; ---------------------------------------------------------------------------
;; Matching

(defn- decode-segment [^String s]
  ;; path segments: '+' is literal, only %XX is decoded
  (URLDecoder/decode (str/replace s "+" "%2B") StandardCharsets/UTF_8))

(defn- split-path [uri] (vec (remove str/blank? (str/split (str uri) #"/"))))

(def ^:private compiled
  (mapv (fn [[method path handler]] [method (split-path path) handler]) routes))

(defn- match [method segments]
  (some (fn [[m pattern handler]]
          (when (and (= m method) (= (count pattern) (count segments)))
            (when-let [params (reduce (fn [acc [p s]]
                                        (cond (str/starts-with? p ":") (assoc acc (keyword (subs p 1)) (decode-segment s))
                                              (= p s) acc
                                              :else (reduced nil)))
                                      {} (map vector pattern segments))]
              [handler params])))
        compiled))

(def ^:private status-of
  {:invalid 422 :unsupported 422 :not-found 404 :conflict 409 :feature-locked 402
   :feature-unavailable 402 :unavailable 503})

(defn api-handler
  "Ring handler for /api/*; nil for anything else."
  [sys]
  (fn [req]
    (when (str/starts-with? (str (:uri req)) "/api/")
      (let [ctx (or (:wmark/ctx req) {:tenant "local" :user "local"})]
        (try
          (if-let [[handler params] (match (:request-method req) (split-path (:uri req)))]
            (let [result (handler sys ctx req params (read-json req))]
              (cond
                (and (map? result) (contains? result ::raw)) (::raw result)
                (vector? result) (json-response (first result) (second result))
                :else            (json-response 200 result)))
            (json-response 404 {:error "not-found" :message "No such endpoint."}))
          (catch clojure.lang.ExceptionInfo e
            (let [{kind :wmark/error :as data} (ex-data e)]
              (if kind
                (json-response (status-of kind 400)
                               (merge {:error kind :message (ex-message e)}
                                      (select-keys data [:errors :features :field :name :existing :missing :reason])))
                (json-response 500 {:error "internal" :message (ex-message e)}))))
          (catch Exception e
            (json-response 500 {:error "internal" :message (str (class e) ": " (ex-message e))})))))))
