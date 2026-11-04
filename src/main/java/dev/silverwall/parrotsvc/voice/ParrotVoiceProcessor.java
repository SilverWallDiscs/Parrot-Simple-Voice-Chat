package dev.silverwall.parrotsvc.voice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Offline DSP for a speech-preserving, stylized parrot voice.
 *
 * The original learned PCM is never modified. Pitch is raised by 1.8x and
 * WSOLA restores the original duration; a configurable wet path then adds
 * mild nasal resonances, brightness, quantized rasp and vocal flutter.
 * This is a lightweight timbral effect, not a biological bird-voice model.
 */
public final class ParrotVoiceProcessor {
    private static final int SAMPLE_RATE = 48_000;
    private static final double PARROT_PITCH_RATIO = 1.8;
    private static final double VIBRATO_HZ = 6.2;
    private static final double VIBRATO_DEPTH_SAMPLES = 1.6;
    private static final double FLUTTER_HZ = 5.5;

    private static final FilterSettings DEFAULT_FILTERS =
            new FilterSettings(50, 45, 25, 12, 8);
    private static volatile FilterSettings filters = DEFAULT_FILTERS;
    private static volatile boolean settingsLoaded;

    private ParrotVoiceProcessor() {
    }

    /**
     * Loads the shared Audacity/mod filter controls from config/parrot-svc.
     * The file is created on first playback and re-read after a server restart.
     */
    private static void ensureSettingsLoaded() {
        if (settingsLoaded) {
            return;
        }
        synchronized (ParrotVoiceProcessor.class) {
            if (settingsLoaded) {
                return;
            }

            Path directory = FabricLoader.getInstance().getConfigDir().resolve("parrot-svc");
            Path configFile = directory.resolve("voice-filters.properties");
            Properties properties = new Properties();
            try {
                Files.createDirectories(directory);
                if (Files.isRegularFile(configFile)) {
                    try (InputStream input = Files.newInputStream(configFile)) {
                        properties.load(input);
                    }
                }

                int mix = readPercent(properties, "mix-percent", DEFAULT_FILTERS.mixPercent());
                int nasal = readPercent(properties, "nasal", DEFAULT_FILTERS.nasal());
                int brightness = readPercent(properties, "brightness", DEFAULT_FILTERS.brightness());
                int roughness = readPercent(properties, "roughness", DEFAULT_FILTERS.roughness());
                int flutter = readPercent(properties, "flutter", DEFAULT_FILTERS.flutter());

                properties.setProperty("mix-percent", Integer.toString(mix));
                properties.setProperty("nasal", Integer.toString(nasal));
                properties.setProperty("brightness", Integer.toString(brightness));
                properties.setProperty("roughness", Integer.toString(roughness));
                properties.setProperty("flutter", Integer.toString(flutter));

                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output,
                            "Parrot voice filter controls (0-100). Changes apply after a server restart.");
                }
                filters = new FilterSettings(mix, nasal, brightness, roughness, flutter);
            } catch (IOException exception) {
                filters = DEFAULT_FILTERS;
            }
            settingsLoaded = true;
        }
    }

    private static int readPercent(Properties properties, String key, int fallback) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
            return Math.max(0, Math.min(100, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static short[] process(short[] input) {
        if (input == null || input.length == 0) {
            return new short[0];
        }

        ensureSettingsLoaded();
        FilterSettings controls = filters;

        // Keep the existing 1.8x pitch character, but do not change phrase length.
        short[] pitched = pitchUp(input, PARROT_PITCH_RATIO);
        try {
            Biquad highPass = Biquad.highPass(SAMPLE_RATE, 150.0, 0.707);
            Biquad lowPass = Biquad.lowPass(SAMPLE_RATE, 7_200.0, 0.707);

            double nasal = controls.nasal() / 100.0;
            double brightness = controls.brightness() / 100.0;
            double roughness = controls.roughness() / 100.0;
            double flutter = controls.flutter() / 100.0;
            double blend = controls.mixPercent() / 100.0;

            // Broad, gentle resonances avoid the harsh metallic peaks of the old chain.
            Biquad nasalBody = Biquad.peaking(SAMPLE_RATE, 1_350.0, 1.1, 3.0 * nasal);
            Biquad nasalEdge = Biquad.peaking(SAMPLE_RATE, 2_450.0, 1.0, 3.5 * nasal);
            Biquad beakBrightness = Biquad.peaking(SAMPLE_RATE, 3_800.0, 0.9, 3.0 * brightness);
            Biquad chestCut = Biquad.peaking(SAMPLE_RATE, 620.0, 1.1, -0.8 * nasal);

            double quantizationLevels = 2_048.0 - (1_536.0 * roughness);
            double roughnessBlend = 0.16 * roughness;
            double flutterDepth = 0.035 * flutter;
            double[] mixed = new double[pitched.length];
            double peak = 0.0;

            for (int i = 0; i < pitched.length; i++) {
                double dry = pitched[i] / 32768.0;
                double wet = highPass.process(dry);
                wet = chestCut.process(wet);
                wet = nasalBody.process(wet);
                wet = nasalEdge.process(wet);
                wet = beakBrightness.process(wet);
                wet = lowPass.process(wet);

                // A small, adjustable reduction in bit depth adds grain without
                // replacing the consonant detail that makes words understandable.
                double quantized = Math.rint(wet * quantizationLevels) / quantizationLevels;
                wet += (quantized - wet) * roughnessBlend;

                // Slow amplitude flutter adds a little irregularity to the wet voice.
                double time = i / (double) SAMPLE_RATE;
                wet *= 1.0 + Math.sin(2.0 * Math.PI * FLUTTER_HZ * time) * flutterDepth;

                // 0% is the clean pitched human voice; 100% is the full parrot filter.
                double value = dry + (wet - dry) * blend;
                mixed[i] = value;
                peak = Math.max(peak, Math.abs(value));
            }

            // Preserve headroom if resonances raise a particularly loud recording.
            double outputGain = peak > 0.98 ? 0.98 / peak : 1.0;
            short[] output = new short[mixed.length];
            for (int i = 0; i < mixed.length; i++) {
                long pcm = Math.round(mixed[i] * outputGain * 32767.0);
                output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm));
            }
            Arrays.fill(mixed, 0.0);
            return output;
        } finally {
            Arrays.fill(pitched, (short) 0);
        }
    }

    private record FilterSettings(int mixPercent, int nasal, int brightness, int roughness, int flutter) {
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
            Arrays.fill(compressed, (short) 0);
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

            if (!Double.isFinite(output)) {
                x1 = x2 = y1 = y2 = 0.0;
                return 0.0;
            }
            return output;
        }
    }
}