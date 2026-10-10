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
