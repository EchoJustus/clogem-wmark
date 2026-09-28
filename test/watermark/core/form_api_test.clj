;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.form-api-test
  "The settings form and the preview through the Core API, on real adapters
  (a file store, local media, the FFmpeg engine). The preview part is
  skipped without ffmpeg."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.config :as config]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.engine :as engine]
            [watermark.engine.conformance :as c]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.media.local :as local-media]
            [watermark.raster.local :as raster-local]))

(set! *warn-on-reflection* true)

(def ctx {:tenant "local" :user "local"})

(defn- sys []
  (let [home  (c/tmp-dir)
        store (config/file-store {:home home})]
    {:home         home
     :profiles-for (constantly store)
     :entitlements (features/community)
     :engine       (ffmpeg/ffmpeg-engine {:work-root (str home "/work")})
     :media        (local-media/local-media)
     :rasterizer   (raster-local/local-rasterizer {:work-root (str home "/work")})
     :secret-for   (constantly (byte-array 32))
     :font         (delay (c/font))
     :preview-dir  (str home "/work/previews")}))

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

(deftest a-preview-before-any-video-is-chosen
  (if-not (c/ffmpeg-available?)
    (println "  (skipped: ffmpeg not installed)")
    (let [s (sys)]
      (api/create-profile! s ctx "Demo" {:logo {:enabled false}
                                         :texts [{:mode :continuous :content "© Studio" :opacity 1.0}
                                                 {:mode :random :content "Pro only"}]})
      (let [p (api/preview-frame s ctx {:profile "demo" :t 2.0 :aspect "9:16"})
            f (api/preview-file s ctx (:id p))]
        (is (= {:frame 50 :t 2.0 :width 720 :height 1280 :sample? true :aspect "9:16"}
               (select-keys p [:frame :t :width :height :sample? :aspect])))
        (is (.isFile ^java.io.File f))
        (is (= [:image 720 1280] ((juxt :kind :width :height) (engine/probe (:engine s) (str f))))
            "a still of the sample clip's size")
        (is (some #(str/includes? % "Randomized text") (:notes p)) "Pro layers are named, not drawn"))
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
