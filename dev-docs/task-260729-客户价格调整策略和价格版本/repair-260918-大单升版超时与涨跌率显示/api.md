# api · repair-260918 大单升版超时与涨跌率显示（v2 · 2026-09-18）

> 本次**无新增 / 删除端点、无路径与参数变更**，只有响应字段的**加法**（`JobDTO.running`、审核列表项 `budgetError`）、三个错误码取值的**加法**，以及两处重试行为修正。
> 合并前按 `docs/rules/task-docs.md §2.5` 回写 `dev-docs/main-api.md` 的对应端点小节。

## 1. `GET /api/cpq/price-adjust/jobs/{jobId}` —— 响应新增 `running`

| 字段 | 类型 | 说明 |
|---|---|---|
| `running` 🆕 | int | 该批次当前 `status = 'RUNNING'` 的明细数。明细开始执行前即提交为 `RUNNING`（B-5），所以执行期间可见 |

其余字段（`jobId / customerNo / versionNo / triggeredBy / triggeredAt / status / total / success / failed / conflict / stale / skipped / finishedAt / notified`）**不变**。

示例（执行中）：

```json
{"jobId":"526e5d91-2573-47e5-a5d4-4acc0a73603c","customerNo":"CUST-0004","versionNo":"V26091802",
 "status":"RUNNING","total":7,"success":3,"failed":0,"conflict":0,"stale":0,"skipped":0,"running":1,
 "finishedAt":null,"notified":false}
```

> `GET /api/cpq/price-adjust/jobs`（列表）若复用同一 DTO，同样带 `running`，计数须一条 `GROUP BY job_id` 批量取（🚫 逐批次查）。

## 2. `GET /api/cpq/price-adjust/jobs/{jobId}/items` —— `errorCode` 新增三个取值

| `errorCode` | 何时出现 | `status` | `errorMessage`（固定文案） | 可重试 |
|---|---|---|---|---|
| `EXECUTION_TIMEOUT` 🆕 | 明细执行被事务超时 / 语句取消中止 | `FAILED` | `执行超时（超过 60 秒）被中止，本行未更新，可重试` | ✅ |
| `REVISION_WRITE_FAILED` 🆕 | 同单多行合并执行时，行已升版成功，但最后写本期版本记录失败 | `FAILED` | `价格已更新，但版本记录写入失败，请重试` | ✅（重试走单条完整路径，同版本重跑结果不变） |
| `EXECUTION_INTERRUPTED` 🆕 | 服务重启时该明细处于「等待」或「执行中」，启动收尾（B-11）将其判为中断 | `FAILED` | `执行中断（服务重启），本行未更新，可重试` | ✅ |
| `UNEXPECTED_ERROR`（既有，**文案变了**） | 其他未预期异常 | `FAILED` | 由 `Error invoking subclass method` 改为 `<根因简单类名>: <根因信息>` | ✅ |

其余取值（`ROW_VERSION_CONFLICT` / `SUBTOTAL_MISMATCH` / `JOB_NOT_FOUND` / `LINE_ITEM_MISSING` / 升版返回的业务错误码）**不变**。

## 2.5 `POST /api/cpq/price-adjust/jobs/{jobId}/retry`（批量重试）—— 行为修正（契约不变）

仍返回 `202`。**修正**：重新执行本批 **`FAILED` + `CONFLICT`** 明细（`STALE` 不动），与本端点注释「§3.4 批量重试该批次全部 FAILED + CONFLICT 项」一致。原先只捞 `WAITING` / `CONFLICT`，失败明细从不被重跑。

## 3. `POST /api/cpq/price-adjust/job-items/{itemId}/retry` —— 行为修正（契约不变）

仍返回 `202`。**修正**：重试执行抛异常时，明细按第 2 节口径落库为 `FAILED`，并重新汇总批次计数（原先异常时明细停在旧状态、批次不重算）。

## 4. 审核待办池 `GET /api/cpq/price-adjust/reviews` —— 取值范围不变，出现场景增加

`budgetStatus = FAILED` 早已是合法取值。本次起，**预算试算抛异常（含超时）的料号也会以 `FAILED` 出现在池中**（原先这类料号不出现）。

**列表项新增字段（D-8，2026-09-18 开发中裁决）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `budgetError` 🆕 | string \| null | 预算失败原因（`material_price_review.budget_error`）。超时文案为 `预算试算超时（超过 60 秒）`；预算正常时为 null。⚠️ v2 文档曾暗示该字段已存在，实际原先不返回 |

`GET /api/cpq/price-adjust/reviews/{reviewId}`（详情）**不加**此字段。

## 5. 涨跌率 `changeRate` —— 精度变化（类型不变）

出现在：版本明细 `GET …/versions/{versionId}/items`、元素矩阵、审核详情 `elementChanges[].changeRate`。
仍是十进制字符串、小数形式（`0.000035` = 0.0035%）。**新生成版本**的值精度由 6 位小数变为 **12 位**（如 `0.000000034611`）；已生成版本的值不回算。

## 6. 零改动说明

- `GET /api/cpq/price-adjust/reviews/{reviewId}`（审核详情）：**响应结构不变**。变化只在耗时（试算不再拍整单快照）。
- `POST /api/cpq/price-adjust/reviews/approve`：不变（仍是同步推进指针 + 异步执行，202）。
- 无数据库迁移。
- **非接口的运行时契约**：新配置项 `cpq.price-adjust.startup-recovery.enabled`（默认 `true`，测试 profile 为 `false`）。开启的实例在启动时收尾中断批次、续跑未完成的预算（B-11 / B-12）。🔒 前提：后端单实例部署；临时后端必须显式关闭。
