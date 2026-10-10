# 端侧模型链路 · 模拟器软测用例集（0.19.0 / 修复后验收）

- 仓库：`/home/dzsun/projects/ai-bookkeeping`（只读，本文档不改仓库任何文件）
- 设备：`emulator-5554`（x86_64），包名 `dev.dzsun.bookkeeping`，装机版本 **0.19.0 (versionCode 18)**
- 模型：`files/models/Spark-X2.5-1.7B-Q4_K_M.gguf`（1107457856 字节 ≈ 1056 MB）
- 四个入口：**AI 悬浮面板**（首页右下 AI 球）· **对话页**（首页「和阿账聊聊 →」）·
  **记一笔解析**（`SparkAiParser` 的 `【记账解析】`链路）· **设置页面板**（设置 → 端侧模型）
- 用例总数 **24**（P0 × 12 / P1 × 8 / P2 × 4）

> **基线说明**：现装 0.19.0 是**修复前**版本。标了【回归】的用例在 0.19.0 上**按设计应当失败**
> （二次加载 2 次、报错文案对不上、非 JSON 只给通用话术），它们的预期值写的是**修复后**的判据。
> 开跑前先确认待测包：`adb shell dumpsys package dev.dzsun.bookkeeping | grep versionName`，
> 并按 `android-emulator-verification` 的做法 grep 新包 dex 里本次修复引入的新字符串
> （如 `未找到模型文件`、`模型引擎状态异常`），命中 0 就是旧包。

---

## 零、通用操作（每条用例引用，不重复抄）

### 0.1 取坐标（**一律来自 dump，禁止目测**）

```bash
export PATH=$HOME/opt/android-toolchain/sdk/platform-tools:$PATH
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s emulator-5554 shell cat /sdcard/ui.xml > /tmp/ui.xml
python3 /home/dzsun/.hermes/skills/android-emulator-verification/scripts/ui_dump_tools.py find /tmp/ui.xml 加载模型
python3 /home/dzsun/.hermes/skills/android-emulator-verification/scripts/ui_dump_tools.py click /tmp/ui.xml   # 列可点节点
```

**只点 `clickable="true"` 的节点**；按钮无文字时（面板「发送」是文字节点但父容器才可点）用
`ui_dump_tools.py send` 按「与输入框纵向重叠、x 在其右侧」定位。

本文档给出的坐标是 1080×2400、当前布局下的**参考值**（每次先 dump 复核，滚动后必变）：

| 控件 | bounds | 中心 |
|---|---|---|
| 首页 AI 球（text="AI"） | [933,1822][974,1885] | (953,1853) |
| 首页「和阿账聊聊 →」 | [58,519][296,645] | (177,582) |
| 底栏「首页」/「设置」 | [80,2266][137,2304] / [944,2266][1001,2304] | (108,2285) / (972,2285) |
| 底栏中央 ＋（content-desc=记一笔） | [512,2162][570,2220] | (541,2191) |
| 设置页「加载模型」/「生成」/「卸载」 | [100,958][228,1004] / [338,958][402,1004] / [512,958][576,1004] | (164,981) / (370,981) / (544,981) |
| 设置页状态行「已加载 · 可以生成了」 | [58,1217][338,1263] | (198,1240) |
| 面板输入框（EditText） | [213,1466][776,1592] | (494,1529) |
| 面板「发送」/「收起」 | [879,1505][943,1551] / [864,1337][990,1463] | (911,1528) / (927,1400) |
| 面板「去问账页」/「去对话页」 | [311,1631][436,1675] / [726,1631][851,1675] | (373,1653) / (788,1653) |

### 0.2 中文输入限制

`adb shell input text` **只吃 ASCII**（中文传进去直接报 `NullPointerException`）。中文一律用
**界面现成短语**：

- 对话页建议 chips（可点）：`午饭花了 35`、`这个月餐饮花了多少`、`这个月咖啡多少钱`、`给我省钱建议`
- 问账页示例卡（可点）：`这个月花了多少`、`星巴克花了几次`、`这个月的餐饮花了多少`
- 面板**没有**短语 chip → 面板用 ASCII（`hello` / `28`），或先走对话页出卡再回面板

输入后固定顺序：`input text` → `keyevent 4`（收键盘）→ **重新 dump 读实际值** → 再点发送。

### 0.3 logcat 采集（每条用例开始前先清）

