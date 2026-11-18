(ns blzr.engine.dsp.envelope
  "Pure Tukey-window grain envelope: the granular worklet's amplitude table
   (pushed via postMessage) and anything that draws it. No side effects, no
   audio context.")

(defn tukey-window
  "sides in [0,1] is the fraction of the window spent in the cosine
   taper (0 = rectangular, 1 = full Hann); tilt in [-1,1] shifts the window
   center. t is normalized position in [0,1]."
  ^double [^double t ^double sides ^double tilt]
  (let [t (-> (+ t tilt) (max 0.0) (min 1.0))
        a (max 1e-6 sides)]
    (cond
      (< t (/ a 2.0)) (* 0.5 (- 1.0 (js/Math.cos (/ (* js/Math.PI 2.0 t) a))))
      (> t (- 1.0 (/ a 2.0))) (* 0.5 (- 1.0 (js/Math.cos (/ (* js/Math.PI 2.0 (- 1.0 t)) a))))
      :else 1.0)))

(defn tukey-envelope
  "Returns a Float32Array of length n in [0,1]: the Tukey window raised to
   `curve`, then square-rooted."
  [{:keys [sides tilt curve n] :or {sides 0.5 tilt 0.0 curve 1.0 n 1024}}]
  (let [out (js/Float32Array. n)]
    (dotimes [i n]
      (let [t (/ (double i) (dec n))
            e (tukey-window t sides tilt)
            e (js/Math.pow e curve)
            e (js/Math.sqrt e)]
        (aset out i (-> e (max 0.0) (min 1.0)))))
    out))
