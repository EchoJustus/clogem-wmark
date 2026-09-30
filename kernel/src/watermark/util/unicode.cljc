;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.unicode
  "What the library knows about characters, from its own copy of the
  Unicode Character Database (watermark.util.unicode-data, version 16.0.0,
  the version Java 25 implements), so every host gives the same answers
  whatever Unicode version its runtime ships. watermark.util.text is the
  interface; tests hold this namespace to Java 25's own results on every
  code point and on random text (kernel/test/watermark/util/unicode_test.clj),
  and the Dart VM checks the same answers (kernel/test/golden/text.edn).

  - category: the general category of a code point.
  - nfd, nfkd, nfc, nfkc: Unicode Standard Annex #15, with Hangul by
    arithmetic.
  - lower: String/toLowerCase(Locale/ROOT) as Java 25 does it: the simple
    lowercase mapping of each code point, except that capital I with dot
    becomes i + combining dot, and capital sigma takes its final form at the
    end of a word. Java finds that end with its word BreakIterator, whose
    rules (sun.text.resources.BreakIteratorRules) `word-boundaries` follows.
    One deliberate difference: Java's BreakIterator/isBoundary misjudges the
    position right after a supplementary code point (it starts from the
    middle of the surrogate pair), so after one Java may keep a final sigma
    small; here the word boundaries are the ones its forward iteration
    reports (docs/adr/0013)."
  (:require [clojure.string :as str]
            [watermark.util.chars :as chars]
            [watermark.util.num :as number]
            [watermark.util.unicode-data :as data]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Tables

(def ^:private hex-digits
  (into {} (map vector "0123456789abcdef" (range 16))))

(defn- hex [s]
  (reduce (fn [n c] (+ (* 16 n) (hex-digits c))) 0 (seq s)))

(defn- entries [chunks] (str/split (apply str (interpose ";" chunks)) #";"))

(defn- cp-range [s]
  (let [[a b] (str/split s #"-")] [(hex a) (hex (or b a))]))

(defn- ranges
  "Entries \"a-b:v\" (or \"a-b\") as [[a b v] ...], `v` read by `f`."
  [chunks f]
  (vec (for [e (entries chunks)
             :let [[r v] (str/split e #":")
                   [a b] (cp-range r)]]
         [a b (f v)])))

(def ^:private tables
  (delay
    (let [categories (ranges data/category keyword)
          decomp     (fn [chunks] (into {} (for [e (entries chunks)
                                                 :let [[cp d] (str/split e #":")]]
                                             [(hex cp) (mapv hex (str/split d #" "))])))
          canonical  (decomp data/canonical)
          excluded   (set (for [[a b] (ranges data/excluded identity)
                                cp (range a (inc b))]
                            cp))]
      {:category-starts (mapv first categories)
       :categories      categories
       :ccc             (into {} (for [[a b c] (ranges data/ccc number/parse-int)
                                       cp (range a (inc b))]
                                   [cp c]))
       :canonical       canonical
       :compat          (decomp data/compat)
       :compose         (into {} (for [[cp [a b :as d]] canonical
                                       :when (and (= 2 (count d)) (not (excluded cp)))]
                                   [[a b] cp]))
       :lower           (into {} (for [e (entries data/lowercase)
                                       :let [[a b] (str/split e #":")]]
                                   [(hex a) (hex b)]))})))

(defn category
  "The general category of code point `cp` as a keyword (:Lu, :Mn, :Nd,
  ...); :Cn for an unassigned one."
  [cp]
  (let [t      @tables
        starts (:category-starts t)
        ;; the last range that starts at or before cp
        i      (loop [lo 0 hi (dec (count starts))]
                 (if (> lo hi)
                   hi
                   (let [mid (quot (+ lo hi) 2)]
                     (if (<= (starts mid) cp) (recur (inc mid) hi) (recur lo (dec mid))))))]
    (if (neg? i)
      :Cn
      (let [[_ b c] ((:categories t) i)] (if (<= cp b) c :Cn)))))

(defn letter-mark-number?
  "True for a letter, mark or number (\\p{L}, \\p{M} or \\p{N})."
  [cp]
  (contains? #{"L" "M" "N"} (subs (name (category cp)) 0 1)))

(defn control?
  "True for a control character (Cc)."
  [cp]
  (= :Cc (category cp)))

(defn- ccc-of [t cp] (get (:ccc t) cp 0))

;; ---------------------------------------------------------------------------
;; Normalization (UAX #15)

(def ^:private s-base 0xAC00)
(def ^:private l-base 0x1100)
(def ^:private v-base 0x1161)
(def ^:private t-base 0x11A7)
(def ^:private l-count 19)
(def ^:private v-count 21)
(def ^:private t-count 28)
(def ^:private n-count (* v-count t-count))
(def ^:private s-count (* l-count n-count))

(defn- decompose
  "Code point `cp` fully decomposed onto `out` (canonical mappings, and
  compatibility ones too when `compat?`)."
  [t compat? out cp]
  (let [s (- cp s-base)]
    (if (< -1 s s-count)
      (let [l  (+ l-base (quot s n-count))
            v  (+ v-base (quot (rem s n-count) t-count))
            tt (+ t-base (rem s t-count))]
        (cond-> (conj out l v) (not= tt t-base) (conj tt)))
      (if-let [d (or (get (:canonical t) cp) (when compat? (get (:compat t) cp)))]
        (reduce (partial decompose t compat?) out d)
        (conj out cp)))))

(defn- reorder
  "Canonical ordering: each run of non-starters stably sorted by combining
  class."
  [t cps]
  (let [cc (fn [cp] (ccc-of t cp))]
    (loop [in cps out []]
      (if-let [cp (first in)]
        (if (zero? (cc cp))
          (recur (rest in) (conj out cp))
          (let [run (take-while #(pos? (cc %)) in)]
            (recur (drop (count run) in) (into out (sort-by cc run)))))
        out))))

(defn- compose-pair [t a b]
  (let [l (- a l-base) v (- b v-base) s (- a s-base) tt (- b t-base)]
    (cond (and (< -1 l l-count) (< -1 v v-count))                      (+ s-base (* (+ (* l v-count) v) t-count))
          (and (< -1 s s-count) (zero? (rem s t-count)) (< 0 tt t-count)) (+ a tt)
          :else                                                        (get (:compose t) [a b]))))

(defn- compose
  "Canonical composition of decomposed, reordered code points: the
  algorithm of UAX #15 as in Unicode's sample code. A text that starts with
  a non-starter gets class 256 for it, so nothing composes onto it."
  [t cps]
  (if (empty? cps)
    []
    (let [c0 (ccc-of t (first cps))]
      (first
       (reduce (fn [[out starter last-cc] cp]
                 (let [cc        (ccc-of t cp)
                       composite (compose-pair t (out starter) cp)]
                   (if (and composite (or (< last-cc cc) (zero? last-cc)))
                     [(assoc out starter composite) starter last-cc]
                     [(conj out cp) (if (zero? cc) (count out) starter) cc])))
               [[(first cps)] 0 (if (zero? c0) 0 256)]
               (rest cps))))))

(defn- normalize [s compat? compose?]
  (let [t   @tables
        cps (reorder t (reduce (partial decompose t compat?) [] (chars/code-points s)))]
    (chars/from-code-points (if compose? (compose t cps) cps))))

(defn nfd "Normalization Form D." [s] (normalize s false false))
(defn nfkd "Normalization Form KD." [s] (normalize s true false))
(defn nfc "Normalization Form C." [s] (normalize s false true))
(defn nfkc "Normalization Form KC." [s] (normalize s true true))

;; ---------------------------------------------------------------------------
;; Java's word boundaries (sun.text.resources.BreakIteratorRules, "WordBreakRules")

(defn- in-ranges? [cp rs] (some (fn [[a b]] (<= a cp b)) rs))

(def ^:private kanji [[0x3005 0x3005] [0x4E00 0x9FA5] [0xF900 0xFA2D]])
(def ^:private kata [[0x30A1 0x30FA] [0x30FD 0x30FE]])
(def ^:private hira [[0x3041 0x3094] [0x309D 0x309E]])
(def ^:private cjk-diacrit [[0x3099 0x309C] [0x30FB 0x30FC]])

(defn- ignored?
  "Format characters (Cf) are invisible to Java's word rules, except the
  soft hyphen, which the rules name as punctuation inside a word. Outside
  the Basic Multilingual Plane, Java's table loses the last code point of
  each run of them: its generator packs the ignore marker, -1, into the
  range entry, which borrows one from the range's end
  (build.tools.generatebreakiteratordata.SupplementaryCharacterData)."
  [cp]
  (and (= :Cf (category cp))
       (if (< cp 0x10000)
         (not= cp 0xAD)
         (= :Cf (category (inc cp))))))

(def ^:private letter-gaps
  "Unassigned code points after CJK ideograph blocks that Java 25's word
  table counts as letters, as its generator reads them."
  [[0x2A6E0 0x2A6FF] [0x2B73A 0x2B73F] [0x2B81E 0x2B81F] [0x2CEA2 0x2CEAF]
   [0x2EBE1 0x2EBEF] [0x2FA1E 0x2FFFF] [0x3134B 0x3134F]])

(defn- roles
  "What Java's word rules see in code point `cp`: the set of its roles."
  [cp]
  (let [cat (category cp)
        l?  (or (#{:Lu :Ll :Lt :Lm :Lo :Mc} cat) (in-ranges? cp letter-gaps))
        cjk (cond (in-ranges? cp kanji) :kanji (in-ranges? cp kata) :kata
                  (in-ranges? cp hira) :hira (in-ranges? cp cjk-diacrit) :cjk-diacrit)]
    (cond-> #{}
      (and l? (not cjk))                                       (conj :letter)
      cjk                                                      (conj cjk)
      (#{:Mn :Me} cat)                                         (conj :enclosing)
      (#{:Nd :Nl :No} cat)                                     (conj :digit)
      (#{0x0964 0x0965} cp)                                    (conj :danda)
      (or (#{:Pd :Pc} cat) (#{0xAD 0x2027 0x22 0x27 0x2E} cp)) (conj :mid-word)
      (#{0x22 0x27 0x2C 0x066B 0x2E} cp)                       (conj :mid-num)
      (and (or (= :Sc cat) (#{0x23 0x2E} cp)) (not= cp 0xA2))  (conj :pre-num)
      (#{0x25 0x26 0xA2 0x066A 0x2030 0x2031} cp)              (conj :post-num)
      (#{0x0A 0x0C 0x2028 0x2029} cp)                          (conj :ls)
      (= cp 0x0D)                                              (conj :cr)
      (or (= :Zs cat) (= cp 0x09))                             (conj :ws)
      (not (#{:Mn :Me :Cc :Cf :Zl :Zp} cat))                   (conj :base))))

;; A small matcher over the units' roles: a parser takes a position and
;; returns the set of positions where a match starting there can end.
(defn- one [units role] (fn [p] (if (and (< p (count units)) ((units p) role)) #{(inc p)} #{})))
(defn- cat* [& ps] (fn [p] (reduce (fn [ends q] (set (mapcat q ends))) #{p} ps)))
(defn- star [q] (fn [p] (loop [seen #{p} frontier #{p}]
                          (let [nxt (set (remove seen (mapcat q frontier)))]
                            (if (empty? nxt) seen (recur (into seen nxt) nxt))))))
(defn- plus [q] (cat* q (star q)))
(defn- opt [q] (fn [p] (conj (q p) p)))
(defn- alt [& ps] (fn [p] (set (mapcat #(% p) ps))))

(defn- rules
  "Java's word rules over `units`, as one parser: everywhere a rule that
  matches from a position can end."
  [units]
  (let [u        (partial one units)
        let*     (cat* (u :letter) (star (u :enclosing)))
        dgt      (cat* (u :digit) (star (u :enclosing)))
        word     (cat* (plus let*) (star (cat* (u :mid-word) (plus let*))) (opt (u :danda)))
        number   (cat* (plus dgt) (star (cat* (u :mid-num) (plus dgt))))
        ws       (cat* (u :ws) (star (u :enclosing)))
        tail     (opt (cat* number (opt (u :post-num))))
        any-unit (fn [p] (if (< p (count units)) #{(inc p)} #{}))]
    (alt any-unit
         (cat* (opt word) (star (cat* number word)) tail)
         (cat* (u :pre-num) (star (cat* number word)) tail)
         (cat* (star ws) (opt (u :cr)) (opt (u :ls)))
         (star (alt (u :kata) (u :cjk-diacrit)))
         (star (alt (u :hira) (u :cjk-diacrit)))
         (star (u :kanji))
         (cat* (u :base) (plus (u :enclosing))))))

(defn word-boundaries
  "The word boundaries of the code points `cps`, as the set of offsets that
  Java's word BreakIterator reports when it iterates forward from the start.
  Ignored characters (`ignored?`) belong to the unit before them, or to the
  first unit when they start the text; from each boundary the next one is
  where the longest rule match ends."
  [cps]
  (let [n     (count cps)
        lead  (count (take-while ignored? cps))
        ;; units: [start end roles]
        units (reduce (fn [us i]
                        (let [cp (cps i)]
                          (if (and (ignored? cp) (seq us))
                            (update us (dec (count us)) assoc 1 (inc i))
                            (conj us [(if (empty? us) 0 i) (inc i) (roles cp)]))))
                      [] (range lead n))
        match (rules (mapv #(nth % 2) units))
        m     (count units)
        ends  (reduce (fn [out _]
                        (let [p (peek out)]
                          (if (>= p m) (reduced out) (conj out (apply max (inc p) (match p))))))
                      [0] (range m))]
    (conj (set (for [e ends] (if (zero? e) 0 (second (units (dec e)))))) n)))

;; ---------------------------------------------------------------------------
;; Lowercase, as String/toLowerCase(Locale/ROOT)

(def ^:private other-cased
  "Other_Lowercase and Other_Uppercase ranges Java's final-sigma test counts
  as cased (java.lang.ConditionalSpecialCasing#isCased, Java 25)."
  [[0x02B0 0x02B8] [0x02C0 0x02C1] [0x02E0 0x02E4] [0x0345 0x0345] [0x037A 0x037A]
   [0x1D2C 0x1D61] [0x2160 0x217F] [0x24B6 0x24E9]])

(defn- cased? [cp]
  (or (#{:Lu :Ll :Lt} (category cp)) (in-ranges? cp other-cased)))

(defn- final-sigma?
  "Java's Final_Cased condition for the sigma at `i`: within its word there
  is a cased letter before it and none after it."
  [cps bounds i]
  (let [n (count cps)]
    (boolean
     (loop [j i]
       (when (and (>= j 0) (not (bounds j)))
         (if (cased? (cps (dec j)))
           (loop [k (inc i)]
             (cond (or (>= k n) (bounds k)) true
                   (cased? (cps k))         false
                   :else                    (recur (inc k))))
           (recur (dec j))))))))

(defn lower
  "`s` lowercased as Java's String/toLowerCase(Locale/ROOT)."
  [s]
  (let [t      @tables
        cps    (chars/code-points s)
        bounds (delay (word-boundaries cps))]
    (chars/from-code-points
     (into [] (mapcat (fn [i cp]
                        (cond (= cp 0x3A3) [(if (final-sigma? cps @bounds i) 0x3C2 0x3C3)]
                              (= cp 0x130) [0x69 0x307]
                              :else        [(get (:lower t) cp cp)]))
                      (range) cps)))))
