cmake_minimum_required(VERSION 3.10)
project(yaya_llama)

# STUB 默认；-PenableLlamaCpp=true 传入 -DLLAMA_ENABLE=ON 后才编译 llama.cpp 全量推理
option(LLAMA_ENABLE "Build full llama.cpp inference" OFF)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama_cpp")

add_library(yaya_llama SHARED llama_jni.cpp)

if(LLAMA_ENABLE)
    if(NOT EXISTS "${LLAMA_CPP_DIR}/llama.h")
        message(FATAL_ERROR
            "-PenableLlamaCpp=true 已启用，但缺少 llama.cpp 源码：请放到 ${LLAMA_CPP_DIR}/")
    endif()
    add_subdirectory(${LLAMA_CPP_DIR} llama_build)
    target_include_directories(yaya_llama PRIVATE ${LLAMA_CPP_DIR})
    target_link_libraries(yaya_llama llama)
    message(STATUS "yaya_llama: FULL llama.cpp inference enabled")
else()
    target_compile_definitions(yaya_llama PRIVATE LLAMA_STUB=1)
    message(STATUS "yaya_llama: STUB build (default), pass -PenableLlamaCpp=true for full inference")
endif()

find_library(log-lib log)
target_link_libraries(yaya_llama ${log-lib})
