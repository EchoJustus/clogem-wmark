;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns smoke.native
  "Smoke test of built binaries, run by Babashka on every CI platform:

    bb test/smoke/native.clj --bin target/bin --ffmpeg target/ffmpeg/linux-x64/bin \\
                             [--mock target/libwmark_engine.so] [--name wmark] [--edition community]
    bb test/smoke/native.clj --bin dist/desktop-server --ffmpeg dist/desktop-server/bin --bundled true

  It checks what a user's first minutes need: --help, doctor, a real render
  (render spec v1, and v2 drawn by wmark itself)
  of a clip in a non-ASCII folder with the progress bar, the profile commands,
  the web UI's assets and the API behind the token, and (with --mock) a render
  through the C ABI.
  Exits 1 if any check fails."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def windows? (fs/windows?))
(defn- exe [dir n] (str (fs/absolutize (fs/path dir (if windows? (str n ".exe") n)))))

(def results (atom []))

(defn- check [label ok & [detail]]
  (swap! results conj [label ok])
  (println (if ok "ok      " "FAILED  ") label (if (and (not ok) detail) (str "\n        " detail) ""))
  ok)

(defn- run [& args]
  (let [argv (if (map? (first args)) (rest args) args)
        opts (merge {:out :string :err :string :continue true} (when (map? (first args)) (first args)))]
    @(apply p/process opts (map str argv))))

(defn- frames [ffprobe file]
  (-> (run ffprobe "-v" "error" "-count_frames" "-select_streams" "v:0"
           "-show_entries" "stream=nb_read_frames" "-of" "csv=p=0" file)
      :out str/trim parse-long))

