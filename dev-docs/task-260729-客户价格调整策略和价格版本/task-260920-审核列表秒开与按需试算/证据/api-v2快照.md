# api.md · 审核列表秒开与按需试算（v2）

> 前后端唯一协调物。契约有疑问一律以本文为准；改本文走 `task-docs.md §4`「开工后契约变更」四步。
> 基线：`dev-docs/main-api.md`（结案前回写，`task-docs.md §2.5`）。前缀均为 `/api/cpq/price-adjust/reviews`。
> 错误信封沿用既有：`ReviewNotReadyException` → `GlobalExceptionMapper:87-91` → `data.code` / `data.invalidItems`；前端类型 `types/price-adjust.ts:439-441`。

## 0. 总览

| 方法 · 路径 | 本次 | 说明 |
|---|---|---|
| `GET /` | 改 | 返回体 +2 字段；排序补确定次键；`breachedOnly` 语义收窄（§1） |
| `GET /{reviewId}` | 改 | 返回体 +`budgetError`；内部元素影响试算加依据单锁（§4） |
| 🆕 `POST /{reviewId}/compute-now` | 新 | 点击即算，**一律 202**（§2） |
| 🆕 `GET /{reviewId}/row` | 新 | 单行状态，供轮询；**不触发任何试算**（§2） |
| `POST /impact` | 改 | 返回体 +`materials[]`（§3） |
| `POST /approve` | **不改** | 既有守门原样保留（§5） |
| `POST /reject` | 改 | 新增预算守门（§5，`D-6`） |
| `POST /{reviewId}/recompute-budget` | 行为收紧 | 签名与异步语义不变；置「计算中」改为条件抢占（§4） |
| ~~`POST /compute-batch`~~ | — | v1 设计，**按 `J-2` 取消**（前端逐条串行调 compute-now） |

---

## 1. `GET /`（列表）

### 1.1 返回体

```jsonc
{
  "content": [
    { // …既有字段一律不变
      "budgetStatus": "QUEUED",
      "quoteCostCurrent": null, "quoteCostAdjusted": null, "costingCost": null, "diffCurrent": null, "diffAdjusted": null
      // 🔒 budgetStatus != "READY" 时上面这些金额**必须为 null**，🚫 不许返 0（前端 fmt 对 null 渲染「—」，对 0 渲染「0」）
    }
  ],
  "page": 1, "size": 20, "total": 4527,
  "notComputedTotal": 4510,       // 🆕 当前筛选条件下、审核状态为「待处理」的行中 budgetStatus ∈ (QUEUED, COMPUTING) 的总数（不是本页）
  "excludedByNotComputed": 0      // 🆕 仅 breachedOnly=true 时有意义：因未计算而未参与筛选的待处理行数；其余情况恒 0
}
```

> 🔒 **`budgetStatus` 只在 `reviewStatus="PENDING"` 时有意义**（`需求文档` R-2）。已通过 / 已驳回 / 已作废的行照常返回该字段，但**不计入** `notComputedTotal`，前端也不按它渲染「未计算 / 计算」。

### 1.2 排序

主排序 `createdAt` 倒序**不变**；补确定次键 `materialNo` 升序、`id` 升序（`J-1`）。
> 原因：批量建行后同一时刻的行成为常态，PostgreSQL 对并列行不保证顺序 ⇒ 翻页重复 / 漏行。v1 写的「排序一律不动」作废。

### 1.3 `breachedOnly=true`

条件由 `breachedCount > 0` 改为 `breachedCount > 0 AND budgetStatus = 'READY'`，并在 `excludedByNotComputed` 给出被排除的条数（前端只展示，🚫 自己算）。

---

## 2. 点击即算

### 2.1 🆕 `POST /{reviewId}/compute-now`

**语义**：插队试算该条并**写回**审核行。**一律返回 202**，结果经 §2.2 轮询取得。
> 🔒 为什么不同步返回：前端请求超时 60 秒（`api.ts:8`）与后端事务超时同为 60 秒，同步等待时一旦超时，界面显示的是网络错误而不是「预算试算超时」。

