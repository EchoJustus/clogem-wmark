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

(deftest ffmpeg-pins-are-exact
  (let [pin {:version "9.0.2" :archives [{:url "https://example.org/ffmpeg.zip" :sha256 (apply str (repeat 64 "a"))}]
             :source ["https://example.org/src"]}
        ok  {:bundles {:b {:sidecars [:ffmpeg]}}
             :ffmpeg  {:license   {:text {:url "https://example.org/COPYING" :sha256 (apply str (repeat 64 "b"))}}
                       :platforms {:linux-x64 pin}}}]
    (is (empty? (build/ffmpeg-pin-problems ok)))
    (is (seq (build/ffmpeg-pin-problems (assoc-in ok [:ffmpeg :platforms :linux-x64 :archives 0 :url] "http://example.org/x.zip")))
        "plain http is not a pin")
    (is (seq (build/ffmpeg-pin-problems (assoc-in ok [:ffmpeg :platforms :linux-x64 :archives 0 :sha256] "latest"))))
    (is (seq (build/ffmpeg-pin-problems (update-in ok [:ffmpeg :platforms :linux-x64] dissoc :source))))
    (is (seq (build/ffmpeg-pin-problems (dissoc ok :ffmpeg))) "bundles with sidecars need pins")))

(deftest licenses-come-from-the-pom-or-its-parents
  (let [repo  (.toFile (java.nio.file.Files/createTempDirectory "m2" (make-array java.nio.file.attribute.FileAttribute 0)))
        write (fn [path xml] (let [f (clojure.java.io/file repo path)] (clojure.java.io/make-parents f) (spit f xml) f))
        _     (write "org/example/parent/1/parent-1.pom"
                     "<project><licenses><license><name>EPL-1.0</name><url>https://e.org/epl</url></license></licenses></project>")
        child (write "org/example/lib/2/lib-2.pom"
                     "<project><parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version></parent></project>")]
    (is (= [{:name "EPL-1.0" :url "https://e.org/epl"}] (build/pom-licenses repo child)))))

(deftest the-sdk-names-the-abi-it-carries
  (is (= 1 (build/abi-version)) "read from native/include/wmark_engine.h")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doesn't match WMARK_ENGINE_ABI_VERSION"
                        (build/sdk {:name "abi-v99"})))
  (is (every? #(.isFile (clojure.java.io/file (first %))) build/sdk-files) "every SDK input exists"))

(deftest git-dependencies-name-their-license-and-source
  (is (= "EPL-2.0" (build/git-license "kernel/src")) "read from the SPDX header of its sources")
  (let [text (build/notices-text {:libs {'wmark/kernel {:git/url "https://github.com/EchoJustus/clogem-wmark.git"
                                                        :git/sha "a3b74780f1357b33cd7f8a33c61b68a19c6aa082"
                                                        :paths   ["kernel/src"]}}}
                                 "Third-party notices")]
    (is (clojure.string/includes? text "License: EPL-2.0"))
    (is (clojure.string/includes? text "Source: https://github.com/EchoJustus/clogem-wmark/tree/a3b74780f1357b33cd7f8a33c61b68a19c6aa082")
        "EPL-2.0 3.1: where to get the source of the exact commit")))

(deftest windows-binaries-embed-the-utf8-manifest
  (let [[embed input] (build/windows-link-options)
        manifest (slurp (subs input (count "-H:NativeLinkerOption=/MANIFESTINPUT:")))]
    (is (= "-H:NativeLinkerOption=/MANIFEST:EMBED" embed))
    (is (clojure.string/includes? manifest "<activeCodePage xmlns=\"http://schemas.microsoft.com/SMI/2019/WindowsSettings\">UTF-8</activeCodePage>"))))
