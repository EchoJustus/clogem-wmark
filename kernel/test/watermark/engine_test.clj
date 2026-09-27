;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine-test
  (:require [clojure.test :refer [deftest is]]
            [watermark.engine :as engine]))

(def request
  {:spec   {:layers [{:kind :image :timing {:type :always} :animation {:type :flip-y}}
                     {:kind :text :timing {:type :periodic} :placement {:type :burst-scatter}}]}
   :encode {:codec :hevc :audio :copy}
   :output {:container "mov"}})

(def full {:layers #{:image :text} :animations #{:flip-y} :timing #{:always :windows :periodic}
           :placement #{:fixed :burst-scatter :per-window} :codecs #{:h264 :hevc}
           :containers #{"mp4" "mov"} :audio #{:copy :aac :none}})

(deftest negotiation
  (is (= [] (engine/missing full request)))
  (is (= [[:layers :text] [:placement :burst-scatter] [:codecs :hevc]]
         (engine/missing (-> full (update :layers disj :text) (update :placement disj :burst-scatter)
                             (update :codecs disj :hevc))
                         request)))
  (let [e (try (engine/check! {:engine/id :tiny :capabilities (update full :layers disj :text)} request) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :unsupported (:wmark/error (ex-data e))))
    (is (= "The tiny engine can't render this: layers text." (ex-message e)))))
