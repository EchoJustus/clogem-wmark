;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.golden
  "Golden vectors: pinned outputs of the kernel for fixed inputs.

  They are the cross-platform contract. A ClojureDart build of the kernel,
  a Swift or Kotlin engine re-implementing the reference semantics, or a Rust
  GPU core must reproduce these files exactly. Regenerate only for an
  intentional, versioned change:  WMARK_UPDATE_GOLDEN=1 bb test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :refer [is]]))

(set! *warn-on-reflection* true)

(defn check-in
  "Compare `actual` with the golden file `dir`/`name`.edn (or rewrite it when
  updating)."
  [dir name actual]
  (let [f (io/file dir (str name ".edn"))]
    (if (or (System/getenv "WMARK_UPDATE_GOLDEN") (not (.exists f)))
      (do (io/make-parents f)
          (binding [*print-length* nil *print-level* nil *print-namespace-maps* false]
            (spit f (with-out-str (pprint/pprint actual))))
          (is true (str "wrote golden " name)))
      (is (= (edn/read-string (slurp f)) actual) (str "golden vector " name " changed")))))

(defn check [name actual] (check-in "kernel/test/golden" name actual))
