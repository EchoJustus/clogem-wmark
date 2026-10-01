;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns wmark.build-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wmark.build :as build])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

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
    (is (seq (build/ffmpeg-pin-problems (dissoc ok :ffmpeg))) "bundles with sidecars need pins")
    (testing "variants (the LGPL build) are pinned the same way, license texts included"
      (let [lgpl {:license   {:texts [{:file "COPYING.LGPLv3" :url "https://example.org/L" :sha256 (apply str (repeat 64 "c"))}]}
                  :platforms {:linux-x64 pin}}]
        (is (empty? (build/ffmpeg-pin-problems (assoc-in ok [:ffmpeg :variants :lgpl] lgpl))))
        (is (seq (build/ffmpeg-pin-problems (assoc-in ok [:ffmpeg :variants :lgpl]
                                                     (assoc-in lgpl [:platforms :linux-x64 :archives 0 :sha256] "latest")))))
        (is (seq (build/ffmpeg-pin-problems (assoc-in ok [:ffmpeg :variants :lgpl]
                                                     (assoc-in lgpl [:license :texts 0 :url] "http://example.org/L")))))))
    (testing "a platform built from source pins its source archive and states its configure flags"
      (let [src  {:version "9.0.2" :source ["the archive"]
                  :build {:source {:url "https://example.org/ffmpeg-9.0.2.tar.xz" :sha256 (apply str (repeat 64 "d"))}
                          :configure ["--enable-version3"]}}
            with #(assoc-in ok [:ffmpeg :platforms :macos-arm64] %)]
        (is (empty? (build/ffmpeg-pin-problems (with src))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in src [:build :source :sha256] "latest")))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in src [:build :configure] [])))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc src :archives (:archives pin)))))
            "archives or a recipe, not both")))
    (testing "a cross-compiled recipe pins its compiler and the libraries it builds first"
      (let [sha   (apply str (repeat 64 "e"))
            cross {:version "9.0.2" :source ["the archive"]
                   :build {:on        :linux-x64
                           :toolchain {:url "https://example.org/toolchain.tar.xz" :sha256 sha}
                           :libraries [{:source {:url "https://example.org/zlib.tar.gz" :sha256 sha}
                                        :env    {"CC" "x86_64-w64-mingw32-clang"}
                                        :steps  [["./configure" "--static" "--prefix=${PREFIX}"] ["make" "install"]]}]
                           :source    {:url "https://example.org/ffmpeg-9.0.2.tar.xz" :sha256 sha}
                           :configure ["--enable-cross-compile" "--target-os=mingw32" "--extra-cflags=-I${PREFIX}/include"]}}
            with  #(assoc-in ok [:ffmpeg :platforms :windows-x64] %)]
        (is (empty? (build/ffmpeg-pin-problems (with cross))))
        (is (= :linux-x64 (build/builds-on :windows-x64 cross)) "it builds on Linux, for Windows")
        (is (= :macos-arm64 (build/builds-on :macos-arm64 {:build {:configure []}})) "a recipe without :on builds on its own platform")
        (is (nil? (build/builds-on :linux-x64 pin)) "archives build nothing")
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in cross [:build :toolchain :sha256] "latest")))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in cross [:build :libraries 0 :source :url] "http://example.org/zlib.tar.gz")))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in cross [:build :libraries 0 :steps] [])))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in cross [:build :libraries 0 :env] {"CC" :clang})))))
        (is (seq (build/ffmpeg-pin-problems (with (assoc-in cross [:build :on] "linux-x64")))))
        (testing "an LGPL recipe configures no GPL or nonfree parts"
          (let [lgpl (assoc-in ok [:ffmpeg :license :spdx] "LGPL-3.0-or-later")]
            (is (empty? (build/ffmpeg-pin-problems (assoc-in lgpl [:ffmpeg :platforms :windows-x64] cross))))
            (is (seq (build/ffmpeg-pin-problems (assoc-in lgpl [:ffmpeg :platforms :windows-x64]
                                                          (update-in cross [:build :configure] conj "--enable-gpl")))))))))))

(defn- pe-file
  "A minimal Windows program (PE32+) in `dir` named `n`: built for
  `machine`, importing `dlls`, with one section holding the import table."
  [dir n machine dlls]
  (let [b     (.order (ByteBuffer/allocate 4096) ByteOrder/LITTLE_ENDIAN)
        pe    0x80, coff (+ pe 4), opt (+ coff 20), opt-size 240, sec (+ opt opt-size)
        raw   0x400, va 0x1000
        names (+ raw (* 20 (inc (count dlls))))]
    (.putShort b 0 (unchecked-short 0x5a4d))
    (.putInt b 0x3c (int pe))
    (.putInt b (int pe) (int 0x4550))
    (.putShort b (int coff) (unchecked-short machine))
    (.putShort b (int (+ coff 2)) (short 1))
    (.putShort b (int (+ coff 16)) (short opt-size))
    (.putShort b (int opt) (unchecked-short 0x20b))
    (.putInt b (int (+ opt 112 8)) (int va))
    (doseq [[at v] [[8 0x1000] [12 va] [16 0x1000] [20 raw]]] (.putInt b (int (+ sec at)) (int v)))
    (loop [[d & ds] dlls, i 0, at names]
      (when d
        (.putInt b (int (+ raw (* 20 i) 12)) (int (+ va (- at raw))))
        (.put b (int at) (.getBytes (str d "\u0000") "US-ASCII"))
        (recur ds (inc i) (+ at (inc (count d))))))
    (let [f (io/file dir n)] (io/copy (.array b) f) f)))

