;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.store
  "The profile-storage port.

  watermark.config implements every profile rule (names, `latest`, conflicts,
  fallback) on top of this protocol and nothing else, so the same rules run
  over any backend:

    watermark.store.file     local files        desktop editions
    watermark.store.memory   an atom            tests, demos, ephemeral workers
    watermark.saas.store     PostgreSQL rows    Stage 5 serverless backend

  Contract
  * Documents are maps carrying :profile/rev, a revision number that
    increases by one on every write. Stores persist whole documents.
  * Writes are compare-and-set on that revision (`expected-rev`):
      nil      the slug must be absent (create)
      n        the stored document's rev must be n (replace what was read)
      ::any    unconditional (last writer wins -- used for `latest`)
    A failed comparison throws :wmark/error :conflict. This is what makes
    concurrent edits safe without holding locks across requests: two browser
    tabs, two serverless instances, or a GUI and the CLI can race, and one of
    them gets a clean 409 instead of silently losing the other's change.
  * `-transact` runs (f tx) as one unit of work on a store view `tx`. A SQL
    store opens a database transaction (and sets the tenant for row-level
    security): all or nothing. File and memory stores serialise units
    in-process and can't roll back, so multi-step operations order their
    writes to fail safe (rename writes the new name before deleting the old:
    a crash leaves a copy, never a loss). Units must be short and do no I/O
    besides the store."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]))

(defprotocol ProfileStore
  (-read     [store slug]                   "Document for `slug`, or nil.")
  (-read-all [store]                        "Every document as [slug doc], or [slug {:error msg}]
                                              for one that can't be decoded. One round trip for DB stores.")
  (-put!     [store slug doc expected-rev]  "Create or replace `slug` (compare-and-set, see ns doc).")
  (-delete!  [store slug expected-rev]      "Delete `slug`; nil/::any = unconditional. Truthy if it existed.")
  (-transact [store f]                      "Call (f tx) as one atomic unit of work; return its value."))

(def format-version
  "On-disk/in-row format of a profile document. Bump + migrate when the shape changes."
  1)

(defn rev-of [doc] (get doc :profile/rev 0))

(defn conflict!
  [slug message data]
  (throw (ex-info message (merge {:wmark/error :conflict :slug slug} data))))

(defn check-rev!
  "Enforce the compare-and-set rule against the currently stored `current`."
  [slug current expected-rev]
  (cond
    (= expected-rev ::any)            nil
    (nil? expected-rev)               (when current
                                        (conflict! slug (str "Profile \"" slug "\" already exists.")
                                                   {:reason :exists}))
    (nil? current)                    (conflict! slug (str "Profile \"" slug "\" no longer exists.")
                                                 {:reason :missing})
    (not= expected-rev (rev-of current))
    (conflict! slug "This profile changed since it was loaded. Reload it to see the latest version."
               {:reason :stale :expected expected-rev :actual (rev-of current)})))

;; ---------------------------------------------------------------------------
;; Document codec: human-editable EDN, shared by file and SQL stores

(defn encode-doc
  "EDN text. Printing vars are pinned so a REPL binding such as *print-length*
  can never truncate a stored profile."
  ^String [doc]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-meta*           false
            *print-namespace-maps* false
            *print-dup*            false
            *print-readably*       true]
    (with-out-str (pprint/pprint doc))))

(defn decode-doc
  "Document from EDN text, or an :invalid error naming `where`."
  [^String text where]
  (let [doc (try (edn/read-string {:eof nil} text)
                 (catch Exception e
                   (throw (ex-info (str "Not valid EDN: " where)
                                   {:wmark/error :invalid :path (str where) :cause (ex-message e)}))))
        fmt (get doc :wmark/format 1)]
    (cond
      (not (and (map? doc) (map? (:settings doc)) (string? (:profile/name doc))))
      (throw (ex-info (str "Not a wmark profile: " where) {:wmark/error :invalid :path (str where)}))

      (not (and (integer? fmt) (<= 1 fmt format-version)))
      (throw (ex-info (str "Profile was written by a newer wmark: " where)
                      {:wmark/error :invalid :path (str where) :format fmt}))

      :else doc)))
