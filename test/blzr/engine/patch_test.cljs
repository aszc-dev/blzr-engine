(ns blzr.engine.patch-test
  (:require
   [blzr.engine.patch.descriptor :as desc]
   [blzr.engine.patch.serial :as serial]
   [blzr.engine.patch.compress :as compress]
   [cljs.test :refer [deftest is testing]]))

;; -- Test data --

(def sample-db
  {:flow {:nodes {"n1" {:id "n1" :type "osc" :data {:frequency 440 :type "sine"} :position {:x 0 :y 0}}
                  "n2" {:id "n2" :type "flt" :data {:frequency 1000 :Q 1} :position {:x 200 :y 0}}
                  "n3" {:id "n3" :type "out" :data {} :position {:x 400 :y 0}}}
          :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
                  {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}]}
   :module-ui {:by-id {}}})

(def sample-db-different-params
  {:flow {:nodes {"n1" {:id "n1" :type "osc" :data {:frequency 880 :type "sawtooth"} :position {:x 0 :y 0}}
                  "n2" {:id "n2" :type "flt" :data {:frequency 2000 :Q 5} :position {:x 200 :y 0}}
                  "n3" {:id "n3" :type "out" :data {} :position {:x 400 :y 0}}}
          :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}
                  {:id "e2" :source "n2" :sourceHandle "audio-out" :target "n3" :targetHandle "audio-in"}]}
   :module-ui {:by-id {}}})

(def sample-db-different-topology
  {:flow {:nodes {"n1" {:id "n1" :type "osc" :data {:frequency 440} :position {:x 0 :y 0}}
                  "n2" {:id "n2" :type "out" :data {} :position {:x 200 :y 0}}}
          :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}]}
   :module-ui {:by-id {}}})

(def sample-db-with-module
  {:flow {:nodes {"m1" {:id "m1" :type "module" :data {} :position {:x 0 :y 0}}
                  "n1" {:id "n1" :type "osc" :data {:frequency 440} :position {:x 0 :y 0} :parent-module "m1"}
                  "n2" {:id "n2" :type "out" :data {} :position {:x 200 :y 0}}}
          :edges [{:id "e1" :source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}]}
   :module-ui {:by-id {"m1" {:id "m1" :label "Test Module" :exposed-ports [] :exposed-params []}}}})

;; -- Descriptor extraction tests --

(deftest from-graph-structure
  (testing "extracts correct structure from a graph"
    (let [d (desc/from-graph sample-db)]
      (is (= 1 (:version d)))
      (is (= 3 (count (get-in d [:structure :nodes]))))
      (is (= 2 (count (get-in d [:structure :edges]))))
      (is (= :osc (get-in d [:structure :nodes "n1" :type])))
      (is (= :flt (get-in d [:structure :nodes "n2" :type]))))))

(deftest from-graph-params
  (testing "extracts params correctly"
    (let [d (desc/from-graph sample-db)]
      (is (= {:frequency 440 :type "sine"} (get-in d [:params "n1"])))
      (is (nil? (get-in d [:params "n3"])) "empty data nodes should be excluded"))))

(deftest from-graph-module-parent
  (testing "preserves parent-module references"
    (let [d (desc/from-graph sample-db-with-module)]
      (is (= "m1" (get-in d [:structure :nodes "n1" :parent-module]))))))

;; -- Round-trip tests --

(deftest descriptor-round-trip
  (testing "from-graph -> to-graph preserves structure"
    (let [d (desc/from-graph sample-db)
          {:keys [flow module-ui]} (desc/to-graph d)
          nodes (:nodes flow)]
      (is (= 3 (count nodes)))
      (is (= "osc" (:type (get nodes "n1"))))
      (is (= {:frequency 440 :type "sine"} (:data (get nodes "n1"))))
      (is (= 2 (count (:edges flow))))
      (is (= {:by-id {}} module-ui)))))

(deftest descriptor-round-trip-with-module
  (testing "round-trip preserves module UI and parent references"
    (let [d (desc/from-graph sample-db-with-module)
          {:keys [flow module-ui]} (desc/to-graph d)]
      (is (= "m1" (get-in flow [:nodes "n1" :parent-module])))
      (is (= "Test Module" (get-in module-ui [:by-id "m1" :label]))))))

;; -- Transit round-trip tests --

(deftest transit-round-trip
  (testing "encode -> decode preserves descriptor with keywords"
    (let [d (desc/from-graph sample-db)
          encoded (serial/encode d)
          decoded (serial/decode encoded)]
      (is (string? encoded))
      (is (= (:version d) (:version decoded)))
      (is (= (get-in d [:structure :nodes]) (get-in decoded [:structure :nodes])))
      (is (= (get-in d [:structure :edges]) (get-in decoded [:structure :edges])))
      (is (= (:params d) (:params decoded))))))

