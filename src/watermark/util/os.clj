;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.os
  "The only place that knows which OS it is on. Everything here uses portable
  JDK APIs; the per-OS differences are file names and one launcher command,
  not Win32 calls."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io InputStream)
           (java.nio.file CopyOption Files LinkOption Path StandardCopyOption)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(defn family []
  (let [os (.toLowerCase (System/getProperty "os.name" "") Locale/ROOT)]
    (cond (str/starts-with? os "windows") :windows
          (str/starts-with? os "mac")     :macos
          :else                           :unix)))

(defn windows? [] (= :windows (family)))

(defn exe-name ^String [base] (if (windows?) (str base ".exe") (str base)))

(defn open-url!
  "Best effort; the URL is printed anyway. On Windows `start` goes through cmd,
  so the URL must not contain `&` -- ours carries a single query parameter."
  [^String url]
  (let [cmd (case (family)
              :windows ["cmd" "/c" "start" "" url]
              :macos   ["open" url]
              ["xdg-open" url])]
    (try (.start (ProcessBuilder. ^java.util.List cmd)) true
         (catch Exception _ false))))

(def ^:private system-fonts
  {:windows ["C:/Windows/Fonts/segoeuib.ttf" "C:/Windows/Fonts/arialbd.ttf" "C:/Windows/Fonts/arial.ttf"]
   :macos   ["/System/Library/Fonts/Supplemental/Arial Bold.ttf" "/Library/Fonts/Arial.ttf"]
   :unix    ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
             "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"
             "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"]})

(defn default-font
  "A font file FFmpeg can open. Prefers `fonts/wmark.ttf` bundled in the binary
  (extracted once to the cache dir -- FFmpeg can't read from inside our
  executable), then common system fonts. Bundle an OFL font with CJK coverage
  if warning texts may be non-Latin."
  [^Path cache-dir]
  (or (when-let [r (io/resource "fonts/wmark.ttf")]
        (let [target (.resolve cache-dir "wmark.ttf")]
          (when-not (Files/exists target (make-array LinkOption 0))
            (Files/createDirectories cache-dir (make-array java.nio.file.attribute.FileAttribute 0))
            (with-open [^InputStream in (io/input-stream r)]
              (Files/copy in target ^"[Ljava.nio.file.CopyOption;"
                          (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
          (str target)))
      (some #(when (.isFile (io/file %)) %) (system-fonts (family)))))
