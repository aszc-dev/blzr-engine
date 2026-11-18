(ns blzr.engine.hash
  (:require [clojure.string :as str]))

(defn audio-flow
  [{:keys [nodes edges]}]
  (let [audio-nodes (into {}
                          (filter (fn [[_ node]]
                                    (not= "module" (:type node))))
                          nodes)
        audio-node-ids (set (keys audio-nodes))
        audio-edges (filterv (fn [edge]
                               (and (contains? audio-node-ids (:source edge))
                                    (contains? audio-node-ids (:target edge))))
                             edges)]
    {:nodes audio-nodes
     :edges audio-edges}))

(defn- node-type-str [node]
  (name (or (:type node) "unknown")))

(defn- find-components [node-ids edges]
  (let [parent (atom (into {} (map (fn [id] [id id]) node-ids)))]
    (letfn [(find-root [x]
              (loop [curr x]
                (let [p (get @parent curr)]
                  (if (= p curr)
                    curr
                    (do (swap! parent assoc curr (get @parent p))
                        (recur p))))))
            (union! [a b]
              (let [ra (find-root a)
                    rb (find-root b)]
                (when (not= ra rb)
                  (swap! parent assoc ra rb))))]
      (doseq [e edges]
        (union! (:source e) (:target e)))
      (let [groups (group-by find-root node-ids)]
        (vals groups)))))

(defn- canonical-edge [id->type edge]
  (let [src-type (id->type (:source edge))
        src-handle (or (:sourceHandle edge) "audio-out")
        tgt-type (id->type (:target edge))
        tgt-handle (or (:targetHandle edge) "audio-in")]
    (str src-type ":" src-handle ">" tgt-type ":" tgt-handle)))

(defn- canonical-topology-for-nodes [all-nodes all-edges node-id-set]
  (let [nodes-in-component (filterv (fn [[id _]] (node-id-set id)) all-nodes)
        edges-in-component (filterv (fn [e] (and (node-id-set (:source e))
                                                  (node-id-set (:target e))))
                                     all-edges)
        id->type (into {} (map (fn [[id n]] [id (node-type-str n)]) nodes-in-component))
        type-list (->> (vals id->type)
                       sort
                       (str/join ","))
        edge-list (->> edges-in-component
                       (mapv #(canonical-edge id->type %))
                       sort
                       (str/join ","))]
    (str type-list "||" edge-list)))

(defn canonical-topology
  "Returns the canonical topology string for a flow graph.
   Topology = sorted node types + sorted canonical edges.
   Deterministic: same topology always produces same string,
   regardless of node IDs, positions, or parameter values."
  [{:keys [nodes edges]}]
  (let [{:keys [nodes edges]} (audio-flow {:nodes nodes :edges edges})]
    (canonical-topology-for-nodes nodes edges (set (keys nodes)))))

(defn component-topologies
  "Returns a sorted vector of canonical topology strings, one per connected component."
  [{:keys [nodes edges]}]
  (let [{:keys [nodes edges]} (audio-flow {:nodes nodes :edges edges})
        node-ids (keys nodes)
        components (find-components node-ids edges)]
    (->> components
         (mapv (fn [component-ids]
                 (canonical-topology-for-nodes nodes edges (set component-ids))))
         sort
         vec)))

(defn- char-code
  "Platform-neutral char code: reducing over a string yields Character on
   the JVM and single-char strings in JS."
  [ch]
  #?(:clj  (int ch)
     :cljs (.charCodeAt ch 0)))

(defn- djb2-hash [s]
  (reduce (fn [hash ch]
            (bit-and (+ (bit-shift-left hash 5) hash (char-code ch))
                     0x7FFFFFFF))
          5381
          s))

(defn- abs-int [n]
  #?(:clj  (Math/abs (long n))
     :cljs (js/Math.abs n)))

(defn- to-hex [n]
  #?(:clj  (Long/toString (long n) 16)
     :cljs (.toString n 16)))

(defn- hex16 [n]
  (let [h (to-hex (abs-int n))]
    (subs (str "0000000000000000" h) (- (count (str "0000000000000000" h)) 16))))

(defn hash-topology
  "Synchronous hash of a canonical topology string.
   Uses djb2 for speed — this is for grouping, not security."
  [topology-str]
  (hex16 (djb2-hash topology-str)))

(defn fingerprint
  "Full fingerprint of a flow graph. Returns a map with hash, topology,
   component hashes/topologies, and summary stats."
  [{:keys [nodes edges]}]
  (let [{:keys [nodes edges]} (audio-flow {:nodes nodes :edges edges})
        flow {:nodes nodes :edges edges}
        topo (canonical-topology flow)
        comp-topos (component-topologies flow)
        node-type-list (->> (vals nodes) (map node-type-str) sort vec)]
    {:graph-hash (hash-topology topo)
     :graph-topology topo
     :component-hashes (mapv hash-topology comp-topos)
     :component-topologies comp-topos
     :node-count (count nodes)
     :edge-count (count edges)
     :node-types node-type-list}))
