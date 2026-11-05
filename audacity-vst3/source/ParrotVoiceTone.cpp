#include "public.sdk/source/main/pluginfactory.h"
#include "public.sdk/source/vst/vstsinglecomponenteffect.h"
#include "pluginterfaces/base/ustring.h"
#include "pluginterfaces/vst/ivstparameterchanges.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace Steinberg {
namespace Vst {

namespace {
enum ParameterId : ParamID {
    kMix = 0,
    kNasal = 1,
    kBrightness = 2,
    kRoughness = 3,
    kFlutter = 4
};

constexpr double kDefaultMix = 85.0;
constexpr double kDefaultNasal = 75.0;
constexpr double kDefaultBrightness = 60.0;
constexpr double kDefaultRoughness = 38.0;
constexpr double kDefaultFlutter = 28.0;
constexpr double kFlutterHz = 5.8;
constexpr double kPi = 3.14159265358979323846;
constexpr double kPitchRatio = 1.8;
constexpr int32 kPitchDelaySamples = 1024;
constexpr int32 kPitchBufferSize = 2048;
constexpr int32 kMaximumChannels = 64;

double clamp01(double value) {
    return std::max(0.0, std::min(1.0, value));
}

class Biquad {
public:
    void reset() {
        x1_ = x2_ = y1_ = y2_ = 0.0;
    }

    void highPass(double sampleRate, double frequency, double q) {
        const double omega = 2.0 * kPi * frequency / sampleRate;
        const double cosine = std::cos(omega);
        const double alpha = std::sin(omega) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        set((1.0 + cosine) * 0.5 / a0, -(1.0 + cosine) / a0,
            (1.0 + cosine) * 0.5 / a0, -2.0 * cosine / a0,
            (1.0 - alpha) / a0);
    }

    void lowPass(double sampleRate, double frequency, double q) {
        const double omega = 2.0 * kPi * frequency / sampleRate;
        const double cosine = std::cos(omega);
        const double alpha = std::sin(omega) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        set((1.0 - cosine) * 0.5 / a0, (1.0 - cosine) / a0,
            (1.0 - cosine) * 0.5 / a0, -2.0 * cosine / a0,
            (1.0 - alpha) / a0);
    }

    void peaking(double sampleRate, double frequency, double q, double gainDb) {
        const double a = std::pow(10.0, gainDb / 40.0);
        const double omega = 2.0 * kPi * frequency / sampleRate;
        const double cosine = std::cos(omega);
        const double alpha = std::sin(omega) / (2.0 * q);
        const double a0 = 1.0 + alpha / a;
        set((1.0 + alpha * a) / a0, -2.0 * cosine / a0,
            (1.0 - alpha * a) / a0, -2.0 * cosine / a0,
            (1.0 - alpha / a) / a0);
    }

    double process(double input) {
        const double output = b0_ * input + b1_ * x1_ + b2_ * x2_
                            - a1_ * y1_ - a2_ * y2_;
        x2_ = x1_;
        x1_ = input;
        y2_ = y1_;
        y1_ = std::isfinite(output) ? output : 0.0;
        if (!std::isfinite(output)) {
            reset();
            return 0.0;
        }
        return output;
    }

private:
    void set(double b0, double b1, double b2, double a1, double a2) {
        b0_ = b0; b1_ = b1; b2_ = b2; a1_ = a1; a2_ = a2;
    }

    double b0_ {1.0}, b1_ {0.0}, b2_ {0.0}, a1_ {0.0}, a2_ {0.0};
    double x1_ {0.0}, x2_ {0.0}, y1_ {0.0}, y2_ {0.0};
};

struct ChannelState {
    Biquad highPass;
    Biquad lowPass;
    Biquad chestCut;
    Biquad nasalBody;
    Biquad nasalEdge;
    Biquad formantPeak;
    Biquad beakBrightness;
    std::array<double, kPitchBufferSize> pitchBuffer {};
    int32 pitchWritePosition {0};
    double pitchPhaseA {0.0};
    double pitchPhaseB {0.5};
    double flutterPhase {0.0};

