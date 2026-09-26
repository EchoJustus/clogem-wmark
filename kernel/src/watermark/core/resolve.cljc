;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.resolve
  "Layered settings resolution -- the pure half of the fallback logic.

  watermark.config decides *which* layers apply (defaults, a named profile or
  `latest`, explicit overrides) and loads them from a store; this namespace
  merges them and records which layer won each field. It is host-independent,
  so an in-process GUI (Stage 3/4) resolves settings exactly like the engine.")

(defn prune-nils
  "Drop nil-valued entries recursively: nil means \"not specified\", which is
  what lets a missing CLI flag or JSON null fall through to the layer below."
  [m]
  (if (map? m)
    (reduce-kv (fn [acc k v] (if (nil? v) acc (assoc acc k (prune-nils v))))
               {} m)
    m))

(defn deep-merge
  "Right-biased recursive merge. Maps merge key by key; every other value --
  vectors included -- is replaced wholesale, so a profile's list of text
  layers replaces the fallback's list instead of interleaving with it."
  ([] {})
  ([a] a)
  ([a b]
   (cond (nil? b)                a
         (and (map? a) (map? b)) (merge-with deep-merge a b)
         :else                   b))
  ([a b & more] (reduce deep-merge (deep-merge a b) more)))

(defn leaf-paths
  "Key paths to every non-map value (vectors count as leaves)."
  ([m] (leaf-paths [] m))
  ([prefix m]
   (if (and (map? m) (seq m))
     (mapcat (fn [[k v]] (leaf-paths (conj prefix k) v)) m)
     (if (seq prefix) [prefix] []))))

(defn layer
  "Merge `layers`, a seq of [source settings] from lowest to highest
  precedence. Returns {:settings merged, :provenance {path source}}, where
  source is the layer that supplied each leaf."
  [layers]
  (let [layers (mapv (fn [[source data]] [source (prune-nils (or data {}))]) layers)
        merged (reduce deep-merge {} (map second layers))]
    {:settings   merged
     :provenance (into {}
                       (for [p (leaf-paths merged)]
                         [p (some (fn [[source data]]
                                    (when (some? (get-in data p)) source))
                                  (rseq layers))]))}))
