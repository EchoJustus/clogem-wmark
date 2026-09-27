;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.prng
  "A portable, specified pseudo-random generator for keyed schedules.

  Pro schedules are evidence: the owner must be able to regenerate the exact
  frame list on any platform, years later -- a desktop render verified by the
  SaaS backend, an iPad render checked on Windows. That rules out host RNGs,
  whose algorithms are unspecified or differ. This is SplitMix64 exactly as
  java.util.SplittableRandom implements it (seeded constructor, nextLong,
  bounded nextLong, nextDouble), so JVM results are unchanged and every other
  host can reproduce them from ~30 lines of integer arithmetic. Tests compare
  it with SplittableRandom draw for draw.

  Needs 64-bit two's-complement integers with wrapping multiplication: the JVM
  and Dart VM/AOT (Flutter) qualify; JavaScript numbers do not, so this
  namespace is not for ClojureScript.")

;; 0x9e3779b97f4a7c15, 0xbf58476d1ce4e5b9, 0x94d049bb133111eb as signed longs
(def ^:private golden-gamma -7046029254386353131)
(def ^:private mix-a -4658895280553007687)
(def ^:private mix-b -7723592293110705685)

(defn- mix64 [z]
  (let [z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 30)) mix-a)
        z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 27)) mix-b)]
    (bit-xor z (unsigned-bit-shift-right z 31))))

(defn generator
  "A generator seeded like `new SplittableRandom(seed)`. Stateful: create one
  per schedule, never share one between threads."
  [seed]
  (volatile! seed))

(defn next-long!
  "Next 64-bit value (SplittableRandom.nextLong())."
  [g]
  (mix64 (vswap! g #(unchecked-add % golden-gamma))))

(defn next-below!
  "Uniform value in [0, bound), bound > 0 (SplittableRandom.nextLong(bound)):
  masking for powers of two, otherwise rejection of over-represented values."
  [g bound]
  (let [m (dec bound)
        r (next-long! g)]
    (if (zero? (bit-and bound m))
      (bit-and r m)
      (loop [u (unsigned-bit-shift-right r 1)]
        (let [r (rem u bound)]
          (if (neg? (unchecked-subtract (unchecked-add u m) r))
            (recur (unsigned-bit-shift-right (next-long! g) 1))
            r))))))

(defn next-double!
  "Uniform double in [0, 1) (SplittableRandom.nextDouble())."
  [g]
  (* (unsigned-bit-shift-right (next-long! g) 11) 1.1102230246251565E-16))

(defn next-between!
  "Uniform integer in [lo, hi], inclusive."
  [g lo hi]
  (+ lo (next-below! g (inc (- hi lo)))))
