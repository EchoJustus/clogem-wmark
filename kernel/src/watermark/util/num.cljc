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
  #?(:cljd (:require ["dart:math" :as math])))

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
