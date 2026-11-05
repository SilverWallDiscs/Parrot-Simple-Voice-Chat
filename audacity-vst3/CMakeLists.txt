# The SDK does not compile this helper into its shared "sdk" library.
# Steinberg's own AGain-Simple example adds it to the plug-in sources.
smtg_add_vst3plugin(
    ParrotVoiceTone
    ${SDK_ROOT}/public.sdk/source/vst/vstsinglecomponenteffect.cpp
    source/ParrotVoiceTone.cpp
)
target_compile_features(ParrotVoiceTone PRIVATE cxx_std_17)
target_link_libraries(ParrotVoiceTone PRIVATE sdk)
