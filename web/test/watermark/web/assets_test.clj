;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.assets-test
  "The vendored front end is exactly the file we reviewed. Upgrading
  Datastar is a deliberate act: new file, new hash here and in
  datastar.LICENSE.txt, then the browser check (test/e2e/ui_smoke.py)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.security MessageDigest)))

(set! *warn-on-reflection* true)

(defn- sha256 [resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (let [md (MessageDigest/getInstance "SHA-256")]
      (.update md (.readAllBytes in))
      (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))))))

(deftest datastar-is-pinned
  (is (= "727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91" (sha256 "public/datastar.js")))
  (is (str/includes? (slurp (io/resource "public/datastar.LICENSE.txt")) "Permission is hereby granted")
      "MIT: the notice ships with the file"))

(deftest no-javascript-of-our-own
  (is (nil? (io/resource "public/app.js")) "the UI is server-rendered: the only script is datastar.js")
  (is (some? (io/resource "public/app.css"))))

(deftest the-icon-is-a-plain-drawing
  ;; wmark's icon (the clogem icon set's v2.1 master), served as the top
  ;; bar's mark and the favicon: an image, so it may run nothing and reach
  ;; nothing outside itself (docs/adr/0011, "The look, refreshed")
  (let [svg (slurp (io/resource "public/wmark.svg"))]
    (is (str/includes? svg "SPDX-License-Identifier: EPL-2.0"))
    (is (re-find #"<svg\b" svg))
    (is (not (re-find #"(?i)<script|<foreignObject|<image\b|\son[a-z]+\s*=" svg)))
    (is (not (re-find #"(?i)href\s*=\s*[\"'](?!#)|url\(\s*[\"']?(?!#)" svg)) "nothing outside itself")))
