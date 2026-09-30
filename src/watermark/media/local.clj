;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.media.local
  "MediaIO over the local file system (desktop editions)."
  (:require [watermark.media :as media]
            [watermark.util.fs :as fs])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(defn output-path
  "<output dir or the input's dir>/<input stem><suffix>.<container>"
  [input {:keys [dir] :as output}]
  (let [f (.getAbsoluteFile (File. (str input)))]
    (str (File. (str (or dir (.getParent f))) ^String (media/output-name (.getName f) output)))))

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
      {:final final :temp (media/part-path final) :container (:container output "mp4")
       :overwrite? (boolean (:overwrite? output))}))

  (commit! [_ _ {:keys [final temp overwrite?]}]
    (when-not (.isFile (File. (str temp)))
      (throw (ex-info (str "The engine reported success but wrote no output (" temp ").")
                      {:wmark/error :failed :path (str temp)})))
    (fs/publish! temp final overwrite?)
    final)

  (discard! [_ _ {:keys [temp]}]
    (fs/delete-quietly! temp)))

(defn local-media [] (->LocalMedia))
