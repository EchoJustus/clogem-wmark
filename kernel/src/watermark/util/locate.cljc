;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.util.locate
  "Finding the executables and libraries shipped next to the app, for a
  zero-dependency, unzip-and-run install. The order and the report are the
  library's, so every host searches alike (docs/adr/0014); the host tells
  it what only the host knows (folders, and what is at a path).

  Default search order (first hit wins):

    :explicit  --ffmpeg / WMARK_FFMPEG (a file or a directory)
    :cwd       the current working directory      ./ffmpeg.exe
    :cwd-bin   its bin/ subdirectory              ./bin/ffmpeg.exe
    :app       the directory of the wmark binary  (a native image or a Dart
    :app-bin   ... and its bin/                    executable: its own folder;
                                                   the JVM: the jar's folder)
    :path      the system PATH

  Why :app after :cwd: double-clicking wmark.exe starts it with its own folder
  as the working directory, so :cwd finds the bundle. But a Start-menu
  shortcut, a Finder launch (working directory /) or a terminal elsewhere
  doesn't, and :app then finds the same bundle before falling back to PATH.

  Security: a working-directory search is how binary planting works -- a
  malicious ffmpeg.exe in a folder the user happens to run wmark from gets
  executed. Go removed implicit current-directory lookups in 1.19 for this
  reason. We keep the order you asked for, but every resolution reports its
  source, `working-dir-warning` flags the risky case (found via the working
  directory while wmark itself lives elsewhere), the order is configurable
  (drop :cwd and :cwd-bin for hardened deployments), and hosts always
  execute absolute paths, so the OS's own implicit search never applies."
  (:require [watermark.files :as files]))

#?(:clj (set! *warn-on-reflection* true))

(def default-search [:cwd :cwd-bin :app :app-bin :path])

(defn- candidates
  "[source path] in search order: each folder of each source, each name in
  it. An explicit path that isn't a folder is its own only candidate."
  [{:keys [search explicit names bin-dir cwd app path dir? join]}]
  (let [join    (or join files/join)
        bin-dir (or bin-dir "bin")
        sources (distinct (cond->> (or search default-search) explicit (cons :explicit)))]
    (for [source sources
          f      (case source
                   :explicit (when explicit
                               (if (dir? explicit) (map #(join explicit %) names) [explicit]))
                   :cwd      (map #(join cwd %) names)
                   :cwd-bin  (map #(join (join cwd bin-dir) %) names)
                   :app      (when app (map #(join app %) names))
                   :app-bin  (when app (map #(join (join app bin-dir) %) names))
                   :path     (for [d path, n names] (join d n))
                   [])]
      [source f])))

(defn locate
  "Resolve a file by trying `names` in each folder of `search`, in order.

    :names     file names, e.g. [\"ffmpeg.exe\"]
    :explicit  a path the user gave (file or folder), tried first
    :search    source order (default `default-search`)
    :bin-dir   subfolder for :cwd-bin and :app-bin (default \"bin\")

  and from the host:

    :cwd       the working folder (absolute)
    :app       the folder the program is installed in, or nil
    :path      the folders on PATH, in order
    :dir?      (fn [path]) is there a folder at path?
    :status    (fn [path]) :found (there and usable), :unusable (there, but
               not usable: not executable, say) or :missing
    :absolute  (fn [path]) path made absolute
    :join      (fn [folder name]) default watermark.files/join

  Returns {:path \"/abs\" :source :cwd :trail [...]} or {:path nil :trail
  [...]}, where the trail lists every candidate tried with :found, :missing
  or :unusable -- shown by `wmark doctor` so support can see exactly what
  happened."
  [{:keys [status absolute] :as opts}]
  (reduce (fn [{:keys [trail]} [source f]]
            (let [st    (status f)
                  trail (conj trail {:source source :path f :status st})]
              (if (= :found st)
                (reduced {:path (absolute f) :source source :trail trail})
                {:path nil :trail trail})))
          {:path nil :trail []}
          (candidates opts)))

(defn working-dir-warning
  "Why a resolution deserves a second look, or nil: it came from the working
  folder (:cwd, :cwd-bin) while the program lives elsewhere. `same-folder?`
  (fn [a b]) compares folders the way the host resolves them (canonical
  paths)."
  [{:keys [path source]} {:keys [cwd app same-folder?]}]
  (when (and path (#{:cwd :cwd-bin} source) app (not (same-folder? cwd app)))
    (str "Using " path " from the current folder, not from wmark's own folder ("
         app "). Run wmark from its own folder or pass --ffmpeg to be explicit.")))
