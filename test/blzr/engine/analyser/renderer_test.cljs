(ns blzr.engine.analyser.renderer-test
  "Renderer correctness as a call-sequence transform.

   Plan → canvas API calls is asserted via a recording stub.
   No pixel snapshots: they are brittle across DPR and browsers."
  (:require
   [blzr.engine.analyser.renderer :as r]
   [blzr.engine.analyser.render-plan :as rp]
   [cljs.test :refer [deftest is testing]]))

(defn- stub-ctx []
  (let [calls (atom [])
        rec  (fn [op & args] (swap! calls conj (vec (cons op args))))
        ctx (js-obj
             "setTransform" (fn [a b c d e f] (rec :setTransform a b c d e f))
             "fillRect"     (fn [x y w h]     (rec :fillRect x y w h))
             "beginPath"    (fn []            (rec :beginPath))
             "moveTo"       (fn [x y]         (rec :moveTo x y))
             "lineTo"       (fn [x y]         (rec :lineTo x y))
             "stroke"       (fn []            (rec :stroke))
             "fillText"     (fn [s x y]       (rec :fillText s x y)))]
    {:ctx ctx :calls calls}))

(defn- ops-of [calls op]
  (filterv #(= op (first %)) @calls))

(deftest draw-applies-dpr-and-clears
  (testing "Plan with no layers → setTransform with DPR + bg fillRect"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 800 :h 300 :dpr 2.0} :layers []}]
      (r/draw! ctx plan)
      (is (= [:setTransform 2.0 0 0 2.0 0 0]
             (first @calls)))
      (is (= [:fillRect 0 0 800 300]
             (second @calls))))))

(deftest draw-grid-emits-stroke-pairs
  (testing "v-divs=2 h-divs=2 grid emits 6 line-segments stroked once"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 100 :h 100 :dpr 1.0}
                :layers [{:kind :grid :v-divs 2 :h-divs 2}]}]
      (r/draw! ctx plan)
      (is (= 6 (count (ops-of calls :moveTo))))
      (is (= 6 (count (ops-of calls :lineTo))))
      (is (= 1 (count (ops-of calls :stroke))))
      (is (= [:moveTo 50 0] (nth (ops-of calls :moveTo) 1))
          "Middle vertical at x=w/2"))))

(deftest draw-trace-emits-polyline
  (testing "3 points → moveTo + 2 lineTo + stroke"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 100 :h 100 :dpr 1.0}
                :layers [{:kind :trace :points [[0 50] [50 25] [100 75]]}]}]
      (r/draw! ctx plan)
      (is (= 1 (count (ops-of calls :moveTo))))
      (is (= 2 (count (ops-of calls :lineTo))))
      (is (= [:moveTo 0 50] (first (ops-of calls :moveTo))))
      (is (= [:lineTo 100 75] (last (ops-of calls :lineTo))))
      (is (= 1 (count (ops-of calls :stroke)))))))

(deftest draw-trace-empty-skips-stroke
  (testing "Trace layer with no points → no path emitted"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 100 :h 100 :dpr 1.0}
                :layers [{:kind :trace :points []}]}]
      (r/draw! ctx plan)
      (is (zero? (count (ops-of calls :stroke))))
      (is (zero? (count (ops-of calls :moveTo)))))))

(deftest draw-spectrum-emits-bars
  (testing "Bars render as fillRect calls"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 100 :h 100 :dpr 1.0}
                :layers [{:kind :spectrum
                          :bars [[0 80 5 20] [10 60 5 40] [20 40 5 60]]}]}]
      (r/draw! ctx plan)
      ;; first fillRect is the bg clear, then 3 spectrum bars
      (is (= 4 (count (ops-of calls :fillRect))))
      (is (= [:fillRect 10 60 5 40]
             (nth (ops-of calls :fillRect) 2))))))

(deftest harmonics-layer-is-canvas-noop
  (testing "Harmonics render as DOM markers (Wave 2); canvas layer is a no-op"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 100 :h 100 :dpr 1.0}
                :layers [{:kind :harmonics
                          :markers [{:hz 440 :ord 1 :parity :odd :px-x 25}
                                    {:hz 880 :ord 2 :parity :even :px-x 50}]}]}]
      (r/draw! ctx plan)
      (is (zero? (count (ops-of calls :moveTo))))
      (is (zero? (count (ops-of calls :lineTo))))
      (is (zero? (count (ops-of calls :stroke)))))))

(deftest cursors-layer-is-canvas-noop
  (testing "Cursors render as DOM overlays (Wave 4); canvas layer is a no-op"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 200 :h 100 :dpr 1.0}
                :layers [{:kind :cursors
                          :items [{:x 75 :label "A" :readout "440 Hz"}]}]}]
      (r/draw! ctx plan)
      (is (zero? (count (ops-of calls :moveTo))))
      (is (zero? (count (ops-of calls :fillText)))))))

(deftest readout-layer-is-canvas-noop
  (testing "Readout renders via DOM panel (Wave 2); canvas layer is a no-op"
    (let [{:keys [ctx calls]} (stub-ctx)
          plan {:viewport {:w 200 :h 100 :dpr 1.0}
                :layers [{:kind :readout :text "440.12 Hz  A4+0¢  -6.3 dBFS"}]}]
      (r/draw! ctx plan)
      (is (zero? (count (ops-of calls :fillText)))
          "No canvas text — DOM owns the readout"))))

(deftest draw-default-render-plan-is-noop-stable
  (testing "Default config render-plan draws without error and is idempotent"
    (let [plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)})
          {:keys [ctx calls]} (stub-ctx)
          _ (r/draw! ctx plan)
          first-pass (vec @calls)
          {ctx2 :ctx calls2 :calls} (stub-ctx)]
      (r/draw! ctx2 plan)
      (is (= (count first-pass) (count @calls2))
          "Idempotent: same plan produces same number of calls"))))
