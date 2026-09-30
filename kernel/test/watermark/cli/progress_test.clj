;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.cli.progress-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.cli.progress :as progress]))

(set! *warn-on-reflection* true)

(deftest the-bar-fills-with-the-fraction
  (is (= "[>         ]" (progress/bar 0 12)))
  (is (= "[=====>    ]" (progress/bar 0.5 12)))
  (is (= "[==========]" (progress/bar 1 12)))
  (is (= "[==========]" (progress/bar 7 12)) "out-of-range fractions are clamped")
  (is (= "[>         ]" (progress/bar -1 12)))
  (is (every? #(= 24 (count (progress/bar % 24))) [0 0.01 0.33 0.999 1]) "always the same width"))

(deftest times-and-estimates
  (is (= "0:07" (progress/clock 7)))
  (is (= "2:05" (progress/clock 125)))
  (is (= "1:00:00" (progress/clock 3600)))
  (is (nil? (progress/eta-seconds 3 0)) "no estimate before there is progress")
  (is (= 30.0 (progress/eta-seconds 10 0.25)) "a quarter done in 10 s: 30 s to go")
  (is (= "clip é.mp4" (progress/file-name "/videos/vidéo/clip é.mp4")))
  (is (= "clip.mp4" (progress/file-name "C:\\videos\\clip.mp4")))
  (is (= "clip.mp4" (progress/file-name "clip.mp4"))))

(deftest auto-means-a-bar-only-on-an-interactive-terminal
  (is (= :bar (progress/resolve-mode :auto {:terminal? true :term "xterm-256color"})))
  (is (= :bar (progress/resolve-mode :auto {:terminal? true :term nil})) "Windows consoles set no TERM")
  (is (= :lines (progress/resolve-mode :auto {:terminal? false :term "xterm"})) "output piped to a file or CI")
  (is (= :lines (progress/resolve-mode :auto {:terminal? true :term "dumb"})))
  (is (= :none (progress/resolve-mode :none {:terminal? true}))))

(defn- events [n input]
  (concat [{:type :started :index n :input input :output (str input ".out")}]
          (for [f [0.05 0.1 0.15 0.5 0.95 1.0]] {:type :progress :index n :fraction f :speed "2.5x"})
          [{:type :finished :index n :state :done}]))

(defn- play [mode]
  (let [t (atom 0)
        p (progress/printer mode 2 {:now-s #(swap! t + 2) :write print})]
    (with-out-str (run! p (events 0 "/in/a.mp4")))))

(deftest lines-mode-prints-every-ten-percent
  (let [out (play :lines)]
    (is (str/includes? out "[1/2] /in/a.mp4 -> /in/a.mp4.out"))
    (is (= ["10%" "50%" "95%"] (re-seq #"\d+%" out))
        "one line per 10% step, never repeated")
    (is (str/includes? out "    done"))
    (is (not (str/includes? out "\r")) "no carriage returns in logs")))

(deftest bar-mode-redraws-one-line
  (let [out (play :bar)
        frames (str/split out #"\r")]
    (is (< 5 (count frames)) "each update redraws the line in place")
    (is (str/includes? out "[1/2]  a.mp4  [") "the file name, not the whole path")
    (is (str/includes? out "100%"))
    (is (str/includes? out "2.5x") "FFmpeg's speed")
    (is (re-find #"ETA \d+:\d\d" out))
    (is (re-find #"\[1/2\]  a\.mp4  done  in \d+:\d\d\s*\n$" out) "ends with the outcome on its own line")))

(deftest none-mode-prints-start-and-outcome
  (let [out (play :none)]
    (is (= 2 (count (str/split-lines out))))
    (is (not (re-find #"\d+%" out)))))
