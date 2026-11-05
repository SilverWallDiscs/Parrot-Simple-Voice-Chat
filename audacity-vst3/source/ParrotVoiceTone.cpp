#include "public.sdk/source/main/pluginfactory.h"
#include "public.sdk/source/vst/vstaudioeffect.h"
#include "public.sdk/source/vst/vsteditcontroller.h"
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
    kParrotMix = 0,
    kSyrinxEdge = 1,
    kTrachealLength = 2,
    kOecVolume = 3,
    kTonguePosition = 4,
    kBeakOpening = 5,
    kFormantStrength = 6,
    kConsonantDetail = 7,
    kVocalIrregularity = 8
};

constexpr double kDefaultMix = 62.0;
constexpr double kDefaultSyrinxEdge = 24.0;
constexpr double kDefaultTrachealLength = 55.0;
constexpr double kDefaultOecVolume = 45.0;
constexpr double kDefaultTonguePosition = 58.0;
constexpr double kDefaultBeakOpening = 38.0;
constexpr double kDefaultFormantStrength = 55.0;
constexpr double kDefaultConsonantDetail = 62.0;
constexpr double kDefaultVocalIrregularity = 5.0;

constexpr double kPi = 3.14159265358979323846;
constexpr int32 kMaximumChannels = 64;
constexpr int32 kTractUpdateInterval = 64;
const FUID kControllerUID(0xB91D37B5, 0x4A2F4F9D, 0x8E1B6C50, 0xD2E7A491);

double clamp01(double value) {
    return std::max(0.0, std::min(1.0, value));
}

double clampFrequency(double frequency, double sampleRate) {
    return std::max(80.0, std::min(frequency, sampleRate * 0.42));
}

class Biquad {
public:
    void reset() {
        x1_ = x2_ = y1_ = y2_ = 0.0;
    }

    void highPass(double sampleRate, double frequency, double q) {
        const double omega = 2.0 * kPi * clampFrequency(frequency, sampleRate) / sampleRate;
        const double cosine = std::cos(omega);
        const double alpha = std::sin(omega) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        set((1.0 + cosine) * 0.5 / a0, -(1.0 + cosine) / a0,
            (1.0 + cosine) * 0.5 / a0, -2.0 * cosine / a0,
            (1.0 - alpha) / a0);
    }

    void peaking(double sampleRate, double frequency, double q, double gainDb) {
        const double omega = 2.0 * kPi * clampFrequency(frequency, sampleRate) / sampleRate;
        const double a = std::pow(10.0, gainDb / 40.0);
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
        b0_ = b0;
        b1_ = b1;
        b2_ = b2;
        a1_ = a1;
        a2_ = a2;
    }

    double b0_ {1.0};
    double b1_ {0.0};
    double b2_ {0.0};
    double a1_ {0.0};
    double a2_ {0.0};
    double x1_ {0.0};
    double x2_ {0.0};
    double y1_ {0.0};
    double y2_ {0.0};
};

struct ChannelState {
    Biquad rumbleFilter;
    Biquad trachealResonance;
    Biquad oecResonance;
    Biquad tongueFormantLow;
    Biquad tongueFormantHigh;
    Biquad beakFormant;
    Biquad consonantHighPass;

    double fastEnvelope {0.0};
    double slowEnvelope {0.0};
    double highBandEnvelope {0.0};
    double articulation {0.0};

    double smoothedMix {kDefaultMix / 100.0};
    double smoothedSyrinxEdge {kDefaultSyrinxEdge / 100.0};
    double smoothedTrachealLength {kDefaultTrachealLength / 100.0};
    double smoothedOecVolume {kDefaultOecVolume / 100.0};
    double smoothedTonguePosition {kDefaultTonguePosition / 100.0};
    double smoothedBeakOpening {kDefaultBeakOpening / 100.0};
    double smoothedFormantStrength {kDefaultFormantStrength / 100.0};
    double smoothedConsonantDetail {kDefaultConsonantDetail / 100.0};
    double smoothedVocalIrregularity {kDefaultVocalIrregularity / 100.0};

    double irregularPhaseA {0.0};
    double irregularPhaseB {1.7};
    int32 filterUpdateCounter {0};

