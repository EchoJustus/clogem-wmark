;; SPDX-FileCopyrightText: 2026 The clogem-wmark authors
;; SPDX-License-Identifier: EPL-2.0
(ns watermark.core.form
  "The settings form as data: one row per setting, for every UI.

  `model` is a pure function of the field catalog below, the effective
  settings and where each value came from (watermark.config's resolution),
  so the built-in Datastar UI and a GUI app render the same rows with the
  same labels, locks and provenance (docs/adr/0011, section 3; docs/adr/0008,
  section 1).

  The catalog is data rather than a walk over the malli schema, so it runs
  where malli may not (the Dart VM). test/watermark/core/form_test.clj keeps
  the two in step: every setting in the schema has a row here, with the same
  kind, bounds and choices.

  Edits are pure too. `edit` applies one operation to a profile's own
  settings and returns the new settings; the Core API validates the whole
  profile and saves it with the revision the client read."
  (:require [clojure.string :as str]
            [watermark.core.features :as features]
            [watermark.util.num :as number]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Words

(defn- words
  "\"top-left\" -> \"Top left\", :flip-y -> \"Flip y\"."
  [k]
  (let [s (str/replace (if (keyword? k) (name k) (str k)) "-" " ")]
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(def source-labels
  "How each UI names where a value came from (the provenance invariant)."
  {:set-here "Set here"
   :last-run "From your last run"
   :unsaved  "Not saved yet"
   :default  "Built-in default"
   :unset    "Not set"})

;; ---------------------------------------------------------------------------
;; The catalog

(def anchors
  [:top-left :top-center :top-right
   :center-left :center :center-right
   :bottom-left :bottom-center :bottom-right])

(def categories
  "Sections of the form, in order. :texts holds the text layer cards."
  [{:id :logo   :title "Logo"        :description "An image that flips in 3D now and then, so a fixed box can't erase it."}
   {:id :texts  :title "Text layers" :description "Warning text drawn over the video, each layer on its own schedule."}
   {:id :output :title "Output"      :description "Where watermarked copies go and what they're called."}
   {:id :encode :title "Encoding"    :description "Codec, quality and sound of the watermarked copies."}])

(def fields
  "Every setting outside the text layers, in form order. Keys:
    :path         where it lives in the settings
    :kind         :boolean :integer :number :enum :text :file :folder
    :min :max     bounds (the schema's); :step for numeric controls
    :scale        shown and typed multiplied by this (100 for percentages)
    :unit         shown after the value
    :slider       a range control next to the number
    :options      an enum's values, in order; :labels names them
    :default      what an absent value means, when the schema has no default"
  [{:path [:logo :enabled] :kind :boolean
    :title "Show the logo" :description "Draw the logo on every video."}
   {:path [:logo :path] :kind :file
    :title "Logo image" :description "A PNG or JPEG file. A transparent PNG looks best."}
   {:path [:logo :anchor] :kind :enum :options anchors
    :title "Position" :description "The corner or edge the logo keeps to."}
   {:path [:logo :offset :x] :kind :integer :min -10000 :max 10000 :unit "px" :default 0
    :title "Horizontal margin" :description "Pixels between the logo and the side it keeps to."}
   {:path [:logo :offset :y] :kind :integer :min -10000 :max 10000 :unit "px" :default 0
    :title "Vertical margin" :description "Pixels between the logo and the top or bottom it keeps to."}
   {:path [:logo :width-ratio] :kind :number :min 0.02 :max 0.6 :step 0.01 :scale 100 :unit "%" :slider true
    :title "Size" :description "The logo's width as a share of the video's width."}
   {:path [:logo :opacity] :kind :number :min 0.0 :max 1.0 :step 0.05 :scale 100 :unit "%" :slider true
    :title "Opacity" :description "How solid the logo is. Lower values show more of the video through it."}
   {:path [:logo :animation :type] :kind :enum :options [:none :flip-y]
    :labels {:none "No animation" :flip-y "Flip in 3D"}
    :title "Animation" :description "The flip that keeps inpainting from erasing the logo."}
   {:path [:logo :animation :every-s] :kind :number :min 2.0 :step 0.5 :unit "s" :default 60.0
    :title "Flip every" :description "Seconds between flips."}
   {:path [:logo :animation :duration-s] :kind :number :min 0.2 :max 5.0 :step 0.1 :unit "s" :default 1.0
    :title "Flip duration" :description "How long one flip takes."}
   {:path [:logo :animation :phase-s] :kind :number :min 0.0 :step 0.5 :unit "s"
    :title "First flip at" :description "Seconds into the video. Empty: after one interval."}

   {:path [:output :dir] :kind :folder
    :title "Output folder" :description "Where copies are written. Empty: next to each original."}
   {:path [:output :suffix] :kind :text :max 40
    :title "File name suffix" :description "Added to each original's name, before the extension."}
   {:path [:output :container] :kind :enum :options ["mp4" "mov" "mkv"]
    :labels {"mp4" "MP4" "mov" "QuickTime (MOV)" "mkv" "Matroska (MKV)"}
    :title "File format" :description "The container of the watermarked copy."}
   {:path [:output :overwrite?] :kind :boolean
    :title "Replace existing copies" :description "Overwrite a copy with the same name instead of stopping."}
   {:path [:output :strip-metadata] :kind :boolean
    :title "Remove metadata" :description "Drop the original's metadata (camera, location, tools) from the copy."}
   {:path [:output :metadata :title] :kind :text :max 200
    :title "Title" :description "The copy's title, as players and file browsers show it."}
   {:path [:output :metadata :author] :kind :text :max 200
    :title "Author" :description "Who made the video. Players show it as the artist or author."}
   {:path [:output :metadata :copyright] :kind :text :max 200
    :title "Copyright" :description "Who owns the video, such as \"© 2026 Studio A\"."}
   {:path [:output :metadata :comment] :kind :text :max 1000 :multiline true
    :title "Comment" :description "A note written into the file, such as a licence or a contact."}

   {:path [:encode :codec] :kind :enum :options [:h264 :hevc]
    :labels {:h264 "H.264" :hevc "HEVC (H.265)"}
    :title "Codec" :description "H.264 plays everywhere. HEVC is smaller at the same quality."}
   {:path [:encode :quality] :kind :enum :options [:archival :high :balanced :compact]
    :title "Quality" :description "Higher quality means larger files."}
   {:path [:encode :audio] :kind :enum :options [:copy :aac :none]
    :labels {:copy "Keep unchanged" :aac "Re-encode as AAC" :none "Remove sound"}
    :title "Sound" :description "What happens to the original's audio."}
   {:path [:encode :ffmpeg :video-codec] :kind :enum
    :options ["libx264" "libx265" "h264_nvenc" "hevc_nvenc" "h264_qsv" "hevc_qsv"
              "h264_amf" "hevc_amf" "h264_videotoolbox" "hevc_videotoolbox"]
    :title "FFmpeg encoder" :description "Force one encoder. Empty: the best one this FFmpeg has." :advanced true}
   {:path [:encode :ffmpeg :crf] :kind :integer :min 0 :max 51
    :title "FFmpeg CRF" :description "Constant rate factor for x264 and x265. Empty: from the quality." :advanced true}
   {:path [:encode :ffmpeg :preset] :kind :text :max 20
    :title "FFmpeg preset" :description "The encoder's speed preset, such as \"slow\"." :advanced true}])

(def layer-modes
  "Text layer kinds, in menu order, by wire id."
  [:continuous :scheduled :subliminal :random])

(def layer-fields
  "Fields of a text layer, by path inside the layer. :modes limits a field
  to some layer kinds; the rest belong to every kind."
  [{:path [:mode] :kind :enum :options layer-modes
    :title "Kind" :description "When the text shows."}
   {:path [:content] :kind :text :max 500 :multiline true
    :title "Text" :description "What the layer says."}
   {:path [:at] :kind :seconds-list :modes #{:scheduled} :min 0.0
    :title "Shown at" :description "Start times in seconds, separated by commas."}
   {:path [:duration-s] :kind :number :min 0.04 :max 600.0 :step 0.5 :unit "s" :modes #{:scheduled}
    :title "Shown for" :description "Seconds each showing lasts."}
   {:path [:every-s] :kind :number :min 1.0 :max 600.0 :step 1.0 :unit "s" :modes #{:subliminal}
    :title "Canary every" :description "Average seconds between canary frames. The exact times are keyed to each video."}
   {:path [:frames] :kind :integer :min 1 :max 3 :unit "frames" :modes #{:subliminal}
    :title "Canary length" :description "Frames per canary, 1 to 3."}
   {:path [:min-duration-s] :kind :number :min 0.04 :max 60.0 :step 0.5 :unit "s" :modes #{:random}
    :title "Shortest showing" :description "Seconds, at least."}
   {:path [:max-duration-s] :kind :number :min 0.04 :max 60.0 :step 0.5 :unit "s" :modes #{:random}
    :title "Longest showing" :description "Seconds, at most."}
   {:path [:min-gap-s] :kind :number :min 0.5 :max 3600.0 :step 1.0 :unit "s" :modes #{:random}
    :title "Shortest gap" :description "Seconds between showings, at least."}
   {:path [:max-gap-s] :kind :number :min 0.5 :max 3600.0 :step 1.0 :unit "s" :modes #{:random}
    :title "Longest gap" :description "Seconds between showings, at most."}
   {:path [:anchor] :kind :enum :options anchors :default :bottom-left
    :title "Position" :description "The corner or edge the text keeps to."}
   {:path [:offset :x] :kind :integer :min -10000 :max 10000 :unit "px" :default 24
    :title "Horizontal margin" :description "Pixels between the text and the side it keeps to."}
   {:path [:offset :y] :kind :integer :min -10000 :max 10000 :unit "px" :default 24
    :title "Vertical margin" :description "Pixels between the text and the top or bottom it keeps to."}
   {:path [:size-ratio] :kind :number :min 0.01 :max 0.3 :step 0.005 :scale 100 :unit "%" :slider true :default 0.035
    :title "Size" :description "Text height as a share of the video's height."}
   {:path [:color] :kind :color :default "white"
    :title "Colour" :description "A colour name or #RRGGBB."}
   {:path [:opacity] :kind :number :min 0.0 :max 1.0 :step 0.05 :scale 100 :unit "%" :slider true :default 0.8
    :title "Opacity" :description "How solid the text is."}
   {:path [:border] :kind :integer :min 0 :max 20 :unit "px" :default 2
    :title "Outline" :description "Width of the dark outline that keeps text readable on bright frames."}
   {:path [:font-path] :kind :file
    :title "Font" :description "A TrueType font file. Empty: the bundled font." :advanced true}])

(def hidden
  "Schema paths the form leaves out on purpose: a layer's :id names it for
  scripts and is kept as it is."
  #{[:texts :id]})

(def required-with
  "Keys the schema requires next to a key the form may set alone: setting
  `every-s` in a profile that inherits the animation needs its `type` too."
  {[:logo :animation] [:type]})

(def layer-required
  "Keys a new layer of each kind starts with (the schema requires them)."
  {:continuous {:content "© Your studio"}
   :scheduled  {:content "© Your studio" :at [5.0] :duration-s 3.0}
   :subliminal {:content "© Your studio"}
   :random     {:content "© Your studio"}})

;; ---------------------------------------------------------------------------
;; Paths and ids

(defn path-id
  "[:texts 0 :offset :x] -> \"texts.0.offset.x\": the row id every UI and the
  API use."
  [path]
  (str/join "." (map #(if (keyword? %) (name %) (str %)) path)))

(defn- index-segment? [s] (boolean (re-matches #"(0|[1-9][0-9]?)" s)))

(defn parse-id
  "\"texts.0.offset.x\" -> [:texts 0 :offset :x], nil unless it names a field
  of the catalog."
  [id]
  (let [segs (str/split (str id) #"\.")]
    (if (and (= "texts" (first segs)) (index-segment? (str (second segs))))
      (let [inner (mapv keyword (drop 2 segs))]
        (when (some #(= inner (:path %)) layer-fields)
          (into [:texts (parse-long (second segs))] inner)))
      (let [path (mapv keyword segs)]
        (when (some #(= path (:path %)) fields)
          path)))))

(defn field-of
  "The catalog entry for a settings path, nil if none."
  [path]
  (if (= :texts (first path))
    (let [inner (vec (drop 2 path))] (some #(when (= inner (:path %)) %) layer-fields))
    (some #(when (= (vec path) (:path %)) %) fields)))

;; ---------------------------------------------------------------------------
;; Values as text

(defn- round-to
  "`x` rounded half up to `places` decimals."
  [x places]
  (let [f (reduce * 1 (repeat places 10))]
    (/ (number/round-half-up (* x f)) (* 1.0 f))))

(defn number-text
  "A number as people read it: no trailing .0, at most three decimals."
  [x]
  (let [r (round-to x 3)
        i (number/floor-int r)]
    (if (== r i) (str i) (str r))))

(defn- option-value-text [v] (if (keyword? v) (name v) (str v)))

(defn- option-label [field v]
  (or (get (:labels field) v)
      (when (= [:mode] (:path field))
        (words (features/mode-display-name v)))
      (words v)))

(defn value-text
  "A field's value as the form shows it."
  [field v]
  (cond
    (nil? v)                    ""
    (= :boolean (:kind field))  (if v "On" "Off")
    (= :enum (:kind field))     (option-label field v)
    (= :seconds-list (:kind field)) (str/join ", " (map number-text v))
    (number? v)                 (str (number-text (* v (:scale field 1)))
                                     (when-let [u (:unit field)] (str (when-not (= "%" u) " ") u)))
    :else                       (str v)))

(defn input-text
  "A field's value as its control starts: what a person would type."
  [field v]
  (cond
    (nil? v)                        ""
    (= :enum (:kind field))         (option-value-text v)
    (= :seconds-list (:kind field)) (str/join ", " (map number-text v))
    (number? v)                     (number-text (* v (:scale field 1)))
    :else                           v))

;; ---------------------------------------------------------------------------
;; Parsing what people type

(defn- invalid [field msg]
  (throw (ex-info (str (:title field) ": " msg)
                  {:wmark/error :invalid :field (:title field)})))

(defn- in-bounds [field x]
  (let [{:keys [min max]} field
        s (:scale field 1)]
    (cond
      (and min (< x min)) (invalid field (str "at least " (number-text (* min s)) (when (:unit field) (str " " (:unit field)))))
      (and max (> x max)) (invalid field (str "at most " (number-text (* max s)) (when (:unit field) (str " " (:unit field)))))
      :else x)))

(defn- number-in [field raw]
  (if (number? raw)
    raw
    (or (parse-double (str/replace (str/trim (str raw)) "," "."))
        (invalid field "a number, please"))))

(defn parse-input
  "What a control sent -> the field's value, or nil for \"not set\". Text
  from a text box, numbers or booleans from JSON. Throws :invalid with a
  message fit for the person who typed it."
  [field raw]
  (let [blank? (or (nil? raw) (and (string? raw) (str/blank? raw)))]
    (case (:kind field)
      :boolean (cond (boolean? raw) raw
                     (= "true" raw) true
                     (= "false" raw) false
                     blank? nil
                     :else (invalid field "on or off"))
      :integer (when-not blank?
                 (let [x (number-in field raw)
                       i (number/floor-int x)]
                   (when-not (== x i) (invalid field "a whole number, please"))
                   (in-bounds field i)))
      :number  (when-not blank?
                 (in-bounds field (/ (* 1.0 (number-in field raw)) (:scale field 1))))
      :enum    (when-not blank?
                 (or (some #(when (= (str/trim (str raw)) (option-value-text %)) %) (:options field))
                     (invalid field (str "one of " (str/join ", " (map option-value-text (:options field)))))))
      :seconds-list (when-not blank?
                      (let [xs (mapv #(in-bounds field (number-in field %))
                                     (remove str/blank? (str/split (str raw) #"[,;\s]+")))]
                        (cond (empty? xs) nil
                              (> (count xs) 500) (invalid field "at most 500 times")
                              :else (mapv #(* 1.0 %) xs))))
      :color   (when-not blank?
                 (let [s (str/trim (str raw))]
                   (if (re-matches #"(#[0-9A-Fa-f]{6}|[a-zA-Z]+)" s) s (invalid field "a colour name or #RRGGBB"))))
      (when-not blank?
        (let [s (str/trim (str raw))]
          (if (and (:max field) (> (count s) (:max field)))
            (invalid field (str "at most " (:max field) " characters"))
            s))))))

;; ---------------------------------------------------------------------------
;; The model

(defn- source-of
  "Where the value at `path` came from, in source-labels' terms."
  [{:keys [provenance base]} path]
  (case (get provenance path)
    :profile   (if (= :latest (:kind base)) :last-run :set-here)
    :overrides :unsaved
    :defaults  :default
    :unset))

(defn- options-of [field entitled?]
  (vec (for [v (:options field)
             :let [feature (when (= [:mode] (:path field)) (keyword "text.mode" (name v)))
                   tier    (when feature (features/tier feature))]]
         (cond-> {:value (option-value-text v) :label (option-label field v)}
           (= :pro tier) (assoc :tier "pro" :locked (not (entitled? feature)))))))

(defn- row
  [field id value source entitled?]
  (cond-> {:id          id
           :path        (:path field)
           :kind        (:kind field)
           :title       (:title field)
           :description (:description field)
           :value       value
           :text        (value-text field value)
           :input       (input-text field value)
           :source      source}
    (and (nil? value) (some? (:default field)))
    (assoc :text (str (value-text field (:default field)) " (default)")
           :input (input-text field (:default field)))
    (:min field)       (assoc :min (* (:min field) (:scale field 1)))
    (:max field)       (assoc :max (* (:max field) (:scale field 1)))
    (:step field)      (assoc :step (* (:step field) (:scale field 1)))
    (:unit field)      (assoc :unit (:unit field))
    (:slider field)    (assoc :slider true)
    (:multiline field) (assoc :multiline true)
    (:advanced field)  (assoc :advanced true)
    (:options field)   (assoc :options (options-of field entitled?))))

(defn- layer-model [r i layer entitled?]
  (let [mode    (features/canonical-mode (:mode layer))
        feature (keyword "text.mode" (name mode))
        source  (source-of r [:texts])]
    (cond-> {:index    i
             :id       (path-id [:texts i])
             :mode     (name mode)
             :title    (str "Text layer " (inc i))
             :subtitle (words (features/mode-display-name mode))
             :rows     (vec (for [f layer-fields
                                  :when (or (nil? (:modes f)) (contains? (:modes f) mode))
                                  :let [path (into [:texts i] (:path f))]]
                              (row f (path-id path) (get-in layer (:path f)) source entitled?)))}
      (= :pro (features/tier feature))
      (assoc :tier "pro" :locked (not (entitled? feature))))))

(defn model
  "The form for resolved settings `r` ({:settings :provenance :base}, as
  watermark.config/resolve-settings returns them):
    {:categories [{:id :title :description :rows [row ...]} ...]
     :texts      {:layers [layer ...] :modes [option ...] :source kw}
     :sources    source-labels}
  A row is {:id \"logo.opacity\" :kind :number :title :description
  :value 0.85 :text \"85%\" :input \"85\" :source :default ...}, plus the
  field's bounds, unit and options. `entitled?` (feature id -> boolean)
  marks Pro choices as locked; by default nothing is."
  ([r] (model r {}))
  ([{:keys [settings] :as r} {:keys [entitled?] :or {entitled? (constantly true)}}]
   {:categories (vec (for [{:keys [id] :as c} categories]
                       (assoc c :rows (vec (for [f fields
                                                 :when (= id (first (:path f)))]
                                             (row f (path-id (:path f)) (get-in settings (:path f))
                                                  (source-of r (:path f)) entitled?))))))
    :texts      {:layers (vec (map-indexed #(layer-model r %1 %2 entitled?) (:texts settings)))
                 :modes  (options-of (first layer-fields) entitled?)
                 :source (source-of r [:texts])}
    :sources    source-labels}))

;; ---------------------------------------------------------------------------
;; Edits: profile settings in, profile settings out

(defn- dissoc-in
  "Remove the value at `path`, and any map that leaves empty."
  [m [k & more]]
  (if (seq more)
    (let [child (dissoc-in (get m k) more)]
      (if (and (map? child) (empty? child)) (dissoc m k) (assoc m k child)))
    (dissoc m k)))

(defn- own-layers
  "The layers a profile edits: its own, or a copy of what it inherits (text
  layers replace as a whole, so the first edit takes them all over)."
  [profile effective]
  (vec (or (:texts profile) (:texts effective) [])))

(defn- layer-index! [layers i]
  (when-not (and (integer? i) (< -1 i (count layers)))
    (throw (ex-info (str "There is no text layer " (if (integer? i) (inc i) i) ".")
                    {:wmark/error :invalid})))
  i)

(defn- with-required
  "Copy required siblings from the effective settings when a profile sets a
  key inside a block it otherwise inherits."
  [profile effective path]
  (reduce (fn [p [block ks]]
            (if (and (= block (vec (take (count block) path))) (> (count path) (count block)))
              (reduce (fn [p k]
                        (let [kp (conj block k)]
                          (if (some? (get-in p kp)) p
                              (if-some [v (get-in effective kp)] (assoc-in p kp v) p))))
                      p ks)
              p))
          profile required-with))

(defn- mode-change
  "A layer switched to `mode`: shared fields stay, the old kind's own fields
  go, and the new kind's required ones start from their defaults."
  [layer mode]
  (let [own  (set (for [f layer-fields :when (:modes f)] (first (:path f))))
        keep (set (for [f layer-fields
                        :when (or (nil? (:modes f)) (contains? (:modes f) mode))]
                    (first (:path f))))]
    (merge (get layer-required mode)
           (into {} (remove (fn [[k _]] (and (own k) (not (keep k))))) layer)
           {:mode mode})))

(defn edit
  "Apply one edit to a profile's own settings; `effective` is what it
  resolves to (for inherited blocks and layers). Operations:
    {:op :set   :id \"logo.opacity\" :value \"70\"}   typed text or JSON value
    {:op :unset :id \"logo.opacity\"}                 back to the lower layer
    {:op :add-layer :mode \"scheduled\"}
    {:op :remove-layer :index 0}
    {:op :move-layer :index 1 :delta -1}
  Throws :invalid for an unknown field or an unusable value."
  [profile effective {:keys [op id value mode index delta]}]
  (let [op (keyword op)]
    (case op
      (:set :unset)
      (let [path  (or (parse-id id)
                      (throw (ex-info (str "No setting \"" id "\".") {:wmark/error :invalid})))
            field (field-of path)
            v     (when (= op :set) (parse-input field value))]
        (if (= :texts (first path))
          (let [layers (own-layers profile effective)
                i      (layer-index! layers (second path))
                inner  (vec (drop 2 path))
                layer  (get layers i)
                layer  (cond
                         (= inner [:mode])
                         (if (nil? v) layer (mode-change layer v))

                         (nil? v)
                         (if (#{[:content]} inner)
                           (invalid field "a layer needs its text")
                           (dissoc-in layer inner))

                         :else (assoc-in layer inner v))]
            (assoc profile :texts (assoc layers i layer)))
          (if (nil? v)
            (dissoc-in profile path)
            (assoc-in (with-required profile effective path) path v))))

      :add-layer
      (let [layers (own-layers profile effective)
            m      (or (some #(when (= (str mode) (name %)) %) layer-modes) :continuous)]
        (when (>= (count layers) 8)
          (throw (ex-info "A profile can have at most 8 text layers." {:wmark/error :invalid})))
        (assoc profile :texts (conj layers (assoc (get layer-required m) :mode m))))

      :remove-layer
      (let [layers (own-layers profile effective)
            i      (layer-index! layers index)]
        (assoc profile :texts (into (subvec layers 0 i) (subvec layers (inc i)))))

      :move-layer
      (let [layers (own-layers profile effective)
            i      (layer-index! layers index)
            j      (+ i (or delta 0))]
        (if (< -1 j (count layers))
          (assoc profile :texts (assoc layers i (get layers j) j (get layers i)))
          profile))

      (throw (ex-info (str "Unknown edit " (pr-str op) ".") {:wmark/error :invalid})))))
