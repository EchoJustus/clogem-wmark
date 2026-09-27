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
           (java.security MessageDigest)
           (java.util HexFormat)))

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
    (if (seq @problems)
      (fail! (str "Build matrix problems:\n  " (str/join "\n  " @problems)) {:problems @problems})
      (println (format "Build matrix OK: %d stages, %d bundles, %d targets, %d editions."
                       (count stages) (count bundles) (count targets) (count editions))))))

;; ---------------------------------------------------------------------------
;; JVM builds

(defn clean [_] ((tb 'delete) {:path "target"}))

(defn uber
  "AOT-compiled, direct-linked uberjar for a target and edition."
  [opts]
  (let [{:keys [aliases main artifact]} (resolve-target opts)
        basis     ((tb 'create-basis) {:aliases aliases})
        class-dir (str "target/classes/" artifact)
        jar       (str "target/" artifact ".jar")]
    ((tb 'delete) {:path class-dir})
    ((tb 'copy-dir) {:src-dirs (:paths basis) :target-dir class-dir :include "**/{*.json,*.edn,*.html,*.css,*.js,*.der,*.ttf,*.png,*.svg,*.txt}"})
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

(defn native
  "Uberjar -> single native binary for the current OS/arch -> target/bin/."
  [opts]
  (let [{:keys [artifact native-image] :as t} (resolve-target opts)
        _   (when-not native-image (fail! (str (:target t) " is not built as a native image")))
        jar (uber opts)
        _   (.mkdirs (io/file "target/bin"))
        out (str "target/bin/" artifact)                ; native-image appends .exe on Windows
        {:keys [exit]} ((tb 'process) {:command-args (concat [(native-image-bin) "-jar" jar "-o" out] native-image)})]
    (when-not (zero? exit) (fail! "native-image failed" {:exit exit}))
    (println "Built" out)))

;; ---------------------------------------------------------------------------
;; Bundles: what a user downloads

(defn- sha256 [^File f]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (java.nio.file.Files/readAllBytes (.toPath f)))))

(defn- exe [name] (if (windows?) (str name ".exe") name))

(defn- target-edition
  "The edition of `t` that goes into a bundle of `edition`. A target that comes
  in one edition only (the TUI) is edition-independent; any other must offer
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

(defn bundle
  "Assemble dist/<bundle>/ for this OS from binaries already built with
  `native` (target/bin/) and FFmpeg sidecars from :ffmpeg-dir:

    wmark(.exe)  wmark-tui(.exe)  bin/ffmpeg(.exe)  bin/ffprobe(.exe)
    licenses/    SHA256SUMS       README.txt

  wmark finds bin/ffmpeg next to itself (see watermark.util.locate). GUI and
  container targets are built by their own toolchains; this reports them."
  [{:keys [bundle ffmpeg-dir edition out] :or {out "dist"}}]
  (let [m       (matrix*)
        b       (or (kw bundle) (fail! (str "Pass :bundle, one of " (vec (keys (:bundles m))))))
        {:keys [targets sidecars doc]} (or (get-in m [:bundles b]) (fail! (str "Unknown bundle " b)))
        dir     (io/file out (name b))
        copied  (atom [])]
    (.mkdirs (io/file dir "bin"))
    (doseq [t targets]
      (let [{:keys [toolchain package commands] :as target} (get-in m [:targets t])]
        (cond
          (and (= :jvm toolchain) (not package))
          (let [{:keys [artifact]} (resolve-target {:target t :edition (target-edition target (kw edition))})
                src (io/file "target/bin" (exe artifact))]
            (when-not (.isFile src)
              (fail! (str "Missing " src ": run  clojure -T:build native :target " t
                          (when edition (str " :edition " edition)))))
            (io/copy src (io/file dir (exe artifact)))
            (.setExecutable (io/file dir (exe artifact)) true)
            (swap! copied conj (io/file dir (exe artifact))))

          :else
          (println (str "  " (name t) ": built separately (" (name toolchain) ") -- " (pr-str (or commands package)))))))
    (doseq [s sidecars]
      (let [src (io/file (or ffmpeg-dir (fail! "Pass :ffmpeg-dir with a full FFmpeg build (drawtext included).")) (exe (name s)))]
        (when-not (.isFile src) (fail! (str "Missing sidecar " src)))
        (io/copy src (io/file dir "bin" (exe (name s))))
        (.setExecutable (io/file dir "bin" (exe (name s))) true)
        (swap! copied conj (io/file dir "bin" (exe (name s))))))
    (doseq [^File f (license-files)]
      (let [dest (io/file dir "licenses" (.getName f))]
        (io/make-parents dest)
        (io/copy f dest)
        (swap! copied conj dest)))
    (spit (io/file dir "SHA256SUMS")
          (apply str (for [^File f @copied]
                       (str (sha256 f) "  " (str/replace (str (.relativize (.toPath dir) (.toPath f))) "\\" "/") "\n"))))
    (spit (io/file dir "README.txt")
          (str "wmark -- " doc "\n\nStart: double-click " (exe "wmark") " (or run it from a terminal).\n"
               "FFmpeg is included in bin/; `" (exe "wmark") " doctor` shows what was found.\n"
               "Licenses and notices: see licenses/. Add FFmpeg's license and source offer there before shipping.\n"))
    (println "Bundled" (str dir) (count @copied) "files")
    (str dir)))
