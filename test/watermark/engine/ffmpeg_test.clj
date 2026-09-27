;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.ffmpeg-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.util.os :as os])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- tmp [] (str (Files/createTempDirectory "wmark-ff" (make-array FileAttribute 0))))

(defn- touch! ^File [dir rel]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f "#!/bin/sh\n")
    (.setExecutable f true)
    f))

(deftest ffprobe-comes-from-ffmpegs-folder
  (let [bundle (tmp), elsewhere (tmp)
        ffmpeg (touch! bundle (os/exe-name "ffmpeg"))
        own    (touch! bundle (os/exe-name "ffprobe"))
        other  (touch! elsewhere (str "bin/" (os/exe-name "ffprobe")))
        find   #(ffmpeg/locate-binaries {:ffmpeg (str ffmpeg) :search [:cwd-bin] :cwd elsewhere :app-dir nil})]
    (testing "the ffprobe next to the chosen ffmpeg wins over any other"
      (let [bins (find)]
        (is (= (.getAbsolutePath own) (get-in bins [:ffprobe :path])))
        (is (nil? (ffmpeg/split-build-warning bins)))))
    (testing "without one, the normal search applies, and the split is flagged"
      (io/delete-file own)
      (let [bins (find)]
        (is (= (.getAbsolutePath other) (get-in bins [:ffprobe :path])))
        (is (re-find #"Ship both together" (str (ffmpeg/split-build-warning bins))))))))
