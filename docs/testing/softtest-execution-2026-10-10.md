# 端侧模型链路软测执行报告 · 2026-10-10

## 一、执行概况

| 项 | 值 |
|---|---|
| App 版本 | **0.20.0 (versionCode 19)** —— `dumpsys package dev.dzsun.bookkeeping` 实测 |
| 待测构建含改动 | `a4d65d7` 收敛端侧模型属主 · `ff37590` 提示词加固+补救重试+纯文本兜底 · `8459144` 数字护栏「元」须紧跟金额 |
| 执行者 | 测试执行工程师（Hermes 子代理），只测不改产品代码 |
| 执行时间 | 2026-10-10 10:24 起（CST） |
| 环境 | x86_64 Android 模拟器 `emulator-5554`（1080×2400），包名 `dev.dzsun.bookkeeping` |
| 模型 | `Spark-X2.5-1.7B-Q4_K_M.gguf`，1107457856 字节（≈1056 MB） |
| 新包核验 | APK dex 中 `未找到模型文件`、`模型引擎状态异常`、`回落规则解析`、`本机模型这次没接上` 均命中 → 非旧包 |
| 库基线 N0 | `select count(*) from journal` = **523**（2026-10-10 10:24） |
| 开工时状态 | `files/models/` **为空**（PM 已按授权删除模型），TC-01 走「内置资产释放」路径；登录态 softtest@local.test 有效 |
| /data 剩余 | 2.5G（释放模型后余 ~1.4G） |

> ⚠️ 全部结论仅 **x86_64 模拟器**有效，不采信为真机指标（TC-21 同口径）。

## 二、24 条用例执行结果

