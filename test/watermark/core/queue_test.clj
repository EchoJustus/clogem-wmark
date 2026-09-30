;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.queue-test
  "The in-process job queue passes the JobQueue contract on the JVM, both as
  the core library's (jobs started with watermark.util.task/later) and as
  the desktop server builds it (on its own daemon threads). The Dart host's
  tests run the same contract on the Dart VM (docs/adr/0015)."
  (:require [clojure.test :refer [deftest]]
            [watermark.core.jobs.local :as local]
            [watermark.core.queue :as queue]
            [watermark.queue-contract :as contract]
            [watermark.util.task :as task])
  (:import (java.util.concurrent CompletableFuture TimeUnit)))

(set! *warn-on-reflection* true)

(defn- passes [make]
  (contract/assert-all
   (task/await (.orTimeout ^CompletableFuture (contract/run-all make) 60 TimeUnit/SECONDS))))

(deftest the-library's-queue-passes-the-contract
  (passes queue/queue))

(deftest the-desktop-server's-queue-passes-the-contract
  (passes local/queue))
