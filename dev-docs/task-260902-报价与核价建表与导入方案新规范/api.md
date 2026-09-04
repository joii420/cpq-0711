# 接口契约 · 报价与核价建表与导入方案新规范

> **前后端唯一协调物。** 前端只按 `fronttask.md` 做、后端只按 `backtask.md` 做，两者互相看不见 —— 契约变更必须走 `task-docs.md §4` 四步。
> Base：axios `baseURL` 已含 `/api/cpq`，前端调用写 `/dataset/...`。
> 路径风格**照搬**现有 `PricingBasicDataMaintenanceResource`（`/api/cpq/pricing-basic-data/...`），便于前端组件复用。

## 0. 公共约定

> 🚩 **2026-09-03 · 交付后模型变更 D-26（需求文档 R-1.6）**：料号类型从「BOM 行的属性」改为「料号的属性」。
> 契约影响面仅一处 —— §3 `GET parts` 的 item 新增 **`materialType`**（见该节）。
> 服务端连带（不进契约但会被前端看到）：三套 `物料BOM` sheet 的元数据里
> 报价「投入类型」/ 核价「组成类型」两列**已移除**，`GET /dataset/{dataset}/sheets` 不再下发它们；
> 三套 `物料` sheet 的列里新增「类型」。取值域 `零件` / `外购件`，选填。
> 📌 两个旧列都是行指纹比对项，删列后现存行指纹必变 ⇒ 删列后的**首次导入必然整组升版**（AC-58 专门验，是预期行为）。

**`{dataset}` 路径参数**（三取一，非法值返回 404）：

| 值 | 数据集 | 轴字段 | 带版本 sheet 数 |
|---|---|---|---|
| `quote` | 报价数据 | `material_no`（销售料号） | 13 |
| `cost-basic` | 基础核价 | `production_no`（生产料号） | 9 |
| `cost-detail` | 详细核价 | `production_no`（生产料号） | 17 |

**统一响应包**：沿用项目现有 `ApiResponse<T>`。

🚩 **2026-09-03 更正（由后端 #3 实查指出，本文档原先写错）**：真实结构是 **`{ code, message, data }`**，
**没有 `success` 字段**（`cpq-backend/src/main/java/com/cpq/common/dto/ApiResponse.java`：`private int code / String message / T data`）。
所有示例已于 2026-09-03 统一改用 `code`（此前误写为 `"success": true/false`，全文 11 处已清）：`code=200` 成功、`400`/`409`/`422`/`500` 见各节。
⚠️ **前端不得按 `success` 字段判定成功与否** —— 那个字段不存在，判定会恒为 falsy。

**权限**：写端点（`PUT rows`、`POST import`）**仅** `PRICING_MANAGER` / `SYSTEM_ADMIN`（AC-31）；
读端点再加上 `SALES_REP` / `SALES_MANAGER`。
🚦 **唯一例外：`PUT /dataset/{dataset}/parts/{axisValue}`（§3.5）四个角色都可调** —— 用户在知悉风险后的裁决（D-31），不是遗漏。

🚩 **2026-09-03 更正**：本文档原写的 `SALES` **角色不存在**。实查 `user` 表，项目真实角色仅四个：
`SYSTEM_ADMIN` / `PRICING_MANAGER` / `SALES_MANAGER` / `SALES_REP`。

---

## 1. `POST /dataset/{dataset}/import` — Excel 导入

**请求**：`multipart/form-data`，字段 `file`（.xlsx）。

**成功 200**

