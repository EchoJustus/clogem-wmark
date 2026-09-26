;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.handler
  "The built-in web UI: a server-rendered page plus Datastar endpoints, over
  the Core API. The desktop server mounts it at / and /ui/*; a hosted
  dashboard can mount it behind its own authentication.

  The page is rendered once; every interaction is a request under /ui/ that
  answers with events patching fragments (by id) and signals. The render
  queue is one long-lived stream per visible tab: the full queue on every
  (re)connect, then row updates coalesced to at most ten per second.

  Security, beyond the transport's (token cookie, Host and Origin checks):
  * a fresh CSP nonce per page; Datastar's CSP mode needs no unsafe-eval;
  * /ui/* requires the Datastar-Request header (cross-site forms can't set it);
  * user text only ever reaches the browser escaped or via JSON signals
    (see watermark.web.views)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [watermark.core.api :as api]
            [watermark.core.schema :as schema]
            [watermark.web.html :as h]
            [watermark.web.sse :as sse]
            [watermark.web.views :as v])
  (:import (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.security SecureRandom)
           (java.time LocalTime)
           (java.time.temporal ChronoUnit)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn- nonce []
  (let [b (byte-array 18)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn page-csp
  "No inline script, no eval: scripts come from this origin or carry the
  page's nonce (Datastar compiles expressions into such scripts)."
  [nonce]
  (str "default-src 'self'; script-src 'self' 'nonce-" nonce "'; style-src 'self'; "
       "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
       "form-action 'self'"))

(defn- ctx-of [req] (or (:wmark/ctx req) {:tenant "local" :user "local"}))

(defn- titles [sys ctx]
  (into {} (map (juxt :id :title)) (:features (api/features sys ctx))))

(defn- first-choice
  "The profile to show: `latest` when it exists, else the first one."
  [profiles]
  (or (some #(when (= "latest" (:slug %)) (:slug %)) profiles)
      (:slug (first (remove :error profiles)))))

(defn- flat-errors
  "[[path messages]] from humanized schema errors (maps and vectors of them)."
  ([errors] (flat-errors [] errors))
  ([prefix x]
   (cond (map? x)        (mapcat (fn [[k v]] (flat-errors (conj prefix k) v)) x)
         (sequential? x) (if (and (seq x) (every? string? x))
                           [[prefix x]]
                           (mapcat #(flat-errors prefix %) x))
         (string? x)     [[prefix [x]]]
         :else           [])))

(defn- error-text [^Exception e]
  (let [detail (->> (flat-errors (:errors (ex-data e)))
                    (take 3)
                    (map (fn [[path msgs]]
                           (str (str/join "." (map #(if (keyword? %) (name %) (str %)) path))
                                ": " (str/join ", " msgs))))
                    (str/join "; "))]
    (str (ex-message e) (when (seq detail) (str " " detail)))))

;; ---------------------------------------------------------------------------
;; Fragments for "show this profile"

(defn- selection-events
  "Signals and fragments that make `slug` (or nothing) the selected profile."
  [sys ctx slug & {:keys [msg]}]
  (let [profiles (api/list-profiles sys ctx)
        doc      (when slug (api/get-profile sys ctx slug))
        slug     (:profile/slug doc)]
    [(sse/patch-signals {:rev      (or (:profile/rev doc) 0)
                         :settings (if doc (v/settings-text (:settings doc)) "")
                         :name     ""})
     (sse/patch-elements (h/html (v/profile-list profiles slug)))
     (sse/patch-elements (h/html (v/inspector-head doc)))
     (sse/patch-elements (h/html (v/editor doc)))
     (sse/patch-elements (h/html (if doc
                                   (v/effective (api/resolve-settings sys ctx {:profile slug}) (titles sys ctx) false)
                                   (v/effective))))
     (sse/patch-elements (h/html (v/run-button slug)))
     (sse/patch-elements (h/html (apply v/message (or msg [nil nil]))))]))

(defn- say [kind text] (sse/patch-elements (h/html (v/message kind text))))

;; ---------------------------------------------------------------------------
;; Handlers: (fn [env req params] -> Ring response)

(defn- page [{:keys [sys]} req _]
  (let [ctx      (ctx-of req)
        n        (nonce)
        profiles (api/list-profiles sys ctx)
        slug     (first-choice profiles)
        doc      (when slug (api/get-profile sys ctx slug))]
    {:status  200
     :headers {"Content-Type"            "text/html; charset=utf-8"
               "Cache-Control"           "no-store"
               "Content-Security-Policy" (page-csp n)
               "X-Content-Type-Options"  "nosniff"
               "Referrer-Policy"         "no-referrer"}
     :body    (h/document (v/page {:nonce    n
                                   :health   (api/health sys)
                                   :profiles profiles
                                   :doc      doc
                                   :resolved (when doc (api/resolve-settings sys ctx {:profile slug}))
                                   :titles   (titles sys ctx)
                                   :jobs     (api/list-jobs sys ctx)}))}))

(defn- select [{:keys [sys]} req {:keys [slug]}]
  (apply sse/response (selection-events sys (ctx-of req) slug)))

(defn- create [{:keys [sys]} req _]
  (let [ctx  (ctx-of req)
        name (str/trim (str (get (sse/read-signals req) "newname")))
        doc  (api/create-profile! sys ctx name {})]
    (apply sse/response (sse/patch-signals {:newname ""})
           (selection-events sys ctx (:profile/slug doc)
                             :msg [:ok (str "Created “" (:profile/name doc) "”.")]))))

(defn- parse-settings [text]
  (let [m (try (json/read-str (str text) :key-fn keyword)
               (catch Exception e
                 (throw (ex-info (str "The settings aren't valid JSON: " (ex-message e)) {:wmark/error :invalid}))))]
    (when-not (map? m)
      (throw (ex-info "The settings must be a JSON object." {:wmark/error :invalid})))
    (schema/decode-json m)))

(defn- save [{:keys [sys]} req {:keys [slug]}]
  (let [ctx     (ctx-of req)
        signals (sse/read-signals req)
        rev     (get signals "rev")
        doc     (api/save-profile! sys ctx slug (parse-settings (get signals "settings"))
                                   {:if-rev (when (and (number? rev) (pos? (long rev))) (long rev))})]
    (sse/response
     (sse/patch-signals {:rev (:profile/rev doc)})
     (sse/patch-elements (h/html (v/profile-list (api/list-profiles sys ctx) (:profile/slug doc))))
     (sse/patch-elements (h/html (v/effective (api/resolve-settings sys ctx {:profile slug}) (titles sys ctx) false)))
     (say :ok (str "Saved “" (:profile/name doc) "”.")))))

(defn- rename-or-copy [f verb {:keys [sys]} req {:keys [slug]}]
  (let [ctx (ctx-of req)
        to  (str/trim (str (get (sse/read-signals req) "name")))
        doc (f sys ctx slug to)]
    (apply sse/response (selection-events sys ctx (:profile/slug doc)
                                          :msg [:ok (str verb " “" (:profile/name doc) "”.")]))))

(defn- delete [{:keys [sys]} req {:keys [slug]}]
  (let [ctx (ctx-of req)]
    (api/delete-profile! sys ctx slug)
    (apply sse/response (selection-events sys ctx (first-choice (api/list-profiles sys ctx))
                                          :msg [:ok "Deleted."]))))

(defn- preview
  "Live check of the editor's text: resolution with the unsaved settings."
  [{:keys [sys]} req _]
  (let [ctx  (ctx-of req)
        text (get (sse/read-signals req) "settings")]
    (try
      (let [r (api/resolve-settings sys ctx {:profile :none :settings (parse-settings text)})]
        (sse/response (sse/patch-elements (h/html (v/effective r (titles sys ctx) true)))
                      (say nil "")))
      (catch clojure.lang.ExceptionInfo e
        ;; typing produces invalid JSON all the time: say so, keep the table
        (sse/response (say :bad (error-text e)))))))

(defn- parse-inputs [text]
  (->> (str/split-lines (str text))
       (map #(-> % str/trim (str/replace #"^\"(.*)\"$" "$1")))   ; "Copy as path" adds quotes
       (remove str/blank?)
       vec))

(defn- submit [{:keys [sys]} req {:keys [slug]}]
  (let [ctx    (ctx-of req)
        inputs (parse-inputs (get (sse/read-signals req) "inputs"))]
    (if (empty? inputs)
      (sse/response (say :bad "Add at least one file path to the render queue."))
      (do (api/submit-job! sys ctx {:profile slug :inputs inputs})
          (sse/response
           (sse/patch-elements (h/html (v/profile-list (api/list-profiles sys ctx) slug)))
           (say :ok (str "Queued " (count inputs) (if (= 1 (count inputs)) " file" " files")
                         ". The settings were saved as “latest”.")))))))

(defn- cancel [{:keys [sys]} req {:keys [id]}]
  (api/cancel-job! sys (ctx-of req) id)
  (sse/response))

;; ---------------------------------------------------------------------------
;; The queue stream

(defn- queue-stream
  [{:keys [sys activity]} req _]
  (let [k       (Object.)
        pending (atom {})                ; job id -> latest state, not yet sent
        seen    (atom #{})
        busy    (atom false)
        closed  (atom false)
        lock    (Object.)
        send-to (atom nil)
        flush!  (fn []
                  (reset! busy false)
                  (let [batch (first (swap-vals! pending (constantly {})))]
                    (locking lock
                      (when-let [send! (and (not @closed) @send-to)]
                        (doseq [[id job] (dissoc batch ::activity)]
                          (if (@seen id)
                            (send! (sse/patch-elements (h/html (v/job-row job))))
                            (do (swap! seen conj id)
                                (send! (sse/patch-elements (h/html (v/job-row job))
                                                           {:selector "#jobs" :mode :prepend})))))
                        (when (::activity batch)
                          (send! (sse/patch-elements (h/html (v/activity @activity)))))))))
        later!  (fn []
                  (when (compare-and-set! busy false true)
                    (future (Thread/sleep 100)
                            (try (flush!)
                                 (catch Throwable t
                                   (binding [*out* *err*] (println "wmark UI stream:" (ex-message t))))))))
        on-job  (fn [{:keys [job]}]
                  (when job
                    (swap! pending assoc (:id job) job)
                    (later!)))
        watch   (fn [_ _ old new]
                  (when (not= old new)
                    (swap! pending assoc ::activity true)
                    (later!)))]
    (sse/stream
     req
     {:on-open  (fn [send!]
                  ;; subscribe first, then read and send the full state under
                  ;; the lock: an update is either in that state or flushed
                  ;; after it, and a duplicate just morphs to the same DOM
                  (api/subscribe-jobs! sys (ctx-of req) k on-job)
                  (add-watch activity k watch)
                  (locking lock
                    (let [all (api/list-jobs sys (ctx-of req))]
                      (reset! seen (set (map :id all)))
                      (send! (sse/patch-elements (h/html (v/job-list all))))
                      (send! (sse/patch-elements (h/html (v/activity @activity))))
                      (reset! send-to send!))))
      :on-close (fn []
                  (reset! closed true)
                  (api/unsubscribe-jobs! sys (ctx-of req) k)
                  (remove-watch activity k))})))

(defn- track-activity!
  "Keep the last 50 job state changes, newest first, for every tab. One log
  per UI instance: the desktop has one user; a hosted dashboard mounts one
  handler per tenant context (`ctx`)."
  [sys ctx activity]
  (let [last-state (atom {})]
    (api/subscribe-jobs! sys ctx activity
                     (fn [{:keys [job]}]
                       (when job
                         (let [{:keys [id state inputs]} job]
                           (when (not= state (get (first (swap-vals! last-state assoc id state)) id))
                             (swap! activity
                                    #(->> % (cons (str (.truncatedTo (LocalTime/now) ChronoUnit/SECONDS) "  "
                                                       (name state) "  "
                                                       (if (= 1 (count inputs)) (first inputs) (str (count inputs) " files"))))
                                          (take 50) vec)))))))))

;; ---------------------------------------------------------------------------
;; Routing

(def ^:private routes
  [[:get    "/"                         page]
   [:get    "/ui/profiles/:slug"        select]
   [:post   "/ui/profiles"              create]
   [:put    "/ui/profiles/:slug"        save]
   [:post   "/ui/profiles/:slug/rename" (partial rename-or-copy api/rename-profile! "Renamed to")]
   [:post   "/ui/profiles/:slug/copy"   (partial rename-or-copy api/copy-profile! "Duplicated as")]
   [:delete "/ui/profiles/:slug"        delete]
   [:post   "/ui/preview/:slug"         preview]
   [:post   "/ui/jobs"                  submit]
   [:post   "/ui/jobs/:slug"            submit]
   [:post   "/ui/jobs/:id/cancel"       cancel]
   [:get    "/ui/stream"                queue-stream]])

(defn- segments [uri] (vec (remove str/blank? (str/split (str uri) #"/"))))

(defn- decode [^String s] (URLDecoder/decode (str/replace s "+" "%2B") StandardCharsets/UTF_8))

(def ^:private compiled (mapv (fn [[m p f]] [m (segments p) f]) routes))

(defn- match [method uri]
  (let [segs (segments uri)]
    (some (fn [[m pattern f]]
            (when (and (= m method) (= (count pattern) (count segs)))
              (when-let [params (reduce (fn [acc [p s]]
                                          (cond (str/starts-with? p ":") (assoc acc (keyword (subs p 1)) (decode s))
                                                (= p s) acc
                                                :else (reduced nil)))
                                        {} (map vector pattern segs))]
                [f params])))
          compiled)))

(defn ui-path?
  "Paths this UI answers: the page and everything under /ui/."
  [uri]
  (or (= "/" uri) (str/starts-with? (str uri) "/ui/")))

(defn handler
  "Ring handler for / and /ui/* (nil for anything else). Call once per
  server, at run time: it subscribes to the job queue. `ctx` is whose
  activity the log shows (default: the local user)."
  ([sys] (handler sys {:tenant "local" :user "local"}))
  ([sys ctx]
  (let [activity (atom [])
        env      {:sys sys :activity activity}]
    (when (:jobs sys) (track-activity! sys ctx activity))
    (fn [req]
      (let [uri (:uri req)]
        (when (ui-path? uri)
          (if-let [[f params] (match (:request-method req) uri)]
            (if (and (not= "/" uri) (not (sse/datastar-request? req)))
              {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Not a Datastar request."}
              (try
                (f env req params)
                (catch clojure.lang.ExceptionInfo e
                  (if (:wmark/error (ex-data e))
                    (sse/response (say :bad (error-text e)))
                    (throw e)))))
            {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "Not found."})))))))
