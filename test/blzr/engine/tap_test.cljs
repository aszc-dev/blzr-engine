(ns blzr.engine.tap-test
  "Mock-runtime coverage for blzr.engine.tap — no real AudioContext/analyser,
   just stub nodes exposing the same surface tap-source reads."
  (:require [cljs.test :refer [deftest is testing async]]
            [blzr.engine.tap :as tap]))

;; A fake AnalyserNode: getFloatTimeDomainData fills a known ramp, connect/
;; disconnect are no-ops we can observe.
(defn- fake-analyser [fill-fn]
  (let [state (atom {:fft-size 8 :connected 0 :disconnected 0})]
    #js {:fftSize (:fft-size @state)
         :frequencyBinCount 4
         :connect (fn [] (swap! state update :connected inc))
         :disconnect (fn [] (swap! state update :disconnected inc))
         :getFloatTimeDomainData fill-fn
         :getByteFrequencyData (fn [buf] (dotimes [i (.-length buf)] (aset buf i 128)))
         :__state state}))

(defn- mock-rt [node analyser]
  {:ctx #js {:createAnalyser (fn [] analyser)}
   :nodes (doto (js/Map.) (.set "n1" node))})

(deftest level-tap-computes-rms-of-fake-analyser
  (async done
    (let [ramp (js/Float32Array. #js [1.0 1.0 1.0 1.0])
          fill (fn [buf] (dotimes [i (.-length buf)] (aset buf i (aget ramp (mod i (.-length ramp))))))
          analyser (fake-analyser fill)
          node #js {:connect (fn [] nil)}
          rt (mock-rt node analyser)
          {:keys [value stop]} (tap/tap-source rt {:tap/type :level :tap/source "n1"})]
      (js/requestAnimationFrame
       (fn []
         (is (< (js/Math.abs (- @value 1.0)) 1e-6) "RMS of an all-1.0 buffer is 1.0")
         (stop)
         (is (pos? (:disconnected @(aget analyser "__state"))) "stop should disconnect the analyser")
         (done))))))

(deftest waveform-tap-exposes-a-float32array
  (async done
    (let [fill (fn [buf] (dotimes [i (.-length buf)] (aset buf i 0.5)))
          analyser (fake-analyser fill)
          node #js {:connect (fn [] nil)}
          rt (mock-rt node analyser)
          {:keys [value stop]} (tap/tap-source rt {:tap/type :waveform :tap/source "n1"})]
      (js/requestAnimationFrame
       (fn []
         (is (instance? js/Float32Array @value))
         (is (= 0.5 (aget @value 0)))
         (stop)
         (done))))))

(deftest waveform-tap-yields-a-fresh-reference-every-frame
  (async done
    (let [fill (fn [buf] (dotimes [i (.-length buf)] (aset buf i 0.5)))
          analyser (fake-analyser fill)
          node #js {:connect (fn [] nil)}
          rt (mock-rt node analyser)
          {:keys [value stop]} (tap/tap-source rt {:tap/type :waveform :tap/source "n1"})]
      (js/requestAnimationFrame
       (fn []
         (let [first-ref @value]
           (js/requestAnimationFrame
            (fn []
              (is (not (identical? first-ref @value))
                  "each frame must reset! a new array so React's Object.is setState check doesn't bail")
              (stop)
              (done)))))))))

(deftest spectrum-tap-yields-a-fresh-reference-every-frame
  (async done
    (let [analyser (fake-analyser (fn [_] nil))
          node #js {:connect (fn [] nil)}
          rt (mock-rt node analyser)
          {:keys [value stop]} (tap/tap-source rt {:tap/type :spectrum :tap/source "n1"})]
      (js/requestAnimationFrame
       (fn []
         (let [first-ref @value]
           (js/requestAnimationFrame
            (fn []
              (is (not (identical? first-ref @value))
                  "each frame must reset! a new array so React's Object.is setState check doesn't bail")
              (stop)
              (done)))))))))

(deftest param-tap-follows-audio-param-value
  (async done
    (let [param #js {:value 0.25}
          node #js {:parameters #js {:get (fn [_] param)}}
          rt {:nodes (doto (js/Map.) (.set "n1" node))}
          {:keys [value stop]} (tap/tap-source rt {:tap/type :param
                                                    :tap/source {:param/node "n1" :param/key :density}})]
      (is (= 0.25 @value) "initial value read synchronously")
      (set! (.-value param) 0.75)
      (js/requestAnimationFrame
       (fn []
         (is (= 0.75 @value))
         (stop)
         (done))))))

(deftest worklet-message-tap-updates-on-matching-message
  (testing "dispatches a fake port message and asserts the ratom + stop restore"
    (let [prev-called (atom nil)
          prev-handler (fn [e] (reset! prev-called e))
          port #js {:onmessage prev-handler}
          node #js {:port port}
          rt {:nodes (doto (js/Map.) (.set "n1" node))}
          {:keys [value stop]} (tap/tap-source rt {:tap/type :worklet-message :tap/source "n1"})
          positions #js [0.1 0.2 0.3]
          evt #js {:data #js {:type "grains" :positions positions}}]
      (is (not= prev-handler (.-onmessage port)) "tap-source should install its own handler")
      ((.-onmessage port) evt)
      (is (identical? positions @value))
      (is (some? @prev-called) "fan-out should still call the previous handler")
      (stop)
      (is (identical? prev-handler (.-onmessage port)) "stop should restore the previous handler"))))
