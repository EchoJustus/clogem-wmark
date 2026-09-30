;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.fs
  "File helpers for the job pipeline."
  (:require [clojure.java.io :as io]
            [watermark.media :as media])
  (:import (java.io RandomAccessFile)
           (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption OpenOption Path Paths
                          StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security SecureRandom)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.attribute.FileAttribute;" no-attrs (make-array FileAttribute 0))
(def ^:private ^"[Ljava.nio.file.LinkOption;" no-links (make-array LinkOption 0))

(defn path ^Path [p & more] (Paths/get (str p) (into-array String (map str more))))

(defn mkdirs! ^Path [p] (Files/createDirectories (path p) no-attrs))

(defn fingerprint
  "The media fingerprint of file `f` (watermark.media/fingerprint): SHA-256
  over its size, first MiB and last MiB."
  ^String [f]
  (with-open [raf (RandomAccessFile. (io/file f) "r")]
    (let [size (.length raf)
          read (fn [[offset n]]
                 (let [buf (byte-array n)]
                   (.seek raf (long offset))
                   (.readFully raf buf)
                   buf))
          [head tail] (map read (media/fingerprint-ranges size))]
      (media/fingerprint size head tail))))

(defn- restrict-to-owner! [^Path p]
  (when (contains? (.supportedFileAttributeViews (.getFileSystem p)) "posix")
    (Files/setPosixFilePermissions p (PosixFilePermissions/fromString "rw-------"))))

(defn studio-secret!
  "32 random bytes at `<home>/secret.key`, created on first use. Keys every
  Pro schedule: back it up -- without it, old schedules can't be reproduced as
  evidence. Owner-only permissions on POSIX; on Windows the per-user AppData
  ACL already restricts it."
  ^bytes [home]
  (let [f (path home "secret.key")]
    (if (Files/exists f no-links)
      (.decode (Base64/getDecoder) (.trim (String. (Files/readAllBytes f) StandardCharsets/UTF_8)))
      (let [b (byte-array 32)]
        (.nextBytes (SecureRandom.) b)
        (mkdirs! home)
        (Files/write f (.getBytes (.encodeToString (Base64/getEncoder) b) StandardCharsets/UTF_8)
                     ^"[Ljava.nio.file.OpenOption;"
                     (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
        (restrict-to-owner! f)
        b))))

(defn write-files!
  "Write {path content} as UTF-8, creating parent directories."
  [files]
  (doseq [[p content] files]
    (let [target (path p)]
      (Files/createDirectories (.getParent target) no-attrs)
      (Files/write target (.getBytes (str content) StandardCharsets/UTF_8)
                   ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0)))))

(defn publish!
  "Move a finished `.part` output into place (atomic where the FS allows), so
  a half-written file never carries the final name."
  [part final overwrite?]
  (let [opts (cond-> [StandardCopyOption/ATOMIC_MOVE]
               overwrite? (conj StandardCopyOption/REPLACE_EXISTING))]
    (when (and (not overwrite?) (Files/exists (path final) no-links))
      (throw (ex-info (str "Output exists: " final " (enable overwrite to replace it)")
                      {:wmark/error :conflict :path (str final)})))
    (Files/move (path part) (path final)
                ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption opts))))

(defn delete-quietly! [p]
  (try (Files/deleteIfExists (path p)) (catch Exception _ nil)))

(defn delete-tree!
  "Remove a scratch directory (best effort)."
  [dir]
  (let [root (io/file (str dir))]
    (when (.exists root)
      (doseq [^java.io.File f (reverse (file-seq root))]
        (.delete f)))))
