# 数据层状态（`core/**`、`tools/**`）

> 每次收工更新本文件。项目经理直接读这里，不必翻 `AGENTS.md` 的长回话区。
> 最后更新：**2026-10-06**
>
> 口径：只写**有机械证据**的结论（测试名、复现命令、提交哈希）。没验过的一律标「未验证」。

---

## 一、产出清单

| 模块 | 内容 | 测试 |
|---|---|---|
| `core/money` | `Money` 值对象。金额一律整数最小单位，小数位查 `java.util.Currency` | `MoneyTest` 13 |
| `core/ledger` | `JournalDraft`（构造即校验复式平衡）+ `LedgerRepository` + 科目表装载 | `JournalDraftTest` 15 |
| `core/database` | Room 三表 `account`/`journal`/`posting` + `journal_item`，DAO 与投影查询 | 编译期校验 |
| `core/network` | 调度层契约 DTO、`DispatcherClient`（六端点 + SSE 重放）、`CaptureClient` | `CaptureModelsTest` 14、`CaptureClientLiveTest` 2 |
| `core/statement` | 流水导入：ZipCrypto 解密、CSV 与 xlsx 解析、入库管道 | `CsvStatementParserTest` 15、`StatementArchiveTest` 8、`StatementImporterMappingTest` 9、`StatementRealFormatTest` 12、`StatementRealFileTest` 2（默认跳过） |
| `core/update` | 在线更新：版本检查、下载校验、交系统安装器 | `UpdateModelsTest` |
| `tools/` | `build.sh` / `setup-toolchain.sh` / `run-emulator.sh` / `serve-apk.sh` | — |

**构建**：`tools/build.sh :app:testDebugUnitTest :app:assembleDebug` → **BUILD SUCCESSFUL**，
**147 个测试 0 失败 4 跳过**（跳过的是 4 个联调用例，见第三节和第六节）。

---

## 二、已定决定（不打算再改的）

1. **金额绝不用浮点。** 契约传十进制主单位（`38.5`），进账本前一律转最小单位整数。
   `ReceiptFields.amount` 刻意是 `JsonElement` 而非 `Double`：走 JSON 字面量直接转
   `BigDecimal`，全程不经过浮点。证据：`CaptureModelsTest` 断言 `38.5 → 3850`。
2. **存储用复式，交互用单式。** 服务端只给方向与分类名（`LedgerEntry`），
   借贷映射在本机由 `JournalDraft` 完成——后端 handler 跑在服务端，写不进手机上这台 Room。
3. **去重只给候选，不自动合并。** 窗口默认前后 3 天，比对金额与时间窗，**不比商户名**。
4. **明细不参与记账。** `journal_item` 只描述「买了什么」，金额以 `posting` 为准，
   不校验「明细之和 == 总额」（折扣、税、抹零会让两者天然不等）。
5. **失败也要带出已完成节点的产物。** 见第四节，这是本轮修掉的一个真 bug。

---

## 三、验证状态

### 已对着真实调度层跑通（`127.0.0.1:8010`）

可重复执行，默认跳过，不影响日常构建：

```bash
DISPATCHER_LIVE=1 DISPATCHER_LIVE_RECEIPT=/tmp/receipt.png \
  tools/build.sh :app:testDebugUnitTest --tests "*CaptureClientLiveTest*" --rerun-tasks
```

| 场景 | 结果 |
|---|---|
| 收据截图 → 上传 → 提交 → 终局 | ✓ `Completed`，`entryId=task_…:write`，`category=餐饮`，`notes=商品说明：拿铁 大杯` |
| 纯文本「午饭花了38」→ 终局 | ✓ 文本任务实测多数 `succeeded`；失败时给出可读 `Problem` |
| `POST /v1/media` / `Problem` / `TaskAccepted` / `TaskSnapshot` 形状 | ✓ 与 DTO 一致 |
| `Last-Event-ID` 重放 | ✓ 帧格式一致；**但见 P0-4，服务端流当前是坏的** |

### 未验证

- 真机（非模拟器）路径。`DEFAULT_BASE_URL` 是 `10.0.2.2`，真机上无效，需在设置页改局域网地址。
- 澄清答复后的恢复路径（`clarify` → `running` → 终局）。dispatcher 正在修 P0-1c 的恢复分支。
- 流水导入的 xlsx（微信）路径——本轮正在做，见第六节。

---

## 四、本轮修掉的客户端 bug：失败时丢弃快照产物

**症状**：任务整单失败时，快照 `artifacts` 里明明有 `extract` 抽好的字段，
客户端却给出 `partialFields = null`——用户白拍一张。

**根因**：`CaptureOutcome` 的失败分支只认事件流攒下来的 `fields`，从不回看快照。
而事件流可能压根没活着（SSE 服务端坏了、退后台断连），那时 `fields` 恒为 null。

**证据**：`CaptureModelsTest.任务失败时已完成节点的产物必须带出来`——修之前
`expected:<3850> but was:<null>`，修之后通过。

