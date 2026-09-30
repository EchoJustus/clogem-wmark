;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.json
  "JSON text from data, written the same way on every host: map keys sorted
  by their text, keywords as their names, numbers as plain decimals
  (watermark.util.num/decimal-str), and text as UTF-8 with only quotes,
  backslashes and control characters escaped. Writing only; hosts read JSON
  with their own parsers."
  (:require [clojure.string :as str]
            [watermark.util.chars :as chars]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private escapes {0x22 "\\\"" 0x5C "\\\\" 0x08 "\\b" 0x0C "\\f" 0x0A "\\n" 0x0D "\\r" 0x09 "\\t"})

(def ^:private hex "0123456789abcdef")

(defn- string-text [s]
  (str "\""
       (apply str (map (fn [cp]
                         (or (escapes cp)
                             (when (< cp 0x20)
                               (str "\\u00" (nth hex (quot cp 16)) (nth hex (rem cp 16))))
                             (chars/from-code-points [cp])))
                       (chars/code-points (str s))))
       "\""))

(defn- key-text [k]
  (string-text (if (keyword? k) (name k) (str k))))

(defn write
  "`x` as JSON text on one line."
  [x]
  (cond (nil? x)     "null"
        (boolean? x) (str x)
        (number? x)  (if (and (not (integer? x))
                              (or (not (or (< x 0) (>= x 0)))             ; NaN
                                  (> x 1.7976931348623157e308) (< x -1.7976931348623157e308)))
                       (throw (ex-info (str "JSON has no " x ".") {:wmark/error :invalid}))
                       (number/decimal-str x))
        (string? x)  (string-text x)
        (keyword? x) (string-text (name x))
        (map? x)     (str "{" (str/join "," (for [[kt v] (sort-by first (for [[k v] x] [(key-text k) v]))]
                                            (str kt ":" (write v))))
                          "}")
        (set? x)     (str "[" (str/join "," (sort (map write x))) "]")
        (coll? x)    (str "[" (str/join "," (map write x)) "]")
        :else        (string-text (str x))))
