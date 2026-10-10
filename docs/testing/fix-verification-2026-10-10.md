# 四项修复 · PM 验收复测记录（2026-10-10）

被测提交：`06f76ee` ① · `f64b369` ② · `2699e21` ③ · `9a94181` ④（装包 = 工作区最新构建，versionName 0.20.0 / versionCode 19）
环境：x86_64 模拟器 `emulator-5554`；**注意：本轮模型先按空间腾挪计划删除、经内置资产释放重建**（`/data` 常态只剩 0.5~1.5G）。

## 门槛（PM 亲自跑，不采信 Builder 自述）

| 项 | 结果 |
|---|---|
| `tools/build.sh -PincludeX86=true :app:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL |
| 单元测试 | **523 通过 / 0 失败**（基线 472，新增 51：`ChatRoutingTest`、`ChatEntryFallbackTest`、`ExtractReplyTest`、`TimeoutBudgetTest`、`SparkSessionPromptTest` 等） |
| 散落超时预算 | 仅剩统一常量定义处 `SparkSession.kt:337 GEN_BUDGET_MS=90_000`、`SparkAiParser.PARSE_TIMEOUT_MS=90_000`（同值）、一处 KDoc；无 20_000/45_000 残留 |
| `LocalAiParser` 在 `feature/chat` | 4 处命中均为 import/实例化/KDoc —— **复用，未新建词表** |
| FATAL | 全程 **0** |

## 逐项复测（模拟器实操）

### ① 聊天入口记账分流 —— **PASS**
- 操作：设置页释放+加载模型（100s 到「已加载」）→ 对话页点 chip `午饭花了 35`
- 结果：**25 秒出记账确认卡**（¥35 / 餐饮 / 午饭 / 今天，含「确认入账」「修改」）
- 关键证据：logcat 里模型 assistant 消息**原生吐出合法数组**，不是规则兜底：
  ```
  [{"kind":"expense","amount":35,"category":"餐饮","note":"午饭","payee":"","confidence":0.9}]
  ```
  （修复前 6/6 次不出数组；本轮无 `补救重试`、无 `回落规则解析` 日志）
- 截图：`docs/screenshots/2026-10-10-fixes/01-chat-entry-card.png`

### ② 统一超时预算 —— **PASS**
- 操作：对话页点 chip `这个月餐饮花了多少`
- 结果：**5 秒出答案**「本月支出（餐饮）￥92.50，共 5 笔」（与库内 5 笔/92.50 一致），答案带本地来源脚注
- 日志：`生成超时（20000ms）` **0 次**（修复前同场景 2 次连续超时）；三入口共用 `INIT_BUDGET_MS=240s` / `GEN_BUDGET_MS=90s`
- 截图：`docs/screenshots/2026-10-10-fixes/02-ask-answer.png`

### ③ 设置页订阅属主状态 —— **PASS（带一条遗留）**
- 正向：设置页「卸载」→ 状态行立即回「未加载 · 点「加载模型」」
- 反向：功能页（对话页）自动加载期间，设置页显示**属主的实时状态**「正在加载模型…」
  （修复前这里是一份自持旧状态、恒显「未加载」）
- 终态：进对话页触发预热认领后，设置页翻成**「已加载 · 可以生成了」**
- 截图：`03-settings-loading.png`（跟随中）/ `04-settings-synced-ready.png`（终态）
- **遗留**：首次加载的协程若被导航取消（用户中途按返回），引擎会自行跑完到 `ModelReady`
  但 `_loadedKey` 未置位 → `sessionStateOf` 判为 `Loading`，设置页**停在「正在加载」**
  直到任一功能页再调一次 `ensureReady`（实测进对话页 10s 内翻正）。代码注释称此态
  「不可能长期出现」，实测在导航取消场景下会出现数十秒~分钟级。是否再修，待定。

### ④ 容忍同义字段 —— **单测级 PASS，设备级未复现**
- `ExtractReplyTest` 覆盖 `content`/`text`/`message`/`data.reply` 放行、空串拒绝、数组不受影响；
- 设备上 `{"content":…}` 这一输出形态本轮没有再出现（模型输出分布随机），
  按「不强造场景」原则未做人工注入 —— 需要时可在补救重试路径人为构造。

### 综合判据
- `Model loaded!` = **1**（无重复加载）· `FATAL` = **0** · 账本/登录态全程未丢

## 环境备注（与产品缺陷分列）
- `/data` 反复吃紧：测试过程中产生过 1.1G 的 `/data/local/tmp/Spark-backup.gguf`（TC-04 备份，
  未自清）+ 1.0G 机上模型 → 本轮再次走「47M 小包过渡 → 删模型 → 装 1.1G 完整包」流程。
  **建议**：TC-04 的备份步骤改为用完即删，或恢复路径直接走内置资产（本次即如此）。
- 本记录全部为模拟器口径；**arm64 真机仍未验**（验收 G 未执行）。

---

## 追加：遗留1（加载被取消后设置页卡在「正在加载」）修复验收 · 2026-10-10 16:46

修复提交：`05c7bfb`（SparkSession 属主自愈监视器）· 版本：**0.21.1 (versionCode 21)**（`5dc42f5`）
门槛：`tools/build.sh -PincludeX86=true :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL，
**530 单测 0 失败**（新增 `SparkSessionClaimTest`，含安全底线「没见过 ProcessingSystemPrompt 的 ModelReady 不许认领」）。

### 复现-修复对照（同一脚本 repro4.sh，模拟器 x86_64）

| | 修复前（0.21.0，15:11-15:14） | 修复后（0.21.1，16:44-16:46） |
|---|---|---|
| 操作 | 卸载 → 对话页触发加载 → 立刻返回取消 | 同左 |
| 引擎日志 | `System prompt processed!`（自行跑完） | `System prompt processed!` |
| 属主动作 | 无（`_loadedKey` 停在 null） | `SparkSession: 调用方已取消，属主自行认领提示词` |
| **设置页状态** | **正在加载模型（首次读1GB+文件…）**（全程不翻正，需进对话页救场） | **已加载 · 可以生成了**（自行翻正，未进对话页） |
| FATAL | 0 | 0 |

- 判据脚本：`等待 System prompt processed 计数 2 → 3`（旧计数不掺和）后 8s 读设置页状态行。
- 截图：`05-before-fix-stuck.png`（修复前卡住） / `06-after-fix-selfhealed.png`（修复后自愈）
- 首轮复现脚本曾因「按出现过与否计数」误判就绪而截错状态，已修正为计数增长——记录在此以免后人把那张旧图当证据。
