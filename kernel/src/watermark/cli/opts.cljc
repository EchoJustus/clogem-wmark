;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.cli.opts
  "Command-line options, parsed the same way on every host (docs/adr/0014):
  the part of clojure.tools.cli's option specs the wmark CLI uses, so the
  JVM and the Dart VM read a command line alike.

  A spec is [short long description & {:default :parse-fn :validate}]:
    short     \"-p\", or nil
    long      \"--profile NAME\" takes a value, shown as NAME; \"--clean\" is a
              flag, true when given. The option's key is the long name as a
              keyword (:profile, :clean).
    :default  the value when the option isn't given (not parsed)
    :parse-fn (fn [text]) the value from its text
    :validate [pred message]: an error unless (pred value)

  Values come as \"--profile NAME\", \"--profile=NAME\" or \"-p NAME\"; \"--\" ends
  the options."
  (:require [clojure.string :as str]
            [watermark.util.host :as host]))

#?(:clj (set! *warn-on-reflection* true))

(defn- compile-spec [[short long desc & {:as kvs}]]
  (let [[flag arg] (str/split long #" " 2)]
    (merge {:id (keyword (subs flag 2)) :short short :long flag :arg arg :desc desc}
           (select-keys kvs [:parse-fn :validate])
           (when (contains? kvs :default) {:default? true :default (:default kvs)}))))

(defn- option-text [{:keys [long arg]} value]
  (str "\"" long (when arg (str " " value)) "\""))

(defn- value-of
  "[value nil] for option `spec` given as `text`, or [nil error]."
  [{:keys [parse-fn validate] :as spec} text]
  (let [[v e] (if parse-fn
                (try [(parse-fn text) nil]
                     (catch #?(:clj Exception :cljd Object) e
                       [nil (str "Error while parsing option " (option-text spec text) ": "
                                 (or (ex-message e) (host/describe-error e)))]))
                [text nil])
        [pred msg] validate]
    (cond e                      [nil e]
          (and pred (not (pred v))) [nil (str "Failed to validate " (option-text spec text) ": " msg)]
          :else                  [v nil])))

(defn- column [rows i]
  (reduce max 0 (map #(count (nth % i)) rows)))

(defn summary
  "The options' help text: one line per option, in columns."
  [specs]
  (let [specs (map compile-spec specs)
        dflt? (some :default? specs)
        rows  (for [{:keys [short long arg desc default? default]} specs]
                (cond-> [(str (if short (str short ", ") "    ") long (when arg (str " " arg)))]
                  dflt? (conj (if default? (if (keyword? default) (name default) (str default)) ""))
                  true  (conj (or desc ""))))
        n     (count (first rows))
        widths (mapv #(column rows %) (range n))
        pad   (fn [s w] (str s (apply str (repeat (- w (count s)) " "))))]
    (str/join "\n" (for [row rows]
                     (str/replace (str "  " (str/join "  " (map pad row widths))) #" +$" "")))))

(defn parse-opts
  "Parse `args` with `specs`: {:options :arguments :errors :summary}, where
  :errors is nil or a vector of messages. With :in-order true, the first
  argument that isn't an option ends the options (a command and its own
  arguments follow)."
  [args specs & {:keys [in-order]}]
  (let [compiled (mapv compile-spec specs)
        by-long  (into {} (map (juxt :long identity) compiled))
        by-short (into {} (keep #(when (:short %) [(:short %) %]) compiled))
        defaults (into {} (keep #(when (:default? %) [(:id %) (:default %)]) compiled))]
    (loop [args (seq args) options defaults arguments [] errors []]
      (if-not args
        {:options options :arguments arguments :errors (not-empty errors) :summary (summary specs)}
        (let [[a & more] args
              [name inline] (if (str/starts-with? a "--") (str/split a #"=" 2) [a nil])
              spec (cond (= "--" a) nil
                         (str/starts-with? a "--") (by-long name)
                         (and (str/starts-with? a "-") (> (count a) 1)) (by-short a))]
          (cond
            (= "--" a)
            (recur nil options (into arguments more) errors)

            (and (nil? spec) (str/starts-with? a "-") (> (count a) 1))
            (recur more options arguments (conj errors (str "Unknown option: \"" name "\"")))

            (nil? spec)
            (if in-order
              (recur nil options (into (conj arguments a) more) errors)
              (recur more options (conj arguments a) errors))

            (not (:arg spec))
            (if inline
              (recur more options arguments (conj errors (str "Option \"" (:long spec) "\" takes no value")))
              (recur more (assoc options (:id spec) true) arguments errors))

            :else
            (let [[text more] (if inline [inline more] [(first more) (next more)])]
              (if (nil? text)
                (recur more options arguments
                       (conj errors (str "Missing required argument for " (option-text spec (:arg spec)))))
                (let [[v e] (value-of spec text)]
                  (if e
                    (recur more options arguments (conj errors e))
                    (recur more (assoc options (:id spec) v) arguments errors)))))))))))