| 用例号 | 结论 | 一句话观察 | 证据文件路径 |
|---|---|---|---|
| TC-01 | PASS | 设置页点「加载模型」后状态行 `未加载 → 加载中… → 已加载 · 可以生成了`（t=103s，内置资产释放），`Model loaded!`=1、`System prompt processed!`=1、FATAL=0，模型字节数 1107457856、`.part`=0 | docs/screenshots/2026-10-10/TC01-before-load.png、TC01-ready.png；/tmp/tc01_log.txt |
| TC-02 | PASS | 设置页加载→预热 45s→面板发 `hello`，全程 `Model loaded!`=1、`Unloading model`=0、FATAL=0，发送后 7s 状态由「正在准备本机模型…」变化（≤20s），终态正文「您好，请说明您想记录的内容或需求。」 | docs/screenshots/2026-10-10/TC02-1-settings-loaded.png、TC02-2-panel-open.png、TC02-3-terminal.png；/tmp/tc02_log.txt |
| TC-03 | **FAIL** | 功能页先加载模型后，设置页状态行仍显示 `未加载 · 点「加载模型」`，而点「生成」却直接出结果（firstToken=8171ms，全窗口 `Model loaded!` 仍=1）——状态不一致；另：对话页卡片 146s 未出（系统提示词解码 149s），导航离开时 `Assistant generation's flow collection cancelled.` | docs/screenshots/2026-10-10/TC03-1-chatpage.png、TC03-2-card.png、TC03-3-settings.png、TC03-4-generate-result.png；/tmp/tc03_log.txt |
| TC-06 | **FAIL** | 点 chip `午饭花了 35` 后模型输出 `{"reply":"查账"}`（logcat `Formatted and added assistant message … {"reply":"查账"}`），被当成查账信号，界面只给兜底句「这个得看你的真实账本，我不编数字。」，**未出记账卡**；库 N0=523 → 523（期望 524），FATAL=0 | docs/screenshots/2026-10-10/TC06-1-chatpage.png、TC06-2-card.png；/tmp/tc06_log.txt |
| TC-17 | **FAIL** | 面板输入 `28` 冷启动下 92s 超时（界面「这次生成超时了（超过 90 秒没出结果）」，logcat 系统提示词解码 10:56:10→10:57:48 = 98s > 90s 超时阈值）；降级走对话页 chip `午饭花了 35` 再次得到 `{"reply":"查账"}` → 仍无卡，库 523→523 | docs/screenshots/2026-10-10/TC17-1-panel-result.png、TC17-3-chat-card.png；/tmp/tc17_log.txt、/tmp/tc17b_log.txt |
| TC-07 | PASS | 问账 chip `这个月餐饮花了多少` → 「本月支出（餐饮） ¥92.50，共 5 笔…这句话是按本地规则理解的，没有经过 AI 服务」；sqlite3 复算 expense.food 本月 = **5 笔 / 92.50**，逐字对上；FATAL=0 | docs/screenshots/2026-10-10/TC07-1-before.png、TC07-2-answer.png；/tmp/tc07_log.txt；复算脚本 ~/.hermes/cache/scratch/softtest/tc07_recalc.py |
| TC-15 | PASS | 设置页重走加载（102s 到「已加载」）→ 面板「去问账页」静置 45s → 示例卡 `这个月花了多少` 6s 出答案「本月支出 ¥235.55，共 19 笔」（库复算 235.55 / 19 笔一致）；全窗口 `Model loaded!`=1、`Unloading model`=0、FATAL=0 | docs/screenshots/2026-10-10/TC15-1-askpage.png、TC15-2-answer.png；/tmp/tc15_log.txt |
| TC-09 | PASS | 设置页加载到「已加载」（103s）后点「生成」，预填文案「用一句话说明记一笔「打车28元」该怎么分类。」不变；状态行 `首 token 8186 ms · 全程 18177 ms · 28 块 · 55 字`，logcat `OnDeviceModel: generate done: firstToken=8186ms total=18177ms chunks=28` **数字逐字一致**，正文非空非报错；`Model loaded!`=1、Unloading=0、FATAL=0 | docs/screenshots/2026-10-10/TC09-1-loaded.png、TC09-2-result.png；/tmp/tc09_log.txt |
| TC-13 | PASS | ② 面板发过一条后设置页状态行仍「已加载 · 可以生成了」，`Model loaded!` 1→1 不增、`Unloading model`=0；③ 点「卸载」→ 状态行回「未加载 · 点「加载模型」」，`Unloading model`=1；④ 回面板再发 `hello` → 自动重载（`Model loaded!` 1→2，属预期）并**给出具体原因**「这次生成超时了（超过 90 秒没出结果）」（冷加载 98s + 解码 > 90s 超时阈值，同 TC-17 根因）；FATAL=0 | docs/screenshots/2026-10-10/TC13-1-loaded.png、TC13-2-panel-ok.png、TC13-3-settings-after-panel.png、TC13-4-unloaded.png、TC13-5-panel-after-unload.png；/tmp/tc13a_partial.txt、/tmp/tc13_log.txt |
| TC-04 | PASS | 删模型（先备份 `/data/local/tmp/Spark-backup.gguf` 1107457856 字节）后冷启动发 `hello`：面板失败区显示**具体原因**「未找到模型文件，去设置页点「加载模型」」，logcat `AzhangChat: 模型未就绪，流式聊天走降级：未找到模型文件，去设置页点「加载模型」`——**界面文案与 logcat 逐字一致**；对话页 chip `这个月咖啡多少钱` 由本地规则直接答「没有记录」+来源脚注；FATAL=0、`Model loaded!`=0 | docs/screenshots/2026-10-10/TC04-1-panel-fail.png、TC04-2-chat-reason.png；/tmp/tc04_log.txt |
| TC-14 | PASS | 缺模型态下 chip `午饭花了 35` → **额外**出现原因气泡「本机模型这次没接上：未找到模型文件，去设置页点「加载模型」」，且记账走规则出卡「已为你解析，确认后入账：¥35 餐饮/午饭」；chip `给我省钱建议`（需横滑露出）→ 规则回复「省钱这事得看你的真实账目，我不编数字。」页面不哑；logcat `AzhangChat: 模型未就绪，聊天走降级：未找到模型文件…` 与气泡逐字一致；FATAL=0、`Model loaded!`=0 | docs/screenshots/2026-10-10/TC14-2-answer1.png、TC14-3-answer2.png、TC14-4-money-saving.png；/tmp/tc14_log.txt、/tmp/tc14b_log.txt |
| TC-19 | **PASS（恢复成功）** | 缺模型态进设置页点「加载模型」→ 状态行 `85%（释放进度） → 加载中… → 已加载 · 可以生成了`（t=109s）；字节数 **1107457856**、`.part` 残留 **0**；`Model loaded!`=1、Unloading=0、FATAL=0；TC-02 判据重新可跑 | docs/screenshots/2026-10-10/TC19-1-released.png、TC19-2-ready.png；/tmp/tc19_log.txt |
| TC-11 | PASS | ① 忙态：状态行 `正在准备本机模型...` 时发送键变 **`…`**，此时输入框已敲入 `world` 但**点不动、没发出**（终态后 `world` 仍在框内，第一条进度未被顶掉）；第一条冷启动 90s 超时终态 `这次没答上` + `这次生成超时了（超过 90 秒没出结果）`（**已知问题①**，非并发缺陷）；③ 对话页连点两 chip `午饭花了 35`→`这个月咖啡多少钱`，两条**都**拿到回复（咖啡 11:44 走本地规则、午饭 11:47 走 `{"reply":"查账"}` 降级话术），无静默吞、无崩溃；`Model loaded!`=1、`Unloading model`=0、FATAL=0（两份日志一致） | docs/screenshots/2026-10-10/TC11-1-busy.png、TC11-2-second-attempt.png、TC11-3-after.png、TC11-4-chat-double.png、TC11-5-chat-both.png、TC11-6-chat-late.png；/tmp/tc11_log.txt、/tmp/tc11b_log.txt |
| TC-10 | PASS | 设置页「卸载」后状态行**立刻**回 `未加载 · 点「加载模型」`（截图 11:49，无旧文案残留）→ 马上点「生成」→ logcat `Unloading model`=1、`Model loaded!` **1→2**（自动重载，属预期）、`System prompt processed!`（11:49:52→11:51:29 = 97s）→ `OnDeviceModel: generate done: firstToken=8278ms total=15431ms chunks=21`，界面状态行 `首 token 8278 ms·全程15431 ms·21块·42字` 与 logcat **逐字一致**，正文「该分类为支出，category为"交通"，amount为28元，note为打车费用。」；终态状态行 `已加载·可以生成了`；FATAL=0。注：加载/生成期间按钮置灰**未取到截图**（轮询间隔内错过），仅记录不判失败 | docs/screenshots/2026-10-10/TC10-1-unloaded.png、TC10-2-after-generate.png、TC10-3-restored.png；/tmp/tc10_log.txt |
| TC-05 | PASS | 冷启动面板发 `hello`：状态行 `正在准备本机模型…` → **92s** 转 `这次没答上`，正文显示**具体原因**「这次生成超时了（超过 90 秒没出结果）。可以点「重试」再发一次，或点下面的入口去对话页。」（非修复前那句通用兜底），失败区第一个 chip = `重试`；另一路（TC-22 的 `28`）触发的非 JSON 降级同样在正文显示真实原因 `模型输出不是合法 JSON（已补救重试 1 次）：{"content": "28是一个表示数量的数字，…}`，与 logcat `AzhangChat: 补救后仍不可解析，走降级：…` **同源同因**；`Model loaded!`=1、Unloading=0、FATAL=0。**注**：修复后 logcat 关键字已由用例集写的 `无法解析模型输出:` 改为 `对话输出不是 JSON，补救重试 1/1:`（本轮 0 次命中前者） | docs/screenshots/2026-10-10/TC05-1-panel-fail.png、TC22-1-panel-28-fail.png；/tmp/tc05_log.txt、/tmp/tc22_log.txt |
| TC-16 | PASS | 点失败区 `重试` → 2s 内状态行**回到** `正在准备本机模型…` → 38s 后出正常回复「你好！请问有什么可以帮您处理账目、解答问题或协助其他相关需求吗？」，状态行变 `本机模型 · 离线可用`；**旧的超时正文被清空重来，无两条历史叠在一起**；logcat `AzhangChat: 对话输出不是 JSON，补救重试 1/1：…` → `补救重试 1 成功，重发后拿到合法输出`；`Model loaded!`=1、FATAL=0 | docs/screenshots/2026-10-10/TC05-1-panel-fail.png（重试前）、TC16-2-retry-ok.png（重试后）；/tmp/tc16_log.txt |
| TC-22 | PASS | 面板输入 `28` → 56s 转 `这次没答上`，正文**显示具体原因**（原文见 TC-05 行），未出现修复前的通用兜底话术 → 命中「失败但显示具体原因」这一档；logcat 两次原文：首次 `28是一个表示数量的数字，在日常生活、数据记录、统计等方面都有使用场景，具体含义会根据使用场景有所不同…`（纯文本非 JSON），补救重试后 `{"content": "28是一个表示数量的数字…}` —— **是 JSON 但缺 `reply` 字段，仍不可解析** → 走降级；本轮**未出记账卡**；`Model loaded!`=1、FATAL=0 | docs/screenshots/2026-10-10/TC22-1-panel-28-fail.png；/tmp/tc22_log.txt |
| TC-23 | PASS | 面板与对话页各发一次 `hello`，模型原始输出**逐字抄录见附录 A**。归类：两侧 3 次样本全部是**纯中文问候句**（非空、**不复述系统提示词**、不自言自语、无控制字符），每次都先触发 `AzhangChat: 对话输出不是 JSON，补救重试 1/1：<原文>`，**重发后拿到合法输出**（3/3 一致，面板 12:32:20→12:32:37、对话页 12:34:40→12:35:06），界面显示的就是重发后的合法 `{"reply":…}` 文案；修复前关键字 `无法解析模型输出:` 全窗口 **0 次**；`Model loaded!`=1、FATAL=0 | docs/screenshots/2026-10-10/TC23-1-panel-hello.png、TC23-2-chat-hello.png；/tmp/tc23_log.txt、/tmp/tc23b_log.txt；附录 A |
| TC-08 | PASS | `SparkBatchActivity --es cases t9_probe.json` 跑 5 条：`SparkBatch: [tc08] 1/5 id=1 entry=true cat=交通 amt=28 115341ms src=MODEL` … `5/5 … src=MODEL`，**5/5 全部 src=MODEL（无一条 src=RULES）**；结果落盘 `files/softtest_tc08.json`（`source` 字段全为 `model`）；中文数字「五十块」→ **50**、多笔「晚饭15块加奶茶12一共27」→ **27** 均算对；非记账句 `你好` 输出 `[{"reply":"你好，我是记账管家…"}]` → `entry=false` 不入账；因 5 条输出**全是合法 JSON**，`SparkAiParser: 模型输出不是合法 JSON，回落规则解析` **0 次命中**（用例里「如输出 `[[]]` 这类」的降级分支本轮未复现，按用例不判失败）；总耗时 188713ms、**平均=37742ms**；`Model loaded!`=1、FATAL=0 | /tmp/tc08_log.txt；/tmp/tc08_out.json（原文见附录 A） |
| TC-20 | PASS | 记一笔面板（＋号 541,2191）整屏 dump 文字清单：`收入/支出/今天/¥0/餐饮…其他支出/点击填写备注/地点（可选）/明细（买了什么）/数字键盘/再记一笔/保存` —— **没有任何文字 AI 解析输入框或「解析」按钮**，`parseNow()` 在 0.20.0 仍**无 UI 触点**（现状留痕，端侧解析链路由 TC-08 批测覆盖）；点顶部相机 icon (871,570) → 菜单两项 `从相册选择`/`拍一张`，点 `拍一张` **拉起系统相机**（`CAM_CameraAppUI: onPreviewStarted`），未走到上传链路，白名单文案本轮未触发（记录不判失败） | docs/screenshots/2026-10-10/TC20-1-addpanel.png、TC20-2-capture-menu.png、TC20-3-capture-tap.png |
| TC-18 | PASS | 设置页 `未加载 → 加载中… → 已加载 · 可以生成了`（t=102s）→ 点「卸载」状态**立即**回 `未加载 · 点「加载模型」`（无旧文案残留）→ 首页面板发 `hello` 触发自动重载（`Model loaded!` **1→2**，属预期）→ 92s 超时给**具体原因**「这次生成超时了（超过 90 秒没出结果）」（已知问题①）→ 回设置页状态行显示 `未加载 · 点「加载模型」`，而进程 7599 的模型**实际仍在内存**（`Model loaded!`×2、`Model unloaded!`×1，之后无第二次卸载）——**不谎报「已加载」**（本用例硬判据通过），但反向状态不同步 = **已知问题③的另一面**；`Unloading model`=1、FATAL=0 | docs/screenshots/2026-10-10/TC18-1-loaded.png、TC18-2-unloaded.png、TC18-3-panel-recover.png、TC18-4-settings-after.png；/tmp/tc18_log.txt |
| TC-21 | PASS（仅留痕） | 四组数全部抄到（**x86_64 模拟器口径，不得当真机指标引用**）：① `Model loaded! → System prompt processed!` = **97.1 / 97.0 / 97.2 / 97.3 / 100.1 s**（TC-01/02/10/18/05 五个窗口，解码统一系统提示词是大头，模型本体 load 只 2s）；② 设置页生成 `firstToken=8186ms total=18177ms chunks=28`（TC-09）、`firstToken=8278ms total=15431ms chunks=21`（TC-10）；③ 面板首条消息到首个状态变化 **2s**（本轮 TC-05 实测；TC-02 为 7s，判据 ≤20s 均满足）；④ 批测 `平均=37742ms`（5 条，含首条冷启动 115341ms） | /tmp/tc01_log.txt、tc02、tc09、tc10、tc18、tc05、tc08 各日志；原始行见本节 |
| TC-24 | PASS | `tools/build.sh :app:testDebugUnitTest --rerun`（Gradle 9.8.0 / JDK 见 tools/build.sh）→ **BUILD SUCCESSFUL**；`app/build/test-results/testDebugUnitTest/` 共 **40 个测试类 XML，tests=472、failures=0、errors=0**（12:50 重跑产物，非缓存旧结果）；新增 `llm.SparkSessionReadyTest` **6 个用例全过**，`feature.chat.AzhangChatRepairTest`、`ChatRoutingTest`、`llm.ReplyStreamFilterTest`、`llm.ThinkingStripperTest` 亦全绿 | `app/build/test-results/testDebugUnitTest/*.xml`（构建产物，未提交）；构建输出末尾 `BUILD SUCCESSFUL` |
| TC-12 | PASS | 压轴全窗口崩溃巡检：**30 份**已落盘日志（`/tmp/tc*.txt` + `/tmp/cap_*.txt`）合并统计 `FATAL` = **0**、`ANR in` = **0**（逐文件明细见证据文件）；当前 `logcat -d` 缓冲 `FATAL EXCEPTION`=0、`ANR`=0；`dumpsys activity activities | grep topResumedActivity` = `dev.dzsun.bookkeeping/.MainActivity`（t83）。**口径说明**：会话中模拟器重启过一次且多次 `logcat -c`，故取「各阶段落盘日志并集 + 当前缓冲」而非单次 buffer | /tmp/tc12_full.txt、/tmp/tc12_buffer.txt |

