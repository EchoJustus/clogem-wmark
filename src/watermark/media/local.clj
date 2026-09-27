;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.media.local
  "MediaIO over the local file system (desktop editions)."
  (:require [clojure.string :as str]
            [watermark.media :as media]
            [watermark.util.fs :as fs])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn output-path
  "<output dir or the input's dir>/<input stem><suffix>.<container>"
  [input {:keys [dir suffix container] :or {suffix "_wm" container "mp4"}}]
  (let [f    (.getAbsoluteFile (File. (str input)))
        stem (str/replace (.getName f) #"\.[^.]+$" "")]
    (str (File. (str (or dir (.getParent f))) (str stem suffix "." container)))))

(defn part-path
  "Temp name the engine writes to; renamed on success. Keeps the extension so
  muxers can still infer the format from it."
  [output]
  (str/replace output #"(\.[^.\\/]+)$" ".part$1"))

(defrecord LocalMedia []
  media/MediaIO
  (open-input [_ _ input]
    (let [f (.getAbsoluteFile (File. (str input)))]
      (when-not (.isFile f)
        (throw (ex-info (str "No such file: " input) {:wmark/error :invalid :path (str input)})))
      {:id (str input) :location (str f) :fingerprint (fs/fingerprint f)}))

  (open-output [_ ctx input {:keys [output]}]
    (let [^String final (output-path input output)]
      (when (and (not (:overwrite? output)) (.exists (File. final)))
        (throw (ex-info (str "Output exists: " final " (enable overwrite to replace it)")
                        {:wmark/error :conflict :path final})))
      (when-not (:dry-run? ctx)                     ; a dry run creates nothing
        (fs/mkdirs! (.getParent (File. final))))
      {:final final :temp (part-path final) :container (:container output "mp4")
       :overwrite? (boolean (:overwrite? output))}))

  (commit! [_ _ {:keys [final temp overwrite?]}]
    (fs/publish! temp final overwrite?)
    final)

  (discard! [_ _ {:keys [temp]}]
    (fs/delete-quietly! temp)))

(defn local-media [] (->LocalMedia))
