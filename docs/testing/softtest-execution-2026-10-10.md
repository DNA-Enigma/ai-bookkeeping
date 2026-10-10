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
