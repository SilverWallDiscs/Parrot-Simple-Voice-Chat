smtg_add_vst3plugin(
    ParrotVoiceTone
    source/ParrotVoiceTone.cpp
)
target_compile_features(ParrotVoiceTone PRIVATE cxx_std_17)
target_link_libraries(ParrotVoiceTone PRIVATE sdk)
