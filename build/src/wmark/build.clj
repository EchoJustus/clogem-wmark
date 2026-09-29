;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns wmark.build
  "Build tasks -- a generic interpreter of :wmark/build-matrix in the deps.edn
  of the project it runs in. No target is hard-coded here: add a component
  alias and a target entry to deps.edn and every task below knows it.

  Both repositories run it: clogem-wmark through `:build {:deps {wmark/build
  {:local/root \"build\"}}}`, the commercial repository through a git
  dependency on this subproject.

    clojure -T:build matrix                                  stages, bundles, targets
    clojure -T:build lint                                    validate the matrix
    clojure -T:build aliases :target :engine                 -> :desktop:web
    clojure -T:build uber    :target :engine [:edition E]    AOT uberjar
    clojure -T:build native  :target :engine [:edition E]    GraalVM binary for THIS OS
    clojure -T:build bundle  :bundle :desktop-server :ffmpeg-dir DIR [:edition E]

  `:with [:local]` adds aliases to a build (the commercial repository uses it
  to build against a sibling checkout of clogem-wmark).

  native-image does not cross-compile: WSL2/Linux builds the Linux binary,
  Windows (MSVC) or the windows-latest CI runner builds the .exe, macOS the
  Mac one. See .github/workflows/ci.yml.

  matrix, lint, aliases and bundle need only Clojure -- tools.build is loaded
  lazily by uber and native."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.security MessageDigest)
           (java.util HexFormat)
           (java.util.zip ZipEntry ZipFile ZipInputStream)))

(set! *warn-on-reflection* true)

(defn- deps-edn [] (edn/read-string (slurp "deps.edn")))
(defn- matrix* [] (:wmark/build-matrix (deps-edn)))
(defn- tb [f] (requiring-resolve (symbol "clojure.tools.build.api" (name f))))

(defn- kw [x] (when x (keyword (name x))))

(defn- fail! [msg & [data]] (throw (ex-info msg (or data {}))))

