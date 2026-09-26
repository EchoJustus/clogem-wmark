;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.config-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [watermark.config :as config]
            [watermark.store-contract :as contract]
            [watermark.store.memory :as memory])
  (:import (java.nio.file Files LinkOption Path)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *store* nil)
(def ^:dynamic ^Path *home* nil)

(defn- delete-tree! [^Path root]
  (doseq [^java.io.File f (reverse (file-seq (.toFile root)))] (.delete f)))

(use-fixtures :each
  (fn [t]
    (let [home (Files/createTempDirectory "wmark-test" (make-array FileAttribute 0))]
      (try
        (binding [*home* home
                  *store* (config/file-store {:home (str home)})]
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
