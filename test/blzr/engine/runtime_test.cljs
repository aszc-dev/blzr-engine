(ns blzr.engine.runtime-test
  "Mounts flows on private OfflineAudioContexts and checks what they render."
  (:require [cljs.test :refer [deftest is async testing]]
            [blzr.engine.runtime :as rt]
            [blzr.engine.specs :as specs]))

(def sine->out
  {:nodes {"o" {:type "osc" :data {:frequency 220 :type "sine"}}
           "out" {:type "out" :data {}}}
   :edges [{:source "o" :sourceHandle "audio-out" :target "out" :targetHandle "audio-in"}]})

(deftest headless-offline-render-produces-sound
  (async done
    (let [offline (js/OfflineAudioContext. 2 22050 44100)
          r (rt/create-runtime offline)]
      (rt/mount! r sine->out)
      (.then (.startRendering offline) (fn [buf] (let [ch (.getChannelData buf 0) peak (areduce ch i m 0 (js/Math.max m (js/Math.abs (aget ch i))))] (is (> peak 0.01) "should render a non-silent sine") (is (js/isFinite peak) "no NaN/Inf") (done)))))))

(deftest two-runtimes-are-isolated
  (let [a (rt/create-runtime (js/OfflineAudioContext. 1 128 44100))
        b (rt/create-runtime (js/OfflineAudioContext. 1 128 44100))]
    (is (not (identical? (:nodes a) (:nodes b))))
    (is (not (identical? (:ctx a) (:ctx b))))))

(deftest worklets-load-from-the-configured-base
  (async done
    (let [offline (js/OfflineAudioContext. 1 128 44100)
          r (rt/create-runtime offline {:worklet-base "/missing-worklets/"})]
      (.then (rt/register-worklets! r)
             (fn [_] (is false "registration should fail for a base with no worklets") (done))
             (fn [_] (is true) (done))))))

(deftest a-runtime-mounts-node-types-from-its-own-specs
  (let [r (rt/create-runtime (js/OfflineAudioContext. 1 128 44100)
                             {:node-specs (assoc specs/node-specs
                                                 :half {:native-type "Gain"
                                                        :inputs [:audio-in]
                                                        :outputs [:audio-out]
                                                        :params {:gain {:type :audio-param :default 0.5}}})})]
    (rt/mount! r {:nodes {"h" {:type "half" :data {}}
                          "out" {:type "out" :data {}}}
                  :edges [{:source "h" :sourceHandle "audio-out" :target "out" :targetHandle "audio-in"}]})
    (is (= 0.5 (.. (rt/get-node r "h") -gain -value)))))

(def dc-processor-source
  "registerProcessor('dc-test-processor', class extends AudioWorkletProcessor {
     process(_inputs, outputs) { outputs[0][0].fill(0.5); return true; }
   });")

(deftest a-worklet-module-added-at-runtime-backs-a-custom-node-type
  (async done
    (let [offline (js/OfflineAudioContext. 1 128 44100)
          url (js/URL.createObjectURL (js/Blob. #js [dc-processor-source] #js {:type "application/javascript"}))
          r (rt/create-runtime offline
                               {:node-specs (assoc specs/node-specs
                                                   :dc {:worklet-name "dc-test-processor"
                                                        :worklet-options {:numberOfInputs 0}
                                                        :inputs []
                                                        :outputs [:audio-out]
                                                        :params {}})})]
      (-> (rt/add-worklet-module! r url)
          (.then (fn [r']
                   (rt/mount! r' {:nodes {"dc" {:type "dc" :data {}}
                                          "out" {:type "out" :data {}}}
                                  :edges [{:source "dc" :sourceHandle "audio-out" :target "out" :targetHandle "audio-in"}]})
                   (.startRendering offline)))
          (.then (fn [buf]
                   (is (< (js/Math.abs (- 0.5 (aget (.getChannelData buf 0) 64))) 1e-6))
                   (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))

(def level-processor-source
  "registerProcessor('level-test-processor', class extends AudioWorkletProcessor {
     constructor(options) { super(); this.level = options.processorOptions.level; }
     process(_inputs, outputs) { outputs[0][0].fill(this.level); return true; }
   });")

(deftest processor-options-come-from-node-data-named-in-the-spec
  (async done
    (let [offline (js/OfflineAudioContext. 2 128 44100)
          url (js/URL.createObjectURL (js/Blob. #js [level-processor-source] #js {:type "application/javascript"}))
          r (rt/create-runtime offline
                               {:node-specs (assoc specs/node-specs
                                                   :level {:worklet-name "level-test-processor"
                                                           :worklet-options {:numberOfInputs 0}
                                                           :processor-options [:level]
                                                           :inputs []
                                                           :outputs [:audio-out]
                                                           :params {:level {:type :number :default 0.25}}})})
          merge-node (.createChannelMerger offline 2)]
      (-> (rt/add-worklet-module! r url)
          (.then (fn [r']
                   (rt/mount! r' {:nodes {"set" {:type "level" :data {:level 0.75}}
                                          "default" {:type "level" :data {}}}
                                  :edges []})
                   (.connect (rt/get-node r' "set") merge-node 0 0)
                   (.connect (rt/get-node r' "default") merge-node 0 1)
                   (.connect merge-node (.-destination offline))
                   (.startRendering offline)))
          (.then (fn [buf]
                   (is (= 0.75 (aget (.getChannelData buf 0) 64)) "value from node data")
                   (is (= 0.25 (aget (.getChannelData buf 1) 64)) "spec default when data omits it")
                   (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))

(deftest mount-does-not-create-module-placeholder-nodes
  (let [offline (js/OfflineAudioContext. 1 128 44100)
        r (rt/create-runtime offline)
        flow {:nodes {"m" {:type "module" :data {}}
                      "out" {:type "out" :data {}}}
              :edges []}]
    (rt/mount! r flow)
    (is (nil? (rt/get-node r "m")))
    (is (some? (rt/get-node r "out")))))

(deftest unmount-clears-node-bookkeeping
  (let [offline (js/OfflineAudioContext. 2 128 44100)
        r (rt/create-runtime offline)]
    (rt/mount! r sine->out)
    (is (some? (rt/get-node r "o")))
    (rt/unmount! r)
    (is (nil? (rt/get-node r "o")))
    (is (nil? (rt/get-node r "out")))))

(deftest every-curve-is-bounded-and-odd
  ;; A WaveShaper curve maps [-1, 1] onto [-1, 1]. Leaving that range is a
  ;; gain blow-up; breaking odd symmetry flips the polarity of one half-wave
  ;; and adds DC. Sample i sits at x = 2i/n - 1, so sample n-i sits at -x.
  (doseq [curve-type (get-in specs/node-specs [:dist :params :curve :options])]
    (testing curve-type
      (let [curve (rt/make-distortion-curve curve-type)
            n     (.-length curve)]
        (is (every? #(<= -1 % 1) (array-seq curve)) "stays within [-1, 1]")
        (is (every? (fn [i] (< (js/Math.abs (+ (aget curve i) (aget curve (- n i)))) 1e-6))
                    (range 1 n))
            "is odd: f(-x) = -f(x)")))))
