(ns blzr.engine.instrument.schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [malli.generator :as mg]
            [blzr.engine.instrument.schema :as s]))

(def minimal
  {:instrument/id :probe
   :instrument/graph {:nodes {"o" {:type "osc" :data {}} "out" {:type "out" :data {}}}
                      :edges [{:source "o" :target "out"}]}
   :instrument/voicing {:voicing/mode :none}
   :instrument/surface []
   :instrument/taps []
   :instrument/io {:io/out {:target :master}}})

(deftest minimal-validates       (is (s/validate minimal)))
(deftest missing-graph-fails     (is (not (s/validate (dissoc minimal :instrument/graph)))))
(deftest missing-id-fails        (is (not (s/validate (dissoc minimal :instrument/id)))))
(deftest explain-returns-data    (is (map? (s/explain (dissoc minimal :instrument/id)))))
(deftest explain-valid-is-nil    (is (nil? (s/explain minimal))))

(deftest json-schema-produces-a-map
  (is (map? (s/->json-schema))))

(deftest generator-produces-valid
  (testing "every generated instrument validates"
    (dotimes [_ 50]
      (is (s/validate (mg/generate s/Instrument {:size 8}))))))

(deftest cross-check-passes-for-minimal
  (is (nil? (s/cross-check minimal))))

(deftest cross-check-catches-dangling-surface-ref
  (let [bad (assoc minimal :instrument/surface
                   [{:control/type :knob :control/label "X"
                     :control/target [{:target/ref {:param/node "ghost" :param/key :freq}}]}])]
    (is (seq (s/cross-check bad)) "dangling ParamRef must be reported")))

(deftest cross-check-catches-dangling-tap-ref
  (let [bad (assoc minimal :instrument/taps
                   [{:tap/id :wave :tap/type :waveform :tap/source "ghost"}])]
    (is (seq (s/cross-check bad)) "dangling NodeRef tap source must be reported")))

(deftest cross-check-catches-unknown-per-voice-param
  (let [bad (-> minimal
                (assoc :instrument/params [{:param/key :density :param/range [0.0 1.0] :param/default 0.5}])
                (assoc-in [:instrument/voicing :voicing/per-voice] #{:ghost}))]
    (is (seq (s/cross-check bad)) "unknown per-voice param key must be reported")))

(deftest cross-check-accepts-valid-refs
  (let [ok (-> minimal
               (assoc :instrument/params [{:param/key :density :param/range [0.0 1.0] :param/default 0.5}])
               (assoc :instrument/surface
                      [{:control/type :knob :control/label "Density"
                        :control/target [{:target/ref {:param/node "o" :param/key :density}}]}])
               (assoc :instrument/taps
                      [{:tap/id :wave :tap/type :waveform :tap/source "o"}])
               (assoc-in [:instrument/voicing :voicing/per-voice] #{:density}))]
    (is (nil? (s/cross-check ok)))))
