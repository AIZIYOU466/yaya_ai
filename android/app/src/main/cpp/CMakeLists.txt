cmake_minimum_required(VERSION 3.10)
project(yaya_llama)

# llama.cpp JNI 桥接库
# 注意：需要预下载 llama.cpp 源码到 llama_cpp_dir
# 当前为 stub 实现，CI 构建时如无 llama.cpp 源码则跳过

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama_cpp")
set(LLAMA_AVAILABLE FALSE)

if(EXISTS "${LLAMA_CPP_DIR}/llama.h")
    set(LLAMA_AVAILABLE TRUE)
    add_subdirectory(${LLAMA_CPP_DIR} llama_build)
endif()

add_library(yaya_llama SHARED llama_jni.cpp)

if(LLAMA_AVAILABLE)
    target_include_directories(yaya_llama PRIVATE ${LLAMA_CPP_DIR})
    target_link_libraries(yaya_llama llama)
else()
    # Stub: 不链接 llama，函数返回空结果
    # 构建不会失败，运行时 JNI 调用返回 stub 结果
    target_compile_definitions(yaya_llama PRIVATE LLAMA_STUB=1)
endif()

find_library(log-lib log)
target_link_libraries(yaya_llama ${log-lib})
