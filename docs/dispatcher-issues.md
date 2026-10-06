# 调度层实测问题清单

来自记账 APP（消费端）一侧。全部是在**真实运行**的调度层上复现出来的，不是读代码推测的。

**测试环境**：`smart-dispatcher` 本地启动（`127.0.0.1:8010`，8000 被别的服务占着），
策略版本 `pv_2026-10-01_03`，档位 `cheap`/`standard` 均为 `mimo-v2.6-flash`，
`executable_handlers: [bookkeeping, calendar]`。

**测试用的输入**：一张合成的微信支付截图（720×1100，¥38.50，星巴克咖啡（国贸店），
2026-10-06 14:32:07），以及纯文本「午饭花了38」。

---

## P0-1　纯文本记账会被套用需要图片的流程模板（**间歇性**）

> **2026-10-06 修正**：这条最初被我写成「必然失败」，那是**错的**。
> 后续用另一段文本（`lunch 38`）复现时，路由走的是 `single_tool_action`、
> `decompose: false`，任务正常跑完并 `succeeded`。**路由是 LLM 判断，
> 同一个意图的失败取决于当次路由选了什么**，所以这是间歇性缺陷而非必现。
> 下面这个复现仍然是真实发生过的，但不要理解成「这条意图完全不能用」。

复现：

```bash
curl -s -X POST http://127.0.0.1:8010/v1/tasks \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: bookkeeping:local:probe-001" \
  -d '{
    "identity": {"user_id": "local"},
    "input": {"text": "午饭花了38"},
    "declared": {"intent": "bookkeeping.capture_from_text"},
    "idempotency_key": "bookkeeping:local:probe-001"
  }'
```

结果：路由到 `multi_step_analysis`，拆解器选中 `receipt_to_entry` 模板，
第一个节点 `extract` 绑定的是 `$ref: "envelope.input.media[0].media_id"`：

```
事件 subtask.failed  {"subtask_id":"extract",
                     "error":{"code":"bad_input_reference","retryable":false},
                     "on_failure_applied":"fail_task"}
事件 task.failed     detail: 节点输入引用无法解析：'envelope.input.media[0].media_id'（下标 0 越界）
```

`receipt_to_entry.yaml` 自己的 `applies_when` 写着：

> 输入中含有一张主要作为凭证的图像……**若图像只是顺带提供上下文而任务实质是回答问题，则不适用。**

**模板的命中判定没有考虑输入模态**，或拆解器没拿到模态信息（`profile.modality` 里其实有
`["text"]`，说明信息是有的，只是没参与判定）。

**建议**：模板命中条件里加入模态约束；或给文本记账补一个 `text_to_entry` 模板
（parse → classify → write），与票据模板同构——路线图里给日程加流程模板那次是同样的修法。

---

## P0-2　节点执行不传 `options`，深度思考一直开着，把超时撑爆

带图截图任务的实测结果：

```
subtask.completed subtask_id=extract   latency_ms=31749      ← 模板里写 timeout_ms: 20000
subtask.failed    subtask_id=normalize error=upstream_llm_error
                  detail: 节点 normalize 失败：请求超时（8.0s，档位 standard）：
```

抽取本身完全正确（`amount 38.5 / merchant 星巴克咖啡（国贸店）/ datetime 2026-10-06 14:32:07 /
direction expense / confidence 0.98`），却因为 `normalize` 超时整单失败。

**根因**：`evaluator` / `router` / `decomposer` 三个阶段各自在策略里传了
`options: { thinking: { type: disabled } }`（`routing.policy.yaml:243,272` 等），
但**节点执行那条路径完全不传 `options`**——于是走供应商默认，而
`mimo-v2.6-flash` / `mimo-v2.6-pro` 的深度思考**默认开启**。

供应商文档原话：「开启深度思考会增加响应延迟，复杂任务尤为明显」。

