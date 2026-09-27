;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.raster.local
  "The local Rasterizer (watermark.raster): the I/O around the kernel's
  drawing. It reads font files, asks the engine to decode stills, names
  each bitmap by the SHA-256 of its size and pixels, and writes it once as
  raw RGBA8 (<sha256>.rgba) in a scratch folder per render, which
  `release!` deletes. No java.desktop: this runs in every native binary."
  (:require [clojure.java.io :as io]
            [watermark.engine :as engine]
            [watermark.raster :as raster]
            [watermark.raster.truetype :as tt]
            [watermark.render.v2 :as v2])
  (:import (java.io File)
           (java.nio.file Files)
           (java.security MessageDigest)
           (java.util HexFormat UUID)))

(set! *warn-on-reflection* true)

(defn bitmap-id
  "SHA-256 (hex) of an image's size and pixels: equal bitmaps, equal names."
  ^String [{:keys [width height ^bytes px]}]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str width "x" height ":") "UTF-8"))
    (.update md px)
    (.formatHex (HexFormat/of) (.digest md))))

(defn read-font
  "A parsed TrueType font from a file path."
  [path]
  (when-not path
    (throw (ex-info "Text layers need a font file (the layer's font-path, or the bundled default)."
                    {:wmark/error :invalid})))
  (let [f (io/file (str path))]
    (when-not (.isFile f)
      (throw (ex-info (str "Font file not found: " path) {:wmark/error :invalid :path (str path)})))
    (tt/parse (Files/readAllBytes (.toPath f)))))

(defn realize
  "The v2 spec for the v1 `spec`, its bitmaps written to `dir`.
  `decode`: (fn [path] image), normally the engine's decode-still."
  [spec ^File dir decode]
  (let [stills (memoize decode)
        fonts  (memoize read-font)
        results (into {}
                      (for [req (v2/raster-requests spec)
                            :let [img (raster/draw req {:decoded stills :font fonts})
                                  id  (bitmap-id img)
                                  f   (io/file dir (str id ".rgba"))]]
                        (do (when-not (.exists f)
                              (io/make-parents f)
                              (with-open [o (io/output-stream f)] (.write o ^bytes (:px img))))
                            [(:key req) {:bitmap id :width (:width img) :height (:height img)
                                         :path (.getAbsolutePath f)}])))]
    (with-meta (v2/assemble spec results) {::dir dir})))

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
