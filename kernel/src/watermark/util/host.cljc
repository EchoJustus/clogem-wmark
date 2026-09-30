;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.host
  "What the profile rules need from their host that each runtime spells
  differently: a host primitive, one branch per runtime (docs/adr/0013).

  - attempt: catch the library's own errors (ex-info), whose class differs;
  - describe-error: one line for an error that isn't the library's own;
  - serialized: one unit of work at a time on a lock (the Dart VM runs an
    isolate's code on one thread, so there is nothing to lock);
  - *clock*: milliseconds since the epoch, rebindable for tests and hosts;
  - read-edn: EDN text as data, with each runtime's reader."
  (:require #?@(:cljd [[cljd.edn :as edn]] :clj [[clojure.edn :as edn]])))

#?(:clj (set! *warn-on-reflection* true))

(defn attempt
  "[(f) nil], or [nil e] when f throws an ex-info error `e`. Other errors
  propagate."
  [f]
  (try [(f) nil]
       (catch #?(:clj clojure.lang.ExceptionInfo :cljd cljd.core/ExceptionInfo) e [nil e])))

(defn describe-error
  "One line for an error that isn't an ex-info: its class and message on the
  JVM (\"NoSuchFileException: /in.mp4\"), its text on the Dart VM."
  [e]
  #?(:clj  (str (.getSimpleName (class e)) ": " (ex-message e))
     :cljd (str e)))

(defn lock
  "A new lock for `serialized`."
  []
  #?(:clj (Object.) :cljd nil))

(defn serialized
  "(f) while holding `lock`, so units of work on the same lock never
  interleave."
  [lock f]
  #?(:clj (locking lock (f)) :cljd (f)))

(def ^:dynamic *clock*
  "The clock: a function of no arguments that returns milliseconds since
  the epoch. Bound to a fixed time by tests and golden vectors."
  (fn [] #?(:clj (System/currentTimeMillis) :cljd (.-millisecondsSinceEpoch (DateTime/now)))))

(defn now-ms
  "Milliseconds since the epoch, from `*clock*`."
  []
  (*clock*))

(defn read-edn
  "The first form of EDN `text`, or nil for empty text. Malformed text
  throws an ex-info error with :wmark/error :invalid and the reader's
  message as :cause."
  [text]
  (try (edn/read-string text)
       (catch #?(:clj Exception :cljd Object) e
         (throw (ex-info "Not valid EDN." {:wmark/error :invalid :cause (str #?(:clj (ex-message e) :cljd e))})))))
