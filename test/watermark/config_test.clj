;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.config-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [watermark.config :as config]
            [watermark.home :as home]
            [watermark.store-contract :as contract]
            [watermark.store.memory :as memory])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:dynamic *store* nil)
(def ^:dynamic ^Path *home* nil)

(defn- delete-tree! [^Path root]
  (doseq [^java.io.File f (reverse (file-seq (.toFile root)))] (.delete f)))

(use-fixtures :each
  (fn [t]
    (let [home (Files/createTempDirectory "wmark-test" (make-array FileAttribute 0))]
      (try
        (binding [*home* home
                  *store* (home/file-store {:home (str home)})]
          (t))
        (finally (delete-tree! home))))))

(deftest file-store-meets-the-contract (contract/run-all *store*))
(deftest memory-store-meets-the-contract (contract/run-all (memory/memory-store)))

(deftest slugs-are-portable-file-names
  (is (= "16-9-video-profile" (config/slug "16:9 Video Profile")))
  (is (= "16-9-video-profile" (config/slug "  16-9   VIDEO profile ")))
  (is (= "_con" (config/slug "CON")) "Windows device names are escaped")
  (is (= "_com1" (config/slug "com¹")) "NFKC folds superscripts before the reserved check")
  (is (= "横屏-16-9" (config/slug "横屏 16:9")) "non-Latin letters survive")
  (is (= "abc" (config/slug "ＡＢＣ")) "fullwidth forms fold")
  (is (= :invalid (try (config/slug "::") nil (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e))))))
  (is (<= (count (config/slug (apply str (repeat 200 "a")))) 64))
  (config/create-profile! *store* "16:9 Video Profile" {})
  (is (Files/exists (.resolve *home* "profiles/16-9-video-profile.edn") (make-array LinkOption 0))))

(deftest damaged-files
  (config/record-latest! *store* {:logo {:anchor :top-left}})
  (spit (str (.resolve *home* "profiles/latest.edn")) "{:broken")
  (spit (str (.resolve *home* "profiles/other.edn")) "[1 2 3]")
  (testing "a damaged latest doesn't block a run -- it is reported and skipped"
    (let [r (config/resolve-settings *store* {:defaults contract/defaults})]
      (is (= {:kind :none} (:base r)))
      (is (= 1 (count (:warnings r))))))
  (testing "the list survives damaged files"
    (is (= #{"latest" "other"} (set (map :slug (filter :error (config/list-profiles *store*)))))))
  (testing "record-latest! repairs a damaged latest"
    (config/record-latest! *store* {:logo {:anchor :center}})
    (is (= :center (get-in (config/get-profile! *store* "latest") [:settings :logo :anchor])))))

(deftest files-are-plain-edn
  (config/create-profile! *store* "Readable" {:logo {:anchor :top-left :path "C:\\logos\\a b.png"}})
  (let [text (slurp (str (.resolve *home* "profiles/readable.edn")) :encoding "UTF-8")
        doc  (edn/read-string text)]
    (is (= 1 (:wmark/format doc)))
    (is (= 1 (:profile/rev doc)))
    (is (= "C:\\logos\\a b.png" (get-in doc [:settings :logo :path])))
    (is (not (re-find #"#:" text)) "no namespaced-map syntax: any EDN reader can load it"))
  (testing "files from before revisions existed are rev 0 and can be updated"
    (spit (str (.resolve *home* "profiles/old.edn"))
          (pr-str {:wmark/format 1 :profile/name "Old" :settings {}}))
    (is (= 1 (:profile/rev (config/save-profile! *store* "Old" {:logo {:opacity 0.3}}))))))

(deftest no-temp-files-left-behind
  (run! deref (doall (for [i (range 20)] (future (config/record-latest! *store* {:logo {:opacity (/ i 100.0)}})))))
  (is (= ["latest.edn"] (vec (.list (.toFile (.resolve *home* "profiles")))))))

(defn- java-slug
  "The slug as the JVM host made it with Java's text functions until the
  rules moved into the core library (docs/adr/0013)."
  [^String s]
  (let [n    (-> (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC)
                 clojure.string/trim
                 (clojure.string/replace #"\s+" " "))
        base (-> (java.text.Normalizer/normalize ^String n java.text.Normalizer$Form/NFKC)
                 (.toLowerCase java.util.Locale/ROOT)
                 (clojure.string/replace #"[^\p{L}\p{M}\p{N}]+" "-")
                 (clojure.string/replace #"^-+|-+$" ""))
        base (if (<= (.codePointCount ^String base 0 (count base)) 64)
               base
               (subs base 0 (.offsetByCodePoints ^String base 0 64)))]
    (clojure.string/replace base #"-+$" "")))

(deftest slugs-are-what-they-were
  ;; profiles are found by the file name their slug gives, so the portable
  ;; text functions must not move an existing profile's slug
  (let [rnd      (java.util.Random. 5)
        alphabet [0x20 0x09 0x0A 0xA0 0x2028 0x3000 0x3A 0x2D 0x5F 0x2E 0x27 0x41 0x61 0x49 0x130 0x131
                  0x3A3 0x3C3 0x391 0x392 0x301 0x327 0xC5 0x212B 0xFB01 0xFF21 0xB9 0x2160 0x6A8 0x663
                  0xAC00 0x1100 0x1161 0x11A8 0x5B57 0x3042 0x200B 0xAD 0x1D400 0x1F600 0xDF 0x1C5]
        names    (repeatedly 20000 #(let [n (inc (.nextInt rnd 14))]
                                      (String. (int-array (repeatedly n (fn [] (alphabet (.nextInt rnd (count alphabet))))))
                                               0 n)))]
    (is (= [] (->> names
                   (remove #(clojure.string/blank? (java-slug %)))
                   (remove #(= (java-slug %) (config/slug %)))
                   (take 5)
                   vec)))))
