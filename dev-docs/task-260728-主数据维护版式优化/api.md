# task-0728 主数据维护版式优化 · 接口契约

> 上游：[`需求说明.md`](./需求说明.md) · 平级：`backtask.md`（后端实现） / `fronttask.md`（前端对接）
> 约定：所有路径省略 `/api/cpq` 前缀（前端 axios `baseURL` 已含）；本文只写**本次变更**，未列出的字段一律保持现状。

---

## 0. 变更总览

| # | 接口 | 类型 | 变更 |
|---|---|---|---|
| A1 | `GET /pricing-basic-data/parts` | 改（加法式） | 新增 `sortBy` / `sortOrder` / `configured` 三个可选查询参数 |
| A2 | `GET /v6/process-master` | 改（加法式） | 新增 `sortBy` / `sortOrder` / `isOutsource` / `processCategory` 四个可选查询参数 |
| A3 | `GET /v6/process-master/categories` | **新增** | 工序分类去重列表（供过滤下拉取选项） |
| A4 | `GET /basic-data-import/v6/pricing/template` | **新增** | 下载核价基础数据 24 Sheet 空模板 |
| — | `GET /material-recipes` | **不变** | 材质走前端分页 + 前端排序 |
| — | 元素列表接口 | **不变** | 同上 |

> **加法式原则**：A1 / A2 的新参数全部可选，不传时行为与改造前**逐字节一致**。现有调用方（若有）零影响。

---

## A1. 料号核价列表（改）

```
GET /pricing-basic-data/parts
```

**实现位置**：`PricingBasicDataMaintenanceResource#parts` → `PricingMaintenanceService#listParts`

### 请求参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `keyword` | string | 否 | — | 现有。模糊匹配 料号 / 品名 |
| `page` | int | 否 | 1 | 现有。**1-based**（注意与 A2 不同） |
| `size` | int | 否 | 20 | 现有。上限 200 |
| `sortBy` | string | 否 | — | **新增**。见下方白名单。非法值 → 忽略，回退默认序 |
| `sortOrder` | string | 否 | `asc` | **新增**。`asc` / `desc`，大小写不敏感。`sortBy` 为空时本参数无意义 |
| `configured` | boolean | 否 | — | **新增**。`true`＝只看已配齐（`configuredCount >= totalSheets`）；`false`＝只看未配齐；不传＝全部 |

### `sortBy` 白名单

| `sortBy` 取值 | 映射到 SQL | 对应列 |
|---|---|---|
| `materialName` | `mm.material_name` | 品名 |
| `materialNo` | `a.mno` | 料号 |
| `specification` | `mm.specification` | 规格 |
| `dimension` | `mm.dimension` | 尺寸 |
| `configuredCount` | `a.c` | 已配置 |
| `lastUpdatedAt` | `a.u` | 最近更新 |

> ⚠️ **只能映射到上述已存在的投影别名**（`a.*` 来自 UNION 聚合子查询，`mm.*` 来自 LEFT JOIN），不得新写表达式。
> ⚠️ **禁止把 `sortBy` 原串拼进 SQL** —— 必须是 `Map<String,String>` 查表命中后取值，未命中回退默认序。

### 排序语义

- 默认序（`sortBy` 为空）：`ORDER BY a.u DESC NULLS LAST, a.mno` —— **保持现状不变**；
- 指定 `sortBy` 时：`ORDER BY <映射列> <asc|desc> NULLS LAST, a.mno` —— 尾部固定追加 `a.mno` 作为**稳定次序键**，避免同值行在翻页间跳动；
- `configured` 过滤落在 `HAVING`/外层 `WHERE` 上（`a.c` 是聚合结果），且**必须同时作用于 count 查询与分页查询**，否则「共 N 条」与实际行数不符。

### 响应（不变）

```jsonc
{
  "total": 82,
  "page": 1,
  "size": 20,
  "items": [
    {
      "materialNo": "3110520789",
      "materialName": "银点触头 AgNi11",
      "specification": "Φ3.0×1.2",
      "dimension": "3.0/1.2",
      "configuredCount": 17,
      "totalSheets": 17,
      "lastUpdatedAt": "2026-07-26T14:22:07Z"
    }
  ]
}
```

### 示例

```
GET /pricing-basic-data/parts?page=1&size=20&sortBy=materialNo&sortOrder=asc
GET /pricing-basic-data/parts?keyword=AgNi&configured=false&sortBy=lastUpdatedAt&sortOrder=desc
```

---

## A2. 工序列表（改）

```
GET /v6/process-master
```

**实现位置**：`ProcessMasterResource#list` → `ProcessMasterReadService#list`

### 请求参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `page` | int | 否 | 0 | 现有。**0-based**（注意与 A1 不同） |
| `size` | int | 否 | 20 | 现有。上限 200 |
| `keyword` | string | 否 | — | 现有。模糊匹配 `process_no` / `process_name` |
| `sortBy` | string | 否 | — | **新增**。见白名单，非法值回退默认序 |
| `sortOrder` | string | 否 | `asc` | **新增**。`asc` / `desc` |
| `isOutsource` | boolean | 否 | — | **新增**。`true`＝外协、`false`＝自制、不传＝全部。⚠️ 库中该列可为 NULL，`false` 是否包含 NULL 见下 |
| `processCategory` | string | 否 | — | **新增**。精确匹配（非模糊），取值来自 A3 |

### `sortBy` 白名单

