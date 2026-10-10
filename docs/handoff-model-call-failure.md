# 交接：端侧模型「加载后调用不了」排查与改造清单

日期：2026-10-10 · 仓库：`~/projects/ai-bookkeeping` · 当前版本 0.19.0

> 证据等级：带 `file:line` 的都是**静态读码**得出的结论；「卡死路径」是**推断**，
> 需按第六节抓一次现场日志确认。本轮**没有在设备上复现**。

---

## 一、现象

设置页点「加载模型」显示已加载，但用模型的地方（记一笔解析、阿账对话、AI 悬浮面板）
**没有走模型**——静默退回规则/固定话术，界面不给具体原因，报错只在 logcat 里。

用户要求：**所有失败路径改成界面上可读的前端报错**（logcat 只留细节，不再是唯一出口）。

---

## 二、根因 1（主）：同一个进程里有两套「模型属主」，系统提示还不一致

| 谁 | 代码 | 加载方式 | 系统提示 | 状态记在哪 |
|---|---|---|---|---|
| 设置页 | `feature/settings/OnDeviceModelSection.kt:103-117` 自建 `SparkLlm(engine)` | `llm.init(path)`（`:184`） | `SparkLlm.DEFAULT_SYSTEM_PROMPT`（`llm/SparkLlm.kt:75-77`） | 私有 `loadedPath`（`:117`） |
| 其余全部功能 | `llm/SparkSession.kt`（KDoc 自称「**端侧模型的唯一属主**」，`:19-28`） | `ensureReady()`（`:92-123`） | 含科目表枚举的自定义提示（`:164-181`） | `loadedKey`（`:49`） |

引擎的硬约束（`com/arm/aichat/internal/InferenceEngineImpl.kt`）：

- `loadModel` 只允许在 `Initialized` 状态调用（`:150-154`）
- `setSystemPrompt` **必须紧跟 loadModel、且只此一次**（`:191-197` 的 `check`）
- `sendUserPrompt` 只允许在 `ModelReady`（`:217-224`）
- `cleanUp()` 只处理 `ModelReady` / `Error` 两种状态，**其它状态直接抛**
  `IllegalStateException`（`:281-305`）

**「系统提示必须紧跟加载」是引擎自身的约束，不是本项目代码的坑**，出处直接看源码：

```kotlin
// com/arm/aichat/internal/InferenceEngineImpl.kt:193-197
require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
check(_state.value is InferenceEngine.State.ModelReady) {
    "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
}
```

`_readyForSystemPrompt` 只在 `loadModel` 成功后置 `true`（`:175`），`setSystemPrompt`
一进门就置 `false`（`:200`）——所以同一份模型**只能带一套系统提示**，换提示必须整轮
卸载重载。`com/arm/aichat/**` 是官方 Kotlin 推理层**原样引入**（git `3df21d1`
「接入官方 Kotlin 推理层（com.arm.aichat，7 个文件原样）」），这段状态机是上游设计，
**只能在它外面排队，不要试图改它的语义**（改了就要跟上游 diff 出叉）。

于是必然发生：

1. 设置页加载成功 → 引擎 `ModelReady`，但 `SparkSession.loadedKey == null`；
2. 用户去记一笔/阿账/悬浮面板 → `ensureReady()` 判定「没加载过」，
   且当前状态不是 `Initialized` → 先 `llm.free()` 卸载，再用**另一套系统提示**
   重新加载 1GB 模型 + 重新解码提示词（模拟器实测 **65 秒**，见 `SparkSession.kt:22-28`）；
3. 这期间用户发的任何消息都在等这把锁，超时就降级；
4. 两套状态互不可见：聊天那边 `unload` 掉之后，设置页 `loadedPath` 仍是旧值，
   页面显示「已加载」，其实引擎已经换了主人。

**一句话：设置页的「加载成功」是一次无效加载**——因为提示词不同，下一次真正要用时
还得整个重来一遍。

## 三、根因 2：失败全被吞进 logcat，界面只剩一句固定话术

