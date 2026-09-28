;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.form-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.core.form :as form]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.web.form :as fv]
            [watermark.web.html :as h]
            [watermark.web.views :as v]))

(set! *warn-on-reflection* true)

(def hostile "x')\" data-on:click=\"@post('/ui/stream')\" <script>alert(1)</script>")

(defn- model [profile]
  {:form (form/model (assoc (resolve/layer [[:defaults schema/defaults] [:profile profile]]) :base {:kind :named})
                     {:entitled? (constantly false)})})

(defn- attrs
  "Every data-* attribute value in `html`, unescaped as the browser sees it."
  [html]
  (->> (re-seq #" (data-[^=\s>]+)=\"([^\"]*)\"" html)
       (map (fn [[_ k v]] [k (-> v (str/replace "&#39;" "'") (str/replace "&quot;" "\"") (str/replace "&amp;" "&"))]))))

(deftest user-text-in-the-form-stays-text
  (let [m (model {:texts [{:mode :continuous :content hostile}] :output {:dir hostile}})]
    (testing "values: escaped text, never inside an expression"
      (let [html (h/html (fv/settings-form "demo" m))]
        (is (not (str/includes? html "<script>")))
        (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;") "the text layer's content, escaped")
        (doseq [[k v] (attrs html)]
          (is (not (str/includes? v "alert")) (str k " carries user text: " v)))))
    (testing "a hostile slug appears in expressions only URL-encoded"
      (let [html (h/html (fv/settings-form hostile m))
            enc  (v/path-segment hostile)]
        (is (re-matches #"[A-Za-z0-9.*_%-]*" enc))
        (is (some #(str/includes? (second %) enc) (attrs html)))
        (doseq [[k v] (attrs html)]
          (is (not (str/includes? v "x')\" data-on")) (str k ": " v))
          (is (not (str/includes? v "<script")) (str k ": " v)))))))

(deftest rows-read-as-views-and-controls
  (let [m    (model {:logo {:opacity 0.5}})
        row  (fv/find-row (:form m) "logo.opacity")
        view (h/html (fv/row-view "demo" row (get-in m [:form :sources])))
        edit (h/html (fv/row-edit "demo" row (get-in m [:form :sources]) "Opacity: at most 100 %"))]
    (is (= "row-logo-opacity" (fv/dom-id "logo.opacity")))
    (is (= "row-output-overwrite-" (fv/dom-id "output.overwrite?")) "ids stay valid selectors")
    (is (str/includes? view ">50%</button>"))
    (is (str/includes? view "Set here"))
    (is (str/includes? view "@delete(&#39;/ui/profiles/demo/fields/logo.opacity&#39;)") "set here: it can be reset")
    (is (str/includes? edit "type=\"number\""))
    (is (str/includes? edit "type=\"range\"") "ratios get a slider too")
    (is (str/includes? edit "data-bind:fv") "the value travels as a signal")
    (is (str/includes? edit "role=\"alert\">Opacity: at most 100 %</p>"))
    (testing "enums are selects; locked Pro kinds are visible, disabled"
      (let [mode (fv/find-row (:form (model {:texts [{:mode :continuous :content "c"}]})) "texts.0.mode")
            html (h/html (fv/row-edit "demo" mode {} nil))]
        (is (str/includes? html "<select"))
        (is (str/includes? html "<option value=\"subliminal\" disabled>Canary (Pro)</option>"))
        (is (not (str/includes? html ">Subliminal")))))
    (testing "booleans are switches that save on change"
      (let [b (fv/find-row (:form m) "output.overwrite?")]
        (is (str/includes? (h/html (fv/row-view "demo" b {})) "role=\"switch\""))))))

(deftest the-preview-panel-asks-for-frames
  (let [html (h/html (fv/preview-panel "demo"))]
    (is (str/includes? html "data-effect=\"$pv; $pa; $pt; $pu; @peek(() =&gt; @post(&#39;/ui/frame/demo&#39;))\"")
        "the request doesn't subscribe the effect to the signals it sends")
    (is (= 3 (count (re-seq #"data-on:click=\"\$pa = \d\"" html))) "16:9, 9:16 and 1:1")
    (is (nil? (re-find #"data-effect" (h/html (fv/preview-panel nil)))) "no profile, no requests"))
  (let [html (h/html (fv/preview-frame {:id "0b1d6c9e-2f7a-4c55-9a0e-1f2b3c4d5e6f" :frame 50 :t 2.0
                                        :width 720 :height 1280 :sample? true :aspect "9:16"
                                        :notes ["Not shown, because they need wmark Pro: Randomized text."]}
                                       nil))]
    (is (str/includes? html "src=\"/api/v1/previews/0b1d6c9e-2f7a-4c55-9a0e-1f2b3c4d5e6f\""))
    (is (str/includes? html "Frame 50 · 2.00 s · sample 9:16"))
    (is (str/includes? html "need wmark Pro"))))