(defn -main [& args]
  (let [opts    (into {} (map (fn [[k v]] [(keyword (subs k 2)) v]) (partition 2 args)))
        wmark   (exe (:bin opts "target/bin") (:name opts "wmark"))
        ffdir   (str (fs/absolutize (or (:ffmpeg opts) (throw (ex-info "--ffmpeg DIR is required" {})))))
        ffmpeg  (exe ffdir "ffmpeg")
        ffprobe (exe ffdir "ffprobe")
        edition (:edition opts "community")
        work    (fs/create-temp-dir {:prefix "wmark-smoke"})
        home    (str (fs/path work "home"))
        ;; --bundled: find FFmpeg the way a download does, in bin/ next to
        ;; wmark, under the hardened order (never the working folder)
        bundled (= "true" (:bundled opts))
        base    (if bundled
                  ["--home" home "--ffmpeg-search" "app,app-bin"]
                  ["--home" home "--ffmpeg" ffdir])
        indir   (fs/path work "vidéo 视频")
        clip    (str (fs/path indir "clip é.mp4"))
        logo    (str (fs/path work "logo.png"))
        outdir  (str (fs/path work "out 输出"))]
    (fs/create-dirs indir)

    (let [{:keys [exit out]} (run wmark "--help")]
      (check "wmark --help" (and (zero? exit) (str/includes? out "Usage: wmark")) out))
    (let [{:keys [exit out]} (run wmark "version")]
      (check (str "wmark version says " edition) (and (zero? exit) (str/includes? out (str "(" edition ")"))) out))
    (let [{:keys [exit out err]} (apply run wmark (concat base ["doctor"]))]
      (check "doctor: engine ready with the bundled FFmpeg"
             (and (zero? exit) (str/includes? out "[ready]") (or (not bundled) (str/includes? out "(from app-bin)")))
             (str out err)))

    (run ffmpeg "-hide_banner" "-loglevel" "error" "-y" "-f" "lavfi" "-i" "testsrc2=size=640x360:rate=25:duration=4"
         "-f" "lavfi" "-i" "sine=frequency=440:duration=4" "-c:v" "mpeg4" "-q:v" "5" "-c:a" "aac" "-shortest" clip)
    (run ffmpeg "-hide_banner" "-loglevel" "error" "-y" "-f" "lavfi" "-i" "color=c=red@0.8:s=200x100,format=rgba"
         "-frames:v" "1" logo)
    (check "test media written (non-ASCII folder)" (and (fs/exists? clip) (fs/exists? logo)))

    (let [{:keys [exit out err]} (apply run wmark (concat base ["run" "--logo" logo "--text" "(c) Studio — ©"
                                                                 "-o" outdir clip]))
          result (str (fs/path outdir "clip é_wm.mp4"))]
      (check "render: logo flip and text, non-ASCII paths" (and (zero? exit) (fs/exists? result)) (str out err))
      (when (fs/exists? result)
        (check "render: every frame kept" (= 100 (frames ffprobe result)) (str (frames ffprobe result) " frames"))))

    ;; render spec v2: the kernel's TrueType reader, text rasterizer and warp run
    ;; inside the binary, the bundled font included; FFmpeg decodes the logo.
    ;; --progress bar: the line redrawn in place, as on a terminal
    (let [out2 (str (fs/path work "out v2"))
          {:keys [exit out err]} (apply run wmark (concat base ["--render-spec" "2" "run" "--progress" "bar"
                                                                 "--logo" logo "--text" "(c) Studio — ©" "-o" out2 clip]))
          result (str (fs/path out2 "clip é_wm.mp4"))]
      (check "render spec v2: wmark draws the flip and the text, FFmpeg composites"
             (and (zero? exit) (fs/exists? result)) (str out err))
      (check "run --progress bar: one line redrawn in place, then the outcome"
             ;; ASCII only: how a console encodes the file name varies by OS
             (and (str/includes? out "\r[1/1]  clip ") (str/includes? out "%  ")
                  (re-find #"  done  in \d+:\d\d" out)) out)
      (when (fs/exists? result)
        (check "render spec v2: every frame kept" (= 100 (frames ffprobe result)) (str (frames ffprobe result) " frames"))))

    ;; the profile commands the terminal client used to offer, now in wmark itself
    (let [{:keys [exit out err]} (apply run wmark (concat base ["profiles" "save" "Smoke test" "--clean"
                                                                 "--opacity" "0.5" "--text-mode" "canary" "--text" "x"]))]
      (check "profiles save" (and (zero? exit) (str/includes? out "Saved \"Smoke test\"")) (str out err)))
    (let [{:keys [exit out err]} (apply run wmark (concat base ["profiles" "effective" "Smoke test"]))]
      (check "profiles effective: values, where each came from, what needs Pro, canary by name"
             (and (zero? exit) (re-find #"logo\.opacity\s+0\.5\s+from profile \"Smoke test\"" out)
                  (str/includes? out "built-in default") (str/includes? out "canary")
                  (not (str/includes? out "subliminal"))
                  (or (not= "community" edition) (str/includes? out "Needs wmark Pro to run")))
             (str out err)))

    (let [srv   (apply p/process {:err :string} wmark (concat base ["serve" "--announce" "json"]))
          line  (.readLine ^java.io.BufferedReader (io/reader (:out srv)))
          {:keys [url token]} (json/parse-string line true)
          get!  (fn [path & [hdrs]] (http/get (str url path) {:headers hdrs :throw false}))]
      (try
        (check "serve announces url and token" (and url token) line)
        (let [r (get! "/api/v1/health" {"Authorization" (str "Bearer " token)})]
          (check "API answers behind the token" (= 200 (:status r)) (:status r)))
        (check "API refuses without the token" (= 401 (:status (get! "/api/v1/health"))))
        (let [r (http/get (str url "/?token=" token) {:throw false :client (http/client {:follow-redirects :never})})]
          (check "sign-in link sets the cookie and redirects"
                 (and (#{302 303} (:status r)) (str/includes? (str (get-in r [:headers "set-cookie"])) "HttpOnly"))
                 (:status r)))
        (let [r (get! "/" {"Authorization" (str "Bearer " token)})]
          (check "web UI page loads datastar.js" (and (= 200 (:status r)) (str/includes? (str (:body r)) "datastar.js"))
                 (:status r)))
        (doseq [asset ["/datastar.js" "/app.css"]]
          (let [r (get! asset)]
            (check (str "asset " asset " is in the binary") (and (= 200 (:status r)) (< 1000 (count (str (:body r)))))
                   (:status r))))
        (finally (p/destroy-tree srv))))

    (when-let [mock (:mock opts)]
      (let [lib ["--home" home "--engine" "native" "--native-lib" (str (fs/absolutize mock))]
            {:keys [exit out err]} (apply run wmark (concat lib ["doctor"]))]
        (check "native engine: the C mock loads through FFM" (and (zero? exit) (str/includes? out "mock")) (str out err))
        (let [{:keys [exit out err]} (apply run wmark (concat lib ["run" "--logo" logo "-o" (str (fs/path work "mock-out")) clip]))]
          (check "native engine: render with progress upcalls, no exception"
                 (and (zero? exit) (str/includes? out "done") (not (str/includes? err "Exception")))
                 (str out err)))
        ;; ABI 2 inside the binary: planning a v2 render asks the library to
        ;; decode the logo (PAM, the mock's one still format). The mock then
        ;; declines to write v2 as MP4, which it only does after the decode
        (let [pam (str (fs/path work "logo.pam"))
              _   (run ffmpeg "-hide_banner" "-loglevel" "error" "-y" "-i" logo "-pix_fmt" "rgba" pam)
              {:keys [out err]} (apply run wmark (concat lib ["--render-spec" "2" "run" "--logo" pam
                                                              "-o" (str (fs/path work "mock-v2")) clip]))]
          (check "native engine: ABI 2 decodes the logo for render spec v2 (then the mock declines MP4, as designed)"
                 (and (str/includes? (str out err) "the mock writes render spec v2 as y4m only")
                      (not (str/includes? err "Exception")))
                 (str out err)))))

    (let [failed (remove second @results)]
      (println (format "\n%d checks, %d failed" (count @results) (count failed)))
      (System/exit (if (seq failed) 1 0)))))

(apply -main *command-line-args*)
