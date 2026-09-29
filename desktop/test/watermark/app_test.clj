;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.app-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.app :as app]
            [watermark.core.features :as features]
            [watermark.util.os :as os])
  (:import (java.lang ProcessBuilder ProcessHandle)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util List)))

(set! *warn-on-reflection* true)

(defn- start ^Process [& argv] (.start (ProcessBuilder. ^List (vec argv))))

(deftest a-gui-sidecar-exits-with-its-parent
  (testing "`serve --parent-pid`: the server outlives nothing it was started by"
    (let [parent (if (os/windows?) (start "ping" "-n" "2" "127.0.0.1") (start "sleep" "1"))
          ended  (promise)]
      (app/watch-parent! (.pid parent) #(deliver ended :ended))
      (is (= :waiting (deref ended 200 :waiting)) "nothing happens while the parent runs")
      (is (= :ended (deref ended 5000 :timeout)) "the parent ending triggers the exit")))
  (testing "a parent that died before the watch started counts as ended"
    (let [parent (if (os/windows?) (start "cmd" "/c" "exit") (start "true"))
          ended  (promise)]
      (.waitFor parent)
      (app/watch-parent! (.pid parent) #(deliver ended :ended))
      (is (= :ended (deref ended 1000 :timeout))))))

(defn- alive-within? [procs ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [alive (filter #(.isAlive ^ProcessHandle %) procs)]
        (if (or (empty? alive) (> (System/currentTimeMillis) deadline))
          (seq alive)
          (do (Thread/sleep 50) (recur)))))))

(deftest a-stopping-server-leaves-no-render-behind
  (testing "everything under the server ends: asked first, then forced after the grace"
    (when-not (os/windows?)
      ;; a stand-in server with two long "renders"; the second ignores SIGTERM
      (let [server (start "sh" "-c" "sleep 60 & sh -c 'trap \"\" TERM; sleep 60' & wait")
            under  #(vec (iterator-seq (.iterator (.descendants (.toHandle server)))))]
        (try
          (loop [n 0] (when (and (< (count (under)) 2) (< n 100)) (Thread/sleep 50) (recur (inc n))))
          (let [procs (under)
                t0    (System/nanoTime)]
            (is (<= 2 (count procs)) "the stand-in's children started")
            (is (= (count procs) (app/end-descendants! (.toHandle server) 500)))
            (is (< (quot (- (System/nanoTime) t0) 1000000) 3000) "a stubborn one is forced after the grace")
            (is (nil? (alive-within? procs 3000)) "none is left running"))
          (finally
            (.destroyForcibly server)))))))

(deftest run-help-is-what-the-main-help-promises
  (let [home    (str (Files/createTempDirectory "wmark-cli" (make-array FileAttribute 0)))
        edition {:edition :community :entitlements-fn (fn [_] (features/community))}
        out     (with-out-str
                  (is (= 0 (app/run-cli edition ["--home" home "run" "--help"]))))]
    (is (str/includes? out "Usage: wmark run [options] INPUT..."))
    (is (str/includes? out "--dry-run"))
    (is (str/includes? out "canary (Pro)"))
    (is (not (str/includes? out "subliminal")) "users never see the canary mode's wire id")))

(deftest the-cli-takes-and-shows-canary
  (let [home    (str (Files/createTempDirectory "wmark-cli" (make-array FileAttribute 0)))
        edition {:edition :community :entitlements-fn (fn [_] (features/community))}
        err     (java.io.StringWriter.)
        code    (binding [*err* err]
                  (app/run-cli edition ["--home" home "run" "--dry-run" "--text-mode" "canary"
                                        "--text" "(c) Studio" (str home "/clip.mp4")]))]
    (is (= 1 code) "the community edition refuses it")
    (is (str/includes? (str err) "Not available on the community plan: Flash-frame canaries") (str err))
    (is (str/includes? (str err) "Locked features: Flash-frame canaries") (str err))
    (is (not (str/includes? (str err) "subliminal")) (str err))))

(defn- cli [home & args]
  (let [edition {:edition :community :entitlements-fn (fn [_] (features/community))}
        err     (java.io.StringWriter.)
        out     (with-out-str (binding [*err* err] (app/run-cli edition (into ["--home" home] args))))]
    {:out out :err (str err)}))

(deftest profiles-show-and-effective-use-display-names
  (let [home (str (Files/createTempDirectory "wmark-cli" (make-array FileAttribute 0)))]
    (cli home "profiles" "save" "Evidence" "--clean" "--text" "(c) Studio" "--text-mode" "canary" "--opacity" "0.7")
    (testing "show: the stored profile, text modes by display name"
      (let [{:keys [out]} (cli home "profiles" "show" "Evidence")]
        (is (str/includes? out ":mode :canary") out)
        (is (not (str/includes? out "subliminal")) out)))
    (testing "effective: every setting, its value and where it came from"
      (let [{:keys [out]} (cli home "profiles" "effective" "Evidence")]
        (is (str/includes? out "Base: profile \"Evidence\"") out)
        (is (re-find #"logo\.opacity\s+0\.7\s+from profile \"Evidence\"" out) out)
        (is (re-find #"logo\.anchor\s+\S+\s+built-in default" out) out)
        (is (str/includes? out "\"mode\":\"canary\"") out)
        (is (str/includes? out "Needs wmark Pro to run: Flash-frame canaries") out)
        (is (not (str/includes? out "subliminal")) out)))
    (testing "effective --clean: built-in defaults only"
      (let [{:keys [out]} (cli home "profiles" "effective" "--clean")]
        (is (str/includes? out "Base: built-in defaults") out)
        (is (not (str/includes? out "from profile")) out)
        (is (not (str/includes? out "Needs wmark Pro")) out)))
    (testing "an unknown profile is an error, never a silent fallback"
      (let [{:keys [err]} (cli home "profiles" "effective" "Nope")]
        (is (str/includes? err "Nope") err)))))

(deftest run-takes-a-progress-mode
  (let [home (str (Files/createTempDirectory "wmark-cli" (make-array FileAttribute 0)))]
    (is (str/includes? (:out (cli home "run" "--help")) "--progress MODE"))
    (is (str/includes? (:err (cli home "run" "--progress" "fancy" "x.mp4")) "must be auto, bar, lines or none"))))

(deftest run-embeds-a-cover-when-asked
  (let [home (str (Files/createTempDirectory "wmark-cli" (make-array FileAttribute 0)))]
    (testing "offered by run, as a number of seconds"
      (is (str/includes? (:out (cli home "run" "--help")) "--cover-at SEC"))
      (is (str/includes? (:err (cli home "run" "--dry-run" "--cover-at" "soon" "x.mp4")) "must be a number")))
    (if-not (try (zero? (.waitFor (start "ffmpeg" "-version"))) (catch java.io.IOException _ false))
      (println "  (skipped: ffmpeg not installed)")
      (let [clip (str home "/clip.mp4")]
        (.waitFor (start "ffmpeg" "-nostdin" "-v" "error" "-f" "lavfi" "-i" "testsrc2=d=1:s=160x90:r=25"
                         "-c:v" "mpeg4" clip))
        (testing "a dry run shows the cover in the command: the frame's still, embedded as the attached picture"
          (let [{:keys [out err]} (cli home "run" "--dry-run" "--clean" "--cover-at" "0.5" clip)]
            (is (str/includes? out "clip_wm.part.mp4.cover.png") (str out err))
            (is (str/includes? out "attached_pic") out)))))))

