# blzr-engine

The Web Audio engine behind [blzr](https://blzr.app), a visual modular
synthesizer, as a ClojureScript library. It mounts a graph described as plain
data on any `AudioContext` or `OfflineAudioContext`, with no UI or state
framework attached.

It contains:

- `blzr.engine.runtime`: creates, connects, updates and tears down nodes;
- `blzr.engine.specs`: the built-in node types (native Web Audio nodes,
  AudioWorklet operators, MIDI-to-CV nodes);
- `blzr.engine.patch.*`: the patch descriptor, its Transit encoding and the
  compressed form used in share links;
- `blzr.engine.instrument.*`: a validated instrument shell (graph, controls,
  taps) built on malli;
- `blzr.engine.analyser.*`: FFT, windowing, trigger and render-plan code for
  scopes and spectrum views;
- the AudioWorklet modules, under `resources/blzr/engine/worklets/`.

## Usage

Add it as a git dependency (shadow-cljs needs `:deps true`):

```clojure
io.github.aszc-dev/blzr-engine {:git/sha "<commit>"}
```

Serve the files in `resources/blzr/engine/worklets/` from your app; the
runtime loads them from `/worklets/` unless you pass another `:worklet-base`.

```clojure
(require '[blzr.engine.runtime :as rt])

(def flow
  {:nodes {"osc" {:type "osc" :data {:frequency 220}}
           "out" {:type "out" :data {}}}
   :edges [{:source "osc" :sourceHandle "audio-out"
            :target "out" :targetHandle "audio-in"}]})

(-> (rt/register-worklets! (rt/create-runtime (js/AudioContext.)))
    (.then (fn [runtime]
             (rt/mount! runtime flow)
             ;; Browsers start audio only after a user gesture.
             (rt/resume! runtime))))
```

### Custom node types and worklets

Each runtime has its own node-type registry, so you can add types without
touching the library. A worklet-backed type needs its processor loaded first;
a Blob URL works, which allows loading processors at run time:

```clojure
(def specs
  (assoc blzr.engine.specs/node-specs
         :gain-x2 {:worklet-name "gain-x2"
                   :inputs [:audio-in]
                   :outputs [:audio-out]
                   :processor-options [:mode]
                   :params {:mode {:type :enum :options ["soft" "hard"] :default "soft"}}}))

(-> (rt/add-worklet-module! (rt/create-runtime ctx {:node-specs specs}) url)
    (.then #(rt/mount! % flow)))
```

Keys listed in `:processor-options` reach the processor as `processorOptions`
when the node is created, and as port messages when they change through
`rt/set-param!`. A processor name can be registered once per context, so a
reloaded processor needs a new name.

## Development

Requires Java 21, the Clojure CLI, Node 22, Chrome and
[babashka](https://babashka.org).

```bash
npm ci
bb test:jvm       # .cljc tests on the JVM (kaocha)
bb test:browser   # ClojureScript tests in headless Chrome (Karma)
bb test           # both
```

To run both lanes without network access:

```bash
docker build -t blzr-engine-test .
docker run --rm --network none blzr-engine-test
```

## License

Copyright 2026 Adrian Szczepański (aszc-dev). Licensed under the
[Apache License 2.0](LICENSE); see [NOTICE](NOTICE).
