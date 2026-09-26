;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns ds-spike.ui
  "Datastar spike: the wmark local UI as server-rendered HTML fragments and
  SSE, over the unchanged Core API and the existing security middleware.
  Throwaway code for the architecture assessment -- not production."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ds-spike.html :as h]
            [org.httpkit.server :as hk]
            [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
            [starfederation.datastar.clojure.api :as d*]
            [watermark.app :as app]
            [watermark.config :as config]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.core.jobs :as jobs]
            [watermark.server.routes :as routes]
            [watermark.server.security :as security])
  (:import (java.io InputStream InputStreamReader)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest SecureRandom)
           (java.time LocalTime)
           (java.time.temporal ChronoUnit)
           (java.util Base64)
           (java.util.concurrent Executors ScheduledExecutorService ScheduledFuture TimeUnit))
  (:gen-class))

(set! *warn-on-reflection* true)

(def ctx {:tenant "local" :user "local"})
(defonce stats (atom {:events 0 :bytes 0 :streams-opened 0}))
(defonce streams (atom #{}))
(defonce ^ScheduledExecutorService ticker (Executors/newSingleThreadScheduledExecutor))
(defonce clip (atom nil))

;; ---------------------------------------------------------------------------
;; Security additions a server-rendered UI needs

(defn- nonce []
  (let [b (byte-array 18)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- page-csp [n]
  ;; no 'unsafe-eval': Datastar's CSP mode compiles expressions into
  ;; nonce-carrying <script> elements instead of calling Function()
  (str "default-src 'self'; script-src 'self'" (when n (str " 'nonce-" n "'"))
       "; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'"
       "; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))

(defn- authed?
  "Server-rendered pages carry data, so they need the token check that the
  JSON API applies to /api/* (here: the session cookie)."
  [req ^String token]
  (let [c (some->> (get-in req [:headers "cookie"])
                   (re-find #"(?:^|;\s*)wmark_session=([A-Za-z0-9_-]+)") second)]
    (and c (MessageDigest/isEqual (.getBytes ^String c StandardCharsets/UTF_8)
                                  (.getBytes token StandardCharsets/UTF_8)))))

;; ---------------------------------------------------------------------------
;; Signals <-> Core API requests

(defn- signals [req]
  (let [s (d*/get-signals req)]
    (cond (instance? InputStream s) (json/read (InputStreamReader. ^InputStream s "UTF-8") :key-fn keyword)
          (string? s)               (json/read-str s :key-fn keyword)
          :else                     {})))

(defn- blank? [x] (str/blank? (str x)))

(defn- overrides [{:keys [anchor opacity text]}]
  (cond-> {}
    (not (blank? anchor))  (assoc-in [:logo :anchor] (keyword anchor))
    (not (blank? opacity)) (assoc-in [:logo :opacity] (or (parse-double (str/trim (str opacity)))
                                                          (str opacity)))   ; let the schema say why
    (not (blank? text))    (assoc :texts [{:mode :continuous :content (str text)}])))

(defn- request [{:keys [profile] :as s}]
  {:profile (when-not (blank? profile) profile) :settings (overrides s)})

;; ---------------------------------------------------------------------------
;; Views

(def ^:private fields
  [[[:logo :path] "Logo"] [[:logo :anchor] "Anchor"] [[:logo :opacity] "Opacity"]
   [[:texts] "Text layers"] [[:output :dir] "Output folder"]])

(defn- origin [r path]
  (case (get-in r [:provenance path])
    :overrides "set here"
    :profile   (if (= :latest (get-in r [:base :kind]))
                 "from your last run"
                 (str "from profile " (get-in r [:base :name])))
    "built-in default"))

(defn- show [v]
  (cond (keyword? v) (name v)
        (and (vector? v) (empty? v)) "none"
        (vector? v) (str/join "; " (map #(str (name (:mode %)) ": " (:content %)) v))
        :else (str v)))

(defn- effective [r]
  [:div {:id "effective"}
   [:table
    (for [[path label] fields]
      [:tr {:id (str "f-" (str/join "-" (map name path)))}
       [:th label] [:td [:code (show (get-in (:settings r) path))]] [:td [:em (origin r path)]]])]
   (when (seq (:locked r))
     [:p {:class "locked"} "Needs Pro: " (str/join ", " (map #(subs (str %) 1) (:locked r)))])])

(defn- flash [kind msg] [:div {:id "flash" :class (name kind)} msg])

(defn- job-row [{:keys [id state progress error]}]
  (let [f (cond (= state :done) 1.0 (:fraction progress) (double (:fraction progress)) :else 0.0)]
    [:div {:id (str "job-" id) :class (str "job " (name state))}
     [:code (subs id 0 8)] " " [:b (name state)] " "
     [:progress {:max "1" :value (format "%.3f" f)}] " " (str (Math/round (* 100.0 f)) "%")
     (when error [:span {:class "err"} " " (:message error)])]))

(defn- log-line [{:keys [id state]}]
  [:div (str (.truncatedTo (LocalTime/now) ChronoUnit/SECONDS) "  " (subs id 0 8) "  " (name state))])

(def ^:private anchors
  [:top-left :top-center :top-right :center-left :center :center-right
   :bottom-left :bottom-center :bottom-right])

(defn- page [sys n]
  (let [r        (api/resolve-settings sys ctx {})
        profiles (api/list-profiles sys ctx)]
    (str "<!doctype html>"
         (h/html
          [:html (cond-> {:lang "en"} n (assoc "data-nonce" n))
           [:head [:meta {:charset "utf-8"}] [:title "wmark (Datastar spike)"]
            [:style "body{font:14px system-ui;margin:24px;max-width:900px}label{display:block;margin:6px 0}
                     th{text-align:left;padding-right:12px}td{padding-right:12px}.error{color:#b00020}
                     .ok{color:#1b5e20}.job{margin:4px 0}progress{width:240px}pre{background:#f4f4f4;padding:8px;min-height:3em}"]
            [:script {:type "module" :src "/datastar.js"}]]
           [:body {"data-signals" (json/write-str {:profile "" :anchor "" :opacity "" :text ""
                                                   :savename "" :rev 0})}
            [:h1 "wmark"]
            [:form {:id "settings" "data-on:input__debounce.200ms" "@post('/ui/resolve')"}
             [:label "Base profile "
              [:select {:id "profile" "data-bind:profile" true}
               [:option {:value ""} "latest (your last run)"]
               (for [{:keys [name slug]} profiles :when (not= slug "latest")]
                 [:option {:value slug} name])]]
             [:label "Anchor "
              [:select {:id "anchor" "data-bind:anchor" true}
               [:option {:value ""} "(inherit)"]
               (for [a anchors] [:option {:value (name a)} (name a)])]]
             [:label "Opacity " [:input {:id "opacity" "data-bind:opacity" true :placeholder "inherit"}]]
             [:label "Warning text " [:input {:id "text" "data-bind:text" true :placeholder "inherit"}]]
             [:button {:id "render" :type "button" "data-on:click" "@post('/ui/jobs')"
                       "data-indicator:busy" true "data-attr:disabled" "$busy"} "Render the test clip"]]
            (flash :none "")
            [:h3 "Effective settings"]
            (effective r)
            [:h3 "Save as profile"]
            [:input {:id "savename" "data-bind:savename" true :placeholder "Profile name"}]
            [:button {:id "save" :type "button" "data-on:click" "@post('/ui/profiles/save')"} "Save"]
            [:span " rev " [:span {:id "rev" "data-text" "$rev"}]]
            [:section {:id "queue"
                       "data-init" "@get('/ui/jobs/stream', {retry: 'always', retryMaxCount: 1000})"}
             [:h3 "Render queue"]
             [:div {:id "jobs"}]
             [:pre {:id "log"}]]]]))))

;; ---------------------------------------------------------------------------
;; SSE plumbing

(defn- send! [sse ^String html opts]
  (swap! stats #(-> % (update :events inc) (update :bytes + (count html))))
  (d*/patch-elements! sse html opts))

(defn- one-shot [req f]
  (hk-gen/->sse-response req {hk-gen/on-open (fn [sse] (try (f sse) (finally (d*/close-sse! sse))))}))

(defn- fail [sse ^Exception e] (send! sse (h/html (flash :error (ex-message e))) {}))

(defn- resolve-h [sys req]
  (let [s (signals req)]
    (one-shot req (fn [sse]
                    (try (send! sse (h/html (effective (api/resolve-settings sys ctx (request s)))) {})
                         (send! sse (h/html (flash :none "")) {})
                         (catch clojure.lang.ExceptionInfo e (fail sse e)))))))

(defn- submit-h [sys req]
  (let [s (signals req)]
    (one-shot req (fn [sse]
                    (try (let [job (api/submit-job! sys ctx (assoc (request s) :inputs [@clip]))]
                           (send! sse (h/html (flash :ok (str "Queued job " (subs (:id job) 0 8) ".")))
                                  {}))
                         (catch clojure.lang.ExceptionInfo e (fail sse e)))))))

(defn- save-h [sys req]
  (let [{:keys [savename rev] :as s} (signals req)]
    (one-shot req (fn [sse]
                    (try (let [settings (overrides s)
                               p (if (zero? (long (or rev 0)))
                                   (api/create-profile! sys ctx savename settings)
                                   (api/save-profile! sys ctx savename settings {:if-rev (long rev)}))]
                           (d*/patch-signals! sse (json/write-str {:rev (:profile/rev p)}))
                           (send! sse (h/html (flash :ok (str "Saved \"" (:profile/name p) "\"."))) {}))
                         (catch clojure.lang.ExceptionInfo e (fail sse e)))))))

(defn- stream-h
  "The render queue: full state on every (re)connect, then row updates
  coalesced to at most 10 per second per connection, whatever FFmpeg emits."
  [sys req]
  (let [k       (Object.)
        pending (atom {})
        seen    (atom #{})
        states  (atom {})
        task    (atom nil)]
    (hk-gen/->sse-response
     req
     {hk-gen/on-open
      (fn [sse]
        (swap! streams conj sse)
        (swap! stats update :streams-opened inc)
        (jobs/subscribe! (:jobs sys) k (fn [{:keys [job]}] (when job (swap! pending assoc (:id job) job))))
        (let [all (api/list-jobs sys ctx)]
          (send! sse (h/html [:div {:id "jobs"} (map job-row all)]) {})
          (reset! seen (set (map :id all)))
          (reset! states (into {} (map (juxt :id :state)) all)))
        (reset! task
                (.scheduleAtFixedRate
                 ticker
                 (fn []
                   (try
                     (doseq [[id job] (first (swap-vals! pending (constantly {})))]
                       (if (@seen id)
                         (send! sse (h/html (job-row job)) {})
                         (do (swap! seen conj id)
                             (send! sse (h/html (job-row job)) {d*/selector "#jobs" d*/patch-mode d*/pm-append})))
                       (when (not= (:state job) (@states id))
                         (swap! states assoc id (:state job))
                         (send! sse (h/html (log-line job)) {d*/selector "#log" d*/patch-mode d*/pm-append})))
                     (catch Throwable _ nil)))
                 100 100 TimeUnit/MILLISECONDS)))
      hk-gen/on-close
      (fn [sse _]
        (swap! streams disj sse)
        (jobs/unsubscribe! (:jobs sys) k)
        (some-> ^ScheduledFuture @task (.cancel false)))})))

;; ---------------------------------------------------------------------------
;; Routing

(defn- js [^String dir]
  {:status  200
   :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "max-age=31536000, immutable"
             "Content-Security-Policy" "default-src 'none'"}
   :body    (Files/readAllBytes (Paths/get dir (into-array String ["datastar.js"])))})

(defn ui-handler [sys token public-dir]
  (fn [req]
    (let [m (:request-method req) u (:uri req)]
      (cond
        (and (= m :get) (= u "/datastar.js")) (js public-dir)
        (not (or (= u "/") (str/starts-with? u "/ui/"))) nil
        (not (authed? req token))
        {:status 401 :headers {"Content-Type" "text/plain"} :body "Open wmark from the link it printed."}
        (and (= m :get) (= u "/"))
        (let [n (when-not (= "nonce=off" (:query-string req)) (nonce))]
          {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"
                                 "Content-Security-Policy" (page-csp n)}
           :body (page sys n)})
        (and (= m :post) (= u "/ui/resolve"))        (resolve-h sys req)
        (and (= m :post) (= u "/ui/jobs"))           (submit-h sys req)
        (and (= m :post) (= u "/ui/profiles/save"))  (save-h sys req)
        (and (= m :get) (= u "/ui/jobs/stream"))     (stream-h sys req)
        (and (= m :post) (= u "/ui/debug/kick"))     (do (run! d*/close-sse! @streams) {:status 204})
        (and (= m :get) (= u "/ui/debug/stats"))     {:status 200 :headers {"Content-Type" "application/json"}
                                                      :body (json/write-str @stats)}
        :else {:status 404 :body "Not found"}))))

(defn -main [home clip-path logo out-dir public-dir]
  (let [sys   (app/with-jobs (app/system {:edition :community :entitlements-fn (fn [_] (features/community))}
                                         {:home home}))
        token (security/new-token)
        port  (promise)
        _     (reset! clip clip-path)
        _     (config/record-latest! ((:profiles-for sys) ctx)
                                     {:logo {:path logo :anchor :top-left} :output {:dir out-dir :overwrite? true}}
                                     nil)
        ui    (ui-handler sys token public-dir)
        api   (routes/api-handler sys)
        srv   (hk/run-server (security/wrap-security (fn [req] (or (ui req) (api req) {:status 404 :body ""}))
                                                     {:token token
                                                      :host-allowed? #(contains? (security/loopback-hosts @port) %)})
                             {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (deliver port (hk/server-port srv))
    (println (json/write-str {:url (str "http://127.0.0.1:" @port) :token token} :escape-slash false))
    (flush)
    @(promise)))
