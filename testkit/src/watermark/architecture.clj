;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.architecture
  "Helpers for architecture fitness tests: read `ns` forms (never load code),
  find what each namespace requires, locate source roots on the classpath, and
  read the SPDX license identifier at the top of a file.

  Shared by clogem-wmark's own architecture test and by repositories that build
  on it: those find the kernel's sources through `source-root` because the
  kernel is a dependency there, not a folder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File PushbackReader)))

(set! *warn-on-reflection* true)

(defn ns-form [^File f]
  (with-open [r (PushbackReader. (io/reader f))]
    (binding [*read-eval* false]
      (read {:read-cond :allow :features #{:clj}} r))))

(defn required [form]
  (->> (rest form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (keep (fn [spec] (cond (symbol? spec) spec
                              (and (vector? spec) (symbol? (first spec))) (first spec))))
       set))

(defn clojure-file? [^File f] (boolean (re-find #"\.clj[cd]?$" (.getName f))))

(defn sources
  "Every Clojure source under `root` (a path or File): its ns, what it
  requires, and whether it is portable .cljc. Missing roots give nothing."
  [root]
  (->> (file-seq (io/file root))
       (filter #(and (.isFile ^File %) (clojure-file? %)))
       (map (fn [^File f] (let [form (ns-form f)]
                            {:file (str f) :ns (second form) :requires (required form)
                             :cljc? (str/ends-with? (.getName f) ".cljc")})))))

(defn warns-on-reflection?
  "Is the first form after `f`'s ns form `(set! *warn-on-reflection* true)`?
  In a .cljc file it may sit behind #?(:clj ...), which reads as the plain form
  on the JVM. A native image fails at run time on a reflective call its build
  didn't register, and reflection warnings are how those calls show up."
  [^File f]
  (with-open [r (PushbackReader. (io/reader f))]
    (binding [*read-eval* false]
      (let [opts {:read-cond :allow :features #{:clj} :eof ::eof}]
        (try (read opts r)
             (= '(set! *warn-on-reflection* true) (read opts r))
             (catch Exception _ false))))))

(defn violations [nses pred]
  (for [{:keys [ns requires]} nses, r requires :when (pred r)] [ns r]))

(defn under? [prefixes sym] (some #(str/starts-with? (str sym) %) prefixes))

(defn source-root
  "The directory that holds `ns-sym` on the classpath: a local folder or a
  checked-out git dependency. nil when the namespace isn't on the classpath as
  a plain file (inside a jar, say)."
  ^File [ns-sym]
  (let [base  (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/"))
        depth (count (str/split base #"/"))]
    (some (fn [ext]
            (when-let [u (io/resource (str base ext))]
              (when (= "file" (.getProtocol ^java.net.URL u))
                (nth (iterate #(.getParentFile ^File %) (io/file (.toURI ^java.net.URL u))) depth))))
          [".cljc" ".clj"])))

;; -- License headers ----------------------------------------------------------

(def open-license "EPL-2.0")

(def closed-license
  "The identifier every file of the commercial repository carries. Spelled in
  two parts so this file never matches a search for it."
  (str "LicenseRef-" "clogem-proprietary"))

(def code-extensions #{"clj" "cljc" "cljd" "c" "h" "sh" "py" "css" "sql" "ps1"})

(defn- extension [^File f]
  (let [n (.getName f) i (.lastIndexOf n ".")]
    (when (pos? i) (subs n (inc i)))))

(defn code-files
  "Files under `roots` whose extension is in `code-extensions`, minus any whose
  path contains one of `excluded` (vendored third-party material)."
  [roots & {:keys [excluded]}]
  (->> roots
       (mapcat #(file-seq (io/file %)))
       (filter (fn [^File f] (and (.isFile f) (code-extensions (extension f))
                                  (not-any? #(str/includes? (str/replace (str f) "\\" "/") %) excluded))))))

(defn spdx-id
  "The SPDX-License-Identifier in the first five lines of `f`, or nil."
  [^File f]
  (with-open [r (io/reader f)]
    (some #(second (re-find #"SPDX-License-Identifier:\s*([A-Za-z0-9.+\-]+)" %))
          (take 5 (line-seq r)))))

(defn mentions?
  "Does `f` contain `s` anywhere?"
  [^File f ^String s]
  (str/includes? (slurp f) s))
