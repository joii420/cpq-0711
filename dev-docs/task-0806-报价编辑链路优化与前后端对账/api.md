# api.md · 报价编辑链路优化与前后端对账（前后端交接契约）

> 本文件是前后端各自实现的**唯一依据**。任一方要改契约，**必须先改本文件并通知另一方**。
> 测试通过后按「任务平台规则 §2.4」把最终契约回写 `dev-docs/main-api.md`。
> 鉴权：全部端点沿用现有会话鉴权（未登录 → `401`）。

---

## 契约总览

| 编号 | 方法 + 路径 | 阶段 | 性质 | 对应 FR |
|---|---|---|---|---|
| **API-1** | `PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` | ① ② | **既有端点，语义变更**（后端零改动） | FR-4, FR-7 |
| **API-2** | `POST /api/cpq/quotations/{quotationId}/ensure-row-data` | ③ | **新增** | FR-12 |
| **API-3** | `POST /api/cpq/quotations/{quotationId}/submit` | ① | **既有端点，新增拒绝分支** | FR-6 |
| **API-4** | `POST /api/cpq/admin/cache/evict` | ④ | **新增**（诊断用） | FR-16 |
| **API-5** | `POST /api/cpq/quotations/line-items/{lineItemId}/reconcile-report` | ① | **新增**（埋点，fire-and-forget） | FR-5 |

---

## API-1 · 编辑单元格（既有端点，**语义变更**）

`PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit`

### 变更点（后端代码零改动，改的是前端怎么用它）

| | 改造前 | 改造后 |
|---|---|---|
| 前端调用方式 | `await` → 用响应 `quoteCardValues` **回灌渲染** | **不 await**；响应**只喂给对账**（FR-7） |
| 响应的用途 | 显示权威 | **校验器输出** |
| DRAFT 下行内显示 | 来自本响应 | 来自前端引擎（FR-1） |

> ⚠️ **后端不需要为阶段① ② 改这个端点**。阶段② 的串行化（FR-8）在服务层做，不改契约。

### 请求

```json
{
  "componentId": "2db185d6-2b5f-4617-bbc5-6957d6b735e2",
  "rowKey": "3120011203/3110520422/00255::Ag粉",
  "fieldName": "材料占比",
  "value": "0.25"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `componentId` | UUID string | ✅ | 页签组件 id |
| `rowKey` | string | ✅ | **权威行键**（带 `__nodeId::` 前缀，由 `buildRawRowKeys + uniquifyRowKeys` 产出，前后端同一口径） |
| `fieldName` | string | ✅ | 字段名（非视图列名） |
| `value` | any | ✅ | 原样透传；**空串 `""` 是合法值**（= 用户显式清空，见「键存在即已定值」口径） |

### 响应 `200`

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "quoteCardValues": "{\"tabs\":[…]}",
    "quoteExcelValues": "…",
    "quoteValuesAt": "2026-08-06T20:08:37.356Z"
  }
}
```

前端对账时从 `quoteCardValues.tabs[].formulaResults[].values` 取后端值，
从 `quoteCardValues.tabs[].resolvedRows[]` 取**后端侧输入**（D2 要求 tooltip 展示两边输入）。

### 错误码

| 码 | 含义 | 前端处理 |
|---|---|---|
| `400` | 非 DRAFT 不可编辑 | 显示只读提示；**不进对账** |
| `404` | lineItem / quotation 不存在 | 提示刷新页面 |
| `409` | **（阶段② 新增）** 乐观锁冲突：`row_version` 已被其它请求推进 | **按 FR-8 重放**（用最新版本重发），重试 N 次仍失败 → FR-10 可见失败态 |
| `500` | 服务端异常 | FR-10 可见失败态 + 自动重试 |

---

## API-2 · 补算 `row_data`（新增，阶段③）

`POST /api/cpq/quotations/{quotationId}/ensure-row-data`

**语义**：把该报价单下所有**标脏**的组件 `row_data` 公式列补算并落库。**幂等**，已算的零开销。
形态完全对标既有的 `POST /api/cpq/quotations/{id}/ensure-excel-values`。

### 请求

无 body。可选 query：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `lineItemId` | UUID | ❌ | 只补该行；不传 = 整单 |

### 响应 `200`

```json
{ "code": 200, "message": "success",
  "data": { "quotationId": "fe75eb4d-…", "materialized": 3, "skipped": 5 } }
```

| 字段 | 说明 |
|---|---|
| `materialized` | 实际补算落库的组件数 |
| `skipped` | 未标脏、无需补算的组件数 |

### 调用方（**后端内部调用，前端一般不直接调**）

按 `需求文档.md §5.3`，以下**服务端**入口在读 `row_data` 前必须先 ensure：

`ExcelViewService` · `LineDiscountService` · `SnapshotCollectorService` · `QuoteBackfillCollector` · `PriceReconciler` · `MaterialVersionUpgradeService`

