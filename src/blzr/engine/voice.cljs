(ns blzr.engine.voice
  "Pure MIDI voice allocation — data in, data out. No atoms, no audio, no
   re-frame. The caller owns the voice state and calls `allocate`/`release`
   for the decision (retrigger-same-note, free-slot-first,
   oldest-timestamp-steal fallback).

   `mount-voices` is the poly-strategy seam (:voicing/strategy from
   blzr.engine.instrument.schema): :worklet instruments (e.g. a granular grain pool) own
   polyphony internally, so allocation here is a no-op for them.
   :graph-clone needs the incremental reconciler that doesn't exist yet —
   declaring it now (vs. silently ignoring it) means an instrument that asks
   for graph-clone poly gets a clear, catchable error instead of quietly
   behaving like a single voice."
  )

;; A voice slot is identified by an opaque id (blzr uses node ids today).
;; State: {voice-id -> {:note int :active? bool :ts number}}

(defn- free-slot [state voice-ids]
  (first (remove #(get-in state [% :active?]) voice-ids)))

(defn- steal-policy [strategy state voice-ids]
  (let [active (filter #(get-in state [% :active?]) voice-ids)]
    (case strategy
      :oldest      (first (sort-by #(get-in state [% :ts] 0) voice-ids))
      :round-robin (first (sort-by #(get-in state [% :ts] 0) voice-ids))
      :lowest      (if (seq active)
                     (apply min-key #(get-in state [% :note] 999) active)
                     (first voice-ids))
      :highest     (if (seq active)
                     (apply max-key #(get-in state [% :note] -1) active)
                     (first voice-ids))
      (first voice-ids))))

(defn allocate
  "Pure. Given voicing config, current state, ordered voice-ids, and an
   incoming note+ts, return [chosen-voice-id new-state]. Retriggers the
   same-note voice if already sounding (avoids double-trigger); otherwise
   takes a free slot; otherwise steals per policy. Mono mode ignores
   voice-ids beyond the first."
  [{:voicing/keys [mode steal] :or {steal :oldest}} state voice-ids note ts]
  (let [voice-ids (if (= mode :mono) (take 1 voice-ids) voice-ids)
        existing (first (filter #(and (get-in state [% :active?])
                                      (= note (get-in state [% :note]))) voice-ids))
        chosen (or existing (free-slot state voice-ids) (steal-policy steal state voice-ids))]
    [chosen (assoc state chosen {:note note :active? true :ts ts})]))

(defn release
  "Pure. Marks all voices playing `note` inactive. Returns [released-ids new-state]."
  [state note]
  (let [ids (for [[id v] state :when (and (:active? v) (= note (:note v)))] id)]
    [ids (reduce (fn [s id] (assoc-in s [id :active?] false)) state ids)]))

;; ---- mount-voices: the poly seam --------------------------------------

(defmulti mount-voices
  "runtime + voicing + graph -> a voice plan the binder uses to route note
   events. Dispatch on :voicing/strategy."
  {:arglists '([rt voicing graph])}
  (fn [_rt voicing _graph] (:voicing/strategy voicing)))

(defmethod mount-voices :worklet [_rt _voicing _graph]
  {:strategy :worklet :voices :internal})

(defmethod mount-voices :graph-clone [_rt voicing _graph]
  (throw (ex-info "graph-clone polyphony needs the incremental reconciler (not implemented yet)"
                  {:voicing voicing})))

(defmethod mount-voices :default [_rt _voicing _graph]
  {:strategy :none :voices :single})