```bash
adb -s emulator-5554 shell am force-stop dev.dzsun.bookkeeping
adb -s emulator-5554 shell logcat -c
adb -s emulator-5554 shell am start -W -n dev.dzsun.bookkeeping/.MainActivity >/dev/null
# …执行操作…
adb -s emulator-5554 shell logcat -d > /tmp/tcNN_log.txt 2>&1
grep -E 'InferenceEngineImpl|SparkSession|AzhangChat|SparkAiParser|SparkLlm|SparkBatch|AndroidRuntime' /tmp/tcNN_log.txt
grep -ac 'Model loaded!'   /tmp/tcNN_log.txt   # 二次加载回归判据
grep -ac 'Unloading model' /tmp/tcNN_log.txt   # 期望 0（卸载用例除外）
grep -ac 'FATAL'           /tmp/tcNN_log.txt   # 期望 0
```

关键日志字：`Model loaded!`、`System prompt processed!`、`Unloading model and free resources...`、
`Model unloaded!`、`AzhangChat: 无法解析模型输出`、`AzhangChat: 模型未就绪`、
`SparkAiParser: 模型输出不是合法 JSON`、`SparkAiParser: 初始化未就绪`、
`SparkSession: init 失败`、`SparkLlm: generate done`、`SparkBatch:`。

### 0.4 截图与落库

```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/tcNN.png     # 每个「预期界面状态」各一张
D=/tmp/softtest-db; mkdir -p $D                                 # 库 + WAL 必须三件套一起拉
for f in ledger.db ledger.db-wal ledger.db-shm; do
  adb -s emulator-5554 exec-out run-as dev.dzsun.bookkeeping cat databases/$f > $D/$f 2>/dev/null
done
sqlite3 $D/ledger.db 'select count(*) from journal;'
sqlite3 $D/ledger.db 'select id,note,dateEpochDay from journal order by createdAt desc limit 3;'
```

（`exec-out` 是无 pty 通道，二进制**不能**用 `adb shell … cat > 文件`，会静默损坏库。）

### 0.5 模型文件增删

```bash
adb -s emulator-5554 shell run-as dev.dzsun.bookkeeping ls -l files/models/
# 删（负向用）：
adb -s emulator-5554 shell run-as dev.dzsun.bookkeeping rm files/models/Spark-X2.5-1.7B-Q4_K_M.gguf
# 恢复：设置页点「加载模型」→ 自动从 APK 内置资产释放（不要 adb push）
adb -s emulator-5554 shell run-as dev.dzsun.bookkeeping sh -c 'ls -l files/models/; ls files/models/ | grep -c part'  # 期望字节数 1107457856、.part 计数 0
```

### 0.6 登录前置

App 是本地账号门禁（`RootFlow`：未登录只到登录页）。**若开跑时停在登录页**，用本地注册
（任意邮箱格式 + ≥6 位口令）进主界面即可；账本数据在 `databases/ledger.db`，登录态不影响数据。

---

## P0 用例（核心链路 + 必含回归 + 负向）

### TC-01 【P0】设置页加载模型（链路基线）
- **前置**：0.5 已确认模型文件在位；0.3 已清 logcat；App 已到首页。
- **步骤**：dump → 点底栏「设置」(972,2285) → 向上滑两次把「端侧模型」区滚进视野 →
  dump 确认状态行为「未加载 · 点『加载模型』」→ 点「加载模型」(164,981) →
  每 4s dump 轮询状态行，直到出现「已加载 · 可以生成了」（首次含资产释放时更久，上限 120s）。
- **预期**：状态行依次 `未加载 → 正在释放/正在加载 → 已加载 · 可以生成了`；**不出现**红字失败；
  logcat 恰有 `Model loaded!` ×1、`System prompt processed!` ×1、`AndroidRuntime FATAL` ×0。
- **证据**：加载完成截图 `/tmp/tc01_ready.png`；`/tmp/tc01_log.txt` + 三个 grep 计数；
  `run-as ls -l files/models/` 输出（字节数）。

### TC-02 【P0】【回归·根因1 / 验收A】设置页加载后，功能页不得二次加载
- **前置**：TC-01 通过（模型已由设置页加载）；**重新 force-stop + `logcat -c`**，重启 App 后
  再走一遍设置页「加载模型」到「已加载」。
