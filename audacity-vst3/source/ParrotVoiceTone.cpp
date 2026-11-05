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

constexpr double kDefaultMix = 50.0;
constexpr double kDefaultNasal = 45.0;
constexpr double kDefaultBrightness = 25.0;
constexpr double kDefaultRoughness = 12.0;
constexpr double kDefaultFlutter = 8.0;
constexpr double kFlutterHz = 5.5;
constexpr double kPi = 3.14159265358979323846;
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
    Biquad beakBrightness;
    double flutterPhase {0.0};

    void reset() {
        highPass.reset();
        lowPass.reset();
        chestCut.reset();
        nasalBody.reset();
        nasalEdge.reset();
        beakBrightness.reset();
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

    tresult PLUGIN_API setupProcessing(ProcessSetup& setup) SMTG_OVERRIDE {
        const tresult result = SingleComponentEffect::setupProcessing(setup);
        if (result != kResultOk) {
            return result;
        }
        sampleRate_ = setup.sampleRate > 1000.0 ? setup.sampleRate : 48000.0;
        for (auto& channel : channels_) {
            channel.reset();
        }
        return kResultOk;
    }

    tresult PLUGIN_API canProcessSampleSize(int32 symbolicSampleSize) SMTG_OVERRIDE {
        return symbolicSampleSize == kSample32 ? kResultTrue : kResultFalse;
    }

    tresult PLUGIN_API process(ProcessData& data) SMTG_OVERRIDE {
        if (data.numInputs < 1 || data.numOutputs < 1 || data.numSamples <= 0
            || data.inputs[0].channelBuffers32 == nullptr
            || data.outputs[0].channelBuffers32 == nullptr) {
            return kResultOk;
        }

        readParameters(data.inputParameterChanges);

        const int32 inputChannels = data.inputs[0].numChannels;
        const int32 outputChannels = data.outputs[0].numChannels;
        const int32 channelCount = std::min(inputChannels, outputChannels);
        float** inputs = data.inputs[0].channelBuffers32;
        float** outputs = data.outputs[0].channelBuffers32;

        configureFilters(channelCount);

        for (int32 channel = 0; channel < channelCount; ++channel) {
            if (channel >= kMaximumChannels || inputs[channel] == nullptr || outputs[channel] == nullptr) {
                continue;
            }
            processChannel(inputs[channel], outputs[channel], data.numSamples, channels_[channel]);
        }

        for (int32 channel = channelCount; channel < outputChannels; ++channel) {
            if (outputs[channel] != nullptr) {
                std::fill(outputs[channel], outputs[channel] + data.numSamples, 0.0f);
            }
        }

        data.outputs[0].silenceFlags = 0;
        return kResultOk;
    }

private:
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
            channel.highPass.highPass(sampleRate_, 150.0, 0.707);
            channel.lowPass.lowPass(sampleRate_, 7200.0, 0.707);
            channel.chestCut.peaking(sampleRate_, 620.0, 1.1, -0.8 * nasal_);
            channel.nasalBody.peaking(sampleRate_, 1350.0, 1.1, 3.0 * nasal_);
            channel.nasalEdge.peaking(sampleRate_, 2450.0, 1.0, 3.5 * nasal_);
            channel.beakBrightness.peaking(sampleRate_, 3800.0, 0.9, 3.0 * brightness_);
        }
    }

    void processChannel(const float* input, float* output, int32 samples, ChannelState& state) {
        const double levels = 2048.0 - 1536.0 * roughness_;
        const double grainMix = 0.16 * roughness_;
        const double flutterDepth = 0.035 * flutter_;
        const double phaseStep = 2.0 * kPi * kFlutterHz / sampleRate_;

        for (int32 i = 0; i < samples; ++i) {
            const double dry = std::isfinite(input[i]) ? input[i] : 0.0;
            double wet = state.highPass.process(dry);
            wet = state.chestCut.process(wet);
            wet = state.nasalBody.process(wet);
            wet = state.nasalEdge.process(wet);
            wet = state.beakBrightness.process(wet);
            wet = state.lowPass.process(wet);

            const double quantized = std::round(wet * levels) / levels;
            wet += (quantized - wet) * grainMix;

            wet *= 1.0 + std::sin(state.flutterPhase) * flutterDepth;
            state.flutterPhase += phaseStep;
            if (state.flutterPhase >= 2.0 * kPi) {
                state.flutterPhase -= 2.0 * kPi;
            }

            double result = dry + (wet - dry) * mix_;
            // A very gentle safety soft-clipper prevents overs on resonant material.
            const double magnitude = std::abs(result);
            if (magnitude > 0.985) {
                result = std::copysign(0.985 + 0.015 * std::tanh((magnitude - 0.985) / 0.015), result);
            }
            output[i] = static_cast<float>(result);
        }
    }

    double sampleRate_ {48000.0};
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
           "0.3.2",
           kVstVersionString,
           ParrotVoiceTone::createInstance)

END_FACTORY
