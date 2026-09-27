;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.jobs-test
  "The job pipeline against a fake engine: no FFmpeg, no native library. If
  jobs only ever talks to the watermark.engine protocols, a test double is
  all it needs -- which is exactly what these tests show."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [watermark.core.features :as features]
            [watermark.core.jobs :as jobs]
            [watermark.core.jobs.local :as local]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.engine :as engine]
            [watermark.media.local :as media])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [t]
    (let [dir (str (Files/createTempDirectory "wmark-jobs" (make-array FileAttribute 0)))]
      (binding [*dir* dir] (t))
      (doseq [^java.io.File f (reverse (file-seq (io/file dir)))] (.delete f)))))

(defrecord FakeHandle [result cancelled]
  engine/RenderHandle
  (cancel! [_] (reset! cancelled true))
  (outcome [_] result))

(defrecord FakeEngine [calls behaviour]
  engine/VideoEngine
  (info [_] {:engine/id :fake :available? true
             :capabilities {:layers #{:image :text} :animations #{:flip-y} :timing #{:always :windows :periodic}
                            :placement #{:fixed} :codecs #{:h264} :containers #{"mp4"} :audio #{:copy :none}}})
  (probe [_ source]
    (swap! calls conj [:probe source])
    (if (.endsWith (str source) ".png")
      {:kind :image :width 400 :height 160}
      {:kind :video :width 1280 :height 720 :fps-num 30 :fps-den 1 :frames 300 :duration-s 10.0 :start-s 0.0}))
  (prepare [this request]
    (swap! calls conj [:prepare request])
    (engine/check! (engine/info this) request)
    {:engine :fake :output (get-in request [:output :path]) :source (:source request)})
  (execute! [_ plan listener]
    (swap! calls conj [:execute plan])
    (let [result (promise) cancelled (atom false)
          mode   (@behaviour (:source plan) :ok)]
      (future
        (dotimes [i 5]
          (Thread/sleep (if (= mode :slow) 60 5))
          (listener {:event :progress :fraction (/ (inc i) 5.0)}))
        (deliver result
                 (cond @cancelled   {:status :cancelled}
                       (= mode :fail) (engine/failed "boom")
                       :else        (do (spit (:output plan) "frames") {:status :done}))))
      (->FakeHandle result cancelled))))

(defn- fake-env [& {:keys [behaviour]}]
  (let [calls (atom [])]
    {:calls        calls
     :engine       (->FakeEngine calls (atom (or behaviour {})))
     :media        (media/local-media)
     :entitlements (features/community)
     :secret-for   (constantly (byte-array 32))
     :font         (delay "/fonts/a.ttf")}))

(defn- input! [name] (let [f (io/file *dir* name)] (spit f "not really a video") (str f)))

(defn- settings [& [extra]]
  (resolve/deep-merge schema/defaults {:logo {:path "/logos/l.png"}
                                       :texts [{:mode :continuous :content "(c)"}]}
                      extra))

(deftest renders-and-publishes-through-the-protocol
  (let [env    (fake-env)
        events (atom [])
        [a b]  [(input! "a.mov") (input! "b.mov")]
        results (jobs/run-job! env {:ctx {} :settings (settings) :inputs [a b]}
                               {:on-event #(swap! events conj %)})]
    (is (= [:done :done] (map :state results)))
    (is (= (str (io/file *dir* "a_wm.mp4")) (:output (first results))))
    (is (= "frames" (slurp (io/file *dir* "a_wm.mp4"))) "published from the temp file")
    (is (not (.exists (io/file *dir* "a_wm.part.mp4"))))
    (let [request (some (fn [[k v]] (when (= k :prepare) v)) @(:calls env))]
      (is (= [:image :text] (map :kind (get-in request [:spec :layers]))) "the engine receives a render spec")
      (is (= (str (io/file *dir* "a_wm.part.mp4")) (get-in request [:output :path]))))
    (is (= [:started :progress :progress :progress :progress :progress :finished]
           (map :type (filter #(= (str a) (:input %)) @events))))))

(deftest failures-stay-per-input
  (let [[a b c] [(input! "a.mov") (input! "b.mov") (str (io/file *dir* "missing.mov"))]
        env     (fake-env :behaviour {a :fail})
        results (jobs/run-job! env {:ctx {} :settings (settings) :inputs [a b c]} {})]
    (is (= [:failed :done :failed] (map :state results)))
    (is (= "boom" (:error (first results))))
    (is (not (.exists (io/file *dir* "a_wm.part.mp4"))) "a failed render leaves no temp file")
    (is (= :invalid (:kind (nth results 2))) "missing input: rejected before the engine is asked")
    (is (not-any? #(= [:probe c] %) @(:calls env)))))

(deftest existing-outputs-are-not-overwritten
  (let [a (input! "a.mov")]
    (spit (io/file *dir* "a_wm.mp4") "earlier result")
    (let [env (fake-env) [r] (jobs/run-job! env {:ctx {} :settings (settings) :inputs [a]} {})]
      (is (= [:failed :conflict] [(:state r) (:kind r)]))
      (is (not-any? #(= :execute (first %)) @(:calls env)) "refused before any rendering time is spent"))))

(deftest capability-gaps-are-reported-not-rendered
  (let [a (input! "a.mov")
        [r] (jobs/run-job! (fake-env) {:ctx {} :settings (settings {:encode {:codec :hevc}}) :inputs [a]} {})]
    (is (= [:failed :unsupported] [(:state r) (:kind r)]))
    (is (= "The fake engine can't render this: codecs hevc." (:error r)))))

(deftest queued-jobs-can-be-cancelled-mid-render
  (let [a   (input! "a.mov")
        env (fake-env :behaviour {a :slow})
        q   (local/local-queue env {:concurrency 1})
        job (jobs/submit! q {:ctx {} :settings (settings) :inputs [a]})]
    (Thread/sleep 120)
    (jobs/cancel! q (:id job))
    (loop [i 0]
      (when (and (< i 50) (not= :cancelled (:state (first (jobs/list-jobs q)))))
        (Thread/sleep 50) (recur (inc i))))
    (is (= :cancelled (:state (first (jobs/list-jobs q)))))
    (is (not (.exists (io/file *dir* "a_wm.mp4"))) "nothing published")
    (jobs/shutdown! q)))
