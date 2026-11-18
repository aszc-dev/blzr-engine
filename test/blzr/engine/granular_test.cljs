(ns blzr.engine.granular-test
  "Offline render sanity for the granular worklet — proves the worklet
   actually produces sound (not just that db state updates, which is all the
   dispatch-based :worklets/* scenarios check)."
  (:require [cljs.test :refer [deftest is async]]
            [blzr.engine.runtime :as rt]
            [blzr.engine.granular :as gr]))

(defn- ramp-sample [n]
  (let [buf (js/Float32Array. n)]
    (dotimes [i n]
      (aset buf i (- (* 2.0 (/ i n)) 1.0)))
    buf))

(defn- build-and-mount!
  "Registers worklets, mounts [granular -> out], returns a Promise of the node."
  [offline]
  (let [r (rt/create-runtime offline)
        flow {:nodes {"gr" {:type "granular" :data {}}
                      "out" {:type "out" :data {}}}
              :edges [{:source "gr" :sourceHandle "audio-out"
                       :target "out" :targetHandle "audio-in"}]}]
    (.then (rt/register-worklets! r) (fn [r'] (rt/mount! r' flow) (rt/get-node r' "gr")))))

(defn- render-after-port-messages
  "Starts the offline render, pausing it after the first render quantum.
   Port messages posted before this call (sample, envelope) are queued on the
   audio thread ahead of the resumed render, so the processor has them before
   it renders the rest of the buffer."
  [offline]
  (.then (.suspend offline (/ 128 (.-sampleRate offline))) #(.resume offline))
  (.startRendering offline))

(deftest granular-renders-non-silent-audio-when-active
  (async done
    (let [offline (js/OfflineAudioContext. 2 22050 44100)]
      (-> (build-and-mount! offline)
          (.then (fn [node]
                   (gr/load-sample! node (ramp-sample 4410) 4410)
                   (gr/push-envelope! node {:sides 0.5 :tilt 0.0 :curve 1.0})
                   (gr/set-param! node :density 30)
                   (render-after-port-messages offline)))
          (.then (fn [buf]
                   (let [ch (.getChannelData buf 0)
                         peak (areduce ch i m 0 (js/Math.max m (js/Math.abs (aget ch i))))]
                     (is (> peak 0.01) "grains should produce audible output")
                     (is (js/isFinite peak) "no NaN/Inf")
                     (done))))))))

(deftest granular-silent-at-zero-density
  (async done
    (let [offline (js/OfflineAudioContext. 2 22050 44100)]
      (-> (build-and-mount! offline)
          (.then (fn [node]
                   (gr/load-sample! node (ramp-sample 4410) 4410)
                   (gr/push-envelope! node {:sides 0.5 :tilt 0.0 :curve 1.0})
                   ;; Instant assignment (not gr/set-param!'s setTargetAtTime
                   ;; smoothing) — an exponential ramp toward 0 would still
                   ;; spawn grains while decaying, defeating this assertion.
                   (set! (.-value (.get (.-parameters node) "density")) 0)
                   (render-after-port-messages offline)))
          (.then (fn [buf]
                   (let [ch (.getChannelData buf 0)
                         peak (areduce ch i m 0 (js/Math.max m (js/Math.abs (aget ch i))))]
                     (is (< peak 0.001) "density=0 should spawn no grains")
                     (done))))))))

(deftest granular-posts-grain-positions
  (async done
    (let [offline (js/OfflineAudioContext. 2 22050 44100)
          received (atom nil)]
      (-> (build-and-mount! offline)
          (.then (fn [node]
                   (gr/load-sample! node (ramp-sample 4410) 4410)
                   (gr/push-envelope! node {:sides 0.5 :tilt 0.0 :curve 1.0})
                   (gr/on-grains node #(reset! received %))
                   (gr/set-param! node :density 30)
                   (render-after-port-messages offline)))
          (.then (fn [_buf]
                   (is (some? @received) "on-grains should have received a message during render")
                   (is (instance? js/Float32Array @received))
                   (done)))))))