| 响应 | 何时 | body |
|---|---|---|
| **202** | 抢占成功，已开始算；或该行已在算（`COMPUTING`） | `{"reviewId":"…","budgetStatus":"COMPUTING"}` |
| **202** | 该行已是 `READY`（无需再算） | `{"reviewId":"…","budgetStatus":"READY"}` —— 前端直接取 §2.2 |
| 404 | `reviewId` 不存在 | 标准错误体 |
| 409 | 该行审核状态不是「待处理」 | `{"code":"REVIEW_NOT_PENDING","message":"该料号已不是待处理状态"}` |

可抢占的状态：`QUEUED`；`FAILED`（仅用户显式点「重新计算」时，前端才会对 FAILED 行发起）。
**版本在算的过程中被作废**：服务端带锁复核发现后**不写**，该行保持「已作废」；前端经 §2.2 轮询看到 `reviewStatus="VOIDED"`，提示「该价格版本已被新版本取代」（`AC-20`）。
**并发**（`AC-21`）：同一行并发调用只有一方抢占成功，另一方直接得到 202 + 当前状态；🚫 产生第二条审核行。

### 2.2 🆕 `GET /{reviewId}/row`

返回该行的 `ReviewListItemDTO`（字段与列表行完全一致，含 `reviewStatus` / `budgetStatus` / `budgetError` / 金额）。**不触发任何试算**（区别于 `GET /{reviewId}`，后者会跑元素影响试算）。
前端轮询建议：1 秒一次，直到 `budgetStatus ∈ (READY, FAILED)` 或 `reviewStatus ≠ PENDING`；90 秒仍未结束则停止轮询并提示「计算较慢，可稍后刷新」。

---

## 3. `POST /impact`（影响面预览）

**入参**：`{"reviewIds": [...]}` —— 前端**只传 READY 的行**（计算失败的行由前端自己在确认框顶部列出，不进本接口）。
**返回体**：既有字段（`materialCount / quotationCount / versionPaths / byStatus / excludedQuotationCount / excludedByStatus / breachedMaterials`）**一个不删**，新增：

```jsonc
"materials": [
  { "materialNo": "S3120011203",
    "quoteCostCurrent":  "0.324083231000",   // col-default 比对列，口径同列表行同名字段
    "quoteCostAdjusted": "1.717061326000",
    "diffAdjusted":      "1.717061326000",
    "status": "MISSING",                     // NORMAL | MISSING | STALE
    "missingSide": "COSTING" }               // QUOTE | COSTING | BOTH | null
]
```

> 🔒 **缺数据时按缺失侧处理，不是一律为空**：缺核价侧（`missingSide="COSTING"`）时报价侧的值照常返回、前端照常显示（实查 `V26092003` 的 1202 条缺核价侧行，全部有「报价·调整后」）。
> v1 设计的 `notComputedCount` / `computeFailedCount` / `computeFailedMaterialNos` **删除**：入参只含 READY 行后它们恒为 0，失败清单前端本来就有。

---

## 4. 既有接口的行为变化（签名不变）

| 接口 | 变化 |
|---|---|
| `GET /{reviewId}` | 返回体新增可空 `budgetError`（`B-19`）；其中的元素影响试算改为持依据单锁（`B-15`），与后台试算错开 |
| `POST /{reviewId}/recompute-budget` | 仍为异步、仍返回原响应；置「计算中」由无条件改为条件抢占（`QUEUED`/`FAILED`/`READY` → `COMPUTING`，已在算则不重复发起） |

---

## 5. 通过 / 驳回的守门

| 接口 | 规则 |
|---|---|
| `POST /approve` | **既有守门原样保留**：入参中任何 `budgetStatus≠READY`（含 `FAILED`）的行 ⇒ 整批拒绝，409 `REVIEW_BUDGET_NOT_READY` + `invalidItems`（`PriceAdjustReviewService.doApprove`）。🚫 另造错误码。前端在确认前已排除失败行，正常路径不会触发 |
| `POST /reject` | 🆕（`D-6`）入参中任何 `budgetStatus≠READY` 的行 ⇒ 整批拒绝，**同一异常类、同一信封**：409 `REVIEW_BUDGET_NOT_READY` + `invalidItems` |

---

## 6. 不新增接口的事项

| 事项 | 为什么 |
|---|---|
| `AC-13` 并行正确性哈希、输入指纹 | 只读 SQL 脚本，落 `证据/`（`B-6`） |
| 后台全量试算的进度 | 列表 `notComputedTotal` + 前端低频轮询即可表达 |
