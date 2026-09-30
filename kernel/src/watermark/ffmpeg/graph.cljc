;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.ffmpeg.graph
  "Pure filtergraph construction: data in, FFmpeg filtergraph text out.
  Part of the core library: the same graph on the JVM and the Dart VM
  (kernel/test/golden/ffmpeg.edn).

  Filters are data -- {:filter \"drawtext\" :args [[:x <expr>] ...]} -- so
  plans can be logged, diffed and unit-tested without running FFmpeg.

  Rules that keep generated graphs correct on every platform:
  * Literal values (paths, colours) go through `escape-value`: FFmpeg
    unescapes twice (graph level, then option level), and Windows paths
    contain ':' (C:/...).
  * Expressions are single-quoted, so their commas and semicolons survive
    the graph parser. Expressions contain no quotes, by construction.
  * User text never enters the graph. drawtext reads it from a UTF-8
    `textfile` with `expansion=none`: no escaping bugs, no filter injection
    (which matters once this runs as a SaaS backend).
  * Numbers are rendered locale-independently (watermark.util.num/decimal-str).
    A locale-aware format writes 0,85 on a German Windows -- and a comma is
    a filter separator."
  (:require [clojure.string :as str]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Rendering primitives

(defn num-str
  "Shortest plain decimal: 24 -> \"24\", 0.85 -> \"0.85\", 1e-4 -> \"0.0001\"."
  [x]
  (number/decimal-str x))

(defn escape-value
  "Escape a literal for a filter option value: option level (\\ ' :) first,
  then graph level (\\ ' [ ] , ;)."
  [s]
  (-> (str s)
      (str/replace #"[\\':]" #(str "\\" %))
      (str/replace #"[\\'\[\],;]" #(str "\\" %))))

(defn expr
  "Mark `s` as an FFmpeg expression (rendered single-quoted)."
  [s]
  {::expr (str s)})

(defn f
  "Filter spec. (f \"scale\" :w 320 :h -2)"
  [filter-name & kvs]
  {:filter filter-name :args (vec (partition 2 kvs))})

(defn- render-value [v]
  (cond
    (and (map? v) (contains? v ::expr)) (str "'" (::expr v) "'")
    (keyword? v)                        (name v)
    (number? v)                         (num-str v)
    :else                               (escape-value v)))

(defn render-filter [{:keys [filter args]}]
  (let [kvs (for [[k v] args :when (some? v)]
              (str (name k) "=" (render-value v)))]
    (if (seq kvs) (str filter "=" (str/join ":" kvs)) filter)))

(defn chain
  "[in-labels] filter-specs [out-labels] -> one filterchain."
  [ins filters outs]
  {:in ins :filters filters :out outs})

(defn render [chains]
  (str/join ";\n"
            (for [{:keys [in filters out]} chains]
              (str (apply str (map #(str "[" % "]") in))
                   (str/join "," (map render-filter filters))
                   (apply str (map #(str "[" % "]") out))))))
