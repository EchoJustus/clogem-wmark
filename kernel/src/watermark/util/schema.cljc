;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.schema
  "Validation, error messages and JSON decoding for the kernel's schemas, in
  malli's words but without malli, so settings and render specs are checked
  the same way on the JVM and on the Dart VM (malli doesn't compile under
  ClojureDart; docs/adr/0012).

  The schemas stay malli's vector syntax (watermark.core.schema,
  watermark.render.schema), and malli still turns them into JSON Schema on
  the JVM. This namespace reads the part of the syntax they use:

    :int :double :string :boolean :keyword, with {:min :max} as in malli
    [:enum & values]  [:= value]  [:re pattern]  pos-int?
    [:map {:closed true} & [key {:optional true} schema]]  [:map-of k v]
    [:vector {:min :max} s]  [:tuple & s]  [:maybe s]
    [:multi {:dispatch key :decode/json f} & [value schema]]

  and refuses anything else, so a new kind of schema can't pass unchecked.

  - `errors` is malli's `(humanize (explain schema x))`: the same messages
    in the same nested shape, nil when x is valid.
  - `decoder` is malli's json-transformer decoder for the same types.

  kernel/test/golden/schema.edn pins both for a corpus of settings and render
  specs. Both runtimes reproduce it, and on the JVM malli agrees with it
  except where this namespace differs on purpose:

  - a pattern must match the whole string. malli searches for it
    (`re-find`), and Java's `$` also matches before a final line break, so
    \"red\\n\" passed as a colour on the JVM, never in Dart or JSON Schema;
  - numbers compare by kind as well as value, as on the JVM: on the Dart VM
    1 and 1.0 are `=`, so `[:= 1]` checks that 1 is an integer."
  (:require [clojure.string :as str]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Reading the syntax

(def ^:private predicates
  "Predicate schemas: malli's message, and how JSON decodes them."
  {pos-int? {:message "should be a positive int" :valid? pos-int? :decode :int}})

(declare schema)

(defn- split
  "[type props children] of a vector schema."
  [[t & more]]
  (if (map? (first more))
    [t (first more) (vec (rest more))]
    [t nil (vec more)]))

(defn- entry [e]
  (let [[k props s] (if (map? (second e)) e [(first e) nil (second e)])]
    {:key k :optional? (boolean (:optional props)) :schema (schema s)}))

(defn- fail [message s]
  (throw (ex-info (str message " " (pr-str s)) {:wmark/error :invalid-schema})))

(defn schema
  "A schema in malli's vector syntax, read once into the form `errors`,
  `valid?` and `decoder` take."
  [s]
  (cond
    (contains? predicates s) (assoc (predicates s) :type :pred)
    (keyword? s)             (if (#{:int :double :string :boolean :keyword} s)
                               {:type s}
                               (fail "Unsupported schema" s))
    (vector? s)
    (let [[t props children] (split s)]
      (case t
        (:int :double :string) {:type t :min (:min props) :max (:max props)}
        :enum   {:type :enum :values children}
        :=      {:type := :value (first children)}
        :re     {:type :re :re (let [r (first children)] (if (string? r) (re-pattern r) r))}
        :maybe  {:type :maybe :child (schema (first children))}
        :vector {:type :vector :min (:min props) :max (:max props) :child (schema (first children))}
        :tuple  {:type :tuple :children (mapv schema children)}
        :map-of {:type :map-of :min (:min props) :max (:max props)
                 :key (schema (first children)) :value (schema (second children))}
        :map    (let [entries (mapv entry children)]
                  {:type :map :closed? (boolean (:closed props)) :entries entries
                   :keys (set (map :key entries))})
        :multi  {:type :multi :dispatch (:dispatch props) :enter (:decode/json props)
                 :cases (into {} (for [[v c] children] [v (schema c)]))}
        (fail "Unsupported schema" s)))
    :else (fail "Unsupported schema" s)))

;; ---------------------------------------------------------------------------
;; Checking

(defn- same?
  "= that tells an integer from a float, like the JVM (the Dart VM's = holds
  1 and 1.0 equal)."
  [a b]
  (and (= a b) (or (not (number? a)) (= (integer? a) (integer? b)))))

(defn- within? [{:keys [min max]} v]
  (and (or (nil? min) (<= min v)) (or (nil? max) (<= v max))))

(defn- min-max-message
  "malli's message for :int and :double."
  [{:keys [min max]} ok? message x]
  (cond (not (ok? x))              message
        (and min (= min max))      (str "should be " min)
        (and min (< x min))        (str "should be at least " min)
        max                        (str "should be at most " max)))

(defn- characters [n] (str n " character" (when (not= 1 n) "s")))

(defn- string-message [{:keys [min max]} x]
  (cond (not (string? x))              "should be a string"
        (and min (= min max))          (str "should be " (characters min))
        (and min (< (count x) min))    (str "should be at least " (characters min))
        max                            (str "should be at most " (characters max))))

(defn- limits-message [{:keys [min max]} x]
  (cond (and min (= min max))       (str "should have " min " elements")
        (and min (< (count x) min)) (str "should have at least " min " elements")
        max                         (str "should have at most " max " elements")))

(defn- enum-message [values]
  (str "should be "
       (if (= 1 (count values))
         (pr-str (first values))
         (str "either " (str/join ", " (map pr-str (butlast values))) " or " (pr-str (last values))))))

(defn- explain
  "Errors of x against s, in malli's order: [{:in path :message m}]."
  [s x in acc]
  (let [err (fn [message] (conj acc {:in in :message message}))]
    (case (:type s)
      :int     (if (and (int? x) (within? s x)) acc (err (min-max-message s int? "should be an integer" x)))
      :double  (if (and (double? x) (within? s x)) acc (err (min-max-message s double? "should be a double" x)))
      :string  (if (and (string? x) (within? s (count x))) acc (err (string-message s x)))
      :boolean (if (boolean? x) acc (err "should be a boolean"))
      :keyword (if (keyword? x) acc (err "should be a keyword"))
      :pred    (if ((:valid? s) x) acc (err (:message s)))
      :enum    (if (some #(same? % x) (:values s)) acc (err (enum-message (:values s))))
      :=       (if (same? (:value s) x) acc (err (str "should be " (pr-str (:value s)))))
      :re      (if (and (string? x) (re-matches (:re s) x)) acc (err "should match regex"))
      :maybe   (if (nil? x) acc (explain (:child s) x in acc))
      :vector  (cond (not (vector? x))  (err "invalid type")
                     (not (within? s (count x))) (err (limits-message s x))
                     :else (reduce-kv (fn [acc i v] (explain (:child s) v (conj in i) acc)) acc x))
      :tuple   (let [n (count (:children s))]
                 (cond (not (vector? x)) (err "invalid type")
                       (not= n (count x)) (err (str "invalid tuple size " (count x) ", expected " n))
                       :else (reduce-kv (fn [acc i c] (explain c (x i) (conj in i) acc)) acc (:children s))))
      :map-of  (cond (not (map? x)) (err "invalid type")
                     (not (within? s (count x))) (err (limits-message s x))
                     :else (reduce-kv (fn [acc k v]
                                        (->> acc
                                             (explain (:key s) k (conj in k))
                                             (explain (:value s) v (conj in k))))
                                      acc x))
      :map     (if-not (map? x)
                 (err "invalid type")
                 (let [acc (reduce (fn [acc {:keys [key optional?] :as e}]
                                     (if-let [[_ v] (find x key)]
                                       (explain (:schema e) v (conj in key) acc)
                                       (if optional? acc (conj acc {:in (conj in key) :message "missing required key"}))))
                                   acc (:entries s))]
                   (if (:closed? s)
                     (reduce-kv (fn [acc k _]
                                  (if (contains? (:keys s) k) acc (conj acc {:in (conj in k) :message "disallowed key"})))
                                acc x)
                     acc)))
      :multi   (let [d (:dispatch s)]
                 (if-let [c (get (:cases s) (when (map? x) (get x d)))]
                   (explain c x in acc)
                   (conj acc {:in (if (and (map? x) (keyword? d)) (conj in d) in)
                              :message "invalid dispatch value"}))))))

;; malli.error/humanize's nesting (-push-in), kept as it is so the shapes match

(defn- error [message] (with-meta [message] {::error true}))
(defn- error? [x] (::error (meta x)))

(defn- lookup [x k]
  (cond (or (set? x) (associative? x)) (get x k)
        (sequential? x)                (get (vec x) k)))

(defn- push [x k v]
  (let [x (if (and (int? k) (sequential? x) (> k (count x)))
            (cond->> (concat x (repeat (- k (count x)) nil)) (not (seq? x)) (into (empty x)))
            x)]
    (cond (or (nil? x) (associative? x)) (assoc x k v)
          (set? x)                       (conj x v)
          :else                          (apply list (assoc (vec x) k v)))))

(defn- push-in [a v [p & ps] message]
  (let [a' (or a (cond (sequential? v) [] (coll? v) (empty v)))]
    (cond (and p (error? a')) a
          p                   (push a' p (push-in (lookup a' p) (lookup v p) ps message))
          (map? a)            (push-in a' v [:malli/error] message)
          (error? a')         (conj a' message)
          (vector? (not-empty a')) a'
          :else               (error message))))

(defn errors
  "Humanized errors of x against the read schema s, as malli.error/humanize
  gives them, or nil when x is valid."
  [s x]
  (when-let [es (seq (explain s x [] []))]
    (reduce (fn [acc {:keys [in message]}] (push-in acc x in message)) nil es)))

(defn valid? [s x] (empty? (explain s x [] [])))

;; ---------------------------------------------------------------------------
;; JSON decoding (malli.transform/json-transformer)

(defn- number->long
  "A whole float within the 64-bit range as an integer (JSON writes 2 as 2.0
  at times); anything else as it is."
  [x]
  (if (and (number? x) (not (integer? x)) (< -9.2e18 x 9.2e18))
    (let [n (number/floor-int x)] (if (= (* 1.0 n) x) n x))
    x))

(defn- number->double [x] (if (number? x) (double x) x))
(defn- string->keyword [x] (if (string? x) (keyword x) x))

(defn- infer
  "The decoder for :enum and := values, by the kind all of them share."
  [values]
  (cond (every? string? values)  nil
        (every? keyword? values) string->keyword
        (every? int? values)     number->long
        (every? double? values)  number->double))

(defn decoder
  "(fn [json-shaped-value] value) for the read schema s: strings to keywords,
  whole numbers to integers and numbers to doubles where s says so."
  [s]
  (case (:type s)
    (:string :boolean :re) nil
    :int     number->long
    :double  number->double
    :keyword string->keyword
    :pred    (case (:decode s) :int number->long nil)
    :enum    (infer (:values s))
    :=       (infer [(:value s)])
    :maybe   (decoder (:child s))
    :vector  (when-let [d (decoder (:child s))]
               (fn [x] (if (or (sequential? x) (set? x)) (into [] (map d) x) x)))
    :map     (let [ds (vec (for [{:keys [key schema]} (:entries s)
                                 :let [d (decoder schema)] :when d]
                             [key d]))]
               (when (seq ds)
                 (fn [x]
                   (if (map? x)
                     (reduce (fn [m [k d]] (if-let [[_ v] (find m k)] (assoc m k (d v)) m)) x ds)
                     x))))
    :multi   (let [ds    (into {} (for [[v c] (:cases s) :let [d (decoder c)] :when d] [v d]))
                   enter (or (:enter s) identity)
                   d     (:dispatch s)]
               (fn [x]
                 (let [x (enter x)]
                   (if-let [f (get ds (when (map? x) (get x d)))] (f x) x))))
    (fail "No JSON decoding for" (:type s))))