- **步骤**：① 设置页「加载模型」→ 等「已加载」；② 点底栏「首页」(108,2285)；
  ③ 点 AI 球 (953,1853) 展开悬浮面板，静置 45s 让 `warmUp` 跑完；
  ④ 面板输入 `hello`（0.2/TC-05 的输入法）→ 点「发送」→ 等到终态（上限 100s）。
- **预期**：**全程 `Model loaded!` 只出现 1 次、`Unloading model` 0 次**；
  面板状态行走 `正在准备本机模型… → 回答生成中/这次没答上`，**不出现 60 秒级二次卡顿**
  （存在性判据：从点「发送」到首个状态变化 ≤ 20s；数值本身不是性能基线）。
- **证据**：`grep -c 'Model loaded!'` = **1** 的命令输出；`grep -c 'Unloading model'` = 0；
  发送后 5s 内的 dump（状态行）+ 终态截图。

### TC-03 【P0】【回归·验收B】反序也通：功能页先用模型，再进设置页生成
- **前置**：force-stop + `logcat -c`，冷启动后**不**先碰设置页。
- **步骤**：① 首页点「和阿账聊聊 →」(177,582) 进对话页 → 点 chip `午饭花了 35` → 等出记账卡（首次加载约 15~65s）；
  ② 点「收起」返回首页 → 点「设置」tab → 滚到端侧模型区，dump 确认状态行**不是**「未加载」；
  ③ 点「生成」(370,981) → 等结果。
- **预期**：设置页状态与功能页**一致**（已加载，不显示「未加载」也不要求重新点「加载模型」）；
  点「生成」后直接出结果（首 token/全程/块数），**不发生卸载重载**：`Model loaded!` 计数在第 ① 步后不再增长、
  `Unloading model` = 0；`FATAL` = 0。
- **证据**：三步各一张截图；`/tmp/tc03_log.txt` 的 `Model loaded!`/`Unloading model` 计数；
  设置页 `SparkLlm: generate done: firstToken=… total=… chunks=…` 行。

### TC-04 【P0】【回归·根因2 / 验收C / 负向①】删掉模型文件后调用，界面必须显示真实原因
- **前置**：force-stop + `logcat -c`；0.5 删除模型文件（先 `ls -l` 留删除前证据）。
- **步骤**：① 冷启动 → 首页点 AI 球开面板 → 输入 `hello` → 点「发送」，等 100s；
  ② 状态行/正文出现失败后，点「去对话页」(788,1653) → 点 chip `这个月咖啡多少钱` → 等回复。
- **预期**：
  - 面板显示**具体原因**：`未找到模型文件，去设置页点「加载模型」`（或同类含「模型文件」「加载模型」的原文），
    **不得**只显示修复前那句「这次没答上来（模型还没准备好或超时了）」；
  - 对话页除降级回答外，另有一条带原因的气泡（`本机模型这次没接上：…未找到模型文件…`）；
  - 与 logcat 同因：`AzhangChat: 模型未就绪…未找到模型文件` / `SparkSession` 无其他异常；`FATAL` = 0。
- **证据**：面板失败态截图 + 对话页气泡截图；`/tmp/tc04_log.txt` 中的原因行原文（**界面文案与 logcat 必须逐字对得上**，写进报告）。
- **后续**：本用例**不恢复模型**，紧接着做 TC-14（趁缺模型态再验一个入口）。

### TC-05 【P0】【回归·A3 / 验收E】模型输出非 JSON 时的降级表现
- **前置**：模型文件在位且已加载（TC-01/TC-02 之后即可）；force-stop + `logcat -c`。
- **步骤**：① 首页 AI 球开面板 → 输入 `hello` → 点「发送」→ 等终态（上限 100s）；
  ② 若状态行是失败态，dump 确认失败区第一个 chip 是「重试」。
- **预期**：
  - 正文区显示**真实原因**：`模型输出不是合法 JSON：<模型实际输出前 80 字>`（与 logcat
    `AzhangChat: 无法解析模型输出: …` 同源同因），**不得**显示「模型还没准备好或超时了」；
  - 状态行显示「这次没答上」；出现「重试」chip；点「重试」重发同一句，状态回到「正在准备本机模型…」；
  - 记一笔/对话页的解析路径同步降级到规则（`SparkAiParser: 模型输出不是合法 JSON，回落规则解析`），**不崩**、`FATAL` = 0。
- **证据**：失败态正文截图（原因文字清晰可读）；`/tmp/tc05_log.txt` 里 `无法解析模型输出` 行；
  重试后再一次状态截图。

