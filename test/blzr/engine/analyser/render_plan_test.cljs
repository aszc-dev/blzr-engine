(ns blzr.engine.analyser.render-plan-test
  "Render-plan pure transforms — Hz/dB → pixel and buffer enrichment.

   Existing structural assertions in analyser_test.cljs already cover
   default-layer shape and harmonic-marker Hz values; these tests
   focus on the new pixel-space transforms and the optional
   samples/mag-db enrichment paths."
  (:require
   [blzr.engine.analyser.dsp.trigger :as trig]
   [blzr.engine.analyser.render-plan :as rp]
   [cljs.test :refer [deftest is testing]]))

(def ^:private freq-scale-log    {:min-hz 20 :max-hz 20000 :log? true})
(def ^:private freq-scale-linear {:min-hz 0  :max-hz 1000  :log? false})
(def ^:private W 800)
(def ^:private H 300)

(deftest hz->x-log-scale-monotonic
  (testing "Log scale: lo edge → 0, hi edge → w, geometric mean → w/2"
    (is (= 0.0 (rp/hz->x 20 freq-scale-log W)))
    (is (< (js/Math.abs (- W (rp/hz->x 20000 freq-scale-log W))) 1e-6))
    (let [geo-mean (js/Math.sqrt (* 20 20000))]
      (is (< (js/Math.abs (- (/ W 2) (rp/hz->x geo-mean freq-scale-log W)))
             1e-6)))))

(deftest hz->x-linear-scale
  (testing "Linear scale: midpoint at half"
    (is (< (js/Math.abs (- (/ W 2) (rp/hz->x 500 freq-scale-linear W))) 1e-6))))

(deftest hz->x-handles-bad-input
  (testing "nil or non-positive Hz returns nil"
    (is (nil? (rp/hz->x nil freq-scale-log W)))
    (is (nil? (rp/hz->x 0 freq-scale-log W)))
    (is (nil? (rp/hz->x -10 freq-scale-log W)))))

(deftest db->y-clamps-and-inverts
  (testing "0 dB at top, floor at bottom, clamped outside"
    (is (= 0.0 (rp/db->y 0.0 -100.0 H)))
    (is (= H   (rp/db->y -100.0 -100.0 H)))
    (is (= H   (rp/db->y -200.0 -100.0 H)) "Below floor clamps to bottom")
    (is (= 0.0 (rp/db->y 50.0 -100.0 H))   "Above 0 clamps to top")
    (is (= (* H 0.5) (rp/db->y -50.0 -100.0 H)) "Midpoint at half height")))

(deftest db->y-nil-passthrough
  (is (nil? (rp/db->y nil -100.0 H))))

(deftest samples->points-downsamples-and-maps-amplitude
  (testing "1024 samples → ≤w points, amp 1 → top, -1 → bottom"
    (let [buf (js/Float32Array. 1024)
          _ (dotimes [i 1024] (aset buf i 1.0))
          pts (rp/samples->points buf 100 200)]
      (is (= 100 (count pts)))
      (is (every? (fn [[x y]] (and (>= x 0) (<= x 100) (= y 0.0))) pts)))))

(deftest samples->points-zero-amplitude-is-mid-height
  (let [buf (js/Float32Array. 100)
        pts (rp/samples->points buf 50 200)]
    (is (every? (fn [[_ y]] (= y 100.0)) pts))))

(deftest mag-db->bars-respects-freq-range
  (testing "Bins outside [min-hz, max-hz] are dropped"
    (let [mag (js/Float32Array. 32)
          _ (dotimes [k 32] (aset mag k -50.0))
          ;; sample-rate=44100, fft=64 → bin width = 689 Hz
          bars (rp/mag-db->bars mag 44100 64
                                {:min-hz 1000 :max-hz 5000 :log? true}
                                -100.0 W H)]
      (is (every? (fn [[x _ _ _]] (and (>= x 0) (<= x W))) bars))
      (is (pos? (count bars))))))

(deftest mag-db->bars-floor-clamped-bins-have-zero-height
  (testing "Bins at the dB floor are dropped (bh = 0, filtered)"
    (let [mag (js/Float32Array. 32)
          _ (dotimes [k 32] (aset mag k -100.0))
          bars (rp/mag-db->bars mag 44100 64
                                {:min-hz 20 :max-hz 20000 :log? true}
                                -100.0 W H)]
      (is (zero? (count bars))))))

