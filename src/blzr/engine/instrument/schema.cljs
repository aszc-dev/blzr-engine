(ns blzr.engine.instrument.schema
  "The one data model for the Instrument layer. Pure data + malli — no audio,
   no DOM, no re-frame. Every other reader (runtime mount, UI generation, LLM
   structured output, test.check generators, humanized errors) derives its
   view from these schemas.

   :instrument/graph is the runtime's flow EDN verbatim — node shapes
   are not redefined here; see blzr.engine.specs/node-specs for the registry."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]))

;; ---- references (resolved at mount, validated structurally here) ----------

;; PascalCase intentional: these are malli schema type names, not plain values.
#_:splint/disable
(def NodeRef :string)                            ; a node id in :instrument/graph
#_:splint/disable
(def ParamRef [:map                               ; addresses one modulatable param
               [:param/node :string]
               [:param/key  :keyword]])

;; ---- ParamSpec: the modulation-boundary source of truth -------------------
;; One ParamSpec generates: worklet parameterDescriptors, a surface Control
;; default, and docs. :audio-rate decides AudioParam vs message.

#_:splint/disable
(def ParamSpec
  [:map
   [:param/key      :keyword]
   [:param/range    [:tuple :double :double]]
   [:param/default  :double]
   [:param/curve    {:optional true} [:enum :lin :exp :db]]
   [:param/unit     {:optional true} :string]
   [:param/audio-rate {:optional true} [:enum :a-rate :k-rate]]
   [:param/scope    {:optional true} [:enum :shared :per-voice]]])

;; ---- Control: param -> human input (supports 1 control -> many params) -----

(def Control
  [:map
   [:control/type   [:enum :knob :slider :xy-pad :curve :toggle :xy-scan]]
   [:control/label  :string]
   ;; target is a vector so one control can drive several params (spray = scan+pan).
   ;; Optional: a :curve control (e.g. a grain envelope editor) drives
   ;; something other than a ParamRef — the instrument wires it directly.
   [:control/target {:optional true}
    [:vector
     [:map
      [:target/ref   ParamRef]
      [:target/range {:optional true} [:tuple :double :double]]
      [:target/curve {:optional true} [:enum :lin :exp :db]]]]]
   [:control/opts   {:optional true} :map]])

;; ---- Tap: processed data the UI reads (never raw) --------------------------

(def Tap
  [:map
   [:tap/id     :keyword]
   [:tap/type   [:enum :waveform :spectrum :level :param :worklet-message]]
   [:tap/source [:or NodeRef ParamRef]]
   [:tap/opts   {:optional true} :map]])

;; ---- Voicing: first-class (see poly decision) ------------------------------

(def Voicing
  [:map
   [:voicing/mode     [:enum :none :mono :poly :paraphonic]]
   [:voicing/voices   {:optional true} :int]
   [:voicing/strategy {:optional true} [:enum :worklet :graph-clone]]
   [:voicing/steal    {:optional true} [:enum :round-robin :oldest :lowest :highest]]
   [:voicing/glide    {:optional true} :double]
   [:voicing/per-voice {:optional true} [:set :keyword]]])

;; ---- IO --------------------------------------------------------------------

(def IO
  [:map
   [:io/audio-in {:optional true} [:map [:source [:enum :mic :stream :none]]]]
   [:io/out      {:optional true} [:map [:target [:enum :master :stream :none]]]]
   [:io/midi     {:optional true} [:enum :none :mono :poly]]])

;; ---- Instrument (top) -------------------------------------------------------

(def Instrument
  [:map {:closed false}
   [:instrument/id      :keyword]
   [:instrument/name    {:optional true} :string]
   [:instrument/graph   [:map
                         [:nodes :map]
                         [:edges [:vector :map]]]]
   [:instrument/params  {:optional true} [:vector ParamSpec]]
   [:instrument/voicing Voicing]
   [:instrument/surface [:vector Control]]
   [:instrument/taps    [:vector Tap]]
   [:instrument/io      IO]])

;; ---- validation + humanized errors -----------------------------------------

(defn validate [inst] (m/validate Instrument inst))

(defn explain
  "Humanized errors for scaffolding + agent self-correction."
  [inst]
  (some-> (m/explain Instrument inst) (me/humanize)))

(defn ->json-schema
  "For LLM structured output / MCP tool schema."
  []
  (mjs/transform Instrument))

;; readers derive their view from the same data:
(defn params-of   [inst] (:instrument/params inst))
(defn taps-of     [inst] (:instrument/taps inst))
(defn controls-of [inst] (:instrument/surface inst))

;; ---- cross-check: referential integrity malli can't express structurally --

(defn- node-ids [inst]
  (set (keys (get-in inst [:instrument/graph :nodes]))))

(defn- param-keys [inst]
  (set (map :param/key (params-of inst))))

(defn- tap-source-node
  "A Tap's source is either a bare NodeRef (string) or a ParamRef (map)."
  [source]
  (if (map? source) (:param/node source) source))

(defn cross-check
  "Referential integrity the malli schema can't express structurally:
   every ParamRef/NodeRef in surface+taps must resolve into the graph;
   every :per-voice key in voicing must name a real param.
   Returns nil if ok, else a vector of {:where :ref :problem} maps."
  [inst]
  (let [nodes (node-ids inst)
        params (param-keys inst)
        surface-problems
        (for [{:control/keys [label target]} (controls-of inst)
              {:target/keys [ref]} target
              :let [node (:param/node ref)]
              :when (not (contains? nodes node))]
          {:where :surface :control label :ref ref :problem :dangling-node-ref})

        tap-problems
        (for [{:tap/keys [id source]} (taps-of inst)
              :let [node (tap-source-node source)]
              :when (not (contains? nodes node))]
          {:where :taps :tap id :ref source :problem :dangling-node-ref})

        voicing-problems
        (for [k (get-in inst [:instrument/voicing :voicing/per-voice])
              :when (not (contains? params k))]
          {:where :voicing :ref k :problem :unknown-param})

        problems (vec (concat surface-problems tap-problems voicing-problems))]
    (seq problems)))
