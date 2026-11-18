(ns blzr.engine.dsp.envelope-test
  (:require
   [blzr.engine.dsp.envelope :as env]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [cljs.test :refer-macros [deftest is testing]]))

(defn- in-01? [^number x] (and (>= x 0.0) (<= x 1.0)))

(deftest envelope-length-matches-n
  (is (= 256 (.-length (env/tukey-envelope {:sides 0.5 :tilt 0.0 :curve 1.0 :n 256})))))

(deftest envelope-values-in-0-1
  (let [t (env/tukey-envelope {:sides 0.3 :tilt 0.0 :curve 2.0 :n 512})]
    (is (every? in-01? (array-seq t)))))

(deftest envelope-endpoints-near-zero-for-non-degenerate-sides
  (let [t (env/tukey-envelope {:sides 0.5 :tilt 0.0 :curve 1.0 :n 1024})]
    (is (< (aget t 0) 0.05))
    (is (< (aget t (dec (.-length t))) 0.05))))

(deftest envelope-monotone-non-decreasing-on-attack-region
  (let [t (env/tukey-envelope {:sides 0.5 :tilt 0.0 :curve 1.0 :n 256})
        half (quot (.-length t) 4)] ;; first quarter is well within the attack taper
    (is (every? true?
                (for [i (range 1 half)]
                  (>= (aget t i) (- (aget t (dec i)) 1e-9)))))))

(deftest envelope-symmetric-under-zero-tilt
  (let [t (env/tukey-envelope {:sides 0.5 :tilt 0.0 :curve 1.0 :n 257})
        n (.-length t)]
    (is (every? true?
                (for [i (range n)]
                  (< (js/Math.abs (- (aget t i) (aget t (- n 1 i)))) 1e-6))))))

(def params-gen
  (gen/let [sides (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})
            tilt  (gen/double* {:min -1.0 :max 1.0 :NaN? false :infinite? false})
            curve (gen/double* {:min 0.1 :max 10.0 :NaN? false :infinite? false})]
    {:sides sides :tilt tilt :curve curve :n 128}))

(def prop-never-nan-or-inf
  (prop/for-all [params params-gen]
    (let [t (env/tukey-envelope params)]
      (every? (fn [x] (and (js/isFinite x) (not (js/isNaN x)))) (array-seq t)))))

(deftest envelope-never-nan-or-inf
  (testing "test.check over sides/tilt/curve ranges"
    (let [result (tc/quick-check 100 prop-never-nan-or-inf)]
      (is (:pass? result) (pr-str result)))))
