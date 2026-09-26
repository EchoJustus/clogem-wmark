;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.security
  "Security for the local HTTP API.

  A server on 127.0.0.1 is still reachable by every web page the user visits
  (via fetch/form posts) and by DNS-rebinding attacks. So:
    * a random per-launch token guards /api/* and the built-in UI (its pages
      carry data) -- `Authorization: Bearer ..` for the CLI/TUI, an HttpOnly
      SameSite=Strict cookie for the browser;
    * the browser gets the token once, via `/?token=..`, which sets the
      cookie and redirects, dropping the token from the address bar;
    * the Host header must name the loopback interface (DNS rebinding);
    * mutating requests with a foreign Origin are refused (CSRF);
    * no CORS headers are ever sent."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def cookie-name "wmark_session")

(defn new-token
  "256-bit random token. Created at run time, never at build time."
  ^String []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- same-token? [^String a ^String b]
  (and a b (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                                  (.getBytes b StandardCharsets/UTF_8))))

(defn- cookie-token [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|;\\s*)" cookie-name "=([A-Za-z0-9_-]+)")))
           second))

(defn- bearer-token [req]
  (some->> (get-in req [:headers "authorization"]) (re-find #"^Bearer\s+(\S+)$") second))

(defn- query-token [req]
  (some->> (:query-string req) (re-find #"(?:^|&)token=([A-Za-z0-9_-]+)") second))

(defn loopback-hosts
  "Host header values accepted when bound to loopback."
  [port]
  #{(str "127.0.0.1:" port) (str "localhost:" port) (str "[::1]:" port)})

(defn- mutating? [req] (not (#{:get :head :options} (:request-method req))))

(defn- forbidden [status msg]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body msg})

(defn api-path? [uri] (str/starts-with? (str uri) "/api/"))

(def ^:private sign-in-page
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>wmark</title></head>"
       "<body><h1>wmark</h1><p>This browser isn't signed in. Open wmark from the link it printed "
       "when it started: that link signs this browser in.</p></body></html>"))

(defn- unauthorized [uri]
  (if (api-path? uri)
    {:status  401
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body    "{\"error\":\"unauthorized\",\"message\":\"Open wmark from the link it printed, or send the Bearer token.\"}"}
    {:status  401
     :headers {"Content-Type"            "text/html; charset=utf-8"
               "Content-Security-Policy" "default-src 'none'"
               "Cache-Control"           "no-store"}
     :body    sign-in-page}))

(defn wrap-security
  "`host-allowed?` is a predicate on the Host header; nil disables the check
  (explicit non-loopback binds, e.g. behind a reverse proxy). `protected?`
  says which paths need the token: /api/* by default; the desktop server
  adds the built-in UI's paths."
  [handler {:keys [token host-allowed? protected?] :or {protected? api-path?}}]
  (fn [req]
    (let [host   (get-in req [:headers "host"])
          origin (get-in req [:headers "origin"])]
      (cond
        (and host-allowed? (not (host-allowed? host)))
        (forbidden 421 "Unexpected Host header.")

        ;; token bootstrap: /?token=.. -> cookie + redirect to a clean URL
        (and (= "/" (:uri req)) (same-token? (query-token req) token))
        {:status  303
         :headers {"Location"      "/"
                   "Set-Cookie"    (str cookie-name "=" token "; Path=/; HttpOnly; SameSite=Strict")
                   "Cache-Control" "no-store"}}

        (and (mutating? req)
             (some? origin)
             (let [origin-host (str/replace origin #"^https?://" "")]
               (not (if host-allowed? (host-allowed? origin-host) (= origin-host host)))))
        (forbidden 403 "Cross-origin request refused.")

        (and (protected? (:uri req))
             (not (or (same-token? (bearer-token req) token)
                      (same-token? (cookie-token req) token))))
        (unauthorized (:uri req))

        :else
        ;; the local user is the only user; the serverless backend's auth
        ;; middleware sets a real tenant and user here instead
        (handler (assoc req :wmark/ctx {:tenant "local" :user "local"}))))))
