;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.static
  "Static files: the built-in UI's assets from resources/public inside the
  binary (datastar.js, app.css), or a whole external UI from a directory
  (`--ui-dir`) -- like Clash's external-ui, so a UI can be developed with hot
  reload, or replaced entirely, without rebuilding the native binary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def ^:private content-types
  {"html"  "text/html; charset=utf-8"
   "js"    "text/javascript; charset=utf-8"
   "mjs"   "text/javascript; charset=utf-8"
   "css"   "text/css; charset=utf-8"
   "json"  "application/json; charset=utf-8"
   "map"   "application/json; charset=utf-8"
   "svg"   "image/svg+xml"
   "png"   "image/png"
   "ico"   "image/x-icon"
   "woff2" "font/woff2"
   "wasm"  "application/wasm"
   "txt"   "text/plain; charset=utf-8"})

(def ^:private security-headers
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy"        "no-referrer"
   "Content-Security-Policy"
   (str "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")})

(defn- ext [^String path]
  (when-let [^String e (second (re-find #"\.([A-Za-z0-9]+)$" path))]
    (.toLowerCase e Locale/ROOT)))

(defn- safe-rel
  "Relative asset path, or nil for anything that could escape the root."
  [^String uri]
  (let [rel (subs uri 1)]
    (when-not (re-find #"\.\.|\\|%|:|^/" rel) rel)))

(defn- from-dir [ui-dir rel]
  (let [root (.getCanonicalFile (io/file ui-dir))
        f    (.getCanonicalFile (io/file root ^String rel))]
    (when (and (.isFile f) (str/starts-with? (.getPath f) (str (.getPath root) File/separator)))
      f)))

(defn- from-resources [rel]
  (some-> (io/resource (str "public/" rel)) io/input-stream))

(defn- respond [rel body]
  {:status  200
   :headers (assoc security-headers
                   "Content-Type"  (content-types (ext rel) "application/octet-stream")
                   "Cache-Control" "no-cache")
   :body    body})

(defn handler
  "GET-only asset handler. For an external UI, paths without an extension
  fall back to its index.html so client-side routes survive a reload; the
  built-in UI has no index.html, so they simply don't match."
  [{:keys [ui-dir]}]
  (let [lookup (if ui-dir #(from-dir ui-dir %) from-resources)]
    (fn [{:keys [uri request-method]}]
      (when (#{:get :head} request-method)
        (when-let [rel (safe-rel uri)]
          (let [rel (if (or (= "" rel) (nil? (ext rel))) "index.html" rel)]
            (some->> (lookup rel) (respond rel))))))))