    void reset() {
        highPass.reset();
        lowPass.reset();
        chestCut.reset();
        nasalBody.reset();
        nasalEdge.reset();
        formantPeak.reset();
        beakBrightness.reset();
        pitchBuffer.fill(0.0);
        pitchWritePosition = 0;
        pitchPhaseA = 0.0;
        pitchPhaseB = 0.5;
        flutterPhase = 0.0;
    }
};
} // namespace

class ParrotVoiceTone final : public SingleComponentEffect {
public:
    static FUnknown* createInstance(void*) {
        return static_cast<IAudioProcessor*>(new ParrotVoiceTone());
    }

    tresult PLUGIN_API initialize(FUnknown* context) SMTG_OVERRIDE {
        const tresult result = SingleComponentEffect::initialize(context);
        if (result != kResultOk) {
            return result;
        }

        addAudioInput(STR16("Audio In"), SpeakerArr::kStereo);
        addAudioOutput(STR16("Audio Out"), SpeakerArr::kStereo);

        parameters.addParameter(new RangeParameter(
            USTRING("Human / Parrot"), kMix, USTRING("%"), 0.0, 100.0,
            kDefaultMix, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Nasal Resonance"), kNasal, USTRING("%"), 0.0, 100.0,
            kDefaultNasal, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Bright Beak Edge"), kBrightness, USTRING("%"), 0.0, 100.0,
            kDefaultBrightness, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Roughness / Grain"), kRoughness, USTRING("%"), 0.0, 100.0,
            kDefaultRoughness, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Vocal Flutter"), kFlutter, USTRING("%"), 0.0, 100.0,
            kDefaultFlutter, kStepCountContinuous, ParameterInfo::kCanAutomate));

        return kResultOk;
    }

    // Audacity voice recordings are commonly mono. Accept both mono and stereo
    // instead of advertising a stereo-only bus that can leave a mono track untreated.
    tresult PLUGIN_API setBusArrangements(SpeakerArrangement* inputs, int32 numIns,
                                           SpeakerArrangement* outputs, int32 numOuts) SMTG_OVERRIDE {
        if (numIns != 1 || numOuts != 1) {
            return kResultFalse;
        }

        const int32 inputCount = SpeakerArr::getChannelCount(inputs[0]);
        const int32 outputCount = SpeakerArr::getChannelCount(outputs[0]);
        if (inputCount != outputCount || (inputCount != 1 && inputCount != 2)) {
            return kResultFalse;
        }

        auto* inputBus = getAudioInput(0);
        auto* outputBus = getAudioOutput(0);
        if (!inputBus || !outputBus) {
            return kResultFalse;
        }

        inputBus->setArrangement(inputs[0]);
        outputBus->setArrangement(outputs[0]);
        return kResultOk;
    }

    tresult PLUGIN_API setupProcessing(ProcessSetup& setup) SMTG_OVERRIDE {
        const tresult result = SingleComponentEffect::setupProcessing(setup);
        if (result != kResultOk) {
            return result;
        }
        sampleRate_ = setup.sampleRate > 1000.0 ? setup.sampleRate : 48000.0;
        sampleSize_ = setup.symbolicSampleSize;
        for (auto& channel : channels_) {
            channel.reset();
        }
        return kResultOk;
    }

    // The dual-head pitch shifter has an average delay of about half its delay span.
    uint32 PLUGIN_API getLatencySamples() SMTG_OVERRIDE {
        return static_cast<uint32>(kPitchDelaySamples / 2);
    }

    tresult PLUGIN_API canProcessSampleSize(int32 symbolicSampleSize) SMTG_OVERRIDE {
        return (symbolicSampleSize == kSample32 || symbolicSampleSize == kSample64)
            ? kResultTrue : kResultFalse;
    }