    void reset() {
        rumbleFilter.reset();
        trachealResonance.reset();
        oecResonance.reset();
        tongueFormantLow.reset();
        tongueFormantHigh.reset();
        beakFormant.reset();
        consonantHighPass.reset();

        fastEnvelope = 0.0;
        slowEnvelope = 0.0;
        highBandEnvelope = 0.0;
        articulation = 0.0;
        smoothedMix = kDefaultMix / 100.0;
        smoothedSyrinxEdge = kDefaultSyrinxEdge / 100.0;
        smoothedTrachealLength = kDefaultTrachealLength / 100.0;
        smoothedOecVolume = kDefaultOecVolume / 100.0;
        smoothedTonguePosition = kDefaultTonguePosition / 100.0;
        smoothedBeakOpening = kDefaultBeakOpening / 100.0;
        smoothedFormantStrength = kDefaultFormantStrength / 100.0;
        smoothedConsonantDetail = kDefaultConsonantDetail / 100.0;
        smoothedVocalIrregularity = kDefaultVocalIrregularity / 100.0;
        irregularPhaseA = 0.0;
        irregularPhaseB = 1.7;
        filterUpdateCounter = 0;
    }
};
} // namespace

// Audacity asks the VST3 edit controller for parameter metadata. Keep the
// controller separate from the audio processor so the host can expose sliders.
class ParrotVoiceToneController final : public EditController {
public:
    static FUnknown* createInstance(void*) {
        return static_cast<IEditController*>(new ParrotVoiceToneController());
    }

    tresult PLUGIN_API initialize(FUnknown* context) SMTG_OVERRIDE {
        const tresult result = EditController::initialize(context);
        if (result != kResultOk) {
            return result;
        }

        parameters.addParameter(new RangeParameter(
            USTRING("Parrot Blend"), kParrotMix, USTRING("%"), 0.0, 100.0,
            kDefaultMix, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Syrinx Edge"), kSyrinxEdge, USTRING("%"), 0.0, 100.0,
            kDefaultSyrinxEdge, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Tracheal Length"), kTrachealLength, USTRING("%"), 0.0, 100.0,
            kDefaultTrachealLength, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("OEC Volume"), kOecVolume, USTRING("%"), 0.0, 100.0,
            kDefaultOecVolume, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Tongue Position"), kTonguePosition, USTRING("%"), 0.0, 100.0,
            kDefaultTonguePosition, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Beak Opening"), kBeakOpening, USTRING("%"), 0.0, 100.0,
            kDefaultBeakOpening, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Formant Strength"), kFormantStrength, USTRING("%"), 0.0, 100.0,
            kDefaultFormantStrength, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Consonant Detail"), kConsonantDetail, USTRING("%"), 0.0, 100.0,
            kDefaultConsonantDetail, kStepCountContinuous, ParameterInfo::kCanAutomate));
        parameters.addParameter(new RangeParameter(
            USTRING("Vocal Irregularity"), kVocalIrregularity, USTRING("%"), 0.0, 100.0,
            kDefaultVocalIrregularity, kStepCountContinuous, ParameterInfo::kCanAutomate));

        return kResultOk;
    }
};

class ParrotVoiceTone final : public AudioEffect {
public:
    ParrotVoiceTone() {
        setControllerClass(kControllerUID);
    }

    static FUnknown* createInstance(void*) {
        return static_cast<IAudioProcessor*>(new ParrotVoiceTone());
    }

    tresult PLUGIN_API initialize(FUnknown* context) SMTG_OVERRIDE {
        const tresult result = AudioEffect::initialize(context);
        if (result != kResultOk) {
            return result;
        }

        addAudioInput(STR16("Audio In"), SpeakerArr::kStereo);
        addAudioOutput(STR16("Audio Out"), SpeakerArr::kStereo);
        return kResultOk;
    }

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

        auto* inputBus = FCast<AudioBus>(audioInputs.at(0));
        auto* outputBus = FCast<AudioBus>(audioOutputs.at(0));
        if (!inputBus || !outputBus) {
            return kResultFalse;
        }

