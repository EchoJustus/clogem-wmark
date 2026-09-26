;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.sse
  "Datastar's server-sent events, written directly.

  The protocol is small: an event names a watcher and carries data lines.
  Writing it here instead of depending on the Clojure SDK (still a release
  candidate) keeps the web UI free of third-party code apart from the
  vendored datastar.js. The official SDK test cases, vendored under
  web/test/datastar-sdk-cases, check this namespace byte for byte (modulo
  data-line order, which the protocol leaves free).

    event: datastar-patch-elements        event: datastar-patch-signals
    data: selector #jobs                  data: onlyIfMissing true
    data: mode append                     data: signals {\"rev\":3}
    data: elements <li>...</li>

  Not implemented on purpose: execute-script. This UI never sends code to
  the browser.

  Transport: one-shot answers are plain Ring responses whose body is a string
  of events (any server). Long-lived streams use http-kit's async channels."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as hk])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(def default-retry-ms
  "The client's reconnect delay unless an event says otherwise."
  1000)

(defn- data-lines [field ^String s]
  (when-not (str/blank? s)
    (map #(str field " " %) (str/split-lines s))))

(defn- event ^String [type {:keys [id retry]} lines]
  (str "event: " type "\n"
       (when id (str "id: " id "\n"))
       (when (and retry (not= (long retry) default-retry-ms)) (str "retry: " retry "\n"))
       (apply str (map #(str "data: " % "\n") lines))
       "\n"))

(defn patch-elements
  "Morph `html` into the page. Without :selector, elements replace the
  elements with the same id (mode :outer). Options: :selector, :mode (:outer
  :inner :replace :prepend :append :before :after :remove),
  :use-view-transition, :namespace (:svg :mathml), :id, :retry. `html` may
  be nil for :remove."
  (^String [html] (patch-elements html {}))
  (^String [html {:keys [selector mode use-view-transition namespace] :as opts}]
   (event "datastar-patch-elements" opts
          (concat (when selector [(str "selector " selector)])
                  (when (and mode (not= "outer" (name mode))) [(str "mode " (name mode))])
                  (when use-view-transition ["useViewTransition true"])
                  (when (and namespace (not= "html" (name namespace))) [(str "namespace " (name namespace))])
                  (data-lines "elements" html)))))

(defn patch-signals
  "Merge signals into the page (RFC 7386 merge patch: nil removes). `signals`
  is a map, written as JSON, or a JSON string. The browser parses it with
  JSON.parse, so user text is safe here, unlike in data-* attributes.
  Options: :only-if-missing, :id, :retry."
  (^String [signals] (patch-signals signals {}))
  (^String [signals {:keys [only-if-missing] :as opts}]
   (event "datastar-patch-signals" opts
          (concat (when only-if-missing ["onlyIfMissing true"])
                  (data-lines "signals" (if (string? signals)
                                          signals
                                          (json/write-str signals :escape-slash false)))))))

(def headers
  {"Content-Type"      "text/event-stream; charset=utf-8"
   "Cache-Control"     "no-store"
   "X-Accel-Buffering" "no"})          ; reverse proxies must not buffer the stream

(defn response
  "A complete answer made of `events` (strings from the functions above)."
  [& events]
  {:status 200 :headers headers :body (apply str events)})

(defn datastar-request?
  "Datastar marks its requests with this header. A cross-site form can't set
  it, so requiring it is a cheap extra CSRF guard."
  [req]
  (= "true" (get-in req [:headers "datastar-request"])))

(defn read-signals
  "The page's signals sent with a request: the JSON body, or for GET and
  DELETE the `datastar` query parameter. A map with string keys."
  [req]
  (let [raw (if (#{:get :delete} (:request-method req))
              (some->> (:query-string req)
                       (re-find #"(?:^|&)datastar=([^&]*)")
                       second
                       (#(URLDecoder/decode ^String % StandardCharsets/UTF_8)))
              (some-> (:body req) (io/reader :encoding "UTF-8") slurp))]
    (if (str/blank? raw)
      {}
      (let [v (try (json/read-str raw)
                   (catch Exception _
                     (throw (ex-info "The page sent malformed signals." {:wmark/error :invalid}))))]
        (if (map? v) v {})))))

;; ---------------------------------------------------------------------------
;; Long-lived streams (http-kit)

(defn stream
  "Hold the connection open as an event stream. `on-open` receives a send
  function (event-string -> boolean, false once the client is gone);
  `on-close` runs when the connection ends."
  [req {:keys [on-open on-close]}]
  (hk/as-channel
   req
   {:on-open  (fn [ch]
                ;; headers plus an SSE comment, so proxies and the browser
                ;; see the stream start immediately
                (when (hk/send! ch {:status 200 :headers headers :body ": wmark\n\n"} false)
                  (on-open (fn [ev] (hk/send! ch ev false)))))
    :on-close (fn [_ch _status] (when on-close (on-close)))}))
