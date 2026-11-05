cmake_minimum_required(VERSION 3.25)
project(ParrotVoiceTonePlugin VERSION 0.3.2 LANGUAGES CXX)

if(NOT DEFINED VST3_SDK_ROOT OR NOT EXISTS "${VST3_SDK_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "Set VST3_SDK_ROOT to a recursively cloned Steinberg VST3 SDK checkout.")
endif()

set(SMTG_ENABLE_VSTGUI_SUPPORT OFF CACHE BOOL "" FORCE)
set(SMTG_ENABLE_VST3_PLUGIN_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SMTG_ENABLE_VST3_HOSTING_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SMTG_CREATE_PLUGIN_LINK OFF CACHE BOOL "" FORCE)

add_subdirectory("${VST3_SDK_ROOT}" "${CMAKE_BINARY_DIR}/vst3sdk")

smtg_add_vst3plugin(ParrotVoiceTone source/ParrotVoiceTone.cpp)
target_compile_features(ParrotVoiceTone PRIVATE cxx_std_17)
target_link_libraries(ParrotVoiceTone PRIVATE sdk)
