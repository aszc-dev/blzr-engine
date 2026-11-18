(ns blzr.engine.instrument.core
  "The instrument shell: validate an Instrument, mount its graph on a fresh
   runtime, resolve surface controls to set-param! bindings, instantiate
   taps, and hand back a bundle a caller-supplied UI renders. No node editor."
  (:require [blzr.engine.instrument.schema :as schema]
            [blzr.engine.runtime :as rt]
            [blzr.engine.tap :as tap]))

(defn- map-range
  "Maps a normalized UI value v in [0,1] onto [lo hi] under `curve`."
  [v [lo hi] curve]
  (let [v (max 0.0 (min 1.0 (or v 0.0)))]
    (case curve
      :exp (if (pos? lo)
             (* lo (js/Math.pow (/ hi lo) v))
             (+ lo (* v v (- hi lo)))) ;; lo<=0: quadratic curve as an exp fallback
      :db  (let [db (+ lo (* v (- hi lo)))]
             (js/Math.pow 10.0 (/ db 20.0)))
      (+ lo (* v (- hi lo))))))

(defn- resolve-target
  "ParamRef -> a setter fn. Routes through blzr.engine.runtime/set-param!, which
   already handles both native AudioParams and worklet parameters."
  [rt {:param/keys [node key]}]
  (fn [v] (rt/set-param! rt {:id node :param key :value v})))

(defn- bind-control
  "Control -> {:control ctrl :set (fn [ui-value])}. Applies range/curve
   mapping. xy-pad/xy-scan controls have two targets and split a [x y]
   2-vector across them (x -> first target, y -> second); every other type
   fans one scalar out to all targets. Controls with no targets (e.g. a
   :curve control that drives something other than a ParamRef) get a no-op
   :set — the instrument that needs bespoke wiring overrides it after mount."
  [rt {:control/keys [type target] :as ctrl}]
  (let [setters (mapv (fn [{:target/keys [ref range curve]}]
                        (let [apply! (resolve-target rt ref)]
                          (if range
                            (fn [v] (apply! (map-range v range curve)))
                            apply!)))
                      target)
        xy? (contains? #{:xy-pad :xy-scan} type)
        set-fn (cond
                 xy? (fn [[x y]]
                       (when-let [sx (first setters)] (sx x))
                       (when-let [sy (second setters)] (sy y)))
                 (seq setters) (fn [v] (doseq [s setters] (s v)))
                 :else (fn [_] nil))]
    {:control ctrl :set set-fn}))

(defn mount-instrument
  "Validate, mount graph on a fresh runtime, wire taps + control bindings.
   Returns a Promise of {:runtime :taps {tap-id {:value :stop}}
   :controls [{:control :set}] :unlock (fn [])}. :unlock resumes the context
   and must be called from a real user gesture — the context starts
   suspended. `runtime-opts` go to blzr.engine.runtime/create-runtime."
  ([ctx inst] (mount-instrument ctx inst {}))
  ([ctx inst runtime-opts]
   (when-not (schema/validate inst)
     (throw (ex-info "Invalid instrument" {:errors (schema/explain inst)})))
   (when-let [probs (seq (schema/cross-check inst))]
     (throw (ex-info "Dangling refs in instrument" {:problems probs})))
   (.then (rt/register-worklets! (rt/create-runtime ctx runtime-opts))
          (fn [runtime]
            (rt/mount! runtime (:instrument/graph inst))
            {:runtime runtime
             :taps (into {} (map (fn [t] [(:tap/id t) (tap/tap-source runtime t)])) (schema/taps-of inst))
             :controls (mapv #(bind-control runtime %) (schema/controls-of inst))
             :unlock (fn [] (rt/resume! runtime))}))))

(defn unmount-instrument!
  "Tears down every tap and destroys every node. Does not close the context —
   callers that own the context close it themselves."
  [{:keys [runtime taps]}]
  (doseq [[_ {:keys [stop]}] taps] (stop))
  (rt/unmount! runtime))
