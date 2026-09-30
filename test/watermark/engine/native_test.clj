;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.engine.native-test
  "The FFM binding against native/mock (compiled here with the system C
  compiler; skipped when there is none). Proves the C ABI end to end:
  lookup, version handshake and the compatibility rule, JSON exchange,
  upcalls from a native thread, cancellation, still decoding, a render spec
  v2 render -- and that the job pipeline runs on it unchanged."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [watermark.core.features :as features]
            [watermark.core.jobs :as jobs]
            [watermark.core.resolve :as resolve]
            [watermark.core.schema :as schema]
            [watermark.engine :as engine]
            [watermark.engine.native :as native]
            [watermark.media.local :as media]
            [watermark.raster :as raster]
            [watermark.raster.local :as raster-local]
            [watermark.render :as render]
            [watermark.render.v2 :as v2])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- tmp [] (str (Files/createTempDirectory "wmark-native" (make-array FileAttribute 0))))

(defn- compile-mock
  "The mock as a library, or nil without a C compiler."
  [& flags]
  (let [out (str (io/file (tmp) (native/library-name)))
        {:keys [exit err]} (try (apply sh/sh (concat ["cc" "-shared" "-fPIC" "-O2" "-pthread"] flags
                                                    ["-o" out "native/mock/mock_engine.c"]))
                                (catch Exception e {:exit -1 :err (ex-message e)}))]
    (if (zero? exit) out (do (println "  (skipped native tests: no C compiler:" err ")") nil))))

(def mock-library (delay (compile-mock)))

