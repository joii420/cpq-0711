# api · repair-0807 更正任务价格丢失与版本错乱

- 对应需求文档：`./需求文档.md`
- 本文件是本次修复的**局部契约**。测试通过后按任务平台规则 §2.4 回写 `dev-docs/main-api.md`（按端点整段覆盖 + 标来源任务）。
- 本次**不新增端点、不改路径、不改鉴权**，只改两个既有端点的响应结构与一个枚举的取值域。

---

## 变更总览

| 端点 | 变更类型 | 说明 |
|---|---|---|
| `GET /api/cpq/price-adjust/reviews/{reviewId}` | 响应字段新增 + 既有字段口径修正 | FR-5 / FR-6 |
| `GET /api/cpq/price-adjust/jobs/{jobId}/items` | 枚举取值域扩展 | FR-4 |
| `GET /api/cpq/price-adjust/jobs/{jobId}` · `GET /api/cpq/price-adjust/jobs` | 响应字段新增 | FR-4 |

⚠️ 三处**全部向后兼容**：新增字段、扩枚举，不删不改类型。前端不同步升级也不会崩，只是看不到新信息。

---

## 1. `GET /api/cpq/price-adjust/reviews/{reviewId}` —— 审核抽屉详情

**鉴权**：登录态（沿用现状）
**对应 FR**：FR-5、FR-6

### 1.1 响应结构（变更部分，其余字段不变）

```jsonc
{
  "reviewId": "a6a2e769-…",
  "materialNo": "3120011203",
  "materialName": "3120011203",
  "currentVersionNo": "V26080701",
  "targetVersionNo": "V26080702",

  // 一、为什么变
  "elementChanges": [
    {
      "elementCode": "Ag",
      "elementName": "银",
      "matchedRule": "客户调价策略",
      "previousPrice": 2500.000000,
      "currentPrice":  3000.000000,
      "changeRate": 0.2,
      "usageQty":        0.001159,   // ★ 变更：原恒为 null，现按 FR-6 算出
      "unitPriceImpact": 0.587137,   // ★ 变更：原恒为 null，现按 FR-6 算出
      "noPrice": false,
      "inheritedFromPrevious": false
    }
  ],
  // ★ 口径修正：原取 col-default 的 diffAdjusted（= 报价·调整后 − 核价·调整后，本例 18.59），
  //   现取「判断依据单 dryRun 后 subtotal − 现 subtotal」（本例 0.587137）
  "elementImpactTotal": 0.587137,

  // 三、下钻
  "quotations": [
    {
      "quotationId": "f768265c-…",
      "quotationNo": "QT-20260807-0140",
      "createdAt": "2026-08-08T00:55:36Z",
      "status": "DRAFT",
      "isBasis": true,
      "quoteSubtotalCurrent": 18.002767,
      "quoteSubtotalAdjusted": 18.587137,  // ★ 变更：原后端零赋值点 → 恒 null；现仅判断依据行有真值
      "adjustedComputed": true,            // ★ 新增
      "comparisonViewUrl": "/quotations/f768265c-…/comparison"
    },
    {
      "quotationId": "4c429bf4-…",
      "quotationNo": "QT-20260807-0127",
      "status": "DRAFT",
      "isBasis": false,
      "quoteSubtotalCurrent": 83.290199,
      "quoteSubtotalAdjusted": null,
      "adjustedComputed": false,           // ★ 新增：前端据此渲染「未试算」而不是「—」
      "comparisonViewUrl": "…"
    }
  ]
}
```

### 1.2 字段定义

| 字段 | 类型 | 必返 | 含义 |
|---|---|---|---|
| `elementChanges[].usageQty` | `number \| null` | 是 | 该元素在**判断依据单**该料号上的用量。算法：`unitPriceImpact ÷ (currentPrice − previousPrice)`。分母为 0 或任一价为 null → `null`（前端显示 `—`）。**不得返回 0 冒充"没有用量"** |
| `elementChanges[].unitPriceImpact` | `number \| null` | 是 | 该元素涨跌对**判断依据单**该料号单价的影响额。单元素版本 = 整体 Δ；多元素版本 = "只改该元素"的 dryRun Δ |
| `elementImpactTotal` | `number` | 是 | 合计对单价影响 = 判断依据单 `dryRun 后 subtotal − 现 subtotal`。**始终取整体 Δ，不取 Σ 明细**（D-6） |
| `quotations[].quoteSubtotalAdjusted` | `number \| null` | 是 | 调整后行小计。**仅 `isBasis=true` 的行有值**，其余恒 `null` |
| `quotations[].adjustedComputed` | `boolean` | 是 | 该行是否跑过试算。`false` → 前端渲染「未试算」；`true` 且值为 `null` → 渲染「—」（试算跑了但拿不到值，属异常态） |