### TC-06 【P0】对话页正向：一句话 → 记账卡 → 确认入账 → 库里真多一条
- **前置**：模型在位已加载；force-stop + `logcat -c`；记录库基线 `select count(*) from journal;` → **N0**。
- **步骤**：首页「和阿账聊聊 →」→ 对话页点 chip `午饭花了 35` → 等出现「已为你解析，确认后入账：」卡片 →
  dump 确认卡片金额为 `35` → 点「确认入账」。
- **预期**：卡片金额 35、分类来自科目表；点后按钮变「已提交」；agent 气泡如实反映结果
  （成功「已入账。可在首页流水中查看」/ 失败「这笔没能入账…」——**两种都算通过，但必须与库里实际一致**）；
  库计数 **N0+1**；`FATAL` = 0。
- **证据**：卡片截图 + 入账后气泡截图；0.4 查询输出（**N0、N0+1 两个数字都写进报告**）；
  logcat 中 `SparkBatch` 不涉及、`AzhangChat` 无 `无法解析`。

### TC-07 【P0】对话页问账：答案来自本地 SQL，且标明来源、不编数字
- **前置**：同 TC-06（不必清 logcat，但记基线）。
- **步骤**：对话页点 chip `这个月餐饮花了多少` → 等答案气泡。
- **预期**：给出金额与占比（与首页「本月总览」口径一致）；答案带**来源脚注**
  （「这句话是按本地规则理解的…」或「这句由本机模型回答，没有查账本」二选一）；
  数字能在库里复算出来，**不出现来源不明的编造金额**；`FATAL` = 0。
- **证据**：答案气泡截图；0.4 里按分类复算的 `select` 输出与界面数字对照。

### TC-08 【P0】记一笔解析链路（`SparkAiParser` / `【记账解析】`）可达且降级可见
- **前置**：模型在位；force-stop + `logcat -c`。**说明**：0.19.0 的记一笔面板**没有文字 AI 解析的 UI 触点**
  （`parseNow()` 全仓无调用方），端侧解析链路用 debug 包的 T9 批测入口驱动——它走的是**同一个
  `SparkAiParser.parseWithSource`**，不是另写一套。
- **步骤**：
  ```bash
  adb -s emulator-5554 shell am start -n dev.dzsun.bookkeeping/.llm.SparkBatchActivity \
       --es cases t9_probe.json --es out softtest_tc08.json --es label tc08
  # 题库 t9_probe.json 已在 files/ 下（打车28、午饭花了35、你好、退款到账了35、晚饭15块…）
  # 轮询 logcat 直到出现「评估完成」（5 条，约 90s）
  adb -s emulator-5554 shell run-as dev.dzsun.bookkeeping cat files/softtest_tc08.json
  ```
- **预期**：logcat `SparkBatch: [tc08] n/5 … src=MODEL`（模型答的）与 `src=RULES`（降级的）**逐条标注**；
  非记账句（`你好`）如输出 `[[]]` 这类非合法 JSON，必须出现
  `SparkAiParser: 模型输出不是合法 JSON，回落规则解析: …`；结果 JSON 落盘可读；`FATAL` = 0。
- **证据**：`SparkBatch:` 全部行 + `SparkAiParser` 降级行；`files/softtest_tc08.json` 内容（`source` 字段）。

### TC-09 【P0】设置页面板「生成」正向出结果
- **前置**：模型已加载（TC-01 后）；force-stop + `logcat -c` → 冷启动 → 设置页 → 加载到「已加载」。
- **步骤**：确认生成框预填文案「用一句话说明记一笔「打车28元」该怎么分类。」（可不改，ASCII 输入框可改）→
  点「生成」(370,981) → 等状态从「生成中…」变出结果。
- **预期**：状态行给出 `首 token N ms · 全程 N ms · N 块 · N 字`，下方是模型正文（不是空、不是报错）；
  logcat `SparkLlm: generate done: firstToken=… total=… chunks=…`；`FATAL` = 0。
- **证据**：结果截图（数字与正文都要拍到）；logcat `generate done` 行；两者数字一致。

### TC-10 【P0】【负向③】卸载模型后再点「生成」
- **前置**：设置页模型处于「已加载」；force-stop + `logcat -c` → 冷启动 → 设置页 → 加载到「已加载」。
- **步骤**：① 点「卸载」(544,981) → dump 确认状态行回到 `未加载 · 点「加载模型」`；
  ② 立刻点「生成」(370,981) → 轮询状态行 120s。
