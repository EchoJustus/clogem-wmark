;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.store.memory
  "Profile store in an atom: tests, demos, and ephemeral workers that receive
  fully resolved settings and never persist profiles. Portable, so the
  profile rules' golden vectors run on it on every host."
  (:require [watermark.store :as store]
            [watermark.util.host :as host]))

#?(:clj (set! *warn-on-reflection* true))

(defrecord MemoryStore [state lock]
  store/ProfileStore
  (-read [_ slug] (get @state slug))
  (-read-all [_] (vec (sort-by key @state)))
  (-put! [_ slug doc expected-rev]
    (host/serialized lock
                     (fn []
                       (store/check-rev! slug (get @state slug) expected-rev)
                       (swap! state assoc slug doc)
                       doc)))
  (-delete! [_ slug expected-rev]
    (host/serialized lock
                     (fn []
                       (let [current (get @state slug)]
                         (when (number? expected-rev) (store/check-rev! slug current expected-rev))
                         (swap! state dissoc slug)
                         (some? current)))))
  (-transact [this f]
    (host/serialized lock #(f this))))

(defn memory-store
  ([] (memory-store {}))
  ([docs] (->MemoryStore (atom docs) (host/lock))))
