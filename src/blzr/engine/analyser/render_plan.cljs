(ns blzr.engine.analyser.render-plan
  "Pure render-plan producer. Consumed by the imperative canvas renderer.

   Splits the renderer in two: build returns a data structure
   describing layers to draw; the imperative consumer issues
   canvas calls.

   Optional inputs (:samples, :mag-db, :sample-rate, :viewport) trigger
   pixel-space layer enrichment: trace gets :points, spectrum gets :bars,
   harmonics get :px-x. When omitted, layers carry only static data — the
   renderer no-ops their draw paths."
  (:require
   [blzr.engine.analyser.dsp.trigger :as trig]))

(def ^:private default-viewport {:w 800 :h 300 :dpr 1.0})

(def ^:private display-periods 2.5)

(defn default-config []
  {:fft-size 4096
   :window :hann
   :overlap 0.75
   :smoothing-ms 150
   :peak-hold? true
   :time-div 1.0
   :freq-scale {:min-hz 20 :max-hz 20000 :log? true}
   :db-scale {:floor -100 :ref :peak-fullscale}
   :harmonics {:enabled? false :f0 440.0 :count 8 :source :auto}
   :cursors {:a {:hz nil :pinned? false :focused? false}
             :b {:hz nil :pinned? false :focused? false}}})

(defn default-readout []
  {:peak-hz nil :peak-db nil :note nil :cents nil
   :rms-db nil :period-ms nil :clarity nil
   :peaks [] :sample-rate nil :fft-size nil})

(def ^:private intervals
  [[1.0       "unison"]
   [(/ 16 15) "m2"]
   [(/ 9 8)   "M2"]
   [(/ 6 5)   "m3"]
   [(/ 5 4)   "M3"]
   [(/ 4 3)   "4th"]
   [(/ 7 5)   "TT"]
   [(/ 3 2)   "5th"]
   [(/ 8 5)   "m6"]
   [(/ 5 3)   "M6"]
   [(/ 7 4)   "m7"]
   [(/ 15 8)  "M7"]
   [2.0       "octave"]])

(defn- ratio->cents [r]
  (* 1200 (/ (js/Math.log r) (js/Math.log 2))))

(defn interval-name
  "If `ratio` matches a just-intonation interval within `tolerance-cents`,
   return its label. Inverts ratios below 1 so f_b < f_a still resolves
   to the absolute interval (octave, 5th, …). Returns nil otherwise."
  [ratio tolerance-cents]
  (when (and (number? ratio) (pos? ratio))
    (let [r (if (< ratio 1.0) (/ 1.0 ratio) ratio)
          target (ratio->cents r)]
      (some (fn [[ir label]]
              (when (<= (js/Math.abs (- target (ratio->cents ir)))
                        tolerance-cents)
                label))
            intervals))))

(def ^:private engineering-freq-ticks
  [20 50 100 200 500 1000 2000 5000 10000 20000])

(def ^:private musical-freq-notes
  [["C2" 65.41]
   ["C3" 130.81]
   ["C4" 261.63]
   ["C5" 523.25]
   ["C6" 1046.50]
   ["C7" 2093.00]])

(defn- format-freq-tick [hz]
  (cond
    (>= hz 1000) (str (int (/ hz 1000)) "k")
    :else        (str (int hz))))

(defn hz->x
  "Map frequency to pixel x using log or linear scale.
   `freq-scale` = {:min-hz :max-hz :log?}, `w` = pixel width."
  [hz {:keys [min-hz max-hz log?]} w]
  (cond
    (or (nil? hz) (<= hz 0)) nil
    log? (let [lo (js/Math.log min-hz)
               hi (js/Math.log max-hz)
               t  (/ (- (js/Math.log hz) lo) (- hi lo))]
           (* w t))
    :else (* w (/ (- hz min-hz) (- max-hz min-hz)))))

(defn freq-axis-labels
  "Pixel-positioned labels for the frequency axis.

   Engineering mode shows decade ticks (10/100/1k/10k Hz). Musical mode
   shows piano C-octaves (C2..C7). Out-of-range ticks for the current
   freq-scale window are dropped."
  [freq-scale axis-mode w]
  (let [ticks (if (= axis-mode :musical)
                musical-freq-notes
                (mapv (fn [hz] [(format-freq-tick hz) hz]) engineering-freq-ticks))
        {:keys [min-hz max-hz]} freq-scale]
    (->> ticks
         (keep (fn [[label hz]]
                 (when (and (>= hz min-hz) (<= hz max-hz))
                   (when-let [x (hz->x hz freq-scale w)]
                     {:px-x x :hz hz :label label}))))
         vec)))

