;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.cli
  "The wmark command line, the same program on every host (docs/adr/0014):
  its options, what they mean as settings, the commands that run through
  the Core API, and what they print. The JVM's `wmark` and the Dart VM's
  `wmark-dart` are this namespace plus a host map:

    :program          the executable's name, as usage shows it
    :version          this build's version
    :edition          :community, or a commercial edition's id
    :system           (fn [global-options]) the Core API's system, or a task
                      of it (the Dart VM discovers its engine asynchronously)
    :default-command  what runs with no command (the JVM: \"ui\"), else usage
    :commands         {name (fn [sys args] exit-code)}: the host's own
                      commands (the JVM's ui, serve and license)
    :usage-commands   [name ...] in the order usage lists them
    :command-help     {name [label description]} for the host's commands
    :write :write-err (fn [text]) standard output and error, as is: no
                      newline added
    :read-text        (fn [path]) a UTF-8 file's text (--text-file)
    :terminal?        (fn []) is standard output an interactive terminal?
    :env              (fn [name]) an environment variable

  `main` returns a task of the exit code: 0 done, 1 failed, 2 misused."
  (:require [clojure.string :as str]
            [watermark.cli.opts :as opts]
            [watermark.cli.progress :as progress]
            [watermark.config :as config]
            [watermark.core.api :as api]
            [watermark.core.features :as features]
            [watermark.util.edn :as edn]
            [watermark.util.host :as util-host]
            [watermark.util.json :as json]
            [watermark.util.num :as number]
            [watermark.util.task :as task]
            [watermark.util.text :as text]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Options

(defn- num-opt [long-opt desc parse]
  [nil long-opt desc :parse-fn parse :validate [some? "must be a number"]])

(def global-options
  [[nil "--home DIR" "Data directory (default: WMARK_HOME, ./wmark-data, else the per-user dir)"]
   [nil "--engine NAME" "Render engine: ffmpeg (default) or native"]
   [nil "--ffmpeg PATH" "ffmpeg executable or folder (default: ./, ./bin/, wmark's folder, its bin/, PATH)"]
   [nil "--ffmpeg-search ORDER" "Where to look, e.g. app,app-bin,path to skip the working folder"]
   [nil "--native-lib PATH" "Native engine library (wmark_engine.dll / libwmark_engine.dylib / .so)"]
   [nil "--render-spec N" "1: the engine draws; 2: wmark draws, the engine only composites (default: 1 if the engine can)"
    :parse-fn number/parse-int :validate [#{1 2} "must be 1 or 2"]]
   ["-h" "--help" "Show help"]])

(def settings-options
  [["-p" "--profile NAME" "Base profile (default: latest)"]
   [nil "--clean" "Ignore profiles; start from built-in defaults"]
   [nil "--logo PATH" "Logo image (PNG with transparency)"]
   [nil "--anchor POS" "top-left | top-center | top-right | center-left | center | ... | bottom-right"
    :parse-fn keyword]
   (num-opt "--offset-x PX" "Horizontal offset from the anchored edge" number/parse-int)
   (num-opt "--offset-y PX" "Vertical offset from the anchored edge" number/parse-int)
   (num-opt "--logo-width RATIO" "Logo width as a fraction of the frame width" number/parse-decimal)
   (num-opt "--opacity A" "Logo opacity, 0-1" number/parse-decimal)
   (num-opt "--flip-every SEC" "Seconds between logo flips" number/parse-decimal)
   (num-opt "--flip-duration SEC" "Duration of one flip" number/parse-decimal)
   [nil "--static-logo" "No flip animation"]
   [nil "--text TEXT" "Add a warning-text layer (replaces the profile's text layers)"]
   [nil "--text-file PATH" "Like --text, read as UTF-8 from a file (safe in any terminal locale)"]
   [nil "--text-mode MODE" "continuous | scheduled | canary (Pro) | random (Pro)"
    :parse-fn keyword :default :continuous]
   [nil "--text-at SECONDS" "Comma-separated start times (scheduled mode)"
    :parse-fn (fn [s] (mapv number/parse-decimal (str/split s #",")))]
   (num-opt "--text-duration SEC" "How long each scheduled text shows" number/parse-decimal)
   ["-o" "--out DIR" "Output directory (default: next to each input)"]])

(def run-options
  (conj settings-options
        (num-opt "--cover-at SEC" "Embed each copy's frame at SEC as its cover (its thumbnail in file browsers; MP4)"
                 number/parse-decimal)
        [nil "--dry-run" "Print the FFmpeg command and filtergraph only"]
        [nil "--progress MODE" "auto (a bar on a terminal, else lines) | bar | lines | none"
         :default :auto :parse-fn keyword
         :validate [progress/modes "must be auto, bar, lines or none"]]
        ["-h" "--help" "Show this help"]))

(def save-options (conj settings-options [nil "--overwrite" "Replace a profile whose name maps to the same file"]))

(defn search-order
  "--ffmpeg-search's text (\"app,app-bin,path\") as a search order."
  [s]
  (when s (mapv keyword (remove text/blank? (str/split s #"[, \t]+")))))

;; ---------------------------------------------------------------------------
;; What the options mean

(defn overrides
  "Setting flags -> a settings overlay. Absent flags stay nil, so they fall
  through to the profile (or latest) during resolution. `read-text` (fn
  [path]) reads --text-file."
  [o read-text]
  {:logo   {:path        (:logo o)
            :anchor      (:anchor o)
            :offset      {:x (:offset-x o) :y (:offset-y o)}
            :width-ratio (:logo-width o)
            :opacity     (:opacity o)
            :animation   (cond
                           (:static-logo o) {:type :none}
                           (or (:flip-every o) (:flip-duration o))
                           {:type :flip-y :every-s (:flip-every o) :duration-s (:flip-duration o)})}
   :texts  (when-let [content (some-> (if (:text-file o) (read-text (:text-file o)) (:text o))
                                      text/trim not-empty)]
             (when (str/includes? content "�")
               (throw (ex-info (str "The text arrived with undecodable characters: this terminal's locale "
                                    "isn't UTF-8 (try LANG=C.UTF-8), or use --text-file.")
                               {:wmark/error :invalid :field :text})))
             [(cond-> {:mode (:text-mode o) :content content}
                (:text-at o)       (assoc :at (:text-at o))
                (:text-duration o) (assoc :duration-s (:text-duration o)))])
   :output {:dir (:out o)}})

(defn request
  "The batch request `run` makes for `inputs` with options `o`."
  [o inputs read-text]
  (cond-> {:profile  (if (:clean o) :none (:profile o))
           :settings (overrides o read-text)
           :inputs   (vec inputs)}
    (:cover-at o) (assoc :cover {:t (:cover-at o)})))

;; ---------------------------------------------------------------------------
;; What the commands print

(defn- pad [s width]
  (let [s (str s)] (str s (apply str (repeat (- width (count s)) " ")))))

(defn- feature-title [id]
  (get-in features/catalog [id :title] (subs (str id) 1)))

(defn base-line [{:keys [base]}]
  (str "Base: " (case (:kind base)
                  :none   "built-in defaults"
                  :latest "latest (auto-saved from the previous run)"
                  (str "profile \"" (:name base) "\""))))

(defn provenance-lines
  "The base, then each setting that isn't a built-in default and the layer
  that set it."
  [{:keys [provenance] :as r}]
  (into [(base-line r)]
        (for [[path src] (sort-by (comp str key) provenance)
              :when (not= src :defaults)]
          (str "  " (pad (str/join "." (map name path)) 28) " <- " (name src)))))

(defn- source-label
  "Where an effective setting came from, as users read it."
  [{:keys [base provenance]} path]
  (case (get provenance path)
    :profile   (if (= :latest (:kind base)) "from your last run" (str "from profile \"" (:name base) "\""))
    :overrides "set here"
    "built-in default"))

(defn- leaves
  "[path value] for every leaf of a settings map, in a stable order. Vectors
  (text layers) are leaves: a layer replaces the lower layers' wholesale."
  ([m] (leaves [] m))
  ([prefix m]
   (mapcat (fn [[k v]]
             (let [p (conj prefix k)]
               (if (and (map? v) (seq v)) (leaves p v) [[p v]])))
           (sort-by (comp str key) m))))

(defn- show-value [v]
  (cond (keyword? v)                  (name v)
        (or (map? v) (sequential? v)) (json/write v)
        (number? v)                   (number/decimal-str v)
        :else                         (str v)))

(defn effective-lines
  "Every setting a run would use, its value and where it came from, then the
  features the current plan doesn't include (by title)."
  [r]
  (concat [(base-line r)]
          (for [[path v] (leaves (features/display-settings (:settings r)))]
            (str "  " (pad (str/join "." (map name path)) 30) " " (pad (show-value v) 28) " " (source-label r path)))
          (when (seq (:locked r))
            [(str "Needs wmark Pro to run: " (str/join ", " (map feature-title (:locked r))))])))

(defn profile-list-lines [profiles]
  (for [{:keys [name updated-at derived-from error auto?]} profiles]
    (str (pad name 32) " " (pad (or updated-at "") 26) " "
         (cond error (str "ERROR: " error)
               (and auto? derived-from) (str "(auto, from " derived-from ")")
               auto? "(auto)"
               :else ""))))

(defn doctor-lines
  "`doctor`: the engine, its problems and warnings, and how each binary was
  found."
  [program {:keys [engine binaries home version edition]}]
  (concat [(str program " " version " (" (name edition) ")  home: " home)
           (str "engine: " (name (:engine/id engine)) " " (or (:engine/version engine) "")
                (if (:available? engine) "  [ready]" "  [NOT READY]"))]
          (for [p (:problems engine)] (str "  problem: " p))
          (for [w (:warnings engine)] (str "  warning: " w))
          (mapcat (fn [[k {:keys [path source trail]}]]
                    (cons (str (name k) ": " (or path "not found") (when source (str "  (from " (name source) ")")))
                          (for [{:keys [source path status]} trail]
                            (str "    " (pad (name status) 9) " " (pad (name source) 9) " " path))))
                  (sort-by (comp name key) binaries))))

(def ^:private quoted {"\"" "\\\"" "\\" "\\\\" "\n" "\\n" "\t" "\\t" "\r" "\\r"})

(defn shell-quote
  "`s` as a shell reader would take it back: as is, or in double quotes
  when it holds whitespace, quotes, brackets or a semicolon."
  [s]
  (if (re-find #"[ \t\n\u000B\f\r\"'\[\];]" s)
    (str "\"" (str/replace s #"[\"\\\n\t\r]" (fn [m] (quoted m))) "\"")
    s))

(defn dry-run-lines
  "`run --dry-run`: where the settings came from, then each input's plan:
  the FFmpeg command and its filtergraph."
  [r]
  (concat (provenance-lines r)
          (mapcat (fn [{:keys [input output spec engine]}]
                    (concat ["" (str "# " input " -> " output)
                             (str "# " (count (:layers spec)) " layers, "
                                  (get-in spec [:timebase :frames]) " frames at "
                                  (get-in spec [:timebase :fps-num]) "/" (get-in spec [:timebase :fps-den]) " fps")]
                            (when-let [argv (:argv engine)] [(str/join " " (map shell-quote argv))])
                            (when-let [graph (:graph engine)] ["" "# filtergraph" graph])))
                  (:plans r))))

(defn- without-newline [s] (if (str/ends-with? s "\n") (subs s 0 (dec (count s))) s))

(defn error-lines
  "What a failed command prints for error `e` (an ex-info)."
  [program e]
  (let [{kind :wmark/error :keys [errors features]} (ex-data e)]
    (concat [(str "Error: " (ex-message e))]
            (when errors [(without-newline (edn/write errors))])
            (when (= kind :feature-locked)
              [(str "Locked features: " (str/join ", " (map feature-title features))
                    " -- see `" program " license`.")]))))

(def command-help
  {"run"      ["run INPUT..." "Watermark files from the command line (see: %s run --help)"]
   "profiles" ["profiles ..." "list | show | effective | save | rename | copy | delete"]
   "doctor"   ["doctor" "Show which engine and FFmpeg binaries %s found, and why"]
   "version"  ["version" ""]})

(defn usage
  "The main help text: commands in the host's order, then global options."
  [{:keys [program version usage-commands] :as host} summary]
  (let [help (merge command-help (:command-help host))]
    (str/join "\n"
              (concat [(str program " " version " -- batch video watermarking")
                       ""
                       (str "Usage: " program " [global options] [command] [args]")
                       ""
                       "Commands:"]
                      (for [c usage-commands
                            :let [[label desc] (help c)]]
                        (str/replace (str "  " (pad label 20) (str/replace desc "%s" program)) #" +$" ""))
                      ["" "Global options:" summary]))))

;; ---------------------------------------------------------------------------
;; Commands

(def ^:private ctx {:tenant "local" :user "local"})

(defn- say [host & lines]
  (doseq [l lines] ((:write host) (str l "\n"))))

(defn- fail [host code & msg]
  ((:write-err host) (str (str/join " " msg) "\n"))
  code)

(defn- run-cmd [{:keys [program read-text terminal? env] :as host} sys args]
  (let [{:keys [options arguments errors summary]} (opts/parse-opts args run-options)
        help (str "Usage: " program " run [options] INPUT...\n\n" summary)]
    (cond
      errors             (fail host 2 (str/join "\n" errors))
      (:help options)    (do (say host help) 0)
      (empty? arguments) (fail host 2 help)
      (:dry-run options) (do (apply say host (dry-run-lines (api/plan-batch sys ctx (request options arguments read-text))))
                             0)
      :else
      (let [mode (progress/resolve-mode (:progress options) {:terminal? (terminal?) :term (env "TERM")})
            now  (fn [] (/ (util-host/now-ms) 1000.0))]
        (task/then (api/run-batch! sys ctx (request options arguments read-text)
                                   {:on-event (progress/printer mode (count arguments) {:now-s now :write (:write host)})})
                   (fn [r] (if (every? #(= :done (:state %)) (:results r)) 0 1)))))))

(defn- profiles-cmd [{:keys [program read-text] :as host} sys [sub & args]]
  (case sub
    (nil "list")
    (do (apply say host (profile-list-lines (api/list-profiles sys ctx))) 0)

    "show"
    ;; users read text modes by display name ("canary"), never by wire id
    (do ((:write host) (edn/write (update (api/get-profile sys ctx (first args)) :settings features/display-settings)))
        0)

    "effective"
    (let [{:keys [options arguments errors]} (opts/parse-opts args [[nil "--clean" "From built-in defaults only"]])]
      (if errors
        (fail host 2 (str/join "\n" errors))
        (do (apply say host (effective-lines (api/resolve-settings sys ctx {:profile (if (:clean options) :none (first arguments))})))
            0)))

    "save"
    (let [{:keys [options arguments errors]} (opts/parse-opts args save-options)
          [name] arguments]
      (cond
        errors      (fail host 2 (str/join "\n" errors))
        (nil? name) (fail host 2 (str "Usage: " program " profiles save NAME [--profile BASE] [setting flags]"))
        :else
        ;; "Save as": the base profile (default latest) plus these flags,
        ;; *without* built-in defaults, so later default changes still apply
        (let [{:keys [settings]} (config/resolve-settings ((:profiles-for sys) ctx)
                                                          {:profile   (if (:clean options) :none (:profile options))
                                                           :overrides (overrides options read-text)})
              p (api/save-profile! sys ctx name settings {:overwrite? (:overwrite options)})]
          (say host (str "Saved \"" (:profile/name p) "\" (" (:profile/slug p) ".edn)"))
          0)))

    "rename" (do (api/rename-profile! sys ctx (first args) (second args)) 0)
    "copy"   (do (api/copy-profile! sys ctx (first args) (second args)) 0)
    "delete" (do (api/delete-profile! sys ctx (first args)) 0)
    (fail host 2 (str "Usage: " program " profiles [list | show NAME | effective [NAME | --clean] | save NAME [flags]"
                      " | rename OLD NEW | copy FROM TO | delete NAME]"))))

(defn- dispatch [{:keys [program version edition commands] :as host} sys cmd args summary]
  (case cmd
    "run"      (run-cmd host sys args)
    "profiles" (profiles-cmd host sys args)
    "doctor"   (let [d (api/diagnose sys)]
                 (apply say host (doctor-lines program d))
                 (if (:available? (:engine d)) 0 1))
    "version"  (do (say host (str program " " version " (" (name edition) ")")) 0)
    (if-let [f (get commands cmd)]
      (f sys args)
      (fail host 2 (usage host summary)))))

(defn- report-error [{:keys [program write-err]} e]
  (write-err (str (str/join "\n" (error-lines program e)) "\n"))
  1)

(defn main
  "Run command line `args` on `host`; a task of the exit code. The library's
  own errors (ex-info) are reported and exit 1; anything else fails the
  task."
  [host args]
  (let [{:keys [options arguments errors summary]} (opts/parse-opts args global-options :in-order true)
        [cmd & more] arguments]
    (cond
      errors          (task/resolved (fail host 2 (str/join "\n" errors)))
      (:help options) (do (say host (usage host summary)) (task/resolved 0))
      :else
      (-> (task/attempt #(task/then ((:system host) options)
                                    (fn [sys] (dispatch host sys (or cmd (:default-command host)) more summary))))
          (task/recover (fn [e] (if (ex-data e) (report-error host e) (task/failed e))))))))