- **预期**：**不得**出现与真实状态矛盾的旧文案；要么自动重新加载后出结果（状态行经历 `正在加载 → 生成中 → 结果`，
  此时 `Model loaded!` +1 是**预期**），要么显示明确失败原因（含「加载」「未找到」字样）；
  按钮在加载/生成期间置灰不可重复触发；`FATAL` = 0。
- **证据**：卸载后、点生成后、终态三张截图；`/tmp/tc10_log.txt` 的 `Model loaded!` 增量与失败/成功行。

### TC-11 【P0】【负向②】生成中途再发一条（并发）
- **前置**：模型已加载；force-stop + `logcat -c` → 冷启动。
- **步骤**：① 首页 AI 球开面板 → 输入 `hello` → 点「发送」，在状态行仍为
  `正在准备本机模型…/回答生成中` 时（dump 确认，未到终态）；
  ② 立刻点面板输入框再输一条 ASCII 并点「发送」（busy 态下发送键应显示 `…` 且**点不动**——这也算一种正确表现）；
  ③ 换对话页复测：点 chip `午饭花了 35`，未出卡时立刻再点 chip `这个月咖啡多少钱`。
- **预期**：第二条**要么被按钮禁用挡住**，**要么**发出后界面显示具体原因（`模型忙` / `生成失败：…Cannot send user prompt…`），
  **不得**静默吞掉、不得顶掉第一条的进度、不得崩（`FATAL` = 0）；两条都结束后 App 仍可正常发第三条。
- **证据**：忙态截图（发送键 `…`/置灰）+ 第二条的界面反馈截图；`/tmp/tc11_log.txt` 中并发抛出的异常与界面文案对照。

### TC-12 【P0】全程崩溃巡检（收尾汇总）
- **前置**：TC-01～TC-11 全部跑完，不 force-stop、不清 logcat。
- **步骤**：`adb -s emulator-5554 shell logcat -d > /tmp/tc12_full.txt`，统计并逐条归因。
- **预期**：`grep -ac 'FATAL' /tmp/tc12_full.txt` = **0**；`topResumedActivity` 仍是 `dev.dzsun.bookkeeping/.MainActivity`；
  无 ANR（`grep -i anr /tmp/tc12_full.txt` 为空）；若有 FATAL，记下首行异常类名+触发送例编号。
- **证据**：三个 grep 的命令与输出；`dumpsys activity activities | grep topResumedActivity` 输出。

---

## P1 用例（状态一致性与降级完备性）

### TC-13 【P1】设置页与功能页状态互可见（双属主收敛的另一面）
- **前置**：force-stop + `logcat -c` → 冷启动 → 设置页加载到「已加载」。
- **步骤**：① 首页开面板发一条（走通）；② 回设置页 dump 状态行；③ 点「卸载」；④ 回首页开面板再发一条。
- **预期**：② 状态行仍为「已加载」且**未发生**卸载重载（`Unloading model` = 0，`Model loaded!` 不增）；
  ④ 因用户显式卸载，此时允许重新加载（`Model loaded!` +1），面板最终可用或给出具体原因。
- **证据**：②④ 两处状态截图；logcat 两个计数的前后对比。

### TC-14 【P1】【回归·验收E】缺模型态下对话页的「原因气泡」
- **前置**：**接 TC-04（模型文件已被删，尚未恢复）**，force-stop + `logcat -c`。
- **步骤**：冷启动 → 对话页 → 点 chip `给我省钱建议` → 等回复；再点 chip `午饭花了 35`。
- **预期**：省钱建议仍按规则给出（页面不哑），但**额外**出现一条带具体原因的气泡
  （`本机模型这次没接上：…未找到模型文件…`）；记账 chip 走规则出卡；`FATAL` = 0。
- **证据**：两张气泡截图；logcat `AzhangChat: 模型未就绪` 原因行与界面逐字对照。

### TC-15 【P1】问账页预热不产生第二次加载
- **前置**：设置页已加载（或 TC-02 已跑到面板发过消息），force-stop + `logcat -c` 后重走设置页加载。
- **步骤**：首页 AI 球 → 面板点「去问账页」(373,1653) → 静置 45s（`AskViewModel.init` 会 `warmUp`）→
  点示例卡 `这个月花了多少` → 点「问一问」。
