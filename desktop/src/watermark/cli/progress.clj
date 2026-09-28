;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.cli.progress
  "Progress of `wmark run` in the terminal (ADR 0010).

  Three modes:
    :bar    one line per input, redrawn in place: a bar, the percentage,
            FFmpeg's speed and an ETA (an interactive terminal);
    :lines  a line at every 10% (logs, CI, output piped to a file);
    :none   only the start and the outcome of each input.
  :auto picks :bar on an interactive terminal, else :lines.

  ASCII only, so the bar looks the same in every console and code page."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def modes #{:auto :bar :lines :none})

(defn- clamp01 ^double [x] (max 0.0 (min 1.0 (double x))))

(defn bar
  "A `width`-character bar for `fraction` (0-1), e.g. \"[=====>    ]\"."
  [fraction width]
  (let [inner  (max 1 (- (long width) 2))
        filled (long (Math/floor (* (clamp01 fraction) inner)))]
    (str "["
         (cond
           (>= filled inner) (apply str (repeat inner \=))
           :else (str (apply str (repeat filled \=)) ">" (apply str (repeat (- inner filled 1) \space))))
         "]")))

(defn clock
  "Seconds as m:ss, or h:mm:ss from an hour on."
  [seconds]
  (let [s (long (Math/round (double (max 0 seconds))))
        h (quot s 3600) m (quot (rem s 3600) 60) ss (rem s 60)]
    (if (pos? h)
      (format "%d:%02d:%02d" h m ss)
      (format "%d:%02d" m ss))))

(defn eta-seconds
  "Remaining seconds, from the time spent so far and the fraction done; nil
  until there is enough to go on."
  [elapsed-s fraction]
  (let [f (clamp01 fraction)]
    (when (and (> f 0.01) (pos? (double elapsed-s)))
      (* (double elapsed-s) (/ (- 1.0 f) f)))))

(defn status-line
  "The one line the :bar mode keeps redrawing for an input."
  [{:keys [index total name fraction speed eta-s]}]
  (str/join "  " (remove nil? [(str "[" (inc (long index)) "/" total "]")
                               name
                               (bar (or fraction 0) 24)
                               (format "%3d%%" (long (* 100 (clamp01 (or fraction 0)))))
                               (when (and speed (not= "N/A" speed)) (str/trim (str speed)))
                               (when eta-s (str "ETA " (clock eta-s)))])))

(defn file-name
  "The last component of a path, whichever separator it uses."
  [path]
  (let [s (str path)]
    (subs s (inc (long (max (or (str/last-index-of s "/") -1) (or (str/last-index-of s "\\") -1)))))))

(defn terminal?
  "Is standard output an interactive terminal? (JDK 22+: System.console()
  exists even when output is redirected; isTerminal tells them apart.)"
  []
  (try (boolean (some-> (System/console) (.isTerminal)))
       (catch Throwable _ false)))

(defn resolve-mode
  "The mode for this run: `requested` unless :auto; then a bar on an
  interactive terminal (not TERM=dumb), else lines."
  [requested {:keys [terminal? term]}]
  (if (not= :auto requested)
    requested
    (if (and terminal? (not= "dumb" term)) :bar :lines)))

(defn printer
  "An `on-event` function for `api/run-batch!` that prints `total` inputs'
  progress in `mode`. `now-s` returns the time in seconds (tests pass a fake
  clock); output goes to *out*."
  ([mode total] (printer mode total #(/ (System/nanoTime) 1e9)))
  ([mode total now-s]
   (let [st (atom {})]
     (fn [{:keys [type index input output fraction speed state error]}]
       (case type
         :started
         (do (reset! st {:start (now-s) :shown -1 :width 0 :file (file-name input)})
             (when (not= :bar mode)
               (println (format "[%d/%d] %s -> %s" (inc (long index)) total input output))))

         :progress
         (when fraction
           (case mode
             :bar
             (let [{:keys [start width file]} @st
                   line (status-line {:index index :total total :name file :fraction fraction :speed speed
                                      :eta-s (eta-seconds (- (double (now-s)) (double start)) fraction)})
                   pad  (max 0 (- (long width) (count line)))]
               (swap! st assoc :width (count line))
               (print (str "\r" line (apply str (repeat pad \space))))
               (flush))
             :lines
             (let [pct (long (* 100 (clamp01 fraction)))]
               (when (>= pct (+ (long (:shown @st)) 10))
                 (swap! st assoc :shown pct)
                 (println (format "    %3d%%" pct))))
             nil))

         :finished
         (let [outcome (str (name state) (when error (str ": " error)))]
           (if (= :bar mode)
             (let [{:keys [start width file]} @st
                   line (str "[" (inc (long index)) "/" total "]  " file "  " outcome
                             (when start (str "  in " (clock (- (double (now-s)) (double start))))))
                   pad  (max 0 (- (long width) (count line)))]
               (println (str "\r" line (apply str (repeat pad \space)))))
             (println (format "    %s" outcome))))
         nil)))))
