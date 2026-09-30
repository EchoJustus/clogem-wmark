;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.time
  "Instants as ISO-8601 text, written the same way on every host."
  (:require [watermark.util.host :as host]))

#?(:clj (set! *warn-on-reflection* true))

(defn- pad [n width]
  (let [s (str n)]
    (str (apply str (repeat (- width (count s)) "0")) s)))

(defn- civil
  "[year month day] of `days` since 1970-01-01, in the proleptic Gregorian
  calendar (Howard Hinnant's days_from_civil, inverted)."
  [days]
  (let [z   (+ days 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (- (+ (- doe (quot doe 1460)) (quot doe 36524)) (quot doe 146096)) 365)
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp  (quot (+ (* 5 doy) 2) 153)
        d   (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m   (if (< mp 10) (+ mp 3) (- mp 9))]
    [(+ yoe (* era 400) (if (<= m 2) 1 0)) m d]))

(defn iso-instant
  "`ms` since the epoch as UTC ISO-8601 text, as java.time.Instant writes
  one of millisecond precision: 2026-09-30T08:15:02.250Z, and no fraction
  when the milliseconds are zero. Years 0 to 9999."
  [ms]
  (let [day     86400000
        days    (quot (if (>= ms 0) ms (- ms (dec day))) day)
        in-day  (- ms (* days day))
        [y m d] (civil days)
        millis  (rem in-day 1000)
        secs    (quot in-day 1000)]
    (str (pad y 4) "-" (pad m 2) "-" (pad d 2)
         "T" (pad (quot secs 3600) 2) ":" (pad (rem (quot secs 60) 60) 2) ":" (pad (rem secs 60) 2)
         (when (pos? millis) (str "." (pad millis 3)))
         "Z")))

(defn now
  "The current instant (`host/*clock*`) as ISO-8601 text."
  []
  (iso-instant (host/now-ms)))