## 三、失败明细（FAIL / BLOCKED，只记录不修）

> 本节只收**原 24 条**的失败；加测章节（TC-25 起）的 FAIL/BLOCKED 见第六节。

### TC-03 · FAIL —— 功能页加载后，设置页仍显示「未加载」

- **复现**：force-stop + `logcat -c` → 冷启动**不碰设置页** → 对话页点 chip `午饭花了 35`（模型被功能页拉起）→ 回设置页 dump 状态行 → 点「生成」。
- **观察 vs 预期**：状态行 = `未加载 · 点「加载模型」`（预期：显示已加载）；但点「生成」**直接出结果**（firstToken=8171ms），全窗口 `Model loaded!`=1 —— 界面与引擎**互相矛盾**。
- **日志/截图**：`/tmp/tc03_log.txt`（`Model loaded!`=1、`Unloading model`=0、FATAL=0）；截图 TC03-3-settings.png、TC03-4-generate-result.png。
- **怀疑位置**：`feature/settings/OnDeviceModelSection.kt:106` —— `OnDeviceModelViewModel` 自持 `MutableStateFlow<OnDeviceUiState>(Idle)`，只由**本页动作**（load/generate/unload）推进，**不订阅属主 `SparkSession` 的真实状态**；状态行文案在同文件 `:319-321`。
- **另一面（本轮新增证据）**：TC-18 里方向相反——面板自动重载后设置页仍显示 `未加载`，而引擎确已加载（`Model loaded!`×2、`Model unloaded!`×1）。

