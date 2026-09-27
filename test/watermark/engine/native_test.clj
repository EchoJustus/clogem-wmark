;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.native-test
  "The FFM binding against native/mock (compiled here with the system C
  compiler; skipped when there is none). Proves the C ABI end to end:
  lookup, version handshake, JSON exchange, upcalls from a native thread,
  cancellation -- and that the job pipeline runs on it unchanged."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.jobs :as jobs]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.engine :as engine]
            [watermark.engine.native :as native]
            [watermark.media.local :as media])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- tmp [] (str (Files/createTempDirectory "wmark-native" (make-array FileAttribute 0))))

(def mock-library
  (delay
    (let [dir (tmp)
          out (str (io/file dir (native/library-name)))
          {:keys [exit err]} (try (sh/sh "cc" "-shared" "-fPIC" "-O2" "-pthread" "-o" out "native/mock/mock_engine.c")
                                  (catch Exception e {:exit -1 :err (ex-message e)}))]
      (if (zero? exit) out (do (println "  (skipped native tests: no C compiler:" err ")") nil)))))

(defmacro with-mock [[sym] & body]
  `(when-let [~sym @mock-library] ~@body))

(deftest no-library-means-unavailable-not-broken
  (let [info (engine/info (native/native-engine {:library "/nonexistent/lib.so" :search []}))]
    (is (false? (:available? info)))
    (is (re-find #"No native engine library" (first (:problems info))))
    (is (= :unavailable (try (engine/probe (native/native-engine {:search []}) "x.mp4") nil
                             (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e))))))))

(deftest abi-round-trip
  (with-mock [lib]
    (let [e (native/native-engine {:library lib})]
      (testing "handshake and capabilities"
        (is (= :mock (:engine/id (engine/info e))))
        (is (= #{:h264} (get-in (engine/info e) [:capabilities :codecs]))))
      (testing "probe and structured errors"
        (is (= {:kind :video :width 640 :fps-num 30} (select-keys (engine/probe e "/in.mp4") [:kind :width :fps-num])))
        (is (= :invalid (try (engine/probe e "/missing.mp4") nil
                             (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x)))))))
      (testing "render with events from the library's thread, then cancel"
        (let [out    (str (io/file (tmp) "o.part.mp4"))
              plan   (engine/prepare e {:spec {:layers [] :timebase {:frames 90}} :encode {:codec :h264 :audio :none}
                                        :output {:path out :container "mp4"}})
              events (atom [])
              h      (engine/execute! e plan #(swap! events conj %))]
          (is (= {:status :done} (deref (engine/outcome h) 5000 :timeout)))
          (is (= 10 (count @events)))
          (is (= "mock render\n" (slurp out)))
          (let [h2 (engine/execute! e plan (fn [_]))]
            (Thread/sleep 40)
            (engine/cancel! h2)
            (is (= {:status :cancelled} (deref (engine/outcome h2) 5000 :timeout))))))
      (testing "capability negotiation happens before the library is asked"
        (is (= :unsupported (try (engine/prepare e {:spec {:layers []} :encode {:codec :hevc}
                                                    :output {:path "x" :container "mp4"}}) nil
                                 (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x))))))))))

(deftest the-job-pipeline-runs-unchanged-on-a-native-engine
  (with-mock [lib]
    (let [dir (tmp)
          in  (str (io/file dir "clip.mov"))
          env {:engine (native/native-engine {:library lib}) :media (media/local-media)
               :entitlements (features/community) :secret-for (constantly (byte-array 32))
               :font (delay "/fonts/a.ttf")}]
      (spit in "stand-in input")
      (let [[r] (jobs/run-job! env {:ctx {} :inputs [in]
                                    :settings (resolve/deep-merge schema/defaults
                                                                  {:logo {:path "/l.png"} :encode {:audio :none}})}
                               {})]
        (is (= :done (:state r)))
        (is (= "mock render\n" (slurp (io/file dir "clip_wm.mp4"))))))))
