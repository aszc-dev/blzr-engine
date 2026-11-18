(ns blzr.engine.hash-test
  (:require
   [blzr.engine.hash :as h]
   [cljs.test :refer [deftest is testing]]))

(def simple-chain
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "flt"}
           "n3" {:type "amp"}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e3" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def simple-chain-shuffled-ids
  {:nodes {"xyz" {:type "osc"}
           "abc" {:type "flt"}
           "qrs" {:type "amp"}
           "zzz" {:type "out"}}
   :edges [{:id "ea" :source "xyz" :sourceHandle "audio-out" :target "abc" :targetHandle "audio-in"}
           {:id "eb" :source "abc" :sourceHandle "audio-out" :target "qrs" :targetHandle "audio-in"}
           {:id "ec" :source "qrs" :sourceHandle "audio-out" :target "zzz" :targetHandle "audio-in"}]})

(def simple-chain-with-positions
  {:nodes {"n1" {:type "osc" :position {:x 100 :y 200}}
           "n2" {:type "flt" :position {:x 300 :y 200}}
           "n3" {:type "amp" :position {:x 500 :y 200}}
           "n4" {:type "out" :position {:x 700 :y 200}}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e3" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def simple-chain-with-params
  {:nodes {"n1" {:type "osc" :data {:frequency 880 :type "sawtooth"}}
           "n2" {:type "flt" :data {:frequency 2000 :Q 5}}
           "n3" {:type "amp" :data {:gain 0.5}}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e3" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def different-topology
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "amp"}
           "n3" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}]})

(def two-components
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "amp"}
           "n3" {:type "osc"}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def two-components-reshuffled
  {:nodes {"zz" {:type "osc"}
           "yy" {:type "out"}
           "xx" {:type "osc"}
           "ww" {:type "amp"}}
   :edges [{:id "ea" :source "xx" :sourceHandle "audio-out" :target "ww" :targetHandle "audio-in"}
           {:id "eb" :source "zz" :sourceHandle "audio-out" :target "yy" :targetHandle "audio-in"}]})

(def empty-graph
  {:nodes {} :edges []})

(def single-node
  {:nodes {"n1" {:type "osc"}} :edges []})

(def three-isolated-nodes
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "flt"}
           "n3" {:type "amp"}}
   :edges []})

(def cv-modulation
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "const"}
           "n3" {:type "amp"}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "gain"}
           {:id "e3" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def cv-modulation-different-param
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "const"}
           "n3" {:type "flt"}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "frequency"}
           {:id "e3" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def stereo-split-merge
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "split"}
           "n3" {:type "merge"}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "left" :target "n3" :targetHandle "left"}
           {:id "e3" :source "n2" :sourceHandle "right" :target "n3" :targetHandle "right"}
           {:id "e4" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def two-oscs-same-target
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "osc"}
           "n3" {:type "amp"}
           "n4" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}
           {:id "e3" :source "n3" :sourceHandle "audio-out" :target "n4" :targetHandle "audio-in"}]})

(def keyword-types
  {:nodes {"n1" {:type :osc}
           "n2" {:type :flt}
           "n3" {:type :out}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}]})

(def string-types
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "flt"}
           "n3" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}]})

(deftest canonical-topology-deterministic
  (testing "same graph always produces same canonical string"
    (let [t1 (h/canonical-topology simple-chain)
          t2 (h/canonical-topology simple-chain)]
      (is (= t1 t2)))))

(deftest canonical-topology-id-invariant
  (testing "different node IDs with same topology produce same canonical string"
    (is (= (h/canonical-topology simple-chain)
            (h/canonical-topology simple-chain-shuffled-ids)))))

(deftest canonical-topology-position-invariant
  (testing "positions don't affect topology"
    (is (= (h/canonical-topology simple-chain)
            (h/canonical-topology simple-chain-with-positions)))))

(deftest canonical-topology-param-invariant
  (testing "parameter values don't affect topology"
    (is (= (h/canonical-topology simple-chain)
            (h/canonical-topology simple-chain-with-params)))))

(deftest canonical-topology-different-graph
  (testing "different topology produces different canonical string"
    (is (not= (h/canonical-topology simple-chain)
              (h/canonical-topology different-topology)))))

(deftest hash-deterministic
  (testing "same topology always produces same hash"
    (is (= (h/hash-topology (h/canonical-topology simple-chain))
            (h/hash-topology (h/canonical-topology simple-chain))))))

(deftest hash-id-invariant
  (testing "hash is same for different IDs with same topology"
    (is (= (h/hash-topology (h/canonical-topology simple-chain))
            (h/hash-topology (h/canonical-topology simple-chain-shuffled-ids))))))

(deftest hash-different-for-different-topology
  (testing "different topology produces different hash"
    (is (not= (h/hash-topology (h/canonical-topology simple-chain))
              (h/hash-topology (h/canonical-topology different-topology))))))

(deftest empty-graph-handling
  (testing "empty graph produces valid topology"
    (let [topo (h/canonical-topology empty-graph)]
      (is (string? topo))
      (is (= "||" topo))))
  (testing "empty graph fingerprint"
    (let [fp (h/fingerprint empty-graph)]
      (is (zero? (:node-count fp)))
      (is (zero? (:edge-count fp)))
      (is (= [] (:node-types fp))))))