前端仅在**打开 Excel 视图 / 点导出前**主动调一次（与现有 `ensure-excel-values` 并列）。

### 错误码

| 码 | 含义 |
|---|---|
| `404` | 报价单不存在 |
| `409` | 另一补算在飞（单飞锁未取到）→ 前端可轮询重试；后端返回 `{"materialized": -1}` 语义同 `ensureCardValues` 的 `WARMING_IN_PROGRESS` |

---

## API-3 · 提交（既有端点，**新增拒绝分支**）

`POST /api/cpq/quotations/{quotationId}/submit`

### 新增前置校验（FR-6 / D7）

提交前对每个 line item 执行 `assertLineSettled(lineItemId)`：

1. 有**在飞的编辑写**（阶段② 的串行队列非空）→ 拒绝；
2. 有**未落定的对账差异**（前端上报且未消解）→ 拒绝。

### 新增错误响应 `409`

```json
{
  "code": 409,
  "message": "存在未落定的前后端差异，无法提交",
  "data": {
    "reason": "RECONCILE_PENDING",
    "conflicts": [
      { "lineItemId": "6caffef0-…", "productPartNo": "3120011203",
        "tabName": "物料", "rowKey": "3120011203/3110520422/00255::Ag粉",
        "fieldName": "材料成本", "frontendValue": 6.087758, "backendValue": 608.775811 }
    ]
  }
}
```

| `reason` | 含义 | 前端处理 |
|---|---|---|
| `RECONCILE_PENDING` | 存在未落定对账差异 | 弹窗列出 `conflicts`，引导用户去对应格子查看 ⚠ |
| `WRITE_IN_FLIGHT` | 有编辑写未落库 | 提示「正在保存，请稍候重试」，可自动重试一次 |

> 其余既有错误码不变。

---

## API-4 · 手工清缓存（新增，阶段④ 诊断用）

`POST /api/cpq/admin/cache/evict`

**用途**：缓存失效点漏挂时的**逃生通道** —— 出事不用重启服务（`需求文档.md §7.3` 三道防线之一）。

### 请求

```json
{ "cache": "frozen-structure", "key": "fe75eb4d-ebd2-4997-85ae-3322b7c09471" }
```

| 字段 | 类型 | 必填 | 取值 |
|---|---|---|---|
| `cache` | string | ✅ | `frozen-structure` \| `row-key-fields` \| `all` |
| `key` | string | ❌ | 不传 = 清该缓存全部条目 |

### 响应 `200`

```json
{ "code": 200, "message": "success", "data": { "cache": "frozen-structure", "evicted": 1 } }
```

### 权限

**仅 `SYSTEM_ADMIN`**；非管理员 → `403`。

---

## API-5 · 对账差异埋点（新增，阶段①）

`POST /api/cpq/quotations/line-items/{lineItemId}/reconcile-report`

**语义**：前端把本轮对账发现的差异上报，供排查与趋势观察。**fire-and-forget，前端不阻塞、不处理响应体**。
D1 明确：**只记录，服务端不据此改任何数据**。

### 请求

```json
{
  "reconciledAt": "2026-08-06T20:08:37.356Z",
  "diffs": [
    {
      "componentId": "2db185d6-…", "tabName": "物料",
      "rowKey": "3120011203/3110520422/00255::Ag粉", "fieldName": "材料成本",
      "frontendValue": 6.087758, "backendValue": 608.775811,
      "frontendInputs": { "材料占比": "0.25", "组成数量": 2, "元素单价": 28892.5 },
      "backendInputs":  { "材料占比": 25,     "组成数量": 2, "元素单价": 28892.5 }
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `diffs[].frontendInputs` / `backendInputs` | ✅ | **D2 强制**：只带该行**行键字段 + 该公式引用到的字段**，不要整行倾倒 |
| `diffs[]` | ✅ | 空数组 = 本轮对账无差异（也可上报，用于统计对账覆盖率） |

### 响应 `202`

```json
{ "code": 202, "message": "accepted", "data": { "recorded": 1 } }
```

### 落点

后端记录到应用日志（`WARN` 级，前缀 `[reconcile-diff]`），字段齐全便于 grep。
**不建表**（避免为可观测性引入 schema 变更）；若后续需要趋势分析再单独立项。

---

## 附：不新增端点的部分

| 需求 | 为什么不用新端点 |
|---|---|
| 阶段① 拿后端算的结果做对账 | **复用 API-1 的响应** —— `quoteCardValues` 里 `formulaResults` + `resolvedRows` 已经同时含「后端值」和「后端输入」，够 D2 用 |
| 阶段② 串行化 / 乐观锁 | 服务层实现，契约只多一个 `409`（见 API-1） |
| 阶段④ 缓存本身 | 纯服务端内部，无对外契约 |
