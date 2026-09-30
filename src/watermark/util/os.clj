;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.os
  "The only place that knows which OS it is on. Everything here uses portable
  JDK APIs; the per-OS differences are file names and one launcher command,
  not Win32 calls. It also tells watermark.util.locate (the core library's
  search order) the folders and files it asks about."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.util.locate :as locate])
  (:import (java.io File InputStream)
           (java.lang ProcessHandle)
           (java.nio.file CopyOption Files LinkOption Path Paths StandardCopyOption)
           (java.util Locale)
           (java.util.regex Pattern)))

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
  "A font file FFmpeg and the v2 rasterizer can open. Prefers `fonts/wmark.ttf`
  bundled in the binary (Fira Sans Bold, OFL; extracted to the cache dir --
  FFmpeg can't read from inside our executable, and re-extracted when the
  bundled file changes), then common system fonts."
  [^Path cache-dir]
  (or (when-let [r (io/resource "fonts/wmark.ttf")]
        (let [target (.resolve cache-dir "wmark.ttf")
              ^bytes bundled (with-open [^InputStream in (io/input-stream r)] (.readAllBytes in))]
          (when-not (and (Files/exists target (make-array LinkOption 0))
                         (java.util.Arrays/equals bundled (Files/readAllBytes target)))
            (Files/createDirectories cache-dir (make-array java.nio.file.attribute.FileAttribute 0))
            (with-open [^InputStream in (io/input-stream r)]
              (Files/copy in target ^"[Ljava.nio.file.CopyOption;"
                          (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
          (str target)))
      (some #(when (.isFile (io/file %)) %) (system-fonts (family)))))

;; ---------------------------------------------------------------------------
;; Where things are: the host's side of watermark.util.locate

(defn native-image?
  "True inside a GraalVM native image (set by the image at run time)."
  []
  (= "runtime" (System/getProperty "org.graalvm.nativeimage.imagecode")))

(defn app-dir
  "Directory wmark was installed in: the executable's folder for a native
  image, the jar's folder on the JVM, nil when running from sources."
  ^File []
  (if (native-image?)
    (let [^String cmd (.orElse (.command (.info (ProcessHandle/current))) nil)]
      (some-> cmd File. .getAbsoluteFile .getParentFile))
    ;; our own class, not Clojure's: from an uberjar that is wmark's jar; from
    ;; sources the class is generated at run time and has no code source
    (let [src (some-> (class native-image?) .getProtectionDomain .getCodeSource .getLocation)
          f   (some-> src .toURI Paths/get .toFile)]
      (when (and f (.isFile f) (str/ends-with? (.getName f) ".jar"))
        (.getParentFile (.getAbsoluteFile f))))))

(defn cwd ^File [] (.getAbsoluteFile (io/file (System/getProperty "user.dir"))))

(defn- path-dirs []
  (->> (str/split (or (System/getenv "PATH") "") (re-pattern (Pattern/quote File/pathSeparator)))
       (remove str/blank?)))

(defn locate
  "watermark.util.locate/locate on this machine. `opts` as there, plus
    :ok?          (fn [File] bool), e.g. executable? (default: is a file)
    :cwd, :app-dir  override the working / install directory (tests)"
  [{:keys [ok?] :as opts}]
  (let [ok? (or ok? #(.isFile ^File %))
        app (if (contains? opts :app-dir) (:app-dir opts) (app-dir))]
    (locate/locate (assoc (dissoc opts :ok? :app-dir)
                          :cwd      (str (if (:cwd opts) (io/file (:cwd opts)) (cwd)))
                          :app      (some-> app str)
                          :path     (path-dirs)
                          :dir?     #(.isDirectory (io/file %))
                          :status   #(let [f (io/file %)] (cond (ok? f) :found (.exists f) :unusable :else :missing))
                          :absolute #(.getAbsolutePath (io/file %))
                          :join     #(str (io/file %1 %2))))))

(defn working-dir-warning
  "Why a resolution deserves a second look, or nil
  (watermark.util.locate/working-dir-warning)."
  [resolution]
  (locate/working-dir-warning resolution {:cwd (cwd) :app (app-dir)
                                          :same-folder? #(= (.getCanonicalFile ^File %1) (.getCanonicalFile ^File %2))}))
