;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.local
  "The local Rasterizer (watermark.raster): the I/O around the kernel's
  drawing (watermark.raster/realize). It reads font files, asks the engine
  to decode stills, and writes each bitmap once as raw RGBA8
  (<raster/bitmap-id>.rgba) in a scratch folder per render, which
  `release!` deletes. No java.desktop: this runs in every native binary."
  (:require [clojure.java.io :as io]
            [watermark.engine :as engine]
            [watermark.raster :as raster])
  (:import (java.io File)
           (java.nio.file Files)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn- font-bytes [path]
  (let [f (io/file (str path))]
    (when-not (.isFile f)
      (throw (ex-info (str "Font file not found: " path) {:wmark/error :invalid :path (str path)})))
    (Files/readAllBytes (.toPath f))))

(defn realize
  "The v2 spec for the v1 `spec`, its bitmaps written to `dir`, validated
  against the published v2 schema before any engine sees it.
  `decode`: (fn [path] image), normally the engine's decode-still."
  [spec ^File dir decode]
  (with-meta
    (raster/realize spec {:decode     decode
                          :font-bytes font-bytes
                          :store!     (fn [id {:keys [px]}]
                                        (let [f (io/file dir (str id ".rgba"))]
                                          (when-not (.exists f)
                                            (io/make-parents f)
                                            (with-open [o (io/output-stream f)] (.write o ^bytes px)))
                                          (.getAbsolutePath f)))})
    {::dir dir}))

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(defrecord LocalRasterizer [work-root]
  raster/Rasterizer
  (realize! [_ eng spec]
    (when-not (satisfies? engine/StillDecoder eng)
      (throw (ex-info "This engine can't decode images, which host rendering (render spec v2) needs."
                      {:wmark/error :unsupported})))
    (realize spec (io/file (str work-root) (str "v2-" (UUID/randomUUID)))
             #(engine/decode-still eng %)))
  (release! [_ spec2]
    (when-let [dir (::dir (meta spec2))] (delete-tree! dir))))

(defn local-rasterizer
  "Bitmaps go to per-render folders under `work-root`."
  [{:keys [work-root]}]
  (->LocalRasterizer work-root))