### TC-06 · FAIL —— `午饭花了 35` 出 `{"reply":"查账"}`，不出记账卡，库不增

- **复现**：对话页点 chip `午饭花了 35`（含数字 → `classifyChatInput` 判为 `Record`，`ChatRouting.kt:71`）→ 等卡。
- **观察 vs 预期**：无卡；气泡 = QUERY_FALLBACK_TEXT「这个得看你的真实账本，我不编数字…」；库 N0=**523 → 523**（预期 524）。
- **日志**：`ai-chat` 中 `Formatted and added assistant message … {"reply":"查账"}`（`/tmp/tc06_log.txt`、`/tmp/tc11_log.txt`、本轮 `/tmp/cap_a.txt` 12:52:04 **共 3 次一致**）。
- **怀疑位置**：`llm/SparkSession.kt:241-244`（提示词 B 段把「报账」和「查账」的边界交给模型判断）+ `feature/chat/AzhangChat.kt:189-196`（`interpret` 先试数组、否则取 `reply`，`reply=="查账"` 即走查账兜底）+ `feature/chat/ChatScreen.kt:238`。
- **连带**：规则出卡入口 `ChatScreen.kt:158-168 entryParse` 只在模型不可用时由 `legacyReply`（`:202`）兜底触达 —— **模型在位时对话页拿不到记账卡**。

