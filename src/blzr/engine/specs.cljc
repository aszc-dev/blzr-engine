(ns blzr.engine.specs
  (:require [blzr.engine.midi.specs :as midi]))

(def web-audio-node-specs
  {:osc {:name "Oscillator"
         :native-type "Oscillator"
         :inputs  []
         :outputs [:audio-out]
         :params  {:frequency {:type :audio-param :default 440 :min 0 :max 22050 :label "Freq" :scale :log}
                   :detune    {:type :audio-param :default 0   :min -4800 :max 4800 :label "Detune"}
                   :type      {:type :enum :options ["sine" "square" "sawtooth" "triangle" "glottal"] :default "sine" :label "Wave"}}}

   :amp {:name "Gain / VCA"
         :native-type "Gain"
         :inputs  [:audio-in]
         :outputs [:audio-out]
         :params  {:gain {:type :audio-param :default 1.0 :min 0 :max 10 :label "Level"}}}

   :flt {:name "Filter"
         :native-type "BiquadFilter"
         :inputs  [:audio-in]
         :outputs [:audio-out]
         :params  {:frequency {:type :audio-param :default 350 :min 0 :max 22050 :label "Cutoff" :scale :log}
                   :Q         {:type :audio-param :default 1   :min 0.0001 :max 1000   :label "Resonance" :scale :log}
                   :detune    {:type :audio-param :default 0   :min -4800 :max 4800 :label "Detune"}
                   :gain      {:type :audio-param :default 0   :min -40 :max 40   :label "Gain (dB)"}
                   :type      {:type :enum
                               :options ["lowpass" "highpass" "bandpass" "lowshelf" "highshelf" "peaking" "notch" "allpass"]
                               :default "lowpass"
                               :label "Type"}}}

   :dly {:name "Delay"
         :native-type "Delay"
         :create-args [5.0]
         :inputs  [:audio-in]
         :outputs [:audio-out]
         :params  {:delayTime {:type :audio-param :default 0.3 :min 0 :max 5.0 :label "Time (s)"}}}

   :comp {:name "Compressor"
          :native-type "DynamicsCompressor"
          :inputs [:audio-in]
          :outputs [:audio-out]
          :params {:threshold {:type :audio-param :default -24 :min -100 :max 0 :label "Thresh"}
                   :knee      {:type :audio-param :default 30  :min 0 :max 40   :label "Knee"}
                   :ratio     {:type :audio-param :default 12  :min 1 :max 20   :label "Ratio"}
                   :attack    {:type :audio-param :default 0.003 :min 0 :max 1  :label "Attack"}
                   :release   {:type :audio-param :default 0.25 :min 0 :max 1   :label "Release"}}}

   :panner {:name "Stereo Panner"
            :native-type "StereoPanner"
            :inputs [:audio-in]
            :outputs [:audio-out]
            :params {:pan {:type :audio-param :default 0 :min -1 :max 1 :label "Pan"}}}

   :scope {:name "Oscilloscope"
           :native-type "Analyser"
           :inputs [:audio-in]
           :outputs [:audio-out]
           :params {}}

   :mul {:name "Multiply (*)"
         :native-type nil
         :worklet-name "mul-processor"
         :inputs [:a]
         :outputs [:result]
         :params {:b {:type :audio-param :default 1 :label "Mod B"}}}

   :add {:name "Add (+)"
         :native-type nil
         :worklet-name "add-processor"
         :inputs [:a]
         :outputs [:result]
         :params {:b {:type :audio-param :default 0 :label "Offset B"}}}

   :env {:name "Envelope Follower"
         :native-type nil
         :worklet-name "env-follow-processor"
         :inputs [:audio-in]
         :outputs [:audio-out]
         :params {:attack  {:type :audio-param :default 0.005 :min 0.0001 :max 1 :step 0.0001 :label "Attack" :scale :log}
                  :release {:type :audio-param :default 0.050 :min 0.0001 :max 5 :step 0.0001 :label "Release" :scale :log}}}

   :granular {:name "Granular"
              :native-type nil
              :worklet-name "granular-processor"
              :worklet-options {:numberOfInputs 0 :outputChannelCount [2]}
              :inputs []
              :outputs [:audio-out]
              :params {:density   {:type :audio-param :default 20 :min 0 :max 200 :label "Density" :scale :log}
                       :scan      {:type :audio-param :default 0  :min 0 :max 1   :label "Scan"}
                       :grainMs   {:type :audio-param :default 80 :min 5 :max 500 :label "Grain (ms)"}
                       :scanSpray {:type :audio-param :default 0  :min 0 :max 2   :label "Scan Spray"}
                       :panSpray  {:type :audio-param :default 0  :min 0 :max 1   :label "Pan Spray"}}}

   :const {:name "Constant"
           :native-type "ConstantSource"
           :inputs []
           :outputs [:audio-out]
           :params {:offset {:type :audio-param :default 1 :min -1000 :max 1000 :label "Value"}}}

   :dist {:name "Distortion"
          :native-type "WaveShaper"
          :inputs [:audio-in]
          :outputs [:audio-out]
          :params {:curve     {:type :enum
                               :options ["soft" "hard" "fuzz" "clean"]
                               :default "soft"
                               :label "Curve"}
                   :oversample {:type :enum
                                :options ["none" "2x" "4x"]
                                :default "none"
                                :label "Oversample"}}}

   :reverb {:name "Reverb"
            :native-type "Convolver"
            :inputs [:audio-in]
            :outputs [:audio-out]
            :params {:normalize {:type :bool :default true :label "Normalize"}
                     :ir        {:type :enum
                                 :options ["hall" "room" "plate" "spring"]
                                 :default "room"
                                 :label "Impulse Response"}}}

   :sampler {:name "Sampler"
             :native-type nil
             :inputs []
             :outputs [:audio-out]
             :params {:playbackRate {:type :audio-param :default 1 :min 0.01 :max 10 :step 0.01 :label "Speed"}
                      :detune       {:type :audio-param :default 0 :min -4800 :max 4800 :step 1 :label "Detune"}
                      :loop         {:type :bool :default false :label "Loop"}}}

   :mic {:name "Microphone"
         :native-type nil
         :inputs []
         :outputs [:audio-out]
         :params {}}

   :noise {:name "Noise"
            :native-type nil
            :worklet-name "noise-processor"
            :processor-options [:type]
            :inputs []
            :outputs [:audio-out]
            :params {:type {:type :enum
                            :options ["white" "pink" "brown"]
                            :default "white"
                            :label "Color"}}}

   :split {:name "Ch. Splitter"
           :native-type "ChannelSplitter"
           :create-args [2]
           :inputs [:audio-in]
           :outputs [:left :right]
           :output-indices {:left 0 :right 1}
           :params {}}

   :merge {:name "Ch. Merger"
           :native-type "ChannelMerger"
           :create-args [2]
           :inputs [:left :right]
           :input-indices {:left 0 :right 1}
           :outputs [:audio-out]
           :params {}}

   })

(def node-specs
  (merge web-audio-node-specs midi/midi-node-specs))