- `SparkSession.ensureReady()` 失败 → `catch (Throwable)` → `Log.w` → `return false`（`:117-121`）
- `SparkAiParser` → `Log.w` + 回落规则解析（`feature/entry/SparkAiParser.kt:76-86`）
- `AzhangChat` → `Log.i` + 返回 `Unavailable`（`feature/chat/AzhangChat.kt:87-99,120-124`）
- 悬浮面板兜底文案是**写死的** `FAILED_TEXT`（`feature/chat/AiFloatPanel.kt:354-355`）：
  「这次没答上来（模型还没准备好或超时了）」，状态栏只显示「这次没答上」（`:161`）
- 唯一有像样报错的是设置页自己（`OnDeviceModelSection.kt:328-329`）

结果就是用户看到的「调不了、也不报错」。

## 四、根因 3（推断，需日志确认）：超时取消后引擎状态卡死

调用方都用 `withTimeoutOrNull` 卡超时（`SparkAiParser.kt:63`、`AzhangChat.kt:84`、
`AiFloatPanel.kt:97`），但取消只能停协程，**停不掉阻塞中的 JNI**。若状态停在
`ProcessingSystemPrompt` / `Generating`：

- `cleanUp()` 走到 `else -> throw`（`InferenceEngineImpl.kt:304`）→ 被 `runCatching` 吞掉；
- 紧接着 `loadModel` 因状态不是 `Initialized` 而 `check` 失败（`:152`）→ 也被吞；
- `ensureReady()` 每次都 `return false`，**直到进程重启**。

代码注释里记过同类事故：`SparkSession.kt:53-58`「首轮批量评估就是这么把 35 条全打成
规则兜底的」。

另一处并发缺口：`ensureReady` 有 mutex，但 `complete` / `stream` **没有互斥**
（`SparkSession.kt:126-141`），设置页和聊天页可以同时 `sendUserPrompt` → 第二个必然
`check(ModelReady)` 抛异常（`InferenceEngineImpl.kt:222`）。

---

## 五、要改什么（按优先级）

1. **收敛成一个属主（硬约束，不是可选优化）。** 加载与推理入口**只能**有
   `SparkSession` 一个属主；设置页 `OnDeviceModelViewModel` 改成注入 `SparkSession`，
   自己降级为**纯展示页：查询状态 + 触发加载**，删掉自建的 `SparkLlm` 与
   私有 `loadedPath`：
   - 加载 = `session.ensureReady()`（用统一 systemPrompt）
   - 生成 = `session.llm` / `session.stream(...)`
   - 卸载 = `session.unload()`
   - 设置页状态从 `SparkSession` 暴露的 StateFlow 读（真源只有一处），
     不要再维护 `loadedPath` 副本。

   **为何必须收敛**：引擎是进程级单例（`AiChat.getInferenceEngine` 返回同一个对象），
   状态机只有一套。任何绕过属主直连 engine 的代码——包括下一次新加的功能——都会
   复刻同一个 bug：自己以为加载好了，别人一调用就卸载重载。这次只把设置页改掉而不
   立这条约束，第二个属主迟早还会长出来。建议在 `AGENTS.md` 约定区补一条
   「端侧模型只经 `SparkSession` 访问，禁止直接 new `SparkLlm`」。
2. **失败原因透出到界面。** `ensureReady(): Boolean` 改成返回原因
   （sealed：`NoModelFile` / `LoadFailed(msg)` / `InitTimeout(ms)` /
   `EngineBusy(state)` / `OutputInvalid`）；`AzhangTurn.Unavailable` 加 `reason` 字段；
   悬浮面板状态栏、聊天页、记一笔确认卡按原因显示**具体文案 + 建议动作**，例如：
   - 找不到模型文件 →「未找到模型，去设置页点『加载模型』」
   - 初始化超时 →「模型加载中（已等 Ns），稍后再发一条」
   - 引擎忙 →「模型正在生成，稍等」
   - 输出不合法 →「模型这次输出没听懂，已回落规则解析」
   并给一个「重试」入口。`logcat` 照记，但不再是唯一出口。
3. **生成互斥。** 把 `complete` / `stream` 也纳入 `SparkSession` 的同一把锁
   （或串行队列），设置页与聊天页不能同时打 JNI。
