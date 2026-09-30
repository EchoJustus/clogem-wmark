;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.cli.progress
  "Progress of `wmark run` in the terminal (ADR 0010), the same on every
  host (docs/adr/0014).

  Three modes:
    :bar    one line per input, redrawn in place: a bar, the percentage,
            FFmpeg's speed and an ETA (an interactive terminal);
    :lines  a line at every 10% (logs, CI, output piped to a file);
    :none   only the start and the outcome of each input.
  :auto picks :bar on an interactive terminal, else :lines.

  ASCII only, so the bar looks the same in every console and code page.
  The host says whether its output is a terminal and writes the text."
  (:require [clojure.string :as str]
            [watermark.util.num :as number]
            [watermark.util.text :as text]))

#?(:clj (set! *warn-on-reflection* true))

(def modes #{:auto :bar :lines :none})

(defn- clamp01 [x] (max 0.0 (min 1.0 (* 1.0 x))))

(defn- spaces [n] (apply str (repeat n " ")))

(defn- pad-left [s width] (str (spaces (- width (count (str s)))) s))

(defn- two-digits [n] (if (< n 10) (str "0" n) (str n)))

(defn bar
  "A `width`-character bar for `fraction` (0-1), e.g. \"[=====>    ]\"."
  [fraction width]
  (let [inner  (max 1 (- width 2))
        filled (number/floor-int (* (clamp01 fraction) inner))]
    (str "["
         (if (>= filled inner)
           (apply str (repeat inner "="))
           (str (apply str (repeat filled "=")) ">" (spaces (- inner filled 1))))
         "]")))

(defn clock
  "Seconds as m:ss, or h:mm:ss from an hour on."
  [seconds]
  (let [s (number/round-half-up (max 0 seconds))
        h (quot s 3600) m (quot (rem s 3600) 60) ss (rem s 60)]
    (if (pos? h)
      (str h ":" (two-digits m) ":" (two-digits ss))
      (str m ":" (two-digits ss)))))

(defn eta-seconds
  "Remaining seconds, from the time spent so far and the fraction done; nil
  until there is enough to go on."
  [elapsed-s fraction]
  (let [f (clamp01 fraction)]
    (when (and (> f 0.01) (pos? elapsed-s))
      (* 1.0 elapsed-s (/ (- 1.0 f) f)))))

(defn- percent [fraction] (number/floor-int (* 100 (clamp01 (or fraction 0)))))

(defn status-line
  "The one line the :bar mode keeps redrawing for an input."
  [{:keys [index total name fraction speed eta-s]}]
  (str/join "  " (remove nil? [(str "[" (inc index) "/" total "]")
                               name
                               (bar (or fraction 0) 24)
                               (str (pad-left (percent fraction) 3) "%")
                               (when (and speed (not= "N/A" speed)) (text/trim speed))
                               (when eta-s (str "ETA " (clock eta-s)))])))

(defn file-name
  "The last component of a path, whichever separator it uses."
  [path]
  (let [s (str path)]
    (subs s (inc (max (or (str/last-index-of s "/") -1) (or (str/last-index-of s "\\") -1))))))

(defn resolve-mode
  "The mode for this run: `requested` unless :auto; then a bar on an
  interactive terminal (not TERM=dumb), else lines."
  [requested {:keys [terminal? term]}]
  (if (not= :auto requested)
    requested
    (if (and terminal? (not= "dumb" term)) :bar :lines)))

(defn printer
  "An `on-event` function for `api/run-batch!` that shows `total` inputs'
  progress in `mode`. The host supplies `now-s`, a clock in seconds (tests
  pass a fake one), and `write`, which writes text to the terminal as it is
  (no newline added) and flushes it."
  [mode total {:keys [now-s write]}]
  (let [st   (atom {})
        line (fn [s] (write (str s "\n")))]
    (fn [{:keys [type index input output fraction speed state error]}]
      (case type
        :started
        (do (reset! st {:start (now-s) :shown -1 :width 0 :file (file-name input)})
            (when (not= :bar mode)
              (line (str "[" (inc index) "/" total "] " input " -> " output))))

        :progress
        (when fraction
          (case mode
            :bar
            (let [{:keys [start width file]} @st
                  text (status-line {:index index :total total :name file :fraction fraction :speed speed
                                     :eta-s (eta-seconds (- (now-s) start) fraction)})]
              (swap! st assoc :width (count text))
              (write (str "\r" text (spaces (- width (count text))))))
            :lines
            (let [pct (percent fraction)]
              (when (>= pct (+ (:shown @st) 10))
                (swap! st assoc :shown pct)
                (line (str "    " (pad-left pct 3) "%"))))
            nil))

        :finished
        (let [outcome (str (name state) (when error (str ": " error)))]
          (if (= :bar mode)
            (let [{:keys [start width file]} @st
                  text (str "[" (inc index) "/" total "]  " file "  " outcome
                            (when start (str "  in " (clock (- (now-s) start)))))]
              (line (str "\r" text (spaces (- width (count text))))))
            (line (str "    " outcome))))
        nil))))
