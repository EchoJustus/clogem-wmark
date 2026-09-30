;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.unicode-test
  "watermark.util.unicode, the library's own Unicode tables, held to Java
  25's results: on every code point, and on random text built from the code
  points where the rules get interesting (combining marks, Hangul, format
  characters, capital sigma in every kind of word)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.util.chars :as chars]
            [watermark.util.text :as text]
            [watermark.util.unicode :as unicode]
            [watermark.util.unicode-data :as data])
  (:import (java.text BreakIterator Normalizer Normalizer$Form)
           (java.util Locale Random)))

(set! *warn-on-reflection* true)

(defn- java-lower [^String s] (.toLowerCase s Locale/ROOT))
(defn- java-norm [^String s form] (Normalizer/normalize s ^Normalizer$Form form))

(def ^:private ops
  [[:lower unicode/lower java-lower]
   [:nfd   unicode/nfd   #(java-norm % Normalizer$Form/NFD)]
   [:nfkd  unicode/nfkd  #(java-norm % Normalizer$Form/NFKD)]
   [:nfc   unicode/nfc   #(java-norm % Normalizer$Form/NFC)]
   [:nfkc  unicode/nfkc  #(java-norm % Normalizer$Form/NFKC)]])

(def ^:private java-categories
  {Character/UPPERCASE_LETTER :Lu Character/LOWERCASE_LETTER :Ll Character/TITLECASE_LETTER :Lt
   Character/MODIFIER_LETTER :Lm Character/OTHER_LETTER :Lo Character/NON_SPACING_MARK :Mn
   Character/COMBINING_SPACING_MARK :Mc Character/ENCLOSING_MARK :Me
   Character/DECIMAL_DIGIT_NUMBER :Nd Character/LETTER_NUMBER :Nl Character/OTHER_NUMBER :No
   Character/CONNECTOR_PUNCTUATION :Pc Character/DASH_PUNCTUATION :Pd
   Character/START_PUNCTUATION :Ps Character/END_PUNCTUATION :Pe
   Character/INITIAL_QUOTE_PUNCTUATION :Pi Character/FINAL_QUOTE_PUNCTUATION :Pf
   Character/OTHER_PUNCTUATION :Po Character/MATH_SYMBOL :Sm Character/CURRENCY_SYMBOL :Sc
   Character/MODIFIER_SYMBOL :Sk Character/OTHER_SYMBOL :So Character/SPACE_SEPARATOR :Zs
   Character/LINE_SEPARATOR :Zl Character/PARAGRAPH_SEPARATOR :Zp Character/CONTROL :Cc
   Character/FORMAT :Cf Character/SURROGATE :Cs Character/PRIVATE_USE :Co Character/UNASSIGNED :Cn})

(defn- code-point-str ^String [cp] (Character/toString (int cp)))

(defn- all-code-points []
  (remove #(<= 0xD800 % 0xDFFF) (range 0x110000)))

(defn- mismatches
  "Up to ten inputs where `ours` and `java` disagree: strings as code
  points, sets sorted."
  [ours java inputs]
  (->> inputs
       (keep (fn [s] (let [a (ours s) b (java s)]
                       (when (not= a b)
                         (mapv #(if (string? %) (chars/code-points %) (sort %)) [s a b])))))
       (take 10)
       vec))

(deftest same-unicode-version-as-java
  ;; Java 25 implements Unicode 16.0; on another JDK the comparisons below
  ;; would measure the JDK, not this code
  (is (= "16.0.0" data/version))
  (is (Character/isDefined 0x1C89) "U+1C89 is new in Unicode 16.0")
  (is (not (Character/isDefined 0x1C8B))))

(deftest categories
  (is (= [] (->> (range 0x110000)
                 (remove #(= (unicode/category %) (java-categories (Character/getType (int %)))))
                 (take 10)
                 vec))))

(deftest every-code-point
  (let [strs (mapv code-point-str (all-code-points))]
    (doseq [[op ours java] ops]
      (testing (name op)
        (is (= [] (mismatches ours java strs)))))))

(deftest whitespace-is-javas
  (is (= [] (->> (all-code-points)
                 (remove #(= (boolean (text/whitespace? %)) (Character/isWhitespace (int %))))
                 (take 10)
                 vec))))

(def ^:private bmp-alphabet
  "Code points of the Basic Multilingual Plane where normalization and
  casing rules meet."
  (vec (concat
        ;; Greek: capital and small sigma, letters, tonos, ypogegrammeni
        [0x3A3 0x3C3 0x3C2 0x391 0x392 0x39F 0x3B1 0x3B2 0x386 0x3AC 0x390 0x3B0 0x345 0x37A]
        ;; Latin, dotted capital I, Turkish, ligatures, fullwidth, superscripts
        [0x41 0x61 0x5A 0x7A 0x49 0x69 0x130 0x131 0xC5 0xE5 0x212B 0x1E0A 0x1E0B 0xFB01 0xFF21 0xFF41 0xB9 0x2075]
        ;; combining marks of several classes, a Hebrew point, a Devanagari nukta
        [0x300 0x301 0x302 0x307 0x308 0x315 0x316 0x323 0x327 0x328 0x334 0x5B0 0x93C 0x1DCE 0x20DD]
        ;; Hangul: conjoining jamo L, V, T, syllables LV and LVT, compatibility jamo
        [0x1100 0x1112 0x1161 0x1175 0x11A8 0x11C2 0xAC00 0xAC01 0xD7A3 0x3131]
        ;; digits, other numbers, number punctuation, cased symbols
        [0x30 0x31 0x39 0x661 0xBD 0x2160 0x2170 0x24B6 0x24D0 0x25 0x23 0x24 0xA2 0x20AC 0x66B 0x66A]
        ;; word punctuation, spaces, separators, format characters
        [0x2E 0x27 0x22 0x2C 0x3A 0x2D 0x5F 0xB7 0x2019 0x2027 0xFF0E 0x20 0x9 0xA 0xD 0xC 0xA0 0x3000
         0x2028 0x200B 0x200D 0xAD 0x964 0x2060]
        ;; CJK, kana and their diacritics
        [0x4E00 0x3005 0x30A2 0x3042 0x3099 0x309A 0x30FC 0xF900])))

(def ^:private supplementary
  "A Deseret capital and small letter, a mathematical letter and digit, a
  CJK compatibility ideograph, an emoji, a combining mark, format
  characters alone, in a run and at a run's end, and unassigned code points
  after CJK blocks."
  [0x10400 0x10428 0x1D400 0x1D7CE 0x2F800 0x1F600 0x1D167
   0xE0001 0xE0041 0xE007F 0x110BD 0x13430 0x1343F 0x2A6E0 0x2FA1E 0x323B0])

(defn- random-strings [seed alphabet n max-len]
  (let [rnd (Random. seed)]
    (vec (repeatedly n (fn []
                         (chars/from-code-points
                          (repeatedly (inc (.nextInt rnd (int max-len)))
                                      #(alphabet (.nextInt rnd (count alphabet))))))))))

(deftest random-text
  (testing "the Basic Multilingual Plane: every operation is Java's"
    (let [strs (random-strings 20260929 bmp-alphabet 20000 12)]
      (doseq [[op ours java] ops]
        (testing (name op)
          (is (= [] (mismatches ours java strs)))))))
  (testing "with supplementary code points: normalization is Java's"
    (let [strs (random-strings 20260930 (into bmp-alphabet supplementary) 20000 12)]
      (doseq [[op ours java] (rest ops)]
        (testing (name op)
          (is (= [] (mismatches ours java strs))))))))

(defn- java-word-boundaries
  "Java's word boundaries of `s` in its forward iteration, as code point
  offsets."
  [^String s]
  (let [bi (BreakIterator/getWordInstance Locale/ROOT)]
    (.setText bi s)
    (set (loop [b (.first bi) out []]
           (if (= b BreakIterator/DONE)
             out
             (recur (.next bi) (conj out (.codePointCount s 0 (int b)))))))))

(deftest word-boundaries
  (testing "random text"
    (let [strs (random-strings 11 (into bmp-alphabet supplementary) 20000 12)]
      (is (= [] (mismatches #(unicode/word-boundaries (chars/code-points %)) java-word-boundaries strs)))))
  (testing "every code point, in contexts that tell letters, digits, marks, spaces and ignored ones apart"
    ;; word-boundaries sees a code point only through its roles and whether
    ;; it is ignored, so ours is computed once per such signature
    (let [contexts  [#(vector 0x61 % 0x62) #(vector 0x20 % 0x62) #(vector % 0x62) #(vector 0x31 % 0x32)]
          signature (juxt #'unicode/roles #'unicode/ignored?)
          cache     (atom {})
          ours      (fn [cp]
                      (let [k (signature cp)]
                        (or (@cache k)
                            (let [v (mapv #(unicode/word-boundaries (% cp)) contexts)]
                              (swap! cache assoc k v)
                              v))))
          bi        (BreakIterator/getWordInstance Locale/ROOT)
          java      (fn [cp] (mapv (fn [c]
                                     (let [cps (c cp) s (chars/from-code-points cps)]
                                       (.setText bi ^String s)
                                       (loop [b (.first bi) out #{}]
                                         (if (= b BreakIterator/DONE)
                                           out
                                           (recur (.next bi) (conj out (.codePointCount ^String s 0 (int b))))))))
                                   contexts))]
      (is (= [] (->> (all-code-points)
                     (remove #(= (ours %) (java %)))
                     (take 10)
                     (mapv #(format "U+%04X" %))))))))

(deftest final-sigma
  (testing "capital sigma ends a word when a cased letter precedes it and none follows, within the word"
    (doseq [s ["ΑΣ" "Σ" "ΑΣ Β" "ΑΣ-Β" "ΑΣ.Β" "ΑΣ'Β" "ΑΣ:Β" "ΑΣ_Β" "ΑΣ1" "1Σ" "Α1Σ"
               "ΑΣ́" "ΆΣ" "ΑΣ́Β" "Α.Σ" "ΑΣ." "A Σ" "aΣ" "ΑΣ·Β" "ΑΣ­Β"
               "ΑΣ,Β" "16:9 ΟΔΟΣ" "ΟΔΟΣ ΤΕΣΤ" "ΑΣ​Β" "Α'Σ" "ΑΣ’Β" "ΑΣ．Β" "ΣΣ" "ΑΣΣ" "​ΑΣ"
               "ΑΣ​" "ΑΣͅ" "ⒶΣ" "ⅠΣ" "ΑΣ%" "$1Σ" "ΑΣ\r\nΒ" "​Σ" "Α​ Σ"]]
      (is (= (java-lower s) (unicode/lower s)) s)))
  (testing "after a supplementary code point Java's isBoundary is off; the forward word boundaries decide"
    (doseq [[s ours java] [["Α𐐨Σ" "α𐐨ς" "α𐐨σ"] ["Α𝐀Σ" "α𝐀ς" "α𝐀σ"] ["𐐨Σ" "𐐨ς" "𐐨ς"]]]
      (is (= java (java-lower s)) "Java 25 as measured")
      (is (= ours (unicode/lower s))))))

(deftest text-functions
  (testing "trim and blank? are clojure.string's on the JVM"
    (let [ws   ["" " " "\t" "\n" " " "　" " " " " " " "﻿" "\u0085" "\u001F"]
          strs (for [a ws b ["" "x" "a b" " x "] c ws] (str a b c))]
      (is (= (map str/trim strs) (map text/trim strs)))
      (is (= (map str/blank? strs) (map text/blank? strs)))
      (is (text/blank? nil))))
  (testing "collapse-spaces is Java's \\s"
    (is (= "a b c d" (text/collapse-spaces "a \t\n\u000B\f\r b  c d"))))
  (testing "replace-runs is a regular expression's replaceAll"
    (let [strs (random-strings 3 (into bmp-alphabet supplementary) 2000 10)]
      (is (= (map #(str/replace % #"[^\p{L}\p{M}\p{N}]+" "-") strs)
             (map #(text/replace-runs % (complement unicode/letter-mark-number?) "-") strs)))))
  (testing "code points"
    (is (= 3 (text/code-point-count "a😀b")))
    (is (= "a😀" (text/take-code-points "a😀b" 2)))
    (is (= "ab" (text/take-code-points "ab" 5)))))
