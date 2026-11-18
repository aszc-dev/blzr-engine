(ns blzr.engine.runtime
  "Headless Web Audio core. No re-frame require — every op takes an explicit
   Runtime handle so tests and offline rendering can mount a graph on a
   private OfflineAudioContext without touching a live context."
  (:require
   [blzr.engine.specs :as specs]
   [lambdaisland.glogi :as log]
   [goog.object :as gobj]))

(defrecord Runtime [ctx nodes aux-nodes node-types node-specs worklet-base worklets-ready?])

;; ---- construction -----------------------------------------------------

(defn create-runtime
  "Build a Runtime around an existing context (online, offline, or nil for a
   caller that creates the context lazily and assoc's it as :ctx).
   `:worklet-base` is the URL prefix the worklet modules are served under;
   `:node-specs` is the node-type registry, blzr.engine.specs/node-specs by
   default."
  ([ctx] (create-runtime ctx {}))
  ([ctx {:keys [worklet-base node-specs]
         :or {worklet-base "/worklets/" node-specs specs/node-specs}}]
   (map->Runtime {:ctx ctx
                  :nodes (js/Map.)
                  :aux-nodes (js/Map.)
                  :node-types (js/Map.)
                  :node-specs node-specs
                  :worklet-base worklet-base
                  :worklets-ready? false})))

(defn create-audio-context []
  (js/AudioContext.))

;; ---- worklets -----------------------------------------------------------

(def worklet-files
  ["mul-processor.js"
   "add-processor.js"
   "noise-processor.js"
   "env-follow-processor.js"
   "granular-processor.js"])

(defn add-worklet-module!
  "Loads one AudioWorklet module into the runtime's context; returns a Promise
   of the runtime. Works on a running context. A processor name can be
   registered only once per context, so a reloaded module needs a new one."
  [{:keys [ctx] :as rt} url]
  (.then (.addModule (.-audioWorklet ctx) url) (constantly rt)))

