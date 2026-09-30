;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.files
  "The files port: the few file operations the use cases need besides media
  I/O, for the preview folder (docs/adr/0013, section 3). Paths are strings.

    watermark.files.local   java.nio on the JVM
    (M3d)                   dart:io on the Dart VM

  Like every port but rendering it is synchronous: the library runs off a
  UI's thread on every host, so blocking file calls are fine."
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(defprotocol Files
  (file?      [fs path] "True when a regular file is at `path`.")
  (make-dirs! [fs path] "Create folder `path` and its parents; fine if it exists.")
  (list-files [fs dir]  "The regular files in folder `dir`: [{:path :name :modified-ms}].")
  (delete!    [fs path] "Delete the file at `path`; truthy if one was there."))

(defn join
  "`dir`/`file`, with the separator `dir` already uses: a Windows folder
  (C:\\...\\) gets a backslash, as java.io.File would give it; any other a
  slash."
  [dir file]
  (let [dir (str dir)]
    (cond (or (str/ends-with? dir "/") (str/ends-with? dir "\\")) (str dir file)
          (and (str/includes? dir "\\") (not (str/includes? dir "/"))) (str dir "\\" file)
          :else (str dir "/" file))))
