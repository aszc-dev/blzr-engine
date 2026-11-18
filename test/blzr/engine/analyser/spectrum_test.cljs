(ns blzr.engine.analyser.spectrum-test
  "Unit tests for blzr.engine.analyser.dsp.spectrum kernels."
  (:require
   [blzr.engine.analyser.dsp.spectrum :as spec]
   [cljs.test :refer [deftest is testing]]))

(defn- f32 [coll]
  (let [n (count coll)
        out (js/Float32Array. n)]
    (dotimes [i n] (aset out i (nth coll i)))
    out))

(defn- ->vec [^js/Float32Array a]
  (vec (for [i (range (.-length a))] (aget a i))))

(deftest reset-peak-hold-fills-with-internal-floor
  (testing "reset-peak-hold! fills buffer with internal dB floor"
    (let [buf (js/Float32Array. 4)]
      (spec/reset-peak-hold! buf)
      (is (every? #(<= % -100.0) (->vec buf))))))

(deftest update-peak-hold-keeps-max-and-applies-decay
  (testing "First update lifts every bin to current; second update decays peaks below current"
    (let [peaks (js/Float32Array. 4)
          _ (spec/reset-peak-hold! peaks)
          c1 (f32 [-60 -40 -20 -80])]
      (spec/update-peak-hold! c1 peaks 0.01)
      (is (= [-60 -40 -20 -80] (->vec peaks))))

    (testing "Smaller current values let decay reduce peaks (with floor protection from max)"
      (let [peaks (f32 [-60 -40 -20 -80])
            c2 (f32 [-100 -100 -100 -100])]
        (spec/update-peak-hold! c2 peaks 0.5)
        (is (= [-60.5 -40.5 -20.5 -80.5] (->vec peaks)))))

    (testing "Larger current value overrides decayed peak"
      (let [peaks (f32 [-60 -40 -20 -80])
            c3 (f32 [-50 -100 -100 -100])]
        (spec/update-peak-hold! c3 peaks 0.5)
        (is (= [-50 -40.5 -20.5 -80.5] (->vec peaks)))))))
