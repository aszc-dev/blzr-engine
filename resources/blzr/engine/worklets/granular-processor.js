// Granular synth processor: a fixed-size pool of grains reads a loaded mono
// sample, each grain windowed by an envelope table computed on the main
// thread (blzr.engine.dsp.envelope) and pushed in via postMessage — this processor
// never calls pow/sqrt in the audio-rate loop. Continuous controls
// (density/scan/grainMs/scanSpray/panSpray) are AudioParams so the blzr graph
// can modulate them (LFO/env/MIDI-CV); per-grain randomization (scan spray,
// pan spray) stays inside process(). Mono, nearest-neighbor sample read — no
// per-grain pitch/rate.

const POOL_SIZE = 64;
const ENV_TABLE_SIZE = 1024;
const GRAIN_MESSAGE_INTERVAL = 5; // render quanta between "grains" position posts

class GranularProcessor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      { name: "density", defaultValue: 20, minValue: 0, maxValue: 200, automationRate: "k-rate" },
      { name: "scan", defaultValue: 0, minValue: 0, maxValue: 1, automationRate: "a-rate" },
      { name: "grainMs", defaultValue: 80, minValue: 5, maxValue: 500, automationRate: "k-rate" },
      { name: "scanSpray", defaultValue: 0, minValue: 0, maxValue: 2, automationRate: "k-rate" },
      { name: "panSpray", defaultValue: 0, minValue: 0, maxValue: 1, automationRate: "k-rate" },
    ];
  }

  constructor() {
    super();

    this._sample = null; // Float32Array, mono
    this._frames = 0;
    this._envTable = new Float32Array(ENV_TABLE_SIZE).fill(1.0);

    // Grain pool, struct-of-arrays — preallocated, no per-grain objects.
    this._active = new Uint8Array(POOL_SIZE);
    this._srcPos = new Float32Array(POOL_SIZE);
    this._elapsed = new Uint32Array(POOL_SIZE);
    this._total = new Uint32Array(POOL_SIZE);
    this._envStep = new Float32Array(POOL_SIZE);
    this._panL = new Float32Array(POOL_SIZE);
    this._panR = new Float32Array(POOL_SIZE);
    this._nextGrain = 0;

    this._spawnAcc = 0;
    this._quantumCount = 0;
    this._positionsMsg = new Float32Array(POOL_SIZE);

    this.port.onmessage = (e) => {
      const msg = e.data;
      if (msg.type === "sample") {
        this._sample = msg.data;
        this._frames = msg.frames;
      } else if (msg.type === "envelope") {
        this._envTable = msg.table;
      }
    };
  }

  _spawnGrain(scan, scanSpray, panSpray, grainMs) {
    if (!this._sample || this._frames === 0) return;

    const idx = this._nextGrain;
    this._nextGrain = (this._nextGrain + 1) % POOL_SIZE;

    const spray = scanSpray > 0 ? (Math.random() * 2 - 1) * scanSpray : 0;
    let startFrac = scan + spray;
    startFrac = startFrac - Math.floor(startFrac); // wrap into [0,1)
    this._srcPos[idx] = startFrac * this._frames;

    const totalSamples = Math.max(1, Math.round((grainMs / 1000) * sampleRate));
    this._total[idx] = totalSamples;
    this._elapsed[idx] = 0;
    this._envStep[idx] = ENV_TABLE_SIZE / totalSamples;

    const pan = panSpray > 0 ? (Math.random() * 2 - 1) * panSpray : 0;
    this._panL[idx] = Math.sqrt(0.5 * (1 - pan));
    this._panR[idx] = Math.sqrt(0.5 * (1 + pan));
    this._active[idx] = 1;
  }

  process(_inputs, outputs, parameters) {
    const out = outputs[0];
    const outL = out[0];
    const outR = out.length > 1 ? out[1] : out[0];
    const n = outL.length;

    const density = parameters.density[0];
    const grainMs = parameters.grainMs[0];
    const scanSpray = parameters.scanSpray[0];
    const panSpray = parameters.panSpray[0];
    const scanArr = parameters.scan;
    const scanIsARate = scanArr.length > 1;

    const spawnStep = density / sampleRate;

    for (let i = 0; i < n; i++) {
      const scan = scanIsARate ? scanArr[i] : scanArr[0];

      this._spawnAcc += spawnStep;
      while (this._spawnAcc >= 1) {
        this._spawnAcc -= 1;
        this._spawnGrain(scan, scanSpray, panSpray, grainMs);
      }

      let sampleL = 0;
      let sampleR = 0;

      for (let g = 0; g < POOL_SIZE; g++) {
        if (!this._active[g]) continue;

        const total = this._total[g];
        const elapsed = this._elapsed[g];
        if (elapsed >= total) {
          this._active[g] = 0;
          continue;
        }

        let envIdx = (elapsed * this._envStep[g]) | 0;
        if (envIdx >= ENV_TABLE_SIZE) envIdx = ENV_TABLE_SIZE - 1;
        const amp = this._envTable[envIdx];

        let pos = this._srcPos[g] | 0; // nearest-neighbor read
        if (pos >= this._frames) pos = this._frames - 1;
        const s = this._sample[pos] * amp;

        sampleL += s * this._panL[g];
        sampleR += s * this._panR[g];

        let next = this._srcPos[g] + 1;
        if (next >= this._frames) next -= this._frames; // loop the source
        this._srcPos[g] = next;
        this._elapsed[g] = elapsed + 1;
      }

      outL[i] = sampleL;
      outR[i] = sampleR;
    }

    this._quantumCount++;
    if (this._quantumCount >= GRAIN_MESSAGE_INTERVAL) {
      this._quantumCount = 0;
      this._postGrainPositions();
    }

    return true;
  }

  // Throttled telemetry, once every GRAIN_MESSAGE_INTERVAL quanta — not on
  // the per-sample hot path, safe to keep as its own call.
  _postGrainPositions() {
    let count = 0;
    for (let g = 0; g < POOL_SIZE; g++) {
      if (this._active[g]) {
        this._positionsMsg[count] = this._frames > 0 ? this._srcPos[g] / this._frames : 0;
        count++;
      }
    }
    this.port.postMessage({ type: "grains", positions: this._positionsMsg.slice(0, count) });
  }
}

registerProcessor("granular-processor", GranularProcessor);