(deftest transit-round-trip-keywords
  (testing "Transit preserves keyword values in params"
    (let [data {:version 1
                :structure {:nodes {"a" {:type :osc}} :edges []}
                :params {"a" {:type "sine"}}
                :metadata {:source :user}}
          decoded (serial/decode (serial/encode data))]
      (is (= :osc (get-in decoded [:structure :nodes "a" :type])))
      (is (= :user (get-in decoded [:metadata :source]))))))

;; -- Compression round-trip tests --

(deftest compress-decompress-round-trip
  (testing "compress -> decompress preserves descriptor"
    (let [d (desc/from-graph sample-db)
          compressed (compress/compress d)
          decompressed (compress/decompress compressed)]
      (is (string? compressed))
      (is (= (:version d) (:version decompressed)))
      (is (= (get-in d [:structure :nodes]) (get-in decompressed [:structure :nodes]))))))

;; -- Hash tests --

(deftest structure-hash-isomorphic
  (testing "same topology -> same structure hash"
    (let [d1 (desc/from-graph sample-db)
          d2 (desc/from-graph sample-db-different-params)]
      (is (= (desc/structure-hash d1) (desc/structure-hash d2))))))

(deftest structure-hash-different-topology
  (testing "different topology -> different structure hash"
    (let [d1 (desc/from-graph sample-db)
          d2 (desc/from-graph sample-db-different-topology)]
      (is (not= (desc/structure-hash d1) (desc/structure-hash d2))))))

(deftest params-hash-different-values
  (testing "different params on same structure -> different params hash"
    (let [d1 (desc/from-graph sample-db)
          d2 (desc/from-graph sample-db-different-params)]
      (is (not= (desc/params-hash d1) (desc/params-hash d2))))))

(deftest full-hash-combines-both
  (testing "full hash differs when params differ on same structure"
    (let [d1 (desc/from-graph sample-db)
          d2 (desc/from-graph sample-db-different-params)]
      (is (not= (desc/full-hash d1) (desc/full-hash d2))))))

(deftest full-hash-deterministic
  (testing "same descriptor -> same full hash"
    (let [d (desc/from-graph sample-db)]
      (is (= (desc/full-hash d) (desc/full-hash d))))))

;; -- Wire format --

(def frozen-descriptor
  {:version 1
   :structure {:nodes {"m1" {:type :module :parent-module nil}
                       "n1" {:type :osc :parent-module "m1"}
                       "n2" {:type :out :parent-module nil}}
               :edges [{:source "n1" :sourceHandle "audio-out" :target "n2" :targetHandle "audio-in"}]}
   :params {"n1" {:frequency 440.5 :type "sine"}}
   :positions {"m1" {:x 0 :y 0} "n1" {:x 10 :y 20} "n2" {:x 200 :y 0}}
   :sizes {"m1" {:width 320 :height 180}}
   :module-ui {:by-id {"m1" {:id "m1" :label "Test Module" :exposed-ports [] :exposed-params []}}}
   :metadata {:source :user :created-at "2026-01-01T00:00:00.000Z"}})

(def frozen-fragment
  "NoIgegBCA0IH4C4BuBTATgZwJYHsB2MAjLIhgC5oCuAxmZWijKJDPAnjgCYoZPhSwAtoT4sSCMgE8ADo3GCulADZy20gIYM8ZALQLOyuXmVKAurDwjozAeADMrRDgzVWYAKythIcyDwAmUVswB3EcSjI3T2hjJTNfRBROAHMeJhtHBAxwtGpVS0zs+jyACXU8ThVWdUpOXB1wyPEyTVSmv0Dm1pQyMoqq2Bq6nB0sAlMJ8Q00dUFea35WAoWxNgAzBgBHShQ8akkYABZDgAYAOndYENZsPEZJtWcsMlw8eYyhKw+2AA8YE-EB2gJ18y2+iD+0EIALYQP8IIsnXBCEh8JhiCBIISWSwAC80itbN5CZkAO5YThkAAWMDs8PEVJQWGSVKahAAHFj5IoVDpKFggpkAEaSUacQWfCVsCleKxsJTqIUoJSsAAqPDIEAAsjzVIkftJnEkdIa0GR3tiUAajZwTZpZhaJtjBD11Jx1C0pWAAJyZSgYdCZagMD3Gj2seH+ABsOhOhFjhFVJxOCGTqfOyZOAC0fKYgA")

(deftest share-link-wire-format-is-frozen
  (is (= frozen-descriptor (compress/decompress frozen-fragment)) "links already shared keep loading")
  (is (= frozen-fragment (compress/compress frozen-descriptor)) "new links use the same encoding"))
