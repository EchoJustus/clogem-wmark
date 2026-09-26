;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.html
  "Hiccup-style HTML that escapes by default, in a few dozen lines, so the
  UI needs no template dependency.

  Every text node and attribute value is escaped. Nothing can opt out except
  `raw`, which the UI code never applies to data. This matters more than
  usual with Datastar: it evaluates data-* attributes as JavaScript and runs
  <script> elements inside patched fragments (stamped with the page's CSP
  nonce), so injected markup would execute despite the CSP.

    (html [:p {:class \"note\"} \"a < b\"])  ;=> \"<p class=\\\"note\\\">a &lt; b</p>\"

  Tags are keywords (`:div`); attributes are a map with keyword or string
  keys (\"data-on:click\"). true renders a bare attribute; nil and false omit
  it. Seqs are spliced."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn escape
  "Text safe inside element content and double- or single-quoted attributes."
  ^String [s]
  (let [s (str s)]
    (if (re-find #"[&<>\"']" s)
      (-> s
          (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
          (str/replace "\"" "&quot;") (str/replace "'" "&#39;"))
      s)))

(deftype Raw [^String s])

(defn raw
  "Pre-rendered markup, inserted verbatim. Only for markup this program
  generated itself -- never for anything derived from a request or a store."
  [s]
  (->Raw (str s)))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(defn- attr-name ^String [k]
  (let [n (if (keyword? k) (name k) (str k))]
    (when-not (re-matches #"[A-Za-z][A-Za-z0-9_:.\-]*" n)
      (throw (ex-info (str "Invalid attribute name: " n) {:attribute n})))
    n))

(defn- attrs ^String [m]
  (apply str (for [[k v] m :when (and (some? v) (not (false? v)))]
               (if (true? v)
                 (str " " (attr-name k))
                 (str " " (attr-name k) "=\"" (escape v) "\"")))))

(defn html
  "Render hiccup to a string."
  ^String [x]
  (cond
    (instance? Raw x) (.-s ^Raw x)
    (vector? x)       (let [[tag & more] x
                            [a kids]     (if (map? (first more)) [(first more) (rest more)] [nil more])
                            t            (name tag)]
                        (when-not (re-matches #"[a-z][a-z0-9]*" t)
                          (throw (ex-info (str "Invalid tag: " t) {:tag t})))
                        (str "<" t (attrs a) ">"
                             (when-not (void-tags t)
                               (str (apply str (map html kids)) "</" t ">"))))
    (seq? x)          (apply str (map html x))
    (nil? x)          ""
    :else             (escape x)))

(defn document
  "A complete page: doctype plus the rendered <html> element."
  ^String [x]
  (str "<!doctype html>" (html x)))
