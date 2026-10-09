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

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 按 ABI 定目标架构 —— 与官方 examples/llama.android/lib/src/main/cpp/CMakeLists.txt 同口径。
# （GGML_SYSTEM_ARCH 随后会被 ggml 自己的 ggml_get_system_arch() 按 CMAKE_SYSTEM_PROCESSOR 重算一遍，
#   这里设的值只用于下面给 ai-chat 的编译宏；GGML_CPU_KLEIDIAI 则是真的要生效的那个。）
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# ---- llama.cpp / ggml 的构建开关 ----
# 必须在 add_subdirectory 之前以 CACHE 形式定死：llama.cpp 的 option() 不会覆盖已有缓存值。
#
# 前面一组是官方示例在 examples/llama.android/lib/build.gradle.kts 里传的，为 Android 调过；
# 后面一组沿用本项目上一版的口径（NDK 没有的东西一律关掉）。
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)  # GGML_BACKEND_DL 要求 ON；ai-chat 也靠它链动态库
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)  # ai_chat.cpp 要 common.h/chat.h/sampling.h → llama-common
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD      OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)  # 交叉编译：宿主机 CPU ≠ 目标 CPU
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)  # 后端编成 .so，运行时按 SoC 能力挑 kernel
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)  # android_armv8.0~v9.2 七套 CPU backend
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)  # llamafile 不支持 Android
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)  # OpenSSL 不在 NDK stable native API 里
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)  # 移动端不支持 spawn 子进程（llama.cpp 自己也是 OFF）
set(LLAMA_LLGUIDANCE      OFF CACHE BOOL "" FORCE)  # ON 会去 ExternalProject 拉 cargo，交叉编译必挂
# docs/android.md 的 Android 交叉编译清单里就写着 GGML_OPENMP=OFF：NDK 虽然带 libomp，
# 但 CMake 装不了这个依赖，而且 AGP 只会打包 CMake 输出目录里的 .so，漏掉的 libomp.so
# 要到真机 loadLibrary 时才炸。ggml 的并行走自己的线程池，OpenMP 不是必需。
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_BLAS             OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(GGML_METAL            OFF CACHE BOOL "" FORCE)

# 静态库（llama-common-base）要链进共享库，必须是 PIC
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 不用 EXCLUDE_FROM_ALL：官方示例也没用它，后端 variant .so 要跟着 all 一起产出，
# 否则 apk 里没有 ggml-cpu-android_armv*.so，运行时挑不到 kernel。
add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama_cpp")

# ---- JNI 入口：官方 ai_chat.cpp ----
# 库名必须是 ai-chat —— InferenceEngineImpl 里是 System.loadLibrary("ai-chat")。
add_library(ai-chat SHARED ai_chat.cpp)

target_include_directories(ai-chat PRIVATE
    "${LLAMA_CPP_DIR}"
    "${LLAMA_CPP_DIR}/common"
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/ggml/src"
)

# 与官方示例一致的三个宏（ai_chat.cpp 目前没用到它们，留着是为了跟上游对齐，便于日后同步）
target_compile_definitions(ai-chat PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
    GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_link_libraries(ai-chat PRIVATE
    llama
    llama-common
    android
    log
)
