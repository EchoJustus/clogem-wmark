;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.util.task :as task]))

(set! *warn-on-reflection* true)

(defn- error-of [t]
  (try (task/await t) nil (catch Throwable e e)))

(deftest values-and-errors
  (testing "then chains values and waits for tasks it's given"
    (is (= 3 (task/await (-> (task/resolved 1) (task/then inc) (task/then #(task/resolved (inc %)))))))
    (is (= 2 @(task/then (task/resolved 1) inc)) "deref reads a task on the JVM"))
  (testing "errors skip then and reach recover as they were thrown"
    (let [boom (ex-info "boom" {:x 1})
          seen (atom [])]
      (is (= :recovered
             (task/await (-> (task/resolved 1)
                             (task/then (fn [_] (throw boom)))
                             (task/then (fn [v] (swap! seen conj v) v))
                             (task/recover (fn [e] (swap! seen conj e) :recovered))))))
      (is (= [boom] @seen))
      (is (identical? boom (error-of (task/failed boom))) "await throws the original error")))
  (testing "always runs either way, and its own error wins"
    (let [n (atom 0)]
      (is (= 1 (task/await (task/always (task/resolved 1) #(swap! n inc)))))
      (is (= "no" (ex-message (error-of (task/always (task/failed (ex-info "no" {})) #(swap! n inc))))))
      (is (= 2 @n))
      (is (= "cleanup" (ex-message (error-of (task/always (task/resolved 1) #(throw (ex-info "cleanup" {}))))))))))

(deftest settling-later
  (let [d (task/deferred)
        t (task/then (task/task-of d) inc)]
    (is (not (.isDone ^java.util.concurrent.CompletableFuture (task/task-of d))))
    (future (Thread/sleep 20) (task/complete! d 41))
    (is (= 42 (task/await t))))
  (let [d (task/deferred)]
    (task/fail! d (ex-info "later" {}))
    (is (= "later" (ex-message (error-of (task/task-of d))))))
  (testing "a pending deref-able becomes a task"
    (let [p (promise)
          t (task/then (task/of p) inc)]
      (deliver p 1)
      (is (= 2 (task/await t))))))

(deftest reducing
  (testing "steps run in order, each waiting for the last"
    (let [order (atom [])]
      (is (= [0 1 2 3]
             (task/await (task/reduce (fn [acc x]
                                        (swap! order conj x)
                                        (if (even? x)
                                          (conj acc x)
                                          (let [d (task/deferred)]
                                            (future (Thread/sleep 5) (task/complete! d (conj acc x)))
                                            (task/task-of d))))
                                      [] (range 4)))))
      (is (= [0 1 2 3] @order))))
  (testing "reduced ends early; an error ends it failed"
    (is (= 3 (task/await (task/reduce (fn [acc x] (if (= x 3) (reduced acc) (+ acc x))) 0 (range 10)))))
    (is (= "at 2" (ex-message (error-of (task/reduce (fn [_ x] (when (= x 2) (throw (ex-info "at 2" {})))) nil (range 5)))))))
  (testing "a long batch of steps that are already done doesn't grow the stack"
    (is (= 200000 (task/await (task/reduce (fn [acc _] (task/resolved (inc acc))) 0 (range 200000)))))))