### TC-17 · FAIL —— 面板 `28` 冷启动 92s 超时，降级后仍不出卡

- **复现**：面板输入 `28` → 发送。
- **观察 vs 预期**：界面「这次生成超时了（超过 90 秒没出结果）」（logcat 系统提示词解码 10:56:10→10:57:48 = **98s > 90s**，已知问题①）；转对话页 chip `午饭花了 35` 再次得到 `{"reply":"查账"}` → 仍无卡，库 523→523。
- **日志/截图**：`/tmp/tc17_log.txt`、`/tmp/tc17b_log.txt`；TC17-1-panel-result.png、TC17-3-chat-card.png。
- **怀疑位置**：`feature/chat/AiFloatPanel.kt:383`（90s 文案）与冷启动解码 97~100s 的矛盾（见 TC-21 数据）；卡的部分同 TC-06。

## 四、与已知问题的对照

| 编号 | 已知问题 | 本轮判据 | 结果 | 证据 |
|---|---|---|---|---|
| A1 / 根因1 | 二次加载（功能页重复 `Model loaded!`） | 每轮 `grep -ac 'Model loaded!'` | **不复现**：TC-01/02/05/07/09/13/15/18 各窗口均为 1（TC-18 卸载后重载 =2 属预期） | 各 `/tmp/tcNN_log.txt` |
| A2 / 根因2 | 报错文案与 logcat 不一致 | 界面文案与 `AzhangChat:`/`InferenceEngineImpl` 原文比对 | **不复现**：TC-04 面板原因与 logcat **逐字一致**；TC-05 非 JSON 原因与 logcat 同源 | TC04-1、TC05-1、/tmp/tc04_log.txt |
| A3 / B2 | 非记账输入只给占位文本 | `hello`/`28` 的原始输出归类 | **不复现占位文本**：均为自然中文问候/解释；但**首次输出常为纯文本**，靠 `补救重试 1/1` 兜回 JSON（见 TC-23、附录 A） | /tmp/tc23*.txt、tc22_log.txt |
| 已知① | 冷启动系统提示词解码 98~149s > 面板 90s 预算 | 冷启动面板消息是否超时 | **仍复现 4 次**（TC-05/11/18 各 92s；TC-03 曾 146s）；解码耗时本轮实测 97.0~100.1s | TC-21 数据；TC05-1、TC18-3 |
| 已知② | `午饭花了 35` → `{"reply":"查账"}` 不出卡 | 出卡与否 | **仍复现 3/3**（TC-06、TC-17、TC-25A）；第 4 次（TC-31）改回普通闲聊回复，**同样不出卡** | 附录 A |
| 已知③ | 功能页加载后设置页显「未加载」 | 状态行 vs 引擎真实状态 | **仍复现**，且发现**反向**同样不同步（TC-18：引擎已加载、设置页仍显未加载） | TC03-3、TC18-4 |

