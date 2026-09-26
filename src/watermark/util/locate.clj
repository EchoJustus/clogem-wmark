;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.locate
  "Finding the executables and libraries shipped next to the app, for a
  zero-dependency, unzip-and-run install.

  Default search order (first hit wins):

    :explicit  --ffmpeg / WMARK_FFMPEG (a file or a directory)
    :cwd       the current working directory      ./ffmpeg.exe
    :cwd-bin   its bin/ subdirectory              ./bin/ffmpeg.exe
    :app       the directory of the wmark binary  (native image: the executable;
    :app-bin   ... and its bin/                    JVM: the uberjar's folder)
    :path      the system PATH

  Why :app after :cwd: double-clicking wmark.exe starts it with its own folder
  as the working directory, so :cwd finds the bundle. But a Start-menu
  shortcut, a Finder launch (working directory /) or a terminal elsewhere
  doesn't, and :app then finds the same bundle before falling back to PATH.

  Security: a working-directory search is how binary planting works -- a
  malicious ffmpeg.exe in a folder the user happens to run wmark from gets
  executed. Go removed implicit current-directory lookups in 1.19 for this
  reason. We keep the order you asked for, but every resolution reports its
  source, `warning` flags the risky case (found via the working directory
  while wmark itself lives elsewhere), the order is configurable (drop :cwd
  and :cwd-bin for hardened deployments), and we always execute absolute
  paths, so the OS's own implicit search never applies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.lang ProcessHandle)
           (java.nio.file Paths)
           (java.util.regex Pattern)))

(set! *warn-on-reflection* true)

(def default-search [:cwd :cwd-bin :app :app-bin :path])

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
       (remove str/blank?)
       (map io/file)))

(defn- dirs-for [source {:keys [explicit bin-dir] :as opts}]
  (let [app     (if (contains? opts :app-dir) (some-> (:app-dir opts) io/file) (app-dir))
        here    (if (:cwd opts) (io/file (:cwd opts)) (cwd))
        bin-dir (or bin-dir "bin")]
    (case source
      :explicit (when explicit
                  (let [f (io/file explicit)] [(if (.isDirectory f) f (.getParentFile (.getAbsoluteFile f)))]))
      :cwd      [here]
      :cwd-bin  [(io/file here bin-dir)]
      :app      (when app [app])
      :app-bin  (when app [(io/file app bin-dir)])
      :path     (path-dirs))))

(defn- file-for [source dir {:keys [explicit names]}]
  (if (and (= source :explicit) (not (.isDirectory (io/file explicit))))
    [(io/file explicit)]
    (map #(io/file dir %) names)))

(defn locate
  "Resolve a file by trying `names` in each directory of `search`, in order.

    :names     file names, e.g. [\"ffmpeg.exe\"]
    :explicit  a path the user gave (file or directory) -- tried first
    :search    source order (default `default-search`)
    :ok?       (fn [File] bool), e.g. executable? (default: is a file)
    :bin-dir   subdirectory name for :cwd-bin / :app-bin (default \"bin\")
    :cwd, :app-dir  override the working / install directory (tests)

  Returns {:path \"/abs\" :source :cwd :trail [...]} or {:path nil :trail [...]}
  where the trail lists every candidate with :found, :missing or :unusable --
  shown by `wmark doctor` so support can see exactly what happened."
  [{:keys [search ok?] :as opts}]
  (let [ok?     (or ok? #(.isFile ^File %))
        sources (cond->> (or search default-search) (:explicit opts) (cons :explicit))]
    (loop [[[source ^File f] & more] (for [source (distinct sources)
                                           dir    (dirs-for source opts)
                                           f      (file-for source dir opts)]
                                       [source f])
           trail []]
      (if (nil? f)
        {:path nil :trail trail}
        (let [status (cond (ok? f) :found (.exists f) :unusable :else :missing)
              trail  (conj trail {:source source :path (str f) :status status})]
          (if (= status :found)
            {:path (.getAbsolutePath f) :source source :trail trail}
            (recur more trail)))))))

(defn working-dir-warning
  "Why a resolution deserves a second look, or nil."
  [{:keys [path source]}]
  (let [app (app-dir)]
    (when (and path (#{:cwd :cwd-bin} source) app
               (not= (.getCanonicalFile (cwd)) (.getCanonicalFile app)))
      (str "Using " path " from the current folder, not from wmark's own folder ("
           app "). Run wmark from its own folder or pass --ffmpeg to be explicit."))))
