(ns blzr.engine.hash-test
  "JVM port of engine/test/blzr/engine/hash_test.cljs. Pins the exact
   outputs that blzr-api reproduces: equal states -> equal hash, param
   change -> different hash, invariants."
  (:require [blzr.engine.hash :as h]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

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

(deftest canonical-topology-id-invariant
  (testing "given two graphs with identical topology but different node IDs"
    (testing "when computing the canonical topology string"
      (testing "then the strings are equal"
        (is (= (h/canonical-topology simple-chain)
               (h/canonical-topology simple-chain-shuffled-ids)))))))

(deftest canonical-topology-param-invariant
  (testing "given two graphs with identical topology but different param values"
    (testing "when computing the canonical topology string"
      (testing "then param values don't affect the string"
        (is (= (h/canonical-topology simple-chain)
               (h/canonical-topology simple-chain-with-params)))))))

(deftest canonical-topology-different-graph
  (testing "given two graphs with different topology"
    (testing "when computing the canonical topology string"
      (testing "then the strings differ"
        (is (not= (h/canonical-topology simple-chain)
                  (h/canonical-topology different-topology)))))))

(deftest hash-id-invariant
  (testing "given two graphs with identical topology but different node IDs"
    (testing "when hashing the canonical topology"
      (testing "then the hashes are equal"
        (is (= (h/hash-topology (h/canonical-topology simple-chain))
               (h/hash-topology (h/canonical-topology simple-chain-shuffled-ids))))))))

(deftest hash-different-for-different-topology
  (testing "given two graphs with different topology"
    (testing "when hashing the canonical topology"
      (testing "then the hashes differ"
        (is (not= (h/hash-topology (h/canonical-topology simple-chain))
                  (h/hash-topology (h/canonical-topology different-topology))))))))

(deftest empty-graph-handling
  (testing "given an empty graph"
    (testing "when computing topology and fingerprint"
      (let [topo (h/canonical-topology empty-graph)
            fp (h/fingerprint empty-graph)]
        (testing "then both report zero content"
          (is (= "||" topo))
          (is (zero? (:node-count fp)))
          (is (zero? (:edge-count fp)))
          (is (= [] (:node-types fp))))))))

(deftest two-component-detection
  (testing "given a graph with two disconnected subgraphs"
    (testing "when computing component topologies"
      (let [comps (h/component-topologies two-components)]
        (testing "then two components are found, independent of node ID assignment"
          (is (= 2 (count comps)))
          (is (= comps (h/component-topologies two-components-reshuffled))))))))

(deftest fingerprint-complete
  (testing "given a simple audio chain"
    (testing "when computing the fingerprint"
      (let [fp (h/fingerprint simple-chain)]
        (testing "then all summary fields and the hash/topology relationship hold"
          (is (= 4 (:node-count fp)))
          (is (= 3 (:edge-count fp)))
          (is (= ["amp" "flt" "osc" "out"] (:node-types fp)))
          (is (= (:graph-hash fp) (h/hash-topology (:graph-topology fp)))))))))

(deftest fingerprint-invariants
  (testing "given isomorphic graphs (same topology, different IDs/params)"
    (testing "when computing fingerprints"
      (testing "then graph-hash is identical across all of them"
        (is (= (:graph-hash (h/fingerprint simple-chain))
               (:graph-hash (h/fingerprint simple-chain-shuffled-ids))))
        (is (= (:graph-hash (h/fingerprint simple-chain))
               (:graph-hash (h/fingerprint simple-chain-with-params))))))))

(deftest hash-length-consistent
  (testing "given topology strings of varying content"
    (testing "when hashing them"
      (testing "then every hash is a 16-char lowercase hex string"
        (let [hashes [(h/hash-topology "")
                      (h/hash-topology "osc||")
                      (h/hash-topology (h/canonical-topology simple-chain))]]
          (doseq [hex hashes]
            (is (= 16 (count hex)))
            (is (re-matches #"[0-9a-f]{16}" hex))))))))

;; --- mutation-hardening (T12): the suites above assert equality/inequality
;; relationships but never pinned concrete outputs, so djb2 arithmetic
;; (bit-shift + char-add) and canonical-edge's default-handle `or` fallbacks
;; had surviving mutants. These lock down exact values / exercised branches. ---

(deftest hash-topology-pins-exact-djb2-output
  (testing "given known topology strings"
    (testing "when hashing them"
      (testing "then the exact djb2 hex is stable (pins + and bit-shift arithmetic)"
        ;; The empty string reduces to the djb2 seed 5381 = 0x1505.
        (is (= "0000000000001505" (h/hash-topology "")))
        ;; A non-empty string exercises (+ (bit-shift-left h 5) h char); any
        ;; arithmetic mutation there changes this concrete value.
        (is (= "0000000010186542" (h/hash-topology "osc||")))))))

(def edge-without-handles
  ;; Same topology as different-topology, but the middle edge omits
  ;; sourceHandle/targetHandle so canonical-edge must apply its "audio-out"/
  ;; "audio-in" `or` defaults. Drop those (or -> and) and the string differs.
  {:nodes {"n1" {:type "osc"}
           "n2" {:type "amp"}
           "n3" {:type "out"}}
   :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
           {:id "e2" :source "n2" :target "n3"}]})

(deftest canonical-edge-defaults-missing-handles
  (testing "given a graph whose edge omits sourceHandle and targetHandle"
    (testing "when computing the canonical topology"
      (testing "then the missing handles default to audio-out/audio-in"
        (is (= (h/canonical-topology different-topology)
               (h/canonical-topology edge-without-handles)))
        ;; explicitly pin that the defaulted edge string carries both handles
        (is (str/includes? (h/canonical-topology edge-without-handles)
                           "amp:audio-out>out:audio-in"))))))

(def three-nodes-one-sink
  ;; a->b and c->b: all three nodes form one component through the shared sink.
  ;; Exercises the per-component edge filter (`and` of source-in-set and
  ;; target-in-set): both edges belong to the single component.
  {:nodes {"a" {:type "osc"} "b" {:type "out"} "c" {:type "amp"}}
   :edges [{:id "e1" :source "a" :sourceHandle "audio-out" :target "b" :targetHandle "audio-in"}
           {:id "e2" :source "c" :sourceHandle "audio-out" :target "b" :targetHandle "audio-in"}]})

(deftest per-component-edge-filter-keeps-in-component-edges
  (testing "given one connected component reachable through a shared sink"
    (testing "when computing component topologies"
      (testing "then all three node types and both edges land in the one component"
        (let [comps (h/component-topologies three-nodes-one-sink)]
          (is (= 1 (count comps)))
          (is (= "amp,osc,out||amp:audio-out>out:audio-in,osc:audio-out>out:audio-in"
                 (first comps))))))))