(defmacro with-mock [[sym] & body]
  `(when-let [~sym @mock-library] ~@body))

(defn- write-pam!
  "An 8-bit RGB_ALPHA PAM file of `w` x `h` straight RGBA bytes."
  [path w h ^bytes rgba]
  (with-open [o (io/output-stream path)]
    (.write o (.getBytes (str "P7\nWIDTH " w "\nHEIGHT " h "\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n")
                         "US-ASCII"))
    (.write o rgba))
  path)

(deftest no-library-means-unavailable-not-broken
  (let [info (engine/info (native/native-engine {:library "/nonexistent/lib.so" :search []}))]
    (is (false? (:available? info)))
    (is (re-find #"No native engine library" (first (:problems info))))
    (is (= :unavailable (try (engine/probe (native/native-engine {:search []}) "x.mp4") nil
                             (catch clojure.lang.ExceptionInfo e (:wmark/error (ex-data e))))))))

(deftest abi-round-trip
  (with-mock [lib]
    (let [e (native/native-engine {:library lib})]
      (testing "handshake and capabilities"
        (is (= :mock (:engine/id (engine/info e))))
        (is (= 2 (get-in (engine/info e) [:details :abi])))
        (is (= #{1 2} (get-in (engine/info e) [:capabilities :spec-versions])))
        (is (= #{:h264 :rawvideo} (get-in (engine/info e) [:capabilities :codecs]))))
      (testing "probe and structured errors"
        (is (= {:kind :video :width 640 :fps-num 30} (select-keys (engine/probe e "/in.mp4") [:kind :width :fps-num])))
        (is (= :invalid (try (engine/probe e "/missing.mp4") nil
                             (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x)))))))
      (testing "render with events from the library's thread, then cancel"
        (let [out    (str (io/file (tmp) "o.part.mp4"))
              plan   (engine/prepare e {:spec {:layers [] :timebase {:frames 90}} :encode {:codec :h264 :audio :none}
                                        :output {:path out :container "mp4"}})
              events (atom [])
              h      (engine/execute! e plan #(swap! events conj %))]
          (is (= {:status :done} (deref (engine/outcome h) 5000 :timeout)))
          (is (= 10 (count @events)))
          (is (= "mock render\n" (slurp out)))
          (let [h2 (engine/execute! e plan (fn [_]))]
            (Thread/sleep 40)
            (engine/cancel! h2)
            (is (= {:status :cancelled} (deref (engine/outcome h2) 5000 :timeout))))))
      (testing "capability negotiation happens before the library is asked"
        (is (= :unsupported (try (engine/prepare e {:spec {:layers []} :encode {:codec :hevc}
                                                    :output {:path "x" :container "mp4"}}) nil
                                 (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x))))))))))

(deftest the-job-pipeline-runs-unchanged-on-a-native-engine
  (with-mock [lib]
    (let [;; non-ASCII where this JVM can name such files: the path must reach
          ;; the library as UTF-8 bytes (the ABI's JSON is UTF-8, unescaped)
          utf8? (= "UTF-8" (System/getProperty "sun.jnu.encoding"))
          dir (str (doto (io/file (tmp) (if utf8? "vidéo 视频" "video")) .mkdirs))
          in  (str (io/file dir (if utf8? "clip é.mov" "clip.mov")))
          env {:engine (native/native-engine {:library lib}) :media (media/local-media)
               :entitlements (features/community) :secret-for (constantly (byte-array 32))
               :font (delay "/fonts/a.ttf")}]
      (spit in "stand-in input")
      (let [[r] @(jobs/run-job! env {:ctx {} :inputs [in]
                                    :settings (resolve/deep-merge schema/defaults
                                                                  {:logo {:path "/l.png"} :encode {:audio :none}})}
                               {})]
        (is (= :done (:state r)))
        (is (= "mock render\n" (slurp (io/file dir (if utf8? "clip é_wm.mp4" "clip_wm.mp4")))))))))

(deftest this-host-speaks-the-header's-abi-and-every-older-one
  (let [v (parse-long (second (re-find #"#define\s+WMARK_ENGINE_ABI_VERSION\s+(\d+)"
                                       (slurp "native/include/wmark_engine.h"))))]
    (is (= (set (range 1 (inc v))) native/abi-versions)))
  (with-mock [_]
    (testing "an ABI 1 library loads, and takes render spec 1 only"
      (let [e (native/native-engine {:library (compile-mock "-DWMARK_MOCK_ABI=1")})]
        (is (:available? (engine/info e)))
        (is (= #{1} (get-in (engine/info e) [:capabilities :spec-versions]))
            "whatever it lists: v2 needs wmark_engine_decode_still")
        (is (= :unsupported (try (engine/decode-still e "/l.pam") nil
                                 (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x))))))))
    (testing "a library from a newer ABI is refused plainly"
      (let [info (engine/info (native/native-engine {:library (compile-mock "-DWMARK_MOCK_ABI=3")}))]
        (is (false? (:available? info)))
        (is (re-find #"speaks ABI 3; this wmark takes ABI 1 or 2" (first (:problems info))))))))

(deftest an-abi-2-engine-decodes-stills-for-the-host
  (with-mock [lib]
    (let [e    (native/native-engine {:library lib})
          px   (byte-array (map unchecked-byte (range 24)))
          ;; a non-ASCII name where this JVM can write one (see below)
          path (write-pam! (str (io/file (tmp) (if (= "UTF-8" (System/getProperty "sun.jnu.encoding"))
                                                  "logo é.pam" "logo.pam")))
                           3 2 px)]
      (is (= {:kind :image :width 3 :height 2} (select-keys (engine/probe e path) [:kind :width :height])))
      (let [img (engine/decode-still e path)]
        (is (= [3 2] [(:width img) (:height img)]))
        (is (= (seq px) (seq ^bytes (:px img))) "straight RGBA8, row-major, byte for byte"))
      (is (= :unsupported (try (engine/decode-still e "/l.png") nil
                               (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x)))))
          "a format the engine can't decode is its error, passed on"))))

(defn- y4m-frames
  "Header line and frames (luma byte arrays) of a grey YUV4MPEG2 file."
  [path w h]
  (let [^bytes all (Files/readAllBytes (.toPath (io/file path)))
        header-end (loop [i 0] (if (= 10 (aget all i)) i (recur (inc i))))
        frame      (+ 6 (* w h))]
    [(String. all 0 (int header-end) "US-ASCII")
     (vec (for [start (range (inc header-end) (alength all) frame)]
            (java.util.Arrays/copyOfRange all (int (+ start 6)) (int (+ start frame)))))]))

(deftest the-mock-composites-render-spec-v2
  (with-mock [lib]
    (let [e    (native/native-engine {:library lib})
          dir  (tmp)
          ;; an opaque black 40 x 20 logo, flipping once a second from 0.5 s
          logo (write-pam! (str (io/file dir "logo.pam")) 40 20
                           (byte-array (apply concat (repeat 800 [0 0 0 -1]))))
          spec (render/build {:settings     (resolve/deep-merge
                                             schema/defaults
                                             {:logo  {:path logo :anchor :top-left :offset {:x 10 :y 10}
                                                      :width-ratio 0.25 :opacity 1.0
                                                      :animation {:type :flip-y :every-s 1.0 :duration-s 0.5 :phase-s 0.5}}
                                              :texts []})
                              :media        (engine/probe e "/in.mp4")
                              :logo-media   (engine/probe e logo)
                              :seed-fn      (constantly 42)
                              :entitlements (features/community)
                              :font         nil})
          s2   (raster/realize! (raster-local/local-rasterizer {:work-root dir}) e spec)
          out  (str (io/file dir "out.y4m"))
          req  {:spec s2 :source "/in.mp4" :media (engine/probe e "/in.mp4")
                :output {:path out :container "y4m"} :encode {:codec :rawvideo :audio :none}}]
      (testing "v2 goes out as y4m only; v1 is never drawn as y4m"
        (is (= :unsupported (try (engine/prepare e (assoc-in req [:output :container] "mp4")) nil
                                 (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x))))))
        (is (= :unsupported (try (engine/prepare e (assoc req :spec spec)) nil
                                 (catch clojure.lang.ExceptionInfo x (:wmark/error (ex-data x)))))))
      (let [events (atom [])
            h      (engine/execute! e (engine/prepare e req) #(swap! events conj %))
            _      (is (= {:status :done} (deref (engine/outcome h) 10000 :timeout)))
            [header frames] (y4m-frames out 640 360)
            book   (first (:layers s2))]
        (is (= "YUV4MPEG2 W640 H360 F30:1 Ip A1:1 Cmono" header))
        (is (= 90 (count frames)))
        (is (= 1.0 (:fraction (last @events))))
        (testing "each frame shows exactly the bitmap the reference semantics pick, where they put it"
          (doseq [n (range 90)
                  :let [{:keys [bitmap x y]} (v2/draw-at s2 book n)
                        {:keys [width height]} ((:bitmaps s2) bitmap)
                        ^bytes f (frames n)
                        dark (for [yy (range 360) xx (range 640) :when (< (bit-and 0xff (aget f (+ xx (* 640 yy)))) 128)]
                               [xx yy])]]
            (is (every? (fn [[xx yy]] (and (<= x xx (+ x width -1)) (<= y yy (+ y height -1)))) dark)
                (str "frame " n ": nothing drawn outside the bitmap's box"))
            (when (= [x y] [(get-in book [:rest :x]) (get-in book [:rest :y])])
              (is (= (* width height) (count dark)) (str "frame " n ": the opaque rest pose fills its box")))))
        (raster/release! (raster-local/local-rasterizer {:work-root dir}) s2)))))
