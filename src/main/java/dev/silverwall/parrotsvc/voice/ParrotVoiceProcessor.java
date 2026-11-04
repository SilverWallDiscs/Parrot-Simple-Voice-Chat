package dev.silverwall.parrotsvc.voice;

/**
 * Offline DSP chain for a stylized parrot-like voice.
 *
 * It is intentionally dependency-free and processes only a short phrase when
 * it is replayed. Resonant EQ creates a nasal/formant-like character; this is
 * an approximation, not a full LPC or phase-vocoder formant shifter.
 */
public final class ParrotVoiceProcessor {
    private static final int SAMPLE_RATE = 48_000;
    private static final double PARROT_PITCH_RATIO = 1.8; // raise pitch while preserving the original duration
    private static final double VIBRATO_HZ = 6.2;
    private static final double VIBRATO_DEPTH_SAMPLES = 1.6;
    private static final double FLUTTER_HZ = 17.0;
    private static final double FLUTTER_DEPTH = 0.012;

    private ParrotVoiceProcessor() {
    }

    public static short[] process(short[] input) {
        if (input == null || input.length == 0) {
            return new short[0];
        }

        // Fixed 1.8x pitch. WSOLA restores the original duration, so the
        // parrot sounds higher without speaking faster or shortening phrases.
        short[] pitched = pitchUp(input, PARROT_PITCH_RATIO);
        Biquad highPass = Biquad.highPass(SAMPLE_RATE, 300.0, 0.707);
        Biquad lowPass = Biquad.lowPass(SAMPLE_RATE, 5_000.0, 0.707);

        // High-Q, narrow resonances. Keeping these peaks narrow prevents the
        // whole midrange from becoming boxy or harsh while preserving the
        // independent 300 Hz / 5 kHz band limits.
        Biquad nasalBody = Biquad.peaking(SAMPLE_RATE, 1_250.0, 5.5, 2.2);
        Biquad nasalEdge = Biquad.peaking(SAMPLE_RATE, 2_350.0, 7.0, 3.0);
        Biquad squawk = Biquad.peaking(SAMPLE_RATE, 3_450.0, 8.0, 1.5);
        Biquad chestCut = Biquad.peaking(SAMPLE_RATE, 680.0, 1.4, -2.0);

        short[] output = new short[pitched.length];
        double normalization = Math.tanh(1.65);

        for (int i = 0; i < pitched.length; i++) {
            double sample = pitched[i] / 32768.0;

            sample = highPass.process(sample);
            sample = chestCut.process(sample);
            sample = nasalBody.process(sample);
            sample = nasalEdge.process(sample);
            sample = squawk.process(sample);
            sample = lowPass.process(sample);

            // Very gentle, asymmetric soft clipping adds a little rasp without
            // destroying consonants or making the recording sound like static.
            double driven = sample + 0.035 * sample * sample;
            double clipped = Math.tanh(driven * 1.65) / normalization;
            sample = sample * 0.88 + clipped * 0.12;

            // A small amount of low-resolution texture, blended in quietly.
            double quantized = Math.rint(sample * 1023.0) / 1023.0;
            sample = sample * 0.98 + quantized * 0.02;

            // Fine flutter gives the voice a slightly squeaky, unstable edge.
            double time = i / (double) SAMPLE_RATE;
            double flutter = 1.0 + Math.sin(2.0 * Math.PI * FLUTTER_HZ * time) * FLUTTER_DEPTH;
            sample *= flutter;

            int pcm = (int) Math.round(sample * 32767.0);
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm));
        }
        return output;
    }

    private static short[] pitchUp(short[] input, double pitchRatio) {
        int compressedLength = Math.max(1, (int) Math.ceil(input.length / pitchRatio));
        short[] compressed = new short[compressedLength];

        for (int i = 0; i < compressedLength; i++) {
            double basePosition = i * pitchRatio;
            double vibrato = Math.sin(2.0 * Math.PI * VIBRATO_HZ * i / SAMPLE_RATE)
                    * VIBRATO_DEPTH_SAMPLES;
            double position = Math.max(0.0, Math.min(input.length - 1.0, basePosition + vibrato));

            int left = (int) position;
            int right = Math.min(left + 1, input.length - 1);
            double fraction = position - left;
            double value = input[left] + (input[right] - input[left]) * fraction;
            compressed[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(value)));
        }

        // Pitch shifting by resampling shortens the clip. WSOLA stretches those
        // high-pitched grains back to the input length without lowering pitch.
        try {
            return stretchToLength(compressed, input.length);
        } finally {
            java.util.Arrays.fill(compressed, (short) 0);
        }
    }

    /**
     * Waveform Similarity Overlap-Add (WSOLA). Frames keep their sample rate;
     * only their overlap positions change, preserving pitch while changing
     * duration. The normalized output length is exactly the requested length.
     */
    private static short[] stretchToLength(short[] source, int targetLength) {
        if (targetLength <= 0 || source.length == 0) return new short[0];
        if (source.length == targetLength) return source.clone();

        int windowSize = Math.min(960, source.length);
        if (windowSize < 96) {
            // Very short fallback: keep exact output duration for tiny samples.
            short[] result = new short[targetLength];
            for (int i = 0; i < targetLength; i++) {
                double position = i * (source.length - 1.0) / Math.max(1, targetLength - 1);
                int left = (int) position;
                int right = Math.min(left + 1, source.length - 1);
                double fraction = position - left;
                result[i] = (short) Math.round(source[left]
                        + (source[right] - source[left]) * fraction);
            }
            return result;
        }

        int synthesisHop = Math.max(24, windowSize / 4);
        int overlap = windowSize - synthesisHop;
        int searchRadius = Math.max(8, windowSize / 12);
        int maxSourceStart = Math.max(0, source.length - windowSize);
        double stretchRatio = targetLength / (double) source.length;
        double[] accumulated = new double[targetLength];
        double[] weights = new double[targetLength];

        for (int outputStart = 0; outputStart < targetLength; outputStart += synthesisHop) {
            int expected = (int) Math.round(outputStart / stretchRatio);
            int chosen = Math.max(0, Math.min(maxSourceStart, expected));

            if (outputStart > 0 && maxSourceStart > 0) {
                int from = Math.max(0, chosen - searchRadius);
                int to = Math.min(maxSourceStart, chosen + searchRadius);
                double bestScore = -Double.MAX_VALUE;

                // Sparse normalized cross-correlation is enough to align voiced
                // cycles while keeping CPU cost low for longer phrases.
                for (int candidate = from; candidate <= to; candidate += 2) {
                    double dot = 0.0;
                    double referenceEnergy = 1.0;
                    double candidateEnergy = 1.0;
                    for (int k = 0; k < overlap; k += 4) {
                        int outputIndex = outputStart + k;
                        if (outputIndex >= targetLength || weights[outputIndex] <= 1.0e-9) continue;
                        double reference = accumulated[outputIndex] / weights[outputIndex];
                        double sample = source[candidate + k];
                        dot += reference * sample;
                        referenceEnergy += reference * reference;
                        candidateEnergy += sample * sample;
                    }
                    double score = dot / Math.sqrt(referenceEnergy * candidateEnergy);
                    if (score > bestScore) {
                        bestScore = score;
                        chosen = candidate;
                    }
                }
            }

            for (int k = 0; k < windowSize && outputStart + k < targetLength; k++) {
                int sourceIndex = Math.min(source.length - 1, chosen + k);
                double window = 0.5 - 0.5 * Math.cos(
                        2.0 * Math.PI * (k + 0.5) / windowSize);
                int outputIndex = outputStart + k;
                accumulated[outputIndex] += source[sourceIndex] * window;
                weights[outputIndex] += window;
            }
        }

        short[] result = new short[targetLength];
        for (int i = 0; i < targetLength; i++) {
            double value = weights[i] > 1.0e-9 ? accumulated[i] / weights[i]
                    : (i > 0 ? result[i - 1] : source[0]);
            result[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(value)));
        }
        return result;
    }

    /** RBJ cookbook biquad, direct-form I. */
    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Biquad lowPass(double sampleRate, double frequency, double q) {
            double omega = 2.0 * Math.PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double a0 = 1.0 + alpha;
            return new Biquad(
                    ((1.0 - cosine) * 0.5) / a0,
                    (1.0 - cosine) / a0,
                    ((1.0 - cosine) * 0.5) / a0,
                    (-2.0 * cosine) / a0,
                    (1.0 - alpha) / a0);
        }

        static Biquad highPass(double sampleRate, double frequency, double q) {
            double omega = 2.0 * Math.PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double a0 = 1.0 + alpha;
            return new Biquad(
                    ((1.0 + cosine) * 0.5) / a0,
                    (-(1.0 + cosine)) / a0,
                    ((1.0 + cosine) * 0.5) / a0,
                    (-2.0 * cosine) / a0,
                    (1.0 - alpha) / a0);
        }

        static Biquad peaking(double sampleRate, double frequency, double q, double gainDb) {
            double a = Math.pow(10.0, gainDb / 40.0);
            double omega = 2.0 * Math.PI * frequency / sampleRate;
            double cosine = Math.cos(omega);
            double alpha = Math.sin(omega) / (2.0 * q);
            double a0 = 1.0 + alpha / a;
            return new Biquad(
                    (1.0 + alpha * a) / a0,
                    (-2.0 * cosine) / a0,
                    (1.0 - alpha * a) / a0,
                    (-2.0 * cosine) / a0,
                    (1.0 - alpha / a) / a0);
        }

        double process(double input) {
            double output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;

            // Prevent a malformed or unexpectedly loud frame from destabilizing
            // the next sample while preserving normal dynamics.
            if (!Double.isFinite(output)) {
                x1 = x2 = y1 = y2 = 0.0;
                return 0.0;
            }
            return output;
        }
    }
}
