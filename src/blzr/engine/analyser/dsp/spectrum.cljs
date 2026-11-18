(ns blzr.engine.analyser.dsp.spectrum
  "Spectrum operations on FFT output (caller-owned Float32Array buffers).

   Magnitude is normalised by N/2 so a full-scale sine (amplitude 1)
   peaks at 0 dB *before* window-coherent-gain correction is applied
   by the caller (see blzr.engine.analyser.dsp.window/coherent-gain-db).")

(def ^:private db-floor-internal -200.0)

(defn complex->mag-db!
  "Convert complex spectrum (out-re + i·out-im) to single-sided magnitude
   in dB. Writes N/2 values into mags (Float32Array of length ≥ N/2).
   Normalised so a full-scale sine reads ~ -6 dB (uncorrected for window
   coherent gain)."
  [^js/Float32Array out-re ^js/Float32Array out-im ^js/Float32Array mags]
  (let [n (.-length out-re)
        half (bit-shift-right n 1)
        norm (/ 2.0 n)]
    (dotimes [k half]
      (let [re (aget out-re k)
            im (aget out-im k)
            mag-lin (* norm (js/Math.sqrt (+ (* re re) (* im im))))
            db (if (pos? mag-lin)
                 (* 20.0 (js/Math.log10 mag-lin))
                 db-floor-internal)]
        (aset mags k db)))
    mags))

(defn reset-peak-hold!
  "Initialise a peak-hold buffer to the dB floor used internally. Mutates
   in place."
  [^js/Float32Array peaks]
  (.fill peaks db-floor-internal)
  peaks)

(defn update-peak-hold!
  "Per-bin max-hold with linear-dB decay. peaks[k] := max(peaks[k] - decay,
   current[k]). Caller-owned buffers (same length). Returns peaks."
  [^js/Float32Array current ^js/Float32Array peaks ^number decay-db]
  (let [n (.-length current)]
    (dotimes [k n]
      (let [decayed (- (aget peaks k) decay-db)
            cur (aget current k)]
        (aset peaks k (js/Math.max decayed cur))))
    peaks))