    tresult PLUGIN_API process(ProcessData& data) SMTG_OVERRIDE {
        if (data.numInputs < 1 || data.numOutputs < 1 || data.numSamples <= 0) {
            return kResultOk;
        }

        readParameters(data.inputParameterChanges);

        if (sampleSize_ == kSample64) {
            if (data.inputs[0].channelBuffers64 == nullptr
                || data.outputs[0].channelBuffers64 == nullptr) {
                return kResultOk;
            }
            processBuffers(data.inputs[0].channelBuffers64, data.outputs[0].channelBuffers64,
                           data.inputs[0].numChannels, data.outputs[0].numChannels, data.numSamples);
        } else {
            if (data.inputs[0].channelBuffers32 == nullptr
                || data.outputs[0].channelBuffers32 == nullptr) {
                return kResultOk;
            }
            processBuffers(data.inputs[0].channelBuffers32, data.outputs[0].channelBuffers32,
                           data.inputs[0].numChannels, data.outputs[0].numChannels, data.numSamples);
        }

        return kResultOk;
    }

private:
    template <typename Sample>
    void processBuffers(Sample** inputs, Sample** outputs, int32 inputChannels,
                        int32 outputChannels, int32 sampleCount) {
        const int32 channelCount = std::min(inputChannels, outputChannels);
        configureFilters(channelCount);

        for (int32 channel = 0; channel < channelCount; ++channel) {
            if (channel >= kMaximumChannels || inputs[channel] == nullptr || outputs[channel] == nullptr) {
                continue;
            }
            processChannel(inputs[channel], outputs[channel], sampleCount, channels_[channel]);
        }

        for (int32 channel = channelCount; channel < outputChannels; ++channel) {
            if (outputs[channel] != nullptr) {
                std::fill(outputs[channel], outputs[channel] + sampleCount, static_cast<Sample>(0));
            }
        }

    }

    void readParameters(IParameterChanges* changes) {
        if (!changes) {
            return;
        }
        for (int32 index = 0; index < changes->getParameterCount(); ++index) {
            IParamValueQueue* queue = changes->getParameterData(index);
            if (!queue || queue->getPointCount() <= 0) {
                continue;
            }
            ParamValue value = 0.0;
            int32 sampleOffset = 0;
            if (queue->getPoint(queue->getPointCount() - 1, sampleOffset, value) != kResultTrue) {
                continue;
            }
            const double plain = clamp01(value) * 100.0;
            switch (queue->getParameterId()) {
                case kMix: mix_ = plain / 100.0; break;
                case kNasal: nasal_ = plain / 100.0; break;
                case kBrightness: brightness_ = plain / 100.0; break;
                case kRoughness: roughness_ = plain / 100.0; break;
                case kFlutter: flutter_ = plain / 100.0; break;
                default: break;
            }
        }
    }

    void configureFilters(int32 channelCount) {
        const int32 count = std::min(channelCount, kMaximumChannels);
        for (int32 index = 0; index < count; ++index) {
            auto& channel = channels_[index];
            channel.highPass.highPass(sampleRate_, 170.0, 0.707);
            channel.lowPass.lowPass(sampleRate_, 8800.0, 0.707);
            // Stronger, broad vocal-tract resonances: a nasal body, a forward
            // beak resonance and an upper "ring" that remain speech-shaped.
            channel.chestCut.peaking(sampleRate_, 500.0, 1.0, -5.5 * nasal_);
            channel.nasalBody.peaking(sampleRate_, 1200.0, 1.25, 8.0 * nasal_);
            channel.nasalEdge.peaking(sampleRate_, 2450.0, 1.15, 9.0 * nasal_);
            channel.formantPeak.peaking(sampleRate_, 3400.0, 1.3, 6.0 * nasal_);
            channel.beakBrightness.peaking(sampleRate_, 4300.0, 1.0, 8.0 * brightness_);
        }
    }

