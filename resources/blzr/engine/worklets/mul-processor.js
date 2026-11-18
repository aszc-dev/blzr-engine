class MulProcessor extends AudioWorkletProcessor {
    static get parameterDescriptors() {
        // 1, not 0: an unconnected B input passes A through instead of muting it.
        return [{ name: "b", defaultValue: 1 }];
    }

    process(inputs, outputs, parameters) {
        const output = outputs[0];
        const inputA = inputs[0];
        const paramB = parameters.b;

        const channelCount = Math.min(inputA.length, output.length);

        for (let channel = 0; channel < channelCount; ++channel) {
            const inputChannel = inputA[channel];
            const outputChannel = output[channel];

            const bValues = paramB.length > 1 ? paramB : null;
            const bStatic = paramB.length === 1 ? paramB[0] : 0;

            if (bValues) {
                for (let i = 0; i < outputChannel.length; ++i) {
                    outputChannel[i] = inputChannel[i] * bValues[i];
                }
            } else {
                for (let i = 0; i < outputChannel.length; ++i) {
                    outputChannel[i] = inputChannel[i] * bStatic;
                }
            }
        }
        return true;
    }
}
registerProcessor("mul-processor", MulProcessor);
