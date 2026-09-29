;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.architecture-test
  "Architecture fitness tests: the dependency rules that keep the roadmap
  cheap. Each rule protects a future stage:

    kernel is portable       -> the Flutter/ClojureDart GUIs (Stages 3-4)
                                run the same planning code in-process;
                                kernel/dart runs it on the Dart VM
    jobs never see engines   -> AVFoundation/Media3/GPU engines (Stage 4+)
                                plug in without touching orchestration
    host core has no desktop -> a hosted backend (Stage 5) reuses the core
                                without http-kit or local files
    web UI sits on the API   -> the same views serve the local UI and a
                                hosted dashboard; it never touches engines
    open core stays open     -> no commercial namespace is named, required
                                or licensed here; the commercial repository
                                depends on this one, never the reverse

  Rules read `ns` forms and file headers only, so they run in milliseconds."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.architecture :as arch :refer [sources violations under?]]))

(set! *warn-on-reflection* true)

(def kernel (sources "kernel/src"))
(def host   (sources "src"))

(def public-roots
  "Everything clogem-wmark publishes, as source roots."
  ["kernel" "src" "test" "testkit" "web" "desktop" "build" "native" "resources" "spikes"
   "scripts/cloud-setup.sh"])

(def vendored
  "Third-party files kept under their own licenses (see their notices)."
  ["web/test/datastar-sdk-cases/" "web/resources/public/datastar.js"])

(deftest the-kernel-is-portable
  (testing "only .cljc files"
    (is (every? :cljc? kernel)))
  (testing "requires nothing but the kernel, clojure.string and (in two places, on the JVM) malli"
    (let [kernel-nses (set (map :ns kernel))]
      (is (empty? (violations kernel #(not (or (kernel-nses %) (= 'clojure.string %)
                                               (str/starts-with? (str %) "malli."))))))))
  (testing "malli only where schemas live, for JSON Schema; watermark.util.schema validates everywhere"
    (is (= #{'watermark.core.schema 'watermark.render.schema}
           (set (map first (violations kernel #(str/starts-with? (str %) "malli."))))))))

(defn- aliases
  "The :as aliases a source's ns form gives its requires."
  [{:keys [file]}]
  (->> (rest (arch/ns-form (io/file file)))
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (keep #(when (vector? %) (second (drop-while (complement #{:as}) %))))
       set))

(deftest the-kernel-runs-on-the-dart-vm
  (testing "the Dart VM harness loads every kernel namespace (kernel/dart)"
    (let [[harness] (sources "kernel/dart/test/watermark/dart/kernel_test.cljd")]
      (is (= (set (map :ns kernel)) (disj (:requires harness) 'clojure.test)))))
  (testing "no alias ClojureDart reads as a Dart type (num/x would call a static member of num)"
    (is (empty? (for [src kernel, a (aliases src)
                      :when ('#{num int double bool dynamic void} a)]
                  [(:ns src) a])))))

(deftest orchestration-never-sees-an-engine-implementation
  (let [jobs (filter #(str/starts-with? (str (:ns %)) "watermark.core.") host)]
    (is (some #(= 'watermark.core.jobs (:ns %)) jobs))
    (is (empty? (violations jobs #(under? ["watermark.engine." "watermark.ffmpeg." "watermark.media." "watermark.store."
                                           "watermark.raster.local"
                                           "watermark.util.os" "watermark.util.locate"
                                           "watermark.server" "org.httpkit"] %)))
        "core namespaces use the watermark.engine / watermark.media ports, never implementations")))

(deftest the-host-core-has-no-desktop-or-edition-code
  (is (empty? (violations host #(under? ["watermark.app" "watermark.main" "watermark.server.http"
                                         "watermark.server.security" "watermark.server.static"
                                         "watermark.pro" "watermark.saas" "org.httpkit"] %)))
      "src/ is shared by desktop and hosted builds"))

(deftest the-web-ui-talks-to-the-core-api-only
  (let [web (sources "web/src")]
    (is (seq web))
    (is (empty? (violations web #(under? ["watermark.engine" "watermark.store" "watermark.media"
                                          "watermark.config" "watermark.core.jobs" "watermark.ffmpeg"
                                          "watermark.app" "watermark.main"
                                          "watermark.server.http" "watermark.server.security"
                                          "watermark.server.static" "watermark.pro" "watermark.saas"
                                          "watermark.util"] %)))
        "views and handlers go through watermark.core.api, like any other client"))
  (is (empty? (violations host #(under? ["watermark.web"] %))) "the host core doesn't know the UI exists"))

(deftest the-open-core-names-no-commercial-code
  (let [nses (mapcat sources ["kernel/src" "src" "web/src" "desktop/src" "testkit/src" "build/src"
                              "kernel/test" "test" "web/test" "desktop/test"])]
    (is (< 50 (count nses)))
    (is (empty? (filter #(under? ["watermark.pro" "watermark.saas"] (:ns %)) nses))
        "no namespace of the commercial editions lives in the open core")
    (is (empty? (violations nses #(under? ["watermark.pro" "watermark.saas" "wmark.dev"] %)))
        "the open core requires nothing from the commercial editions; they plug in through registries and ports")))

(deftest every-namespace-warns-on-reflection
  (let [files (->> ["kernel/src" "src" "web/src" "desktop/src" "testkit/src" "build/src"
                    "kernel/test" "test" "web/test" "desktop/test"]
                   (mapcat sources)
                   (map (comp io/file :file)))]
    (is (< 50 (count files)))
    (is (empty? (remove arch/warns-on-reflection? files))
        "(set! *warn-on-reflection* true) follows every ns form, so no reflective call compiles silently")))

(deftest every-source-file-carries-the-open-license
  (let [files (arch/code-files public-roots :excluded vendored)]
    (is (< 50 (count files)))
    (testing "SPDX-License-Identifier: EPL-2.0 at the top of each file"
      (is (empty? (remove #(= arch/open-license (arch/spdx-id %)) files))))
    (testing "no file here carries the commercial repository's license marker"
      (is (empty? (filter #(arch/mentions? % arch/closed-license) files))))))
