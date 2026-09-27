;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.modes
  "Text display modes: a settings text layer -> a render-spec text layer.

  A registry of functions, not a multimethod: ClojureDart has no multimethods,
  and this namespace must run there when a GUI plans renders in-process
  (Stages 3-4). The open core registers :continuous and :scheduled; the Pro
  tree registers :subliminal and :random when its namespace loads. Under
  native-image that happens at build time, so the registry baked into the Pro
  binary already holds them, while the community binary -- compiled without
  the Pro tree -- reports them as unavailable.

  Mode functions receive ctx:
    :canvas {:width :height}   :timebase {:fps-num :fps-den :frames :first-frame}
    :fps (double)              :font default font path
    :index layer position      :seed 64-bit seed keyed to (secret, input, layer)
    :entitlements the active watermark.core.features/Entitlements"
  (:require [watermark.core.features :as features]
            [watermark.render.layout :as layout]))

#?(:clj (set! *warn-on-reflection* true))

(defonce ^:private registry (atom {}))

(defn register!
  "Install `f` as the implementation of text mode `mode`."
  [mode f]
  (swap! registry assoc mode f)
  mode)

(defn available [] (set (keys @registry)))

(defn layer-spec
  "Render-spec layer for one settings text layer. The registry is keyed by
  wire id, so an alias (\"canary\") finds its mode (:subliminal)."
  [ctx layer]
  (let [layer (update layer :mode features/canonical-mode)]
    (if-let [f (get @registry (:mode layer))]
      (f ctx layer)
      (throw (ex-info (str "The \"" (features/mode-display-name (:mode layer)) "\" text mode is part of wmark Pro.")
                      {:wmark/error :feature-unavailable
                       :mode        (:mode layer)})))))

(register! :continuous
           (fn [ctx layer] (layout/text-layer ctx layer)))

(register! :scheduled
           (fn [{:keys [fps timebase] :as ctx} {:keys [at duration-s] :as layer}]
             (assoc (layout/text-layer ctx layer)
                    :timing {:type    :windows
                             :windows (layout/normalize-windows
                                       (keep #(layout/seconds->window fps % duration-s) at)
                                       (:frames timebase))})))
