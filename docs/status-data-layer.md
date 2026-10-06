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
| `core/statement` | 流水导入：ZipCrypto 解密、CSV 解析、入库管道 | `CsvStatementParserTest`、`StatementArchiveTest`、`StatementImporterMappingTest` |
| `core/update` | 在线更新：版本检查、下载校验、交系统安装器 | `UpdateModelsTest` |
| `tools/` | `build.sh` / `setup-toolchain.sh` / `run-emulator.sh` / `serve-apk.sh` | — |

**构建**：`tools/build.sh :app:testDebugUnitTest :app:assembleDebug` → **BUILD SUCCESSFUL**，
**111 个测试 0 失败 2 跳过**（跳过的是两个联调用例，见第三节）。

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

## 六、下一步

- **P0-c 真实账单格式定稿**（进行中）：支付宝 CSV 的 GBK / 23 行回单头 / 尾逗号 /
  「不计收支」第三档；微信 xlsx 输入格式。见 `docs/statement-formats-real.md`。
- P0-a 低置信才弹确认：需要数据层把分字段置信度暴露出去（`extract` 已带 `confidence`）。
- P0-b：dispatcher 修完 P0-1c 后把 `authoritative` 切回 `true`，
  并把联调用例里的 P0-1c 重试去掉。