(defn register-worklets!
  "Loads the built-in worklet modules from :worklet-base. Returns a Promise of
   the runtime with :worklets-ready? true."
  [{:keys [worklet-base] :as rt}]
  (.then (js/Promise.all (into-array (map #(add-worklet-module! rt (str worklet-base %)) worklet-files)))
         (fn [_] (assoc rt :worklets-ready? true))))

;; ---- pure node-building helpers ------------------------------------------

(defn make-distortion-curve [curve-type]
  (let [n 44100
        curve (js/Float32Array. n)]
    (dotimes [i n]
      (let [x (dec (* 2 (/ i n)))]
        (aset curve i
              (case curve-type
                "clean" x
                "soft"  (js/Math.tanh (* 2 x))
                "hard"  (js/Math.max -1 (js/Math.min 1 (* 3 x)))
                "fuzz"  (let [sign (if (>= x 0) 1 -1)]
                          (* sign (- 1 (js/Math.exp (* -10 (js/Math.abs x))))))
                x))))
    curve))

(defn generate-impulse-response [ctx ir-type]
  (let [[duration decay-rate] (case ir-type
                                "hall"   [3.0 2.0]
                                "room"   [1.0 1.5]
                                "plate"  [2.0 3.0]
                                "spring" [0.5 5.0]
                                [1.0 2.0])
        sample-rate (.-sampleRate ctx)
        length (int (* duration sample-rate))
        buffer (.createBuffer ctx 2 length sample-rate)]
    (doseq [ch [0 1]]
      (let [data (.getChannelData buffer ch)]
        (dotimes [i length]
          (let [t (/ i sample-rate)]
            (aset data i (* (dec (* 2 (js/Math.random)))
                            (js/Math.exp (- (* decay-rate (/ t duration))))))))))
    buffer))

(defn make-glottal-wave
  "Create a PeriodicWave with 1/n^2 harmonic amplitudes (-12 dB/oct glottal rolloff)."
  [ctx]
  (let [n 64
        real (js/Float32Array. (inc n))
        imag (js/Float32Array. (inc n))]
    (dotimes [i n]
      (let [k (inc i)]
        (aset imag k (/ 1.0 (* k k)))))
    (.createPeriodicWave ctx real imag)))

;; ---- node lifecycle -------------------------------------------------------

(defn create-node!
  "Creates the Web Audio node for {:keys [id type data]} from its spec."
  [{:keys [ctx nodes aux-nodes node-types node-specs]} {:keys [id type data]}]
  (let [spec (get node-specs type)]
    (.set node-types id type)

    (cond
      (= type :out)
      (do
        (.set nodes id (.-destination ctx))
        (log/info :audio {:msg "Registered Output Node" :id id}))

      (= :midi-cv (:category spec))
      (let [make-cs (fn []
                      (let [cs (.createConstantSource ctx)]
                        (set! (.. cs -offset -value) 0)
                        (.start cs)
                        cs))
            [_main-out & aux-outs] (:cv-outputs spec)]
        (.set nodes id (make-cs))
        (when (seq aux-outs)
          (.set aux-nodes id (into {} (map (fn [k] [k (make-cs)]) aux-outs))))
        (log/info :audio {:msg "Created MIDI CV Node" :id id :type type}))

      (= type :sampler)
      (let [gain (.createGain ctx)]
        (set! (.. gain -gain -value) 1.0)
        (.set nodes id gain)
        (log/info :audio {:msg "Created Sampler (GainNode wrapper)" :id id}))

      (= type :mic)
      (let [gain (.createGain ctx)]
        (set! (.. gain -gain -value) 1.0)
        (.set nodes id gain)
        (-> (.. js/navigator -mediaDevices (getUserMedia #js {:audio true}))
            (.then (fn [stream]
                     (let [source (.createMediaStreamSource ctx stream)]
                       (.connect source gain)
                       (.set aux-nodes id {:stream stream :source source})
                       (log/info :audio {:msg "Microphone active" :id id}))))
            (.catch (fn [err]
                      (log/error :audio {:msg "Microphone access denied" :err err}))))
        (log/info :audio {:msg "Created Mic Node (waiting for permission)" :id id}))

      (:worklet-name spec)
      (try
        (let [proc-opts (when-let [ks (seq (:processor-options spec))]
                          (into {} (map (fn [k] [k (get data k (get-in spec [:params k :default]))])) ks))
              opts (cond-> (or (:worklet-options spec) {})
                     proc-opts (assoc :processorOptions proc-opts))
              n (js/AudioWorkletNode. ctx (:worklet-name spec) (clj->js opts))]
          (doseq [[k v] (:params spec)]
            (let [val (get data k (:default v))
                  p (.get (.-parameters n) (name k))]
              (when p (set! (.-value p) val))))
          (.set nodes id n)
          (log/info :audio {:msg "Created Operator Node" :type type}))
        (catch js/Error e
          (log/error :audio {:msg "Worklet create failed" :err e})))

      spec
      (let [factory-method-name (str "create" (or (:native-type spec) "Gain"))
            factory-fn (aget ctx factory-method-name)]

        (if factory-fn
          (let [args (or (:create-args spec) [])
                node (.apply factory-fn ctx (to-array args))
                special-params (cond
                                 (= type :dist) #{:curve}
                                 (= type :reverb) #{:ir}
                                 (and (= type :osc) (= (get data :type) "glottal")) #{:type}
                                 :else #{})]
            (when node
              (when (= type :dist)
                (let [curve-type (get data :curve "soft")]
                  (set! (.-curve node) (make-distortion-curve curve-type))))

              (when (= type :reverb)
                (let [ir-type (get data :ir "room")]
                  (set! (.-buffer node) (generate-impulse-response ctx ir-type))))

              (when (and (= type :osc) (= (get data :type) "glottal"))
                (.setPeriodicWave node (make-glottal-wave ctx)))

              (doseq [[k v] (:params spec)
                      :when (not (contains? special-params k))]
                (let [val (get data k (:default v))
                      param-name (name k)]
                  (case (:type v)
                    :audio-param
                    (when-let [audio-param (gobj/get node param-name)] (set! (.-value audio-param) val))

                    :enum
                    (gobj/set node param-name val)

                    :bool
                    (gobj/set node param-name val)

                    :number
                    (gobj/set node param-name val)

                    nil)))

              (when (exists? (.-start node)) (.start node))

              (.set nodes id node)
              (log/info :audio {:msg "Created Generic Node" :type type})))

          (log/error :audio {:msg "Unknown WebAudio factory" :method factory-method-name})))

      :else
      (log/error :audio {:msg "No spec found for node type" :type type}))))

(defn get-audio-param [node param-name]
  (or (gobj/get node param-name)
      (when (and node (exists? (.-parameters node)))
        (.get (.-parameters node) param-name))))

(defn set-param!
  [{:keys [ctx nodes aux-nodes node-types node-specs]} {:keys [id param value]}]
  (let [node (.get nodes id)
        node-type (.get node-types id)
        processor-option? (set (:processor-options (get node-specs node-type)))]
    (when node
      (let [param-name (name param)]
        (cond
          (and (= node-type :dist) (= param :curve))
          (set! (.-curve node) (make-distortion-curve value))

          (and (= node-type :reverb) (= param :ir))
          (set! (.-buffer node) (generate-impulse-response ctx value))

          (processor-option? param)
          (.postMessage (.-port node) (clj->js {param value}))

          (and (= node-type :sampler) (case param (:detune :loop :playbackRate) param nil))
          (when-let [aux (.get aux-nodes id)]
            (when-let [source (:source aux)]
              (case param
                :playbackRate (set! (.. source -playbackRate -value) value)
                :detune       (set! (.. source -detune -value) value)
                :loop         (set! (.-loop source) value)
                nil)))

          (and (= node-type :osc) (= param :type) (= value "glottal"))
          (.setPeriodicWave node (make-glottal-wave ctx))

          (and (= node-type :osc) (= param :type) (not= value "glottal"))
          (gobj/set node "type" value)

          :else
          (let [prop (gobj/get node param-name)
                worklet-param (when (exists? (.-parameters node))
                                (.get (.-parameters node) param-name))]
            (cond
              (or (instance? js/AudioParam prop)
                  (instance? js/AudioParam worklet-param))
              (let [p (or prop worklet-param)
                    now (.-currentTime ctx)
                    tc (cond
                         (= param-name "offset") 0.015
                         (and (= node-type :osc) (= param-name "frequency")) 0.005
                         (and (= node-type :flt) (= param-name "frequency")) 0.010
                         :else 0.020)]
                (.cancelScheduledValues p now)
                (.setTargetAtTime p value now tc))

              :else
              (gobj/set node param-name value))))))))

(defn schedule-param-ramp!
  [{:keys [nodes]} {:keys [id param schedule]}]
  (let [node (.get nodes id)
        param-name (name param)]
    (when node
      (let [prop (gobj/get node param-name)
            worklet-param (when (exists? (.-parameters node))
                            (.get (.-parameters node) param-name))
            p (or (when (instance? js/AudioParam prop) prop)
                  (when (instance? js/AudioParam worklet-param) worklet-param))]
        (when (and p (seq schedule))
          (let [[first-entry & rest-entries] schedule]
            (.cancelScheduledValues p (:at first-entry))
            (.setValueAtTime p (:value first-entry) (:at first-entry))
            (doseq [{:keys [value at]} rest-entries]
              (.linearRampToValueAtTime p value at))))))))

(defn load-sample!
  [{:keys [ctx nodes aux-nodes]} {:keys [id file]}]
  (let [gain-node (.get nodes id)
        reader (js/FileReader.)]
    (set! (.-onload reader)
          (fn [e]
            (-> (.decodeAudioData ctx (.. e -target -result))
                (.then (fn [buffer]
                         (when-let [old-aux (.get aux-nodes id)]
                           (when-let [old-src (:source old-aux)]
                             (try (.stop old-src) (catch js/Error _))
                             (.disconnect old-src)))

                         (let [source (.createBufferSource ctx)]
                           (set! (.-buffer source) buffer)
                           (.connect source gain-node)
                           (.start source)
                           (.set aux-nodes id {:buffer buffer :source source})
                           (log/info :audio {:msg "Sample loaded and playing" :id id}))))
                (.catch (fn [err]
                          (log/error :audio {:msg "Sample decode failed" :err err}))))))
    (.readAsArrayBuffer reader file)))

(defn play-sample!
  [{:keys [ctx nodes aux-nodes]} {:keys [id]}]
  (let [gain-node (.get nodes id)]
    (when-let [aux (.get aux-nodes id)]
      (when-let [buffer (:buffer aux)]
        (when-let [old-src (:source aux)]
          (try (.stop old-src) (catch js/Error _))
          (.disconnect old-src))

        (let [source (.createBufferSource ctx)]
          (set! (.-buffer source) buffer)
          (.connect source gain-node)
          (.start source)
          (.set aux-nodes id (assoc aux :source source))
          (log/info :audio {:msg "Sample retriggered" :id id}))))))

(defn- stop-and-disconnect! [node]
  (when node
    (try
      (when (exists? (.-stop node)) (.stop node))
      (when (exists? (.-disconnect node)) (.disconnect node))
      (catch js/Error _))))

(defn destroy-node!
  [{:keys [nodes aux-nodes node-types node-specs]} id]
  (when-let [node (.get nodes id)]
    (stop-and-disconnect! node)
    (.delete nodes id))

  (when-let [aux (.get aux-nodes id)]
    (let [spec (get node-specs (.get node-types id))]
      (if (= :midi-cv (:category spec))
        (doseq [[_ cs] aux] (stop-and-disconnect! cs))
        (do (stop-and-disconnect! (:source aux))
            (when-let [stream (:stream aux)]
              (doseq [track (.getTracks stream)]
                (.stop track))))))
    (.delete aux-nodes id))

  (.delete node-types id)
  (log/info :audio {:msg "Destroyed Node" :id id}))

(defn resolve-source-node
  [{:keys [nodes aux-nodes node-types node-specs]} source-id source-handle target-handle]
  (let [aux (.get aux-nodes source-id)
        src-type (.get node-types source-id)
        spec (get node-specs src-type)
        midi-cv? (= :midi-cv (:category spec))
        normalized-source-handle (when (and source-handle (not= source-handle ""))
                                   source-handle)
        effective-handle
        (if midi-cv?
          (let [cv-set (set (:cv-outputs spec))
                explicit (keyword normalized-source-handle)]
            (cond
              (and normalized-source-handle
                   (not (case normalized-source-handle ("audio-out" "out") normalized-source-handle nil))
                   (cv-set explicit))
              explicit

              :else
              (or (get (:handle-inference spec) target-handle)
                  (first (:cv-outputs spec)))))
          (keyword normalized-source-handle))
        aux-node (when (and effective-handle aux)
                   (get aux effective-handle))]
    (or aux-node (.get nodes source-id))))

(defn connect!
  [{:keys [nodes node-types node-specs] :as rt} {:keys [source-id target-id source-handle target-handle]}]
  (let [src (resolve-source-node rt source-id source-handle target-handle)
        tgt (.get nodes target-id)]

    (if (and src tgt)
      (try
        (let [handle-name (name (or target-handle "audio-in"))
              prop (gobj/get tgt handle-name)
              worklet-param (when (exists? (.-parameters tgt))
                              (.get (.-parameters tgt) handle-name))
              target-param (or prop worklet-param)

              src-type (.get node-types source-id)
              tgt-type (.get node-types target-id)
              src-spec (get node-specs src-type)
              tgt-spec (get node-specs tgt-type)
              output-idx (when (and source-handle src-spec)
                           (get (:output-indices src-spec) (keyword source-handle)))
              input-idx (when (and target-handle tgt-spec)
                          (get (:input-indices tgt-spec) (keyword target-handle)))]

          (cond
            (instance? js/AudioParam target-param)
            (do
              (if output-idx
                (.connect src target-param output-idx)
                (.connect src target-param))
              (log/info :audio {:msg "Connected Modulation"
                                :from source-id :to target-id :param handle-name}))

            (or output-idx input-idx)
            (do
              (.connect src tgt (or output-idx 0) (or input-idx 0))
              (log/info :audio {:msg "Connected Indexed"
                                :from source-id :to target-id
                                :out-idx output-idx :in-idx input-idx}))

            :else
            (do
              (.connect src tgt)
              (log/info :audio {:msg "Connected Audio"
                                :from source-id :to target-id :input handle-name}))))

        (catch js/Error e
          (log/error :audio {:msg "Connection Failed" :error e})))

      (log/warn :audio {:msg "Missing nodes for connection"}))))

(defn disconnect!
  [{:keys [nodes node-types node-specs] :as rt} {:keys [source-id target-id source-handle target-handle]}]
  (let [src (resolve-source-node rt source-id source-handle target-handle)
        tgt (.get nodes target-id)]
    (when (and src tgt)
      (try
        (let [tgt-type (.get node-types target-id)
              src-spec (get node-specs (.get node-types source-id))
              tgt-spec (get node-specs tgt-type)
              output-idx (when (and source-handle src-spec)
                           (get (:output-indices src-spec) (keyword source-handle)))
              input-idx (when (and target-handle tgt-spec)
                          (get (:input-indices tgt-spec) (keyword target-handle)))]

          (cond
            (and target-handle (not= target-handle "audio-in")
                 (not (get (:input-indices tgt-spec) (keyword target-handle))))
            (if-let [param (get-audio-param tgt target-handle)]
              (.disconnect src param)
              (log/warn :audio {:msg "Parameter to disconnect doesn't exist" :param target-handle}))

            (or output-idx input-idx)
            (.disconnect src tgt (or output-idx 0) (or input-idx 0))

            :else
            (.disconnect src tgt)))

        (log/info :audio {:msg "Disconnected" :src source-id :tgt target-id})
        (catch js/Error e
          (log/warn :audio {:msg "Disconnect warning" :exception e}))))))

;; ---- whole-graph ops -------------------------------------------------

(defn has-node? [{:keys [nodes]} id] (.has nodes id))

(defn reset-graph!
  "Disconnects every live node and clears bookkeeping, leaving the context
   running. Used by the OSC patch-apply protocol between full teardown and
   :audio/restore-graph rebuild."
  [{:keys [nodes aux-nodes node-types]}]
  (.forEach nodes (fn [node _id]
                    (try
                      (when (exists? (.-disconnect node)) (.disconnect node))
                      (catch js/Error _))))
  (.clear nodes)
  (.clear aux-nodes)
  (.clear node-types))

(defn stop-all!
  "Stops and disconnects every live node, clears bookkeeping. Used for full
   teardown (test isolation / context close) — unlike reset-graph!, this also
   stops sources so nothing keeps playing after the context closes."
  [{:keys [nodes aux-nodes node-types]}]
  (.forEach nodes (fn [node _id]
                    (try
                      (when (exists? (.-stop node)) (.stop node))
                      (when (exists? (.-disconnect node)) (.disconnect node))
                      (catch js/Error _))))
  (.clear nodes)
  (.clear node-types)
  (.clear aux-nodes))

(defn mount!
  "Instantiates a flow {:nodes {id {:type :data}} :edges [..]} on this
   runtime and returns rt. Nodes of type \"module\" are grouping placeholders
   and are skipped. Assumes the worklets the flow needs are registered."
  [rt {:keys [nodes edges]}]
  (let [real (into {} (filter (fn [[_ node]] (not= (name (:type node)) "module"))) nodes)]
    (doseq [[id node] real]
      (create-node! rt {:id id :type (keyword (:type node)) :data (:data node)}))
    (doseq [e edges]
      (connect! rt {:source-id (:source e) :source-handle (:sourceHandle e)
                    :target-id (:target e) :target-handle (:targetHandle e)}))
    rt))

(defn unmount! [rt]
  (doseq [id (js/Array.from (.keys (:nodes rt)))] (destroy-node! rt id))
  rt)

;; ---- context lifecycle -----------------------------------------------

(defn resume! [{:keys [ctx]}] (when (= (.-state ctx) "suspended") (.resume ctx)))
(defn suspend! [{:keys [ctx]}] (when (= (.-state ctx) "running") (.suspend ctx)))

(defn panic!
  [{:keys [ctx] :as rt}]
  (.forEach (:nodes rt) (fn [node _id]
                          (try
                            (when (exists? (.-disconnect node)) (.disconnect node))
                            (catch js/Error _))))
  (when (= (.-state ctx) "running") (.suspend ctx)))

;; ---- tap access --------------------------------------------------------

(defn get-node [rt id] (.get (:nodes rt) id))
