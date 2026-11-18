(ns blzr.engine.voice-test
  (:require
   [blzr.engine.voice :as voice]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [cljs.test :refer-macros [deftest is testing]]))

(def poly {:voicing/mode :poly :voicing/steal :oldest})

;; ── examples (ported from the pre-extraction available-voice unit tests) ──

(deftest picks-a-free-slot-first
  (let [[id _] (voice/allocate poly {} ["n1" "n2"] 60 0)]
    (is (= "n1" id))))

(deftest skips-active-slots
  (let [state {"n1" {:note 50 :active? true :ts 100}}
        [id _] (voice/allocate poly state ["n1" "n2"] 60 200)]
    (is (= "n2" id))))

(deftest steals-oldest-when-all-active
  (let [state {"n1" {:note 50 :active? true :ts 200}
               "n2" {:note 51 :active? true :ts 100}}
        [id _] (voice/allocate poly state ["n1" "n2"] 60 300)]
    (is (= "n2" id))))

(deftest same-note-retrigger-returns-already-active-voice
  (let [state {"n1" {:note 60 :active? true :ts 100}
               "n2" {:note 61 :active? true :ts 50}}
        [id new-state] (voice/allocate poly state ["n1" "n2"] 60 200)]
    (is (= "n1" id) "retriggers the voice already sounding that note, not a steal")
    (is (= 200 (get-in new-state ["n1" :ts])) "retrigger still updates the timestamp")))

(deftest free-slot-chosen-before-any-steal
  (let [state {"n1" {:note 50 :active? true :ts 999}}
        [id _] (voice/allocate poly state ["n1" "n2"] 60 0)]
    (is (= "n2" id) "n2 is free — must not steal n1 even though n1 looks stealable")))

(deftest mono-mode-always-returns-the-single-first-voice
  (let [mono {:voicing/mode :mono}
        [id1 s1] (voice/allocate mono {} ["n1" "n2" "n3"] 60 0)
        [id2 _s2] (voice/allocate mono s1 ["n1" "n2" "n3"] 64 10)]
    (is (= "n1" id1))
    (is (= "n1" id2) "mono ignores voice-ids beyond the first — always retriggers n1")))

(deftest steal-policy-lowest-highest-empty-voice-ids-does-not-throw
  (testing "misconfigured zero-voice instrument must not crash allocate"
    (let [[id-lo _] (voice/allocate (assoc poly :voicing/steal :lowest) {} [] 60 0)
          [id-hi _] (voice/allocate (assoc poly :voicing/steal :highest) {} [] 60 0)]
      (is (nil? id-lo))
      (is (nil? id-hi)))))

(deftest release-marks-all-voices-playing-note-inactive
  (let [state {"v1" {:note 60 :active? true :ts 1}
               "v2" {:note 60 :active? true :ts 2}
               "v3" {:note 64 :active? true :ts 3}}
        [released new-state] (voice/release state 60)]
    (is (= #{"v1" "v2"} (set released)))
    (is (false? (get-in new-state ["v1" :active?])))
    (is (false? (get-in new-state ["v2" :active?])))
    (is (true? (get-in new-state ["v3" :active?])))))

;; ── properties ──────────────────────────────────────────────────────────

(def voice-ids-gen
  (gen/let [n (gen/choose 1 8)] (mapv #(str "v" %) (range n))))

(def notes-gen (gen/vector (gen/choose 21 108) 0 40))

(def prop-no-double-allocation
  (prop/for-all [voice-ids voice-ids-gen
                 notes notes-gen]
    (let [final (reduce (fn [st [i note]]
                          (second (voice/allocate poly st voice-ids note i)))
                        {} (map-indexed vector notes))
          active (filter (comp :active? val) final)]
      (= (count active) (count (set (map key active)))))))

(deftest no-double-allocation
  (testing "active voices never share a slot id, across random note sequences + pool sizes"
    (let [result (tc/quick-check 200 prop-no-double-allocation)]
      (is (:pass? result) (pr-str result)))))

(def prop-capacity-respected
  (prop/for-all [voice-ids voice-ids-gen
                 notes notes-gen]
    (let [final (reduce (fn [st [i note]]
                          (second (voice/allocate poly st voice-ids note i)))
                        {} (map-indexed vector notes))
          active (filter (comp :active? val) final)]
      (<= (count active) (count voice-ids)))))

(deftest capacity-respected
  (let [result (tc/quick-check 200 prop-capacity-respected)]
    (is (:pass? result) (pr-str result))))

(def prop-oldest-steal-honoured
  (prop/for-all [voice-ids voice-ids-gen]
    ;; fill every slot with a distinct note, ascending timestamps, then one
    ;; more note (guaranteed new, out of range) must steal the min-ts slot.
    (let [filled (reduce (fn [st [i vid]] (assoc st vid {:note (+ 1000 i) :active? true :ts i}))
                         {} (map-indexed vector voice-ids))
          oldest-id (first voice-ids)
          [chosen _] (voice/allocate poly filled voice-ids 9999 (count voice-ids))]
      (= chosen oldest-id))))

(deftest oldest-steal-honoured
  (let [result (tc/quick-check 100 prop-oldest-steal-honoured)]
    (is (:pass? result) (pr-str result))))

(def prop-release-invariant
  (prop/for-all [voice-ids voice-ids-gen
                 notes notes-gen]
    (let [final (reduce (fn [st [i note]]
                          (second (voice/allocate poly st voice-ids note i)))
                        {} (map-indexed vector notes))
          note (or (first notes) 60)
          [_ released-state] (voice/release final note)
          active (filter (comp :active? val) released-state)]
      (not-any? #(= note (:note (val %))) active))))

(deftest release-invariant
  (let [result (tc/quick-check 200 prop-release-invariant)]
    (is (:pass? result) (pr-str result))))

(def prop-alternating-allocate-release-round-trips
  (prop/for-all [voice-ids voice-ids-gen
                 note (gen/choose 21 108)
                 n (gen/choose 1 10)]
    (let [final (reduce (fn [st i]
                          (let [[_ s1] (voice/allocate poly st voice-ids note i)
                                [_ s2] (voice/release s1 note)]
                            s2))
                        {} (range n))
          active-for-note (filter #(and (:active? (val %)) (= note (:note (val %)))) final)]
      (<= (count active-for-note) 1))))

(deftest alternating-allocate-release-round-trips
  (let [result (tc/quick-check 200 prop-alternating-allocate-release-round-trips)]
    (is (:pass? result) (pr-str result))))

;; ── mount-voices: the poly seam ────────────────────────────────────────

(deftest mount-voices-worklet-returns-internal-plan
  (is (= {:strategy :worklet :voices :internal}
         (voice/mount-voices nil {:voicing/strategy :worklet} nil))))

(deftest mount-voices-graph-clone-throws
  (try
    (voice/mount-voices nil {:voicing/strategy :graph-clone} nil)
    (is false "should have thrown")
    (catch :default e
      (is (map? (ex-data e))))))

(deftest mount-voices-default-returns-single
  (is (= {:strategy :none :voices :single}
         (voice/mount-voices nil {:voicing/strategy nil} nil))))
