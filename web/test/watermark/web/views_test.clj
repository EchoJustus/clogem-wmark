;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.views-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.web.html :as h]
            [watermark.web.views :as v]))

(set! *warn-on-reflection* true)

(def hostile "x')\" data-on:click=\"@post('/ui/stream')\" <script>alert(1)</script>")

(deftest user-text-never-becomes-code
  (let [html (h/html [:div (v/profile-list [{:name hostile :slug "x-data-on-click" :auto? false}] nil)
                      (v/inspector-head {:profile/name hostile :profile/slug "x-data-on-click" :settings {}})
                      (v/editor {:profile/slug "x" :settings {:texts [{:mode :continuous :content hostile}]}})
                      (v/job-list [{:id "9b1d" :inputs [hostile] :state :failed :created-at "t"
                                    :error {:message hostile}}])])]
    (is (not (str/includes? html "<script>")))
    (is (not (re-find #"data-on:click=\"@post\('/ui/stream'\)\"" html))
        "the smuggled attribute is escaped text, not an attribute")
    (is (str/includes? html "&lt;script&gt;"))))

(deftest urls-inside-expressions-are-inert
  (testing "slugs are letters of any script, digits and hyphens; encoding makes them ASCII"
    (is (= "%E6%A8%AA%E5%B1%8F-16-9" (v/path-segment "横屏-16-9")))
    (is (re-matches #"[A-Za-z0-9.*_%-]*" (v/path-segment "a'b\"c\\d </script> e+f"))))
  (is (str/includes? (h/html (v/inspector-head {:profile/name "N" :profile/slug "横屏"}))
                     "@put(&#39;/ui/profiles/%E6%A8%AA%E5%B1%8F&#39;)")
      "(quotes are escaped in the attribute; the browser decodes them before Datastar reads it)"))

(deftest effective-shows-where-each-value-came-from
  (let [r    {:settings   {:logo {:anchor :top-left :opacity 0.5} :texts []}
              :provenance {[:logo :anchor] :profile [:logo :opacity] :overrides [:texts] :defaults}
              :base       {:kind :latest}
              :locked     [:text.mode/subliminal]}
        rows (->> (h/html (v/effective r {:text.mode/subliminal "Flash-frame canaries"} false))
                  (re-seq #"<tr[^>]*><td>([^<]*)</td><td>([^<]*)</td><td>([^<]*)</td></tr>")
                  (map (fn [[_ path value from]] [(str/replace path "​" "") value from])))]
    (is (= [["logo.anchor" "top-left" "last run"] ["logo.opacity" "0.5" "override"] ["texts" "[]" "default"]] rows))
    (is (str/includes? (h/html (v/effective r {:text.mode/subliminal "Flash-frame canaries"} false))
                       "Needs wmark Pro to run: Flash-frame canaries."))
    (is (str/includes? (h/html (v/effective r {} true)) "this profile (unsaved)") "preview wording")))

(deftest only-numbers-in-data-signals
  (let [page (h/html (v/page {:nonce "n0nce" :health {:edition :community :version "1" :engine {:available? true :engine/id :ffmpeg}}
                              :profiles [{:name hostile :slug "x" :auto? false}]
                              :doc {:profile/name hostile :profile/slug "x" :profile/rev 7 :settings {:texts [{:content hostile}]}}
                              :resolved nil :titles {} :jobs []}))]
    (is (= ["{&quot;rev&quot;:7}"] (map second (re-seq #"data-signals=\"([^\"]*)\"" page))))
    (is (str/includes? page "data-nonce=\"n0nce\""))
    (is (= 1 (count (re-seq #"<script" page))) "one script: datastar.js")))
