// Envelope follower: full-wave rectifier + one-pole LPF with split attack/release
// coefficients. Output tracks the smoothed magnitude of the input signal, suitable
// for driving control-rate targets (e.g. amp.gain) from an oscillating audio source.
class EnvelopeFollower extends AudioWorkletProcessor {
    static get parameterDescriptors() {
        return [
            { name: "attack",  defaultValue: 0.005, minValue: 0.0001, maxValue: 1, automationRate: "a-rate" },
            { name: "release", defaultValue: 0.050, minValue: 0.0001, maxValue: 5, automationRate: "a-rate" }
        ];
    }

    constructor() {
        super();
        this._env = 0;
    }

    process(inputs, outputs, parameters) {
        const inCh  = inputs[0] && inputs[0][0];
        const outCh = outputs[0][0];
        if (!inCh) {
            outCh.fill(this._env);
            return true;
        }
        const sr = sampleRate;
        const aArr = parameters.attack;
        const rArr = parameters.release;
        for (let i = 0; i < outCh.length; i++) {
            const a = aArr[aArr.length > 1 ? i : 0];
            const r = rArr[rArr.length > 1 ? i : 0];
            const x = Math.abs(inCh[i]);
            const coef = x > this._env
                ? Math.exp(-1 / (a * sr))
                : Math.exp(-1 / (r * sr));
            this._env = coef * this._env + (1 - coef) * x;
            outCh[i] = this._env;
        }
        return true;
    }
}

registerProcessor("env-follow-processor", EnvelopeFollower);
