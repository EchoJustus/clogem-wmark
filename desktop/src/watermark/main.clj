;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.main
  "Community edition entry point -- compiled WITHOUT the pro/ source tree, so
  Pro text modes have no implementation in this binary at all."
  (:require [watermark.app :as app]
            [watermark.core.features :as features])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& args]
  (let [code (app/run-cli {:edition         :community
                           :entitlements-fn (fn [_home] (features/community))}
                          args)]
    (shutdown-agents)
    (System/exit (int (or code 0)))))