| `sortBy` 取值 | 映射列 |
|---|---|
| `processNo` | `process_no` |
| `processName` | `process_name` |
| `processCategory` | `process_category` |
| `isOutsource` | `is_outsource` |
| `standardCurrency` | `standard_currency` |
| `standardUnit` | `standard_unit` |
| `defaultDefectRate` | `default_defect_rate` |
| `updatedAt` | `updated_at` |

### 语义细则

- 默认序：`ORDER BY process_no ASC`（现状口径，若现状不同则以现状为准并在 `backtask.md` 记录）；
- 指定排序时尾部固定追加 `process_no` 作稳定次序键；
- 可空列（`process_category` / `standard_currency` / `default_defect_rate` 等）排序一律 `NULLS LAST`，升降序都放最后；
- **`isOutsource=false` 的语义**：前端「自制」选项对应库里 `is_outsource = false`；`is_outsource IS NULL` 的行 UI 上显示为 `—`，**不归入「自制」也不归入「外协」**，即 `WHERE is_outsource = false` 而非 `IS NOT TRUE`。选「全部」时它们照常出现。

### 响应（不变）

`ApiResponse<PageResult<ProcessMasterDTO>>`：

```jsonc
{
  "success": true,
  "data": {
    "content": [ /* ProcessMasterDTO[] */ ],
    "page": 0,
    "size": 20,
    "totalElements": 36,
    "totalPages": 2
  }
}
```

---

## A3. 工序分类选项（新增）

```
GET /v6/process-master/categories
```

供「工序分类」过滤下拉取选项。**不能靠前端从当前页去重**——当前页只有 20 行，会漏掉其它页才出现的分类。

| 项 | 内容 |
|---|---|
| 权限 | 与 A2 `list` 相同：`SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN` |
| 查询 | `SELECT DISTINCT process_category FROM process_master WHERE process_category IS NOT NULL AND process_category <> '' ORDER BY 1` |
| 响应 | `ApiResponse<List<String>>` |

```jsonc
{ "success": true, "data": ["制造", "组装", "包装", "清洗", "电镀"] }
```

> 空表时返回 `[]`（不是 null）；前端此时把「工序分类」下拉置为禁用并 tooltip「暂无分类数据」。

---

## A4. 核价基础数据导入模板（新增）

```
GET /basic-data-import/v6/pricing/template
```

**实现位置**：`BasicDataImportV6Resource`（与 `POST /basic-data-import/v6/pricing` 同 Resource）

| 项 | 内容 |
|---|---|
| 权限 | 登录即可（四角色），与 `v6/process-master/import/template`、`material-recipes/import/template` 对齐。下载空模板无副作用，故比导入端点（`SYSTEM_ADMIN`）宽 |
| `@Produces` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| 响应头 | `Content-Disposition: attachment; filename="pricing_basic_data_template.xlsx"` |
| 响应体 | xlsx 字节流（**裸 Response，不包 `ApiResponse`** —— 与另两个模板下载端点同约定） |

### 模板内容契约（关键）

| 规则 | 说明 |
|---|---|
| Sheet 数量与命名 | **遍历已注册的 `P01~P24` handler，用其 `sheetName()` 建 sheet** —— 禁止手写常量数组。手写会在 handler 改名时静默失配，导致下载的模板导不进去 |
| Sheet 顺序 | 与 handler 注册顺序一致（P01 → P24） |
| 表头 | 每个 sheet 第 1 行写该 handler 识别的中文列名 |
| 数据行 | 空（只有表头） |
| 自检 | **下载的模板原样回传 `POST /basic-data-import/v6/pricing` 必须不报「缺少 Sheet」类错误**（0 行数据、`SUCCESS` 或全 0 行的正常报告均可）——这是本端点的验收判据 |

> 表头列名来源：若现有 handler 未暴露列清单，需在 handler 接口上加 `default List<String> templateHeaders()`，由各 handler 返回其解析用的中文列名常量。具体做法见 `backtask.md`。
> 参考实现：`ProcessMasterImportService#generateTemplate`、`MaterialRecipeImportService#generateTemplate`。

---

## 5. 不变更的接口（明确记录，防止误改）

| 接口 | 为什么不动 |
|---|---|
| `GET /material-recipes?keyword=&withCount=` | 返 `List<MaterialRecipeDTO>` 全量（39 条），材质页签走**前端**分页 + 前端排序 + 前端过滤 |
| 元素列表（`elementService.list(kw)`） | 同上，24 条全量 |
| `POST /basic-data-import/v6/pricing` | 导入解析与落库逻辑一字不动 |
| `POST /v6/process-master/import`、`POST /material-recipes/import` | 同上 |
| BOM 查询相关端点 | 「BOM」页签只摘前端入口，后端保留 |
| 配置模板（`/config-templates/*`）后端接口 | 只下线前端路由，后端接口保留 |

---

## 6. 错误约定

沿用现有全局约定，本次不新增错误码：

| 场景 | 行为 |
|---|---|
| `sortBy` 非白名单值 | **不报错**，忽略该参数、回退默认序（前端不会发出非法值，容错为主） |
| `sortOrder` 非 `asc`/`desc` | 同上，按 `asc` 处理 |
| `page` / `size` 越界 | 沿用现有 clamp 逻辑（`Pagination.clampPage` / `clampSize`，A1 侧为 `Math.max/min`） |
| 模板生成失败 | 500 + 现有全局异常处理 |
