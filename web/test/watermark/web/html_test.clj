;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.html-test
  (:require [clojure.test :refer [deftest is testing]]
            [watermark.web.html :as h]))

(set! *warn-on-reflection* true)

(deftest renders-hiccup
  (is (= "<p class=\"note\">a &lt; b</p>" (h/html [:p {:class "note"} "a < b"])))
  (is (= "<input disabled data-bind:name>" (h/html [:input {:disabled true "data-bind:name" true :hidden false :x nil}])))
  (is (= "<ul><li>1</li><li>2</li></ul>" (h/html [:ul (for [i [1 2]] [:li i])])))
  (is (= "<b>ok</b>" (h/html (h/raw "<b>ok</b>"))))
  (is (= "<!doctype html><html></html>" (h/document [:html]))))

(deftest escapes-everything-it-is-given
  (testing "text and attribute values"
    (is (= "<p title=\"&quot; onmouseover=&quot;x()\">&lt;script&gt;alert(1)&lt;/script&gt;</p>"
           (h/html [:p {:title "\" onmouseover=\"x()"} "<script>alert(1)</script>"]))))
  (testing "a Datastar attribute smuggled in as text stays text"
    (is (= "<span>&lt;b data-on:click=&quot;@post(&#39;/x&#39;)&quot;&gt;</span>"
           (h/html [:span "<b data-on:click=\"@post('/x')\">"]))))
  (testing "names that could break out of a tag are refused, not escaped"
    (is (thrown? clojure.lang.ExceptionInfo (h/html [:p {"onclick=x y" "1"}])))
    (is (thrown? clojure.lang.ExceptionInfo (h/html [(keyword "p onclick=x") "t"])))))
