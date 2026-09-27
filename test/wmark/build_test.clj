;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns wmark.build-test
  (:require [clojure.test :refer [deftest is]]
            [wmark.build :as build]))

(set! *warn-on-reflection* true)

(deftest the-uberjar-carries-every-project-folder
  ;; the shape tools.deps gives a basis for `-A:desktop:web`
  (let [basis {:paths     ["src" "resources"]
               :classpath {"src"                      {:path-key :paths}
                           "resources"                {:path-key :paths}
                           "desktop/src"              {:path-key :extra-paths}
                           "desktop/resources"        {:path-key :extra-paths}
                           "web/src"                  {:path-key :extra-paths}
                           "web/resources"            {:path-key :extra-paths}
                           "kernel/src"               {:lib-name 'wmark/kernel}
                           "/m2/http-kit-2.8.1.jar"   {:lib-name 'http-kit/http-kit}}}]
    (is (= ["desktop/resources" "desktop/src" "resources" "src" "web/resources" "web/src"]
           (build/project-dirs basis))
        "component resources (datastar.js, app.css, reachability-metadata.json) are copied; libraries are left to uber")))
