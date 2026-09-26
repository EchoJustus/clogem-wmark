;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.store.file
  "Profile store on the local file system: one human-editable EDN file per
  profile under <home>/profiles/<slug>.edn.

  Writes go to a temp file in the same directory, are fsynced, then renamed
  over the target, so readers see the old file or the new one, never a torn
  one. Antivirus scanners and sync clients (OneDrive, Dropbox) briefly lock
  fresh files on Windows; those failures are retried."
  (:require [watermark.store :as store])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file AccessDeniedException AtomicMoveNotSupportedException
                          CopyOption DirectoryStream Files FileSystemException
                          LinkOption OpenOption Path StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private file-ext ".edn")
(def ^:private ^"[Ljava.nio.file.attribute.FileAttribute;" no-attrs (make-array FileAttribute 0))
(def ^:private ^"[Ljava.nio.file.LinkOption;" no-links (make-array LinkOption 0))

(defn- profile-file ^Path [^Path dir ^String slug]
  (.resolve dir (str slug file-ext)))

(defn- transient-fs-error?
  "AccessDenied, or a bare FileSystemException (sharing violation). Subclasses
  such as NoSuchFileException are real errors and are not retried."
  [^Throwable e]
  (or (instance? AccessDeniedException e)
      (= FileSystemException (class e))))

(defn- with-fs-retries [f]
  (loop [attempt 0]
    (let [result (try {:ok (f)}
                      (catch Exception e
                        (if (and (transient-fs-error? e) (< attempt 5))
                          ::retry
                          (throw e))))]
      (if (= ::retry result)
        (do (Thread/sleep (* 20 (bit-shift-left 1 attempt)))
            (recur (inc attempt)))
        (:ok result)))))

(defn- write-atomically! [^Path target ^String content]
  (let [dir (.getParent target)
        tmp (.resolve dir (str "." (.getFileName target) "." (UUID/randomUUID) ".tmp"))]
    (Files/createDirectories dir no-attrs)
    (try
      (Files/write tmp (.getBytes content StandardCharsets/UTF_8)
                   ^"[Ljava.nio.file.OpenOption;"
                   (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE
                                           StandardOpenOption/SYNC]))
      (with-fs-retries
        #(try
           (Files/move tmp target ^"[Ljava.nio.file.CopyOption;"
                       (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
           (catch AtomicMoveNotSupportedException _
             (Files/move tmp target ^"[Ljava.nio.file.CopyOption;"
                         (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))
      (finally
        (Files/deleteIfExists tmp)))))

(defn- read-file [^Path dir slug]
  (let [f (profile-file dir slug)]
    (when (Files/isRegularFile f no-links)
      (store/decode-doc (String. (Files/readAllBytes f) StandardCharsets/UTF_8) f))))

(defn- list-slugs [^Path dir]
  (if-not (Files/isDirectory dir no-links)
    []
    (with-open [^DirectoryStream ds (Files/newDirectoryStream dir (str "*" file-ext))]
      (->> (seq ds)
           (map (fn [^Path p] (str (.getFileName p))))
           (remove #(.startsWith ^String % "."))            ; temp and hidden files
           (map #(subs % 0 (- (count %) (count file-ext))))
           sort
           doall))))

(defrecord FileStore [^Path dir lock]
  store/ProfileStore
  (-read [_ slug] (read-file dir slug))
  (-read-all [_]
    (vec (for [slug (list-slugs dir)]
           [slug (try (read-file dir slug)
                      (catch clojure.lang.ExceptionInfo e {:error (ex-message e)}))])))
  (-put! [_ slug doc expected-rev]
    (locking lock
      (when-not (= expected-rev ::store/any)
        (store/check-rev! slug (try (read-file dir slug)
                                    ;; a damaged file can be replaced unconditionally only
                                    (catch clojure.lang.ExceptionInfo _ nil))
                          expected-rev))
      (write-atomically! (profile-file dir slug) (store/encode-doc doc))
      doc))
  (-delete! [_ slug expected-rev]
    (locking lock
      (when (number? expected-rev)
        (store/check-rev! slug (read-file dir slug) expected-rev))
      (with-fs-retries #(Files/deleteIfExists (profile-file dir slug)))))
  (-transact [this f]
    (locking lock (f this))))

(defn file-store
  "Store under `profiles-dir` (usually <home>/profiles). Writes are serialised
  in-process; across processes the atomic rename keeps every file whole and
  revisions catch lost updates."
  [^Path profiles-dir]
  (->FileStore profiles-dir (Object.)))
