class MulProcessor extends AudioWorkletProcessor {
    static get parameterDescriptors() {
        // Domyślnie 1, żeby nie wyciszać sygnału przy braku wejścia B
        return [{ name: "b", defaultValue: 1 }];
    }

    process(inputs, outputs, parameters) {
        const output = outputs[0];
        const inputA = inputs[0]; // Sygnał wejściowy (główny)
        const paramB = parameters.b; // Sygnał modulujący lub stała

        // Obsługa mono/stereo
        const channelCount = Math.min(inputA.length, output.length);

        for (let channel = 0; channel < channelCount; ++channel) {
            const inputChannel = inputA[channel];
            const outputChannel = output[channel];

            // Optymalizacja: pobierz tablicę wartości B lub stałą
            const bValues = paramB.length > 1 ? paramB : null;
            const bStatic = paramB.length === 1 ? paramB : 0;

            if (bValues) {
                // Modulacja (a-rate)
                for (let i = 0; i < outputChannel.length; ++i) {
                    outputChannel[i] = inputChannel[i] * bValues[i];
                }
            } else {
                // Stała wartość (k-rate) - dużo szybsze
                for (let i = 0; i < outputChannel.length; ++i) {
                    outputChannel[i] = inputChannel[i] * bStatic;
                }
            }
        }
        return true;
    }
}
registerProcessor("mul-processor", MulProcessor);
