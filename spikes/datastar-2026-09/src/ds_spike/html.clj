;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns ds-spike.html
  "A tiny hiccup renderer that escapes by default: enough for a hypermedia UI
  without a template dependency. Text and attribute values are always
  escaped; only (raw ..) passes through."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn esc ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;") (str/replace "'" "&#39;")))

(deftype Raw [^String s])
(defn raw [s] (->Raw (str s)))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(defn- attrs [m]
  (apply str (for [[k v] m :when (and (some? v) (not (false? v)))]
               (let [k (if (keyword? k) (name k) (str k))]
                 (if (true? v) (str " " k) (str " " k "=\"" (esc v) "\""))))))

(defn html ^String [x]
  (cond
    (instance? Raw x) (.-s ^Raw x)
    (vector? x)       (let [[tag & more] x
                            [a kids]     (if (map? (first more)) [(first more) (rest more)] [nil more])
                            t            (name tag)]
                        (str "<" t (attrs a) ">"
                             (when-not (void-tags t)
                               (str (apply str (map html kids)) "</" t ">"))))
    (seq? x)          (apply str (map html x))
    (nil? x)          ""
    :else             (esc x)))
