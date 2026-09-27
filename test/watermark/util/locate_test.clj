;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.locate-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [watermark.util.locate :as locate])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- tmp [] (str (Files/createTempDirectory "wmark-locate" (make-array FileAttribute 0))))

(defn- touch! [dir name exec?]
  (let [f (io/file dir name)]
    (io/make-parents f)
    (spit f "#!/bin/sh\n")
    (.setExecutable f exec?)
    (str (.getAbsolutePath f))))

(defn- find-ff [opts]
  (locate/locate (merge {:names ["ffmpeg"] :ok? #(and (.isFile ^java.io.File %) (.canExecute ^java.io.File %))
                         :search [:cwd :cwd-bin :app :app-bin]}
                        opts)))

(deftest search-order
  (let [cwd (tmp) app (tmp)]
    (touch! app "bin/ffmpeg" true)
    (is (= :app-bin (:source (find-ff {:cwd cwd :app-dir app}))) "falls back to the install folder")
    (touch! cwd "bin/ffmpeg" true)
    (is (= :cwd-bin (:source (find-ff {:cwd cwd :app-dir app}))) "./bin/ beats the install folder")
    (touch! cwd "ffmpeg" true)
    (is (= :cwd (:source (find-ff {:cwd cwd :app-dir app}))) "./ffmpeg beats ./bin/")
    (is (= :app-bin (:source (find-ff {:cwd cwd :app-dir app :search [:app :app-bin]})))
        "hardened order: no working-directory lookups")))

(deftest explicit-paths-and-diagnostics
  (let [cwd (tmp) other (tmp)]
    (let [f (touch! other "ffmpeg" true)]
      (is (= {:path f :source :explicit} (select-keys (find-ff {:cwd cwd :app-dir nil :explicit f}) [:path :source])))
      (is (= :explicit (:source (find-ff {:cwd cwd :app-dir nil :explicit other}))) "a directory works too"))
    (touch! cwd "ffmpeg" false)
    (let [r (find-ff {:cwd cwd :app-dir nil})]
      (is (nil? (:path r)) "a non-executable file is not used...")
      (is (= [:unusable :missing] (map :status (:trail r))) "...but shows up in the trail"))))
