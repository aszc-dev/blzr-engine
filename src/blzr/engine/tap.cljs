(ns blzr.engine.tap
  "Turns a Tap descriptor (blzr.engine.instrument.schema/Tap) + a runtime into a
   subscribable signal — a plain atom, driven by requestAnimationFrame for
   analyser-backed taps or push-driven for worklet messages. This is the only
   extension point for new tap types: add a `:tap/type` enum member to the
   schema and a defmethod here.

   Analyser taps are passive branches (node -> analyser only, never
   analyser -> destination) so tapping never alters the audible signal."
  (:require [blzr.engine.runtime :as rt]))

;; each tap yields {:value <atom> :stop (fn [])}
(defmulti tap-source
  "runtime + Tap descriptor -> {:value atom :stop fn}."
  {:arglists '([rt tap])}
  (fn [_rt tap] (:tap/type tap)))

;; ---- analyser-backed taps share a driver ----------------------------------

(defn- attach-analyser [rt node-id {:keys [fft-size smoothing] :or {fft-size 2048 smoothing 0.8}}]
  (let [ctx (:ctx rt)
        an (doto (.createAnalyser ctx)
             (aset "fftSize" fft-size)
             (aset "smoothingTimeConstant" smoothing))
        node (rt/get-node rt node-id)]
    (.connect node an)
    an))

(defn- raf-loop [f]
  (let [id (atom nil)]
    (letfn [(tick [] (f) (reset! id (js/requestAnimationFrame tick)))]
      (reset! id (js/requestAnimationFrame tick))
      #(js/cancelAnimationFrame @id))))

(defmethod tap-source :level [rt {:keys [tap/source tap/opts]}]
  (let [an (attach-analyser rt source opts)
        buf (js/Float32Array. (.-fftSize an))
        value (atom 0.0)
        stop (raf-loop
              (fn []
                (.getFloatTimeDomainData an buf)
                (let [rms (js/Math.sqrt
                           (/ (areduce buf i s 0.0 (+ s (* (aget buf i) (aget buf i))))
                              (.-length buf)))]
                  (reset! value rms))))]
    {:value value
     :stop (fn [] (stop) (.disconnect an))}))

(defmethod tap-source :waveform [rt {:keys [tap/source tap/opts]}]
  (let [an (attach-analyser rt source opts)
        buf (js/Float32Array. (.-fftSize an))
        value (atom buf)
        ;; .slice yields a fresh reference each frame — reset!-ing `buf` itself
        ;; would be the same identity every tick, which React's setState
        ;; bails on via Object.is, freezing the canvas after frame 1.
        stop (raf-loop (fn [] (.getFloatTimeDomainData an buf) (reset! value (.slice buf))))]
    {:value value
     :stop (fn [] (stop) (.disconnect an))}))

(defmethod tap-source :spectrum [rt {:keys [tap/source tap/opts]}]
  (let [an (attach-analyser rt source opts)
        buf (js/Uint8Array. (.-frequencyBinCount an))
        value (atom buf)
        stop (raf-loop (fn [] (.getByteFrequencyData an buf) (reset! value (.slice buf))))]
    {:value value
     :stop (fn [] (stop) (.disconnect an))}))

;; ---- param tap: read a live AudioParam value ------------------------------

(defmethod tap-source :param [rt {:keys [tap/source]}]
  (let [{:param/keys [node key]} source
        p (some-> (rt/get-node rt node) (.-parameters) (.get (name key)))
        value (atom (some-> p (.-value)))
        stop (raf-loop (fn [] (when p (reset! value (.-value p)))))]
    {:value value :stop stop}))

;; ---- worklet-message tap: push-driven, no rAF -----------------------------
;; e.g. granular grain positions. Fans out alongside any existing onmessage
;; handler (e.g. blzr.engine.granular/on-grains) so both get every message.

(defmethod tap-source :worklet-message [rt {:keys [tap/source tap/opts]}]
  (let [node (rt/get-node rt source)
        msg-key (or (:key opts) "grains")
        value (atom nil)
        prev (.. node -port -onmessage)]
    (set! (.. node -port -onmessage)
          (fn [^js e]
            (when (= msg-key (.. e -data -type))
              (reset! value (.. e -data -positions)))
            (when prev (prev e))))
    {:value value
     :stop #(set! (.. node -port -onmessage) prev)}))
