;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.app
  "Desktop entry point: system assembly and the CLI.

  watermark.main (community) and watermark.pro.main (Pro) differ only in the
  `edition` map they pass to `run-cli`:
    {:edition :community|:pro
     :entitlements-fn (fn [home] entitlements)
     :license-cmd (optional, Pro) (fn [home args] exit-code)}

  Modes: no arguments (double-click) = `ui`: serve on loopback + open the
  browser. `serve` is the same without the browser, for the TUI, scripts and
  GUI shells (--announce, --parent-pid). `run` encodes from the command line
  through the same Core API. `doctor` shows how the engine was resolved."
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [watermark.config :as config]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.core.jobs.local :as local-jobs]
            [watermark.engine.ffmpeg :as ffmpeg]
            [watermark.engine.native :as native]
            [watermark.media.local :as local-media]
            [watermark.server.http :as http]
            [watermark.util.fs :as fs]
            [watermark.util.os :as os])
  (:import (java.lang ProcessHandle)
           (java.nio.file Path)))

(set! *warn-on-reflection* true)

(def version "0.2.0-SNAPSHOT")

;; ---------------------------------------------------------------------------
;; System

(defn- search-order [s]
  (when s (mapv keyword (str/split s #"[,\s]+"))))

(defn make-engine
  "The render engine for this run: FFmpeg by default; the native engine (a
  platform library behind the C ABI) when asked for."
  [{:keys [engine ffmpeg ffmpeg-search native-lib]} ^Path home]
  (case (or engine "ffmpeg")
    "ffmpeg" (ffmpeg/ffmpeg-engine {:ffmpeg    (or ffmpeg (System/getenv "WMARK_FFMPEG"))
                                    :search    (search-order (or ffmpeg-search (System/getenv "WMARK_FFMPEG_SEARCH")))
                                    :work-root (str (.resolve home "work"))})
    "native" (native/native-engine {:library (or native-lib (System/getenv "WMARK_ENGINE_LIB"))})
    (throw (ex-info (str "Unknown engine: " engine " (use ffmpeg or native)") {:wmark/error :invalid}))))

(defn system
  "Everything the Core API needs. Built at run time, never at build time."
  [edition opts]
  (let [^Path home (config/resolve-home opts)
        store      (config/file-store {:home (str home)})
        secret     (delay (fs/studio-secret! home))]
    {:edition      (:edition edition)
     :version      version
     :home         home
     :profiles-for (constantly store)            ; SaaS: (fn [ctx] (tenant-store ctx))
     :entitlements ((:entitlements-fn edition) home)
     :engine       (make-engine opts home)
     :media        (local-media/local-media)
     :secret-for   (fn [_ctx] @secret)            ; SaaS: per-tenant secret
     :font         (delay (os/default-font (.resolve home "cache")))}))

(defn with-jobs [sys]
  (let [n (if (features/entitled? (:entitlements sys) :jobs/parallel)
            (max 1 (quot (.availableProcessors (Runtime/getRuntime)) 4))
            1)]
    (assoc sys :jobs (local-jobs/local-queue sys {:concurrency n}))))

;; ---------------------------------------------------------------------------
;; Options

(def global-options
  [[nil "--home DIR" "Data directory (default: WMARK_HOME, ./wmark-data, else the per-user dir)"]
   [nil "--engine NAME" "Render engine: ffmpeg (default) or native"]
   [nil "--ffmpeg PATH" "ffmpeg executable or folder (default: ./, ./bin/, wmark's folder, its bin/, PATH)"]
   [nil "--ffmpeg-search ORDER" "Where to look, e.g. app,app-bin,path to skip the working folder"]
   [nil "--native-lib PATH" "Native engine library (wmark_engine.dll / libwmark_engine.dylib / .so)"]
   ["-h" "--help" "Show help"]])

(def serve-options
  [[nil "--host HOST" "Interface to bind" :default "127.0.0.1"]
   [nil "--port PORT" "Port, 0 = any free port" :default 0
    :parse-fn parse-long :validate [some? "must be a number"]]
   [nil "--ui-dir DIR" "Serve the web UI from this directory (external UI)"]
   [nil "--no-browser" "Don't open a browser"]
   [nil "--announce FORMAT" "Print the endpoint as one line for a parent process (json)"]
   [nil "--parent-pid PID" "Exit when this process exits (for GUI shells running wmark as a sidecar)"
    :parse-fn parse-long :validate [some? "must be a number"]]])

(defn- num-opt [long-opt desc parse]
  [nil long-opt desc :parse-fn parse :validate [some? "must be a number"]])

(def settings-options
  [["-p" "--profile NAME" "Base profile (default: latest)"]
   [nil "--clean" "Ignore profiles; start from built-in defaults"]
   [nil "--logo PATH" "Logo image (PNG with transparency)"]
   [nil "--anchor POS" "top-left | top-center | top-right | center-left | center | ... | bottom-right"
    :parse-fn keyword]
   (num-opt "--offset-x PX" "Horizontal offset from the anchored edge" parse-long)
   (num-opt "--offset-y PX" "Vertical offset from the anchored edge" parse-long)
   (num-opt "--logo-width RATIO" "Logo width as a fraction of the frame width" parse-double)
   (num-opt "--opacity A" "Logo opacity, 0-1" parse-double)
   (num-opt "--flip-every SEC" "Seconds between logo flips" parse-double)
   (num-opt "--flip-duration SEC" "Duration of one flip" parse-double)
   [nil "--static-logo" "No flip animation"]
   [nil "--text TEXT" "Add a warning-text layer (replaces the profile's text layers)"]
   [nil "--text-file PATH" "Like --text, read as UTF-8 from a file (safe in any terminal locale)"
    :parse-fn #(slurp % :encoding "UTF-8")]
   [nil "--text-mode MODE" "continuous | scheduled | subliminal (Pro) | random (Pro)"
    :parse-fn keyword :default :continuous]
   [nil "--text-at SECONDS" "Comma-separated start times (scheduled mode)"
    :parse-fn (fn [s] (mapv parse-double (str/split s #",")))]
   (num-opt "--text-duration SEC" "How long each scheduled text shows" parse-double)
   ["-o" "--out DIR" "Output directory (default: next to each input)"]])

(def run-options (conj settings-options
                       [nil "--dry-run" "Print the FFmpeg command and filtergraph only"]
                       ["-h" "--help" "Show this help"]))

(def save-options (conj settings-options [nil "--overwrite" "Replace a profile whose name maps to the same file"]))

(defn overrides
  "CLI flags -> a settings overlay. Absent flags stay nil, so they fall
  through to the profile (or latest) during resolution."
  [o]
  {:logo   {:path        (:logo o)
            :anchor      (:anchor o)
            :offset      {:x (:offset-x o) :y (:offset-y o)}
            :width-ratio (:logo-width o)
            :opacity     (:opacity o)
            :animation   (cond
                           (:static-logo o) {:type :none}
                           (or (:flip-every o) (:flip-duration o))
                           {:type :flip-y :every-s (:flip-every o) :duration-s (:flip-duration o)})}
   :texts  (when-let [content (some-> (or (:text-file o) (:text o)) str/trim not-empty)]
             (when (str/includes? content "\uFFFD")
               (throw (ex-info (str "The text arrived with undecodable characters: this terminal's locale "
                                    "isn't UTF-8 (try LANG=C.UTF-8), or use --text-file.")
                               {:wmark/error :invalid :field :text})))
             [(cond-> {:mode (:text-mode o) :content content}
                (:text-at o)       (assoc :at (:text-at o))
                (:text-duration o) (assoc :duration-s (:text-duration o)))])
   :output {:dir (:out o)}})

(defn- request [o inputs]
  {:profile  (if (:clean o) :none (:profile o))
   :settings (overrides o)
   :inputs   (vec inputs)})

;; ---------------------------------------------------------------------------
;; Output helpers

(defn- fail! [code & msg]
  (binding [*out* *err*] (apply println msg))
  code)

(defn- print-provenance [{:keys [base provenance]}]
  (println (str "Base: " (case (:kind base)
                          :none   "built-in defaults"
                          :latest "latest (auto-saved from the previous run)"
                          (str "profile \"" (:name base) "\""))))
  (doseq [[path src] (sort-by (comp str key) provenance)
          :when (not= src :defaults)]
    (println (format "  %-28s <- %s" (str/join "." (map name path)) (name src)))))

(defn- progress-printer []
  (let [last-shown (atom -1)]
    (fn [{:keys [type index input output fraction state error]}]
      (case type
        :started  (do (reset! last-shown -1)
                      (println (format "[%d] %s -> %s" (inc index) input output)))
        :progress (when fraction
                    (let [pct (int (* 100 fraction))]
                      (when (>= pct (+ @last-shown 10))
                        (reset! last-shown pct)
                        (println (format "    %3d%%" pct)))))
        :finished (println (format "    %s%s" (name state) (if error (str ": " error) "")))
        nil))))

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

(defn- cmd-serve [sys opts open?]
  (let [sys (with-jobs sys)
        srv (http/start! sys opts)
        url (str (:url srv) "/?token=" (:token srv))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(http/stop! sys srv)))
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

(defn- shell-quote [s]
  (if (re-find #"[\s\"'\[\];]" s) (pr-str s) s))

(defn- cmd-run [sys args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args run-options)]
    (cond
      errors            (fail! 2 (str/join "\n" errors))
      (:help options)   (do (println (str "Usage: wmark run [options] INPUT...\n\n" summary)) 0)
      (empty? arguments) (fail! 2 (str "Usage: wmark run [options] INPUT...\n\n" summary))
      (:dry-run options)
      (let [r (api/plan-batch sys {:tenant "local" :user "local"} (request options arguments))]
        (print-provenance r)
        (doseq [{:keys [input output spec engine]} (:plans r)]
          (println (str "\n# " input " -> " output))
          (println (str "# " (count (:layers spec)) " layers, "
                        (get-in spec [:timebase :frames]) " frames at "
                        (get-in spec [:timebase :fps-num]) "/" (get-in spec [:timebase :fps-den]) " fps"))
          (when-let [argv (:argv engine)]
            (println (str/join " " (map shell-quote argv))))
          (when-let [graph (:graph engine)]
            (println "\n# filtergraph")
            (println graph)))
        0)
      :else
      (let [r (api/run-batch! sys {:tenant "local" :user "local"} (request options arguments)
                              {:on-event (progress-printer)})]
        (if (every? #(= :done (:state %)) (:results r)) 0 1)))))

(defn- cmd-doctor [sys]
  (let [{:keys [engine binaries home version edition]} (api/diagnose sys)]
    (println (str "wmark " version " (" (name edition) ")  home: " home))
    (println (str "engine: " (name (:engine/id engine)) " " (or (:engine/version engine) "")
                  (if (:available? engine) "  [ready]" "  [NOT READY]")))
    (doseq [p (:problems engine)] (println (str "  problem: " p)))
    (doseq [w (:warnings engine)] (println (str "  warning: " w)))
    (doseq [[k {:keys [path source trail]}] binaries]
      (println (str (name k) ": " (or path "not found") (when source (str "  (from " (name source) ")"))))
      (doseq [{:keys [source path status]} trail]
        (println (format "    %-9s %-9s %s" (name status) (name source) path))))
    (if (:available? engine) 0 1)))

(defn- cmd-profiles [sys [sub & args]]
  (let [ctx {:tenant "local" :user "local"}]
    (case sub
      (nil "list")
      (do (doseq [{:keys [name updated-at derived-from error auto?]} (api/list-profiles sys ctx)]
            (println (format "%-32s %-26s %s" name (or updated-at "")
                             (cond error (str "ERROR: " error)
                                   (and auto? derived-from) (str "(auto, from " derived-from ")")
                                   auto? "(auto)"
                                   :else ""))))
          0)

      "show"
      (do (pprint/pprint (api/get-profile sys ctx (first args))) 0)

      "save"
      (let [{:keys [options arguments errors]} (cli/parse-opts args save-options)
            [name] arguments]
        (cond
          errors      (fail! 2 (str/join "\n" errors))
          (nil? name) (fail! 2 "Usage: wmark profiles save NAME [--profile BASE] [setting flags]")
          :else
          ;; "Save as": the base profile (default latest) plus these flags,
          ;; *without* built-in defaults, so later default changes still apply
          (let [{:keys [settings]} (config/resolve-settings ((:profiles-for sys) ctx)
                                                            {:profile   (if (:clean options) :none (:profile options))
                                                             :overrides (overrides options)})
                p (api/save-profile! sys ctx name settings {:overwrite? (:overwrite options)})]
            (println (str "Saved \"" (:profile/name p) "\" (" (:profile/slug p) ".edn)"))
            0)))

      "rename" (do (api/rename-profile! sys ctx (first args) (second args)) 0)
      "copy"   (do (api/copy-profile! sys ctx (first args) (second args)) 0)
      "delete" (do (api/delete-profile! sys ctx (first args)) 0)
      (fail! 2 "Usage: wmark profiles [list | show NAME | save NAME [flags] | rename OLD NEW | copy FROM TO | delete NAME]"))))

(defn- usage [summary]
  (str/join "\n"
            [(str "wmark " version " -- batch video watermarking")
             ""
             "Usage: wmark [global options] [command] [args]"
             ""
             "Commands:"
             "  ui                  Start and open the web UI (default when run without arguments)"
             "  serve               Start the local API/UI server without opening a browser"
             "  run INPUT...        Watermark files from the command line (see: wmark run --help)"
             "  profiles ...        list | show | save | rename | copy | delete"
             "  doctor              Show which engine and FFmpeg binaries wmark found, and why"
             "  license ...         status | activate KEY (Pro)"
             "  version"
             ""
             "Global options:"
             summary]))

(defn- report-error [e]
  (let [{kind :wmark/error :keys [errors features]} (ex-data e)]
    (binding [*out* *err*]
      (println (str "Error: " (ex-message e)))
      (when errors (pprint/pprint errors))
      (when (= kind :feature-locked)
        (println (str "Locked features: " (str/join ", " (map #(subs (str %) 1) features))
                      " -- see `wmark license`."))))
    1))

(defn run-cli
  "Parse `args`, run a command, return an exit code."
  [edition args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args global-options :in-order true)
        [cmd & more] arguments]
    (cond
      errors          (fail! 2 (str/join "\n" errors))
      (:help options) (do (println (usage summary)) 0)
      :else
      (try
        (let [sys (system edition options)]
          (case (or cmd "ui")
            "ui"       (let [{o :options e :errors} (cli/parse-opts more serve-options)]
                         (if e (fail! 2 (str/join "\n" e)) (cmd-serve sys o (not (:no-browser o)))))
            "serve"    (let [{o :options e :errors} (cli/parse-opts more serve-options)]
                         (if e (fail! 2 (str/join "\n" e)) (cmd-serve sys o false)))
            "run"      (cmd-run sys more)
            "profiles" (cmd-profiles sys more)
            "doctor"   (cmd-doctor sys)
            "license"  (if-let [f (:license-cmd edition)]
                         (f (:home sys) more)
                         (do (println "Community edition: all open-source features are enabled.") 0))
            "version"  (do (println (str "wmark " version " (" (name (:edition edition)) ")")) 0)
            (fail! 2 (usage summary))))
        (catch clojure.lang.ExceptionInfo e
          (report-error e))))))
