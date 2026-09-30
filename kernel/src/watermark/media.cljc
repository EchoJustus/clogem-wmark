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
  made on the desktop must be verifiable by the backend and vice versa.")

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
