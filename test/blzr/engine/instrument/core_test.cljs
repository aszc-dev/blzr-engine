(ns blzr.engine.instrument.core-test
  (:require [cljs.test :refer [deftest is async]]
            [blzr.engine.instrument.core :as inst]
            [blzr.engine.runtime :as rt]))

(def instrument
  {:instrument/id :granular-fixture
   :instrument/name "granular fixture"
   :instrument/graph
   {:nodes {"gr" {:type "granular" :data {:density 20 :grainMs 80}}
            "flt" {:type "flt" :data {:type "lowpass" :frequency 8000}}
            "amp" {:type "amp" :data {:gain 0.9}}
            "out" {:type "out" :data {}}}
    :edges [{:source "gr" :sourceHandle "audio-out" :target "flt" :targetHandle "audio-in"}
            {:source "flt" :target "amp" :targetHandle "audio-in"}
            {:source "amp" :target "out" :targetHandle "audio-in"}]}
   :instrument/params
   [{:param/key :density :param/range [0.5 200] :param/default 20 :param/curve :exp :param/audio-rate :k-rate}
    {:param/key :scan :param/range [0.0 1.0] :param/default 0.0 :param/audio-rate :a-rate}
    {:param/key :panSpray :param/range [0.0 1.0] :param/default 0.0 :param/audio-rate :k-rate}]
   :instrument/voicing {:voicing/mode :poly :voicing/strategy :worklet}
   :instrument/surface
   [{:control/type :knob :control/label "Density"
     :control/target [{:target/ref {:param/node "gr" :param/key :density} :target/range [0.5 200] :target/curve :exp}]}
    {:control/type :xy-scan :control/label "Scan / Spray"
     :control/target [{:target/ref {:param/node "gr" :param/key :scan}}
                      {:target/ref {:param/node "gr" :param/key :panSpray}}]}
    {:control/type :curve :control/label "Grain Env"
     :control/opts {:sides 0.5 :tilt 0.0 :curve 1.0}}
    {:control/type :knob :control/label "Tone"
     :control/target [{:target/ref {:param/node "flt" :param/key :frequency} :target/range [200 12000] :target/curve :exp}]}]
   :instrument/taps
   [{:tap/id :wave :tap/type :waveform :tap/source "amp"}
    {:tap/id :grains :tap/type :worklet-message :tap/source "gr" :tap/opts {:key "grains"}}
    {:tap/id :level :tap/type :level :tap/source "amp"}
    {:tap/id :spec :tap/type :spectrum :tap/source "amp"}]
   :instrument/io {:io/out {:target :master} :io/midi :none}})

(deftest mount-instrument-rejects-invalid-instrument
  (let [bad (dissoc instrument :instrument/graph)]
    (try
      (inst/mount-instrument (js/OfflineAudioContext. 1 128 44100) bad)
      (is false "should have thrown")
      (catch :default e
        (is (contains? (ex-data e) :errors))))))

(deftest mount-instrument-rejects-dangling-ref
  (let [bad (assoc instrument :instrument/surface
                   [{:control/type :knob :control/label "Ghost"
                     :control/target [{:target/ref {:param/node "does-not-exist" :param/key :x}}]}])]
    (try
      (inst/mount-instrument (js/OfflineAudioContext. 1 128 44100) bad)
      (is false "should have thrown")
      (catch :default e
        (is (contains? (ex-data e) :problems))))))

(deftest mount-instrument-wires-taps-and-controls
  (async done
    (.then (inst/mount-instrument (js/OfflineAudioContext. 2 128 44100) instrument) (fn [bundle] (is (= 4 (count (:taps bundle)))) (is (= 4 (count (:controls bundle)))) (is (every? (clojure.core/fn [%1] (contains? (:taps bundle) %1)) [:wave :grains :level :spec])) (is (fn? (:unlock bundle))) (doseq [{:keys [set]} (:controls bundle)] (is (fn? set))) (done)))))

(deftest control-set-moves-the-underlying-param
  ;; blzr.engine.runtime/set-param! schedules AudioParam changes via
  ;; setTargetAtTime — .value doesn't change synchronously (it's an
  ;; asymptotic ramp toward the target), so we spy on the scheduling call
  ;; itself rather than read .value right after :set.
  (async done
    (.then (inst/mount-instrument (js/OfflineAudioContext. 2 128 44100) instrument) (fn [bundle] (let [density-control (first (filter (clojure.core/fn [%1] (= "Density" (:control/label (:control %1)))) (:controls bundle))) node (rt/get-node (:runtime bundle) "gr") param (.get (.-parameters node) "density") orig (.bind (.-setTargetAtTime param) param) scheduled (atom [])] (set! (.-setTargetAtTime param) (fn [target & args] (swap! scheduled conj target) (apply orig target args))) ((:set density-control) 1.0) (is (some (clojure.core/fn [%1] (< (js/Math.abs (- %1 200)) 1.0E-6)) (clojure.core/deref scheduled)) "control set should schedule density toward 200") (done))))))

(deftest mount-instrument-passes-runtime-options-through
  (async done
    (.then (inst/mount-instrument (js/OfflineAudioContext. 2 128 44100) instrument
                                  {:worklet-base "/missing-worklets/"})
           (fn [_] (is false "worklets should load from the given base") (done))
           (fn [_] (is true) (done)))))