(defn x->hz
  "Inverse of hz->x. Map pixel x back to frequency. `w` = pixel width."
  [x {:keys [min-hz max-hz log?]} w]
  (when (and x (pos? w))
    (let [t (/ x w)]
      (if log?
        (let [lo (js/Math.log min-hz)
              hi (js/Math.log max-hz)]
          (js/Math.exp (+ lo (* t (- hi lo)))))
        (+ min-hz (* t (- max-hz min-hz)))))))

(defn db->y
  "Map dB magnitude to pixel y. 0 dB at top, floor at bottom.
   Values outside [floor, 0] are clamped."
  [db floor h]
  (when db
    (let [norm (js/Math.max 0.0
                            (js/Math.min 1.0
                                         (/ (- db floor) (- 0.0 floor))))]
      (* h (- 1.0 norm)))))

(defn samples->points
  "Time-domain Float32Array → vector of [x y] pixel pairs. Downsamples
   to ≤w columns; amplitude -1..1 maps to y in [h, 0]."
  [^js/Float32Array samples w h]
  (when samples
    (let [n (.-length samples)
          cols (js/Math.min n w)]
      (if (zero? cols)
        []
        (let [stride (/ n cols)
              half-h (* 0.5 h)]
          (loop [i 0
                 out (transient [])]
            (if (>= i cols)
              (persistent! out)
              (let [idx (js/Math.floor (* i stride))
                    amp (aget samples idx)
                    x (* w (/ i cols))
                    y (- half-h (* half-h amp))]
                (recur (inc i) (conj! out [x y]))))))))))

(defn- lerp-sample
  "Linear interpolation at fractional sample index. Out-of-bounds reads
   clamp to the buffer's first/last sample."
  [^js/Float32Array samples ^number pos]
  (let [n (.-length samples)
        i (js/Math.floor pos)]
    (cond
      (<= i 0)        (aget samples 0)
      (>= i (dec n))  (aget samples (dec n))
      :else (let [f (- pos i)
                  a (aget samples i)
                  b (aget samples (inc i))]
              (+ a (* f (- b a)))))))

