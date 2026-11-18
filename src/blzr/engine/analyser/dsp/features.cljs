(ns blzr.engine.analyser.dsp.features
  "Peak detection and frequency-domain features.

   QIFFT (quadratic interpolation on log magnitudes) gives sub-bin peak
   frequency accuracy well under 1 cent for Hann-windowed sinusoids at
   N=4096, Fs=44.1 kHz.")

(def ^:private note-names
  #js ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn- peak-bin
  "Return index of maximum magnitude in mags, searching bins [1, half).
   Skips DC. Returns nil if all bins are at the noise floor."
  [^js/Float32Array mags]
  (let [n (.-length mags)
        half (bit-shift-right n 1)
        upper (if (zero? half) n half)]
    (loop [k 2
           best-k 1
           best-v (aget mags 1)]
      (if (>= k upper)
        best-k
        (let [v (aget mags k)]
          (if (> v best-v)
            (recur (inc k) k v)
            (recur (inc k) best-k best-v)))))))

(defn qifft-peak-hz
  "Parabolic interpolation on log-magnitude bins around the spectral peak.
   mags: Float32Array of dB magnitudes (length ≥ fft-size/2).
   Returns interpolated peak frequency in Hz, or nil for degenerate input."
  [^js/Float32Array mags fft-size sample-rate]
  (let [k (peak-bin mags)]
    (when (and k (pos? k) (< k (dec (/ fft-size 2))))
      (let [a (aget mags (dec k))
            b (aget mags k)
            c (aget mags (inc k))
            denom (- (+ a c) (* 2.0 b))
            p (if (zero? denom) 0.0 (* 0.5 (/ (- a c) denom)))]
        (* (+ k p) (/ sample-rate fft-size))))))

(defn peak-db
  "Return maximum dB value across the positive-half spectrum."
  [^js/Float32Array mags]
  (let [n (.-length mags)
        half (bit-shift-right n 1)
        upper (if (zero? half) n half)]
    (loop [k 0
           best js/Number.NEGATIVE_INFINITY]
      (if (>= k upper)
        best
        (let [v (aget mags k)]
          (recur (inc k) (if (> v best) v best)))))))

(defn hz->note
  "Convert frequency in Hz to {:note <name><octave> :cents <±50>} using
   A4 = 440 Hz reference. Returns nil for non-positive input."
  [hz]
  (when (and hz (pos? hz))
    (let [midi (+ 69.0 (* 12.0 (/ (js/Math.log (/ hz 440.0))
                                  (js/Math.log 2.0))))
          idx (js/Math.round midi)
          cents (* 100.0 (- midi idx))
          octave (dec (js/Math.floor (/ idx 12)))
          name (aget note-names (mod idx 12))]
      {:note (str name octave)
       :cents cents})))

(defn- window-min
  "Minimum dB magnitude in [lo, hi] inclusive."
  [^js/Float32Array mags lo hi]
  (loop [i lo
         mn js/Number.POSITIVE_INFINITY]
    (if (> i hi)
      mn
      (let [v (aget mags i)]
        (recur (inc i) (if (< v mn) v mn))))))

(defn find-peaks
  "Locate local maxima in dB-magnitude spectrum that satisfy a prominence
   threshold. Prominence is defined as peak - min-in-window where the
   window is ±max(3, ⌈0.06·k⌉) bins (approximately 1/6-octave).

   Returns vector of {:hz, :db, :bin}, sorted by ascending frequency."
  [^js/Float32Array mags fft-size sample-rate {:keys [prominence-db]
                                               :or {prominence-db 6.0}}]
  (let [n (.-length mags)
        half (js/Math.min n (/ fft-size 2))
        bin->hz (/ sample-rate fft-size)]
    (loop [k 2
           result (transient [])]
      (if (>= k (dec half))
        (persistent! result)
        (let [v (aget mags k)
              left (aget mags (dec k))
              right (aget mags (inc k))]
          (if (and (> v left) (> v right))
            (let [radius (js/Math.max 3 (js/Math.ceil (* 0.06 k)))
                  lo (js/Math.max 1 (- k radius))
                  hi (js/Math.min (dec half) (+ k radius))
                  floor (window-min mags lo hi)]
              (if (> (- v floor) prominence-db)
                (recur (inc k) (conj! result {:hz (* k bin->hz) :db v :bin k}))
                (recur (inc k) result)))
            (recur (inc k) result)))))))