(deftest build-emits-points-when-samples-provided
  (let [samples (js/Float32Array. 256)
        plan (rp/build {:config (rp/default-config)
                        :readout (rp/default-readout)
                        :samples samples})
        trace (first (filter #(= :trace (:kind %)) (:layers plan)))]
    (is (some? (:points trace)))
    (is (pos? (count (:points trace))))))

(deftest build-emits-bars-when-mag-db-provided
  (let [mag (js/Float32Array. 256)
        _ (dotimes [k 256] (aset mag k -30.0))
        plan (rp/build {:config (rp/default-config)
                        :readout (rp/default-readout)
                        :mag-db mag
                        :sample-rate 44100})
        spectrum (first (filter #(= :spectrum (:kind %)) (:layers plan)))]
    (is (some? (:bars spectrum)))
    (is (pos? (count (:bars spectrum))))))

(deftest build-harmonics-include-pixel-x
  (let [plan (rp/build {:config (assoc-in (rp/default-config)
                                          [:harmonics :enabled?] true)
                        :readout (rp/default-readout)})
        harm (first (filter #(= :harmonics (:kind %)) (:layers plan)))]
    (is (every? :px-x (:markers harm)))
    (is (every? #(<= 0 (:px-x %) W) (:markers harm)))))

(deftest build-harmonics-omitted-by-default
  (testing "Default :harmonics :enabled? is false → no :harmonics layer"
    (let [plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)})]
      (is (nil? (first (filter #(= :harmonics (:kind %)) (:layers plan))))))))

(deftest freq-axis-labels-engineering-emits-decade-ticks
  (testing "Engineering mode emits 20/50/.../20k labels within range"
    (let [labels (rp/freq-axis-labels {:min-hz 20 :max-hz 20000 :log? true}
                                      :engineering 800)]
      (is (some #(= "20" (:label %)) labels))
      (is (some #(= "100" (:label %)) labels))
      (is (some #(= "1k" (:label %)) labels))
      (is (some #(= "10k" (:label %)) labels))
      (is (every? #(<= 0 (:px-x %) 800) labels)))))

(deftest freq-axis-labels-musical-emits-c-octaves
  (testing "Musical mode emits C2..C7 labels"
    (let [labels (rp/freq-axis-labels {:min-hz 20 :max-hz 20000 :log? true}
                                      :musical 800)]
      (is (= ["C2" "C3" "C4" "C5" "C6" "C7"]
             (mapv :label labels))))))

(deftest freq-axis-labels-drops-out-of-range
  (testing "Ticks outside the freq-scale window are dropped"
    (let [labels (rp/freq-axis-labels {:min-hz 100 :max-hz 1000 :log? true}
                                      :engineering 800)]
      (is (not-any? #(= "20" (:label %)) labels))
      (is (not-any? #(= "10k" (:label %)) labels))
      (is (some #(= "500" (:label %)) labels)))))

(deftest build-emits-axis-labels-layer-when-grid-on
  (testing "Default grid-on? true produces :axis-labels layer with freq labels"
    (let [plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)})
          layer (first (filter #(= :axis-labels (:kind %)) (:layers plan)))]
      (is (some? layer))
      (is (seq (:freq-labels layer))))))

(deftest build-omits-axis-labels-when-grid-off
  (testing ":grid-on? false drops the :axis-labels layer too"
    (let [plan (rp/build {:config (assoc (rp/default-config) :grid-on? false)
                          :readout (rp/default-readout)})]
      (is (nil? (first (filter #(= :axis-labels (:kind %)) (:layers plan))))))))

(deftest build-harmonics-include-parity
  (let [plan (rp/build {:config (assoc (rp/default-config)
                                       :harmonics {:enabled? true
                                                   :f0 110.0
                                                   :count 8
                                                   :source :auto})
                        :readout (rp/default-readout)})
        harm (first (filter #(= :harmonics (:kind %)) (:layers plan)))
        parities (mapv :parity (:markers harm))
        ords     (mapv :ord (:markers harm))]
    (is (= [1 2 3 4 5 6 7 8] ords))
    (is (= [:odd :even :odd :even :odd :even :odd :even] parities))))

(deftest build-omits-cursors-layer
  (testing "Cursors are DOM-rendered (Wave 4); render-plan no longer emits :cursors"
    (let [plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)})]
      (is (nil? (first (filter #(= :cursors (:kind %)) (:layers plan))))))))

(deftest build-grid-layer-default-on
  (testing "Default config emits the grid layer"
    (let [plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)})]
      (is (some? (first (filter #(= :grid (:kind %)) (:layers plan))))))))

(deftest build-grid-layer-omitted-when-disabled
  (testing ":grid-on? false drops the grid layer entirely"
    (let [plan (rp/build {:config (assoc (rp/default-config) :grid-on? false)
                          :readout (rp/default-readout)})]
      (is (nil? (first (filter #(= :grid (:kind %)) (:layers plan))))))))

(deftest build-peak-spectrum-layer-emitted-when-buffer-provided
  (testing "Peak-hold buffer + sample-rate produces a :peak-spectrum layer with bars"
    (let [peak (js/Float32Array. 2048)
          plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)
                          :peak-mag-db peak
                          :sample-rate 48000})
          peak-layer (first (filter #(= :peak-spectrum (:kind %)) (:layers plan)))]
      (is (some? peak-layer))
      (is (seq (:bars peak-layer))))))

(deftest build-peak-spectrum-layer-omitted-without-buffer
  (testing "No peak-mag-db buffer means no :peak-spectrum layer"
    (let [plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)
                          :sample-rate 48000})]
      (is (nil? (first (filter #(= :peak-spectrum (:kind %)) (:layers plan))))))))

(deftest x->hz-roundtrip-log
  (testing "x->hz inverts hz->x on log scale"
    (let [fs {:min-hz 20 :max-hz 20000 :log? true}
          w 1000]
      (doseq [hz [50 440 1000 5000 19000]]
        (let [x (rp/hz->x hz fs w)
              hz' (rp/x->hz x fs w)]
          (is (< (js/Math.abs (- hz hz')) 1e-6) (str "hz=" hz)))))))

(deftest x->hz-roundtrip-linear
  (testing "x->hz inverts hz->x on linear scale"
    (let [fs {:min-hz 20 :max-hz 22050 :log? false}
          w 800]
      (doseq [hz [50 1000 11025 22050]]
        (let [x (rp/hz->x hz fs w)
              hz' (rp/x->hz x fs w)]
          (is (< (js/Math.abs (- hz hz')) 1e-6) (str "hz=" hz)))))))

(deftest build-without-samples-omits-points
  (let [plan (rp/build {:config (rp/default-config)
                        :readout (rp/default-readout)})
        trace (first (filter #(= :trace (:kind %)) (:layers plan)))]
    (is (not (contains? trace :points)))))

(deftest build-without-mag-db-omits-bars
  (let [plan (rp/build {:config (rp/default-config)
                        :readout (rp/default-readout)})
        spectrum (first (filter #(= :spectrum (:kind %)) (:layers plan)))]
    (is (not (contains? spectrum :bars)))))

(deftest build-readout-text-formats-fields
  (let [readout {:peak-hz 440.123 :peak-db -6.345 :note "A4" :cents 2}
        plan (rp/build {:config (rp/default-config) :readout readout})
        text (:text (first (filter #(= :readout (:kind %)) (:layers plan))))]
    (is (re-find #"440.12 Hz" text))
    (is (re-find #"A4" text))
    (is (re-find #"-6.3 dBFS" text))))

(deftest build-respects-custom-viewport
  (let [plan (rp/build {:config (rp/default-config)
                        :readout (rp/default-readout)
                        :viewport {:w 1600 :h 600 :dpr 2.0}})]
    (is (= {:w 1600 :h 600 :dpr 2.0} (:viewport plan)))))

;; ── Trigger helpers ───────────────────────────────────────────────────

(defn- sine-buf [freq sample-rate n]
  (let [out (js/Float32Array. n)
        w (/ (* 2.0 js/Math.PI freq) sample-rate)]
    (dotimes [i n] (aset out i (js/Math.sin (* w i))))
    out))

(deftest find-rising-zero-cross-on-sine
  (testing "Sine 440Hz @ 44100 → period ≈ 100 samples; window covers ≥1 crossing"
    (let [buf (sine-buf 440.0 44100 1024)
          zc (trig/find-rising-zero-cross buf 200)]
      (is (some? zc))
      (is (number? (:i zc)))
      (is (<= 0 (:frac zc)))
      (is (< (:frac zc) 1.0))
      (let [{:keys [i frac]} zc
            crossing-x (+ i frac)
            ;; Sine has rising zero crossings at integer-multiple periods (≈100.227 samples).
            period 100.227
            ratio (/ crossing-x period)
            err (js/Math.abs (- ratio (js/Math.round ratio)))]
        (is (< err 0.05) "Crossing should be within 5% of an integer period")))))

(deftest find-rising-zero-cross-noop-on-flat
  (testing "All-zero buffer has no rising crossing"
    (let [buf (js/Float32Array. 256)]
      (is (nil? (trig/find-rising-zero-cross buf 256))))))

(deftest find-rising-zero-cross-respects-window
  (testing "Crossing outside the search window is not returned"
    (let [buf (sine-buf 440.0 44100 1024)
          ;; Period ≈ 100; search window of 5 samples should usually miss.
          ;; Run multiple times to defeat lucky alignment — assert at least
          ;; one trial returns nil within a tiny window.
          trials (for [start [0 25 50 75]]
                   (let [sub (js/Float32Array. 5)]
                     (dotimes [i 5] (aset sub i (aget buf (+ start i))))
                     (trig/find-rising-zero-cross sub 5)))]
      (is (some nil? trials)))))

(deftest smooth-period-cold-start-returns-raw
  (is (= 100.0 (trig/smooth-period nil 100.0 0.16))))

(deftest smooth-period-nil-raw-holds-prev
  (is (= 50.0 (trig/smooth-period 50.0 nil 0.16))))

(deftest smooth-period-step-response-approaches-new
  (testing "After many ticks at constant raw, smoothed converges within 1%"
    (let [alpha 0.16
          target 200.0]
      (loop [smoothed 100.0 n 0]
        (if (or (>= n 200) (< (js/Math.abs (- smoothed target)) 2.0))
          (do (is (< (js/Math.abs (- smoothed target)) 2.0))
              (is (< n 100) "Should converge within 100 ticks at α=0.16"))
          (recur (trig/smooth-period smoothed target alpha) (inc n)))))))

(deftest update-lock-enters-on-high-clarity
  (is (true? (trig/update-lock false 0.5))))

(deftest update-lock-exits-on-low-clarity
  (is (false? (trig/update-lock true 0.2))))

(deftest update-lock-holds-in-dead-band
  (testing "Clarity in [0.3, 0.4] holds prior state — Schmitt hysteresis"
    (is (true?  (trig/update-lock true  0.35)))
    (is (false? (trig/update-lock false 0.35)))))

(deftest update-lock-nil-clarity-unlocks
  (is (false? (trig/update-lock true nil))))

;; ── aligned-trace-points ──────────────────────────────────────────────

(deftest aligned-trace-points-locked-sine-shifts-x
  (testing "Locked sine → first x ≤ 0 (sub-sample frac shifted left)"
    (let [buf (sine-buf 440.0 44100 1024)
          period (/ 44100.0 440.0)
          pts (rp/aligned-trace-points buf period 800 200)]
      (is (some? pts))
      (is (pos? (count pts)))
      (let [[x0 _] (first pts)]
        (is (<= x0 0.0) "Sub-sample shift baked into x means first point sits at or left of 0")))))

(deftest aligned-trace-points-nil-on-flat
  (let [buf (js/Float32Array. 1024)]
    (is (nil? (rp/aligned-trace-points buf 100.0 800 200)))))

(deftest aligned-trace-points-nil-without-period
  (let [buf (sine-buf 440.0 44100 1024)]
    (is (nil? (rp/aligned-trace-points buf nil 800 200)))
    (is (nil? (rp/aligned-trace-points buf 0 800 200)))))

(deftest aligned-trace-points-display-window-covers-multiple-periods
  (testing "Display window must contain ≥ 2 full periods of samples;
            previously the search-end bug collapsed it to a fraction
            of a period stretched across the canvas."
    (let [buf (sine-buf 440.0 44100 4096)
          period (/ 44100.0 440.0)        ; ≈ 100.23 samples
          pts (rp/aligned-trace-points buf period 800 200)
          n (count pts)]
      (is (some? pts))
      (is (>= n (* 2 (int period)))
          (str "Got " n " points; expected ≥ " (* 2 (int period))
               " (2 full periods at this period length)")))))

(deftest aligned-trace-points-nil-when-buffer-too-short
  (testing "Buffer with fewer than 2 full periods of samples cannot align
            (need one period to display + one period of search room)"
    (let [period 150.0
          ;; 200 samples < 2 × 150 = 300; available = 50 < period.
          buf (sine-buf 294.0 44100 200)]
      (is (nil? (rp/aligned-trace-points buf period 800 200))))))

(deftest aligned-trace-points-adapts-display-at-low-freq
  (testing "Low-frequency input: 2.5 periods don't fit, so display
            adapts to fewer periods rather than failing alignment.
            Regression: pre-adaptive logic left search-window narrower
            than one period and missed crossings phase-dependently
            → intermittent raw-fallback that looked like a slow slide.
            Pre-fix: at sr=48000 fft=4096 the boundary was ≈41 Hz; this
            test exercises 40 Hz with a non-zero starting phase so the
            crossing search isn't pinned to a float-precision edge."
    (let [;; 40Hz @ 48000 with 4096-sample buffer: period 1200.
          ;; Use π/3 phase offset so crossings land mid-buffer, not at
          ;; sample boundaries where sin(2πk) ≈ ±1e-16.
          n 4096
          sr 48000.0
          freq 40.0
          phase (/ js/Math.PI 3.0)
          buf (let [out (js/Float32Array. n)
                    w (/ (* 2.0 js/Math.PI freq) sr)]
                (dotimes [i n] (aset out i (js/Math.sin (+ phase (* w i)))))
                out)
          period (/ sr freq)               ; 1200
          pts (rp/aligned-trace-points buf period 800 200)]
      (is (some? pts) "Aligned trace must produce points at low freq")
      (is (pos? (count pts)) "Trace must have at least one point"))))

(deftest build-locked-trigger-marks-trace
  (testing "When trigger locks and alignment succeeds, trace :locked? = true"
    (let [buf (sine-buf 440.0 44100 4096)
          period (/ 44100.0 440.0)
          plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)
                          :samples buf
                          :trigger {:locked? true :period-samples period}})
          trace (first (filter #(= :trace (:kind %)) (:layers plan)))]
      (is (true? (:locked? trace)))
      (is (pos? (count (:points trace)))))))

(deftest build-free-run-falls-back-to-raw-points
  (testing "When trigger.locked? is false, build emits raw samples->points"
    (let [buf (sine-buf 440.0 44100 4096)
          plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)
                          :samples buf
                          :trigger {:locked? false :period-samples 100.0}})
          trace (first (filter #(= :trace (:kind %)) (:layers plan)))]
      (is (false? (:locked? trace)))
      (is (pos? (count (:points trace)))))))

(deftest build-locked-without-zero-cross-falls-back
  (testing "Locked + flat buffer → no crossing → raw fallback, :locked? false"
    (let [buf (js/Float32Array. 1024)
          plan (rp/build {:config (rp/default-config)
                          :readout (rp/default-readout)
                          :samples buf
                          :trigger {:locked? true :period-samples 100.0}})
          trace (first (filter #(= :trace (:kind %)) (:layers plan)))]
      (is (false? (:locked? trace)))
      (is (pos? (count (:points trace)))))))

(deftest interval-name-recognizes-just-ratios
  (testing "Exact just-intonation ratios"
    (is (= "unison" (rp/interval-name 1.0 10)))
    (is (= "5th"    (rp/interval-name 1.5 10)))
    (is (= "octave" (rp/interval-name 2.0 10)))
    (is (= "M3"     (rp/interval-name (/ 5 4) 10)))
    (is (= "4th"    (rp/interval-name (/ 4 3) 10)))
    (is (= "M7"     (rp/interval-name (/ 15 8) 10)))))

(deftest interval-name-inverts-ratios-below-one
  (testing "f_b < f_a → ratio < 1, still resolves to absolute interval"
    (is (= "5th"    (rp/interval-name (/ 2.0 3.0) 10)))
    (is (= "octave" (rp/interval-name 0.5 10)))))

(deftest interval-name-honours-tolerance
  (testing "Within tolerance → label"
    ;; 1.505 → ~707.5 cents, P5 = 702 → ~5.6 c off → matches
    (is (= "5th" (rp/interval-name 1.505 10))))
  (testing "Out of tolerance → nil"
    ;; 1.7 → 918.6 cents, closest M6=884.3 (diff 34c) → no match
    (is (nil? (rp/interval-name 1.7 10)))))

(deftest interval-name-rejects-non-positive
  (is (nil? (rp/interval-name 0 10)))
  (is (nil? (rp/interval-name -1 10)))
  (is (nil? (rp/interval-name nil 10))))
