;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.store-contract
  "Behaviour every ProfileStore must show through watermark.config.

  Run against the file store, the memory store and the PostgreSQL store: if
  all pass, config and the Core API need no change to switch backends."
  (:require [clojure.test :refer [is testing]]
            [watermark.config :as config]))

(defn- kind [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e)))))

(def defaults
  {:logo  {:enabled true :anchor :bottom-right :offset {:x 24 :y 24} :opacity 0.85}
   :texts []})

(defn crud [store]
  (testing "create, read, alias rules, rename, delete"
    (let [p (config/create-profile! store "16:9 Video Profile" {:logo {:anchor :top-left :opacity nil}})]
      (is (= "16-9-video-profile" (:profile/slug p)))
      (is (= 1 (:profile/rev p)))
      (is (= {:logo {:anchor :top-left}} (:settings p)) "nil values are not persisted"))
    (is (= "16:9 Video Profile" (:profile/name (config/get-profile store "16-9 video profile")))
        "lookup is case- and punctuation-insensitive")
    (let [before (config/get-profile! store "16:9 Video Profile")
          after  (config/save-profile! store "16:9 VIDEO PROFILE" {:logo {:anchor :center}})]
      (is (= :center (get-in after [:settings :logo :anchor])) "same name, other case: replace")
      (is (= (:profile/created-at before) (:profile/created-at after)))
      (is (= 2 (:profile/rev after))))
    (let [by-slug (config/save-profile! store "16-9-video-profile" {:logo {:anchor :top-right}})]
      (is (= "16:9 VIDEO PROFILE" (:profile/name by-slug)) "addressing by slug never renames")
      (is (= 3 (:profile/rev by-slug))))
    (is (= :conflict (kind #(config/save-profile! store "16 9 video profile!" {})))
        "a different name aliasing the same slug is a conflict")
    (is (= "16 9 video profile!"
           (:profile/name (config/save-profile! store "16 9 video profile!" {} {:overwrite? true}))))
    (is (= :conflict (kind #(config/create-profile! store "16:9 video profile" {}))) "create never replaces")
    (config/rename-profile! store "16 9 video profile!" "Vertical 9:16")
    (is (nil? (config/get-profile store "16:9 Video Profile")))
    (is (some? (config/get-profile store "vertical 9:16")))
    (config/rename-profile! store "vertical 9:16" "VERTICAL 9:16")
    (is (= ["VERTICAL 9:16"] (map :name (config/list-profiles store))) "case-only rename keeps one entry")
    (is (= :conflict (kind #(do (config/create-profile! store "Other" {})
                                (config/rename-profile! store "Other" "vertical 9:16")))))
    (is (true? (config/delete-profile! store "vertical 9:16")))
    (is (= :not-found (kind #(config/delete-profile! store "vertical 9:16"))))
    (config/delete-profile! store "Other")))

(defn latest [store]
  (testing "latest is reserved, auto-saved, and never keeps transient keys"
    (is (= :invalid (kind #(config/create-profile! store "Latest" {}))))
    (config/record-latest! store {:logo {:anchor :top-right} :inputs ["a.mp4"] :job {:seed 42}}
                           "16:9 Video Profile")
    (let [l (config/get-profile! store "latest")]
      (is (= {:logo {:anchor :top-right}} (:settings l)))
      (is (true? (:profile/auto? l)))
      (is (= "16:9 Video Profile" (:profile/derived-from l))))
    (is (= :invalid (kind #(config/rename-profile! store "latest" "x"))))
    (config/copy-profile! store "latest" "Promoted")
    (is (= :top-right (get-in (config/get-profile! store "promoted") [:settings :logo :anchor])))
    (is (= ["latest" "Promoted"] (map :name (config/list-profiles store))) "latest sorts first")
    (config/delete-profile! store "Promoted")
    (config/delete-profile! store "latest")))

(defn fallback [store]
  (testing "resolution: defaults < base profile < overrides"
    (let [r (config/resolve-settings store {:defaults defaults :overrides {:logo {:opacity 0.5}}})]
      (is (= {:kind :none} (:base r)))
      (is (= :overrides (get-in r [:provenance [:logo :opacity]]))))
    (config/record-latest! store {:logo {:anchor :top-left :offset {:x 8}}
                                  :texts [{:mode :continuous :content "(c) Studio"}]})
    (let [r (config/resolve-settings store {:defaults defaults
                                            :overrides {:logo {:offset {:y 99} :anchor nil}}})]
      (is (= :latest (get-in r [:base :kind])))
      (is (= {:x 8 :y 99} (get-in r [:settings :logo :offset])))
      (is (= :top-left (get-in r [:settings :logo :anchor])))
      (is (= :profile (get-in r [:provenance [:logo :anchor]]))))
    (config/create-profile! store "Square" {:logo {:anchor :center}})
    (let [r (config/resolve-settings store {:profile "square" :defaults defaults})]
      (is (= {:kind :named :name "Square"} (:base r)))
      (is (= {:x 24 :y 24} (get-in r [:settings :logo :offset])) "latest is not consulted"))
    (is (= :not-found (kind #(config/resolve-settings store {:profile "nope"})))
        "an unknown explicit profile is an error, never a silent fallback")
    (is (= :bottom-right (get-in (config/resolve-settings store {:profile :none :defaults defaults})
                                 [:settings :logo :anchor])))
    (config/delete-profile! store "Square")
    (config/delete-profile! store "latest")))

(defn revisions [store]
  (testing "optimistic concurrency"
    (let [p (config/create-profile! store "Shared" {:logo {:opacity 0.5}})]
      (is (= :conflict (kind #(config/save-profile! store "Shared" {} {:if-rev 99})))
          "saving over someone else's newer revision fails")
      (is (= 2 (:profile/rev (config/save-profile! store "Shared" {:logo {:opacity 0.6}}
                                                   {:if-rev (:profile/rev p)})))))
    (let [results (doall (pmap (fn [i] (try (config/save-profile! store "Shared" {:logo {:opacity (/ i 10.0)}}
                                                                  {:if-rev 2})
                                            :won
                                            (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e)))))
                               (range 8)))]
      (is (= 1 (count (filter #{:won} results))) "exactly one of eight racing editors wins")
      (is (every? #{:won :conflict} results)))
    (config/delete-profile! store "Shared")))

(defn concurrent-latest [store]
  (testing "40 concurrent auto-saves: last writer wins, nothing torn"
    (run! deref (doall (for [i (range 40)]
                         (future (config/record-latest! store {:logo {:opacity (/ i 100.0)}})))))
    (is (number? (get-in (config/get-profile! store "latest") [:settings :logo :opacity])))
    (is (empty? (filter :error (config/list-profiles store))))
    (config/delete-profile! store "latest")))

(defn run-all [store]
  (crud store) (latest store) (fallback store) (revisions store) (concurrent-latest store)
  (is (empty? (config/list-profiles store)) "the contract cleans up after itself"))
