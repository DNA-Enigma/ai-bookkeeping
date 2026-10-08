// 链接探针，见 CMakeLists.txt 里的说明：只验证 libllama 能被链进 APK，不做任何封装。
#include "llama.h"

extern "C" __attribute__((visibility("default")))
const char * bk_llama_version() {
    return llama_version();
}