4. **状态自愈。** `ensureReady` 里发现引擎停在 `ProcessingSystemPrompt`/`Generating`
   超过阈值 → 透出「引擎状态异常」并提供重载按钮；不要每次静默 `free()` + 重载。
5. **真机 arm64 复验。** 目前所有证据来自 x86_64 模拟器（AGENTS.md 遗留第 3 条），
   真机上要确认 `.so` 分发与加载正常。

## 六、复现与抓日志

```bash
# 构建（模拟器要 x86_64）
tools/build.sh :app:testDebugUnitTest :app:assembleDebug
# 装完后清日志再复现
adb logcat -c && adb logcat | grep -E 'SparkSession|AzhangChat|SparkAiParser|SparkLlm|InferenceEngine|AndroidRuntime'
```

复现步骤：设置页「加载模型」→ 等到「已加载」→ 直接进阿账对话发一条 → 看是否出现
**第二次** `Model loaded!`、以及 `ensureReady` 的失败原因。

## 七、验收标准

**第 0 步（动手前，先花 10 分钟，必须留痕）：坐实或否定根因 3。**
按第六节抓日志，复现「超时后引擎状态卡死」那条路径。结果二选一，都要写回本文档：

- **复现了** → 根因 3 从「推断」升为正式根因，修复优先级提到最高
  （状态卡死要重启 App 才能恢复，比 65 秒降级更伤体验），并补写复现步骤与日志片段；
- **没复现** → 就地标注「2026-10-XX 未复现（设备/步骤），降级为观察项」，
  保留原推断文字，别删——下一个人不用再猜，也不用再花这 10 分钟。

- [ ] **A（回归硬指标，验证根因 1 修好了）**：设置页点「加载模型」→「已加载」→
  直接进阿账对话/记一笔/悬浮面板发一条 → **全程不发生卸载重载**。
  判据两条同时满足：
  1. logcat 里 `Model loaded!` **只出现一次**；
  2. 功能页首条消息**没有第二次 60 秒级卡顿**（重载必然伴随这个量级的停顿；
     仅用它做「有没有重载」的存在性判据，**数值本身不是性能基线**，见第八节）。
- [ ] B：反序也通（先在对话页用过模型，再进设置页点「生成」）
- [ ] C：删掉 `files/models/` 后调用 → 界面显示「找不到模型文件」，不是只有 logcat
- [ ] D：生成中再发一条 → 界面显示「模型忙」，不崩、不静默降级
- [ ] E：记一笔 / 阿账对话 / 悬浮面板三处失败时都能看到具体原因，与 logcat 内容一致
- [ ] F：`tools/build.sh :app:testDebugUnitTest` 全绿（当前基线 300+ 用例 0 失败）
- [ ] G：**至少一台中端真机（8GB 内存）上把 A-E 全跑一遍**——模拟器结论不采信（见第八节）

## 八、外面那份「通用排查文档」哪些不用查

| 通用文档说的 | 本项目情况 |
|---|---|
| 模型文件损坏/断点续传错位 | **不用查**：模型内置 APK，`ModelInstaller` 先写 `.part`、校验字节数才改名，半截文件不会叫正式名 |
| 4B Q4 约 2.3GB、需 3-4GB 空闲内存 | **不适用**：本项目是 Spark-X2.5 **1.7B** Q4_K_M，1.06GB |
| 先换小模型跑通全链路 | **不用查**：1.7B 已在模拟器实测生成成功（firstToken=8201ms，AGENTS.md 2026-10-09） |
| JNI 要独立线程、回调回主线程 | **已满足**：`InferenceEngineImpl` 自带单线程 dispatcher（`:124`）+ `flowOn`，Flow 逐 token 回主线程渲染 |
| 状态机管理、并发推理互斥 | **正是本项目根因 1 和根因 3**，按第五节改 |
| 真机 ABI/arm64 未验 | **要查，且必须在真机验收**：至少一台中端真机（**8GB 内存**）把第七节 A-E 全跑一遍，**模拟器结论一律不采信**。本项目目前所有实测证据（模型释放、生成成功、65 秒加载）都来自 x86_64 模拟器；其中 **65 秒只能当「有没有发生第二次加载」的存在性判据，不是性能基线**，任何验收标准/性能承诺都不要引用这个数 |
