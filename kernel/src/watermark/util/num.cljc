;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.num
  "The numeric operations whose results differ between hosts.

  Everything the kernel computes (frame windows, geometry, schedules) must come
  out bit-identical on the JVM, under ClojureDart (Dart VM/AOT), in native
  engines that re-implement the reference semantics (Swift, Kotlin, Rust), and
  in golden test vectors. Host `round` functions disagree on halves (Java
  rounds -2.5 to -2, Dart and Swift to -3), so rounding is defined here, once.

  The :cljd branches follow ClojureDart's documented interop and are untested
  until the Stage 3 spike -- see docs/ROADMAP.md."
  #?(:cljd (:require ["dart:math" :as math])))

(def pi #?(:clj Math/PI :cljs js/Math.PI :cljd math/pi))

(defn cos [x] #?(:clj (Math/cos (double x)) :cljs (js/Math.cos x) :cljd (math/cos x)))
(defn sin [x] #?(:clj (Math/sin (double x)) :cljs (js/Math.sin x) :cljd (math/sin x)))

(defn floor-int
  "Largest integer <= x."
  [x]
  #?(:clj (long (Math/floor (double x))) :cljs (js/Math.floor x) :cljd (.floor (.toDouble x))))

(defn ceil-int
  "Smallest integer >= x."
  [x]
  #?(:clj (long (Math/ceil (double x))) :cljs (js/Math.ceil x) :cljd (.ceil (.toDouble x))))

(defn round-half-up
  "Nearest integer, halves toward +infinity -- identical on every host."
  [x]
  (floor-int (+ x 0.5)))

(defn even
  "Nearest even integer (chroma-subsampled pixel formats need even sizes)."
  [x]
  (* 2 (round-half-up (/ x 2.0))))

(defn clamp [lo hi x] (max lo (min hi x)))
