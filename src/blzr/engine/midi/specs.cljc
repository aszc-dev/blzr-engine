(ns blzr.engine.midi.specs)

(def cc-names
  {0 "Bank Select" 1 "Mod Wheel" 2 "Breath" 4 "Foot Pedal"
   5 "Portamento Time" 6 "Data Entry" 7 "Volume" 8 "Balance"
   10 "Pan" 11 "Expression" 12 "Effect 1" 13 "Effect 2"
   64 "Sustain" 65 "Portamento" 66 "Sostenuto" 67 "Soft Pedal"
   71 "Resonance" 72 "Release" 73 "Attack" 74 "Cutoff"
   84 "Portamento Amt" 91 "Reverb" 92 "Tremolo" 93 "Chorus"
   94 "Detune" 95 "Phaser" 120 "All Sound Off" 123 "All Notes Off"})

(defn cc-label [cc-number]
  (get cc-names cc-number (str "CC " cc-number)))

(def midi-node-specs
  {:midi    {:name "MIDI"
             :category :midi-cv
             :inputs []
             :outputs [:pitch :gate :velocity :pitch-bend]
             :cv-outputs [:pitch :gate :velocity :pitch-bend]
             :handle-inference {"gain"      :gate
                                "velocity"  :velocity
                                "frequency" :pitch
                                "detune"    :pitch}
             :params {:device  {:type :enum :default "all" :label "Device"
                                :options ["all"]}
                      :channel {:type :enum :default "omni" :label "Channel"
                                :options ["omni" "1" "2" "3" "4" "5" "6" "7" "8"
                                          "9" "10" "11" "12" "13" "14" "15" "16"]}
                      :glide   {:type :number :default 0 :min 0 :max 500 :step 1 :label "Glide ms"}}}

   :midi-cc {:name "MIDI CC"
             :category :midi-cv
             :inputs []
             :outputs [:value]
             :cv-outputs [:value]
             :handle-inference {}
             :params {:device  {:type :enum :default "all" :label "Device"
                                :options ["all"]}
                      :channel {:type :enum :default "omni" :label "Channel"
                                :options ["omni" "1" "2" "3" "4" "5" "6" "7" "8"
                                          "9" "10" "11" "12" "13" "14" "15" "16"]}
                      :cc      {:type :number :default 1 :min 0 :max 127 :step 1 :label "CC#"}}}})
