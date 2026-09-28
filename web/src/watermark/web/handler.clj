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
            [watermark.web.form :as fv]
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

(defn- form-of [sys ctx slug] (when slug (api/settings-form sys ctx slug)))

(defn- form-events
  "The form, the JSON view and the preview after `doc` changed: the new
  revision, the form in view mode, and a nudge (`pv`) to redraw the frame."
  [{:keys [sys pv]} ctx doc]
  (let [slug (:profile/slug doc)]
    [(sse/patch-signals {:rev      (:profile/rev doc)
                         :settings (v/settings-text (:settings doc))
                         :pv       (swap! pv inc)})
     (sse/patch-elements (h/html (fv/settings-form slug (form-of sys ctx slug))))
     (sse/patch-elements (h/html (v/effective (api/resolve-settings sys ctx {:profile slug}) (titles sys ctx) false)))]))

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
     (sse/patch-elements (h/html (fv/settings-form slug (form-of sys ctx slug))))
     (sse/patch-elements (h/html (fv/preview-panel slug)))
     (sse/patch-elements (h/html (v/run-button slug)))
     (sse/patch-elements (h/html (apply v/message (or msg [nil nil]))))]))

(defn- say [kind text] (sse/patch-elements (h/html (v/message kind text))))

;; ---------------------------------------------------------------------------
;; Handlers: (fn [env req params] -> Ring response)

(def ^:private theme-cookie "wmark_theme")

(defn- theme-of
  "The theme the person chose (an index into views/themes), from its cookie."
  [req]
  (or (some->> (get-in req [:headers "cookie"])
               (re-find (re-pattern (str "(?:^|;\\s*)" theme-cookie "=([0-2])")))
               second
               parse-long)
      0))

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
     :body    (h/document (v/page {:nonce        n
                                   :health       (api/health sys)
                                   :profiles     profiles
                                   :doc          doc
                                   :resolved     (when doc (api/resolve-settings sys ctx {:profile slug}))
                                   :titles       (titles sys ctx)
                                   :jobs         (api/list-jobs sys ctx)
                                   :theme        (theme-of req)
                                   :form-view    (fv/settings-form slug (form-of sys ctx slug))
                                   :preview-view (fv/preview-panel slug)}))}))

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

(defn- rev-of [signals]
  (let [rev (get signals "rev")]
    (when (and (number? rev) (pos? (long rev))) (long rev))))

(defn- save [{:keys [sys] :as env} req {:keys [slug]}]
  (let [ctx     (ctx-of req)
        signals (sse/read-signals req)
        doc     (api/save-profile! sys ctx slug (parse-settings (get signals "settings"))
                                   {:if-rev (rev-of signals)})]
    (apply sse/response
           (concat (form-events env ctx doc)
                   [(sse/patch-elements (h/html (v/profile-list (api/list-profiles sys ctx) (:profile/slug doc))))
                    (say :ok (str "Saved “" (:profile/name doc) "”."))]))))

;; ---------------------------------------------------------------------------
;; The settings form: one path at a time (docs/adr/0011, section 3)

(defn- row-or-404 [sys ctx slug id]
  (or (fv/find-row (:form (form-of sys ctx slug)) id)
      (throw (ex-info (str "No setting \"" id "\".") {:wmark/error :not-found}))))

(defn- field-view [{:keys [sys]} req {:keys [slug id]}]
  (let [ctx (ctx-of req)
        f   (form-of sys ctx slug)]
    (sse/response (sse/patch-elements (h/html (fv/row-view slug (row-or-404 sys ctx slug id) (get-in f [:form :sources])))))))

(defn- field-edit [{:keys [sys]} req {:keys [slug id]}]
  (let [ctx (ctx-of req)
        f   (form-of sys ctx slug)
        row (row-or-404 sys ctx slug id)]
    (sse/response
     ;; the value travels as a signal (JSON), never inside an expression
     (sse/patch-signals {:fv (if (= :boolean (:kind row)) (true? (:value row)) (:input row))})
     (sse/patch-elements (h/html (fv/row-edit slug row (get-in f [:form :sources]) nil))))))

