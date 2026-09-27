;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.tui.main-test
  "wmark-tui against the real server on an ephemeral loopback port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.app :as app]
            [watermark.core.features :as features]
            [watermark.server.http :as http]
            [watermark.tui.main :as tui])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- temp-dir [] (str (Files/createTempDirectory "wmark-tui" (make-array FileAttribute 0))))

(deftest options-and-exit-codes
  (let [out (with-out-str (is (= 0 (tui/run ["--help"]))))]
    (is (str/includes? out "Usage: wmark-tui [options]"))
    (is (str/includes? out "--url URL")))
  (is (= 2 (tui/run ["--bogus"])) "an unknown option is a usage error")
  (is (str/includes? (with-out-str (is (= 1 (tui/run ["--home" (temp-dir)]))))
                     "No running wmark server found")
      "no runtime file, no server: say so and fail"))

(deftest a-session-against-a-running-engine
  (let [sys (app/with-jobs (app/system {:edition :community :entitlements-fn (fn [_] (features/community))}
                                       {:home (temp-dir)}))
        srv (http/start! sys {})]
    (try
      (let [out (with-out-str
                  (with-in-str "new Smoke test\nlist\nquit\n"
                    (is (= 0 (tui/run ["--url" (:url srv) "--token" (:token srv)])))))]
        (testing "it connects, creates a profile through the API and lists it"
          (is (str/includes? out "community edition"))
          (is (re-find #"1  Smoke test" out) out)))
      (finally (http/stop! sys srv)))))
