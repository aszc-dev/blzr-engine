(ns blzr.engine.analyser.dsp.fft
  "Real-input FFT wrapper around fft.js (indutny, radix-4).

   Instances and interleaved complex output buffers are cached per size."
  (:require
   ["fft.js" :as FFT]))

(defonce ^:private instances (atom {}))
(defonce ^:private out-buffers (atom {}))

(defn- instance-for [size]
  (or (@instances size)
      (let [inst (FFT. size)]
        (swap! instances assoc size inst)
        inst)))

(defn- out-buffer-for [size]
  (or (@out-buffers size)
      (let [buf (js/Array. (* 2 size))]
        (swap! out-buffers assoc size buf)
        buf)))

(defn forward!
  "Real forward FFT. samples: Float32Array of length N (power of two).
   Writes positive-frequency bins into out-re and out-im (Float32Arrays
   of length N; only first N/2+1 entries are meaningful for real input).
   scratch is reserved for future use (currently unused — fft.js manages
   its own interleaved buffer internally).
   Returns nil."
  [^js/Float32Array samples ^js/Float32Array out-re ^js/Float32Array out-im _scratch]
  (let [n (.-length samples)
        inst (instance-for n)
        complex (out-buffer-for n)]
    (.realTransform inst complex samples)
    (dotimes [k n]
      (aset out-re k (aget complex (* 2 k)))
      (aset out-im k (aget complex (inc (* 2 k)))))
    nil))
