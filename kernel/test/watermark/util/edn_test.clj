;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.edn-test
  (:require [clojure.edn :as clj-edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.util.edn :as edn]
            [watermark.util.host :as host]
            [watermark.util.time :as time])
  (:import (java.time Instant)
           (java.util Random)))

(set! *warn-on-reflection* true)

(defn- random-data
  "Nested EDN data from `rnd`, `depth` levels deep at most."
  [^Random rnd depth]
  (let [scalar #(case (.nextInt rnd 9)
                  0 nil
                  1 (.nextBoolean rnd)
                  2 (- (.nextInt rnd 2000) 1000)
                  3 (.nextLong rnd)
                  4 (* (.nextGaussian rnd) (Math/pow 10.0 (- (.nextInt rnd 40) 20)))
                  5 (apply str (repeatedly (.nextInt rnd 12)
                                           (fn [] (char (nth [34 92 10 9 13 12 8 0 31 127 0xA0 0x3A3 0x2028 97 32]
                                                             (.nextInt rnd 15))))))
                  6 (keyword (str "k" (.nextInt rnd 50)))
                  7 (keyword "ns" (str "k" (.nextInt rnd 50)))
                  8 (symbol (str "s" (.nextInt rnd 5))))]
    (if (or (zero? depth) (< (.nextInt rnd 10) 5))
      (scalar)
      (let [n (.nextInt rnd 6)]
        (case (.nextInt rnd 4)
          0 (into {} (repeatedly n #(vector (scalar) (random-data rnd (dec depth)))))
          1 (vec (repeatedly n #(random-data rnd (dec depth))))
          2 (set (repeatedly n scalar))
          3 (apply list (repeatedly n #(random-data rnd (dec depth)))))))))

(deftest reads-back-as-the-same-data
  (let [rnd (Random. 42)]
    (doseq [x (repeatedly 3000 #(random-data rnd 4))]
      (is (= x (clj-edn/read-string (edn/write x))) (pr-str x))
      (is (= x (host/read-edn (edn/write x)))))))

(deftest numbers
  (is (= "24\n" (edn/write 24)))
  (is (= "24.0\n" (edn/write 24.0)) "a double stays a double")
  (is (= "0.0001\n" (edn/write 1e-4)) "no exponent")
  (is (= "[##NaN ##Inf ##-Inf]\n" (edn/write [Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY])))
  (doseq [x [0.1 0.85 1e-7 1e21 123456789.125 -2.5 Double/MIN_NORMAL Double/MAX_VALUE]]
    (is (= x (clj-edn/read-string (edn/write x))))))

(deftest strings
  (is (= "\"a\\\"b\\\\c\\nd\\te\\rf\\fg\\bh\"\n" (edn/write "a\"b\\c\nd\te\rf\fg\bh")))
  (is (= "\"ΟΔΟΣ   \u0000\"\n" (edn/write "ΟΔΟΣ   \u0000")) "anything else as is"))

(deftest layout
  (testing "keys and set elements sorted by their text, whatever the map's order"
    (let [pairs (for [i (range 12)] [(keyword (str "k" i)) i])]
      (is (= (edn/write (into {} pairs)) (edn/write (into {} (reverse pairs)))
             (edn/write (apply array-map (mapcat identity (shuffle pairs))))))
      (is (= "#{\"c\" 1 :a :b}\n" (edn/write #{1 "c" :b :a})))))
  (testing "one line when it fits in 80 columns, else an entry per line, aligned"
    (is (= "{:a 1 :b [1 2 3]}\n" (edn/write {:b [1 2 3] :a 1})))
    (let [long-text (apply str (repeat 70 "x"))]
      (is (= (str "{:a 1\n :b \"" long-text "\"}\n") (edn/write {:a 1 :b long-text})))
      (is (= (str "{:outer {:a 1\n         :b \"" long-text "\"}}\n") (edn/write {:outer {:a 1 :b long-text}})))
      (is (= (str "[1\n \"" long-text "\"\n (2 3)]\n") (edn/write [1 long-text '(2 3)])))))
  (testing "with keyword keys, as documents have, only an atom longer than the width overflows it"
    (let [rnd    (Random. 7)
          keyed  (fn keyed [x] (cond (map? x)  (into {} (map (fn [[k v]] [(keyword (str "k" (hash k))) (keyed v)]) x))
                                     (coll? x) (into (empty x) (map keyed x))
                                     :else     x))]
      (doseq [x (repeatedly 500 #(keyed (random-data rnd 4)))
              line (str/split-lines (edn/write x))
              :when (> (count line) 80)]
        (is (not (re-find #"[\[{(]" (str/replace line #"\"(?:[^\"\\\\]|\\\\.)*\"" "\"\""))) line))))
  (testing "not EDN data"
    (is (= :invalid (:wmark/error (ex-data (try (edn/write {:f (Object.)}) (catch Exception e e))))))))

(deftest iso-instants-are-javas
  (let [rnd (Random. 1)
        lo  -62167219200000   ; 0000-01-01T00:00:00Z
        hi  253402300799999]  ; 9999-12-31T23:59:59.999Z
    (doseq [ms (concat [0 1 999 1000 -1 -1000 -1001 lo hi 951782400000 -2203891200000 1790000000000]
                       (repeatedly 20000 #(+ lo (long (* (.nextDouble rnd) (- hi lo))))))]
      (is (= (str (Instant/ofEpochMilli ms)) (time/iso-instant ms)) (str ms))))
  (is (= "2026-09-30T00:00:00.250Z" (binding [host/*clock* (constantly 1790726400250)] (time/now)))))

(deftest host-primitives
  (testing "attempt catches ex-info errors only"
    (is (= [1 nil] (host/attempt (constantly 1))))
    (let [[v e] (host/attempt #(throw (ex-info "no" {:x 1})))]
      (is (nil? v))
      (is (= {:x 1} (ex-data e))))
    (is (thrown? ArithmeticException (host/attempt #(/ 1 0)))))
  (testing "read-edn"
    (is (= {:a [1 2.5 "x"]} (host/read-edn "{:a [1 2.5 \"x\"]} trailing")))
    (is (nil? (host/read-edn "")))
    (doseq [bad ["{:a" "#=(+ 1 2)" "#foo 1" "{:a 1 :b}"]]
      (is (= :invalid (:wmark/error (ex-data (try (host/read-edn bad) (catch Exception e e))))) bad)))
  (testing "serialized"
    (let [lock (host/lock) n (atom 0)]
      (run! deref (doall (for [_ (range 8)] (future (dotimes [_ 1000] (host/serialized lock #(swap! n inc)))))))
      (is (= 8000 @n)))))
