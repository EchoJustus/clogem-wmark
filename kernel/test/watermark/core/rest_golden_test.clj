;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.rest-golden-test
  "Golden vectors for the REST contract (kernel/test/golden/rest.edn): the
  same answers to the same requests on the JVM and the Dart VM (kernel/dart),
  where an app answers them in its own process."
  (:require [clojure.test :refer [deftest is]]
            [watermark.core.rest :as rest]
            [watermark.golden :as golden]
            [watermark.golden-inputs :as inputs]
            [watermark.util.task :as task])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.util Random)))

(set! *warn-on-reflection* true)

(deftest golden-rest
  (golden/check "rest" (task/await (inputs/rest-vectors))))

(defn- random-text
  "Up to 12 code points, from ASCII to astral planes, never a surrogate."
  ^String [^Random r]
  (let [sb (StringBuilder.)]
    (dotimes [_ (.nextInt r 13)]
      (let [cp (case (int (.nextInt r 4))
                 0 (+ 0x20 (.nextInt r 0x5F))
                 1 (+ 0xA0 (.nextInt r 0x700))
                 2 (+ 0xE000 (.nextInt r 0x1000))
                 (+ 0x10000 (.nextInt r 0x1000)))]
        (.appendCodePoint sb (int cp))))
    (str sb)))

(deftest segments-decode-as-the-jdk-decodes-them
  ;; what the HTTP adapter used before the contract moved into the library
  (let [r (Random. 42)]
    (dotimes [_ 2000]
      (let [s       (random-text r)
            encoded (.replace (URLEncoder/encode s StandardCharsets/UTF_8) "+" "%20")
            jdk     (URLDecoder/decode (.replace encoded "+" "%2B") StandardCharsets/UTF_8)]
        (is (= s jdk (rest/decode-segment encoded)) encoded)))
    (is (= "a+b" (rest/decode-segment "a+b")) "+ is literal in a path")))
