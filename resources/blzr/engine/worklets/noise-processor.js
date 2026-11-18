class NoiseProcessor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [];
  }

  constructor(options) {
    super();
    // Paul Kellet pink noise state variables
    this._b0 = 0;
    this._b1 = 0;
    this._b2 = 0;
    this._b3 = 0;
    this._b4 = 0;
    this._b5 = 0;
    this._b6 = 0;
    // Brown noise state
    this._lastBrown = 0;

    this._type =
      (options.processorOptions && options.processorOptions.type) || "white";
    this.port.onmessage = (e) => {
      if (e.data.type !== undefined) this._type = e.data.type;
    };
  }

  process(inputs, outputs) {
    const output = outputs[0];
    for (let ch = 0; ch < output.length; ch++) {
      const out = output[ch];
      for (let i = 0; i < out.length; i++) {
        const white = Math.random() * 2 - 1;
        switch (this._type) {
          case "pink":
            this._b0 = 0.99886 * this._b0 + white * 0.0555179;
            this._b1 = 0.99332 * this._b1 + white * 0.0750759;
            this._b2 = 0.969 * this._b2 + white * 0.153852;
            this._b3 = 0.8665 * this._b3 + white * 0.3104856;
            this._b4 = 0.55 * this._b4 + white * 0.5329522;
            this._b5 = -0.7616 * this._b5 - white * 0.016898;
            out[i] =
              (this._b0 +
                this._b1 +
                this._b2 +
                this._b3 +
                this._b4 +
                this._b5 +
                this._b6 +
                white * 0.5362) *
              0.11;
            this._b6 = white * 0.115926;
            break;
          case "brown":
            this._lastBrown = (this._lastBrown + 0.02 * white) / 1.02;
            out[i] = this._lastBrown * 3.5;
            break;
          default:
            out[i] = white;
            break;
        }
      }
    }
    return true;
  }
}

registerProcessor("noise-processor", NoiseProcessor);
