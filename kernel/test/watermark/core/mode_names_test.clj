;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.mode-names-test
  "The canary mode: users see and type \"canary\"; the wire id stays
  `subliminal`, because it is part of every keyed seed (docs/adr/0005)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.modes :as modes]
            [watermark.core.schema :as schema]
            [watermark.core.seeds :as seeds]
            [watermark.render :as render])
  (:import (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(deftest names-resolve-to-wire-ids
  (is (= :subliminal
         (features/canonical-mode :canary)
         (features/canonical-mode "canary")
         (features/canonical-mode :subliminal)))
  (is (= :continuous (features/canonical-mode "continuous")) "other modes pass through")
  (is (= "canary" (features/mode-display-name :subliminal) (features/mode-display-name "canary")))
  (is (= "random" (features/mode-display-name :random)))
  (is (= {:subliminal "canary"} features/mode-display-names) "the only mode shown differently")
  (is (= #{:text.mode/subliminal} (features/required-features {:texts [{:mode :canary :content "x"}]}))
      "the alias is gated like the mode it names"))

(deftest settings-take-the-alias-and-keep-the-wire-id
  (testing "JSON (REST, the web editor) and EDN (the CLI, profiles) alike"
    (is (= :subliminal (get-in (schema/validate! (schema/decode-json {:texts [{:mode "canary" :content "x"}]}))
                               [:texts 0 :mode])))
    (is (= :subliminal (get-in (schema/validate! {:texts [{:mode :canary :content "x" :every-s 4.0}]})
                               [:texts 0 :mode]))))
  (testing "users read the display name"
    (is (= :canary (get-in (features/display-settings {:texts [{:mode :subliminal :content "x"}]})
                           [:texts 0 :mode])))
    (is (= :continuous (get-in (features/display-settings {:texts [{:mode :continuous :content "x"}]})
                               [:texts 0 :mode])))
    (is (= {:logo {:opacity 0.5}} (features/display-settings {:logo {:opacity 0.5}}))))
  (testing "schema-driven UIs title the canary branch with its display name"
    (is (= ["canary"] (keep :title (get-in (schema/json-schema) [:properties :texts :items :oneOf]))))))

(def golden-seed
  "The seed kernel/test/golden/seeds.edn pins for this canary layer."
  (get-in (edn/read-string (slurp "kernel/test/golden/seeds.edn"))
          [:seeds "wmark/v1|56f87b09|0|subliminal|(c) Studio"]))

(defn- planned
  "What the planner hands each text mode: the layer's mode and its keyed seed.
  A mode's schedule is a function of exactly these (and the layer's own
  parameters), so equal inputs here mean an equal schedule."
  [settings]
  (with-redefs [modes/layer-spec (fn [ctx layer] {:mode (:mode layer) :seed (:seed ctx)})]
    (:layers (render/build {:settings settings
                            :media    {:width 1920 :height 1080 :fps-num 25 :fps-den 1 :frames 250}
                            :seed-fn  (seeds/seed-fn (byte-array (range 32)) "56f87b09")}))))

(deftest the-alias-leaves-every-schedule-unchanged
  (let [layer {:content "(c) Studio" :every-s 4.0}]
    (is (some? golden-seed))
    (is (= [{:mode :subliminal :seed golden-seed}]
           (planned {:texts [(assoc layer :mode :subliminal)]})
           (planned {:texts [(assoc layer :mode :canary)]})
           (planned (schema/validate! (schema/decode-json {:texts [(assoc layer :mode "canary")]}))))
        "whatever users call it, the canary layer gets the golden seed")))

(deftest the-community-error-names-the-canary
  (let [e (try (modes/layer-spec {} {:mode :canary :content "x"}) nil
               (catch ExceptionInfo e e))]
    (is (= "The \"canary\" text mode is part of wmark Pro." (ex-message e)))
    (is (= :subliminal (:mode (ex-data e))) "data keeps the wire id")))
