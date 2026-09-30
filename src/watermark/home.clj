;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.home
  "Where configuration lives on this machine: the home folder and the
  profile store in it. The profile rules themselves are the core library's
  (watermark.config); this is the JVM host's part.

  GraalVM native-image initialises Clojure namespaces at *build* time, so
  nothing environment-dependent (home dir, env vars) may sit in a top-level
  def: it would be frozen into the binary from the build machine. Everything
  here is a function."
  (:require [clojure.string :as str]
            [watermark.store.file :as file-store])
  (:import (java.nio.file Files LinkOption Path Paths)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-links
  (make-array LinkOption 0))

(defn- path ^Path [^String p & more]
  (Paths/get p (into-array String more)))

(defn- env ^String [^String k] (System/getenv k))

(defn- os-family []
  (let [os (.toLowerCase (System/getProperty "os.name" "") Locale/ROOT)]
    (cond (str/starts-with? os "windows") :windows
          (str/starts-with? os "mac")     :macos
          :else                           :unix)))

(defn default-home
  "Per-user config directory following each platform's convention. Only plain
  environment and system-property reads -- no OS-specific APIs."
  ^Path []
  (let [home (System/getProperty "user.home")]
    (case (os-family)
      :windows (path (or (not-empty (env "APPDATA"))
                         (str home "\\AppData\\Roaming"))
                     "wmark")
      :macos   (path home "Library" "Application Support" "wmark")
      (path (or (not-empty (env "XDG_CONFIG_HOME")) (str home "/.config"))
            "wmark"))))

(defn resolve-home
  "Config home, first match wins:
     1. explicit `:home` (the --home CLI flag)
     2. the WMARK_HOME environment variable
     3. `./wmark-data` when that directory exists: portable mode, for
        unzip-and-run installs. Explorer starts a double-clicked .exe with the
        exe's folder as working directory, so this also works on Windows.
     4. the OS default."
  ^Path [{:keys [home]}]
  (let [portable (path (System/getProperty "user.dir") "wmark-data")
        ^Path chosen (cond
                       home                                  (path (str home))
                       (not-empty (env "WMARK_HOME"))        (path (env "WMARK_HOME"))
                       (Files/isDirectory portable no-links) portable
                       :else                                 (default-home))]
    (.toAbsolutePath chosen)))

(defn file-store
  "The local profile store under `<home>/profiles/`; see `resolve-home` for
  `opts`."
  ([] (file-store {}))
  ([opts] (file-store/file-store (.resolve (resolve-home opts) "profiles"))))
