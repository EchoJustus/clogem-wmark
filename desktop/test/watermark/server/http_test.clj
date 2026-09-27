;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.server.http-test
  "Starts the real server on an ephemeral loopback port and talks HTTP to it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [watermark.app :as app]
            [watermark.core.features :as features]
            [watermark.server.http :as http])
  (:import (java.io BufferedReader InputStreamReader PrintWriter)
           (java.net Socket URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
                          HttpRequest$Builder HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:dynamic *srv* nil)

(use-fixtures :once
  (fn [t]
    (let [home (Files/createTempDirectory "wmark-http" (make-array FileAttribute 0))
          sys  (app/with-jobs (app/system {:edition :community
                                           :entitlements-fn (fn [_] (features/community))}
                                          {:home (str home)}))
          srv  (http/start! sys {})]
      (try (binding [*srv* srv] (t))
           (finally (http/stop! sys srv))))))

(def ^:private client
  (delay (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NEVER) (.build))))

(defn- call
  [method path & {:keys [body auth headers] :or {auth :bearer}}]
  (let [^HttpRequest$Builder b (cond-> (HttpRequest/newBuilder (URI. (str (:url *srv*) path)))
                                 (= auth :bearer) (.header "Authorization" (str "Bearer " (:token *srv*)))
                                 body             (.header "Content-Type" "application/json"))
        ^HttpRequest$Builder b (reduce (fn [^HttpRequest$Builder b [k v]] (.header b k v)) b headers)
        ^HttpResponse r (.send ^HttpClient @client
                               (.build (.method b method (if body
                                                           (HttpRequest$BodyPublishers/ofString (json/write-str body))
                                                           (HttpRequest$BodyPublishers/noBody))))
                               (HttpResponse$BodyHandlers/ofString))
        ct (.orElse (.firstValue (.headers r) "content-type") "")]
    {:status  (.statusCode r)
     :headers (.map (.headers r))
     :body    (if (str/starts-with? ct "application/json")
                (json/read-str (str (.body r)) :key-fn keyword)
                (.body r))}))

(defn- enc [s] (str/replace (URLEncoder/encode ^String s "UTF-8") "+" "%20"))

(defn- raw-status
  "Status line for a hand-written request -- HttpClient won't send a forged Host."
  [host-header]
  (with-open [s (Socket. "127.0.0.1" (int (:port *srv*)))]
    (doto (PrintWriter. (.getOutputStream s))
      (.print (str "GET /api/v1/health HTTP/1.1\r\nHost: " host-header
                   "\r\nAuthorization: Bearer " (:token *srv*) "\r\nConnection: close\r\n\r\n"))
      (.flush))
    (.readLine (BufferedReader. (InputStreamReader. (.getInputStream s) StandardCharsets/UTF_8)))))

;; ---------------------------------------------------------------------------