(defn- edit!
  "Apply one form edit; field mistakes are shown at the field."
  [{:keys [sys] :as env} req slug edit & {:keys [ok]}]
  (let [ctx     (ctx-of req)
        signals (sse/read-signals req)]
    (try
      (let [doc (api/edit-profile! sys ctx slug (assoc edit :if-rev (rev-of signals)))]
        (apply sse/response (concat (form-events env ctx doc) [(say :ok (or ok "Saved."))])))
      (catch clojure.lang.ExceptionInfo e
        (let [row (when (and (:id edit) (= :invalid (:wmark/error (ex-data e))))
                    (fv/find-row (:form (form-of sys ctx slug)) (:id edit)))]
          (if row
            (sse/response (sse/patch-elements
                           (h/html (fv/row-edit slug row (get-in (form-of sys ctx slug) [:form :sources]) (ex-message e)))))
            (throw e)))))))

(defn- field-save [env req {:keys [slug id]}]
  (edit! env req slug {:op :set :id id :value (get (sse/read-signals req) "fv")}))

(defn- field-reset [env req {:keys [slug id]}]
  (edit! env req slug {:op :unset :id id} :ok "Reset to what it inherits."))

(defn- layer-add [env req {:keys [slug]}]
  (edit! env req slug {:op :add-layer :mode (get (sse/read-signals req) "nl")} :ok "Added a text layer."))

(defn- layer-index [s]
  (or (when (re-matches #"[0-9]{1,2}" (str s)) (parse-long s))
      (throw (ex-info "No such text layer." {:wmark/error :not-found}))))

(defn- layer-up [env req {:keys [slug index]}]
  (edit! env req slug {:op :move-layer :index (layer-index index) :delta -1}))

(defn- layer-down [env req {:keys [slug index]}]
  (edit! env req slug {:op :move-layer :index (layer-index index) :delta 1}))

(defn- layer-remove [env req {:keys [slug index]}]
  (edit! env req slug {:op :remove-layer :index (layer-index index)} :ok "Removed the text layer."))

;; ---------------------------------------------------------------------------
;; Preview frames (docs/adr/0011, section 5)

(defn- frame
  "Draw the frame the preview panel asks for. One at a time, newest wins: a
  request that finds a newer one waiting answers with nothing."
  [{:keys [sys preview-ticket preview-lock]} req {:keys [slug]}]
  (let [ctx     (ctx-of req)
        signals (sse/read-signals req)
        ticket  (swap! preview-ticket inc)
        number  #(let [x (get signals %)] (if (number? x) (double x) 0.0))
        source  (str/trim (str (get signals "ps")))]
    (locking preview-lock
      (if (not= ticket @preview-ticket)
        (sse/response)
        (try
          (let [p (api/preview-frame sys ctx {:profile slug
                                              :t       (number "pt")
                                              :aspect  (get fv/aspects (long (number "pa")) "16:9")
                                              :source  (not-empty source)})]
            (sse/response
             (sse/patch-signals {:pmax (max 1.0 (double (or (:duration-s p) 12.0)))})
             (sse/patch-elements (h/html (fv/preview-frame p nil)))))
          (catch clojure.lang.ExceptionInfo e
            (if (:wmark/error (ex-data e))
              (sse/response (sse/patch-elements (h/html (fv/preview-frame nil (error-text e)))))
              (throw e))))))))

(defn- theme [_ _ {:keys [n]}]
  (let [i (or (when (re-matches #"[0-2]" (str n)) (parse-long n)) 0)]
    (assoc (sse/response (sse/patch-signals {:theme i}))
           :headers (assoc sse/headers "Set-Cookie" (str theme-cookie "=" i "; Path=/; Max-Age=31536000; SameSite=Strict")))))

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
   [:get    "/ui/profiles/:slug/fields/:id"      field-view]
   [:get    "/ui/profiles/:slug/fields/:id/edit" field-edit]
   [:put    "/ui/profiles/:slug/fields/:id"      field-save]
   [:delete "/ui/profiles/:slug/fields/:id"      field-reset]
   [:post   "/ui/profiles/:slug/layers"          layer-add]
   [:post   "/ui/profiles/:slug/layers/:index/up"   layer-up]
   [:post   "/ui/profiles/:slug/layers/:index/down" layer-down]
   [:delete "/ui/profiles/:slug/layers/:index"      layer-remove]
   [:post   "/ui/frame/:slug"           frame]
   [:post   "/ui/theme/:n"              theme]
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
        env      {:sys sys :activity activity
                  :pv (atom 0) :preview-ticket (atom 0) :preview-lock (Object.)}]
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
