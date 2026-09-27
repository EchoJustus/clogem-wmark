;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.native
  "NativeFFIProcessor: the watermark.engine/VideoEngine implementation for
  engines shipped as native libraries behind the C ABI in
  native/include/wmark_engine.h -- AVFoundation/Metal on Apple (so even the
  macOS server edition can render with the hardware pipeline instead of
  FFmpeg), Media3 on Android, or a future Rust GPU core.

  Bound with Java's Foreign Function & Memory API (final in JDK 22; supported
  by GraalVM Native Image 25 -- the downcall/upcall descriptors are
  registered in the desktop reachability metadata). The job pipeline can't
  tell this engine from FFmpeg: same protocol, same capability negotiation,
  same events.

  Status: the binding is complete and tested against native/mock (a C test
  double); no production engine library exists yet. With no library found,
  `info` reports the engine as unavailable instead of throwing.

  Library lookup (watermark.util.locate order): --native-lib or
  WMARK_ENGINE_LIB, then wmark_engine.dll / libwmark_engine.dylib /
  libwmark_engine.so in the working folder, its lib/, wmark's folder, lib/."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [watermark.engine :as engine]
            [watermark.util.locate :as locate])
  (:import (java.lang.foreign AddressLayout Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle MethodHandles MethodType)
           (java.nio.file Path Paths)))

(set! *warn-on-reflection* true)

(def abi-version 1)

(defn library-name []
  (let [os (str/lower-case (System/getProperty "os.name" ""))]
    (cond (str/starts-with? os "windows") "wmark_engine.dll"
          (str/starts-with? os "mac")     "libwmark_engine.dylib"
          :else                           "libwmark_engine.so")))

;; ---------------------------------------------------------------------------
;; FFM plumbing (everything created at run time, never at image build time)

(def ^:private signatures
  "C function -> [return & params]; :void, :int, :ptr."
  {"wmark_abi_version"    [:int]
   "wmark_engine_open"    [:ptr :ptr :ptr]
   "wmark_engine_close"   [:void :ptr]
   "wmark_engine_info"    [:ptr :ptr]
   "wmark_engine_probe"   [:ptr :ptr :ptr :ptr]
   "wmark_engine_prepare" [:ptr :ptr :ptr :ptr]
   "wmark_render_start"   [:ptr :ptr :ptr :ptr :ptr :ptr]
   "wmark_render_cancel"  [:void :ptr]
   "wmark_render_release" [:void :ptr]
   "wmark_free"           [:void :ptr]})

(defn- layout ^MemoryLayout [k]
  (case k :int ValueLayout/JAVA_INT :ptr ValueLayout/ADDRESS))

(defn- descriptor ^FunctionDescriptor [[ret & params]]
  (let [ps (into-array MemoryLayout (map layout params))]
    (if (= ret :void) (FunctionDescriptor/ofVoid ps) (FunctionDescriptor/of (layout ret) ps))))

(defn- bind-library
  "Map of C name -> MethodHandle for every ABI function."
  [^Path lib]
  (let [linker (Linker/nativeLinker)
        lookup (SymbolLookup/libraryLookup lib (Arena/global))]
    (into {} (for [[name sig] signatures]
               (let [^MemorySegment addr (.orElseThrow (.find lookup name))]
                 [name (.downcallHandle linker addr (descriptor sig) (make-array Linker$Option 0))])))))

(defn- call [fns name & args]
  (.invokeWithArguments ^MethodHandle (get fns name) ^java.util.List (vec args)))

(defn- null? [^MemorySegment s] (or (nil? s) (zero? (.address s))))

(defn- c-string
  "Read and free a library-owned string."
  [fns ^MemorySegment s]
  (when-not (null? s)
    (try (.getString (.reinterpret s Long/MAX_VALUE) 0)
         (finally (call fns "wmark_free" s)))))

