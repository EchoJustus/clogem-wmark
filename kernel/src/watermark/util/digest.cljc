;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.digest
  "SHA-256 over the same bytes on every host: a host primitive, one branch
  per runtime (docs/adr/0014). It names bitmaps (watermark.raster/bitmap-id)
  and fingerprints media (watermark.media/fingerprint), which keys the Pro
  schedules, so both runtimes must agree to the bit."
  ;; :cljd first: ClojureDart's macro pass reads both branches' features
  #?(:cljd (:require ["dart:convert" :as convert]
                     ["dart:typed_data" :as td]
                     ["package:crypto/crypto.dart" :as crypto])
     :clj  (:import (java.nio.charset StandardCharsets)
                    (java.security MessageDigest)
                    (java.util HexFormat))))

#?(:clj (set! *warn-on-reflection* true))

(defn sha256-hex
  "Lowercase hex SHA-256 of `chunks`, in order: each a string (hashed as its
  UTF-8 bytes) or a byte array (a Uint8List on the Dart VM)."
  [chunks]
  #?(:clj  (let [md (MessageDigest/getInstance "SHA-256")]
             (doseq [c chunks]
               (if (string? c)
                 (.update md (.getBytes ^String c StandardCharsets/UTF_8))
                 (.update md ^bytes c)))
             (.formatHex (HexFormat/of) (.digest md)))
     :cljd (let [parts (mapv (fn [c] (if (string? c) (.encode convert/utf8 c) c)) chunks)
                 all   (td/Uint8List (reduce + 0 (map (fn [^List p] (.-length p)) parts)))]
             (reduce (fn [at ^List p]
                       (let [end (+ at (.-length p))]
                         (.setRange all at end p)
                         end))
                     0 parts)
             (.toString (.convert crypto/sha256 all)))))
