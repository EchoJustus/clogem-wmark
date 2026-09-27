;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.web.sse-test
  "watermark.web.sse against the official Datastar SDK test cases (vendored
  in web/test/datastar-sdk-cases), compared the way the SDK suite compares
  them: per event, fields by name, data lines grouped by their first word
  (order kept within a group)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.web.sse :as sse])
  (:import (java.io ByteArrayInputStream File)))

(set! *warn-on-reflection* true)

(def ^:private cases-dir (io/file (io/resource "datastar-sdk-cases")))   ; web/test is on the classpath

(defn- render [{:strs [type elements selector mode useViewTransition namespace eventId retryDuration
                       signals signals-raw onlyIfMissing] :as ev}]
  (let [opts {:id eventId :retry retryDuration}]
    (case type
      "patchElements" (sse/patch-elements elements (assoc opts :selector selector :mode mode
                                                          :use-view-transition useViewTransition
                                                          :namespace namespace))
      "patchSignals"  (sse/patch-signals (or signals-raw signals) (assoc opts :only-if-missing onlyIfMissing))
      (throw (ex-info (str "unsupported case event " type) ev)))))

(defn- parse
  "Events as {field lines}, with data lines grouped by their first word."
  [text]
  (for [block (str/split (str/trim-newline text) #"\n\n+")
        :when (not (str/blank? block))]
    (let [lines (str/split-lines block)
          data  (for [l lines :when (str/starts-with? l "data: ")] (subs l 6))]
      (-> (group-by #(first (str/split % #":" 2)) (remove #(str/starts-with? % "data: ") lines))
          (assoc :data (group-by #(first (str/split % #" " 2)) data))))))

(deftest official-sdk-cases
  (let [cases (->> (.listFiles ^File cases-dir) (filter #(.isDirectory ^File %)) (sort-by #(.getName ^File %)))]
    (is (= 15 (count cases)) "every vendored case runs")
    (doseq [^File c cases]
      (testing (.getName c)
        (let [input    (json/read-str (slurp (io/file c "input.json")))
              expected (slurp (io/file c "output.txt"))
              actual   (apply str (map render (get input "events")))]
          (is (= (parse expected) (parse actual)))
          (is (str/ends-with? actual "\n\n") "every event ends with a blank line"))))))

(deftest signals-are-json-not-javascript
  (is (= "event: datastar-patch-signals\ndata: signals {\"settings\":\"</script>\\n'\\\"\"}\n\n"
         (sse/patch-signals {:settings "</script>\n'\""}))
      "user text travels as a JSON string, which the browser reads with JSON.parse"))

(deftest reading-signals
  (is (= {"a" 1} (sse/read-signals {:request-method :post :body (ByteArrayInputStream. (.getBytes "{\"a\":1}" "UTF-8"))})))
  (is (= {"q" "x y"} (sse/read-signals {:request-method :get :query-string "datastar=%7B%22q%22%3A%22x%20y%22%7D"})))
  (is (= {} (sse/read-signals {:request-method :delete :query-string "other=1"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sse/read-signals {:request-method :post :body (ByteArrayInputStream. (.getBytes "{oops" "UTF-8"))}))))
