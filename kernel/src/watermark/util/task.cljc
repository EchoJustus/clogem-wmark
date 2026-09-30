;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.task
  "Eventual values, the same way on every host: a host primitive, one branch
  per runtime (docs/adr/0013, section 3). A render finishes later, and the
  Dart VM can't wait for it (a Future has no blocking get), so the pipeline
  chains what happens next instead. A task is a CompletableFuture on the
  JVM, which `deref` also reads, and a Future on the Dart VM.

  Errors travel inside the task: `then` skips them, `recover` sees the
  original error (the JVM's CompletionException wrapper removed)."
  (:refer-clojure :exclude [reduce await])
  #?(:cljd (:require ["dart:async" :as async])
     :clj  (:import (java.util.concurrent CompletableFuture CompletionException ExecutionException)
                    (java.util.function BiConsumer BiFunction Function Supplier))))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn- unwrap
     "The error a JVM completion carries, without its wrappers."
     [^Throwable e]
     (if (and (or (instance? CompletionException e) (instance? ExecutionException e)) (.getCause e))
       (recur (.getCause e))
       e)))

(defn task?
  "True for a task."
  [x]
  #?(:clj (instance? CompletableFuture x) :cljd (dart/is? x Future)))

(defn resolved
  "A task already resolved to `v`."
  [v]
  #?(:clj (CompletableFuture/completedFuture v) :cljd (Future/value v)))

(defn failed
  "A task already failed with error `e`."
  [e]
  #?(:clj (CompletableFuture/failedFuture e) :cljd (Future/error e)))

(defn of
  "A task for `x`: x itself when it is one; on the JVM, a pending deref-able
  (a promise, a future) is read on another thread; anything else is a task
  resolved to x."
  [x]
  (cond (task? x) x
        #?@(:clj [(instance? clojure.lang.IPending x)
                  (CompletableFuture/supplyAsync (reify java.util.function.Supplier (get [_] @x)))])
        :else (resolved x)))

(defn attempt
  "A task for (f): its value, a task it returns, or the error it throws."
  [f]
  (try (of (f))
       (catch #?(:clj Throwable :cljd Object) e (failed e))))

(defn later
  "A task for (f), run soon but not on the caller's stack: on the JVM's
  common pool, from the Dart VM's event loop. A task f returns is waited
  for; an error it throws fails the task."
  [f]
  #?(:clj  (.thenCompose (CompletableFuture/supplyAsync (reify Supplier (get [_] (attempt f))))
                         (reify Function (apply [_ t] t)))
     :cljd (Future (fn [] (attempt f)))))

(defn then
  "A task for (f v) once `t` resolves to v; a task f returns is waited for.
  A failed `t` stays failed and f isn't called."
  [t f]
  #?(:clj  (.thenCompose ^CompletableFuture (of t) (reify Function (apply [_ v] (of (f v)))))
     :cljd (.then ^Future (of t) (fn [v] (f v)))))

(defn recover
  "A task for `t`, or, when it fails with error e, for (f e)."
  [t f]
  #?(:clj  (.thenCompose (.handle ^CompletableFuture (of t)
                                  (reify BiFunction
                                    (apply [_ v e] (if e (attempt #(f (unwrap e))) (resolved v)))))
                         (reify Function (apply [_ x] x)))
     :cljd (.catchError ^Future (of t) (fn [e] (f e)))))

(defn always
  "A task for `t` that first calls (f) when t settles, either way. An error
  f throws replaces t's outcome."
  [t f]
  #?(:clj  (.thenCompose (.handle ^CompletableFuture (of t)
                                  (reify BiFunction
                                    (apply [_ v e] (f) (if e (failed (unwrap e)) (resolved v)))))
                         (reify Function (apply [_ x] x)))
     :cljd (.whenComplete ^Future (of t) (fn [] (f)))))

(defn deferred
  "A task to settle later with `complete!` or `fail!`; `task-of` is the
  task itself."
  []
  #?(:clj (CompletableFuture.) :cljd (async/Completer)))

(defn complete! [d v] #?(:clj (.complete ^CompletableFuture d v) :cljd (.complete ^async/Completer d v)))
(defn fail! [d e] #?(:clj (.completeExceptionally ^CompletableFuture d e) :cljd (.completeError ^async/Completer d e)))
(defn task-of [d] #?(:clj d :cljd (.-future ^async/Completer d)))

(defn reduce
  "Like clojure.core/reduce, where (f acc x) may return a task: the next
  step waits for it. A `reduced` value ends early. Returns a task of the
  result. On the JVM, steps that are already done run in a loop rather than
  nested, so a long batch of instant steps can't overflow the stack."
  [f init coll]
  #?(:clj
     (let [out (CompletableFuture.)]
       (letfn [(run [acc xs]
                 (loop [acc acc xs xs]
                   (if-let [s (seq xs)]
                     (let [^CompletableFuture t (attempt #(f acc (first s)))]
                       (if (.isDone t)
                         (let [[v e] (try [(.join t) nil] (catch Throwable e [nil (unwrap e)]))]
                           (cond e           (.completeExceptionally out e)
                                 (reduced? v) (.complete out @v)
                                 :else        (recur v (rest s))))
                         (.whenComplete t (reify BiConsumer
                                            (accept [_ v e]
                                              (cond e           (.completeExceptionally out (unwrap e))
                                                    (reduced? v) (.complete out @v)
                                                    :else        (run v (rest s))))))))
                     (.complete out acc))))]
         (run init coll)
         out))
     :cljd
     (letfn [(step [acc xs]
               (if-let [s (seq xs)]
                 (then (attempt #(f acc (first s)))
                       (fn [v] (if (reduced? v) @v (step v (rest s)))))
                 (resolved acc)))]
       (step init coll))))

#?(:clj
   (defn await
     "The value of task `t`, waiting for it (the JVM only: hosts that block,
     like the CLI and tests). A failed task throws its original error."
     [t]
     (try (.get ^CompletableFuture (of t))
          (catch ExecutionException e (throw (unwrap e))))))