## 五、执行中的环境问题（与产品缺陷分开列）

1. **logcat 环形缓冲被冲掉**：`ai-chat` 的 token 级 `V` 日志一个冷启动就上千行，把默认缓冲冲掉，导致 TC-11 窗口里 `AzhangChat:` 行**一条都不剩**。处置：改用 `adb logcat -G 16M` 放大缓冲（本节之后的窗口均正常）；统计崩溃时改为「落盘日志并集」口径（TC-12）。
2. **中文无法入框**：`adb shell input text` 只吃 ASCII，中文一律点界面 chip（本轮全部走 chip / 坐标来自 dump）。
3. **模拟器中途重启过一次**（11:52 上一位测试员停摆后），11:52 前的日志随重启丢失，之前阶段的日志已各自落盘 `/tmp/tcNN_log.txt`。
4. **uiautomator dump 慢**：单次 dump 约 3~5s，导致「逐字上屏」采样分辨率有限（面板侧仍抓到 3 个增长点）；对话页只能证明「整段上屏」。
5. **拍照入口会抢焦点**：点记一笔面板「拍一张」直接拉起系统相机（甚至触发 Google Lens 权限框），需 `keyevent 4`/`3` 或 `am force-stop com.android.camera2` 收回，否则后续 dump 抓到的是相机屏（本轮踩过一次）。
6. **/data 余量**：整盘 5.8G 已用 5.1G，可用仅 **513M**（模型 1.03GiB 常驻 `files/models/` + `/data/local/tmp/Spark-backup.gguf` 1.03GiB 备份）；跑批测/装包前需留意。
7. **单测与模拟器抢 CPU**：TC-24 的 `--rerun` 排在全部设备用例之后执行，避免影响耗时判据。
