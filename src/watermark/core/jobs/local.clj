;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.jobs.local
  "The JVM's in-process JobQueue: the core library's (watermark.core.queue,
  docs/adr/0015), with jobs started on a pool of daemon threads. A job's
  planning and probing run there, off the server's request threads;
  its render then goes on asynchronously, holding no thread.

  The pool is created by `local-queue` at run time: a thread pool in a
  top-level def would be initialised during the native-image build."
  (:require [watermark.core.jobs :as jobs]
            [watermark.core.queue :as queue])
  (:import (java.util.concurrent ExecutorService Executors ThreadFactory)))

(set! *warn-on-reflection* true)

(defn- pool ^ExecutorService [concurrency]
  (let [n (atom 0)]
    (Executors/newFixedThreadPool
     (int concurrency)
     (reify ThreadFactory
       (newThread [_ r]
         (doto (Thread. r (str "wmark-job-" (swap! n inc)))
           (.setDaemon true)))))))

(defn queue
  "watermark.core.queue/queue, with jobs started on the pool."
  [{:keys [concurrency] :or {concurrency 1} :as opts}]
  (let [p (pool concurrency)]
    (queue/queue (assoc opts
                        :concurrency concurrency
                        :spawn       (fn [f] (.execute p ^Runnable f))
                        :on-shutdown #(.shutdownNow p)))))

(defn local-queue
  "Queue running `concurrency` jobs at a time against `env` (see watermark.core.jobs)."
  [env {:keys [concurrency] :or {concurrency 1}}]
  (queue {:concurrency concurrency
          :run         (fn [job opts] (jobs/run-job! env job opts))}))
