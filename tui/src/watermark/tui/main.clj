;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.tui.main
  "wmark-tui: a terminal client of a running `wmark serve` -- just another API
  client, like the web UI (the Clash model).

  This first cut is a line-mode shell: it works in every terminal, including
  legacy Windows consoles, with no native code. Full-screen Lanterna views
  (profile list + detail panes) come next behind the same `call` function;
  Lanterna stays in this separate binary because it is LGPL-3.0 and because
  its Windows console support is its weakest part.

  Connects via --url/--token, else the runtime file the server writes
  (<home>/runtime/server.edn)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [watermark.config :as config])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption Path))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- discover [{:keys [url token home]}]
  (or (when (and url token) {:url url :token token})
      (let [^Path f (.resolve (config/resolve-home {:home home}) "runtime/server.edn")]
        (when (Files/exists f (make-array LinkOption 0))
          (edn/read-string (slurp (str f) :encoding "UTF-8"))))))

(defn call
  "One API call -> {:status n :body data}."
  [{:keys [url token]} method path & [body]]
  (let [req  (-> (HttpRequest/newBuilder (URI. (str url "/api/v1" path)))
                 (.header "Authorization" (str "Bearer " token))
                 (.header "Content-Type" "application/json")
                 (.method method (if body
                                   (HttpRequest$BodyPublishers/ofString (json/write-str body))
                                   (HttpRequest$BodyPublishers/noBody)))
                 (.build))
        ^HttpResponse resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))
        text (str (.body resp))]
    {:status (.statusCode resp)
     :body   (when-not (str/blank? text) (json/read-str text :key-fn keyword))}))

(defn- enc [s] (str/replace (URLEncoder/encode (str s) StandardCharsets/UTF_8) "+" "%20"))

(defn- ok-or-say [{:keys [status body]}]
  (if (< status 300)
    body
    (do (println (str "  " (or (:message body) (str "HTTP " status))))
        (when-let [errors (:errors body)] (pprint/pprint errors))
        nil)))

(defn- list-profiles [conn]
  (let [ps (:profiles (ok-or-say (call conn "GET" "/profiles")))]
    (if (empty? ps)
      (println "  No profiles yet. The first run creates \"latest\".")
      (doseq [[i p] (map-indexed vector ps)]
        (println (format "  %2d  %s%s" (inc i) (:name p) (if (:auto? p) "  (last run)" "")))))
    ps))

(defn- pick
  "A profile by list number or by name."
  [ps s]
  (or (when-let [n (parse-long (str s))] (:name (nth ps (dec n) nil)))
      s))

(def help
  "  list | show N | effective N | new NAME | rename N NEW | copy N NEW | delete N | help | quit
  N is a number from `list` or a profile name.")

(defn- repl [conn]
  (let [h (ok-or-say (call conn "GET" "/health"))]
    (println (str "Connected to " (:url conn) " -- " (:edition h) " edition " (:version h))))
  (println help)
  (loop [ps (list-profiles conn)]
    (print "wmark> ") (flush)
    (when-let [line (read-line)]
      (let [[cmd a b] (str/split (str/trim line) #"\s+" 3)
            a (when a (pick ps a))]
        (case (keyword (or (not-empty cmd) "blank"))
          (:quit :exit :q) nil
          :blank    (recur ps)
          :help     (do (println help) (recur ps))
          :list     (recur (list-profiles conn))
          :show     (do (some-> (ok-or-say (call conn "GET" (str "/profiles/" (enc a)))) :settings pprint/pprint)
                        (recur ps))
          :effective (do (when-let [r (ok-or-say (call conn "POST" "/resolve" {:profile a}))]
                            (doseq [[path src] (sort (:provenance r))]
                              (println (format "  %-30s %s" (name path) src)))
                            (when (seq (:locked r)) (println "  Needs Pro:" (str/join ", " (:locked r)))))
                          (recur ps))
          :new      (do (ok-or-say (call conn "POST" "/profiles" {:name (str/join " " (remove nil? [a b])) :settings {}}))
                        (recur (list-profiles conn)))
          :rename   (do (ok-or-say (call conn "POST" (str "/profiles/" (enc a) "/rename") {:to b}))
                        (recur (list-profiles conn)))
          :copy     (do (ok-or-say (call conn "POST" (str "/profiles/" (enc a) "/copy") {:to b}))
                        (recur (list-profiles conn)))
          :delete   (do (ok-or-say (call conn "DELETE" (str "/profiles/" (enc a))))
                        (recur (list-profiles conn)))
          (do (println (str "  Unknown command: " cmd)) (println help) (recur ps)))))))

(def options
  [[nil "--url URL" "Server URL (default: discovered from the runtime file)"]
   [nil "--token TOKEN" "API token (default: discovered)"]
   [nil "--home DIR" "wmark data directory, to find the runtime file"]])

(defn -main [& args]
  (let [{:keys [options errors]} (cli/parse-opts args options)
        conn (discover options)]
    (cond
      errors (println (str/join "\n" errors))
      (nil? conn) (println "No running wmark server found. Start one with `wmark serve` (or `wmark`), or pass --url and --token.")
      :else (try (repl conn)
                 (catch java.io.IOException _
                   ;; also covers a stale runtime file left by a crashed server
                   (println (str "Can't reach the wmark server at " (:url conn)
                                 ". Is it running? Start it with `wmark serve`.")))))
    (shutdown-agents)))
