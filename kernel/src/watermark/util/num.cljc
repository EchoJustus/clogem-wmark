;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.num
  "The numeric operations whose results differ between hosts.

  Everything the kernel computes (frame windows, geometry, schedules) must come
  out bit-identical on the JVM, under ClojureDart (Dart VM/AOT), in native
  engines that re-implement the reference semantics (Swift, Kotlin, Rust), and
  in golden test vectors. Host `round` functions disagree on halves (Java
  rounds -2.5 to -2, Dart and Swift to -3), so rounding is defined here, once.

  The :cljd branches run on the Dart VM in CI (kernel/dart, the golden
  vectors)."
  (:require [clojure.string :as str]
            #?(:cljd ["dart:math" :as math])))

#?(:clj (set! *warn-on-reflection* true))

(def pi #?(:clj Math/PI :cljs js/Math.PI :cljd math/pi))

(defn cos [x] #?(:clj (Math/cos (double x)) :cljs (js/Math.cos x) :cljd (math/cos x)))
(defn sin [x] #?(:clj (Math/sin (double x)) :cljs (js/Math.sin x) :cljd (math/sin x)))
(defn sqrt
  "Square root. IEEE 754 requires it correctly rounded, so unlike cos and sin
  it is bit-identical on every host."
  [x]
  #?(:clj (Math/sqrt (double x)) :cljs (js/Math.sqrt x) :cljd (math/sqrt x)))

(defn floor-int
  "Largest integer <= x."
  [x]
  #?(:clj (long (Math/floor (double x))) :cljs (js/Math.floor x) :cljd (.floor (.toDouble ^num x))))

(defn ceil-int
  "Smallest integer >= x."
  [x]
  #?(:clj (long (Math/ceil (double x))) :cljs (js/Math.ceil x) :cljd (.ceil (.toDouble ^num x))))

(defn round-half-up
  "Nearest integer, halves toward +infinity -- identical on every host."
  [x]
  (floor-int (+ x 0.5)))

(defn even
  "Nearest even integer (chroma-subsampled pixel formats need even sizes)."
  [x]
  (* 2 (round-half-up (/ x 2.0))))

(defn clamp [lo hi x] (max lo (min hi x)))

;; 64-bit two's-complement arithmetic that wraps on overflow, as Java's long
;; and the Dart VM's int do (watermark.util.prng). Dart compiled to
;; JavaScript has no 64-bit integers; the kernel doesn't run there.

(defn add-wrap
  "a + b, wrapping at 64 bits."
  [a b]
  #?(:clj (unchecked-add (long a) (long b)) :cljd (+ ^int a ^int b)))

(defn sub-wrap
  "a - b, wrapping at 64 bits."
  [a b]
  #?(:clj (unchecked-subtract (long a) (long b)) :cljd (- ^int a ^int b)))

(defn mul-wrap
  "a * b, wrapping at 64 bits."
  [a b]
  #?(:clj (unchecked-multiply (long a) (long b)) :cljd (* ^int a ^int b)))

;; ---------------------------------------------------------------------------
;; Text: numbers written and read the same way on every host

(defn parse-int
  "Decimal integer text (an optional sign, then digits) as an integer, else
  nil. Stricter than a host's parser: the Dart VM's also reads \"0x1F\" and
  ignores surrounding spaces."
  [s]
  (when (and (string? s) (re-matches #"[+-]?[0-9]+" s))
    (parse-long s)))

(defn parse-decimal
  "Decimal text (\"10.5\", \"-3\", \".25\", \"1e-3\") as a double, else nil.
  Stricter than a host's parser: no \"NaN\", \"Infinity\", hexadecimal or
  surrounding spaces."
  [s]
  (when (and (string? s) (re-matches #"[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?" s))
    (parse-double s)))

(defn- plain
  "A host's shortest round-trip text of a finite double (\"1.0E-4\" on the
  JVM, \"1e-7\" or \"1e+21\" on the Dart VM) as a plain decimal: no
  exponent, no trailing zeros, no sign on zero."
  [s]
  (let [[m sign whole frac exp] (re-matches #"(-?)([0-9]+)(?:\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?" s)
        _      (when-not m
                 (throw (ex-info (str "Not a finite number: " s) {:wmark/error :invalid})))
        digits (str whole frac)
        point  (+ (count whole) (if exp (parse-int (str/replace exp "+" "")) 0))
        placed (cond (<= point 0)               (str "0." (apply str (repeat (- point) "0")) digits)
                     (>= point (count digits))  (str digits (apply str (repeat (- point (count digits)) "0")))
                     :else                      (str (subs digits 0 point) "." (subs digits point)))
        [w f]  (str/split placed #"\." 2)
        w      (or (second (re-matches #"0*([0-9]+)" w)) "0")
        f      (str/replace (or f "") #"0+$" "")
        text   (if (seq f) (str w "." f) w)]
    (if (and (= sign "-") (not= text "0")) (str "-" text) text)))

(defn decimal-str
  "A number as the shortest plain decimal that reads back as the same
  double: 24 -> \"24\", 24.0 -> \"24\", 0.85 -> \"0.85\", 1e-4 -> \"0.0001\".
  Never locale-dependent (a German locale writes 0,85, and a comma
  separates FFmpeg filters), never in exponent form. Both hosts start from
  their shortest round-trip text of the double (Java 19+, Dart), whose
  digits the double alone decides; kernel/test/golden/ffmpeg.edn checks
  that they agree. One exception: a subnormal double (below 2.2e-308) that
  one digit identifies, where Java picks the closest decimal of one or two
  digits (4.9E-324, not 5e-324). No plan uses one."
  [x]
  (if (integer? x)
    (str x)
    (plain #?(:clj (Double/toString (double x)) :cljd (.toString (.toDouble ^num x))))))