    double pitchUp(double input, ChannelState& state) {
        // Two overlapping delay taps read faster than the write head. Their
        // cosine windows crossfade at the delay wrap, raising pitch without
        // changing the duration of the recorded phrase.
        state.pitchBuffer[state.pitchWritePosition] = input;
        const double phaseStep = (kPitchRatio - 1.0) / (kPitchDelaySamples - 4.0);
        double sum = 0.0;
        double weightSum = 0.0;

        for (int head = 0; head < 2; ++head) {
            double& phase = head == 0 ? state.pitchPhaseA : state.pitchPhaseB;
            const double delay = 4.0 + (1.0 - phase) * (kPitchDelaySamples - 4.0);
            double readPosition = state.pitchWritePosition - delay;
            while (readPosition < 0.0) readPosition += kPitchBufferSize;
            while (readPosition >= kPitchBufferSize) readPosition -= kPitchBufferSize;

            const int32 indexA = static_cast<int32>(readPosition);
            const int32 indexB = (indexA + 1) % kPitchBufferSize;
            const double fraction = readPosition - indexA;
            const double delayed = state.pitchBuffer[indexA]
                + (state.pitchBuffer[indexB] - state.pitchBuffer[indexA]) * fraction;
            const double window = 0.5 - 0.5 * std::cos(2.0 * kPi * phase);

            sum += delayed * window;
            weightSum += window;

            phase += phaseStep;
            if (phase >= 1.0) phase -= 1.0;
        }

        state.pitchWritePosition = (state.pitchWritePosition + 1) % kPitchBufferSize;
        return weightSum > 1.0e-9 ? sum / weightSum : input;
    }

    template <typename Sample>
    void processChannel(const Sample* input, Sample* output, int32 samples, ChannelState& state) {
        const double levels = 1024.0 - 768.0 * roughness_;
        const double grainMix = 0.45 * roughness_;
        const double flutterDepth = 0.12 * flutter_;
        const double phaseStep = 2.0 * kPi * kFlutterHz / sampleRate_;

        for (int32 i = 0; i < samples; ++i) {
            const double dry = std::isfinite(static_cast<double>(input[i]))
                ? static_cast<double>(input[i]) : 0.0;
            const double pitched = pitchUp(dry, state);
            double wet = state.highPass.process(pitched);
            wet = state.chestCut.process(wet);
            wet = state.nasalBody.process(wet);
            wet = state.nasalEdge.process(wet);
            wet = state.formantPeak.process(wet);
            wet = state.beakBrightness.process(wet);
            wet = state.lowPass.process(wet);

            // A restrained asymmetric waveshaper adds upper harmonics that make
            // the voice less like a clean EQ and more like a raspy bird vocalization.
            const double asymmetry = 0.035 * roughness_;
            wet += asymmetry * wet * std::abs(wet);
            wet -= 0.24 * roughness_ * wet * wet * wet;

            const double quantized = std::round(wet * levels) / levels;
            wet += (quantized - wet) * grainMix;

            wet *= 1.0 + std::sin(state.flutterPhase) * flutterDepth;
            state.flutterPhase += phaseStep;
            if (state.flutterPhase >= 2.0 * kPi) {
                state.flutterPhase -= 2.0 * kPi;
            }

            double result = pitched + (wet - pitched) * mix_;
            const double magnitude = std::abs(result);
            if (magnitude > 0.97) {
                result = std::copysign(0.97 + 0.03 * std::tanh((magnitude - 0.97) / 0.03), result);
            }
            output[i] = static_cast<Sample>(result);
        }
    }

    double sampleRate_ {48000.0};
    int32 sampleSize_ {kSample32};
    double mix_ {kDefaultMix / 100.0};
    double nasal_ {kDefaultNasal / 100.0};
    double brightness_ {kDefaultBrightness / 100.0};
    double roughness_ {kDefaultRoughness / 100.0};
    double flutter_ {kDefaultFlutter / 100.0};
    std::array<ChannelState, kMaximumChannels> channels_ {};
};

} // namespace Vst
} // namespace Steinberg

using namespace Steinberg::Vst;

BEGIN_FACTORY_DEF("SilverWallDiscs", "https://github.com/SilverWallDiscs", "")

DEF_CLASS2(INLINE_UID(0xB91D37A2, 0x4A2F4F9D, 0x8E1B6C50, 0xD2E7A491),
           PClassInfo::kManyInstances,
           kVstAudioEffectClass,
           "Parrot Voice Tone",
           0,
           "Fx",
           "0.3.3",
           kVstVersionString,
           ParrotVoiceTone::createInstance)

END_FACTORY
