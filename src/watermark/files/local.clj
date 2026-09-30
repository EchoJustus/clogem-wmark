;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.files.local
  "The files port on the local file system (java.nio)."
  (:require [watermark.files :as files])
  (:import (java.nio.file Files LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-links (make-array LinkOption 0))

(defn- path ^Path [p] (Paths/get (str p) (make-array String 0)))

(defrecord LocalFiles []
  files/Files
  (file? [_ p] (Files/isRegularFile (path p) no-links))
  (make-dirs! [_ p] (Files/createDirectories (path p) (make-array FileAttribute 0)) nil)
  (list-files [_ dir]
    (let [d (path dir)]
      (if (Files/isDirectory d no-links)
        (with-open [s (Files/newDirectoryStream d)]
          (vec (for [^Path f s :when (Files/isRegularFile f no-links)]
                 {:path        (str f)
                  :name        (str (.getFileName f))
                  :modified-ms (.toMillis (Files/getLastModifiedTime f no-links))})))
        [])))
  (delete! [_ p] (Files/deleteIfExists (path p))))

(defn local-files [] (->LocalFiles))
