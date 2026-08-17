# 接口文档 · 主数据维护-核价基础数据维护（task-0712）

> 与 `需求说明.md`（C1–C13）、`backtask.md`、`fronttask.md` 配套。
> 设计原则：**元数据驱动的一套通用接口**，按 `sheetKey` + `materialNo` 参数化，覆盖 16 个版本组（见需求说明 §4.1）。
> 纪律：所有接口**禁止 for 循环嵌套查库**，一次查询内存装配（沿用 tesk-0709 约束）。

---

## 0. 通用约定

- **Base path**：`/api/cpq/pricing-basic-data`（新 `PricingBasicDataMaintenanceResource`）。
- **鉴权**（C10）：
  - 读接口（GET）：`@RolesAllowed({"SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
  - 写接口（PUT 保存）：`@RolesAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`
- **system_type**：本模块固定 `PRICING`（后端常量注入，不由前端传）。
- **sheetKey**：16 个版本组的稳定标识（见 §5 附录）。例：`CONSUMABLE` / `INCOMING_OTHER` / `CAPACITY` / `LABOR_RATE` / `ENERGY` / `DEPRECIATION` / `MATERIAL_BOM` / `ELEMENT_BOM` …
- **错误码**：`400` 参数错/校验失败；`401` 未鉴权；`403` 角色不足；`404` 料号/sheet 不存在；`409` 乐观锁冲突（版本已被他人升级）；`422` 业务护栏拒绝（如整组清空、轴被篡改）。
- 统一响应包裹沿用项目现有 `ApiResponse<T>` 风格（若现网无统一包裹，则直接返 body，按现有 Resource 习惯对齐）。

---

## 1. 料号列表 · 有核价数据的销售料号（C3/C4）

```
GET /api/cpq/pricing-basic-data/parts
```

**Query**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | string | 否 | 按 `material_no` 或 `material_name` 模糊搜索 |
| page | int | 否 | 页码，默认 1 |
| size | int | 否 | 每页，默认 20 |

**Response 200**

```json
{
  "total": 137,
  "page": 1,
  "size": 20,
  "items": [
    {
      "materialNo": "10023001",
      "materialName": "端子A",
      "specification": "H62",
      "dimension": "2.5*0.6",
      "configuredCount": 12,       // 已配置版本组数 N
      "totalSheets": 16,           // 固定 16
      "lastUpdatedAt": "2026-07-10T09:12:33Z"
    }
  ]
}
```

**实现要点**
- 数据源 = 至少一张 PRICING 版本化表存在 `is_current=true` 行的 `material_no`。
- **零 N+1**：用一条 `UNION ALL` 汇总各表（select material_no, max(updated_at), sheetKey 标记）→ group by material_no 得 `configuredCount` + `lastUpdatedAt`，再 join `material_master` 带品名/规格/尺寸。禁止逐料号查 16 次。
- `unit_price` 表按 `price_type` 归到对应 sheetKey；同一料号在同表多 price_type 各计一组。

---

## 2. Sheet 元数据 · 16 组列定义（渲染 + 校验用）

```
GET /api/cpq/pricing-basic-data/sheets
```

无参数（元数据静态，前端可缓存；后端从 `PricingSheetRegistry` 生成）。

**Response 200**

```json
{
  "sheets": [
    {
      "sheetKey": "SELF_PROCESS",
      "tabName": "加工费&组装费",
      "group": "FEE",                    // FEE(费用类) / BOM / CAPACITY_ENERGY / TOOLING
      "order": 5,
      "masterDetail": false,             // P06/P07 为 true
      "salesPartAnchor": "code",         // 销售料号锚列
      "columns": [
        {
          "name": "operation_no",
          "label": "工序号",
          "type": "STRING",              // STRING/NUMBER/DECIMAL/BOOLEAN/ENUM
          "role": "SUBDIM",              // AXIS/SUBDIM/VALUE/NAME
          "editable": true,
          "dropdown": {                  // C12/C13：编码列录入方式
            "kind": "MASTER",            // MASTER / ENUM / FREE
            "master": "process",         // MASTER 时：process/element/material
            "nameColumn": "operation_name" // 联动只读名称列 name（§4.4.0）
          }
        },
        { "name": "operation_name", "label": "工序名", "type": "STRING", "role": "NAME", "editable": false },
        { "name": "pricing_price", "label": "单价", "type": "DECIMAL", "role": "VALUE", "editable": true },
        { "name": "currency", "label": "币种", "type": "ENUM", "role": "VALUE", "editable": true,
          "dropdown": { "kind": "ENUM", "options": ["CNY","USD","EUR"] } },
        { "name": "unit", "label": "单位", "type": "ENUM", "role": "VALUE", "editable": true,
          "dropdown": { "kind": "ENUM", "options": ["PCS","KG","H"] } },
        { "name": "defect_rate", "label": "不良率", "type": "DECIMAL", "role": "VALUE", "editable": true }
      ]
    }
    // … 其余 15 组
  ]
}
```

**字段语义**
- `role`：`AXIS`=轴（锁定不可改，前端不渲染为可编辑）；`SUBDIM`=子维度编码列（入 content，可改，走下拉）；`VALUE`=普通值列（入 content，可改）；`NAME`=只读名称列（关联主表带出，不入 content、不进指纹比对）。
- `dropdown.kind`：`MASTER`（走 §7 lookup 接口）/`ENUM`（固定枚举）/`FREE`（自由文本，含要素名称/模具编号/物料BOM组成件，见 C13）。
- 列定义**必须与 `VersionedV6Writer` 各 handler 的 groupKey/content 严格同源**（backtask §B2 强约束）。

---

## 3. 料号概览 · 16 组当前状态（抽屉 tab 徽标）

```
GET /api/cpq/pricing-basic-data/parts/{materialNo}/overview
```

**Response 200**

```json
{
  "materialNo": "10023001",
  "materialName": "端子A",
  "specification": "H62",
  "dimension": "2.5*0.6",
  "sheets": [
    { "sheetKey": "SELF_PROCESS", "hasData": true,  "currentVersion": "2003", "versionCount": 4, "lastUpdatedAt": "2026-07-10T09:12:33Z" },
    { "sheetKey": "TOOLING",      "hasData": false, "currentVersion": null,   "versionCount": 0, "lastUpdatedAt": null }
    // … 16 项，无数据的 hasData=false（前端渲染空 tab，允许从零新建 C9）
  ]
}
```

**实现**：一条查询（各表 `is_current=true` 的 material_no 过滤 + group）装配 16 项，禁止逐 sheet 查询。

---

## 4. 读取某组数据 · 当前版 / 历史版

```
GET /api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows
```

**Query**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| version | string | 否 | 不传=当前版（is_current=true）；传则取该历史版本号的组行 |

**Response 200**

```json
{
  "sheetKey": "SELF_PROCESS",
  "materialNo": "10023001",
  "version": "2003",
  "isCurrent": true,
  "editable": true,                 // isCurrent && 当前用户有编辑权（C7：历史版恒 false）
  "rows": [
    {
      "operation_no": "OP10",
      "operation_name": "冲压",      // NAME 列由后端 join 带出
      "pricing_price": "1.230000",
      "currency": "CNY",
      "unit": "PCS",
      "defect_rate": "0.0200"
    }
  ]
}
```

**实现**
- 当前版：`WHERE system_type='PRICING' AND <锚列>=materialNo AND price_type=? AND is_current=true`。
- 历史版：`… AND <versionColumn>=:version`（不看 is_current）。
- NAME 列一次性 join 主表（`process_master`/`element`/`material_master`）带出，禁止逐行查名。
- 主从 BOM（P06/P07）：返回子表明细行；主表信息（bom_type/production_no 等）放 `masterInfo` 附带字段。

---

## 5. 版本列表（版本切换下拉 + 操作留痕 C11）

```
GET /api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/versions
```

**Response 200**

```json
{
  "versions": [
    { "version": "2003", "isCurrent": true,  "source": "MANUAL", "operator": "张三", "operatedAt": "2026-07-10T09:12:33Z" },
    { "version": "2002", "isCurrent": false, "source": "IMPORT", "operator": "系统导入", "operatedAt": "2026-07-01T02:00:00Z" }
  ]
}
```

**实现**：按 `<versionColumn>` distinct 取每版的 `is_current`、`source`、`updated_by`→用户名、`updated_at`。`operator` 由 `created_by/updated_by` UUID join 用户表带出。

---

## 6. 保存整组（编辑升版，C5/C6/C9）

```
PUT /api/cpq/pricing-basic-data/parts/{materialNo}/sheets/{sheetKey}/rows
```

**Body**

```json
{
  "expectedCurrentVersion": "2003",   // 乐观锁：前端加载时的当前版本号；空 tab 从零新建传 null
  "rows": [
    { "operation_no": "OP10", "pricing_price": "1.500000", "currency": "CNY", "unit": "PCS", "defect_rate": "0.0200" },
    { "operation_no": "OP20", "pricing_price": "0.800000", "currency": "CNY", "unit": "PCS", "defect_rate": "0.0100" }
  ]
}
```

- `rows` 只需带 **AXIS 之外**的列（AXIS 由 path 的 materialNo + registry 的 price_type 常量注入，前端不可改）。
- NAME 列不必回传（后端忽略；不进指纹比对）。

**Response 200**

```json
{
  "result": "UPGRADED",      // UNCHANGED（内容未变，复用旧版本）/ UPGRADED（升版）/ CREATED（从零新建=2000）
  "version": "2004",
  "isCurrent": true
}
```

**护栏 / 语义（backtask §B4）**
- **指纹比对**（C6）：构造 `VersionedGroupSpec` → `VersionedV6Writer.writeVersionedGroup(s)`（P06/P07 走 `writeVersionedMasterDetail`）。内容未变→`UNCHANGED` 不写库；变→升版；空组首存→`CREATED`（2000）。
- **乐观锁**（并发）：`expectedCurrentVersion` 与库内当前版不一致 → `409`（提示他人已升级，请刷新）。为空但库内已有当前版（从零新建冲突）→ `409`。
- **轴锁定**：忽略/拒绝 body 内任何 AXIS 列的篡改（materialNo/price_type/system_type 以服务端为准）。
- **至少留一行**（C5）：`rows` 为空 → `422`（整组下线走专门 API，不在本期）。
- **来源/操作人**（C11）：写入行 `source='MANUAL'`、`updated_by`=当前用户；升版新组同样。
- **枚举/主表校验**（严格，无宽松回退）：`kind=MASTER` 列的值不存在于主表 → `400`；`kind=ENUM` 非法值（不在该列 `options` / CHECK 约束枚举内）→ `400` 并指明列；`BOOLEAN` 列非布尔值 → `400`。前端 ENUM 用固定 `options` 下拉、不允许自定义输入（原"未知可输入回退"已于 2026-07-12 撤销）。

---

## 7. 主表候选下拉（C12）

```
GET /api/cpq/pricing-basic-data/lookup/{masterType}
```

`masterType` ∈ `process` | `element` | `material`

**Query**：`keyword`（模糊）、`limit`（默认 20）

**Response 200**

```json
{
  "items": [
    { "code": "OP10", "name": "冲压" },        // process: process_no/process_name
    { "code": "Ag",   "name": "银" },          // element: element_code/element_name
    { "code": "10023001", "name": "端子A" }     // material: material_no/material_name
  ]
}
```

**实现**：`process`→`process_master`，`element`→`element`（status='ACTIVE'），`material`→`material_master`。单查询 + limit，前端 `Select` 远程搜索。

---

## 8. 附录 · sheetKey ↔ 版本组 ↔ 表 映射（16 组）

| sheetKey | tabName | 表 | 版本列 | price_type 常量 | 销售料号锚 | 主从 |
|----------|---------|----|--------|----------------|-----------|------|
| CONSUMABLE | 生产耗材BOM | unit_price | version_no | CONSUMABLE | code | 否 |
| PACKAGING | 包装材料BOM | unit_price | version_no | PACKAGING | code | 否 |
| INCOMING_PROCESS | 来料加工费 | unit_price | version_no | INCOMING_PROCESS | finished_material_no | 否 |
| INCOMING_OTHER | 来料其他费用(P16+P17) | unit_price | version_no | INCOMING_OTHER | finished_material_no | 否 |
| SELF_PROCESS | 加工费&组装费 | unit_price | version_no | SELF_PROCESS | code | 否 |
| FINISHED_OTHER | 成品其他费用(P19+P20) | unit_price | version_no | FINISHED_OTHER | code | 否 |
| PLATING | 电镀成本 | unit_price | version_no | PLATING | code | 否 |
| OUTSOURCE_PROCESS | 其他外加工成本 | unit_price | version_no | OUTSOURCE_PROCESS | code | 否 |
| MATERIAL_BOM | 物料BOM | material_bom(+item) | bom_version | — | material_no | **是** |
| ELEMENT_BOM | 物料与元素BOM | element_bom(+item) | characteristic | — | material_no | **是** |
| CAPACITY | 产能 | capacity | calc_version | — | material_no | 否 |
| LABOR_RATE | 工时单价 | labor_rate | version_no | — | material_no | 否 |
| DEPRECIATION | 折旧 | production_energy | calc_version | DEPRECIATION | material_no | 否 |
| ENERGY | 能耗 | production_energy | calc_version | ENERGY | material_no | 否 |
| AUX_ENERGY | 辅助能耗 | auxiliary_energy | calc_version | — | material_no | 否 |
| TOOLING | 模具工装成本 | tooling_cost | calc_version | — | material_no | 否 |

> 每组精确的「轴列 / 值列 / 子维度编码列」以 tesk-0709《版本升级规则文档 §5.1》+ 对应 `P*Handler` 的现有登记为准（backtask §B2 同源约束）。
