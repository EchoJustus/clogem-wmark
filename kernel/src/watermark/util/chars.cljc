;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.chars
  "Strings as Unicode code points and back: a host primitive, one branch
  per runtime. Everything else the library knows about characters comes
  from its own tables (watermark.util.unicode), not the host's.")

#?(:clj (set! *warn-on-reflection* true))

(defn code-points
  "The Unicode code points of a string, as a vector."
  [s]
  #?(:clj  (vec (.toArray (.codePoints ^String s)))
     :cljs (mapv #(.codePointAt % 0) (js/Array.from s))
     :cljd (vec (.-runes ^String s))))

(defn from-code-points
  "The string of a sequence of code points."
  [cps]
  #?(:clj  (let [a (int-array cps)] (String. a 0 (alength a)))
     :cljs (apply js/String.fromCodePoint cps)
     :cljd (String.fromCharCodes (vec cps))))