`normalize` 是商户归类，不是推理密集型任务；`extract` 是视觉抽取，同理。思考在这里
只贡献延迟和按输出计费的 reasoning token。

**建议**：让执行器读 `node_defaults.options` 并透传给 `ctx.llm(..., options=...)`，
之后在策略里写 `node_defaults: { options: { thinking: { type: disabled } } }` 即可，
纯配置生效。拆解阶段的思考不受影响（它用的是 `decomposer.options`，另一个字段）。

> 这条与路线图 M1 验收结论里记过的「超时值拍脑袋填」是同一类问题。
> 当时的教训是「数值没对着真实延迟校准」，这次是「开关没传到该传的地方」。

---

## P1-3　`node_defaults` 是死配置，从来没被读过

```bash
$ grep -rn "node_defaults" --include=*.py --include=*.yaml .
dispatcher/core/policy.py:130:    node_defaults: dict[str, Any]        # 仅声明
config/routing.policy.yaml:300:  node_defaults:                        # 仅配置
```

**没有任何代码读它。** 也就是说配置里写的 `timeout_ms: 15000` 与 `retry` 从未生效过——
现在之所以看不出问题，只是因为 `receipt_to_entry.yaml` 给每个节点都显式写了这两个值。
一旦有人依赖默认值，就会踩空。

与 P0-2 是同一处，修 P0-2 时会顺带修活这个字段。

---

## P1-4　`bookkeeping.*` 四个 schema 被引用但从未定义

`bookkeeping.ReceiptFields` / `Merchant` / `DedupeResult` / `LedgerEntry` 在
`config/flow_templates/`、`examples/handlers/`、`fixtures/` 里都被引用，
但 `schemas/` 下没有对应文件。

消费端要渲染结果、本地表要落库，都必须知道形状。我从实测中把前两个打了出来：

```jsonc
// bookkeeping.ReceiptFields —— 取自 extract 节点的 subtask.completed.output
{ "amount": 38.5, "currency": "CNY", "merchant": "星巴克咖啡（国贸店）",
  "datetime": "2026-10-06 14:32:07", "payment_method": null,
  "direction": "expense", "confidence": 0.98, "notes": "…" }

// bookkeeping.DedupeResult
{ "duplicate": false, "matched_entry_id": null }
```

**`bookkeeping.LedgerEntry`** —— 取自一次跑通的任务的 `artifacts.main.entry`
（文本记账，路由 `single_tool_action`，澄清用 `free_text` 答复后 `succeeded`）：

```jsonc
{ "amount": 38.0, "currency": "CNY", "merchant": null, "category": "lunch",
  "direction": "expense", "id": "e_1", "_token": "task_…:main" }
```

`_token` 与契约承诺的「按 `(task_id, subtask_id)` 派生的稳定幂等 token」一致。

`Merchant` 仍未拿到（`normalize` 那步还没跑通过）。
**建议补上这四个 schema**，消费端已经照实测形状写了映射，形状一冻结才好对齐。

---

## P1-4b　`clarify` 与 `feedback` 的 `edits` 同名不同形，且 clarify 端点没有校验

两处的 `edits` 形状与含义都不同：

| 端点 | 形状 | 含义 |
|---|---|---|
| `clarify` | `type: [object, "null"]` | `prior = {**prior, **edits}` —— **合并进 partial_profile** |
| `feedback` | `[{field, from, to}]` 数组 | 自进化的真值标注 |

同名不同形本身容易混，更麻烦的是**两个端点都用裸 `request.json()`，没有 Pydantic 校验**：

- `clarify` 传了数组 → `{**prior, **list}` 抛 `TypeError` → **500**
- 传了契约里不存在的字段名（比如把 `answer_id` 写成 `option_id`）→ **静默忽略**，
  用户答了等于没答，而且不报错

第二类尤其危险：客户端写错字段名不会有任何反馈。**建议给这两个端点加上请求模型校验**
（哪怕只是 `extra="forbid"`），让错值在边界上就被拒。

