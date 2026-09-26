;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.seeds
  "Per-video, per-layer seeds keyed by the studio secret.

  seed = first 64 bits (big-endian, signed) of
         HMAC-SHA256(secret, \"wmark/v1|<input fingerprint>|<layer>|<mode>|<text>\")

  Same secret + same master + same layer => same schedule, so the owner can
  regenerate the exact frame list later as evidence. A different master gives
  an unrelated schedule, so there is no pattern to learn across a catalogue.
  The derivation is open on purpose (Kerckhoffs): security rests on the
  secret, not on hiding the algorithm. kernel/test/golden/seeds.edn pins the
  outputs every host implementation must reproduce."
  #?(:clj (:import (java.nio ByteBuffer)
                   (java.nio.charset StandardCharsets)
                   (javax.crypto Mac)
                   (javax.crypto.spec SecretKeySpec))))

#?(:clj (set! *warn-on-reflection* true))

(defn keyed-seed
  "First 64 bits of HMAC-SHA256(secret, context) as a signed integer."
  [secret context]
  #?(:clj  (let [mac (doto (Mac/getInstance "HmacSHA256")
                       (.init (SecretKeySpec. ^bytes secret "HmacSHA256")))]
             (.getLong (ByteBuffer/wrap (.doFinal mac (.getBytes ^String context StandardCharsets/UTF_8)))))
     ;; Dart: package:crypto's Hmac(sha256, key).convert(utf8.encode(context)),
     ;; then the first 8 bytes big-endian (ByteData.getInt64(0, Endian.big)).
     :default (throw (ex-info "keyed-seed is not implemented on this host yet."
                              {:wmark/error :unavailable}))))

(defn context
  [input-fingerprint layer-index layer]
  (str "wmark/v1|" input-fingerprint "|" layer-index "|" (name (:mode layer)) "|" (:content layer)))

(defn seed-fn
  "(fn [layer-index layer] seed) for one input."
  [secret input-fingerprint]
  (fn [i layer] (keyed-seed secret (context input-fingerprint i layer))))
