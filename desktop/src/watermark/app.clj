;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.app
  "Desktop entry point: system assembly and the CLI.

  watermark.main (community) and watermark.pro.main (Pro) differ only in the
  `edition` map they pass to `run-cli`:
    {:edition :community|:pro
     :entitlements-fn (fn [home] entitlements)
     :allowance-fn (optional) (fn [home opts] allowance-or-nil): a render
                   allowance for this run (docs/adr/0017); `opts` are the
                   command's, so an edition can limit only some runs
     :license-cmd (optional, Pro) (fn [home args] exit-code)}

  Modes: no arguments (double-click) = `ui`: serve on loopback + open the
  browser. `serve` is the same without the browser, for scripts and GUI
  shells (--announce, --parent-pid). `run` encodes from the command line
  through the same Core API, with a progress bar on a terminal. `profiles`
  manages profiles and shows what a run would use (`effective`). `doctor`
  shows how the engine was resolved.

  The command line itself (options, commands, what they print) is the core
  library's (watermark.cli), shared with the Dart VM's wmark-dart; this is
  the JVM's host for it, and its own commands: ui, serve and license."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [watermark.cli :as cli]
            [watermark.cli.opts :as opts]
            [watermark.home :as home]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.core.jobs.local :as local-jobs]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.engine.native :as native]
            [watermark.files.local :as local-files]
            [watermark.media.local :as local-media]
            [watermark.raster.local :as raster-local]
            [watermark.server.http :as http]
            [watermark.util.fs :as fs]
            [watermark.util.num :as number]
            [watermark.util.os :as os])
  (:import (java.lang ProcessHandle)
           (java.nio.file Path)
           (java.util.concurrent TimeUnit)))

(set! *warn-on-reflection* true)

(def version
  "This build's version, from the resource wmark/version.txt: the release
  workflow stamps it with the release's version before building. Read when
  the namespace loads, so a native image keeps the version it was built with."
  (or (some-> (io/resource "wmark/version.txt") slurp str/trim not-empty) "unknown"))

;; ---------------------------------------------------------------------------
;; System

(defn make-engine
  "The render engine for this run: FFmpeg by default; the native engine (a
  platform library behind the C ABI) when asked for."
  [{:keys [engine ffmpeg ffmpeg-search native-lib]} ^Path home]
  (case (or engine "ffmpeg")
    "ffmpeg" (ffmpeg/ffmpeg-engine {:ffmpeg    (or ffmpeg (System/getenv "WMARK_FFMPEG"))
                                    :search    (cli/search-order (or ffmpeg-search (System/getenv "WMARK_FFMPEG_SEARCH")))
                                    :work-root (str (.resolve home "work"))})
    "native" (native/native-engine {:library (or native-lib (System/getenv "WMARK_ENGINE_LIB"))})
    (throw (ex-info (str "Unknown engine: " engine " (use ffmpeg or native)") {:wmark/error :invalid}))))

(defn system
  "Everything the Core API needs. Built at run time, never at build time."
  [edition opts]
  (let [^Path home (home/resolve-home opts)
        store      (home/file-store {:home (str home)})
        secret     (delay (fs/studio-secret! home))]
    {:edition      (:edition edition)
     :version      version
     :home         home
     :profiles-for (constantly store)            ; SaaS: (fn [ctx] (tenant-store ctx))
     :entitlements ((:entitlements-fn edition) home)
     :allowance    (when-let [f (:allowance-fn edition)] (f home opts))
     :engine       (make-engine opts home)
     :media        (local-media/local-media)
     :rasterizer   (raster-local/local-rasterizer {:work-root (str (.resolve home "work"))})
     :spec-version (:render-spec opts)             ; nil: v1 where the engine takes it
     :secret-for   (fn [_ctx] @secret)            ; SaaS: per-tenant secret
     :font         (delay (os/default-font (.resolve home "cache")))
     :preview-dir  (str (.resolve home "work/previews"))  ; SaaS: object storage (M4)
     :files        (local-files/local-files)}))

(defn with-jobs [sys]
  (let [n (if (features/entitled? (:entitlements sys) :jobs/parallel)
            (max 1 (quot (.availableProcessors (Runtime/getRuntime)) 4))
            1)]
    (assoc sys :jobs (local-jobs/local-queue sys {:concurrency n}))))

;; ---------------------------------------------------------------------------
;; Options of the JVM's own commands (the rest are watermark.cli's)

(def serve-options
  [[nil "--host HOST" "Interface to bind" :default "127.0.0.1"]
   [nil "--port PORT" "Port, 0 = any free port" :default 0
    :parse-fn number/parse-int :validate [some? "must be a number"]]
   [nil "--ui-dir DIR" "Serve the web UI from this directory (external UI)"]
   [nil "--no-browser" "Don't open a browser"]
   [nil "--announce FORMAT" "Print the endpoint as one line for a parent process (json)"]
   [nil "--parent-pid PID" "Exit when this process exits (for GUI shells running wmark as a sidecar)"
    :parse-fn number/parse-int :validate [some? "must be a number"]]])

;; ---------------------------------------------------------------------------
;; Commands

