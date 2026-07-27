# 接口契约 —— repair-0727 报价侧树页签删除行 BUG

> 上游：`需求说明.md`（§12 裁决 / §13 方案定稿）。本文件是前后端联调的唯一契约来源。
> 契约变更共 3 处：①树删除端点响应扩展 ②墓碑存储格式扩展 ③提交校验语义变化（无签名变化）。

---

## 1. 树删除执行端点（响应体扩展）

`POST /api/cpq/quotations/{quotationId}/line-items/{lineItemId}/tree/delete`

### 1.1 请求（**不变**）

```jsonc
{
  "componentId": "656c9b87-cda5-4c32-8d72-45d94714f77a",
  "mode": "ROW",                       // PRUNE | ROW
  "nodeId": "S-3120014539/S-80011/992",
  "rowKey": "5",                       // mode=ROW 必填；前端 __effKey
  "previewToken": "3759a0e2c1a074544a17011929efbf3a"
}
```

### 1.2 响应（**新增 `componentData`**）

```jsonc
{
  "code": 200,
  "message": "success",
  "data": {
    "deletedNodeIds": ["S-3120014539/S-80011/992"],       // 不变
    "cascadeDeletedRowKeys": { "<componentId>": ["992"] }, // 不变
    "quoteCardValues": "{...}",                            // 不变
    "quoteExcelValues": "{...}",                           // 🆕 可为 null
    "quoteValuesAt": "2026-07-26T18:33:21Z",               // 🆕 可为 null
    "componentData": [                                     // 🆕 权威投影，前端据此原子重灌
      {
        "componentId": "656c9b87-...",
        "rowData": "[{...}, {...}]",   // 已按墓碑物化的 N-1 行（JSON 字符串）
        "deletedRowKeys": "[{\"effKey\":\"...\",\"fp\":\"...\",\"nodeId\":\"...\"}]",
        "subtotal": 1234.5678
      }
    ]
  }
}
```

**契约要点**

| 项 | 约定 |
|---|---|
| `componentData` 覆盖范围 | 该 lineItem 下**全部**组件（不只被删的那个），按 `sortOrder, id` 排序 —— 与既有 `delete-driver-row` 完全一致 |
| `rowData` | **已按墓碑物化**的 N-1 行；前端整段替换 `comp.rows`，**不得**再自行过滤 |
| `deletedRowKeys` | 服务端权威墓碑数组原文；前端整段替换，**不得**乐观追加 |
| `componentData` 缺失时 | 前端**必须**回退到"只回灌 quoteCardValues"的旧行为（非 DRAFT 单据服务端会返回不带该字段） |
| 幂等 | 重复删同一 `(nodeId, rowKey)` → 200 且墓碑不重复追加（按 `nodeId + fp` 判重） |

> 与既有 `POST .../components/{componentId}/delete-driver-row` 的响应结构**逐字段对齐**，前端可复用同一个 `applyQuoteProjection`。

---

## 2. 墓碑存储格式（`quotation_line_component_data.deleted_row_keys`）

### 2.1 新格式

```jsonc
[
  { "effKey": "S-3120014539/992::4", "fp": "∅∅∅AgNi11#-Ⅰ∅∅∅∅992S-3120014539", "nodeId": "S-3120014539/992" }
]
```

| 字段 | 含义 | 何时写 |
|---|---|---|
| `effKey` | 兼容字段，**不参与匹配**（2026-07-14 起） | 一直写 |
| `fp` | `driverRow` 内容指纹（`DeletedRowKeys.rowFingerprint`） | 一直写 |
| `nodeId` | 🆕 树行结构身份（来自 baseRow 顶层 `__nodeId`） | **仅树页签行**；非树行不写该键 |

### 2.2 匹配规则（前后端**必须逐字节一致**）

```
一行被判删 ⟺ 存在墓碑 t 满足：
    t.fp == row.fp
  且 ( t.nodeId 为空  或  row.__nodeId 为空  或  t.nodeId == row.__nodeId )
```

| 场景 | 行为 |
|---|---|
| 新墓碑（含 nodeId）× 树行 | nodeId + fp **双命中**才删 → 同料号挂不同父可精确区分 |
| 旧墓碑（无 nodeId）× 树行 | 退化 fp 单键 → **存量单据行为逐字节不变** |
| 任意墓碑 × 非树行（无 `__nodeId`） | fp 单键 → **非树页签行为逐字节不变** |

> 实现方：后端 `DeletedRowKeys.keepMask`、前端 `deletedRows.ts#keepRow`。两侧必须有对应单测。

---

## 3. 提交审批（语义变化，**签名不变**）

`POST /api/cpq/quotations/{id}/submit`

### 3.1 变化点

| # | 变化 | 说明 |
|---|---|---|
| 3.1.1 | **校验前先剔除已删行** | 按 `deleted_row_keys`（行墓碑，规则同 §2.2）+ `quotation_line_item.deleted_tree_nodes`（剪枝，`__nodeId` 前缀匹配）过滤 `snapshot_rows`，剩余行才参与判重 |
| 3.1.2 | **树行判重键加节点维度** | 行带 `__nodeId` 时，判重键 = `computeDedupKey(...) + "@" + __nodeId`；非树行口径不变 |

### 3.2 响应（结构不变，仅冲突集合变小）

失败仍为 `HTTP 422` + `RowKeyConflictException` 结构：

```jsonc
{ "code": 422,
  "message": "行键重复，无法提交：\n· 组件「…」行键 [AgNi11#-Ⅰ] 在第 5,6 行重复",
  "data": { "conflicts": [ { "lineItemId": "...", "componentId": "...", "tabName": "...",
                            "rowKey": "AgNi11#-Ⅰ", "rowIndices": [5,6] } ] } }
```

**前端无需改动**（`RowKeyConflictDrawer` 沿用）。

### 3.3 行为对照（以实测单 QT-20260726-0006 为准）

| 场景 | 改动前 | 改动后（期望） |
|---|---|---|
| 992 挂两父，两行都在 | 422 拦截 | ✅ **放行**（nodeId 不同 = 合法结构） |
| 992 挂两父，删掉其中一行 | 422 拦截 | ✅ 放行 |
| 992 挂两父，两行都删 | 422 拦截（实测复现） | ✅ 放行 |
| 同一节点下两条相同行键的明细 | 422 拦截 | ✅ **仍拦截**（真撞键） |
| 非树页签同组件行键重复 | 422 拦截 | ✅ **仍拦截**（逐字节不变） |

---

## 4. 不变更清单（回归红线）

- `tree/add-leaf`、`tree/delete-preview` 请求与响应**零变化**
- `delete-driver-row`、`restore-driver-rows`（墓碑清空）**零变化**
- 核价侧一切接口与取数**零变化**（`keepMask` 在核价侧 `deleted == null`，不进新分支）
- 无数据库 DDL、无 Flyway 迁移（`deleted_row_keys` 是既有 jsonb 列，仅内部多一个键）
