;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.form-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.form :as form]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The catalog agrees with the schema

(defn- props [form] (when (map? (second form)) (second form)))

(defn- leaf
  "A schema leaf as the catalog describes one."
  [s]
  (let [t (if (vector? s) (first s) s)
        p (when (vector? s) (props s))]
    (case t
      :boolean {:kind #{:boolean}}
      :int     {:kind #{:integer} :min (:min p) :max (:max p)}
      :double  {:kind #{:number} :min (:min p) :max (:max p)}
      :string  {:kind #{:text :file :folder} :max (:max p)}
      :enum    {:kind #{:enum} :options (vec (if p (drop 2 s) (rest s)))}
      :re      {:kind #{:color}}
      :=       {:kind #{:enum}}
      :vector  {:kind #{:seconds-list} :min (:min (props (last s))) :max (:max (props (last s)))})))

(defn- entries [m] (drop (if (props m) 2 1) m))

(defn- leaves
  "{path leaf} for every setting outside :texts."
  ([s] (leaves [] s))
  ([prefix s]
   (if (and (vector? s) (= :map (first s)))
     (into {} (mapcat (fn [e] (let [[k & more] e] (leaves (conj prefix k) (last more)))) (entries s)))
     {prefix (leaf s)})))

(defn- layer-leaves
  "{mode {path leaf}} for the text layer kinds."
  []
  (into {} (for [[mode layer] (entries schema/TextLayer)]
             [mode (leaves layer)])))

(defn- same? [field l]
  (and (contains? (:kind l) (:kind field))
       (every? (fn [k] (= (get l k) (get field k))) (keep #(when (contains? l %) %) [:min :max]))
       (or (not (:options l)) (= (:options l) (:options field)))))

(deftest every-setting-has-a-matching-row
  (let [schema-leaves (dissoc (leaves schema/Settings) [:texts])]
    (doseq [[path l] schema-leaves]
      (let [field (form/field-of path)]
        (is (some? field) (str (form/path-id path) " has no row in the form"))
        (when field (is (same? field l) (str (form/path-id path) " differs from the schema: " field " vs " l)))))
    (is (= (set (keys schema-leaves)) (set (map :path form/fields)))
        "and the form shows nothing the schema doesn't have")))

(deftest every-layer-field-matches-each-kind
  (let [by-mode (layer-leaves)]
    (is (= (set form/layer-modes) (set (keys by-mode))))
    (doseq [[mode ls] by-mode
            [path l] ls
            :when (not (form/hidden (into [:texts] path)))]
      (let [f (some #(when (= path (:path %)) %) form/layer-fields)]
        (is (some? f) (str (name mode) " " path " has no row"))
        (when f
          (is (or (nil? (:modes f)) (contains? (:modes f) mode)) (str path " is missing from " mode))
          (when-not (= [:mode] path)
            (is (same? f l) (str path " differs from the schema: " f " vs " l))))))
    (doseq [f form/layer-fields :when (:modes f)
            m (:modes f)]
      (is (contains? (get by-mode m) (:path f)) (str (:path f) " isn't part of " m)))))

(deftest new-layers-are-valid
  (doseq [m form/layer-modes]
    (is (schema/validate! {:texts [(assoc (form/layer-required m) :mode m)]}))))

;; ---------------------------------------------------------------------------
;; The model

(defn- resolved [profile & {:keys [latest?]}]
  (let [r (resolve/layer [[:defaults schema/defaults] [:profile profile]])]
    (assoc r :base {:kind (if latest? :latest :named)})))

(defn- rows [m] (into {} (for [c (:categories m) r (:rows c)] [(:id r) r])))

(deftest rows-show-values-sources-and-choices
  (let [m    (form/model (resolved {:logo {:opacity 0.5 :anchor :top-left}}))
        by   (rows m)]
    (is (= ["Logo" "Text layers" "Output" "Encoding"] (map :title (:categories m))))
    (is (= {:value 0.5 :text "50%" :input "50" :source :set-here :min 0.0 :max 100.0 :slider true}
           (select-keys (by "logo.opacity") [:value :text :input :source :min :max :slider])))
    (is (= "Top left" (:text (by "logo.anchor"))))
    (is (= "top-left" (:input (by "logo.anchor"))))
    (is (= 9 (count (:options (by "logo.anchor")))))
    (is (= :default (:source (by "logo.width-ratio"))))
    (is (= "12%" (:text (by "logo.width-ratio"))))
    (is (= :unset (:source (by "logo.path"))) "no default and not set")
    (is (= "60 s" (:text (by "logo.animation.every-s"))))
    (is (= "On" (:text (by "output.strip-metadata"))))
    (is (= "H.264" (:text (by "encode.codec"))))
    (is (= form/source-labels (:sources m)))
    (testing "the last run's values are named as such"
      (is (= :last-run (:source ((rows (form/model (resolved {:logo {:opacity 0.5}} :latest? true))) "logo.opacity")))))))

(deftest text-layers-are-cards-with-their-kinds-fields
  (let [m (form/model (resolved {:texts [{:mode :continuous :content "(c) Studio"}
                                         {:mode :subliminal :content "Canary" :frames 2}]})
                      {:entitled? #(= :community (features/tier %))})
        [a b] (get-in m [:texts :layers])]
    (is (= ["Text layer 1" "Continuous"] [(:title a) (:subtitle a)]))
    (is (= ["Text layer 2" "Canary"] [(:title b) (:subtitle b)]) "the canary mode by its display name")
    (is (not-any? #(str/includes? (str (:subtitle %) (:title %)) "ubliminal") [a b]))
    (is (= {:tier "pro" :locked true} (select-keys b [:tier :locked])))
    (is (nil? (:locked a)))
    (is (not-any? #(= "texts.0.frames" (:id %)) (:rows a)) "fields of other kinds stay out")
    (is (some #(= "texts.1.frames" (:id %)) (:rows b)))
    (let [size (some #(when (= "texts.0.size-ratio" (:id %)) %) (:rows a))]
      (is (= [nil "3.5% (default)" "3.5"] [(:value size) (:text size) (:input size)])))
    (is (= [["continuous" nil] ["scheduled" nil] ["subliminal" true] ["random" true]]
           (map (juxt :value :locked) (get-in m [:texts :modes]))))
    (is (= "Canary" (:label (nth (get-in m [:texts :modes]) 2))))))

;; ---------------------------------------------------------------------------
;; Parsing

(deftest typed-values-become-settings
  (let [f #(form/field-of (form/parse-id %))]
    (is (= 0.7 (form/parse-input (f "logo.opacity") "70")))
    (is (= 0.7 (form/parse-input (f "logo.opacity") "70,0")) "a decimal comma is fine")
    (is (= 12 (form/parse-input (f "logo.offset.x") "12")))
    (is (= 12 (form/parse-input (f "logo.offset.x") 12.0)))
    (is (= :top-left (form/parse-input (f "logo.anchor") "top-left")))
    (is (= "mkv" (form/parse-input (f "output.container") "mkv")) "string enums stay strings")
    (is (= [5.0 12.5] (form/parse-input (f "texts.0.at") "5, 12.5")))
    (is (true? (form/parse-input (f "output.overwrite?") true)))
    (is (nil? (form/parse-input (f "output.dir") "  ")) "blank is not set")
    (doseq [[id v msg] [["logo.opacity" "150" "at most 100 %"]
                        ["logo.offset.x" "1.5" "a whole number"]
                        ["logo.anchor" "middle" "one of"]
                        ["texts.0.color" "red;" "a colour name"]
                        ["logo.opacity" "lots" "a number"]]]
      (let [e (try (form/parse-input (f id) v) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid (:wmark/error (ex-data e))) id)
        (is (str/includes? (str (ex-message e)) msg) (ex-message e))))))

(deftest ids-name-only-catalog-fields
  (is (= [:logo :offset :x] (form/parse-id "logo.offset.x")))
  (is (= [:texts 3 :content] (form/parse-id "texts.3.content")))
  (is (nil? (form/parse-id "logo.nope")))
  (is (nil? (form/parse-id "texts.x.content")))
  (is (nil? (form/parse-id "texts.01.content")))
  (is (nil? (form/parse-id "texts.0.id")) "hidden fields aren't editable here"))

;; ---------------------------------------------------------------------------
;; Edits

(defn- edit [profile e]
  (let [effective (:settings (resolved profile))]
    (schema/validate! (form/edit profile effective e))))

(deftest one-path-at-a-time
  (is (= {:logo {:opacity 0.7}} (edit {} {:op :set :id "logo.opacity" :value "70"})))
  (is (= {} (edit {:logo {:opacity 0.7}} {:op :unset :id "logo.opacity"})) "empty blocks go")
  (is (= {:logo {:animation {:type :flip-y :every-s 30.0}}}
         (edit {} {:op :set :id "logo.animation.every-s" :value "30"}))
      "an inherited block brings its required keys along")
  (is (= {:output {:dir "/out"}} (edit {} {:op :set :id "output.dir" :value " /out "})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No setting"
                        (edit {} {:op :set :id "logo.bogus" :value "1"}))))

(deftest text-layers-change-as-a-whole
  (let [p {:texts [{:mode :continuous :content "A"} {:mode :scheduled :content "B" :at [1.0] :duration-s 2.0}]}]
    (is (= "C" (get-in (edit p {:op :set :id "texts.1.content" :value "C"}) [:texts 1 :content])))
    (testing "switching kind keeps shared fields and drops the old kind's own"
      (let [l (get-in (edit (assoc-in p [:texts 1 :opacity] 0.5) {:op :set :id "texts.1.mode" :value "continuous"})
                      [:texts 1])]
        (is (= {:mode :continuous :content "B" :opacity 0.5} l))))
    (is (= [:continuous :scheduled :random]
           (mapv :mode (:texts (edit p {:op :add-layer :mode "random"})))))
    (is (= ["B"] (mapv :content (:texts (edit p {:op :remove-layer :index 0})))))
    (is (= ["B" "A"] (mapv :content (:texts (edit p {:op :move-layer :index 1 :delta -1})))))
    (is (= ["A" "B"] (mapv :content (:texts (edit p {:op :move-layer :index 1 :delta 1})))) "the end stays put")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs its text"
                          (edit p {:op :unset :id "texts.0.content"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no text layer 3"
                          (edit p {:op :set :id "texts.2.content" :value "x"})))))