```json
{ "code": 200, "data": {
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
{ "code": 400, "message": "导入校验未通过，共 4 处问题，本次未写入任何数据", "data": {
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
- `reason` 取值封闭集：`必填项为空` / `轴列不可为空` / `主数据不存在` / `不是合法数值` / `不是合法整数` / `超出长度上限 {n}` / `sheet「{名}」不属于{数据集中文名}数据集` / `表头列名与规范不一致：缺少「{列名}」` / **`客户编号未在客户档案中登记`**（D-19，仅报价 `客户料号` sheet） / **`轴值未在物料表登记`**（D-24，全部带版本 sheet） / **`产品分类「{值}」未在产品分类主数据中登记`**（D-27，仅报价 `物料` sheet） / **`值「{值}」不在允许值域：{允许值域}`**（D-30，三套 `物料` sheet 的「类型」列） / **`主键「{值}」重复：第 {a} 行与第 {b} 行`**（D-28，全部免版本 sheet）。

**服务端异常 500**：Phase 2 整事务回滚，`message` 为 `写入失败，已回滚：{原因}`。

---

## 2. `GET /dataset/{dataset}/sheets` — 带版本 sheet 元数据

**响应 200** —— 结构**完全对齐**现有 `SheetMeta`，前端 `EditableSheetTable` 可直接消费：

```json
{ "code": 200, "data": { "sheets": [
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

**Query**：`page`（**0-based**）、`size`、`keyword`、`sortBy`、`sortDir`（`asc`|`desc`）、**`configured`**（`Boolean`，可空）

🚩 **`configured`（2026-09-03 补）**：`true` = 只返已配齐（`configuredCount == totalSheetCount`）、`false` = 只返未配齐、**不传 = 全部**。
原型 `核价数据-列表.html` 的「配置状态」下拉对应此参数。此前本节漏写该参数、后端签名也没有它 ⇒ 前端发送后被静默忽略。

`sortBy` 白名单：`axisValue` / `materialName` / `specification` / `dimension` / `configuredCount` / `lastUpdatedAt`

**响应 200**

```json
{ "code": 200, "data": {
  "total": 128,
  "items": [
    { "axisValue": "3120014539", "materialName": "主料1", "specification": null,
      "dimension": "3.5×3.5×0.6", "oldMaterialNo": "8DLX.550.653", "unitWeight": null,
      "productionNo": null, "materialType": "零件",
      "categoryCode": "000000", "categoryName": "默认分类",
      "configuredCount": 6, "totalSheetCount": 9, "lastUpdatedAt": "2026-09-03T10:12:00+08:00" }
  ]
}}
```

- `configuredCount` = 该轴值在**带版本表**中至少有 1 行数据的 sheet 数（列表「已配置 6/9」徽标）。
- 🚩 **`productionNo`（2026-09-03 补）**：`ds_quote_material` 建了 **7 列**（销售料号/品名/规格/尺寸/旧料号/单重/**生产料号**），
  本响应体原先只列了 6 个、漏掉生产料号 —— 由「产品管理优化」会话指出，属**契约疏漏修补**（响应体本就该覆盖表全部列），非扩范围。
  `dataset=quote` 时该字段有值；`cost-basic` / `cost-detail` 的物料表**没有**这一列（它们的轴本身就是生产料号），返回时**省略该字段**，不要返 null。
- 🚩 **`materialType`（2026-09-03 补，D-26 / 需求文档 R-1.6）**：料号类型，取值域 **`零件` / `外购件`**，**可空**（`null` = 尚未分类）。
  三套数据集的物料表**都**建了这一列（`ds_quote_material` / `ds_cost_basic_material` / `ds_cost_detail_material`），
  ⇒ **三套的键都恒出现**，该料号没填就是 `"materialType": null`（与 `specification` / `dimension` 的空值口径一致）。
  与 `productionNo` 同一套判定口径：**键的有无由「数据集的物料表有没有这一列」决定，不由「行的值」决定**，
  服务端按 Registry 元数据判断而非硬编码 `dataset`。

- 🚩 **`categoryCode` / `categoryName`（2026-09-03，D-27 / AC-61）**：产品分类编码与显示名。
  **只有 `dataset=quote`** 下发这两个键（只有 `ds_quote_material` 建了 `category_code` 列）；`cost-basic` / `cost-detail` **整个键不出现**，与 `productionNo` 同口径。
  `categoryName` 由后端 `LEFT JOIN product_category` 在**同一条 SELECT** 里带出 —— 🚫 不逐行查，该端点 SQL 条数恒为 2（count + page）。
  `categoryCode` 恒非空（导入 Phase 1 对空格子填 `000000`）；`categoryName` 在分类被删时可能为 `null`，此时前端显示 code 本身。

---

## 3.5 `PUT /dataset/{dataset}/parts/{axisValue}` — 料号单列更新（2026-09-03 新增，D-31 / AC-65）

> 🚩 **免版本表的第二条写入路径。** 原规则「免版本表只能经导入通道写入」已由 D-31 作废。
> ⚠️ 电镀方案**仍然只读** —— 本次开放的只有 `ds_quote_material.production_no` 一列。

**请求 body**：`{ "<字段名>": <值> }`，可一次传多列。

字段名**两种写法都接受**：DB 列名 `production_no`（正名）与其小驼峰 `productionNo`
（= §3 `GET /parts` item 里的键名）。前端从列表拿到的是小驼峰，写回时不必再自己转蛇形。

```json
{ "production_no": "P-3120014539" }
{ "productionNo": "P-3120014539" }     // 等价
```

🚩 **`null` 与「键缺席」是两件事，必须区分**：

| body | 含义 |
|---|---|
| `{ "productionNo": "X" }` | 该列改成 `X` |
| `{ "productionNo": null }` | 该列**清空**（写 `NULL`） |
| `{}` | 400 `未提供任何待更新字段` |
| 不传 `productionNo` 这个键 | 该列**一个字节不动** |

⚠️ 后端**不得**用 `map.get(k) != null` 判断「这个字段要不要改」——
`{"productionNo": null}` 与 `{}` 在 `get()` 下返回同一个 `null`，
「清空」会被当成「没传」而**静默不改**，症状是「点了清空、提示保存成功、值还在」。
判据必须是**键在不在**（`containsKey` / 遍历 `entrySet`）。

**`updated` 回显用调用方发来的键名**（发 `productionNo` 就回 `productionNo`），前端可直接回填。

**成功 200**

```json
{ "code": 200, "data": {
  "dataset": "quote",
  "axisValue": "S-3120014539",
  "updated": { "production_no": "P-3120014539" },
  "updatedAt": "2026-09-03T18:40:11+08:00",
  "source": "IMPORT"
}}
```

**语义**（AC-65 逐条验）：

| # | 规则 |
|---|---|
| ① | **白名单**：只有 `ColumnDef.partEditable` 的列可改。当前白名单 = `ds_quote_material.production_no` **一列**。不在白名单 / 不存在的字段 → **400 并点名该字段**，🚫 不靠「前端不显示」兜底 |
| ② | **部分更新**：只写传入的列，该行其余列**逐字不变**。🚫 后端不得复用整行 UPSERT（那会把未传字段写成 `NULL`） |
| ③ | `source` **保持原值**（行级来源仍是 `IMPORT`，不因改一列翻成 `MANUAL`） |
| ④ | `updated_at` 变新、`updated_by` = 调用者 |
| ⑤ | **不接升版**：免版本表没有 `version_no`，也不写 `_history` |
| ⑥ | 与导入的冲突消解：本端点改过的 `production_no` **不会**被下次导入的空格子打回（导入侧对该列走 `COALESCE(EXCLUDED.x, 旧值)`，D-29 / AC-62）。⚠️ **每新开放一个可编辑字段都要单独回答这个问题**，不能默认继承 |

**错误**：

| 状态 | 场景 | message |
|---|---|---|
| 400 | 字段不在白名单 | `字段「specification」不允许直接编辑（可编辑字段：production_no / productionNo）` |
| 400 | body 为空 | `未提供任何待更新字段` |
| 400 | 值超列长度上限 | `列「生产料号」超出长度上限 128` |
| 404 | 料号不存在 | `料号不存在: {axisValue}` |
| 404 | dataset 非法 | `数据集不存在: {dataset}` |

**权限**：🚦 **`SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN` 四个角色都可调**。
⚠️ 这与本文件其他写端点（仅 `PRICING_MANAGER` / `SYSTEM_ADMIN`）**刻意不同**，是用户在知悉风险后的裁决（D-31 留痕），🚫 不要「顺手对齐」改回两角色。

---

## 4. `GET /dataset/{dataset}/parts/{axisValue}/overview` — 抽屉徽标

```json
{ "code": 200, "data": {
  "axisValue": "3120014539", "materialName": "主料1",
  "sheets": [ { "sheetKey": "MATERIAL_BOM", "rowCount": 7, "versionNo": 3, "lastUpdatedAt": "…", "source": "IMPORT" } ]
}}
```

- `versionNo` 为 `null` 表示该 sheet 该轴值**从未有过数据**（抽屉 tab 不打徽标，进去是空态 AC-32）。

---

## 5. `GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows` — 行数据

**Query**：`version`（可选；省略 = 当前版本）

```json
{ "code": 200, "data": {
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
{ "code": 200, "data": { "versions": [
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
{ "code": 200, "data": { "result": "UPGRADED", "versionNo": 4, "rowCount": 7,
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
{ "code": 400, "message": "数据已被他人更新至 v4，请刷新后重试",
  "data": { "currentVersion": 4, "baseVersion": 3 } }
```

**校验失败 400**：与导入同构，`errors` 数组的 `sheet` 固定为本 sheet 名、`row` 为**数组下标 + 1**。

**整组清空 422**（2026-09-03 补定义）

```json
{ "code": 422, "message": "至少保留一行数据；整组清空不在本期范围", "data": null }
```

`rows` 传空数组时返回 422、**不写库**。理由：整组清空会让主表当前版本消失、版本号只剩 `_history`，
下次保存时乐观锁会误判成「该轴值从未有过数据」而走 `CREATED` 分支，版本号回退。
与现有 `PricingMaintenanceService` 的同名护栏口径一致。
> 📌 这条原本不在任何 AC 里，是后端 #3 实现时加的防御。主线裁决**保留**，并在此定义为契约的一部分 ——
> 删行到只剩 0 行属于「删除整组数据」，本期不提供该能力（见 `需求文档.md §② 明确不做`）。

---

## 8. `GET /dataset/{dataset}/lookup/{masterType}` — 主数据下拉

**Query**：`keyword`、`limit`（默认 20）

`masterType` ∈ `material` / `process` / `element` / `recipe` / `customer`

```json
{ "code": 200, "data": { "items": [ { "code": "Z053", "name": "铣割" } ] } }
```

> **主数据共享，不拆**（D-16）。本端点读的是现有 `process` / `element` / `material_recipe` / `customer` 表，**只读**。
> 可直接复用现有 `/pricing-basic-data/lookup/{masterType}` 的实现，但**必须新开路径**，不得改动现有端点。

---

## 8.5 `GET /dataset/{dataset}/plating-schemes` — 电镀方案只读列表（S-9 / AC-48~51）

> 🚩 2026-09-03 追加（裁决 D-21）。补上**免版本表在新体系里没有查看入口**的缺口。
> `{dataset}` 仅接受 `quote` 与 `cost-detail`（`cost-basic` 没有电镀方案表，返回 **404**）。

**Query**：`page`（0-based）、`size`、`keyword`（匹配方案编号 / 电镀元素名称）

**响应 200** —— `columns` 由后端**按数据集下发**，前端不得写死：

```json
{ "code": 200, "data": {
  "total": 2,
  "columns": [
    { "name": "scheme_no",        "label": "方案编号",   "type": "STRING" },
    { "name": "scheme_version",   "label": "版本",       "type": "STRING" },
    { "name": "item_seq",         "label": "项次",       "type": "NUMBER" },
    { "name": "plating_element",  "label": "电镀元素名称","type": "STRING" },
    { "name": "price_source_url", "label": "元素单价来源网站网址", "type": "STRING" },
    { "name": "price_source_name","label": "元素单价来源网站名称", "type": "STRING" },
    { "name": "price_fetch_rule", "label": "元素单价抓取规则",     "type": "STRING" },
    { "name": "plating_area",     "label": "电镀面积（cm2）",     "type": "DECIMAL" },
    { "name": "coating_thickness","label": "镀层厚度（μm）",      "type": "DECIMAL" },
    { "name": "plating_requirement","label": "电镀要求", "type": "STRING" }
  ],
  "items": [
    { "scheme_no": "A0001", "scheme_version": "2000", "item_seq": 1, "plating_element": "Ni",
      "price_source_url": "https://www.ccmn.cn/", "price_source_name": "长江有色网",
      "price_fetch_rule": "1.均价", "plating_area": "0.031000000000",
      "coating_thickness": "0.400000000000", "plating_requirement": "镀层厚度≥0.4μm" }
  ]
}}
```

**两个数据集的列不同**（AC-49）：

| dataset | 表 | 列数 | 差异 |
|---|---|---|---|
| `quote` | `ds_quote_plating_scheme` | **10** | 多 `元素单价来源网站网址 / 名称 / 抓取规则` |
| `cost-detail` | `ds_cost_detail_plating_scheme` | **8** | 多 `密度（g/cm3)`，无上述三列 |

🚫 **只读，没有写端点。** 电镀方案是免版本表，写入语义是「按主键覆盖更新」，只能经导入通道（`POST .../import`）。

---

## 9. 报价侧导入（复用 §1）

报价单管理「导入报价数据」按钮调 `POST /dataset/quote/import`，**无独立端点**。
🚫 现有 `/pricing-basic-data/import`（料号核价页签）与报价单管理原有「从基础数据导入」端点**一个字节都不改**（AC-43）。

---

## 10. 回写 `main-api.md`

本任务新增 **10 个端点**，测试完成后、合并 master 前必须回写 `dev-docs/main-api.md`（`task-docs.md §2.5`）：

```
POST /api/cpq/dataset/{dataset}/import
GET  /api/cpq/dataset/{dataset}/sheets
GET  /api/cpq/dataset/{dataset}/parts
GET  /api/cpq/dataset/{dataset}/parts/{axisValue}/overview
GET  /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows
GET  /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions
PUT  /api/cpq/dataset/{dataset}/parts/{axisValue}                                  ← 2026-09-03 新增（D-31）
PUT  /api/cpq/dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows
GET  /api/cpq/dataset/{dataset}/lookup/{masterType}
GET  /api/cpq/dataset/{dataset}/plating-schemes
```

**本次不修改、不删除任何既有端点。**