**顺带核实的一步**：PM 怀疑的另一半「服务端本来就没有产物可带」**也是真的**。
实测 5/5 次文本任务失败，快照 `artifacts` 都是 `{}`（拆解阶段就崩，一个节点都没产出）。
依据两条：
- 连续 5 次 `POST /v1/tasks`（`capture_from_text`）→ 全部 `failed`，`artifacts: {}`，
  错误是 `节点 dedupe_check/write_entry/record 失败：节点输入引用无法解析`
- 服务端 `dispatcher/core/runner.py:450` 收集 **所有产出过输出的节点**，与任务成败无关
  ——即「有产物就必须保留」这条契约服务端是实现了的

所以联调用例的断言改成了**回查快照再比**：服务端没产物时不假红，
客户端吞产物时必定红。没有把它改成恒真。

---

## 五、阻塞 / 需要后端（`smart-dispatcher`）处理

| 编号 | 问题 | 影响 | 状态 |
|---|---|---|---|
| P0-1c | `authoritative: true` 时拆解走到 `record_expense` 模板，节点输入引用解析不了（`envelope.user_text` 断了）→ 整单失败；另有间歇性 `strategy=single_step 但节点数为 0` 直接 422 | 客户端已**临时**改用 `false` | 后端修中 |
| **P0-4** | **SSE 端点当前 100% 不可用**：`app.py` 的 `gen()` 里 `to_sse` 被函数内的 import 绑成局部变量，正常路径上 `UnboundLocalError` → 连接立即断，**0 字节** | 客户端只能靠快照轮询兜底 | 见下 |
| P0-2 | `ReceiptFields` 缺明细行（小票上的「拿铁 大杯」没被抽出来，被塞进 `notes`） | `journal_item` 表有生产者缺口 | 已提单 |
| — | HEIC：`openapi.yaml` 声明接受，`routing.policy.yaml` 白名单里没有 | 部分机型相机默认输出 HEIF，模型那跳才失败 | 客户端自行转 JPEG |

**P0-4 复现**（当前 dispatcher 代码）：

```bash
curl -s -N "http://127.0.0.1:8010/v1/tasks/<task_id>/events" -w "[bytes=%{size_download}]\n"
# → [bytes=0]，服务端日志：
# UnboundLocalError: cannot access local variable 'to_sse' where it is not associated with a value
```

位置：`dispatcher/interface/app.py`，`gen()` 里 `from ..core.events import EventRecord, to_sse`
写在 `if len(backlog) > replay_limit:` 分支内，Python 因此把 `to_sse` 视为整个函数的局部名，
走到 `yield to_sse(ev)` 时未绑定。模块顶部那行只 import 了 `to_sse_heartbeat`。
修法：把 `to_sse` 提到模块顶部导入。

**客户端的应对**（不是替代修复）：事件流中途断开（`IOException`）或没给出终态就结束时，
退回按快照轮询，而不是把服务端已经跑完的任务当成失败。
`CancellationException` 不吞——吞了外层 `withTimeout` 就失效。

---

## 六、P0-c 真实账单格式定稿（已完成）

按 `docs/statement-formats-real.md` 改动，并用**真实导出账单**在本机验过。

### 验证结果（真实文件只在本机跑，不进仓库）

```bash
STATEMENT_REAL_ALIPAY="/path/支付宝交易明细.csv" \
STATEMENT_REAL_WECHAT="/path/微信支付账单.xlsx" \
  tools/build.sh :app:testDebugUnitTest --tests "*StatementRealFileTest*" --rerun-tasks
```

| 账单 | 消费 | 排除 | 不可用 |
|---|---|---|---|
| 支付宝 CSV（596 笔） | 521 | 75 | **0** |
| 微信 xlsx（89 笔） | 63 | 26 | **0** |

排除项与账单自己的声明逐条对得上：
支付宝「不计收支」34 笔、支出侧「交易关闭」7 笔；微信收/支为 `/` 的 8 笔。

### 四个坑的实际结论

| 坑 | 结论 |
|---|---|
| GBK 编码 | 改对了。解码顺序里 **GB18030 替掉 GBK**——前者是后者的严格超集，并列只会让后者永远轮不到（死配置） |
| 23 行回单头 | **原来就没事**。表头判据是「能对上三列以上」，不写死行号 |
| 尾逗号 | **原来就没事**。表头与数据行都多一个空字段，列数仍然一致 |
| 单号带 `\t` | **原来就没事**。切格后统一 `trim()` |

**真正致命的是第 5 个（文档里没列为坑）**：`ref` 列的别名。
支付宝的单号列叫「**交易订单号**」，配置里写的是「交易号」，而列名匹配是
**精确相等**——认不出来就会因为「缺少必需列 ref」让整份账单解析失败。

### 改了什么

1. **`StatementDirection` 多一档 `NEUTRAL`**。原先把「不计收支」当成
   「收支方向无法识别」报 `Unusable`，用户看到会以为文件坏了——其实它文件没坏，
   只是这行不该入账。现在落 `NotConsumption` 并说明是资金转移。
   **微信那一档实测写的是 `/`**，不是文档推测的「中性交易」；两个都收。
