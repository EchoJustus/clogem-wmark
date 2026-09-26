;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.app-test
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.app :as app]
            [watermark.util.os :as os])
  (:import (java.lang ProcessBuilder)
           (java.util List)))

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
