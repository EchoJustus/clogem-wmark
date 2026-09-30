;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.routes
  "REST adapter: HTTP <-> the REST contract (watermark.core.rest), which maps
  each request to the Core API. No business logic lives here: this reads
  the request's JSON, waits for the contract's answer and writes it.

  Transport- and auth-neutral: a plain Ring handler that any server can host
  -- http-kit on the desktop (watermark.server.http adds loopback security
  and server-sent events), a function adapter in the serverless backend (with
  real authentication in front). The caller's identity arrives as
  (:wmark/ctx request), set by whichever auth middleware wraps this handler."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.core.api :as api]
            [watermark.core.rest :as rest]))

(set! *warn-on-reflection* true)

(def jsonable
  "Data as JSON-ready data (watermark.core.rest/jsonable)."
  rest/jsonable)

(defn json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8" "Cache-Control" "no-store"}
   :body    (json/write-str body)})

(defn- read-json [req]
  (when-let [body (:body req)]
    (let [s (slurp (io/reader body :encoding "UTF-8"))]
      (when-not (str/blank? s)
        (try (json/read-str s :key-fn keyword)
             (catch Exception _
               (throw (ex-info "Request body is not valid JSON." {:wmark/error :invalid}))))))))

(def routes
  "The contract's routes (watermark.core.rest/routes)."
  rest/routes)

(defn api-handler
  "Ring handler for /api/*; nil for anything else."
  [sys]
  (fn [req]
    (when (str/starts-with? (str (:uri req)) "/api/")
      (let [ctx    (or (:wmark/ctx req) {:tenant "local" :user "local"})
            answer (api/await (rest/respond sys ctx (:request-method req) (:uri req) #(read-json req)))]
        (if-let [file (:file answer)]
          {:status  (:status answer)
           :headers {"Content-Type" (:content-type answer) "Cache-Control" "private, max-age=3600"
                     "X-Content-Type-Options" "nosniff"}
           :body    (java.io.File. ^String file)}
          (json-response (:status answer) (:body answer)))))))