(deftest single-node-handling
  (testing "single node topology"
    (let [topo (h/canonical-topology single-node)]
      (is (= "osc||" topo))))
  (testing "single node is one component"
    (let [comps (h/component-topologies single-node)]
      (is (= 1 (count comps)))
      (is (= "osc||" (first comps))))))

(deftest isolated-nodes-are-separate-components
  (testing "three isolated nodes = three components"
    (let [comps (h/component-topologies three-isolated-nodes)]
      (is (= 3 (count comps)))
      (is (= ["amp||" "flt||" "osc||"] comps)))))

(deftest two-component-detection
  (testing "two disconnected subgraphs produce two components"
    (let [comps (h/component-topologies two-components)]
      (is (= 2 (count comps)))))
  (testing "component topologies are sorted and consistent regardless of ID assignment"
    (is (= (h/component-topologies two-components)
            (h/component-topologies two-components-reshuffled)))))

(deftest cv-modulation-handle-sensitivity
  (testing "different target handles produce different topologies"
    (is (not= (h/canonical-topology cv-modulation)
              (h/canonical-topology cv-modulation-different-param)))))

(deftest stereo-routing-handles
  (testing "split/merge with left/right handles are captured"
    (let [topo (h/canonical-topology stereo-split-merge)]
      (is (re-find #"left" topo))
      (is (re-find #"right" topo)))))

(deftest duplicate-node-types
  (testing "two oscs going into same amp are captured"
    (let [topo (h/canonical-topology two-oscs-same-target)]
      (is (re-find #"osc.*osc" topo)))))

(deftest keyword-vs-string-types
  (testing "keyword and string types produce same topology"
    (is (= (h/canonical-topology keyword-types)
            (h/canonical-topology string-types)))))

(deftest fingerprint-complete
  (testing "fingerprint returns all expected keys"
    (let [fp (h/fingerprint simple-chain)]
      (is (string? (:graph-hash fp)))
      (is (string? (:graph-topology fp)))
      (is (vector? (:component-hashes fp)))
      (is (vector? (:component-topologies fp)))
      (is (= 4 (:node-count fp)))
      (is (= 3 (:edge-count fp)))
      (is (= ["amp" "flt" "osc" "out"] (:node-types fp)))))
  (testing "fingerprint hash matches manual hash"
    (let [fp (h/fingerprint simple-chain)]
      (is (= (:graph-hash fp)
              (h/hash-topology (:graph-topology fp)))))))

(deftest fingerprint-invariants
  (testing "fingerprints are identical for isomorphic graphs"
    (is (= (:graph-hash (h/fingerprint simple-chain))
            (:graph-hash (h/fingerprint simple-chain-shuffled-ids))))
    (is (= (:graph-hash (h/fingerprint simple-chain))
            (:graph-hash (h/fingerprint simple-chain-with-positions))))
    (is (= (:graph-hash (h/fingerprint simple-chain))
            (:graph-hash (h/fingerprint simple-chain-with-params))))))

(deftest component-hash-consistency
  (testing "component hashes from two-component graph match individual single-component hashes"
    (let [comp-hashes (set (:component-hashes (h/fingerprint two-components)))
          osc-amp {:nodes {"a" {:type "osc"} "b" {:type "amp"}}
                   :edges [{:id "e" :source "a" :sourceHandle "audio-out" :target "b" :targetHandle "audio-in"}]}
          osc-out {:nodes {"a" {:type "osc"} "b" {:type "out"}}
                   :edges [{:id "e" :source "a" :sourceHandle "audio-out" :target "b" :targetHandle "audio-in"}]}
          h1 (:graph-hash (h/fingerprint osc-amp))
          h2 (:graph-hash (h/fingerprint osc-out))]
      (is (contains? comp-hashes h1))
      (is (contains? comp-hashes h2)))))

(deftest default-handles
  (testing "missing handles default to audio-out/audio-in"
    (let [with-handles {:nodes {"a" {:type "osc"} "b" {:type "amp"}}
                        :edges [{:id "e" :source "a" :sourceHandle "audio-out" :target "b" :targetHandle "audio-in"}]}
          without-handles {:nodes {"a" {:type "osc"} "b" {:type "amp"}}
                           :edges [{:id "e" :source "a" :target "b"}]}]
      (is (= (h/canonical-topology with-handles)
              (h/canonical-topology without-handles))))))

(deftest topology-string-reconstructable
  (testing "topology string contains enough info to identify the graph pattern"
    (let [topo (h/canonical-topology simple-chain)]
      (is (re-find #"amp" topo))
      (is (re-find #"flt" topo))
      (is (re-find #"osc" topo))
      (is (re-find #"out" topo))
      (is (re-find #"audio-out>.*audio-in" topo)))))

(deftest hash-length-consistent
  (testing "hash is always 16 hex chars"
    (let [hashes [(h/hash-topology "")
                  (h/hash-topology "osc||")
                  (h/hash-topology (h/canonical-topology simple-chain))
                  (h/hash-topology (h/canonical-topology stereo-split-merge))]]
      (doseq [h hashes]
        (is (= 16 (count h)))
        (is (re-matches #"[0-9a-f]{16}" h))))))
