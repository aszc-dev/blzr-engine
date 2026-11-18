(ns blzr.engine.analyser.dsp.trigger
  "Oscilloscope trigger — McLeod Pitch Method (NSDF).

   Phase-robust pitch detection via Normalised Square Difference
   Function: autocorrelation maxima stay put regardless of input
   phase, which is critical for visualisation stability. The clarity
   metric ∈ [0,1] drives the free-run fallback when no pitched signal
   is present.

   Direct O(N²) NSDF is used for simplicity at scope-size N=2048
   (≈2M ops per call — cheap on the main thread). FFT-accelerated
   autocorrelation is a future optimisation.")

(def ^:private pitch-min-hz 20.0)
(def ^:private pitch-max-hz 2000.0)
(def ^:private key-max-threshold 0.85)

(defn- mean
  [^js/Float32Array xs]
  (let [n (.-length xs)]
    (loop [i 0 s 0.0]
      (if (>= i n)
        (/ s n)
        (recur (inc i) (+ s (aget xs i)))))))

(defn- dc-block!
  "Subtract mean in place. Returns xs."
  [^js/Float32Array xs]
  (let [n (.-length xs)
        m (mean xs)]
    (dotimes [i n] (aset xs i (- (aget xs i) m)))
    xs))

(defn- nsdf!
  "Compute NSDF[τ] for τ in [0, max-lag] into out (Float32Array).
   NSDF[τ] = 2·Σ x[j]·x[j+τ] / (Σ x[j]² + Σ x[j+τ]²)."
  [^js/Float32Array samples ^js/Float32Array out max-lag]
  (let [n (.-length samples)]
    (aset out 0 1.0)
    (loop [tau 1]
      (when (<= tau max-lag)
        (let [upper (- n tau)]
          (loop [j 0 num 0.0 da 0.0 db 0.0]
            (if (>= j upper)
              (let [denom (+ da db)]
                (aset out tau (if (zero? denom)
                                0.0
                                (/ (* 2.0 num) denom))))
              (let [xj (aget samples j)
                    xjt (aget samples (+ j tau))]
                (recur (inc j)
                       (+ num (* xj xjt))
                       (+ da (* xj xj))
                       (+ db (* xjt xjt)))))))
        (recur (inc tau))))
    out))

(defn- key-maxima
  "Walk NSDF[τ] over [1, max-lag]. Record τ at which NSDF peaks
   inside each positive lobe — but only after the *first* positively-
   sloped zero-crossing (i.e., we have seen NSDF ≤ 0 at least once).
   This skips the initial autocorrelation descent from NSDF[0]=1,
   which otherwise registers as a false peak whose parabolic
   interpolation produces nonsense (e.g. negative period_samples
   for low-frequency inputs where the descent extends well past
   min-lag). Peaks with τ < min-lag are discarded.

   Returns vector of lag indices sorted ascending."
  [^js/Float32Array nsdf min-lag max-lag]
  (loop [tau 1
         seen-zero? false
         in-lobe? false
         best-tau -1
         best-val js/Number.NEGATIVE_INFINITY
         result (transient [])]
    (if (> tau max-lag)
      (persistent! (if (and in-lobe? seen-zero? (>= best-tau min-lag))
                     (conj! result best-tau)
                     result))
      (let [v (aget nsdf tau)]
        (cond
          (<= v 0.0)
          (let [r (if (and in-lobe? seen-zero? (>= best-tau min-lag))
                    (conj! result best-tau)
                    result)]
            (recur (inc tau) true false -1
                   js/Number.NEGATIVE_INFINITY r))

          (and seen-zero? (or (not in-lobe?) (> v best-val)))
          (recur (inc tau) true true tau v result)

          :else
          (recur (inc tau) seen-zero? in-lobe? best-tau best-val result))))))

(defn- parabolic-interp
  "Sub-sample refinement of peak location."
  [^js/Float32Array nsdf tau]
  (let [alpha (aget nsdf (dec tau))
        beta (aget nsdf tau)
        gamma (aget nsdf (inc tau))
        denom (- (+ alpha gamma) (* 2.0 beta))]
    (if (zero? denom)
      [(double tau) beta]
      (let [p (* 0.5 (/ (- alpha gamma) denom))
            refined (+ beta (* 0.25 (- alpha gamma) p))]
        [(+ tau p) refined]))))

(def ^:private lock-enter-clarity 0.4)
(def ^:private lock-exit-clarity  0.3)

(defn update-lock
  "Schmitt trigger over MPM clarity. Enter locked at >0.4, exit at <0.3.
   In the dead band, hold previous state. Returns the new locked? bool."
  [prev-locked? clarity]
  (cond
    (nil? clarity)                 false
    (> clarity lock-enter-clarity) true
    (< clarity lock-exit-clarity)  false
    :else                           (boolean prev-locked?)))

(defn smooth-period
  "One-pole LPF on period-samples between frames. `prev` may be nil
   (cold-start). `alpha` ∈ (0,1] is the LPF weight applied to the new
   sample; 1.0 = no smoothing. With τ ≈ 100 ms at 60 fps, alpha ≈ 0.16."
  [prev raw alpha]
  (cond
    (nil? raw)  prev
    (nil? prev) raw
    :else       (+ (* alpha raw) (* (- 1.0 alpha) prev))))

(defn find-rising-zero-cross
  "Walk backwards through `samples` looking for the most recent rising
   zero crossing (y[i] < 0 ≤ y[i+1]) within the inclusive index range
   ending at `end-idx` and spanning at most `window` samples.

   Returns {:i <int> :frac <double>} where `frac = -y[i] / (y[i+1] - y[i])`
   ∈ [0,1) is the sub-sample offset of the true crossing past index i.
   Returns nil if no crossing exists in the search range.

   Single-arity (samples window) is shorthand for end-idx = n-1: search
   the tail of the buffer."
  ([^js/Float32Array samples window]
   (find-rising-zero-cross samples window (dec (.-length samples))))
  ([^js/Float32Array samples window end-idx]
   (let [n (.-length samples)
         upper (js/Math.min end-idx (- n 2))
         lower (js/Math.max 0 (- (inc upper) window))]
     (loop [i upper]
       (when-not (< i lower) (let [a (aget samples i) b (aget samples (inc i))] (if (and (< a 0.0) (>= b 0.0)) (let [denom (- b a)] {:i i, :frac (if (zero? denom) 0.0 (/ (- a) denom))}) (recur (dec i)))))))))

(defn mpm-period
  "Estimate pitch period from time-domain samples via MPM.
   Returns {:period-samples <double|nil> :clarity <0..1>}.
   Clarity below ~0.3 indicates no pitched signal (free-run fallback)."
  [^js/Float32Array samples sample-rate]
  (let [n (.-length samples)
        ;; Copy so we don't mutate caller's buffer
        buf (js/Float32Array. n)
        _ (dotimes [i n] (aset buf i (aget samples i)))
        _ (dc-block! buf)
        max-lag (js/Math.min (bit-shift-right n 1)
                             (js/Math.floor (/ sample-rate pitch-min-hz)))
        min-lag (js/Math.max 2 (js/Math.ceil (/ sample-rate pitch-max-hz)))
        nsdf (js/Float32Array. (+ max-lag 2))
        _ (nsdf! buf nsdf max-lag)
        maxima (key-maxima nsdf min-lag (dec max-lag))]
    (if (empty? maxima)
      {:period-samples nil :clarity 0.0}
      (let [global-max (reduce (fn [m tau]
                                 (let [v (aget nsdf tau)]
                                   (if (> v m) v m)))
                               js/Number.NEGATIVE_INFINITY
                               maxima)
            threshold (* key-max-threshold global-max)
            chosen (or (first (filter #(>= (aget nsdf %) threshold) maxima))
                       (first maxima))
            [period refined-val] (parabolic-interp nsdf chosen)]
        {:period-samples period
         :clarity (js/Math.max 0.0 (js/Math.min 1.0 refined-val))}))))