2. **交易状态「交易关闭」不导入**（新增配置项 `skip_statuses`）。
   **但没有写成「非成功即排除」**：微信的「已全额退款」是成交后退的，钱动过，
   将来要靠它发现状态变化并冲减。
3. **微信 xlsx 输入**。xlsx 就是 zip + 几份 XML，自己读的（共享字符串、单元格、
   `styles.xml`），**没有引入任何依赖**。日期必须读样式才知道：
   Excel 里日期就是个数字（`46301.83` = 2026-10-06），靠 `s="1"` → `numFmtId=164`
   认出来；不读样式整列时间全废。金额列 `¥#,##0.00` 则不能被误判成日期。
4. **重构**：找表头 / 认格式 / 判收支 / 排除非消费 抽到 `StatementRowParser`，
   CSV 与 xlsx 共用。两边各写一份的话，改了 CSV 忘了改 xlsx，同一份账单换个格式
   导入结果就会不同，而这种不一致极难发现。

### 测试

- `StatementRealFormatTest` 12 例，**全合成脱敏**：四个坑各一条断言，
  支付宝三档收支、微信 `/`、日期序列号、金额不被误判成日期。
  xlsx 夹具在代码里现造（二进制进仓库既不能逐字审阅，也说不清 `numFmtId` 是什么）。
- `StatementRealFileTest` 2 例，**默认跳过**，只从环境变量取路径——
  真实账单含姓名/手机号/完整交易记录，路径与内容都不进仓库。

### 已知边界（记在这里，别当 bug）

- **退款行会被当成消费导入**。微信实测 4 笔「已全额退款」+1 笔「已退款¥6.60」，
  收/支 列仍写「支出」/「收入」。这是**有意的**：`externalStatus` 存住平台侧原状态，
  将来再导入时靠它发现变化并冲减（P1-d）。落地前支出会略微高估。
- **列数少于表头的行会被当成统计行跳过**，只报条数不报内容。真实账单里
  数据行不会少列（空的也占位），所以概率低；但真发生时会表现为
  「跳过了 N 行说明/统计行」而不是报错。
- xlsx 只读**第一个工作表**，且不处理公式求值（只取缓存结果）。

---

## 七、P0-a 置信度阈值（数据层部分已完成）

产品决定：高置信直接入账（事后可撤销），低置信才弹确认卡。
数据层的分工是「值从哪来 / 阈值是多少 / 谁说了算」，界面只读结论。

| 件 | 在哪 |
|---|---|
| 整体置信度 | `CaptureOutcome.Completed.confidence: Float?` |
| 字段级置信度 | `Completed.fieldConfidence` / `ReceiptFields.fieldConfidence`；**服务端当前不产出，恒 null** |
| 判定 | `core/ledger/ConfidenceGate`（纯函数，无 Android 依赖） |
| 阈值真源 | `assets/entry_rules.json`，由 `EntryRuleCatalog` 读，读不到兜底 0.85 |

三条口径：

1. **`confidence == null` 一律要确认。** 三种真实来路：老服务端不给、
   纯文本走 `single_tool_action`（产物无此字段）、事件流断了只回看到 `LedgerEntry`。
   把 null 当成可信，就会在这几条路上**静默**跳过用户确认。
2. **取等号算达标**（`>=`）。写 `>` 会让用户设 0.85 时实际生效 0.86。
3. **阈值出厂默认与可调范围出自同一份配置**，避免「默认值落在自己声明的范围之外」。

顺带删掉了 `ReceiptFields.isHighConfidence` 与它的 `CONFIDENCE_THRESHOLD = 0.8`——
那是个和真阈值 0.85 并排躺着的第二个数，迟早有人抓错。

**`field_confidence` 是一次事实更正**：PM 的任务描述说它已存在，实测不存在
（schema 里没有、dispatcher 全仓 grep 为空）。按契约留了口子，但界面**别把它
当作可用信号**。

---

## 八、下一步

- **`LedgerRepository.void(journalId)`**（界面提的、P0-a 的缺口）：
  高置信自动入账要配「事后可撤销」，而现在 `LedgerRepository` 只有 `post`。
  该跟 `JournalStatus.VOID` 对齐（作废留痕而非删除，分录跟着一并作废），
  而不是直接删行——删行会绕过凭证与分录的事务，留下孤儿分录。
  界面的 `UnavailableAutoEntryUndo` 占位是对的（没能力就不渲染按钮）。
- P0-b 已完成：`authoritative` 已切回 `true` 并经真实调度层验证（纯文本 4/4 succeeded，
  收据走完 extract→normalize→dedupe→write）。
- P1-d 退款冲减：`statusChanged` 现在只能看不能办，需要能落库的形状
  （负向金额或冲减标记）。
- 阈值 0.85 仍是起步值，**没有照真实使用校准过**——攒够「用户改了多少笔自动入账的账」
  之后再调 `entry_rules.json`。
