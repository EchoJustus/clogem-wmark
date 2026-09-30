;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.ffmpeg.graph-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.ffmpeg.graph :as g])
  (:import (java.util Locale)))

(set! *warn-on-reflection* true)

(defn- unescape-level
  "One level of FFmpeg's av_get_token unescaping (for strings without quotes)."
  [s]
  (str/replace s #"\\(.)" "$1"))

(defn- bare
  "Special characters left unescaped at one parsing level."
  [s specials]
  (loop [[c & more] (seq s) found []]
    (cond (nil? c)     found
          (= c \\)    (recur (rest more) found)          ; \x: escaped, skip both
          (specials c) (recur more (conj found c))
          :else        (recur more found))))

(deftest literals-survive-both-unescaping-levels
  (doseq [s ["C:/Users/a b/logo.png" "it's" "a,b;c[d]e" "back\\slash" "100% sure" "x=1:y=2"]]
    (let [e (g/escape-value s)]
      (is (= s (unescape-level (unescape-level e))) s)
      (is (empty? (bare e #{\, \; \[ \] \'})) "nothing looks like a graph separator")
      (is (empty? (bare (unescape-level e) #{\: \'})) "nor, one level down, like an option separator"))))

(deftest numbers-ignore-the-default-locale
  (let [orig (Locale/getDefault)]
    (try
      (Locale/setDefault Locale/GERMANY)   ; formats 0.85 as 0,85 -- a filter separator
      (is (= "0.85" (g/num-str 0.85)))
      (is (= "24" (g/num-str 24.0)))
      (is (= "0.0001" (g/num-str 1e-4)))
      (is (= "fps=fps=30000/1001:x=0.85" (g/render-filter (g/f "fps" :fps "30000/1001" :x 0.85))))
      (finally (Locale/setDefault orig)))))

(deftest render-quotes-expressions-and-drops-nils
  (is (= "[0:v]drawtext=fontfile=C\\\\:/f.ttf:x='w-tw-(24)'[out]"
         (g/render [(g/chain ["0:v"]
                             [(g/f "drawtext" :fontfile "C:/f.ttf" :x (g/expr "w-tw-(24)") :enable nil)]
                             ["out"])]))))
