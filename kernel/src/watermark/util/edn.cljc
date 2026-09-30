;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.edn
  "EDN text from data, written the same way on every host, so a document a
  person may edit (a profile) comes out byte for byte alike whichever
  runtime wrote it. Map keys and set elements are sorted by their text; a
  collection that fits in the width stays on one line, else each entry
  gets a line of its own, aligned under the first. Reading is each host's
  own EDN reader (watermark.util.host/read-edn)."
  (:require [clojure.string :as str]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private width 80)

(def ^:private escapes
  {"\"" "\\\"" "\\" "\\\\" "\n" "\\n" "\t" "\\t" "\r" "\\r" "\f" "\\f" "\b" "\\b"})

(defn- string-text [s]
  (str "\"" (str/replace s #"[\"\\\n\t\r\f\x08]" (fn [m] (escapes m))) "\""))

(defn- number-text [x]
  (cond (integer? x)          (str x)
        (not (or (< x 0) (>= x 0)))   "##NaN" ; no comparison holds for NaN
        (> x 1.7976931348623157e308)  "##Inf"
        (< x -1.7976931348623157e308) "##-Inf"
        :else (let [t (number/decimal-str x)]
                (if (str/includes? t ".") t (str t ".0")))))

(declare flat)

(defn- entries
  "The parts of collection `x` in print order: [open [part ...] close],
  where a map's part is [key-text value]. Map keys are printed on one line."
  [x]
  (cond (map? x)    ["{" (sort-by first (for [[k v] x] [(flat k) v])) "}"]
        (set? x)    ["#{" (sort-by flat x) "}"]
        (vector? x) ["[" (vec x) "]"]
        :else       ["(" (vec x) ")"]))

(defn- flat
  "`x` on one line."
  [x]
  (cond (nil? x)     "nil"
        (boolean? x) (str x)
        (number? x)  (number-text x)
        (string? x)  (string-text x)
        (keyword? x) (str x)
        (symbol? x)  (str x)
        (coll? x)    (let [[open parts close] (entries x)]
                       (str open
                            (str/join " " (for [p parts]
                                            (if (map? x) (str (first p) " " (flat (second p))) (flat p))))
                            close))
        :else (throw (ex-info (str "Not EDN data: " (pr-str x)) {:wmark/error :invalid}))))

(defn- pretty
  "`x` starting at column `col`."
  [x col]
  (let [one-line (flat x)]
    (if (or (not (coll? x)) (<= (+ col (count one-line)) width))
      one-line
      (let [[open parts close] (entries x)
            inner (+ col (count open))
            sep   (str "\n" (apply str (repeat inner " ")))]
        (str open
             (str/join sep (for [p parts]
                             (if (map? x)
                                               (let [[k v] p] (str k " " (pretty v (+ inner (count k) 1))))
                                               (pretty p inner))))
             close)))))

(defn write
  "`x` as EDN text, with a final newline."
  [x]
  (str (pretty x 0) "\n"))
