# llama.cpp / Spark-X2.5 端侧集成实录

> 状态：**已在 x86_64 模拟器上端侧真跑通**（模型加载 + 真实推理 + 耗时可测）
> 关联提交：`a8ce77e`（JNI 移植）→ `3df21d1`（官方 Kotlin 推理层）→ `1848d25`（封装 + 修通）

## 一、链路

```
设置页「端侧模型」区块
  └─ OnDeviceModelViewModel        找模型 / 加载 / 生成 / 计时
      └─ SparkLlm                  init = loadModel + setSystemPrompt（原子）
          └─ ThinkingStripper      流式剔除思考段与控制 token
      └─ com.arm.aichat.AiChat     官方推理层（InferenceEngineImpl，单线程 dispatcher）
          └─ libai-chat.so (JNI)   ai_chat.cpp：模板应用、批处理、采样
              └─ libllama.so / libggml-cpu-*.so（运行时 dlopen 后端）
```

模型：`Spark-X2.5-1.7B-Q4_K_M.gguf`，`general.architecture = spark2_5`，1.05 GiB。
思考标记是 GGUF 里的 token[3]/token[4]（` Winthink` / ` Winthink`），
`tokenizer.chat_template` 是完整 Jinja 模板（宏 + namespace + `tojson`）。

## 二、踩到的三个坑（都是编译期看不出来的）

### 1. 后端 `.so` 没被解压 → `no backends are loaded`

```
E ai-chat : llama_model_load_from_file_impl: no backends are loaded.
            hint: use ggml_backend_load() … before calling this function
```

AGP 默认 `extractNativeLibs=false`，`nativeLibraryDir` 目录是**空的**（库只在 APK 里内存映射）。
`System.loadLibrary("ai-chat")` 不受影响，但 llama.cpp 是运行时
`ggml_backend_load_all_from_path(nativeLibraryDir)` 去 dlopen 后端库的 —— 找不到就一个后端都没加载。

修：`app/build.gradle.kts` → `packaging { jniLibs { useLegacyPackaging = true } }`。
**真机同样会踩**，装完可以 `ls $base/lib/arm64-v8a/ | grep ggml-cpu` 自查。

### 2. 非 Jinja 路径套不了这个模板 → SIGABRT 带走 App

```
Abort message: 'terminating due to uncaught exception of type std::runtime_error:
                this custom template is not supported, try using --jinja'
  #08 common_chat_templates_apply
  #09 common_chat_format_single(…, use_jinja=false)
  #11 Java_…_processSystemPrompt
```

`ai_chat.cpp` 硬编码 `use_jinja=false`，而 Spark-X2.5 的模板用了 Jinja 宏，
非 jinja 路径直接抛异常，JNI 边界没人接 → `SIGABRT`。

修：`chat_add_and_format()` 改 `use_jinja=true`，并 try/catch 兜底（失败降级成裸文本，不再崩）。
验证：logcat 中 `Formatted and added user message` 后能看到正确拼装的
`<｜start▁of▁sentence｜><|User|>…<｜end▁of▁sentence｜><｜start▁of▁sentence｜><|Bot|>`，
且全程 0 次 `template formatting failed`。

### 3. 内存被 OOM 杀（环境问题，不是代码）

Hermes 给每个后台任务的 cgroup 限了 **4 GiB**（`memory.max=4294967296`），
qemu 实占 4.16 GiB → 内核连杀三次模拟器。解法：启动后
`systemctl --user set-property hermes-worker-<id>.scope MemoryMax=8G`。
另外主机 swap 已满（4G/4G），重活并发跑会互相挤。

## 三、复现步骤

```bash
# 1) 构建（模拟器要 x86_64，加 -PincludeX86=true；真机不用加，只发 arm64）
cd ~/projects/ai-bookkeeping
export JAVA_HOME=$HOME/opt/android-toolchain/jdk PATH=$JAVA_HOME/bin:$PATH
export LLAMA_CPP_DIR=$HOME/llama.cpp CMAKE_BUILD_PARALLEL_LEVEL=3   # 16 核不限会把内存编爆
./gradlew :app:testDebugUnitTest -PincludeX86=true --max-workers=3   # 430 单测
./gradlew :app:assembleDebug     -PincludeX86=true --max-workers=3

# 2) 装 + 放模型（放 app 私有目录，免权限；外部 /sdcard/Android/data 目录
#    用 adb mkdir 建出来属 shell，app 读不到 —— 踩过）
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb push ~/projects/models/Spark-X2.5-1.7B-Q4_K_M.gguf /data/local/tmp/
adb shell "cat /data/local/tmp/spark.gguf | run-as dev.dzsun.bookkeeping \
           sh -c 'mkdir -p files/models && cat > files/models/Spark-X2.5-1.7B-Q4_K_M.gguf'"
adb shell rm /data/local/tmp/spark.gguf

# 3) 跑：设置 → 端侧模型 → 加载模型 → 生成（或用脚本驱动）
python3 run_onsite.py      # 冷启动→进设置→加载→生成→抓 logcat
```

## 四、实测数据（x86_64 模拟器，2026-10-09）

| 指标 | 数值 |
|---|---|
| 模型 | 1056 MB（1107457856 B，字节数与本机一致） |
| 加载 | 成功，`已加载 · 可以生成了` |
| 首 token | **7544 ms** |
| 全程 | **193332 ms** / 532 块 / 944 字 ≈ **2.75 token/s** |
| 单测 | **430 通过 / 0 失败**（基线 415 + 新增 15） |
| 证据 | `ondevice-run.png` 截图 + logcat `SparkLlm: generate done: …` |

生成耗时长有两个原因：① 模拟器 CPU 推理本来就慢；② 本次打满了 512 token 上限
（`DEFAULT_MAX_TOKENS=512`），把它调到 128 可把全程压到约 45 s。

## 五、已知不足（别在材料里说满）

1. **只有模拟器验证，arm64 真机未跑** —— 赛题要「真实端侧运行」，真机录屏是必做项。
2. **输出质量待调**：1.7B 模型会复述系统提示、自言自语；`ThinkingStripper` 只能剔除
   标准思考段，剔不掉不带标记的碎碎念。需要调提示词与 `DEFAULT_SAMPLER_TEMP`（现 0.3）。
3. **性能数字是模拟器口径**，不能当真机指标引用。
