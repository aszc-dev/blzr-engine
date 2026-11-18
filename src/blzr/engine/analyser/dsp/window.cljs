(ns blzr.engine.analyser.dsp.window
  "Window functions for spectral analysis.

   Coefficient arrays cached by (kind, size). apply-window! multiplies
   input samples by coefficients into caller-owned out-buffer.

   Coherent-gain corrections apply to dB readouts so peak amplitude
   readings are consistent across window choices.")

(def ^:private two-pi (* 2.0 js/Math.PI))

(defn- hann-coeffs [n]
  (let [out (js/Float32Array. n)]
    (dotimes [i n]
      (aset out i (* 0.5 (- 1.0 (js/Math.cos (/ (* two-pi i) n))))))
    out))

(defn- blackman-harris-4-coeffs [n]
  (let [a0 0.35875  a1 0.48829  a2 0.14128  a3 0.01168
        out (js/Float32Array. n)]
    (dotimes [i n]
      (let [x (/ (* two-pi i) n)]
        (aset out i (+ a0
                       (* -1.0 a1 (js/Math.cos x))
                       (* a2 (js/Math.cos (* 2.0 x)))
                       (* -1.0 a3 (js/Math.cos (* 3.0 x)))))))
    out))

(defn- flat-top-coeffs [n]
  (let [a0 0.21557895  a1 0.41663158  a2 0.277263158
        a3 0.083578947 a4 0.006947368
        out (js/Float32Array. n)]
    (dotimes [i n]
      (let [x (/ (* two-pi i) n)]
        (aset out i (+ a0
                       (* -1.0 a1 (js/Math.cos x))
                       (* a2 (js/Math.cos (* 2.0 x)))
                       (* -1.0 a3 (js/Math.cos (* 3.0 x)))
                       (* a4 (js/Math.cos (* 4.0 x)))))))
    out))

(defonce ^:private cache (atom {}))

(defn coeffs
  "Return cached Float32Array of window coefficients for [kind size]."
  [kind size]
  (let [key [kind size]]
    (or (@cache key)
        (let [arr (case kind
                    :hann (hann-coeffs size)
                    :blackman-harris-4 (blackman-harris-4-coeffs size)
                    :flat-top (flat-top-coeffs size)
                    (throw (ex-info "Unknown window kind" {:kind kind})))]
          (swap! cache assoc key arr)
          arr))))

(defn apply-window!
  "Multiply samples by coeffs into out. Returns out."
  [^js/Float32Array samples ^js/Float32Array coeffs ^js/Float32Array out]
  (let [n (.-length samples)]
    (dotimes [i n]
      (aset out i (* (aget samples i) (aget coeffs i))))
    out))

(defn coherent-gain
  "Mean of window coefficients (linear). Matches a0 for cosine-sum windows."
  [kind]
  (case kind
    :hann 0.5
    :blackman-harris-4 0.35875
    :flat-top 0.21557895
    :rectangular 1.0))

(defn coherent-gain-db
  "dB correction to add to peak magnitude so sine-amplitude readings match
   across windows: 20*log10(1 / coherent-gain)."
  [kind]
  (* -20.0 (js/Math.log10 (coherent-gain kind))))