(deftest windows-downloads-are-checked-from-their-headers
  (let [dir  (str (Files/createTempDirectory "wmark-pe" (make-array FileAttribute 0)))
        crt  ["KERNEL32.dll" "api-ms-win-crt-runtime-l1-1-0.dll" "bcrypt.dll" "ole32.dll"]
        both (fn [machine dlls] (doseq [n ["ffmpeg.exe" "ffprobe.exe"]] (pe-file dir n machine dlls)))]
    (testing "machine and imports, read on any OS"
      (both 0x8664 crt)
      (is (= {:machine :x64 :imports crt} (build/pe-info (io/file dir "ffmpeg.exe"))))
      (is (empty? (build/windows-program-problems :windows-x64 dir)))
      (is (= 2 (count (build/windows-program-problems :windows-arm64 dir))) "an x64 build is not an Arm64 one"))
    (testing "Arm64"
      (both 0xaa64 crt)
      (is (= :arm64 (:machine (build/pe-info (io/file dir "ffprobe.exe")))))
      (is (empty? (build/windows-program-problems :windows-arm64 dir))))
    (testing "a DLL that doesn't ship with Windows would be missing from the download"
      (both 0x8664 (conj crt "libwinpthread-1.dll"))
      (is (re-find #"libwinpthread-1\.dll" (first (build/windows-program-problems :windows-x64 dir)))))
    (is (every? build/windows-dll? ["KERNEL32.dll" "USER32.DLL" "api-ms-win-crt-heap-l1-1-0.dll" "mfplat.dll"]))
    (is (not-any? build/windows-dll? ["vcruntime140.dll" "msvcp140.dll" "libc++.dll" "zlib1.dll" "avcodec-62.dll"]))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a Windows program" (build/pe-info "deps.edn")))))

(deftest the-downloads-bundle-lgpl-ffmpeg
  ;; ADR 0001 (owner, 2026-09-27): LGPL builds by default on every release
  ;; platform; GPL builds only as the :gpl variant
  (let [{:keys [license platforms variants]} (:ffmpeg (:wmark/build-matrix (edn/read-string (slurp "deps.edn"))))
        release (set (map keyword (re-seq #"(?<=platform: )[a-z0-9-]+" (slurp ".github/workflows/release.yml"))))]
    (is (= "LGPL-3.0-or-later" (:spdx license)))
    (is (= #{"COPYING.LGPLv3" "COPYING.GPLv3"} (set (map :file (build/license-texts license)))))
    (is (= #{:linux-x64 :windows-x64 :macos-arm64 :macos-x64} release))
    (is (every? platforms release) "every platform the release builds has an LGPL pin")
    (is (every? #(get-in platforms [% :build]) [:macos-arm64 :macos-x64])
        "no maintained macOS LGPL build exists: macOS builds FFmpeg's source")
    (is (not-any? #(some #{"--enable-gpl" "--enable-nonfree"} (get-in platforms [% :build :configure])) (keys platforms)))
    (is (= "GPL-3.0-or-later" (get-in variants [:gpl :license :spdx])))))

(deftest licenses-come-from-the-pom-or-its-parents
  (let [repo  (.toFile (java.nio.file.Files/createTempDirectory "m2" (make-array java.nio.file.attribute.FileAttribute 0)))
        write (fn [path xml] (let [f (clojure.java.io/file repo path)] (clojure.java.io/make-parents f) (spit f xml) f))
        _     (write "org/example/parent/1/parent-1.pom"
                     "<project><licenses><license><name>EPL-1.0</name><url>https://e.org/epl</url></license></licenses></project>")
        child (write "org/example/lib/2/lib-2.pom"
                     "<project><parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1</version></parent></project>")]
    (is (= [{:name "EPL-1.0" :url "https://e.org/epl"}] (build/pom-licenses repo child)))))

(deftest the-sdk-names-the-abi-it-carries
  (is (= 2 (build/abi-version)) "read from native/include/wmark_engine.h")
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

(deftest intel-macs-build-on-graalvm-25-0-1
  (is (= "25.0.1" (build/graalvm-version "native-image 25.0.1 2025-10-21\nGraalVM Runtime Environment GraalVM CE 25.0.1+8.1")))
  (is (= "25.0.2" (build/graalvm-version "native-image 25.0.2 2026-01-20\n")))
  (is (nil? (build/graalvm-version "command not found")))
  (let [pinned (get-in (edn/read-string (slurp "deps.edn")) [:wmark/build-matrix :graalvm :macos-x64])]
    (is (= "25.0.1" pinned) "GraalVM 25.0.2 dropped macOS x64 (ADR 0007)")
    (doseq [wf [".github/workflows/ci.yml" ".github/workflows/release.yml"]
            :let [entries (re-seq #"platform: ([a-z0-9-]+), +graalvm: \"([0-9.]+)\"" (slurp wf))]]
      (is (= 4 (count entries)) (str wf " builds four platforms, each naming its GraalVM"))
      (is (= [pinned] (for [[_ p v] entries :when (= "macos-x64" p)] v))
          (str wf ": the macos-x64 entry must name the release deps.edn pins")))))