(deftest authentication
  (is (= 401 (:status (call "GET" "/api/v1/health" :auth nil))))
  (let [r (call "GET" "/api/v1/health")]
    (is (= 200 (:status r)))
    (is (= "community" (get-in r [:body :edition])))
    (is (= "ffmpeg" (get-in r [:body :engine :engine/id])) "the engine reports itself through the port")
    (is (contains? (get-in r [:body :engine :binaries]) :ffmpeg)))
  (let [r (call "GET" "/api/v1/doctor")]
    (is (= 200 (:status r)))
    (is (seq (get-in r [:body :binaries :ffmpeg :trail])) "doctor shows where wmark looked"))
  (testing "DNS rebinding: a foreign Host is refused even with the token"
    (is (str/includes? (raw-status "attacker.example:80") " 421"))
    (is (str/includes? (raw-status (str "127.0.0.1:" (:port *srv*))) " 200")))
  (testing "browser bootstrap: token -> HttpOnly SameSite cookie + clean redirect"
    (let [r      (call "GET" (str "/?token=" (:token *srv*)) :auth nil)
          cookie (first (get (:headers r) "set-cookie"))]
      (is (= 303 (:status r)))
      (is (= ["/"] (get (:headers r) "location")))
      (is (every? #(str/includes? cookie %) ["HttpOnly" "SameSite=Strict"]))
      (is (= 200 (:status (call "GET" "/api/v1/profiles" :auth nil
                                :headers {"Cookie" (first (str/split cookie #";"))})))))))

(deftest cross-site-writes-are-refused
  (is (= 403 (:status (call "POST" "/api/v1/profiles" :body {:name "x" :settings {}}
                            :headers {"Origin" "https://evil.example"})))))

(deftest profile-crud-over-http
  (let [name "16:9 Video Profile"
        r    (call "POST" "/api/v1/profiles"
                   :body {:name name :settings {:logo {:anchor "top-left" :opacity 1}}})]
    (is (= 201 (:status r)))
    (is (= "16-9-video-profile" (get-in r [:body :profile/slug])))
    (is (= "top-left" (get-in r [:body :settings :logo :anchor])) "keywords round-trip as strings")
    (is (= 200 (:status (call "GET" (str "/api/v1/profiles/" (enc name))))))
    (is (= 409 (:status (call "PUT" (str "/api/v1/profiles/" (enc "16 9 video profile!"))
                              :body {:settings {}})))
        "an alias of an existing name is a conflict")
    (let [bad (call "PUT" (str "/api/v1/profiles/" (enc name)) :body {:settings {:logo {:opacity 3}}})]
      (is (= 422 (:status bad)))
      (is (= ["should be at most 1.0"] (get-in bad [:body :errors :logo :opacity]))))
    (testing "optimistic concurrency over HTTP"
      (let [rev (get-in (call "GET" (str "/api/v1/profiles/" (enc name))) [:body :profile/rev])
            ok  (call "PUT" (str "/api/v1/profiles/" (enc name)) :body {:settings {} :if-rev rev})
            old (call "PUT" (str "/api/v1/profiles/" (enc name)) :body {:settings {} :if-rev rev})]
        (is (= 200 (:status ok)))
        (is (= 409 (:status old)) "a save based on a stale revision is refused")
        (is (= "stale" (get-in old [:body :reason])))))
    (is (= 200 (:status (call "POST" (str "/api/v1/profiles/" (enc name) "/rename") :body {:to "Widescreen"}))))
    (is (= 201 (:status (call "POST" "/api/v1/profiles/widescreen/copy" :body {:to "Widescreen B"}))))
    (is (= ["Widescreen" "Widescreen B"]
           (map :name (get-in (call "GET" "/api/v1/profiles") [:body :profiles]))))
    (is (= 200 (:status (call "DELETE" "/api/v1/profiles/widescreen-b"))))
    (is (= 404 (:status (call "GET" "/api/v1/profiles/widescreen-b"))))))

(deftest gating-and-resolution
  (let [pro-layer {:mode "subliminal" :content "(c) Studio"}
        r (call "POST" "/api/v1/resolve" :body {:settings {:texts [pro-layer]}})]
    (is (= 200 (:status r)) "resolution works, so the UI can show what's locked")
    (is (= ["text.mode/subliminal"] (get-in r [:body :locked])) "namespaces survive JSON")
    (is (= "overrides" (get-in r [:body :provenance (keyword "texts")])))
    (let [j (call "POST" "/api/v1/jobs" :body {:inputs ["/nonexistent.mp4"] :settings {:texts [pro-layer]}})]
      (is (= 402 (:status j)) "locked features never reach the queue")
      (is (= ["text.mode/subliminal"] (get-in j [:body :features]))))
    (is (some #(and (= "text.mode/random" (:id %)) (false? (:entitled %)))
              (get-in (call "GET" "/api/v1/features") [:body :features])))))

(deftest built-in-ui-and-static-assets
  (is (= 401 (:status (call "GET" "/" :auth nil))) "the built-in UI's page carries data: token required")
  (let [r (call "GET" "/")]
    (is (= 200 (:status r)))
    (is (str/includes? (first (get (:headers r) "content-security-policy")) "frame-ancestors 'none'")))
  (let [js (call "GET" "/datastar.js" :auth nil)]
    (is (= 200 (:status js)) "assets are public")
    (is (= ["nosniff"] (get (:headers js) "x-content-type-options"))))
  (is (= 404 (:status (call "GET" "/profiles/some-client-route" :auth nil))) "no SPA fallback for the built-in UI")
  (is (= 404 (:status (call "GET" "/..%2f..%2fetc/passwd" :auth nil))))
  (is (= 404 (:status (call "GET" "/api/v1/nope")))))

(deftest an-external-ui-replaces-the-built-in-one
  (let [dir  (Files/createTempDirectory "wmark-ext-ui" (make-array FileAttribute 0))
        _    (spit (str (.resolve dir "index.html")) "<!doctype html><title>external</title>")
        home (Files/createTempDirectory "wmark-ext-home" (make-array FileAttribute 0))
        sys  (app/system {:edition :community :entitlements-fn (fn [_] (features/community))} {:home (str home)})
        srv  (http/start! sys {:ui-dir (str dir)})]
    (try
      (binding [*srv* srv]
        (is (str/includes? (:body (call "GET" "/" :auth nil)) "external") "its files are public")
        (is (str/includes? (:body (call "GET" "/some/client/route" :auth nil)) "external") "SPA fallback")
        (is (= 401 (:status (call "GET" "/api/v1/profiles" :auth nil))) "its API calls aren't"))
      (finally (http/stop! sys srv)))))
