# 接口契约 · 报价与核价建表与导入方案新规范

> **前后端唯一协调物。** 前端只按 `fronttask.md` 做、后端只按 `backtask.md` 做，两者互相看不见 —— 契约变更必须走 `task-docs.md §4` 四步。
> Base：axios `baseURL` 已含 `/api/cpq`，前端调用写 `/dataset/...`。
> 路径风格**照搬**现有 `PricingBasicDataMaintenanceResource`（`/api/cpq/pricing-basic-data/...`），便于前端组件复用。

## 0. 公共约定

**`{dataset}` 路径参数**（三取一，非法值返回 404）：

| 值 | 数据集 | 轴字段 | 带版本 sheet 数 |
|---|---|---|---|
| `quote` | 报价数据 | `material_no`（销售料号） | 13 |
| `cost-basic` | 基础核价 | `production_no`（生产料号） | 9 |
| `cost-detail` | 详细核价 | `production_no`（生产料号） | 17 |

**统一响应包**：沿用项目现有 `ApiResponse<T>`（`{ success, data, message }`）。

**权限**：读端点 `PRICING_MANAGER` / `SYSTEM_ADMIN` / `SALES` 均可；写端点（`PUT rows`、`POST import`）**仅** `PRICING_MANAGER` / `SYSTEM_ADMIN`（AC-31）。

---

## 1. `POST /dataset/{dataset}/import` — Excel 导入

**请求**：`multipart/form-data`，字段 `file`（.xlsx）。

**成功 200**

```json
{ "success": true, "data": {
  "dataset": "cost-basic",
  "fileName": "核价2 - 数据导入与表格建表.xlsx",
  "durationMs": 4210,
  "summary": [
    { "sheet": "物料",     "versioned": false, "axisCount": 0,  "inserted": 1, "updated": 0 },
    { "sheet": "物料BOM",  "versioned": true,  "axisCount": 1,  "created": 0, "upgraded": 1, "unchanged": 0 }
  ]
}}
```

- 免版本 sheet 出 `inserted` / `updated`；带版本 sheet 出 `created` / `upgraded` / `unchanged`（AC-11）。

**校验失败 400**（Phase 1 拒收，**一行未写**）

```json
{ "success": false, "message": "导入校验未通过，共 4 处问题，本次未写入任何数据", "data": {
  "errors": [
    { "sheet": "物料BOM",       "row": 3, "column": "组成料号", "reason": "必填项为空" },
    { "sheet": "年降系数",       "row": 2, "column": "销售料号", "reason": "轴列不可为空" },
    { "sheet": "物料与元素BOM",  "row": 3, "column": "元素代码", "reason": "主数据不存在" },
    { "sheet": "来料加工费",     "row": 3, "column": "加工费",   "reason": "不是合法数值" }
  ]
}}
```

- `row` = **Excel 物理行号**（表头占 1~2 行，数据从第 3 行起；免版本 sheet 无第 2 行，数据从第 2 行起）。
- 🚫 **必须一次返回全部错误**，不许遇错即停（AC-10）。
- `reason` 取值封闭集：`必填项为空` / `轴列不可为空` / `主数据不存在` / `不是合法数值` / `不是合法整数` / `超出长度上限 {n}` / `sheet「{名}」不属于{数据集中文名}数据集` / `表头列名与规范不一致：缺少「{列名}」`。

**服务端异常 500**：Phase 2 整事务回滚，`message` 为 `写入失败，已回滚：{原因}`。

---

## 2. `GET /dataset/{dataset}/sheets` — 带版本 sheet 元数据

**响应 200** —— 结构**完全对齐**现有 `SheetMeta`，前端 `EditableSheetTable` 可直接消费：

```json
{ "success": true, "data": { "sheets": [
  { "sheetKey": "MATERIAL_BOM", "sheetName": "物料BOM", "sortOrder": 1,
    "axisColumn": "production_no", "axisLabel": "生产料号",
    "columns": [
      { "name": "item_seq",     "label": "项次",   "role": "VALUE", "type": "NUMBER",  "editable": true,  "required": true,  "compared": false },
      { "name": "component_no", "label": "组成料号","role": "SUBDIM","type": "STRING",  "editable": true,  "required": true,  "compared": true,
        "dropdown": { "kind": "MASTER", "masterType": "material", "nameColumn": "component_name" } },
      { "name": "component_name","label": "组成料号名称","role": "NAME", "editable": false },
      { "name": "currency",     "label": "币种",   "role": "VALUE", "type": "STRING",  "editable": true,  "required": false, "compared": true,
        "dropdown": { "kind": "ENUM", "options": ["CNY","USD","EUR","JPY"] } }
    ] }
] }}
```

| 字段 | 含义 |
|---|---|
| `role` | `AXIS`（前端不渲染，抽屉上下文锁定） / `SUBDIM` / `VALUE` / `NAME`（只读，主数据带出） |
| `required` | 红底 或 轴列 → `true`（前端做前置校验，后端仍会再校验一次） |
| `compared` | 是否为比对项。**前端只读展示用**（在列头打 🔗 角标），不参与前端逻辑 |
| `dropdown.kind` | `MASTER`（远程搜索）/ `ENUM`（固定候选 + 可输入）/ `FREE` |

> ⚠️ `NAME` 角色的列**不是数据库字段**（白底不建字段），由后端 JOIN 主数据实时带出，`PUT rows` 时前端**不要回传**。

---

## 3. `GET /dataset/{dataset}/parts` — 料号列表（列表页数据源 = 该数据集的物料表）

**Query**：`page`（**0-based**）、`size`、`keyword`、`sortBy`、`sortDir`（`asc`|`desc`）