另外 `clarify` 的 `edits` 语义在 openapi 里只写了「用户对已抽取字段的修改」，
但实现是拿它补画像的。**两处描述应统一**，否则消费端会把字段修正发到这里，
而它其实该走 `feedback`。

---

## 补充：澄清可能没有选项，客户端必须有自由文本入口

实测评估器下发的澄清：

```jsonc
{ "question_id": "q_411d83ab",
  "question": "你想记一笔关于午餐的账吗？请补充金额和支付方式（如现金/微信/支付宝）。",
  "options": [],          // ← 空
  "blocking": true }
```

`options` 为空，而契约的状态机要求 `blocking: true` 的任务必须答复才能继续。
此时唯一的路是 `free_text`（实现侧 `reply = answer.get("free_text") or answer.get("answer_id")`）。

**建议**：在 `PendingClarification` 的文档里写明「`options` 可以为空，此时客户端
应提供自由文本输入」。否则界面按「渲染选项按钮」实现，用户会撞上一个没有按钮的问题。

（实测确认：用 `free_text` 答复后任务从 `awaiting_clarification` 正常恢复到
`succeeded`，这条路本身是通的。）

---

## P1-5　`openapi.yaml` 声明接受 HEIC，策略里并不接受

```
openapi.yaml:345                     image/heic         ← 声明接受
config/routing.policy.yaml:333       [image/jpeg, image/png, image/webp]
docs/05-media.md                     「最初这里还列着 image/heic……已被移除」
```

实测确认策略才是真的：

```bash
curl -X POST .../v1/media -H "Content-Type: image/heic" --data-binary @x.png
→ 415 {"code":"unsupported_media",
       "detail":"不支持的类型 image/heic；允许：['image/jpeg', 'image/png', 'image/webp']"}
```

**只有 openapi 没同步。** 对客户端尤其要紧：部分安卓机型相机默认输出 HEIF，
用户会在「模型那一跳」才失败——钱花了，错误出现在离原因很远的地方。

---

## P2-6　事件重放没有条数上限

契约只说按 `seq` 重放，没说单次最多回放多少条。断线久了可能一次推几千条，
移动端会被打爆。建议给 `/events` 加 `limit` 或分页语义。

（顺带确认一件做对了的事：`heartbeat` 事件复用上一个事件的 `id`，不推进重放游标——
这是对的，客户端不需要为它做特判。）

---

## P2-7　只有 `bearerAuth` 声明，没有发放 token 的端点

`openapi.yaml:29` 全局 `security: bearerAuth: []`（`bearerFormat: JWT`），
但没有任何获取/续期 token 的端点。移动端必须知道怎么拿和怎么续。

---

## P2-8　错误细节为空，排查靠猜

```
detail: 节点 normalize 失败：请求超时（8.0s，档位 standard）：
```

冒号后面**什么都没有**。上游返回的真实原因没有传上来。这条本身不致命，
但上面几个问题排查时都得靠猜，成本都摊在这里了。

---

## 附：已经确认没问题的部分

免得上面一长串让人觉得整条链都是坏的——契约对齐得好的地方：

| 能力 | 结果 |
|---|---|
| `POST /v1/media` | 响应形状与契约完全一致 |
| `Problem` 错误体 | RFC 9457 形状正确，`code`/`retryable` 齐全 |
| `POST /v1/tasks` | 与 `TaskAccepted` 一致（注意 `events_url` 是相对路径） |
| SSE 帧格式 | `event:` / `id:` / `data:` + 空行，与规范一致 |
| `Last-Event-ID` 重放 | 实测带 `5` 重连，正确地从 seq 6 开始回放 |
| `GET /v1/tasks/{id}` | 与 `TaskSnapshot` 一致 |
| 视觉抽取质量 | 合成支付截图的金额/商户/时间/方向全部抽对，confidence 0.98 |
