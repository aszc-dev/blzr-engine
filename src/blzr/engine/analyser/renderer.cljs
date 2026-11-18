(ns blzr.engine.analyser.renderer
  "Imperative canvas-2D consumer of render-plan data.

   Sole owner of canvas mutation. Each layer kind has a small
   draw-* fn — the renderer just dispatches on `:kind`. All
   geometry and text content arrives pre-computed in the plan
   (see blzr.engine.analyser.render-plan/build); this layer translates
   plan → canvas API calls and nothing else.

   Tested via a recording canvas stub — renderer correctness is
   asserted as a sequence of API calls, not pixels.")

(def ^:private grid-stroke        "#222")
(def ^:private trace-stroke       "#8f0")
(def ^:private trace-width        1.5)
(def ^:private spectrum-fill      "#09c")
(def ^:private peak-hold-stroke   "#fc0")
(def ^:private peak-hold-width    1.0)
(def ^:private axis-label-fill    "rgba(200,200,200,0.7)")
(def ^:private axis-label-font    "10px monospace")
(def ^:private bg-fill            "#000")

(defn- clear! [ctx w h]
  (set! (.-fillStyle ctx) bg-fill)
  (.fillRect ctx 0 0 w h))

(defn- draw-grid! [ctx w h v-divs h-divs]
  (set! (.-strokeStyle ctx) grid-stroke)
  (set! (.-lineWidth ctx) 1)
  (.beginPath ctx)
  (dotimes [i (inc v-divs)]
    (let [x (* (/ w v-divs) i)]
      (.moveTo ctx x 0)
      (.lineTo ctx x h)))
  (dotimes [i (inc h-divs)]
    (let [y (* (/ h h-divs) i)]
      (.moveTo ctx 0 y)
      (.lineTo ctx w y)))
  (.stroke ctx))

(defn- draw-trace! [ctx points]
  (when (seq points)
    (set! (.-strokeStyle ctx) trace-stroke)
    (set! (.-lineWidth ctx) trace-width)
    (.beginPath ctx)
    (let [[x0 y0] (first points)]
      (.moveTo ctx x0 y0))
    (doseq [[x y] (rest points)]
      (.lineTo ctx x y))
    (.stroke ctx)))

(defn- draw-spectrum! [ctx bars]
  (when (seq bars)
    (set! (.-fillStyle ctx) spectrum-fill)
    (doseq [[x y bw bh] bars]
      (.fillRect ctx x y bw bh))))

(defn- draw-axis-labels! [ctx h freq-labels]
  (when (seq freq-labels)
    (set! (.-fillStyle ctx) axis-label-fill)
    (set! (.-font ctx) axis-label-font)
    (set! (.-textBaseline ctx) "bottom")
    (set! (.-textAlign ctx) "center")
    (let [y (- h 2)]
      (doseq [{:keys [px-x label]} freq-labels]
        (.fillText ctx label px-x y)))))

(defn- draw-peak-spectrum! [ctx bars]
  (when (seq bars)
    (set! (.-strokeStyle ctx) peak-hold-stroke)
    (set! (.-lineWidth ctx) peak-hold-width)
    (.beginPath ctx)
    (let [[x0 y0 bw0 _] (first bars)]
      (.moveTo ctx (+ x0 (/ bw0 2.0)) y0))
    (doseq [[x y bw _] (rest bars)]
      (.lineTo ctx (+ x (/ bw 2.0)) y))
    (.stroke ctx)))

(defn draw!
  "Consume render-plan, issue canvas calls. Idempotent — calling with
   the same plan twice produces the same call sequence."
  [ctx {:keys [viewport layers]}]
  (let [{:keys [w h dpr]} viewport
        dpr (or dpr 1.0)]
    (.setTransform ctx dpr 0 0 dpr 0 0)
    (clear! ctx w h)
    (doseq [{:keys [kind] :as layer} layers]
      (case kind
        :grid          (draw-grid! ctx w h (:v-divs layer) (:h-divs layer))
        :trace         (draw-trace! ctx (:points layer))
        :spectrum      (draw-spectrum! ctx (:bars layer))
        :peak-spectrum (draw-peak-spectrum! ctx (:bars layer))
        :axis-labels   (draw-axis-labels! ctx h (:freq-labels layer))
        :harmonics     nil
        :cursors       nil
        :readout       nil
        nil))))
