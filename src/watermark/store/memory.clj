;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.store.memory
  "Profile store in an atom: tests, demos, and ephemeral workers that receive
  fully resolved settings and never persist profiles."
  (:require [watermark.store :as store]))

(defrecord MemoryStore [state lock]
  store/ProfileStore
  (-read [_ slug] (get @state slug))
  (-read-all [_] (vec (sort-by key @state)))
  (-put! [_ slug doc expected-rev]
    (locking lock
      (store/check-rev! slug (get @state slug) expected-rev)
      (swap! state assoc slug doc)
      doc))
  (-delete! [_ slug expected-rev]
    (locking lock
      (let [current (get @state slug)]
        (when (number? expected-rev) (store/check-rev! slug current expected-rev))
        (swap! state dissoc slug)
        (some? current))))
  (-transact [this f]
    (locking lock (f this))))

(defn memory-store
  ([] (memory-store {}))
  ([docs] (->MemoryStore (atom docs) (Object.))))