(defn aligned-trace-points
  "Locked-trigger trace points. Finds a rising zero-cross with enough
   buffer remaining after it to display the trace, then emits up to
   `display-periods` periods starting at the *true* sub-sample crossing
   position `i + frac`. Each pixel column samples the buffer via linear
   interpolation at the corresponding fractional time, so the trace is
   phase-stable across frames regardless of which integer-indexed
   zero-cross the search lands on. (Without interpolation, frame-to-frame
   variation in `frac` produces multiple ghost traces — the 'three
   flickering sines' artifact.)

   Adaptive displayed window: reserves *one full period* at the buffer
   tail as a search window so a rising zero-cross is always available
   when one exists in the audio. The displayed periods is the smaller
   of `display-periods` and however many fit in `n - period` samples.
   Without this, low-frequency inputs (where 2.5 × period eats almost
   the entire buffer) leave the search window narrower than one period
   and miss crossings phase-dependently, producing intermittent raw-
   sample fallback that looks like a slow horizontal slide.

   Returns nil when no qualifying zero-cross exists or when the buffer
   is too short to display at least one full period (sub-DC regime)."
  ([^js/Float32Array samples period-samples w h]
   (aligned-trace-points samples period-samples w h 1.0))
  ([^js/Float32Array samples period-samples w h time-div]
  (when (and samples period-samples (pos? period-samples))
    (let [n (.-length samples)
          period (double period-samples)
          available (- n period)
          fit-periods (/ available period)
          td (max 0.01 (or time-div 1.0))
          periods (js/Math.min (* display-periods td) fit-periods)
          display-samples (* periods period)
          display-int (js/Math.ceil display-samples)]
      (when (and (pos? display-int)
                 (>= periods 1.0)
                 (> n display-int))
        (let [end-idx (- n display-int 1)
              search-window (js/Math.min (inc end-idx)
                                         (js/Math.ceil period))
              zc (trig/find-rising-zero-cross samples search-window end-idx)]
          (when zc
            (let [{:keys [i frac]} zc
                  start-pos (+ i frac)
                  cols (js/Math.min display-int w)
                  ;; Map pixel-column k → fractional sample position;
                  ;; linear interp for sub-sample accuracy.
                  step-samples (/ display-samples cols)
                  half-h (* 0.5 h)]
              (loop [k 0
                     out (transient [])]
                (if (>= k cols)
                  (persistent! out)
                  (let [pos (+ start-pos (* k step-samples))
                        amp (lerp-sample samples pos)
                        x (* w (/ k cols))
                        y (- half-h (* half-h amp))]
                    (recur (inc k) (conj! out [x y])))))))))))))

(defn mag-db->bars
  "Spectrum magnitudes (dB) → vector of [x y bar-w bar-h] tuples.
   One bar per FFT bin within freq-scale range. Bars taller than
   floor have positive height; floor-clamped bins are dropped."
  [^js/Float32Array mag-db sample-rate fft-size freq-scale db-floor w h]
  (when (and mag-db sample-rate fft-size)
    (let [n (.-length mag-db)
          bin->hz (/ sample-rate fft-size)
          {:keys [min-hz max-hz]} freq-scale
          out (transient [])]
      (loop [k 1]
        (if (>= k n)
          (persistent! out)
          (let [hz (* k bin->hz)]
            (if (or (< hz min-hz) (> hz max-hz))
              (recur (inc k))
              (let [db (aget mag-db k)
                    x  (hz->x hz freq-scale w)
                    y  (db->y db db-floor h)
                    bh (- h y)]
                (when (and x y (pos? bh))
                  (conj! out [x y 1.0 bh]))
                (recur (inc k))))))))))

(defn- harmonic-markers
  [{:keys [f0 count]} freq-scale w]
  (mapv (fn [i]
          (let [ord (inc i)
                hz  (* ord f0)
                parity (if (odd? ord) :odd :even)]
            (cond-> {:hz hz :ord ord :parity parity}
              freq-scale (assoc :px-x (hz->x hz freq-scale w)))))
        (range count)))

(defn- readout-text [{:keys [peak-hz peak-db note cents]}]
  (str (when peak-hz (str (.toFixed peak-hz 2) " Hz"))
       (when note (str "  " note (when cents (str " " (int cents) "¢"))))
       (when peak-db (str "  " (.toFixed peak-db 1) " dBFS"))))

(defn build
  "Pure state → render-plan transform.

   Required: {:config :readout}.
   Optional: :samples (Float32Array, time-domain) → trace :points
             :mag-db  (Float32Array, dB magnitudes) → spectrum :bars
             :sample-rate (Hz) — required when :mag-db is provided
             :viewport {:w :h :dpr} — defaults to 800×300 @1.0 DPR
             :trigger {:locked? bool :period-samples double}
               When :locked? is true and :period-samples > 0, the trace
               is aligned to the most recent rising zero-cross with
               sub-sample shift. Falls back to raw samples when no
               crossing is found.

   Layers always include grid, trace, spectrum, readout. Harmonics
   layer appears when enabled in config."
  [{:keys [config readout samples mag-db peak-mag-db sample-rate viewport trigger]}]
  (let [{:keys [harmonics freq-scale db-scale fft-size axis-mode]} config
        viewport (merge default-viewport viewport)
        {:keys [w h]} viewport
        floor (:floor db-scale -100.0)
        grid-on? (not (false? (:grid-on? config)))
        {:keys [locked? period-samples]} trigger
        aligned (when (and samples locked? period-samples (pos? period-samples))
                  (aligned-trace-points samples period-samples w h
                                        (:time-div config 1.0)))
        trace-points (or aligned (when samples (samples->points samples w h)))
        trace-layer (cond-> {:kind :trace
                             :locked? (boolean (and locked? aligned))}
                      samples (assoc :points trace-points))
        spectrum-layer (cond-> {:kind :spectrum}
                         (and mag-db sample-rate)
                         (assoc :bars (mag-db->bars mag-db sample-rate
                                                    (or fft-size 4096)
                                                    freq-scale floor w h)))
        peak-spectrum-layer (when (and peak-mag-db sample-rate)
                              {:kind :peak-spectrum
                               :bars (mag-db->bars peak-mag-db sample-rate
                                                   (or fft-size 4096)
                                                   freq-scale floor w h)})
        axis-labels-layer (when grid-on?
                            {:kind :axis-labels
                             :freq-labels (freq-axis-labels
                                           freq-scale
                                           (or axis-mode :engineering)
                                           w)})]
    {:viewport viewport
     :layers (cond-> []
               grid-on?            (conj {:kind :grid :v-divs 8 :h-divs 10})
               true                (conj trace-layer spectrum-layer)
               peak-spectrum-layer (conj peak-spectrum-layer)
               axis-labels-layer   (conj axis-labels-layer)

               (and harmonics (:enabled? harmonics))
               (conj {:kind :harmonics
                      :f0 (:f0 harmonics)
                      :markers (harmonic-markers harmonics freq-scale w)})

               true
               (conj {:kind :readout :text (readout-text readout)}))}))
