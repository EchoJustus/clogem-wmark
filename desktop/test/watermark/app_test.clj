;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.app-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.app :as app]
            [watermark.core.features :as features]
            [watermark.util.os :as os])
  (:import (java.lang ProcessBuilder)
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