- **预期**：答案出现；全程 `Model loaded!` = **1**、`Unloading model` = 0。
- **证据**：计数输出 + 答案截图。

### TC-16 【P1】面板失败态的「重试」闭环
- **前置**：接 TC-05 的失败态（或复现之）。
- **步骤**：dump 找到失败区「重试」chip → 点它 → 观察状态行与正文。
- **预期**：状态行回 `正在准备本机模型…`，正文被清空重来；重试后若成功显示正常回复，仍失败则仍显示具体原因；
  不出现按钮点了没反应、也不出现两条历史正文叠在一起。
- **证据**：重试前后两张截图。

### TC-17 【P1】悬浮面板记账卡「记下来」真入账
- **前置**：模型在位；库基线 **N0**（0.4）。
- **步骤**：面板输入 `28` → 点「发送」→ 若出「记一笔」卡（金额 28），点「记下来」→ 等「已入账」标签；
  若只出文字回复（模型这次没认成账），**本用例降级为跳过并留痕**，改在对话页用 chip `午饭花了 35` 出卡后点「确认入账」。
- **预期**：库计数 **N0+1**，卡片标签变「已入账」；失败时显示「没记上（分类或账户没准备好）…」而非假成功。
- **证据**：卡片与入账标签截图；库计数 N0/N0+1。

### TC-18 【P1】显式卸载 → 功能页自动恢复
- **前置**：模型在位；force-stop + `logcat -c` → 冷启动 → 设置页加载到「已加载」。
- **步骤**：设置页点「卸载」→ 首页开面板 → 输入 `hello` → 发送 → 等终态。
- **预期**：面板触发重新加载（`Model loaded!` +1 属预期），随后正常回复或给出具体原因；
  设置页再进时状态**不再**谎报「已加载」（要么自己同步，要么点生成时按真实状态处理，见 TC-10）。
- **证据**：卸载后设置页状态截图、面板终态截图、logcat 计数。

### TC-19 【P1】【恢复】删模型后由内置资产自动释放（验收 0.18.0 释放路径仍好用）
- **前置**：接 TC-14（模型仍缺失）。
- **步骤**：设置页 → 点「加载模型」→ 轮询状态行（首次释放约数秒~十几秒）→ 完成后 0.5 校验字节数与 `.part`。
- **预期**：状态行 `正在释放内置模型 …% → 正在加载 → 已加载 · 可以生成了`；字节数 **1107457856**；
  `.part` 残留计数 **0**；随后 TC-02 的判据重新可跑（`Model loaded!` 恢复为 1 次/轮）。
- **证据**：释放过程 + 完成截图；`ls -l files/models/` 输出；`grep -c part` 输出。

### TC-20 【P1】记一笔面板入口触点核查（现状留痕）
- **前置**：模型在位；App 到首页。
- **步骤**：底栏中央 ＋ (541,2191) 打开记一笔面板 → dump 整屏 → 逐个节点找**文字 AI 解析输入框/「解析」按钮**；
  再看「拍照记账」icon (870,570) 的两个菜单项（从相册选择 / 拍一张）。
- **预期**：**如实记录现状**——0.19.0 面板只有手动表单 + 拍照/相册，`parseNow()`（文字端侧解析）**没有 UI 触点**，
  该链路由 TC-08 的批测入口覆盖；拍照路径走云端 `CaptureClient`，连不上时显示白名单文案
  （`UserFacingErrors` 系，**不透传服务端原文/URL**）。若待测包已补上文字入口，则改跑：
  输入 `打车28` → 点解析 → 出确认卡 → 库校验，作为通过判据。
- **证据**：面板整屏 dump 文本清单 + 截图；结论写明「有/无触点」。

---

## P2 用例（留痕与口径，不阻塞发布）

### TC-21 【P2】性能留痕（模拟器口径）
- **前置**：任一加载/生成用例完成。
- **步骤**：从 logcat 抄四个数：加载耗时（`Model loaded!` → `System prompt processed!`）、
  设置页 `firstToken/total/chunks`、面板首条消息首个状态变化耗时、批测 `平均=Nms`。
- **预期**：四组数记入报告并**标注「x86_64 模拟器口径，不得当真机指标引用」**（对应 handoff 第八节）。
- **证据**：`grep` 出的原始日志行 + 填好数字的表格。

