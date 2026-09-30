;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.text
  "Text operations that give the same answer on every host, from the
  library's own Unicode tables (watermark.util.unicode, UCD 16.0.0) rather
  than the runtime's: the Dart VM has no normalizer and lowercases a few
  hundred code points differently from Java, and a runtime moving to a
  newer Unicode version must not change a profile's file name
  (docs/adr/0013).

  Whitespace here is Java's Character/isWhitespace, which clojure.string's
  trim and blank? use on the JVM. Their ClojureDart versions use Dart's
  String.trim, which also removes no-break spaces and the byte order mark,
  so portable code trims with this namespace."
  (:require [clojure.string :as str]
            [watermark.util.chars :as chars]
            [watermark.util.unicode :as unicode]))

#?(:clj (set! *warn-on-reflection* true))

(defn nfc
  "`s` in Unicode Normalization Form C."
  [s]
  (unicode/nfc (str s)))

(defn nfkc
  "`s` in Unicode Normalization Form KC: compatibility forms folded too
  (fullwidth letters, superscript digits, ligatures)."
  [s]
  (unicode/nfkc (str s)))

(defn lower
  "`s` lowercased without a locale, as String/toLowerCase(Locale/ROOT): a
  default locale would turn \"I\" into a dotless i on Turkish systems."
  [s]
  (unicode/lower (str s)))

(defn whitespace?
  "Java's Character/isWhitespace: the space separators except the no-break
  ones (U+00A0, U+2007, U+202F), line and paragraph separators, and the
  controls U+0009-U+000D and U+001C-U+001F."
  [cp]
  (or (<= 0x09 cp 0x0D) (<= 0x1C cp 0x20)
      (= cp 0x1680) (<= 0x2000 cp 0x2006) (<= 0x2008 cp 0x200A)
      (= cp 0x2028) (= cp 0x2029) (= cp 0x205F) (= cp 0x3000)))

(defn trim
  "`s` without leading and trailing whitespace (`whitespace?`)."
  [s]
  (let [cps (chars/code-points (str s))
        n   (count cps)
        a   (count (take-while whitespace? cps))
        b   (- n (count (take-while whitespace? (rseq (subvec cps a n)))))]
    (if (and (zero? a) (= b n)) (str s) (chars/from-code-points (subvec cps a b)))))

(defn blank?
  "True for nil and for text of whitespace only (`whitespace?`)."
  [s]
  (or (nil? s) (every? whitespace? (chars/code-points (str s)))))

(defn collapse-spaces
  "Runs of ASCII whitespace (space, tab, line feed, vertical tab, form feed,
  carriage return: Java's \\s) as one space. Dart's \\s matches more, so the
  class is spelt out."
  [s]
  (str/replace (str s) #"[ \t\n\u000B\f\r]+" " "))

(defn replace-runs
  "`s` with every run of code points that satisfy `pred` replaced by
  `replacement`."
  [s pred replacement]
  (->> (chars/code-points (str s))
       (partition-by (comp boolean pred))
       (mapcat (fn [run] (if (pred (first run)) (chars/code-points replacement) run)))
       chars/from-code-points))

(defn code-point-count
  "The number of code points in `s`."
  [s]
  (count (chars/code-points (str s))))

(defn take-code-points
  "The first `n` code points of `s`."
  [s n]
  (let [cps (chars/code-points (str s))]
    (if (<= (count cps) n) (str s) (chars/from-code-points (subvec cps 0 n)))))
