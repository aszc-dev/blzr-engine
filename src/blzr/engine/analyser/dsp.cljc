(ns blzr.engine.analyser.dsp
  "Pure analyser helpers, JVM-testable without AnalyserNode or canvas interop.
   Bin/frequency mapping and the render pipeline live in
   `blzr.engine.analyser.render-plan`.")

(defn needs-realloc?
  "True when a fft-size change requires fresh typed-array buffers."
  [state fft-size]
  (not= fft-size (:fft-size state)))

(defn run-dsp?
  "True when the engine should poll DSP this frame. Space (:paused?) halts
   the entire pipeline — no FFT, no readout, no draw. The last drawn frame
   remains on canvas."
  [config]
  (not (:paused? config)))

(defn redraw?
  "True when the engine should redraw the canvas this frame. `.` (:frozen?)
   keeps DSP running so the measurements panel updates, but freezes the
   visual."
  [config]
  (not (or (:paused? config) (:frozen? config))))
