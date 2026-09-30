;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.form-api-test
  "The settings form and the preview through the Core API, on real adapters
  (a file store, local media, the FFmpeg engine). The preview part is
  skipped without ffmpeg."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.home :as home]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.files.local :as local-files]
            [watermark.media.local :as local-media]
            [watermark.raster.local :as raster-local]
            [watermark.server.routes :as routes]))

(set! *warn-on-reflection* true)

(def ctx {:tenant "local" :user "local"})

(defn- sys []
  (let [home  (c/tmp-dir)
        store (home/file-store {:home home})]
    {:home         home
     :profiles-for (constantly store)
     :entitlements (features/community)
     :engine       (ffmpeg/ffmpeg-engine {:work-root (str home "/work")})
     :media        (local-media/local-media)
     :rasterizer   (raster-local/local-rasterizer {:work-root (str home "/work")})
     :secret-for   (constantly (byte-array 32))
     :font         (delay (c/font))
     :preview-dir  (str home "/work/previews")
     :files        (local-files/local-files)}))

(defn- row [f id] (some #(when (= id (:id %)) %) (mapcat :rows (get-in f [:form :categories]))))

(deftest one-setting-at-a-time-with-the-revision-read
  (let [s   (sys)
        doc (api/create-profile! s ctx "Social 9:16" {})
        f0  (api/settings-form s ctx "social-9-16")]
    (is (= {:profile/name "Social 9:16" :profile/slug "social-9-16" :profile/rev (:profile/rev doc)}
           (select-keys (:profile f0) [:profile/name :profile/slug :profile/rev])))
    (is (= :default (:source (row f0 "logo.opacity"))))
    (let [saved (api/edit-profile! s ctx "social-9-16" {:op :set :id "logo.opacity" :value "70"
                                                        :if-rev (:profile/rev doc)})
          f1    (api/settings-form s ctx "social-9-16")]
      (is (= {:logo {:opacity 0.7}} (:settings saved)))
      (is (= [:set-here "70%"] ((juxt :source :text) (row f1 "logo.opacity"))))
      (testing "an edit made against an older revision is refused"
        (let [e (try (api/edit-profile! s ctx "social-9-16" {:op :set :id "logo.anchor" :value "top-left"
                                                             :if-rev (:profile/rev doc)})
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= [:conflict "stale"] [(:wmark/error (ex-data e)) (name (:reason (ex-data e) ""))]))))
      (testing "reset brings the default back"
        (api/edit-profile! s ctx "social-9-16" {:op :unset :id "logo.opacity"})
        (is (= :default (:source (row (api/settings-form s ctx "social-9-16") "logo.opacity"))))))
    (testing "text layers, Pro kinds locked on Community"
      (api/edit-profile! s ctx "social-9-16" {:op :add-layer :mode "subliminal"})
      (let [f (api/settings-form s ctx "social-9-16")]
        (is (= [:text.mode/subliminal] (:locked f)))
        (is (= [{:title "Text layer 1" :subtitle "Canary" :tier "pro" :locked true}]
               (map #(select-keys % [:title :subtitle :tier :locked]) (get-in f [:form :texts :layers]))))))))

(deftest a-draft-is-edited-without-saving
  (let [s   (sys)
        doc (api/create-profile! s ctx "Draft me" {:logo {:opacity 0.7}})
        d0  (api/draft-form s ctx "draft-me" {:settings (:settings doc)})]
    (is (false? (:unsaved? d0)) "a draft that matches the profile is not unsaved")
    (is (= [:set-here "70%"] ((juxt :source :text) (row d0 "logo.opacity"))))
    (let [d1 (api/draft-form s ctx "draft-me" {:settings (:settings d0)
                                               :edit {:op :set :id "logo.opacity" :value "40"}})]
      (testing "an edit changes the draft, not the profile"
        (is (= {:logo {:opacity 0.4}} (:settings d1)))
        (is (true? (:unsaved? d1)))
        (is (= [:unsaved "40%"] ((juxt :source :text) (row d1 "logo.opacity"))))
        (is (= [(:profile/rev doc) {:logo {:opacity 0.7}}]
               ((juxt :profile/rev :settings) (api/get-profile s ctx "draft-me")))))
      (testing "a reset goes to the built-in default, not to the saved value, and is unsaved"
        (let [d2 (api/draft-form s ctx "draft-me" {:settings (:settings d1) :edit {:op :unset :id "logo.opacity"}})]
          (is (= {} (:settings d2)))
          (is (= :unsaved (:source (row d2 "logo.opacity"))))
          (is (not= "70%" (:text (row d2 "logo.opacity"))))))
      (testing "a text layer added to the draft; Pro kinds are locked on Community"
        (let [d3 (api/draft-form s ctx "draft-me" {:settings (:settings d1) :edit {:op :add-layer :mode "subliminal"}})]
          (is (= [:subliminal] (map :mode (:texts (:settings d3)))) "stored by wire id")
          (is (= [:text.mode/subliminal] (:locked d3)))
          (is (= :unsaved (get-in d3 [:form :texts :source])))))
      (testing "a bad value is refused and names the field"
        (let [e (try (api/draft-form s ctx "draft-me" {:settings (:settings d1)
                                                       :edit {:op :set :id "logo.opacity" :value "150"}})
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= :invalid (:wmark/error (ex-data e))))))
      (testing "saved with the revision read, the draft is no longer unsaved"
        (api/save-profile! s ctx "draft-me" (:settings d1) {:if-rev (:profile/rev doc)})
        (let [d (api/draft-form s ctx "draft-me" {:settings (:settings d1)})]
          (is (false? (:unsaved? d)))
          (is (= :set-here (:source (row d "logo.opacity")))))))))

(deftest a-preview-before-any-video-is-chosen
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [s (sys)]
      (api/create-profile! s ctx "Demo" {:logo {:enabled false}
                                         :texts [{:mode :continuous :content "© Studio" :opacity 1.0}
                                                 {:mode :random :content "Pro only"}]})
      (let [p (api/await (api/preview-frame s ctx {:profile "demo" :t 2.0 :aspect "9:16"}))
            f (api/preview-file s ctx (:id p))]
        (is (= {:frame 50 :t 2.0 :width 720 :height 1280 :sample? true :aspect "9:16"}
               (select-keys p [:frame :t :width :height :sample? :aspect])))
        (is (.isFile (java.io.File. ^String f)) "preview-file is the PNG's path")
        (is (= [:image 720 1280] ((juxt :kind :width :height) (engine/probe (:engine s) (str f))))
            "a still of the sample clip's size")
        (is (some #(str/includes? % "Randomized text") (:notes p)) "Pro layers are named, not drawn"))
      (testing "the other sample shapes"
        (is (= [864 1080 "4:5"] ((juxt :width :height :aspect) (api/await (api/preview-frame s ctx {:profile "demo" :aspect "4:5"})))))
        (is (= [1680 720 "21:9"] ((juxt :width :height :aspect) (api/await (api/preview-frame s ctx {:profile "demo" :aspect "21:9"}))))))
      (testing "unknown or malformed ids are not files"
        (doseq [id ["nope" "../secret" (str (java.util.UUID/randomUUID))]]
          (is (= :not-found (try (api/preview-file s ctx id) nil
                                 (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e)))))))))))

(deftest a-preview-needs-an-engine-that-can-make-one
  (let [s (assoc (sys) :engine (reify engine/VideoEngine
                                 (info [_] {:engine/id :test :available? true :capabilities {}})
                                 (probe [_ _] nil) (prepare [_ _] nil) (execute! [_ _ _] nil)))]
    (api/create-profile! s ctx "P" {})
    (is (= :unsupported (try (api/preview-frame s ctx {:profile "p"}) nil
                             (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e))))))
    (is (= :unsupported (try (api/preview-frame (dissoc s :preview-dir) ctx {:profile "p"}) nil
                             (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e))))))
    (is (not (.exists (io/file (:home s) "work" "previews" "x.png"))))))

(defn- rest!
  "One REST call through the route table, as a GUI app makes it."
  [s method uri body]
  (let [resp ((routes/api-handler s) {:request-method method :uri uri
                                       :body (when body (java.io.ByteArrayInputStream. (.getBytes ^String (json/write-str body) "UTF-8")))})]
    [(:status resp) (if (string? (:body resp)) (json/read-str (:body resp) :key-fn keyword) (:body resp))]))

(deftest the-rest-routes-a-gui-app-uses
  (let [s (sys)
        [_ doc] (rest! s :post "/api/v1/profiles" {:name "GUI" :settings {}})
        rev     (:profile/rev doc)
        [st f]  (rest! s :get "/api/v1/profiles/gui/form" nil)]
    (is (= 200 st))
    (is (= ["Logo" "Text layers" "Output" "Encoding"] (map :title (get-in f [:form :categories]))))
    (let [[st saved] (rest! s :post "/api/v1/profiles/gui/edit" {:op "set" :id "logo.opacity" :value "70" :if-rev rev})]
      (is (= 200 st))
      (is (= ["70%" "set-here"] ((juxt :text :source) (row saved "logo.opacity")))))
    (testing "an edit against the revision read before is refused, not saved over"
      (let [[st e] (rest! s :post "/api/v1/profiles/gui/edit" {:op "unset" :id "logo.opacity" :if-rev rev})]
        (is (= [409 "stale"] [st (:reason e)]))
        (is (= "70%" (:text (row (second (rest! s :get "/api/v1/profiles/gui/form" nil)) "logo.opacity"))))))
    (testing "a bad value is a 422 that names the field"
      (let [[st e] (rest! s :post "/api/v1/profiles/gui/edit" {:op "set" :id "logo.opacity" :value "150"})]
        (is (= 422 st))
        (is (re-find #"Opacity: at most 100" (:message e)))))
    (testing "a draft's form, after one edit, saves nothing"
      (let [[_ saved] (rest! s :get "/api/v1/profiles/gui" nil)
            [st d]    (rest! s :post "/api/v1/profiles/gui/form"
                             {:settings (:settings saved) :edit {:op "set" :id "logo.anchor" :value "top-left"}})]
        (is (= 200 st))
        (is (true? (:unsaved? d)))
        (is (= {:logo {:opacity 0.7 :anchor "top-left"}} (:settings d)))
        (is (= ["Top left" "unsaved"] ((juxt :text :source) (row d "logo.anchor"))))
        (is (= (:profile/rev saved) (:profile/rev (second (rest! s :get "/api/v1/profiles/gui" nil)))))
        (let [[st e] (rest! s :post "/api/v1/profiles/gui/form"
                            {:settings (:settings d) :edit {:op "set" :id "logo.opacity" :value "150"}})]
          (is (= 422 st))
          (is (re-find #"Opacity: at most 100" (:message e))))))))
