cmake_minimum_required(VERSION 3.22.1)
project(bookkeeping LANGUAGES C CXX)

# llama.cpp 源码不在仓库里（体积与授权都不该进 git）。
# 默认用本机已 clone 的那份，可用环境变量 LLAMA_CPP_DIR 覆盖。
if(DEFINED ENV{LLAMA_CPP_DIR})
    set(LLAMA_CPP_DIR "$ENV{LLAMA_CPP_DIR}")
else()
    set(LLAMA_CPP_DIR "/home/dzsun/llama.cpp")
endif()
if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp 源码不在 ${LLAMA_CPP_DIR}，请设置环境变量 LLAMA_CPP_DIR")
endif()

# 只编核心库（libllama + ggml），不编 tools/examples/server/common/tests。
# 必须在 add_subdirectory 之前以 CACHE 形式定死，llama.cpp 的 option() 不会覆盖已有缓存值。
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)   # 静态编，最终整体链进一个 .so
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD   OFF CACHE BOOL "" FORCE)
# NDK 没有 OpenMP 运行库；BLAS/Vulkan/Metal 在 arm64 Android 上都不需要
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS   OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL  OFF CACHE BOOL "" FORCE)

# 静态库要链进共享库，必须是 PIC（llama.cpp 只在 BUILD_SHARED_LIBS=ON 时自己开）
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama_cpp" EXCLUDE_FROM_ALL)

# 链接探针：静态编出的 libllama/libggml 真正链进 APK 里的一个 .so。
# --whole-archive 是为了真的把核心库全部链一遍做校验——
# 只探一个符号的话，静态链接只会拉进那一个目标文件，等于没验。
# 本任务刻意到此为止：JNI 入口与推理封装是下一步的事。
add_library(native-lib SHARED native-lib.cpp)
target_include_directories(native-lib PRIVATE "${LLAMA_CPP_DIR}/include")
target_link_libraries(native-lib PRIVATE
    -Wl,--whole-archive
    llama
    -Wl,--no-whole-archive
)
