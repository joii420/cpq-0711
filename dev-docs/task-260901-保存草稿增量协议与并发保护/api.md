# 接口契约 · task-260901

> 🚨 **本文件是前后端唯一协调物。** 前端子代理与后端子代理互相看不见，只能靠这份契约对齐。
> 契约变更必须按 `task-docs.md §4`「开工后 AC/契约变更」四步走，并**主动通知在跑的子代理**。

---

## 1. `PUT /api/cpq/quotations/{id}/draft` —— 保存草稿（**请求体与响应体均变更**）

### 1.1 变更摘要

| | 变更前 | 变更后 |
|---|---|---|
| 请求体 | `SaveDraftRequest{单头字段…, lineItems: LineItemDraft[]}`（全量 1845 行） | `SaveDraftRequest{单头字段…, baseVersion, added[], modified[], removed[]}` |
| 删除语义 | **隐式**：`lineItems` 里没出现的行 = 删除 | **显式**：只删 `removed` 数组里的 id |
| 响应体 | `QuotationDTO`（含全部行 + `componentData`，实测 24.6 MB） | `SaveDraftResponse`（只含变化行的 6 字段 + 单头 + 新版本号，目标 < 500 KB） |
| 并发控制 | 无（悲观写锁仅防同时写） | `baseVersion` 乐观校验，不匹配 → 409 |

### 1.2 请求体

```jsonc
{
  // ── 单头字段：与变更前完全一致，patch 语义（null 不覆盖）──
  "name": "...", "contactId": "...", "contactName": "...", "contactPhone": "...",
  "contactEmail": "...", "projectName": "...", "opportunityId": "...", "quoteType": "...",
  "priority": "...", "stage": "...", "expectedCloseDate": "2026-12-31", "paymentTerms": "...",
  "deliveryCycle": 30, "expiryDate": "2026-12-31", "remarks": "...",
  "finalDiscountRate": "100", "discountAdjustmentReason": "...",
  "customerTemplateId": "uuid", "costingCardTemplateId": "uuid", "categoryId": "uuid",

  // ── 新增：乐观并发基线 ──
  "baseVersion": 42,          // 必填。前端最近一次从服务端拿到的 userDataVersion

  // ── 新增：三数组（取代原 lineItems）──
  "added":    [ /* LineItemDraft，id 必须为 null */ ],
  "modified": [ /* LineItemDraft，id 必须非 null 且存在于该单 */ ],
  "removed":  [ "uuid", "uuid" ]   // 仅 id 列表
}
```

**`LineItemDraft` 的字段构成与变更前完全一致**（`productId` / `templateId` / `productPartNo` / `productAttributeValues` / `annualVolume` / `discount*` / `componentData[]` / `processNos[]` / `compositeProcesses[]` / `sortOrder` / `compositeType` / `tempParentIndex` …），本次不增删字段，仅改变它们的组织方式。

⚠️ **三处与「数组下标」解耦的强制要求**（原实现依赖 payload 下标，增量后下标语义失效）：

| 项 | 变更前 | 变更后 |
|---|---|---|
| `sortOrder` | 可空，空则回退 payload 下标 `i` | **必填**，前端显式传全局序号 |
| `tempParentIndex`（组合产品父子） | 父行在 payload 数组中的下标 | 改为 `tempParentKey`（父行的 `tempId`，`added` 内部互指）或 `parentLineItemId`（父行已持久化时直接给 id）。**两者互斥，二选一** |
| 总价 | 后端遍历 payload 累加 `subtotal` | 后端改为从库 `SELECT sum(subtotal)` |

### 1.3 响应体（200）

```jsonc
{
  "code": 200, "message": "success",
  "data": {
    "id": "uuid",
    "userDataVersion": 43,            // 新增：本次保存后的版本号，前端必须更新本地基线
    "totalAmount": "12345.000000000000",
    "originalAmount": "12345.000000000000",
    // 其余单头字段与变更前一致（供 setQuotationPreservingStructures 使用）

    "lineItems": [                     // ⚠️ 只含本次 added + modified 的行，不含未变行
      {
        "id": "uuid",                  // added 行在此拿到 DB 生成的 id
        "partVersionLocked": 2000,
        "quoteCardValues": null,       // 被清空的行回传 null（前端按既有逻辑跳过回灌）
        "costingCardValues": null,
        "quoteExcelValues": "{...}",
        "costingExcelValues": "{...}"
      }
    ]
  }
}
```

🚫 **响应中不得包含 `componentData`** —— 它占 9.3 MB 且前端从不读取（证据 `证据/E3-前端消费点.md`）。

🔑 **`added` 行的 id 对应关系**：`added` 行请求时 `id` 为 null，响应中如何认领？
**约定**：`LineItemDraft` 增加请求侧字段 `tempId`（前端生成的稳定 UUID，已在前端存在），响应中**该行原样回传 `tempId`**，前端按 `tempId` 认领新 id。
> 不用「按数组顺序对应」——那会重蹈下标耦合。

🚨 **`tempId` 的作用域（2026-09-01 收敛，两份文档原本互相矛盾）**：

