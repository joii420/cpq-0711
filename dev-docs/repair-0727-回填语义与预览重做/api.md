# repair-0727 · 接口契约变更

> 基线契约：`dev-docs/task-0721-报价升版逻辑/api.md`。本文只写**增量**，未提及的字段/端点一律不变。

---

## 1. `GET /api/cpq/quotations/{id}/costing-approve/preview`

端点路径、鉴权、错误码**不变**。响应体在原结构上做**加法式扩展**（旧字段全部保留，前端可平滑迁移）。

### 1.1 响应体（扩展后）

```jsonc
{
  "quotationId": "8e97cec0-…",
  "previewToken": "sha256hex…",
  "summary": {
    "versionedGroups": 3,
    "addedRows": 1,
    "deletedRows": 2,
    "changedRows": 4,
    "affectedProducts": 2          // 🆕 涉及产品数（groups 里 productNo 去重，null 不计）
  },
  "products": [                     // 🆕 按产品聚合的视图（groups 仍保留，见 §1.3）
    {
      "productNo": "S-3120014539",
      "productName": "接触片组件",              // 🆕 material_master.material_name，缺失时 null
      "customerNo": "CUST-0001",
      "customerName": "苏州西门子",              // 🆕 customer.name，缺失时 null
      "groupIndexes": [0, 2, 5]                // 指向 groups 数组下标，避免重复传输
    }
  ],
  "globalShared": {                 // 🆕 无产品维度的全局共享组（当前仅 plating_scheme）
    "groupIndexes": [7]
  },
  "groups": [
    {
      // ── 原有字段（不变）──
      "table": "material_bom_item",
      "tabName": "BOM",
      "groupKey": { "system_type": "QUOTE", "customer_no": "CUST-0001", "material_no": "S-3120014539" },
      "isGlobalShared": false,
      "versionFrom": "2009",
      "versionTo": "2010",
      "rows": [ /* 见 §1.2 */ ],

      // ── 🆕 新增字段 ──
      "productNo": "S-3120014539",
      "productName": "接触片组件",
      "categoryLabel": "BOM 组成",              // 业务类别中文名
      "route": "REBUILD",                       // REBUILD / FLIP / OFFLINE
      "baseSource": "PENDING",                  // PENDING / CURRENT / NONE（基底行来源）
      "baseRowCount": 4,                        // 基底行数
      "resultRowCount": 4,                      // 通过后该组行数（预期值）
      "axisLabels": [                           // 轴的人类可读表达
        { "column": "customer_no", "label": "客户",  "value": "CUST-0001",    "display": "苏州西门子（CUST-0001）" },
        { "column": "material_no", "label": "产品料号", "value": "S-3120014539", "display": "S-3120014539 接触片组件" }
      ]
    }
  ]
}
```

### 1.2 `groups[].rows[]`（行级，结构升级）

```jsonc
{
  "op": "CHANGE",                    // CHANGE / ADD / DELETE（不变）
  "__v6_id": "3d450949-…",           // 不变；ADD 为 null
  "rowLabel": "组成件 W-1001（外购件）",   // 🆕 该行业务身份，前端直接展示
  "conflict": false,                 // 🆕 同列被多页签 patch 且值不同（B3.2）
  "changes": [                       // 🔄 结构变更：Map<col,[old,new]> → 数组，带中文名
    { "column": "composition_qty", "label": "组成数量", "oldValue": "1", "newValue": "2" }
  ],
  "values": [                        // 🔄 ADD/DELETE 用；同样带中文名
    { "column": "component_no", "label": "料件", "value": "W-1001" }
  ]
}
```

> **破坏性**：`changes` / `values` 由对象改为数组 —— 前端 `CostingApprovePreviewDrawer.tsx` 与 `costingOrderService.ts` 类型须同步改（F1/F2）。后端不保留旧形状（同批次发布，无外部消费方）。

### 1.3 兼容策略

- `groups` 数组**保留且顺序稳定**（按 table → 轴规范串排序，与 token 计算同序），`products[].groupIndexes` 引用其下标。
- 前端渲染以 `products` + `globalShared` 为主视图；`groups` 作为数据源。

---

## 2. `POST /api/cpq/quotations/{id}/costing-approve`

- 入参、`previewToken` 校验、错误码（400/403/409/500）**全部不变**。
- 响应 `backfill` 摘要新增 `affectedProducts`，与预览口径一致。

> ⚠️ **发布说明必写**：`previewToken` 是「回填计划有效状态」的哈希，本次 plan 内容口径变化 → 部署瞬间已打开的预览抽屉里的旧 token 会 409。行为符合设计（重新预览即可），需在发布说明和 RECORD 里点名。

---

## 3. 无新增端点、无新增字段类型

不新增 `field_type`、不改组件配置 schema、不改 `snapshot_rows` 结构 → **AP-44 联动协议不触发**。
