(ns blzr.engine.granular
  "Main-thread control surface for the granular worklet node. The runtime
   creates the node generically from the :granular spec; this ns covers the
   rest: loading a sample, pushing the grain envelope table
   (blzr.engine.dsp.envelope) and reading grain positions back for
   visualization."
  (:require [blzr.engine.dsp.envelope :as env]))

(defn load-sample!
  "Sends a decoded mono sample to the worklet. Transfers the buffer — do not
   reuse `mono` after calling this."
  [node ^js/Float32Array mono frames]
  (.postMessage (.-port node) #js {:type "sample" :data mono :frames frames}
                #js [(.-buffer mono)]))

(defn push-envelope!
  "Recomputes the Tukey grain envelope (blzr.engine.dsp.envelope) and pushes
   it to the worklet."
  [node {:keys [sides tilt curve]}]
  (let [table (env/tukey-envelope {:sides sides :tilt tilt :curve curve :n 1024})]
    (.postMessage (.-port node) #js {:type "envelope" :table table})))

(defn on-grains
  "Registers `cb` to receive grain-position Float32Arrays. Fans out to any
   previously-registered handler so a tap (blzr.engine.tap) and this
   caller can both listen without stomping each other."
  [node cb]
  (let [prev (.. node -port -onmessage)]
    (set! (.. node -port -onmessage)
          (fn [^js e]
            (when (= "grains" (.. e -data -type))
              (cb (.. e -data -positions)))
            (when prev (prev e))))))

(defn set-param!
  "k in #{:density :scan :grainMs :scanSpray :panSpray}."
  [node k v]
  (when-let [p (.get (.-parameters node) (name k))]
    (.setTargetAtTime p v (.-currentTime (.-context node)) 0.01)))