### 1.3 错误码

| HTTP | 场景 | body |
|---|---|---|
| 404 | `reviewId` 不存在 | `{"code":404,"message":"review 不存在: <id>"}`（现状不变） |
| 200 | 判断依据单缺失 / 该单已被删 / dryRun 失败 | **不报错**：全部行 `adjustedComputed=false`、`elementImpactTotal=0`、明细两列 `null`。后端落 `WARN` 日志 |

> 🔒 **dryRun 失败必须降级为 200 + 留白，不得 500**：抽屉是财务的只读看板，一次试算失败不该让整个审核流程卡住。

### 1.4 性能约定

单元素版本 ≤ 3s；每多一个变价元素约 +1.6s（各跑一次 dryRun）。前端保持既有 loading 态即可，**不加超时**（超时会让财务以为坏了）。

---

## 2. `GET /api/cpq/price-adjust/jobs/{jobId}/items` —— 更新任务明细

**对应 FR**：FR-4

### 2.1 变更

`items[].status` 枚举取值域扩展：

```
WAITING | RUNNING | SUCCESS | FAILED | CONFLICT | STALE | SKIPPED   ← SKIPPED 为新增
```

```jsonc
{
  "id": "…",
  "quotationId": "…",
  "quotationNo": "QT-20260807-0128",
  "materialNo": "3120011203",
  "status": "SKIPPED",                                    // ★ 新增取值
  "errorCode": null,                                      // SKIPPED 不占用 errorCode
  "errorMessage": "冻结结构已补建，但仍无接价格策略的组件（三角色字段未配齐），无可升版内容",
  "retryCount": 0
}
```

### 2.2 语义

| 值 | 含义 | 可重试 |
|---|---|---|
| `SKIPPED` | 该单**未被更新**，且重试不会有不同结果（无价格承载组件 / 补建结构失败） | ❌ 否。前端不显示「重试」按钮 |
| `STALE` | 所属版本已被新版取代 | ❌ 否（现状） |
| `FAILED` / `CONFLICT` | 可重试（现状） | ✅ 是 |

🔒 `SKIPPED` 的 `errorCode` **保持 `null`** —— 本模块既定语义是「`errorCode` 非空 = 非成功态需人工处理」，`SKIPPED` 是设计内的"不处理"，占用 `errorCode` 会把屏 7 的可重试判定带偏（`PriceAdjustJobExecutionService:324-327` 有同款注释）。

---

## 3. `GET /api/cpq/price-adjust/jobs/{jobId}` 与 `GET /api/cpq/price-adjust/jobs`

**对应 FR**：FR-4

### 3.1 变更

```jsonc
{
  "id": "32ced881-…",
  "versionNo": "V26080702",
  "status": "PARTIAL",        // ★ 口径变更：有 SKIPPED 项时不再报 SUCCESS
  "totalCount": 32,
  "successCount": 31,
  "failedCount": 0,
  "conflictCount": 0,
  "staleCount": 0,
  "skippedCount": 1           // ★ 新增
}
```

### 3.2 批次状态判定表（`MaterialPriceUpdateJob.recountFrom` 唯一实现）

| 条件 | `status` |
|---|---|
| `failed==0 && conflict==0 && stale==0 && skipped==0` | `SUCCESS` |
| `success==0 && stale==total` | `STALE` |
| `success==0 && skipped==0` | `FAILED` |
| 其余（**含"全部成功但有跳过"**） | `PARTIAL` |

> 🚨 **"有跳过就不许报 SUCCESS"**：本次缺陷的传播路径正是"32/32 全成功"骗过了财务，而其中至少 1 张单一个字节都没改。

---

## 4. 无变更但需确认的端点

| 端点 | 结论 |
|---|---|
| `POST /api/cpq/price-adjust/reviews/approve` | 不变。仍是同步推进指针 + 异步建 job（202） |
| `POST /api/cpq/price-adjust/jobs/{jobId}/retry` | 不变。`SKIPPED` 项会被 `loadWaitingItems` 的 `status in (WAITING, CONFLICT)` 过滤掉，**不会被重试** —— 这是期望行为（FR-4） |
| `POST /api/cpq/price-adjust/job-items/{itemId}/retry` | 行为微调：`SKIPPED` 项调用时**照现状执行**（不像 `STALE` 那样抛 409）。理由：修好 FR-3 后重试有意义（结构可能已被别的路径补上），拦掉反而堵死自救通道。前端不放按钮，但接口不拒 |
| `GET /api/cpq/quotations/{id}` | 不变。价格与徽标的正确性由后端写入侧保证，响应结构不动 |
