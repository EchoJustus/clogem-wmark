;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.web-ui-test
  "The built-in Datastar UI through the real desktop server: security, the
  event format on the wire, profile editing with revisions, validation, and
  a render watched over the queue stream. A fake engine stands in for
  FFmpeg, as in the job tests."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [watermark.app :as app]
            [watermark.core.features :as features]
            [watermark.engine :as engine]
            [watermark.server.http :as http])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
                          HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.stream Stream)))

(set! *warn-on-reflection* true)

(defrecord FakeHandle [result]
  engine/RenderHandle
  (cancel! [_] nil)
  (outcome [_] result))

(defrecord FakeEngine []
  engine/VideoEngine
  (info [_] {:engine/id :fake :engine/version "1.0" :available? true :problems [] :warnings []
             :capabilities {:layers #{:image :text} :animations #{:flip-y} :timing #{:always :windows :periodic}
                            :placement #{:fixed :burst-scatter :per-window} :codecs #{:h264 :hevc}
                            :containers #{"mp4"} :audio #{:copy :aac :none}}})
  (probe [_ _] {:kind :video :width 1280 :height 720 :fps-num 25 :fps-den 1 :frames 250
                :duration-s 10.0 :start-s 0.0 :has-audio? false})
  (prepare [this request]
    (engine/check! (engine/info this) request)
    {:engine :fake :output (get-in request [:output :path])})
  (execute! [_ plan listener]
    (let [result (promise)]
      (future (dotimes [i 4] (Thread/sleep 60) (listener {:event :progress :fraction (/ (inc i) 4.0)}))
              (spit (:output plan) "frames")
              (deliver result {:status :done}))
      (->FakeHandle result))))

(def ^:dynamic *srv* nil)
(def ^:dynamic *dir* nil)

(use-fixtures :once
  (fn [t]
    (let [dir  (str (Files/createTempDirectory "wmark-web" (make-array FileAttribute 0)))
          sys  (-> (app/system {:edition :community :entitlements-fn (fn [_] (features/community))}
                               {:home (str dir "/home")})
                   (assoc :engine (->FakeEngine))
                   app/with-jobs)
          srv  (http/start! sys {})]
      (try (binding [*srv* srv *dir* dir] (t))
           (finally (http/stop! sys srv))))))

(def ^:private client
  (delay (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.build))))

(defn- builder ^HttpRequest$Builder [path {:keys [auth datastar? headers]}]
  (let [b (HttpRequest/newBuilder (URI. (str (:url *srv*) path)))
        b (if (= auth :cookie) (.header b "Cookie" (str "wmark_session=" (:token *srv*))) b)
        b (if datastar? (.header b "Datastar-Request" "true") b)]
    (reduce (fn [^HttpRequest$Builder b [k v]] (.header b k v)) b headers)))

(defn- parse-events [^String body]
  (for [block (str/split (str body) #"\n\n") :when (str/starts-with? block "event: ")]
    (let [lines (str/split-lines block)]
      {:event (subs (first lines) 7)
       :data  (->> lines
                   (keep #(when (str/starts-with? % "data: ") (subs % 6)))
                   (map #(str/split % #" " 2))
                   (reduce (fn [m [k v]] (update m k (fnil conj []) v)) {}))})))

(defn- ui
  "A Datastar request: signals as JSON (body, or ?datastar= for GET/DELETE)."
  [method path & {:keys [signals auth datastar? headers] :or {auth :cookie datastar? true signals {}}}]
  (let [query?  (#{"GET" "DELETE"} method)
        path    (if (and query? (seq signals))
                  (str path "?datastar=" (URLEncoder/encode (json/write-str signals) "UTF-8"))
                  path)
        ^HttpResponse r (.send ^HttpClient @client
                               (.build (.method (builder path {:auth auth :datastar? datastar? :headers headers})
                                                method
                                                (if query?
                                                  (HttpRequest$BodyPublishers/noBody)
                                                  (HttpRequest$BodyPublishers/ofString (json/write-str signals)))))
                               (HttpResponse$BodyHandlers/ofString))]
    {:status  (.statusCode r)
     :type    (.orElse (.firstValue (.headers r) "content-type") "")
     :headers (.map (.headers r))
     :body    (str (.body r))
     :events  (parse-events (.body r))}))

(defn- elements [r] (str/join "\n" (mapcat #(get-in % [:data "elements"]) (:events r))))
(defn- signals [r] (apply merge (for [e (:events r) s (get-in e [:data "signals"])] (json/read-str s))))
(defn- message [r] (second (re-find #"<p id=\"message\"[^>]*>([^<]*)</p>" (elements r))))

;; ---------------------------------------------------------------------------

(deftest the-page-is-protected-and-locked-down
  (is (= 401 (:status (ui "GET" "/" :auth nil :datastar? false))) "pages carry data: token required")
  (let [r     (ui "GET" "/" :datastar? false)
        csp   (first (get (:headers r) "content-security-policy"))
        nonce (second (re-find #"'nonce-([^']+)'" csp))]
    (is (= 200 (:status r)))
    (is (and nonce (str/includes? (:body r) (str "data-nonce=\"" nonce "\""))) "Datastar runs in CSP mode")
    (is (not (str/includes? csp "unsafe-eval")))
    (is (not (str/includes? csp "unsafe-inline")))
    (is (not= nonce (second (re-find #"'nonce-([^']+)'" (first (get (:headers (ui "GET" "/" :datastar? false))
                                                                   "content-security-policy")))))
        "a fresh nonce per page"))
  (is (= 401 (:status (ui "POST" "/ui/profiles" :auth nil))))
  (is (= 400 (:status (ui "POST" "/ui/profiles" :datastar? false))) "only Datastar requests")
  (is (= 403 (:status (ui "POST" "/ui/profiles" :signals {"newname" "x"}
                          :headers {"Origin" "https://evil.example"})))
      "cross-site writes are refused")
  (is (= 404 (:status (ui "GET" "/ui/nope")))))

(deftest editing-profiles-over-datastar
  (let [created (ui "POST" "/ui/profiles" :signals {"newname" "Promo cut"})]
    (is (str/starts-with? (:type created) "text/event-stream"))
    (is (= "Created “Promo cut”." (message created)))
    (is (= 1 (get (signals created) "rev")))
    (is (str/includes? (elements created) "aria-current=\"true\"")))
  (testing "live preview of unsaved edits"
    (let [bad  (ui "POST" "/ui/preview/promo-cut" :signals {"settings" "{\"logo\": {"})
          ugly (ui "POST" "/ui/preview/promo-cut" :signals {"settings" "{\"logo\": {\"opacity\": 7}}"})
          good (ui "POST" "/ui/preview/promo-cut" :signals {"settings" "{\"logo\": {\"opacity\": 0.4}}"})]
      (is (str/starts-with? (message bad) "The settings aren&#39;t valid JSON"))
      (is (str/includes? (message ugly) "logo.opacity: should be at most 1.0"))
      (is (re-find #"<td>logo.​opacity</td><td>0.4</td><td>this profile \(unsaved\)</td>" (elements good)))))
  (testing "saving with the revision the page loaded; a stale save is refused"
    (let [ok    (ui "PUT" "/ui/profiles/promo-cut" :signals {"settings" "{\"logo\": {\"opacity\": 0.4}}" "rev" 1})
          stale (ui "PUT" "/ui/profiles/promo-cut" :signals {"settings" "{}" "rev" 1})]
      (is (= "Saved “Promo cut”." (message ok)))
      (is (= 2 (get (signals ok) "rev")))
      (is (str/starts-with? (message stale) "This profile changed since it was loaded"))))
  (testing "rename, duplicate, delete"
    (is (= "Renamed to “Trailer”." (message (ui "POST" "/ui/profiles/promo-cut/rename" :signals {"name" "Trailer"}))))
    (is (= "Duplicated as “Trailer B”." (message (ui "POST" "/ui/profiles/trailer/copy" :signals {"name" "Trailer B"}))))
    (is (= "Deleted." (message (ui "DELETE" "/ui/profiles/trailer-b"))))
    (is (str/includes? (message (ui "GET" "/ui/profiles/trailer-b")) "trailer-b"))))

(deftest hostile-names-are-inert
  (let [name "<img src=x onerror=alert(1)> \" data-on:click=\"@post('/ui/jobs')"
        r    (ui "POST" "/ui/profiles" :signals {"newname" name})
        html (elements r)]
    (is (str/includes? html "&lt;img src=x onerror=alert(1)&gt;"))
    (is (not (str/includes? html "<img")))
    (is (not (str/includes? html "data-on:click=\"@post('/ui/jobs')\"")))
    (is (= "" (get (signals r) "name")) "text signals travel as JSON")))

(defn- open-stream
  "Collect the queue stream's lines in the background."
  []
  (let [lines (atom [])
        ^HttpResponse r (.send ^HttpClient @client
                               (.build (.GET (builder "/ui/stream" {:auth :cookie :datastar? true})))
                               (HttpResponse$BodyHandlers/ofLines))]
    (future (.forEach ^Stream (.body r) (reify java.util.function.Consumer (accept [_ l] (swap! lines conj l)))))
    lines))

(defn- wait-for [pred ms]
  (loop [t 0] (cond (pred) true (> t ms) false :else (do (Thread/sleep 25) (recur (+ t 25))))))

(deftest a-render-watched-over-the-stream
  (let [lines (open-stream)
        input (doto (io/file *dir* "clip.mp4") (spit "not really a video"))]
    (is (wait-for #(some (fn [l] (str/includes? l "<ol id=\"jobs\">")) @lines) 3000) "the full queue first")
    (ui "POST" "/ui/profiles" :signals {"newname" "Render test"})
    (ui "PUT" "/ui/profiles/render-test"
        :signals {"rev" 1 "settings" (json/write-str {:logo {:enabled false}
                                                      :texts [{:mode "continuous" :content "(c) Studio"}]
                                                      :output {:dir (str *dir* "/out") :overwrite? true}})})
    (let [r (ui "POST" "/ui/jobs/render-test" :signals {"inputs" (str "\"" input "\"\n\n")})]
      (is (str/starts-with? (message r) "Queued 1 file.")))
    (is (wait-for #(some (fn [l] (re-find #"<li id=\"job-[^\"]+\" class=\"done\">" l)) @lines) 5000)
        "the row reaches done over the stream")
    (is (some #(re-find #"<li id=\"job-[^\"]+\" class=\"running\">.*<progress" %) @lines)
        "progress arrived while running")
    (is (wait-for #(some (fn [l] (re-find #"<li>[0-9:]+  done  .*clip.mp4</li>" l)) @lines) 3000) "activity log")
    (is (.isFile (io/file *dir* "out" "clip_wm.mp4")) "published by the unchanged job pipeline")
    (is (= "Add at least one file path to the render queue."
           (message (ui "POST" "/ui/jobs" :signals {"inputs" "  "}))))))