        inputBus->setArrangement(inputs[0]);
        outputBus->setArrangement(outputs[0]);
        return kResultOk;
    }

    tresult PLUGIN_API setupProcessing(ProcessSetup& setup) SMTG_OVERRIDE {
        const tresult result = AudioEffect::setupProcessing(setup);
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

            const double normalized = clamp01(value);
            switch (queue->getParameterId()) {
                case kParrotMix: mix_ = normalized; break;
                case kSyrinxEdge: syrinxEdge_ = normalized; break;
                case kTrachealLength: trachealLength_ = normalized; break;
                case kOecVolume: oecVolume_ = normalized; break;
                case kTonguePosition: tonguePosition_ = normalized; break;
                case kBeakOpening: beakOpening_ = normalized; break;
                case kFormantStrength: formantStrength_ = normalized; break;
                case kConsonantDetail: consonantDetail_ = normalized; break;
                case kVocalIrregularity: vocalIrregularity_ = normalized; break;
                default: break;
            }
        }
    }

    template <typename Sample>
    void processBuffers(Sample** inputs, Sample** outputs, int32 inputChannels,
                        int32 outputChannels, int32 sampleCount) {
        const int32 channelCount = std::min(inputChannels, outputChannels);

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

    void updateTractFilters(ChannelState& state) {
        // Longer tracheal paths lower the first resonance. A larger OEC cavity
        // lowers its principal resonance; tongue and beak positions move the
        // higher resonances independently.
        const double syllableMotion = (state.articulation - 0.30) * 0.16;
        const double trachealFrequency =
            (1320.0 - 520.0 * state.smoothedTrachealLength) * (1.0 + syllableMotion * 0.35);
        const double oecFrequency =
            (2580.0 - 980.0 * state.smoothedOecVolume) * (1.0 + syllableMotion * 0.45);
        const double tongueLowFrequency =
            (1780.0 + 1500.0 * state.smoothedTonguePosition) * (1.0 + syllableMotion * 0.55);
        const double tongueHighFrequency =
            (3350.0 + 2050.0 * state.smoothedTonguePosition) * (1.0 + syllableMotion * 0.70);
        const double beakFrequency =
            (4650.0 + 2550.0 * state.smoothedBeakOpening) * (1.0 + syllableMotion * 0.60);

        const double strength = state.smoothedFormantStrength;
        state.trachealResonance.peaking(sampleRate_, trachealFrequency, 1.65,
                                         0.5 + 2.8 * strength);
        state.oecResonance.peaking(sampleRate_, oecFrequency, 1.45,
                                    0.5 + 3.2 * strength);
        state.tongueFormantLow.peaking(sampleRate_, tongueLowFrequency, 1.35,
                                        0.8 + 3.4 * strength);
        state.tongueFormantHigh.peaking(sampleRate_, tongueHighFrequency, 1.20,
                                         0.4 + 2.5 * strength);
        state.beakFormant.peaking(sampleRate_, beakFrequency, 1.05,
                                   0.3 + 2.2 * strength * state.smoothedBeakOpening);
    }

    template <typename Sample>
    void processChannel(const Sample* input, Sample* output, int32 samples, ChannelState& state) {
        const double smoothing = 1.0 - std::exp(-1.0 / (sampleRate_ * 0.018));
        const double fastAttack = 1.0 - std::exp(-1.0 / (sampleRate_ * 0.003));
        const double fastRelease = 1.0 - std::exp(-1.0 / (sampleRate_ * 0.018));
        const double slowAttack = 1.0 - std::exp(-1.0 / (sampleRate_ * 0.020));
        const double slowRelease = 1.0 - std::exp(-1.0 / (sampleRate_ * 0.090));
        const double articulationSmoothing = 1.0 - std::exp(-1.0 / (sampleRate_ * 0.030));
        const double phaseStepA = 2.0 * kPi * 2.3 / sampleRate_;
        const double phaseStepB = 2.0 * kPi * 3.7 / sampleRate_;

        for (int32 i = 0; i < samples; ++i) {
            state.smoothedMix += (mix_ - state.smoothedMix) * smoothing;
            state.smoothedSyrinxEdge += (syrinxEdge_ - state.smoothedSyrinxEdge) * smoothing;
            state.smoothedTrachealLength += (trachealLength_ - state.smoothedTrachealLength) * smoothing;
            state.smoothedOecVolume += (oecVolume_ - state.smoothedOecVolume) * smoothing;
            state.smoothedTonguePosition += (tonguePosition_ - state.smoothedTonguePosition) * smoothing;
            state.smoothedBeakOpening += (beakOpening_ - state.smoothedBeakOpening) * smoothing;
            state.smoothedFormantStrength += (formantStrength_ - state.smoothedFormantStrength) * smoothing;
            state.smoothedConsonantDetail += (consonantDetail_ - state.smoothedConsonantDetail) * smoothing;
            state.smoothedVocalIrregularity += (vocalIrregularity_ - state.smoothedVocalIrregularity) * smoothing;

            const double dry = std::isfinite(static_cast<double>(input[i]))
                ? static_cast<double>(input[i]) : 0.0;

            const double consonant = state.consonantHighPass.process(dry);
            const double magnitude = std::abs(dry);
            const double fastCoefficient = magnitude > state.fastEnvelope ? fastAttack : fastRelease;
            const double slowCoefficient = magnitude > state.slowEnvelope ? slowAttack : slowRelease;
            state.fastEnvelope += (magnitude - state.fastEnvelope) * fastCoefficient;
            state.slowEnvelope += (magnitude - state.slowEnvelope) * slowCoefficient;

            const double highMagnitude = std::abs(consonant);
            const double highCoefficient = highMagnitude > state.highBandEnvelope ? fastAttack : fastRelease;
            state.highBandEnvelope += (highMagnitude - state.highBandEnvelope) * highCoefficient;

            const double articulationTarget = clamp01(
                std::abs(state.fastEnvelope - state.slowEnvelope) * 8.0
                + state.highBandEnvelope * 0.45);
            state.articulation += (articulationTarget - state.articulation) * articulationSmoothing;

            if (state.filterUpdateCounter == 0) {
                updateTractFilters(state);
            }
            state.filterUpdateCounter = (state.filterUpdateCounter + 1) % kTractUpdateInterval;

            // The source stage adds a small asymmetric component and a soft
            // third-harmonic bend. It enriches voiced peaks without quantizing
            // the waveform or moving the speaker's fundamental pitch.
            const double irregularity =
                0.70 * std::sin(state.irregularPhaseA)
                + 0.30 * std::sin(state.irregularPhaseB);
            state.irregularPhaseA += phaseStepA;
            state.irregularPhaseB += phaseStepB;
            if (state.irregularPhaseA >= 2.0 * kPi) state.irregularPhaseA -= 2.0 * kPi;
            if (state.irregularPhaseB >= 2.0 * kPi) state.irregularPhaseB -= 2.0 * kPi;

            const double edge = state.smoothedSyrinxEdge;
            const double sourceMotion = 1.0 + irregularity * edge * state.smoothedVocalIrregularity * 0.012;
            const double driven = dry * sourceMotion
                + edge * (0.16 * dry * dry - 0.20 * dry * dry * dry);
            const double source = dry + (driven - dry) * (0.25 + 0.35 * edge);

            double wet = state.rumbleFilter.process(source);
            wet = state.trachealResonance.process(wet);
            wet = state.oecResonance.process(wet);
            wet = state.tongueFormantLow.process(wet);
            wet = state.tongueFormantHigh.process(wet);
            wet = state.beakFormant.process(wet);

            // Keep unprocessed high-frequency articulation in parallel. This
            // prevents the moving resonances from smearing / swallowing s, f, t.
            wet += consonant * state.smoothedConsonantDetail * 0.22;

            double result = dry + (wet - dry) * state.smoothedMix;
            const double outputMagnitude = std::abs(result);
            if (outputMagnitude > 0.96) {
                result = std::copysign(
                    0.96 + 0.04 * std::tanh((outputMagnitude - 0.96) / 0.04),
                    result);
            }
            output[i] = static_cast<Sample>(result);
        }
    }

    double sampleRate_ {48000.0};
    int32 sampleSize_ {kSample32};

    double mix_ {kDefaultMix / 100.0};
    double syrinxEdge_ {kDefaultSyrinxEdge / 100.0};
    double trachealLength_ {kDefaultTrachealLength / 100.0};
    double oecVolume_ {kDefaultOecVolume / 100.0};
    double tonguePosition_ {kDefaultTonguePosition / 100.0};
    double beakOpening_ {kDefaultBeakOpening / 100.0};
    double formantStrength_ {kDefaultFormantStrength / 100.0};
    double consonantDetail_ {kDefaultConsonantDetail / 100.0};
    double vocalIrregularity_ {kDefaultVocalIrregularity / 100.0};

    std::array<ChannelState, kMaximumChannels> channels_ {};
};

} // namespace Vst
} // namespace Steinberg

using namespace Steinberg::Vst;

BEGIN_FACTORY_DEF("SilverWallDiscs", "https://github.com/SilverWallDiscs", "")

DEF_CLASS2(INLINE_UID(0xB91D37B4, 0x4A2F4F9D, 0x8E1B6C50, 0xD2E7A491),
           PClassInfo::kManyInstances,
           kVstAudioEffectClass,
           "Parrot Voice Tone",
           0,
           "Fx",
           "0.4.0",
           kVstVersionString,
           ParrotVoiceTone::createInstance)

DEF_CLASS2(INLINE_UID(0xB91D37B5, 0x4A2F4F9D, 0x8E1B6C50, 0xD2E7A491),
           PClassInfo::kManyInstances,
           kVstComponentControllerClass,
           "Parrot Voice Tone Controller",
           0,
           "",
           "0.4.0",
           kVstVersionString,
           ParrotVoiceToneController::createInstance)

END_FACTORY