| 响应中的行 | 键集 |
|---|---|
| 来自 `added` 的行 | 上述 6 个键 **+ `tempId`**（共 7 键）—— 这是新行认领 id 的**唯一**手段 |
| 来自 `modified` 的行 | **恰好 6 个键，不带 `tempId`**（该行本来就有 DB id，按 id 匹配即可） |

> **为什么要写死这条**：`test.md` 的 T-16 断言「响应 line 元素恰好 6 个键」，而本文件要求回传 `tempId` —— 两者原本冲突。若后端为过 T-16 而对 `added` 行也不回传 `tempId`，新增行将永远拿不到 DB id ⇒ 下次保存重复插入，**正是 AC-17 要防的那件事**。按上表分作用域后两者自洽：T-16 测的是 `modified` 行。

### 1.4 错误响应

| 状态码 | reason | 触发条件 | 前端行为 |
|---|---|---|---|
| **409** | `STALE_VERSION` | `baseVersion` ≠ 库中 `user_data_version` | 弹「保存失败」对话框，只给「刷新页面」一个按钮（见 `原型图/冲突提示.html`） |
| 409 | `RECONCILE_PENDING` / `WRITE_IN_FLIGHT` | **既有行为不变** | 既有处理不变 |
| 400 | — | `modified` 中的 id 不属于该单 / `added` 中 id 非 null / `baseVersion` 缺失 | 报错提示 |

**409 STALE_VERSION 响应体**：

```jsonc
{ "code": 409, "message": "这张报价单已被他人修改", "data": { "reason": "STALE_VERSION", "currentVersion": 45 } }
```

---

## 2. `PUT /api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` —— 单元格编辑（**响应新增一个字段**）

请求体不变：`{componentId, rowKey, fieldName, value}`。

响应**新增** `userDataVersion`：

```jsonc
{ "code": 200, "data": {
    "quoteCardValues": "...", "quoteExcelValues": "...", "quoteValuesAt": "...",
    "userDataVersion": 44          // 新增
} }
```

🔑 **为什么必须加**：该端点写 `row_data`（经 `materializeWholeLineRowData`），属**用户数据**写入，必须递增版本号；不回传的话前端本地基线立刻过期，下一次保存必然误报 409（AC-14）。

---

## 3. `GET /api/cpq/quotations/{id}` —— 取单据详情（**响应新增一个字段**）

**本次不改分页、不改结构**（分页见需求文档 §② 明确不做）。仅在响应根部新增：

```jsonc
{ "data": { "userDataVersion": 42, /* 其余一切不变 */ } }
```

前端打开单据时以此初始化本地版本基线。

---

## 4. 版本号语义（③ 的核心契约，前后端都必须遵守）

`quotation.user_data_version`（`integer NOT NULL DEFAULT 0`）

### 4.1 什么会递增它

| 写入方 | 递增 |
|---|---|
| `PUT /draft`（本次有实际写入时） | ✅ +1 |
| `PUT /quote-card-edit` | ✅ +1 |
| 其它写用户数据的端点（如删除 driver 行、剪枝树节点） | ✅ +1 |

### 4.2 什么**绝不**递增它（🚨 最关键的一条）

| 写入方 | 写的是什么 | 递增 |
|---|---|---|
| `ensureCardValues` / `ensureCardValuesDetailed` | `quote_card_values` / `costing_card_values` | ❌ |
| `ensureExcelValues` | `quote_excel_values` / `costing_excel_values` | ❌ |
| `snapshotQuotation` | `snapshot_rows` | ❌ |
| `CreateQuotationMaterializer` 建单物化四步 | 同上 | ❌ |
| `priceReconcile` | 价格对账占位行 | ❌ |

> **理由**：这些是**系统自算的派生数据**，用户没做任何事。若它们递增版本号，用户打开页面等后台重算跑完就必然撞 409 —— 而重算是每次保存后必然触发的，会形成「保存→重算→必冲突→刷新→保存」的死循环。
> 判定口径**复用既有的 `stableDraftDedupKey` 划分**（它已明确剔除 `subtotal` / `quoteExcelValues` / `rowData` 等派生字段），不另立标准。
> 对应 **AC-13**（反向验收）。

---

## 5. 不变更的端点（本次零改动，列出以免子代理误改）

| 端点 | 说明 |
|---|---|
| `POST /quotations`（建单） | 不变。建单后 `user_data_version` = 0 |
| `POST /quotations/{id}/submit` | 不变。提交前的 `handleSaveDraft` 走新协议，端点本身不改 |
| `POST /quotations/{id}/ensure-card-values` | 不变（其「内部整跑一次 getById」的缺陷已登记 BACKLOG，非本期） |
| `GET /quotations/{id}/materialize-status` | 不变 |
| `POST /quotations/{id}/copy` / `delete` / `approve` / `reject` | 不变 |

---

## 6. 回写总账

按 `task-docs.md §2.5`：本任务变更了 3 个端点（`PUT /draft`、`PUT /quote-card-edit`、`GET /quotations/{id}`），**测试完成后、合并 master 之前**必须回写 `dev-docs/main-api.md`，每个端点小节末尾加：

```
> 来源任务：`task-260901-保存草稿增量协议与并发保护`｜回写日期：<实取日期>
```
