cmake_minimum_required(VERSION 3.29.2)
project(ecocapture_qwen3vl LANGUAGES C CXX)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The verified Qwen runtime supports arm64-v8a only")
endif()
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(LLAMA_CPP_SOURCE_DIR "${REPO_ROOT}/tools/vlm-feasibility/artifacts/llama.cpp" CACHE PATH "Pinned llama.cpp checkout")
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/../../../LLAMA_CPP_COMMIT" PINNED_COMMIT LIMIT_COUNT 1)
find_package(Git REQUIRED)
execute_process(COMMAND "${GIT_EXECUTABLE}" -c "safe.directory=${LLAMA_CPP_SOURCE_DIR}"
    -C "${LLAMA_CPP_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE SOURCE_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE GIT_RESULT)
execute_process(COMMAND "${GIT_EXECUTABLE}" -c "safe.directory=${LLAMA_CPP_SOURCE_DIR}"
    -C "${LLAMA_CPP_SOURCE_DIR}" status --porcelain
    OUTPUT_VARIABLE SOURCE_CHANGES OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE STATUS_RESULT)
if(NOT GIT_RESULT EQUAL 0 OR NOT STATUS_RESULT EQUAL 0 OR
   NOT SOURCE_COMMIT STREQUAL PINNED_COMMIT OR NOT SOURCE_CHANGES STREQUAL "")
    message(FATAL_ERROR "Prepare the clean pinned source with tools/vlm-feasibility/prepare.ps1")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(FLAG GGML_NATIVE GGML_OPENMP GGML_LLAMAFILE GGML_CCACHE GGML_VULKAN GGML_CUDA GGML_METAL
    LLAMA_OPENSSL LLAMA_SUBPROCESS LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_EXAMPLES
    LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_TOOLS MTMD_VIDEO)
    set(${FLAG} OFF CACHE BOOL "" FORCE)
endforeach()
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
add_subdirectory("${LLAMA_CPP_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama")
add_library(ecocapture_qwen3vl SHARED qwen3vl_jni.cpp)
target_compile_features(ecocapture_qwen3vl PRIVATE cxx_std_17)
target_compile_definitions(ecocapture_qwen3vl PRIVATE ECOCAPTURE_RUNTIME_COMMIT="${PINNED_COMMIT}")
target_compile_definitions(ecocapture_qwen3vl PRIVATE ECOCAPTURE_NATIVE_BUILD_TYPE="${CMAKE_BUILD_TYPE}")
target_link_libraries(ecocapture_qwen3vl PRIVATE mtmd llama android log jnigraphics)
# Android 16 supports 16 KiB pages; do not produce a library limited to 4 KiB pages.
target_link_options(ecocapture_qwen3vl PRIVATE "-Wl,-z,max-page-size=16384")