`sortBy` 白名单：`axisValue` / `materialName` / `specification` / `dimension` / `configuredCount` / `lastUpdatedAt`

**响应 200**

```json
{ "success": true, "data": {
  "total": 128,
  "items": [
    { "axisValue": "3120014539", "materialName": "主料1", "specification": null,
      "dimension": "3.5×3.5×0.6", "oldMaterialNo": "8DLX.550.653", "unitWeight": null,
      "configuredCount": 6, "totalSheetCount": 9, "lastUpdatedAt": "2026-09-03T10:12:00+08:00" }
  ]
}}
```

- `configuredCount` = 该轴值在**带版本表**中至少有 1 行数据的 sheet 数（列表「已配置 6/9」徽标）。

---

## 4. `GET /dataset/{dataset}/parts/{axisValue}/overview` — 抽屉徽标

```json
{ "success": true, "data": {
  "axisValue": "3120014539", "materialName": "主料1",
  "sheets": [ { "sheetKey": "MATERIAL_BOM", "rowCount": 7, "versionNo": 3, "lastUpdatedAt": "…", "source": "IMPORT" } ]
}}
```

- `versionNo` 为 `null` 表示该 sheet 该轴值**从未有过数据**（抽屉 tab 不打徽标，进去是空态 AC-32）。

---

## 5. `GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows` — 行数据

**Query**：`version`（可选；省略 = 当前版本）

```json
{ "success": true, "data": {
  "versionNo": 3, "isLatest": true, "readOnly": false, "source": "MANUAL",
  "rows": [ { "item_seq": 10, "component_no": "S-2120011658", "component_name": "半成品A",
              "component_qty": "1.000000000000", "currency": "CNY", "row_fingerprint": "a3f1…" } ]
}}
```

- 请求历史版本时 `isLatest=false`、`readOnly=true`（前端据此禁用保存/新增，AC-29）。
- 数值以**字符串**回传，保留库中 scale，避免 JS 精度丢失（与现有 part-costing 一致）。

---

## 6. `GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions` — 版本列表

```json
{ "success": true, "data": { "versions": [
  { "versionNo": 3, "isLatest": true,  "rowCount": 7, "archivedAt": null,
    "updatedAt": "2026-09-03T10:12:00+08:00", "updatedBy": "admin", "source": "MANUAL" },
  { "versionNo": 2, "isLatest": false, "rowCount": 7, "archivedAt": "2026-09-03T10:12:00+08:00",
    "archivedBy": "admin", "archiveReason": "MANUAL_UPGRADE" }
] }}
```

- 倒序（最新在前）。v1 及之后的历史版本读自 `_history` 表。

---

## 7. `PUT /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows` — 保存（走整组升版）

**请求**

```json
{ "baseVersion": 3,
  "rows": [ { "item_seq": 10, "component_no": "S-2120011658", "component_qty": "2", "currency": "CNY" } ] }
```

- `baseVersion` = 前端当前展示的版本号，**必传**，用于乐观锁（AC-41）。
- `rows` 是该轴值该 sheet 的**整组全量**（不是增量）。删行 = 不出现在数组里。
- 🚫 前端**不传** `role=NAME` 的列与 `row_fingerprint`（由后端计算）。

**成功 200**

```json
{ "success": true, "data": { "result": "UPGRADED", "versionNo": 4, "rowCount": 7,
                             "message": "已升版至 v4" } }
```

`result` 三取一：

| 值 | 含义 | 前端 toast |
|---|---|---|
| `UNCHANGED` | 指纹多重集与行数均未变 | `数据无变化，未升版`（info，AC-28） |
| `UPGRADED` | 已升版，旧版进 `_history` | `已升版至 v{n}`（success，AC-27） |
| `CREATED` | 该轴值首次有数据 | `已创建 v1`（success） |

**冲突 409**（AC-41）

```json
{ "success": false, "message": "数据已被他人更新至 v4，请刷新后重试",
  "data": { "currentVersion": 4, "baseVersion": 3 } }
```

**校验失败 400**：与导入同构，`errors` 数组的 `sheet` 固定为本 sheet 名、`row` 为**数组下标 + 1**。

---

## 8. `GET /dataset/{dataset}/lookup/{masterType}` — 主数据下拉

**Query**：`keyword`、`limit`（默认 20）

`masterType` ∈ `material` / `process` / `element` / `recipe` / `customer`

```json
{ "success": true, "data": { "items": [ { "code": "Z053", "name": "铣割" } ] } }
```

> **主数据共享，不拆**（D-16）。本端点读的是现有 `process` / `element` / `material_recipe` / `customer` 表，**只读**。
> 可直接复用现有 `/pricing-basic-data/lookup/{masterType}` 的实现，但**必须新开路径**，不得改动现有端点。

---

## 9. 报价侧导入（复用 §1）

报价单管理「导入报价数据」按钮调 `POST /dataset/quote/import`，**无独立端点**。
🚫 现有 `/pricing-basic-data/import`（料号核价页签）与报价单管理原有「从基础数据导入」端点**一个字节都不改**（AC-43）。

---

## 10. 回写 `main-api.md`

本任务新增 **8 个端点**，测试完成后、合并 master 前必须回写 `dev-docs/main-api.md`（`task-docs.md §2.5`）：

```
POST /api/cpq/dataset/{dataset}/import
GET  /api/cpq/dataset/{dataset}/sheets
GET  /api/cpq/dataset/{dataset}/parts
GET  /api/cpq/dataset/{dataset}/parts/{axisValue}/overview
GET  /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows
GET  /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions
PUT  /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows
GET  /api/cpq/dataset/{dataset}/lookup/{masterType}
```

**本次不修改、不删除任何既有端点。**
