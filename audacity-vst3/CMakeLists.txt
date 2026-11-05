# This directory is added from the Steinberg VST3 SDK's top-level CMake build,
# after the SDK has created its shared targets and registered smtg_add_vst3plugin().
smtg_add_vst3plugin(ParrotVoiceTone source/ParrotVoiceTone.cpp)
target_compile_features(ParrotVoiceTone PRIVATE cxx_std_17)
target_link_libraries(ParrotVoiceTone PRIVATE sdk)
