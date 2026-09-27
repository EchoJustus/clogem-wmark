;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.truetype
  "A TrueType (glyf) font reader: just what rasterizing a line of text needs.

  Tables read: head (units per em, loca format), hhea (ascender, descender,
  line gap, metric count), maxp (glyph count), hmtx (advances), cmap
  (Unicode: format 12, else format 4), loca and glyf (simple and composite
  glyphs). Outlines are returned in font units, y up, as contours of
  {:x :y :on?} points, the way the glyf table stores them.

  Not read, on purpose: hinting instructions (the rasterizer is unhinted, so
  the same pixels come out everywhere), kerning and shaping (GPOS/GSUB: text
  is laid out by advance widths), and CFF outlines (an .otf with a CFF table
  is refused). docs/adr/0006 records these limits."
  (:require [watermark.raster.image :as image]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Big-endian reads

(defn- u8 [b i] (image/u8 b i))
(defn- u16 [b i] (+ (* 256 (u8 b i)) (u8 b (inc i))))
(defn- i16 [b i] (let [v (u16 b i)] (if (>= v 32768) (- v 65536) v)))
(defn- u32 [b i] (+ (* 65536 (u16 b i)) (u16 b (+ i 2))))
(defn- i8 [b i] (let [v (u8 b i)] (if (>= v 128) (- v 256) v)))
(defn- f2dot14 [b i] (/ (i16 b i) 16384.0))

(defn- tag [b i] (apply str (map #(char (u8 b (+ i %))) (range 4))))

(defn- fail [message] (throw (ex-info message {:wmark/error :invalid})))

;; ---------------------------------------------------------------------------
;; cmap

(defn- cmap-format-12 [b o]
  (let [n (u32 b (+ o 12))]
    (vec (for [k (range n) :let [g (+ o 16 (* 12 k))]]
           [(u32 b g) (u32 b (+ g 4)) (u32 b (+ g 8))]))))

(defn- lookup-12 [groups cp]
  (loop [lo 0 hi (dec (count groups))]
    (when (<= lo hi)
      (let [mid (quot (+ lo hi) 2)
            [start end gid] (groups mid)]
        (cond (< cp start) (recur lo (dec mid))
              (> cp end)   (recur (inc mid) hi)
              :else        (+ gid (- cp start)))))))

(defn- cmap-format-4 [b o]
  (let [segs (quot (u16 b (+ o 6)) 2)
        ends   (+ o 14)
        starts (+ ends (* 2 segs) 2)
        deltas (+ starts (* 2 segs))
        ranges (+ deltas (* 2 segs))]
    (vec (for [k (range segs)]
           {:end (u16 b (+ ends (* 2 k))) :start (u16 b (+ starts (* 2 k)))
            :delta (u16 b (+ deltas (* 2 k))) :range (u16 b (+ ranges (* 2 k)))
            :range-at (+ ranges (* 2 k))}))))

(defn- lookup-4 [b segments cp]
  (when (<= cp 0xFFFF)
    (when-let [{:keys [start delta range range-at]} (first (filter #(<= cp (:end %)) segments))]
      (when (>= cp start)
        (if (zero? range)
          (mod (+ cp delta) 65536)
          (let [g (u16 b (+ range-at range (* 2 (- cp start))))]
            (if (zero? g) 0 (mod (+ g delta) 65536))))))))

(defn- cmap-lookup
  "codepoint -> glyph id (0 when missing) from the best Unicode subtable."
  [b cmap]
  (let [n (u16 b (+ cmap 2))
        subtables (for [k (range n) :let [r (+ cmap 4 (* 8 k))]]
                    {:platform (u16 b r) :encoding (u16 b (+ r 2)) :offset (+ cmap (u32 b (+ r 4)))})
        fmt (fn [{:keys [offset]}] (u16 b offset))
        unicode? (fn [{:keys [platform encoding]}]
                   (or (= platform 0) (and (= platform 3) (#{1 10} encoding))))
        pick (fn [f] (first (filter #(and (unicode? %) (= f (fmt %))) subtables)))]
    (if-let [t12 (pick 12)]
      (let [groups (cmap-format-12 b (:offset t12))] (fn [cp] (or (lookup-12 groups cp) 0)))
      (if-let [t4 (pick 4)]
        (let [segments (cmap-format-4 b (:offset t4))] (fn [cp] (or (lookup-4 b segments cp) 0)))
        (fail "The font has no Unicode cmap (format 4 or 12).")))))

;; ---------------------------------------------------------------------------
;; The font

(defn parse
  "A TrueType font from its file's bytes."
  [b]
  (let [version (u32 b 0)
        _       (when-not (#{0x00010000 0x74727565} version)
                  (fail (if (= "OTTO" (tag b 0))
                          "CFF (PostScript-outline) fonts aren't supported: use a TrueType (glyf) font."
                          "Not a TrueType font file.")))
        tables  (into {} (for [k (range (u16 b 4)) :let [r (+ 12 (* 16 k))]]
                           [(tag b r) (u32 b (+ r 8))]))
        need    (fn [t] (or (tables t) (fail (str "The font has no " t " table."))))
        head    (need "head") hhea (need "hhea") maxp (need "maxp")
        glyphs  (u16 b (+ maxp 4))
        long?   (= 1 (i16 b (+ head 50)))
        loca    (need "loca")]
    {:bytes        b
     :units-per-em (u16 b (+ head 18))
     :ascender     (i16 b (+ hhea 4))
     :descender    (i16 b (+ hhea 6))
     :line-gap     (i16 b (+ hhea 8))
     :metrics      (u16 b (+ hhea 34))
     :hmtx         (need "hmtx")
     :glyf         (need "glyf")
     :glyphs       glyphs
     :loca         (vec (for [g (range (inc glyphs))]
                          (if long? (u32 b (+ loca (* 4 g))) (* 2 (u16 b (+ loca (* 2 g)))))))
     :cmap         (cmap-lookup b (need "cmap"))}))

(defn glyph-id [font cp] ((:cmap font) cp))

(defn advance
  "Advance width of glyph `g` in font units."
  [{:keys [bytes hmtx metrics]} g]
  (u16 bytes (+ hmtx (* 4 (min g (dec metrics))))))

;; ---------------------------------------------------------------------------
;; Outlines

(defn- simple-glyph [b o contours]
  (let [ends   (mapv #(u16 b (+ o 10 (* 2 %))) (range contours))
        points (if (seq ends) (inc (peek ends)) 0)
        ins    (+ o 10 (* 2 contours))
        flags-at (+ ins 2 (u16 b ins))
        ;; flags, with repeats expanded
        [flags after-flags]
        (loop [fs [] i flags-at]
          (if (>= (count fs) points)
            [fs i]
            (let [f (u8 b i)]
              (if (pos? (bit-and f 8))
                (recur (into fs (repeat (inc (u8 b (inc i))) f)) (+ i 2))
                (recur (conj fs f) (inc i))))))
        coords (fn [start short-bit same-bit]
                 (loop [k 0 v 0 i start out []]
                   (if (= k points)
                     [out i]
                     (let [f (flags k)]
                       (cond
                         (pos? (bit-and f short-bit))
                         (let [d (u8 b i) v (long (+ v (if (pos? (bit-and f same-bit)) d (- d))))]
                           (recur (inc k) v (inc i) (conj out v)))
                         (pos? (bit-and f same-bit)) (recur (inc k) v i (conj out v))
                         :else (let [v (long (+ v (i16 b i)))] (recur (inc k) v (+ i 2) (conj out v))))))))
        [xs after-x] (coords after-flags 2 16)
        [ys _]       (coords after-x 4 32)
        pts (mapv (fn [k] {:x (xs k) :y (ys k) :on? (pos? (bit-and (flags k) 1))}) (range points))]
    (loop [start 0 [end & more] ends out []]
      (if end
        (recur (long (inc end)) more (conj out (subvec pts start (inc end))))
        out))))

(declare outline)

(defn- composite-glyph [font o depth]
  (let [b (:bytes font)]
    (loop [i (+ o 10) out []]
      (let [flags (u16 b i) gid (u16 b (+ i 2))
            words? (pos? (bit-and flags 1)) xy? (pos? (bit-and flags 2))
            [dx dy i] (if words?
                        [(i16 b (+ i 4)) (i16 b (+ i 6)) (+ i 8)]
                        [(i8 b (+ i 4)) (i8 b (+ i 5)) (+ i 6)])
            [dx dy] (if xy? [dx dy] [0 0])                ; point matching: not supported, placed at 0
            [[a bb c d] i] (cond
                             (pos? (bit-and flags 8))    (let [s (f2dot14 b i)] [[s 0.0 0.0 s] (+ i 2)])
                             (pos? (bit-and flags 0x40)) [[(f2dot14 b i) 0.0 0.0 (f2dot14 b (+ i 2))] (+ i 4)]
                             (pos? (bit-and flags 0x80)) [[(f2dot14 b i) (f2dot14 b (+ i 2))
                                                          (f2dot14 b (+ i 4)) (f2dot14 b (+ i 6))] (+ i 8)]
                             :else                       [[1.0 0.0 0.0 1.0] i])
            part (for [contour (outline font gid (inc depth))]
                   (mapv (fn [{:keys [x y on?]}]
                           {:x (+ (* a x) (* c y) dx) :y (+ (* bb x) (* d y) dy) :on? on?})
                         contour))
            out (into out part)]
        (if (pos? (bit-and flags 0x20)) (recur i out) out)))))

(defn outline
  "Contours of glyph `g` in font units (y up); [] for an empty glyph."
  ([font g] (outline font g 0))
  ([{:keys [bytes loca glyf glyphs] :as font} g depth]
   (let [g (if (< -1 g glyphs) g 0)
         start (loca g) end (loca (inc g))]
     (cond
       (> depth 8) (fail "The font's composite glyphs nest too deeply.")
       (>= start end) []
       :else (let [o (+ glyf start)
                   n (i16 bytes o)]
               (if (neg? n) (composite-glyph font o depth) (simple-glyph bytes o n)))))))