(defn resolve-target
  "Everything a JVM build of `target` in `edition` needs."
  [{:keys [target edition with]}]
  (let [m       (matrix*)
        target  (or (kw target) (fail! (str "Pass :target, one of " (vec (keys (:targets m))))))
        t       (or (get-in m [:targets target]) (fail! (str "Unknown target " target ", one of " (vec (keys (:targets m))))))
        edition (or (kw edition) (first (:editions t)))]
    (when-not (= :jvm (:toolchain t))
      (fail! (str target " is built with the " (name (:toolchain t)) " toolchain: " (pr-str (:commands t)))))
    (when-not (some #{edition} (:editions t))
      (fail! (str target " comes in " (:editions t) ", not " edition)))
    (doseq [f (get-in m [:editions edition :requires])]
      (when-not (.exists (io/file f))
        (fail! (str "The " (name edition) " edition needs " f " (see the runbook: license keys)."))))
    {:target       target
     :edition      edition
     :aliases      (vec (distinct (concat (:aliases t) (get-in m [:editions edition :aliases]) (map kw with))))
     :main         (get-in t [:main edition])
     :artifact     (get-in t [:artifact edition])
     :native-image (:native-image t)}))

;; ---------------------------------------------------------------------------
;; Inspection

(defn matrix
  "Print the roadmap as the build sees it."
  [_]
  (let [{:keys [stages bundles targets]} (matrix*)]
    (doseq [[n {:keys [name bundles]}] (sort stages)]
      (println (str "Stage " n ": " name))
      (doseq [b bundles]
        (let [{:keys [doc platforms targets sidecars gui]} (get (:bundles (matrix*)) b)]
          (println (format "  %-15s %-28s %s" (clojure.core/name b) (str/join "," (map clojure.core/name platforms)) doc))
          (println (format "  %-15s targets %s%s%s" "" (str/join ", " (map clojure.core/name targets))
                           (if (seq sidecars) (str "  + sidecars " (str/join ", " (map clojure.core/name sidecars))) "")
                           (if gui (str "  gui " (pr-str gui)) ""))))))
    (println "\nTargets:")
    (doseq [[k {:keys [doc toolchain aliases editions]}] targets]
      (println (format "  %-7s %-5s aliases %-18s editions %-20s %s" (name k) (name toolchain)
                       (pr-str (or aliases [])) (pr-str (or editions [])) doc)))
    (when (empty? bundles) (println "(no bundles)"))))

(defn aliases
  "Print the alias string for a target, for dev runs:
  clojure -M$(clojure -T:build aliases :target :engine) -m watermark.main"
  [opts]
  (println (apply str (map str (:aliases (resolve-target opts))))))

(defn- ns->file [ns-sym] (str (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- paths-of [deps alias]
  (let [a (get-in deps [:aliases alias])]
    (concat (:extra-paths a) (:paths a))))

(defn- declared-libs
  "Every lib the project declares: top-level :deps plus each alias's
  :extra-deps, :deps and :override-deps."
  [deps]
  (set (concat (keys (:deps deps))
               (mapcat (fn [[_ a]] (concat (keys (:extra-deps a)) (keys (:deps a)))) (:aliases deps)))))

(defn- manifest-problems
  "A component that has its own deps.edn (so other repositories can depend on
  it with :deps/root) must match the alias that adds it here: same paths
  (relative to the component) and the same dependencies. Drift between the two
  would let this repository test one thing and ship another."
  [deps]
  (for [[alias {:keys [extra-paths extra-deps]}] (:aliases deps)
        :let [dirs (distinct (map #(first (str/split % #"/")) extra-paths))]
        :when (and (= 1 (count dirs)) (every? #(str/includes? % "/") extra-paths))
        :let [dir (first dirs) f (io/file dir "deps.edn")]
        :when (.isFile f)
        :let [manifest (edn/read-string (slurp f))
              want     (set (map #(subs % (inc (count dir))) extra-paths))
              have     (set (:paths manifest))]
        problem [(when (not= want have)
                   (str "alias " alias " adds paths " (vec (sort want)) " of " dir "/ but " dir "/deps.edn declares " (vec (sort have))))
                 (when (not= (or extra-deps {}) (or (:deps manifest) {}))
                   (str "alias " alias " and " dir "/deps.edn declare different :deps: "
                        (pr-str (or extra-deps {})) " vs " (pr-str (or (:deps manifest) {}))))]
        :when problem]
    problem))

(defn license-texts
  "The license files a pinned FFmpeg build ships: [{:file :url :sha256}].
  A single :text is the GPLv3."
  [{:keys [text texts]}]
  (or texts (when text [(assoc text :file "COPYING.GPLv3")])))

(defn ffmpeg-pin-problems
  "What's wrong with the matrix's :ffmpeg pins (and each :variants entry):
  every platform needs a version, source notes, and either archives of a
  build (:archives, each an https :url with a SHA-256) or a recipe to build
  one (:build, a pinned :source archive and its :configure flags); the
  license texts are pinned the same way. Bundles that ship sidecars need
  pins."
  [{:keys [ffmpeg bundles]}]
  (let [sha?     #(and (string? %) (re-matches #"[0-9a-f]{64}" %))
        https?   #(and (string? %) (str/starts-with? % "https://"))
        pinned?  (fn [{:keys [url sha256]}] (and (https? url) (sha? sha256)))
        pins     (fn [label {:keys [license platforms]}]
                   (concat
                    (when-not (and (seq (license-texts license)) (every? pinned? (license-texts license)))
                      [(str label " :license needs its texts with an https :url and a :sha256 each")])
                    (for [[p {:keys [version archives build source]}] platforms
                          problem [(when-not (string? version) (str label " for " p " has no :version"))
                                   (when-not (= 1 (count (remove nil? [(seq archives) build])))
                                     (str label " for " p " needs either :archives or a :build recipe"))
                                   (when-not (every? pinned? archives) (str label " for " p ": every archive needs an https :url and a 64-hex :sha256"))
                                   (when (and build (not (and (pinned? (:source build))
                                                              (seq (:configure build))
                                                              (every? string? (:configure build)))))
                                     (str label " for " p ": :build needs a :source with an https :url and a 64-hex :sha256, and :configure flags"))
                                   (when (empty? source) (str label " for " p " doesn't say where its source is (:source)"))]
                          :when problem]
                      problem)))]
    (concat
     (when (and (some (comp seq :sidecars) (vals bundles)) (empty? (:platforms ffmpeg)))
       ["bundles ship FFmpeg sidecars, but the matrix pins no :ffmpeg builds"])
     (when ffmpeg (pins "FFmpeg" ffmpeg))
     (mapcat (fn [[v pin]] (pins (str "FFmpeg (" (name v) ")") pin)) (:variants ffmpeg)))))

(defn lint
  "Validate the matrix against the repository: every alias exists, every path
  exists, every main namespace is on its target's paths (or comes from the
  library named by :main-lib), bundles name real targets, stages name real
  bundles, and component deps.edn files agree with their aliases. Throws on
  the first problems found."
  [_]
  (let [deps     (deps-edn)
        {:keys [editions targets bundles stages] :as m} (:wmark/build-matrix deps)
        problems (atom [])
        problem! #(swap! problems conj %)]
    (when-not m (problem! "deps.edn has no :wmark/build-matrix"))
    (doseq [p (:paths deps)] (when-not (.exists (io/file p)) (problem! (str "root path missing: " p))))
    (doseq [[e {:keys [aliases]}] editions, a aliases]
      (when-not (get-in deps [:aliases a]) (problem! (str "edition " e " names unknown alias " a))))
    (doseq [[t {:keys [toolchain aliases editions main main-lib project]}] targets]
      (case toolchain
        :jvm  (do (doseq [a aliases]
                    (if-let [alias (get-in deps [:aliases a])]
                      (doseq [p (:extra-paths alias)]
                        (when-not (.exists (io/file p)) (problem! (str "alias " a " path missing: " p))))
                      (problem! (str "target " t " names unknown alias " a))))
                  (doseq [e editions]
                    (let [ns   (get main e)
                          dirs (concat (:paths deps) ["kernel/src"]
                                       (mapcat #(paths-of deps %) (concat aliases (get-in m [:editions e :aliases]))))]
                      (cond (nil? ns) (problem! (str "target " t " has no main for edition " e))

                            main-lib
                            (when-not (contains? (declared-libs deps) main-lib)
                              (problem! (str "target " t " takes its main from " main-lib ", which deps.edn doesn't declare")))

                            (not-any? #(or (.exists (io/file % (ns->file ns))) (.exists (io/file % (str (ns->file ns) "c")))) dirs)
                            (problem! (str "target " t " main " ns " not found on " (vec dirs)))))))
        :cljd (when-not (.exists (io/file project "deps.edn"))
                (problem! (str "target " t " project " project "/deps.edn missing")))
        (problem! (str "target " t " has unknown toolchain " toolchain))))
    (doseq [[b {:keys [targets]}] bundles, t targets]
      (when-not (contains? (:targets m) t)
        (problem! (str "bundle " b " names unknown target " t))))
    (doseq [[s {:keys [bundles]}] stages, b bundles]
      (when-not (contains? (:bundles m) b) (problem! (str "stage " s " names unknown bundle " b))))
    (doseq [p (manifest-problems deps)] (problem! p))
    (doseq [p (ffmpeg-pin-problems m)] (problem! p))
    (if (seq @problems)
      (fail! (str "Build matrix problems:\n  " (str/join "\n  " @problems)) {:problems @problems})
      (println (format "Build matrix OK: %d stages, %d bundles, %d targets, %d editions."
                       (count stages) (count bundles) (count targets) (count editions))))))

;; ---------------------------------------------------------------------------
;; JVM builds

(defn clean [_] ((tb 'delete) {:path "target"}))

(defn project-dirs
  "The project's own folders in `basis`: its :paths and the :extra-paths of
  the aliases in play, sorted. (:paths basis) alone misses the second kind,
  which is where components keep their assets and native-image metadata
  (web/resources, desktop/resources, an edition's resources). Libraries,
  local ones included, reach the uberjar through tools.build's `uber`."
  [basis]
  (vec (sort (for [[path {:keys [path-key]}] (:classpath basis) :when path-key] path))))

(defn uber
  "AOT-compiled, direct-linked uberjar for a target and edition."
  [opts]
  (let [{:keys [aliases main artifact]} (resolve-target opts)
        basis     ((tb 'create-basis) {:aliases aliases})
        class-dir (str "target/classes/" artifact)
        jar       (str "target/" artifact ".jar")]
    ((tb 'delete) {:path class-dir})
    ((tb 'copy-dir) {:src-dirs (project-dirs basis) :target-dir class-dir :include "**/{*.json,*.edn,*.html,*.css,*.js,*.der,*.ttf,*.png,*.svg,*.txt}"})
    ((tb 'compile-clj) {:basis        basis
                        :class-dir    class-dir
                        :ns-compile   [main]            ; transitive: everything main requires
                        :compile-opts {:direct-linking true
                                       :elide-meta     [:doc :file :line :added]}})
    ((tb 'uber) {:basis basis :class-dir class-dir :uber-file jar :main main})
    (println "Built" jar)
    jar))

(defn- windows? [] (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows"))

(defn- native-image-bin []
  (let [home (or (System/getenv "GRAALVM_HOME")
                 (fail! "Set GRAALVM_HOME to a GraalVM 25 (Community) installation."))]
    (str home (if (windows?) "\\bin\\native-image.cmd" "/bin/native-image"))))

(defn- linux? [] (str/starts-with? (str/lower-case (System/getProperty "os.name")) "linux"))

(defn native-image-env
  "Environment for the native-image process. The image keeps the charset it
  was built with for paths, arguments and the environment (sun.jnu.encoding,
  oracle/graal#10237), and a JVM derives that charset from the locale: a Linux
  build in a POSIX locale (containers, CI) makes a binary that can't open
  `vidéo/clip.mp4` whatever the user's locale. So Linux builds run in
  C.UTF-8. macOS always uses UTF-8; Windows is covered by CI's
  non-ASCII smoke test."
  []
  (if (linux?) {"LC_ALL" "C.UTF-8"} {}))

(defn windows-link-options
  "Extra native-image options on Windows: embed wmark/utf8.manifest, which
  makes UTF-8 the process code page. The C runtime passes arguments in the
  process code page and the image decodes them as UTF-8, so without it a
  Windows binary received `vidéo\\clip é.mp4` as `vid?o\\clip ?.mp4`
  (Windows 10 1903 or later)."
  []
  (let [f (io/file "target/utf8.manifest")]
    (io/make-parents f)
    (spit f (slurp (io/resource "wmark/utf8.manifest")))
    ["-H:NativeLinkerOption=/MANIFEST:EMBED"
     (str "-H:NativeLinkerOption=/MANIFESTINPUT:" (.getAbsolutePath f))]))

(declare check-graalvm!)

(defn native
  "Uberjar -> single native binary for the current OS/arch -> target/bin/."
  [opts]
  (let [{:keys [artifact native-image] :as t} (resolve-target opts)
        _   (when-not native-image (fail! (str (:target t) " is not built as a native image")))
        _   (check-graalvm!)
        jar (uber opts)
        _   (.mkdirs (io/file "target/bin"))
        out (str "target/bin/" artifact)                ; native-image appends .exe on Windows
        {:keys [exit]} ((tb 'process) {:command-args (concat [(native-image-bin) "-jar" jar "-o" out] native-image
                                                             (when (windows?) (windows-link-options)))
                                       :env          (native-image-env)})]
    (when-not (zero? exit) (fail! "native-image failed" {:exit exit}))
    (println "Built" out)))

;; ---------------------------------------------------------------------------
;; FFmpeg: the pinned sidecar binaries

(defn- sha256 [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream f)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n) (.update md buf 0 n) (recur)))))
    (.formatHex (HexFormat/of) (.digest md))))

(defn platform
  "This machine as a key of the matrix's :ffmpeg :platforms, e.g. :linux-x64,
  :windows-x64, :macos-arm64."
  []
  (let [os   (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))]
    (keyword (str (cond (str/starts-with? os "windows") "windows"
                        (str/starts-with? os "mac")     "macos"
                        :else                           "linux")
                  (if (#{"aarch64" "arm64"} arch) "-arm64" "-x64")))))

(defn graalvm-version
  "The release `native-image --version` names, e.g. \"25.0.1\"."
  [version-output]
  (second (re-find #"(?m)^native-image\s+(\d+(?:\.\d+)*)" (str version-output))))

(defn- check-graalvm!
  "Fail unless GRAALVM_HOME is the release the matrix pins for this platform
  (:graalvm, e.g. macOS x64 on 25.0.1). Unpinned platforms take any 25.x."
  []
  (when-let [want (get-in (matrix*) [:graalvm (platform)])]
    (let [^java.util.List argv [(native-image-bin) "--version"]
          p    (.start (doto (ProcessBuilder. argv) (.redirectErrorStream true)))
          have (graalvm-version (slurp (.getInputStream p)))]
      (.waitFor p)
      (when-not (= want have)
        (fail! (str "Native builds for " (name (platform)) " need GraalVM " want
                    " (deps.edn :graalvm), but GRAALVM_HOME has " (or have "an unknown release")
                    ". GraalVM 25.0.2 dropped macOS x64, so Intel Macs stay on 25.0.1."))))))

(defn- exe-on [platform name]
  (if (str/starts-with? (clojure.core/name platform) "windows") (str name ".exe") name))

(defn- download!
  "Fetch `url` into `dest` unless a file with `sha` is already there; fail
  unless the result has that SHA-256."
  [url sha ^File dest]
  (when-not (and (.isFile dest) (= sha (sha256 dest)))
    (io/make-parents dest)
    (println "Downloading" url)
    (let [part    (io/file (str dest ".part"))
          ^HttpClient client (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NORMAL) (.build))
          ^HttpResponse resp (.send client (.build (HttpRequest/newBuilder (URI. url)))
                                    (HttpResponse$BodyHandlers/ofFile (.toPath part)))]
      (when-not (= 200 (.statusCode resp))
        (fail! (str "HTTP " (.statusCode resp) " for " url)))
      (Files/move (.toPath part) (.toPath dest) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
  (let [actual (sha256 dest)]
    (when-not (= sha actual)
      (.delete dest)
      (fail! (str "SHA-256 mismatch for " url ": expected " sha ", got " actual) {:url url})))
  dest)

(defn- extract!
  "Copy the files of `archive` (.zip or .tar.xz) whose names are in `wanted`
  into `dir`, flattening their paths; returns the names found."
  [^File archive wanted ^File dir]
  (.mkdirs dir)
  (let [n (.getName archive)]
    (cond
      (str/ends-with? n ".zip")
      (with-open [zin (ZipInputStream. (io/input-stream archive))]
        (loop [found #{}]
          (if-let [e (.getNextEntry zin)]
            (let [base (last (str/split (.getName e) #"/"))]
              (if (and (not (.isDirectory e)) (wanted base))
                (do (io/copy zin (io/file dir base)) (recur (conj found base)))
                (recur found)))
            found)))

      (str/ends-with? n ".tar.xz")
      (let [tmp (io/file (str archive ".d"))]
        (.mkdirs tmp)
        (let [^java.util.List argv ["tar" "-xJf" (str archive) "-C" (str tmp)]
              p (-> (ProcessBuilder. argv) (.inheritIO) (.start))]
          (when-not (zero? (.waitFor p)) (fail! (str "tar failed on " archive))))
        (into #{} (for [^File f (file-seq tmp) :when (and (.isFile f) (wanted (.getName f)))]
                    (do (io/copy f (io/file dir (.getName f))) (.getName f)))))

      :else (fail! (str "Unknown archive type: " n)))))

(defn- source-note [platform {:keys [version archives build source]} {:keys [spdx] :as license}]
  (str/join "\n"
            (concat [(str "FFmpeg " version " for " (name platform) ": bin/ffmpeg and bin/ffprobe in this download.")
                     ""
                     "wmark runs FFmpeg as a separate program. FFmpeg is not part of wmark; it is"
                     (str "licensed under " spdx " (" (str/join ", " (map :file (license-texts license))) ", next to this file).")
                     ""]
                    (if build
                      (concat ["The binaries were built from this source archive (SHA-256 verified):"
                               (str "  " (get-in build [:source :url]) "\n    sha256 " (get-in build [:source :sha256]))
                               ""
                               "with nothing changed, by:"
                               (str "  ./configure " (str/join " " (:configure build)))
                               "  make"])
                      (cons "The binaries come unmodified from these archives (SHA-256 verified):"
                            (for [{:keys [url sha256]} archives] (str "  " url "\n    sha256 " sha256))))
                    ["" "Source code of this build:"]
                    (map #(str "  " %) source)
                    [""])))

(defn- run-in!
  "Run `argv` in `dir`, its output on this console; fail unless it exits 0."
  [^File dir argv]
  (let [^java.util.List argv (mapv str argv)
        p (-> (ProcessBuilder. argv) (.directory dir) (.inheritIO) (.start))]
    (when-not (zero? (.waitFor p))
      (fail! (str (str/join " " (take 2 argv)) " failed in " dir)))))

(defn- output-of
  "Everything `argv` prints (stdout and stderr); fails when it can't run."
  ^String [argv]
  (let [^java.util.List argv (mapv str argv)
        ^Process p (try (.start (doto (ProcessBuilder. argv) (.redirectErrorStream true)))
                 (catch java.io.IOException e
                   (fail! (str "Can't run " (first argv) ": " (ex-message e)))))
        out (slurp (.getInputStream p))]
    (.waitFor p)
    out))

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(defn- build-from-source!
  "Build ffmpeg and ffprobe from a pin's :build recipe (its source archive,
  SHA-256 verified, then ./configure with its flags and make) into `bin`;
  returns the names built. Only for this machine's platform, and once per
  recipe: the result is cached next to the download."
  [p {:keys [build]} ^File cache ^File bin]
  (when (not= p (platform))
    (fail! (str "FFmpeg for " (name p) " is built from source, which only works on " (name p) " itself.")))
  (let [{:keys [source configure]} build
        ^File archive (download! (:url source) (:sha256 source) (io/file cache (last (str/split (:url source) #"/"))))
        md      (MessageDigest/getInstance "SHA-256")
        recipe  (subs (.formatHex (HexFormat/of) (.digest md (.getBytes (pr-str build) "UTF-8"))) 0 16)
        work    (io/file cache (str "build-" recipe))
        out     (io/file cache (str "built-" recipe))
        names   ["ffmpeg" "ffprobe"]]
    (when-not (every? #(.isFile (io/file out %)) names)
      (delete-tree! work)
      (.mkdirs work)
      (println "Building FFmpeg" (:url source) "for" (name p))
      (run-in! work ["tar" "-xJf" (.getAbsolutePath archive) "--strip-components=1"])
      (run-in! work (into ["./configure"] configure))
      (run-in! work ["make" (str "-j" (.availableProcessors (Runtime/getRuntime)))])
      (.mkdirs out)
      (doseq [n names] (io/copy (io/file work n) (io/file out n)))
      (delete-tree! work))
    (.mkdirs bin)
    (doseq [n names]
      (io/copy (io/file out n) (io/file bin n))
      (.setExecutable (io/file bin n) true))
    (set names)))

(defn- check-build!
  "Fail unless the ffmpeg in `bin` is what its pin says: it states the pinned
  license (`ffmpeg -L`), an LGPL build has no GPL or nonfree parts configured
  in, and on macOS it links nothing but the OS. Only on this machine's
  platform, where the binary runs."
  [p ^File bin {:keys [spdx]}]
  (when (= p (platform))
    (let [ff      (str (io/file bin (exe-on p "ffmpeg")))
          license (output-of [ff "-hide_banner" "-L"])
          config  (str (second (re-find #"configuration: (.*)" (output-of [ff "-hide_banner" "-version"]))))
          lgpl?   (str/starts-with? (str spdx) "LGPL")
          says    (if lgpl?
                    (str/includes? license "GNU Lesser General Public License")
                    (and (str/includes? license "GNU General Public License") (not (str/includes? license "Lesser"))))]
      (when-not says
        (fail! (str ff " doesn't state the pinned license " spdx " (ffmpeg -L): " (str/trim license))))
      (when-let [flags (and lgpl? (seq (filter #(str/includes? config %) ["--enable-gpl" "--enable-nonfree"])))]
        (fail! (str ff " is pinned as " spdx " but was configured with " (str/join " " flags))))
      (when (str/starts-with? (name p) "macos")
        (let [libs    (for [line (rest (str/split-lines (output-of ["otool" "-L" ff])))
                            :let [lib (first (str/split (str/trim line) #"\s+"))]
                            :when (seq lib)]
                        lib)
              foreign (remove #(or (str/starts-with? % "/usr/lib/") (str/starts-with? % "/System/Library/")) libs)]
          (when (seq foreign)
            (fail! (str ff " links libraries that aren't part of macOS, so the download would be incomplete "
                        "and its license unclear: " (str/join ", " foreign)))))))))

(defn ffmpeg
  "Fetch the pinned FFmpeg build for this platform (or :platform) and verify
  it against its SHA-256, or build it from its pinned source recipe
  (:build, only on the platform itself):

    <out>/<platform>/bin/ffmpeg(.exe), bin/ffprobe(.exe)
    <out>/<platform>/licenses/<license texts>, SOURCE.txt

  On this machine's platform it then checks the binary against its pin
  (check-build!: the license it states, no GPL parts in an LGPL build, no
  libraries outside macOS). :variant :gpl fetches that pin instead, into
  <out>/<platform>-gpl. :out defaults to target/ffmpeg; downloads and builds
  are cached in target/downloads. Pass the platform folder to `bundle` as
  :ffmpeg-dir."
  [{:keys [out variant] :as opts :or {out "target/ffmpeg"}}]
  (let [pins  (or (:ffmpeg (matrix*)) (fail! "deps.edn has no :ffmpeg pins in its build matrix"))
        {:keys [license platforms]} (if variant
                                      (or (get-in pins [:variants (kw variant)])
                                          (fail! (str "No FFmpeg variant " variant "; pinned: " (vec (keys (:variants pins))))))
                                      pins)
        p     (or (kw (:platform opts)) (platform))
        pin   (or (get platforms p) (fail! (str "No FFmpeg pinned for " p "; pinned: " (vec (keys platforms)))))
        dir   (io/file (str out) (str (name p) (when variant (str "-" (name (kw variant))))))
        cache (io/file "target/downloads" (name p))
        want  #{(exe-on p "ffmpeg") (exe-on p "ffprobe")}
        found (if (:build pin)
                (build-from-source! p pin cache (io/file dir "bin"))
                (reduce (fn [found {:keys [url sha256]}]
                          (into found (extract! (download! url sha256 (io/file cache (last (str/split url #"/"))))
                                                want (io/file dir "bin"))))
                        #{} (:archives pin)))]
    (when-let [missing (seq (remove found want))]
      (fail! (str "The pinned archives for " p " lack " (vec missing))))
    (doseq [f want] (.setExecutable (io/file dir "bin" f) true))
    (doseq [{:keys [file url sha256]} (license-texts license)]
      (io/copy (download! url sha256 (io/file "target/downloads" file))
               (doto (io/file dir "licenses" file) io/make-parents)))
    (spit (io/file dir "licenses" "SOURCE.txt") (source-note p pin license))
    (check-build! p (io/file dir "bin") license)
    (println "FFmpeg" (:version pin) "for" (name p) "(" (:spdx license) ") in" (str dir))
    (str dir)))

;; ---------------------------------------------------------------------------
;; Third-party notices, from the resolved dependencies

(defn- pom-of
  "The POM next to a jar in the local Maven repository, or nil."
  ^File [^String jar]
  (let [f (io/file (str/replace jar #"\.jar$" ".pom"))] (when (.isFile f) f)))

(defn- tag [xml t] (some-> (re-find (re-pattern (str "(?s)<" t ">\\s*(.*?)\\s*</" t ">")) xml) second))

(defn pom-licenses
  "[{:name :url}] from a POM's <licenses>, following <parent> POMs through
  the local repository `repo` (Clojure's contrib libraries inherit theirs)."
  [repo ^File pom]
  (loop [^File pom pom depth 0]
    (when (and pom (.isFile pom) (< depth 6))
      (let [xml   (slurp pom)
            found (for [l (map second (re-seq #"(?s)<license>(.*?)</license>" xml))]
                    {:name (tag l "name") :url (tag l "url")})]
        (if (seq found)
          (vec found)
          (when-let [p (tag xml "parent")]
            (let [g (tag p "groupId") a (tag p "artifactId") v (tag p "version")]
              (when (and g a v)
                (recur (io/file repo (str/replace g "." "/") a v (str a "-" v ".pom")) (inc depth))))))))))

(defn- local-repo
  "The Maven repository root that holds `jar` of library `lib`
  (<repo>/<group path>/<artifact>/<version>/<jar>)."
  ^File [lib ^String jar]
  (nth (iterate #(some-> ^File % .getParentFile) (io/file jar))
       (+ 3 (count (str/split (or (namespace lib) (name lib)) #"\.")))))

(defn git-license
  "The SPDX-License-Identifier at the top of the first Clojure source under a
  git dependency's source folder, e.g. \"EPL-2.0\" for clogem-wmark's
  components."
  [dir]
  (let [d (io/file (str dir))]
    (when (.isDirectory d)
      (some (fn [^File f]
              (when (and (.isFile f) (re-find #"\.clj[cd]?$" (.getName f)))
                (with-open [r (io/reader f)]
                  (some #(second (re-find #"SPDX-License-Identifier:\s*([A-Za-z0-9.+\-]+)" %))
                        (take 5 (line-seq r))))))
            (file-seq d)))))

(defn- embedded-notices
  "Texts of LICENSE, NOTICE and COPYING files inside a jar."
  [^String jar]
  (with-open [z (ZipFile. jar)]
    (vec (for [^ZipEntry e (enumeration-seq (.entries z))
               :when (and (not (.isDirectory e))
                          (re-find #"(?i)^(META-INF/)?(LICENSE|NOTICE|COPYING)[^/]*$" (.getName e)))]
           [(.getName e) (slurp (.getInputStream z e) :encoding "UTF-8")]))))

(defn notices-text
  "THIRD-PARTY notices for a basis: each library, its version and declared
  license, and the license and notice files it ships. Libraries without a
  version (local roots) are part of the project itself."
  [basis title]
  (let [libs (sort-by (comp str key) (:libs basis))]
    (str title "\n" (apply str (repeat (count title) "=")) "\n\n"
         "Libraries included in this program, with the licenses their publishers\n"
         "declare. Their source code is published with each library (Maven Central,\n"
         "Clojars or the git repository named).\n\n"
         (str/join
          "\n"
          (for [[lib {:keys [mvn/version git/url git/sha paths]}] libs
                :when (or version url)
                :let [jar  (first (filter #(str/ends-with? (str %) ".jar") paths))
                      lics (when jar (pom-licenses (local-repo lib jar) (pom-of jar)))
                      spdx (when url (some-> (first paths) git-license))]]
            (str "-- " lib " " (or version (str url " " sha)) "\n"
                 (cond
                   (seq lics) (str/join "" (for [{n :name u :url} lics] (str "   License: " n (when u (str " <" u ">")) "\n")))
                   url        (str "   License: " (or spdx "see the repository") "\n"
                                   "   Source: " (str/replace url #"\.git$" "") "/tree/" sha "\n")
                   :else      "   License: see the library's own files below or its repository\n")
                 (str/join "" (for [[entry text] (some-> jar embedded-notices)]
                                (str "\n   " entry ":\n" (str/join "\n" (map #(str "   | " %) (str/split-lines text))) "\n")))))))))

(defn notices
  "Write the third-party notices of a target to target/notices/<artifact>.txt."
  [opts]
  (let [{:keys [aliases artifact]} (resolve-target opts)
        f (io/file "target/notices" (str artifact ".txt"))]
    (io/make-parents f)
    (spit f (notices-text ((tb 'create-basis) {:aliases aliases})
                          (str "Third-party notices for " artifact)))
    (println "Wrote" (str f))
    (str f)))

;; ---------------------------------------------------------------------------
;; Bundles: what a user downloads

(defn- exe [name] (if (windows?) (str name ".exe") name))

(defn- target-edition
  "The edition of `t` that goes into a bundle of `edition`. A target that comes
  in one edition only (a tool built the same for every edition) is
  edition-independent; any other must offer
  the edition asked for, so a Pro bundle can never pick up a community engine."
  [{:keys [editions]} edition]
  (cond (nil? edition)                edition
        (some #{edition} editions)    edition
        (= 1 (count editions))        (first editions)
        :else                         edition))

(defn- license-files
  "LICENSE, NOTICE and everything under licenses/ at the project root: what a
  binary distribution must carry (EPL-2.0 section 3.1 for the open core; the
  third-party notices for the rest)."
  []
  (concat (filter #(.isFile ^File %) [(io/file "LICENSE") (io/file "NOTICE")])
          (when (.isDirectory (io/file "licenses"))
            (sort-by str (filter #(.isFile ^File %) (file-seq (io/file "licenses")))))))

(defn- sidecar-dirs
  "The folder holding the sidecar executables, and the one holding their
  license files: either `ffmpeg-dir` itself (a plain FFmpeg folder) or its
  bin/ and licenses/ (what the `ffmpeg` task writes)."
  [ffmpeg-dir]
  (let [d (io/file (str ffmpeg-dir))                  ; -T passes unquoted paths as symbols
        bin (io/file d "bin")]
    [(if (.isDirectory bin) bin d)
     (let [l (io/file d "licenses")] (when (.isDirectory l) l))]))

(defn bundle
  "Assemble dist/<bundle>/ for this OS from binaries already built with
  `native` (target/bin/) and FFmpeg sidecars from :ffmpeg-dir (a folder with
  the executables, or what `clojure -T:build ffmpeg` wrote):

    wmark(.exe)  bin/ffmpeg(.exe)  bin/ffprobe(.exe)
    licenses/    (LICENSE, NOTICE, licenses/*, THIRD-PARTY-<artifact>.txt,
                  ffmpeg/COPYING.GPLv3 and ffmpeg/SOURCE.txt)
    SHA256SUMS   README.txt

  wmark finds bin/ffmpeg next to itself (see watermark.util.locate). GUI and
  container targets are built by their own toolchains; this reports them."
  [{:keys [bundle ffmpeg-dir edition out] :or {out "dist"}}]
  (let [m       (matrix*)
        b       (or (kw bundle) (fail! (str "Pass :bundle, one of " (vec (keys (:bundles m))))))
        {:keys [targets sidecars doc]} (or (get-in m [:bundles b]) (fail! (str "Unknown bundle " b)))
        dir     (io/file (str out) (name b))
        copied  (atom [])
        put!    (fn [^File src ^File dest]
                  (io/make-parents dest)
                  (io/copy src dest)
                  (swap! copied conj dest)
                  dest)]
    (.mkdirs (io/file dir "bin"))
    (doseq [t targets]
      (let [{:keys [toolchain package commands] :as target} (get-in m [:targets t])]
        (cond
          (and (= :jvm toolchain) (not package))
          (let [opts {:target t :edition (target-edition target (kw edition))}
                {:keys [artifact]} (resolve-target opts)
                src (io/file "target/bin" (exe artifact))]
            (when-not (.isFile src)
              (fail! (str "Missing " src ": run  clojure -T:build native :target " t
                          (when edition (str " :edition " edition)))))
            (.setExecutable ^File (put! src (io/file dir (exe artifact))) true)
            (put! (io/file (notices opts)) (io/file dir "licenses" (str "THIRD-PARTY-" artifact ".txt"))))

          :else
          (println (str "  " (name t) ": built separately (" (name toolchain) ") -- " (pr-str (or commands package)))))))
    (when (seq sidecars)
      (let [[bin lic] (sidecar-dirs (or ffmpeg-dir (fail! "Pass :ffmpeg-dir: run  clojure -T:build ffmpeg  and pass target/ffmpeg/<platform>")))]
        (doseq [s sidecars]
          (let [src (io/file bin (exe (name s)))]
            (when-not (.isFile src) (fail! (str "Missing sidecar " src)))
            (.setExecutable ^File (put! src (io/file dir "bin" (exe (name s)))) true)))
        (if lic
          (doseq [^File f (sort-by str (.listFiles ^File lic)) :when (.isFile f)]
            (put! f (io/file dir "licenses" "ffmpeg" (.getName f))))
          (println "  WARNING: no FFmpeg license files next to" (str bin)
                   "-- add FFmpeg's license and source notes to licenses/ffmpeg/ before shipping"))))
    (doseq [^File f (license-files)]
      (put! f (io/file dir "licenses" (.getName f))))
    (spit (io/file dir "SHA256SUMS")
          (apply str (for [^File f (sort-by str @copied)]
                       (str (sha256 f) "  " (str/replace (str (.relativize (.toPath dir) (.toPath f))) "\\" "/") "\n"))))
    (spit (io/file dir "README.txt")
          (str "wmark -- " doc "\n\nStart: double-click " (exe "wmark") " (or run it from a terminal).\n"
               "FFmpeg is included in bin/; `" (exe "wmark") " doctor` shows what was found.\n"
               "Licenses and notices: licenses/ (FFmpeg's in licenses/ffmpeg/).\n"))
    (println "Bundled" (str dir) (count @copied) "files")
    (str dir)))

;; ---------------------------------------------------------------------------
;; Engine SDK: what a native engine (Swift, Kotlin, Rust) or a kernel port
;; builds against, released on abi-v* and kernel-v* tags

(def sdk-files
  "[source destination] pairs of the engine SDK archive."
  [["native/include/wmark_engine.h"   "include/wmark_engine.h"]
   ["native/mock/mock_engine.c"       "mock/mock_engine.c"]
   ["native/render-spec.schema.json"  "schemas/render-spec.schema.json"]
   ["native/render-spec-v2.schema.json" "schemas/render-spec-v2.schema.json"]
   ["native/settings.schema.json"     "schemas/settings.schema.json"]
   ["kernel/test/golden/prng.edn"     "golden/prng.edn"]
   ["kernel/test/golden/seeds.edn"    "golden/seeds.edn"]
   ["kernel/test/golden/render-basic.edn" "golden/render-basic.edn"]
   ["kernel/test/golden/render-v2.edn" "golden/render-v2.edn"]
   ["kernel/test/golden/schema.edn"   "golden/schema.edn"]
   ["kernel/test/golden/form.edn"     "golden/form.edn"]
   ["resources/fonts/wmark.ttf"       "golden/fonts/wmark.ttf"]     ; render-v2's text
   ["licenses/FiraSans-OFL.txt"       "golden/fonts/OFL.txt"]
   ["native/README.md"                "README.md"]
   ["docs/ENGINE.md"                  "ENGINE.md"]
   ["LICENSE"                         "LICENSE"]
   ["NOTICE"                          "NOTICE"]])

(defn abi-version
  "WMARK_ENGINE_ABI_VERSION as written in the header."
  []
  (some->> (slurp "native/include/wmark_engine.h")
           (re-find #"#define\s+WMARK_ENGINE_ABI_VERSION\s+(\d+)")
           second parse-long))

(defn sdk
  "Zip the engine SDK as dist/wmark-engine-sdk-<name>.zip. An abi-vN name
  must match the header's WMARK_ENGINE_ABI_VERSION."
  [{:keys [name] :or {name "dev"}}]
  (let [name (str name)
        n    (some->> name (re-matches #"abi-v(\d+)") second parse-long)
        _    (when (and n (not= n (abi-version)))
               (fail! (str name " doesn't match WMARK_ENGINE_ABI_VERSION " (abi-version) " in the header")))
        base (str "wmark-engine-sdk-" name)
        zip  (io/file "dist" (str base ".zip"))]
    (io/make-parents zip)
    (with-open [out (java.util.zip.ZipOutputStream. (io/output-stream zip))]
      (doseq [[src dest] sdk-files]
        (let [f (io/file src)]
          (when-not (.isFile f) (fail! (str "SDK input missing: " src)))
          (.putNextEntry out (ZipEntry. (str base "/" dest)))
          (io/copy f out)
          (.closeEntry out))))
    (println "Wrote" (str zip))
    (str zip)))

