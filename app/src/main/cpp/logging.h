//
// Created by Han Yin on 10/31/25.
//

#ifndef AICHAT_LOGGING_H
#define AICHAT_LOGGING_H

#endif //AICHAT_LOGGING_H

#pragma once
#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "ai-chat"
#endif

#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

static inline int ai_should_log(int prio) {
    // 本 App 的 minSdk 是 26，官方示例是 33——__android_log_is_loggable 是 API 30 才有的，
    // 低于 30 的部署目标下 NDK 头文件把它标成 unavailable，直接编译不过。
    // 退化成同一个语义里不依赖系统属性的那半边：prio 达到 LOG_MIN_LEVEL 就记。
    // （__android_log_is_loggable 只是额外再读一次 LOG_TAG 的系统属性下限，这里读不到，影响仅此。）
#if __ANDROID_API__ >= 30
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
#else
    return prio >= LOG_MIN_LEVEL;
#endif
}

#if LOG_MIN_LEVEL <= ANDROID_LOG_VERBOSE
#define LOGv(...) do { if (ai_should_log(ANDROID_LOG_VERBOSE)) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGv(...) ((void)0)
#endif

#if LOG_MIN_LEVEL <= ANDROID_LOG_DEBUG
#define LOGd(...) do { if (ai_should_log(ANDROID_LOG_DEBUG)) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGd(...) ((void)0)
#endif

#define LOGi(...)   do { if (ai_should_log(ANDROID_LOG_INFO )) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGw(...)   do { if (ai_should_log(ANDROID_LOG_WARN )) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGe(...)   do { if (ai_should_log(ANDROID_LOG_ERROR)) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)

static inline int android_log_prio_from_ggml(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default:                   return ANDROID_LOG_DEFAULT;
    }
}

static inline void aichat_android_log_callback(enum ggml_log_level level,
                                              const char* text,
                                              void* /*user*/) {
    const int prio = android_log_prio_from_ggml(level);
    if (!ai_should_log(prio)) return;
    __android_log_write(prio, LOG_TAG, text);
}