### TC-22 【P2】面板 ASCII 正向尝试（`28`）
- **前置**：模型在位已加载。
- **步骤**：面板输入 `28` → 发送 → 等终态。
- **预期**：三者之一即可：出记账卡（转 TC-17）/ 出 `{"reply":…}` 正文（正文非空且无 JSON 报错）/
  失败但显示**具体原因**。禁止：空白无响应、只显示修复前的通用兜底话术。
- **证据**：终态截图 + 对应 logcat 行。

### TC-23 【P2】非记账输入的输出质量留档（对应 A3/B2）
- **前置**：同上。
- **步骤**：面板与对话页各发一次 `hello`，把 logcat 里 `无法解析模型输出: ` 后的**模型原始输出**
  （前 200 字）原样抄进报告。
- **预期**：只做记录与归类（复述提示词 / 自言自语 / 空输出 / 合法 JSON），**不要求修复**——
  用于后续调提示词与 `DEFAULT_SAMPLER_TEMP=0.3`；同时确认界面侧降级文案与之一致（TC-05 已覆盖）。
- **证据**：原始输出片段 + 归类结论。

### TC-24 【P2】【验收F】单测基线不回退
- **前置**：不占用模拟器，可与其它用例**并行**（唯一可并行项）。
- **步骤**：`cd /home/dzsun/projects/ai-bookkeeping && tools/build.sh :app:testDebugUnitTest`
- **预期**：BUILD SUCCESSFUL，**0 失败**（基线 300+ 用例；新增的 `SparkSessionReadyTest` 一并通过）。
- **证据**：构建输出末尾的测试统计行。

---

## 执行顺序清单

> **全部 24 条都跑在同一个 `emulator-5554` 上 → 除 TC-24 外一律串行**；
> 标 **(S)** 的用例是**状态链用例**，顺序不可调、且必须在上一条的预期状态下接着跑。

```
阶段 0 · 准备（一次性）
  0.1 adb devices 确认 emulator-5554；dumpsys 确认待测包版本 + grep 新包新字符串
  0.2 0.5 确认模型文件在位（1107457856 字节）；0.6 确认能进主界面
  0.3 建库基线：select count(*) from journal → 记 N0

阶段 1 · 加载基线与「根因1」回归（顺序锁死）
  TC-01 (S) → force-stop+清日志 → TC-02 (S) → force-stop+清日志 → TC-03 (S)

阶段 2 · 功能页正向四入口
  TC-06 (S，用到 N0) → TC-17 (S，接 N0+1) → TC-07 → TC-15 (S，需先重走加载)

阶段 3 · 设置页面板
  TC-09 (S) → TC-13 (S)

阶段 4 · 负向与破坏性用例（顺序锁死，TC-04 起进入「缺模型态」）
  TC-04 (S，删模型) → TC-14 (S，趁缺模型态) → TC-19 (S，恢复+字节校验)
  → TC-11 (S，需模型在位) → TC-10 (S，卸载态，跑完自行恢复)

阶段 5 · 非 JSON 与降级
  TC-05 (S) → TC-16 (S，接 TC-05 失败态) → TC-22 → TC-23

阶段 6 · 记一笔解析链路
  TC-08 → TC-20

阶段 7 · 收尾
  TC-12（全窗口汇总，必须最后跑） → TC-21（抄数） → TC-24（可随时/并行）
```

**串行硬约束**
1. 所有用例共用一台模拟器、一个进程、一份 logcat 缓冲：**任何两条不得并发**（会互相污染
   `Model loaded!` 计数与崩溃归因）。唯一例外 TC-24（纯 JVM 单测，不碰设备）。
2. **回归判据类（TC-02/TC-15）必须在干净窗口里跑**：每条开始前 `force-stop + logcat -c`，
   跑的过程中不得切别的用例。
3. **破坏性用例必须成组**：TC-04 → TC-14 → TC-19 是一个不可拆的「删-验-恢复」链，
   中间插入其它用例会让后续判据失效；跑完必须用 TC-19 恢复并校验字节数，**不得留缺模型态收工**。
4. TC-12 必须压轴：它统计的是整个会话的窗口，提前跑会漏。

**本轮不覆盖（对应 handoff 第七节 G）**：中端真机（8GB）arm64 复验 —— 本用例集全部结论仅
x86_64 模拟器有效，**模拟器结论不采信**，需另起真机轮次把 TC-01~TC-14 重跑一遍。