(defn watch-parent!
  "Call `on-exit` (default: exit) when process `pid` -- the GUI shell that
  started this server -- ends, so a crashed or force-quit GUI never leaves an
  orphaned server behind. A parent that is already gone counts as ended."
  ([pid] (watch-parent! pid (fn [] (System/exit 0))))
  ([pid on-exit]
   (if-let [parent (.orElse (ProcessHandle/of (long pid)) nil)]
     (.thenRun (.onExit ^ProcessHandle parent) ^Runnable on-exit)
     (on-exit))))

(defn end-descendants!
  "End every process under `root` (default: this one), children and theirs:
  FFmpeg during a render. Each is asked to stop first (SIGTERM; on Windows
  `destroy` already terminates), and whatever is still running after
  `grace-ms` is forced. Returns how many there were.

  A server that exits -- Ctrl+C, SIGTERM, or its parent gone (`--parent-pid`)
  -- would otherwise leave a render running on its own: FFmpeg keeps writing
  when the JVM that started it is gone."
  ([] (end-descendants! (ProcessHandle/current) 2000))
  ([^ProcessHandle root grace-ms]
   (let [procs    (vec (iterator-seq (.iterator (.descendants root))))
         deadline (+ (System/nanoTime) (* 1000000 (long grace-ms)))]
     (doseq [^ProcessHandle p procs] (.destroy p))
     (doseq [^ProcessHandle p procs]
       (let [left-ms (quot (- deadline (System/nanoTime)) 1000000)]
         (when (pos? left-ms)
           (try (.get (.orTimeout (.onExit p) left-ms TimeUnit/MILLISECONDS))
                (catch Exception _ nil)))
         (when (.isAlive p) (.destroyForcibly p))))
     (count procs))))

(defn- cmd-serve [sys opts open?]
  (let [sys (with-jobs sys)
        srv (http/start! sys opts)
        url (str (:url srv) "/?token=" (:token srv))]
    ;; on every exit the JVM sees (Ctrl+C, SIGTERM, the parent watch's exit):
    ;; renders first, so no FFmpeg outlives the server, then the listener
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (end-descendants!) (http/stop! sys srv))))
    (when-let [pid (:parent-pid opts)] (watch-parent! pid))
    (if (= "json" (:announce opts))
      ;; one machine-readable line for the process that launched us
      (do (println (json/write-str {:url (:url srv) :token (:token srv) :version version
                                    :edition (name (:edition sys))
                                    :pid (.pid (ProcessHandle/current))}
                                   :escape-slash false))
          (flush))
      (do (println (str "wmark " version " (" (name (:edition sys)) ") is running."))
          (println (str "  Open:     " url))
          (println (str "  Profiles: " (.resolve ^Path (:home sys) "profiles")))
          (println "  Stop with Ctrl+C, or close this window.")
          (when (and open? (not (os/open-url! url)))
            (println "  (Couldn't open a browser automatically; open the link above.)"))))
    @(promise)))

(defn- terminal?
  "Is standard output an interactive terminal? (JDK 22+: System.console()
  exists even when output is redirected; isTerminal tells them apart.)"
  []
  (try (boolean (some-> (System/console) (.isTerminal)))
       (catch Throwable _ false)))

(defn- read-text
  "A UTF-8 file's text, for --text-file."
  [path]
  (let [f (io/file (str path))]
    (when-not (.isFile f)
      (throw (ex-info (str "No such text file: " path) {:wmark/error :invalid :path (str path)})))
    (slurp f :encoding "UTF-8")))

(defn- serve-cmd [sys args open?]
  (let [{o :options e :errors} (opts/parse-opts args serve-options)]
    (if e
      (do (binding [*out* *err*] (println (str/join "\n" e))) 2)
      (cmd-serve sys o open?))))

(defn host
  "The JVM's host map for watermark.cli: this edition's system, the
  terminal, and the commands only the JVM has (ui, serve, license)."
  [edition]
  {:program         "wmark"
   :version         version
   :edition         (:edition edition)
   :system          #(system edition %)
   :default-command "ui"
   :commands        {"ui"      (fn [sys args] (serve-cmd sys args true))
                     "serve"   (fn [sys args] (serve-cmd sys args false))
                     "license" (fn [sys args]
                                 (if-let [f (:license-cmd edition)]
                                   (f (:home sys) args)
                                   (do (println "Community edition: all open-source features are enabled.") 0)))}
   :usage-commands  ["ui" "serve" "run" "profiles" "doctor" "license" "version"]
   :command-help    {"ui"      ["ui" "Start and open the web UI (default when run without arguments)"]
                     "serve"   ["serve" "Start the local API/UI server without opening a browser"]
                     "license" ["license ..." "status | activate KEY (Pro)"]}
   :write           (fn [s] (print s) (flush))
   :write-err       (fn [s] (binding [*out* *err*] (print s) (flush)))
   :read-text       read-text
   :terminal?       terminal?
   :env             #(System/getenv ^String %)})

(defn run-cli
  "Parse `args`, run a command, return an exit code."
  [edition args]
  (api/await (cli/main (host edition) args)))