(defn- ->json
  "Clojure data -> JSON text, keeping keyword namespaces (\"spec/version\")."
  [x]
  (json/write-str
   (walk/postwalk #(if (keyword? %) (if (namespace %) (str (namespace %) "/" (name %)) (name %)) %) x)
   :escape-slash false))

(defn- <-json [s] (some-> s (json/read-str :key-fn keyword)))

(defn- call-checked
  "Call a fallible ABI function (last param char **error_json); returns the
  result segment or throws the engine's error as ex-info."
  [fns name & args]
  (with-open [arena (Arena/ofConfined)]
    (let [err (.allocate arena ^MemoryLayout ValueLayout/ADDRESS)]
      (.set err ^AddressLayout ValueLayout/ADDRESS 0 MemorySegment/NULL)
      (let [^MemorySegment r (apply call fns name (concat args [err]))]
        (if (null? r)
          (let [e (<-json (c-string fns (.get err ^AddressLayout ValueLayout/ADDRESS 0)))]
            (throw (ex-info (or (:message e) (str name " failed"))
                            {:wmark/error (keyword (or (:kind e) "failed")) :engine :native})))
          r)))))

(defn- string-arg ^MemorySegment [^Arena arena ^String s] (.allocateFrom arena s))

(defn- event-stub
  "Upcall stub for wmark_event_fn that hands (user, json) to `f`."
  ^MemorySegment [^Arena arena f]
  (let [target (-> (MethodHandles/lookup)
                   (.findVirtual clojure.lang.IFn "invoke"
                                 (MethodType/methodType Object Object (into-array Class [Object])))
                   (.bindTo f)
                   (.asType (MethodType/methodType Void/TYPE MemorySegment (into-array Class [MemorySegment]))))]
    (.upcallStub (Linker/nativeLinker) target
                 (FunctionDescriptor/ofVoid (into-array MemoryLayout [ValueLayout/ADDRESS ValueLayout/ADDRESS]))
                 arena (make-array Linker$Option 0))))

;; ---------------------------------------------------------------------------
;; Normalisation: JSON from the library -> the protocol's data shapes

(def ^:private keyword-caps #{:layers :animations :timing :placement :codecs :audio :sources})

(defn- normalize-info [info]
  (-> info
      (update :engine/id #(some-> % keyword))
      (update :capabilities
              (fn [caps] (into {} (for [[k v] caps]
                                    [k (if (keyword-caps k) (set (map keyword v)) (set v))]))))))

(defn- normalize-media [m] (update m :kind keyword))

;; ---------------------------------------------------------------------------
;; The engine

(defn- load-engine
  "Locate, bind, handshake, open. Returns {:fns :engine :info} or {:problems}."
  [{:keys [library search config]}]
  (let [found (locate/locate {:names [(library-name)] :explicit library :search search :bin-dir "lib"})]
    (if-not (:path found)
      {:located found
       :problems [(str "No native engine library (" (library-name) ") found. Pass --native-lib or set WMARK_ENGINE_LIB.")]}
      (try
        (let [fns (bind-library (Paths/get ^String (:path found) (make-array String 0)))
              v   (call fns "wmark_abi_version")]
          (if (not= abi-version v)
            {:located found :problems [(str "Native engine speaks ABI " v "; this wmark needs " abi-version ".")]}
            (let [handle (with-open [arena (Arena/ofConfined)]
                           (call-checked fns "wmark_engine_open" (string-arg arena (->json (or config {})))))]
              {:located found :fns fns :engine handle
               :info (normalize-info (<-json (c-string fns (call fns "wmark_engine_info" handle))))})))
        (catch Throwable t
          {:located found :problems [(str "Could not load " (:path found) ": " (ex-message t))]})))))

(defrecord NativeRender [result fns ^MemorySegment render]
  engine/RenderHandle
  (cancel! [_] (when-not (realized? result) (call fns "wmark_render_cancel" render)))
  (outcome [_] result))

(defrecord NativeFFIProcessor [opts state]
  engine/VideoEngine
  (info [_]
    (let [{:keys [info problems located]} (force (:loaded state))]
      (if info
        (assoc info :binaries {:library located})
        {:engine/id :native :available? false :problems problems
         :binaries {:library located} :capabilities {}})))

  (probe [this source]
    (let [{:keys [fns engine]} (force (:loaded state))]
      (when-not engine
        (throw (ex-info (first (:problems (engine/info this))) {:wmark/error :unavailable})))
      (with-open [arena (Arena/ofConfined)]
        (normalize-media (<-json (c-string fns (call-checked fns "wmark_engine_probe" engine
                                                             (string-arg arena (str source)))))))))

  (prepare [this request]
    (let [{:keys [fns engine]} (force (:loaded state))
          info (engine/info this)]
      (when-not (:available? info)
        (throw (ex-info (str/join " " (:problems info)) {:wmark/error :unavailable})))
      (engine/check! info request)
      (with-open [arena (Arena/ofConfined)]
        {:engine      :native
         :output      (get-in request [:output :path])
         :native-plan (c-string fns (call-checked fns "wmark_engine_prepare" engine
                                                  (string-arg arena (->json request))))})))

  (execute! [_ plan listener]
    (let [{:keys [fns engine]} (force (:loaded state))
          result (promise)
          arena  (Arena/ofShared)                  ; lives until the render is released
          stub   (event-stub arena
                             (fn [_user ^MemorySegment json-seg]
                               (let [e (<-json (.getString (.reinterpret json-seg Long/MAX_VALUE) 0))]
                                 (if (= "finished" (:event e))
                                   (deliver result (cond-> {:status (keyword (:status e))}
                                                     (:error e) (assoc :error (:error e))))
                                   (when listener (listener (update e :event keyword)))))
                               nil))
          render (try (call-checked fns "wmark_render_start" engine
                                    (string-arg arena (:native-plan plan)) stub MemorySegment/NULL)
                      (catch Throwable t
                        (.close arena)
                        (throw t)))]
      ;; free the native render and the upcall stub once the final event is in
      (doto (Thread. ^Runnable (fn [] (deref result)
                                 (call fns "wmark_render_release" render)
                                 (.close arena))
                     "wmark-native-release")
        (.setDaemon true)
        (.start))
      (->NativeRender result fns render))))

(defn native-engine
  "Native engine.
    :library  explicit library path or folder (--native-lib / WMARK_ENGINE_LIB)
    :search   locate order (default watermark.util.locate/default-search)
    :config   engine-specific options passed to wmark_engine_open
  Loading happens lazily, once, on first use."
  [opts]
  (->NativeFFIProcessor opts {:loaded (delay (load-engine opts))}))
