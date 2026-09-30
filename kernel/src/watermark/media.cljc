;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.media
  "The media I/O port: where inputs come from and where outputs go.

  Engines read a local path or URL and write a local temp file; they never
  decide names, publish results, or know about buckets. That lives here:

    watermark.media.local    files next to the input or in an output folder;
                             atomic publish from a .part file (desktop)
    (Stage 5) object storage presigned GET URLs as input locations (FFmpeg
                             reads them directly), /tmp outputs uploaded on
                             commit, fingerprints from ranged GETs

  The fingerprint (SHA-256 over size + first MiB + last MiB) keys the Pro
  schedules, so every implementation must compute it identically -- a render
  made on the desktop must be verifiable by the backend and vice versa.
  `fingerprint` is that computation; hosts only read the bytes
  `fingerprint-ranges` names. Output names (`output-name`, `part-path`) are
  shared the same way (docs/adr/0014)."
  (:require [clojure.string :as str]
            [watermark.util.digest :as digest]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol MediaIO
  (open-input  [io ctx input]
    "{:id display-name, :location local path or URL for the engine, :fingerprint hex}")
  (open-output [io ctx input settings]
    "{:final where the result will live, :temp local path the engine writes,
      :container \"mp4\"}. Fails early (:conflict) if the result exists and
      overwriting is off, before any encoding time is spent.")
  (commit!     [io ctx output] "Publish :temp as :final; returns the final location. A missing
                               :temp is an ex-info with :wmark/error :failed.")
  (discard!    [io ctx output] "Remove :temp after a failed or cancelled render."))

;; ---------------------------------------------------------------------------
;; What every implementation computes the same way

(def fingerprint-chunk
  "How many bytes the fingerprint reads at each end of a file: 1 MiB."
  1048576)

(defn fingerprint-ranges
  "The bytes of a `size`-byte file its fingerprint reads, as [offset length]:
  the head, then the tail. A file smaller than two chunks is read in part
  twice, and one smaller than a chunk whole, twice."
  [size]
  (let [n (min size fingerprint-chunk)]
    [[0 n] [(max 0 (- size n)) n]]))

(defn fingerprint
  "The fingerprint of a `size`-byte file from the bytes at its
  `fingerprint-ranges`: lowercase hex SHA-256 over the size in decimal
  (UTF-8), the head and the tail. Deterministic and instant even for
  multi-GB masters; it keys schedules, it is not an integrity hash (the
  studio's audit ledger keeps full hashes)."
  [size head tail]
  (digest/sha256-hex [(str size) head tail]))

(defn output-name
  "The output's file name for an input named `input-name`: its stem, the
  suffix, then the container as the extension (\"clip.mov\" becomes
  \"clip_wm.mp4\")."
  [input-name {:keys [suffix container] :or {suffix "_wm" container "mp4"}}]
  (str (str/replace (str input-name) #"\.[^.]+$" "") suffix "." container))

(defn part-path
  "The temp name an engine writes to, renamed on success: `output` with
  \".part\" before its extension, which muxers still infer the format from
  (\"/out/clip_wm.mp4\" becomes \"/out/clip_wm.part.mp4\")."
  [output]
  (let [s   (str output)
        sep (max (or (str/last-index-of s "/") -1) (or (str/last-index-of s "\\") -1))
        dot (or (str/last-index-of s ".") -1)]
    (if (and (> dot sep) (< dot (dec (count s))))
      (str (subs s 0 dot) ".part" (subs s dot))
      s)))
