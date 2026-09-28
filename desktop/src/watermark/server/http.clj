;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.http
  "http-kit lifecycle for the desktop editions. Binds to loopback by default;
  binding elsewhere is an explicit choice (e.g. a LAN render box) and still
  requires the token. Adds what only a long-lived local server can offer:
  server-sent job events, and the runtime file local clients discover.

  What it serves:
    /api/*        the REST API (watermark.server.routes), plus /api/v1/events
    / and /ui/*   the built-in Datastar UI (watermark.web.handler)
    other paths   static assets from resources/public (datastar.js, app.css)
  With --ui-dir, an external UI replaces the built-in one entirely and talks
  to the REST API, like Clash's external-ui."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [org.httpkit.server :as hk]
            [watermark.core.api :as api]
            [watermark.server.routes :as routes]
            [watermark.server.security :as security]
            [watermark.server.static :as static]
            [watermark.web.handler :as web])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute PosixFilePermissions)))

(set! *warn-on-reflection* true)

(def ^:private loopback #{"127.0.0.1" "localhost" "::1"})

(defn- not-found [_]
  {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Not found"})

(defn- runtime-file ^Path [^Path home] (.resolve home "runtime/server.edn"))

(defn- write-runtime-file!
  "Discovery file for local clients (scripts, GUI shells): URL + token, owner-only.
  Same idea as Jupyter's runtime files."
  [^Path home info]
  (let [f (runtime-file home)]
    (Files/createDirectories (.getParent f) (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str f) (pr-str info) :encoding "UTF-8")
    (when (contains? (.supportedFileAttributeViews (.getFileSystem f)) "posix")
      (Files/setPosixFilePermissions f (PosixFilePermissions/fromString "rw-------")))))

(defn- events-handler
  "GET /api/v1/events: job state and progress as server-sent events.
  EventSource can't send headers, which is why the SPA authenticates with
  the session cookie."
  [sys]
  (fn [req]
    (when (and (= :get (:request-method req)) (= "/api/v1/events" (:uri req)))
      (hk/as-channel
       req
       {:on-open  (fn [ch]
                    (hk/send! ch {:status  200
                                  :headers {"Content-Type"  "text/event-stream; charset=utf-8"
                                            "Cache-Control" "no-store"}
                                  :body    ": connected\n\n"}
                              false)
                    (api/subscribe-jobs! sys (:wmark/ctx req) ch
                                         (fn [event]
                                           (hk/send! ch (str "data: " (json/write-str (routes/jsonable event)) "\n\n")
                                                     false))))
        :on-close (fn [ch _] (api/unsubscribe-jobs! sys (:wmark/ctx req) ch))}))))

(defn start!
  "Start serving. Returns {:server .. :url .. :token ..}; the URL carries the
  token for the browser's one-time cookie bootstrap."
  [sys {:keys [host port ui-dir] :or {host "127.0.0.1" port 0}}]
  (let [token   (security/new-token)
        events  (events-handler sys)
        api     (routes/api-handler sys)
        ui      (when-not ui-dir (web/handler sys))
        assets  (static/handler {:ui-dir ui-dir})
        started (promise)
        handler (security/wrap-security
                 (fn [req] (or (events req) (api req) (when ui (ui req)) (assets req) (not-found req)))
                 {:token token
                  ;; the built-in UI's pages carry data, so they need the token
                  ;; too; an external UI's files are public, its API calls aren't
                  :protected? (if ui
                                #(or (security/api-path? %) (web/ui-path? %))
                                security/api-path?)
                  ;; the Host allow-list needs the real port, known only after bind
                  :host-allowed? (when (loopback host)
                                   (fn [h] (contains? (security/loopback-hosts @started) h)))})
        server  (hk/run-server handler {:ip host :port port
                                        :legacy-return-value? false
                                        :server-header nil
                                        :max-body (* 2 1024 1024)})
        bound   (hk/server-port server)
        url     (str "http://" (if (= host "::1") "[::1]" (if (loopback host) "127.0.0.1" host)) ":" bound)]
    (deliver started bound)
    (write-runtime-file! (:home sys) {:url url :token token
                                      :pid (.pid (ProcessHandle/current))})
    {:server server :url url :token token :port bound}))

(defn stop! [sys {:keys [server]}]
  (when server @(hk/server-stop! server {:timeout 2000}))
  (Files/deleteIfExists (runtime-file (:home sys))))

(defn read-runtime-file
  "Connection info of a running server, for local clients; nil if none."
  [^Path home]
  (let [f (runtime-file home)]
    (when (Files/exists f (make-array LinkOption 0))
      (edn/read-string (slurp (str f) :encoding "UTF-8")))))
