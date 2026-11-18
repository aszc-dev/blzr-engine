(ns blzr.engine.patch.descriptor
  "Canonical Patch Descriptor: single source of truth for patch serialization.
   Converts between a {:flow :module-ui} graph and a portable descriptor format."
  (:require [blzr.engine.hash :as hash]))

(defn from-graph
  "Extract the canonical patch descriptor from a {:flow :module-ui} graph."
  [db]
  (let [flow (:flow db)
        nodes (:nodes flow)
        edges (:edges flow)
        module-ui (:module-ui db)]
    {:version 1
     :structure {:nodes (into {}
                              (map (fn [[id node]]
                                     [id {:type (keyword (:type node))
                                          :parent-module (:parent-module node)}]))
                              nodes)
                 :edges (mapv #(select-keys % [:source :target :sourceHandle :targetHandle])
                              edges)}
     :params (into {}
                   (keep (fn [[id node]]
                           (when (seq (:data node))
                             [id (:data node)])))
                   nodes)
     :positions (into {}
                      (map (fn [[id node]]
                             [id (:position node)]))
                      nodes)
     :sizes (into {}
                  (keep (fn [[id node]]
                          (when (or (:width node) (:height node))
                            [id (select-keys node [:width :height])])))
                  nodes)
     :module-ui module-ui
     :metadata {:source :user
                :created-at (.toISOString (js/Date.))}}))

(defn to-graph
  "Convert descriptor back to {:flow ... :module-ui ...} for loading."
  [descriptor]
  (let [{:keys [structure params positions sizes module-ui]} descriptor
        nodes (into {}
                    (map (fn [[id {:keys [type parent-module]}]]
                           (let [size (get sizes id)]
                             [id (cond-> {:id id
                                          :type (name type)
                                          :data (get params id {})
                                          :position (or (get positions id) {:x 0 :y 0})}
                                   parent-module (assoc :parent-module parent-module)
                                   (:width size)  (assoc :width  (:width size))
                                   (:height size) (assoc :height (:height size)))])))
                    (:nodes structure))
        edges (mapv (fn [edge]
                      (assoc edge :id (str (random-uuid))))
                    (:edges structure))]
    {:flow {:nodes nodes :edges edges}
     :module-ui (or module-ui {:by-id {}})}))

(defn structure-hash
  "Hash only the topology -- ignores params, positions, UI."
  [descriptor]
  (hash/hash-topology
    (hash/canonical-topology
      {:nodes (into {}
                    (map (fn [[id {:keys [type]}]]
                           [id {:type type}]))
                    (get-in descriptor [:structure :nodes]))
       :edges (get-in descriptor [:structure :edges])})))

(defn params-hash
  "Deterministic hash of parameter values, sorted canonically."
  [descriptor]
  (let [sorted-params (->> (:params descriptor)
                           (sort-by key)
                           (mapv (fn [[id params]]
                                   [id (into (sorted-map) params)])))]
    (hash/hash-topology (pr-str sorted-params))))

(defn full-hash
  "Combined hash: structure + params. Unique per sound."
  [descriptor]
  (hash/hash-topology
    (str (structure-hash descriptor) ":" (params-hash descriptor))))
