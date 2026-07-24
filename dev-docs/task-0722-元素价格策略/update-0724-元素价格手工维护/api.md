# 接口文档 · 元素价格手工维护（update-0724）

> 隶属：`dev-docs/task-0722-元素价格策略` · update-0724
> 需求依据：同目录 `需求文档.md`（U0~U12 已定稿）
> 本文是前后端唯一契约来源。**契约有歧义时以本文为准，不以任一侧实现为准。**

---

## 0. 全局约定（先读，两个坑）

### 0.1 命名空间辨析 —— `element-price`（单数）是活的，`element-prices`（复数）本期下线

| 命名空间 | 归属 | 本期处置 |
|---|---|---|
| `/api/cpq/element-price/**`（**单数**） | task-0722 交付，`PriceTableResource` / `PriceImportResource` / 策略端点 | **活跃**，本期在此新增 4 个端点 |
| `/api/cpq/element-prices/**`（**复数**） | v1 遗留，`ElementPriceResource` | **整体下线**（§5） |

⚠️ 两者只差一个字母。删代码前用 `/usr/bin/grep -a -n "element-prices"` 精确匹配复数形式，**不要**用 `element-price` 做子串匹配（会命中全部活端点）。

### 0.2 响应包装形态不统一 —— 新端点跟 `PriceTableResource`，**不包 `ApiResponse`**

已核实（`PriceTableResource.java:34/46/80`）：

```java
// 活的 task-0722 端点：直接返回 DTO / PageResult，无包装
public PageResult<ElementPriceRowDTO> listDetail(...)
public List<ElementLatestPriceDTO> latestBySource(...)

// 待下线的 v1 端点：包了一层 ApiResponse
public ApiResponse<ElementReferenceDTO> getReference(...)
```

**本期 4 个新端点全部挂在 `PriceTableResource` 上，因此一律直接返回 DTO，不包 `ApiResponse`。**
前端 `elementPriceStrategyService` 现有解包方式即适用，**不要**再套 `.data`。

### 0.3 权限

4 个新端点全部落在 `PriceTableResource` 的**类级** `@RoleAllowed`：

```java
@RoleAllowed({"SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
```

与「价格导入」`PriceImportResource` 类级角色**逐字一致**（已核实），符合 U7「能导入即能手工维护」。
**方法级不再另加 `@RoleAllowed`**；v1 老端点的 `SYSTEM_ADMIN` 尺度不继承（U7）。

### 0.4 错误响应形态

沿用 `BusinessException(code, message)`，响应体：

```json
{ "code": 409, "message": "该元素在该源该日期已存在价格，请改用编辑" }
```

---

## 1. 新建价格

```
POST /api/cpq/element-price/prices
```

### 请求体

```json
{
  "elementCode": "Ag",
  "sourceId": "3f2a1e7c-0b44-4c19-9a51-6d8e2f9c1234",
  "priceDate": "2026-07-24",
  "price": 5860.0000,
  "currency": "CNY",
  "priceUnit": "kg"
}
```

| 字段 | 类型 | 必填 | 校验 | 落库列 |
|---|---|:--:|---|---|
| `elementCode` | string | 是 | 非空；须存在于 `element` 主表且 `status='ACTIVE'` | `element_name` |
| `sourceId` | uuid | **是** | 须存在于 `element_price_source` 且 `status='ACTIVE'` | `source_id` |
| `priceDate` | string(date) | **是** | `yyyy-MM-dd` | `price_date` |
| `price` | number | 是 | **> 0**（等于 0 或负数拒绝） | `raw_price` |
| `currency` | string | 是 | 非空 | `currency` |
| `priceUnit` | string | 是 | 非空 | `price_unit` |

> **`sourceId`/`priceDate` 必填是技术铁律，不是产品偏好**（U2）。取价函数 `f_customer_element_price` 按 `dp.source_id = w.source_id` 严格匹配（已核实 `pg_get_functiondef`），`source_id IS NULL` 的行**永远取不到价**。
>
> ⚠️ 注意：`element_daily_price` 表里 `source_id` / `raw_price` / `currency` / `price_unit` **在 DDL 层都是 nullable**（已核实 `\d element_daily_price`）。必填**只能靠应用层强制**，不能指望数据库兜底。

### 响应 `201`

返回新建行的完整 `ElementPriceRowDTO`（形状见 §4）。

### 错误

| 码 | 触发 | message |
|---|---|---|
| `400` | 任一必填缺失 / `price <= 0` / 日期格式非法 | 具体字段名 + 原因 |
| `400` | `elementCode` 不存在或非 ACTIVE | `元素不存在或已停用: {code}` |
| `400` | `sourceId` 不存在或非 ACTIVE | `价格源不存在或已停用` |
| **`409`** | `(elementCode, sourceId, priceDate)` 已存在 | `该元素在该源该日期已存在价格，请改用编辑` |

> **409 是拒绝，不是覆盖**（U0 ②、需求 §4.3 规则 2）。表上唯一键 `uq_element_daily = (element_name, COALESCE(source_id::text,''), price_date)`。
> 实现**不得**用导入侧的 `INSERT ... ON CONFLICT DO UPDATE`（那是导入的语义），必须先查后插或捕获唯一键冲突转 409。

### 副作用

- `fetch_status` 写 `'MANUAL'`
- `manually_filled_by` / `created_by` / `updated_by` 写当前会话用户
- **同事务**写一条 `element_daily_price_log`，`action='CREATE'`

---

## 2. 修改价格

```
PUT /api/cpq/element-price/prices/{id}
```

`{id}` = `element_daily_price.id`（UUID）。

### 请求体 —— 只有三个字段

```json
{
  "price": 5920.0000,
  "currency": "CNY",
  "priceUnit": "kg"
}
```

| 字段 | 必填 | 校验 |
|---|:--:|---|
| `price` | 是 | **> 0** |
| `currency` | 是 | 非空 |
| `priceUnit` | 是 | 非空 |

> **键锁定是后端硬保证，不依赖前端置灰**（U4、需求 §4.3 规则 3）。
> 请求 DTO **不得**声明 `elementCode` / `sourceId` / `priceDate` 字段——不是"声明了但忽略"，是**根本不存在这三个字段**，这样前端多传时被 Jackson 直接丢弃，不存在被误用的可能。
> 验收 4 会构造请求强行传这三个字段，验证键不变。

### 响应 `200`

返回修改后的完整 `ElementPriceRowDTO`。

### 错误

| 码 | 触发 | message |
|---|---|---|
| `400` | `price <= 0` / 必填缺失 | 具体原因 |
| `404` | `id` 不存在 | `价格记录不存在: {id}` |

### 副作用

- **`fetch_status` 一律改写为 `'MANUAL'`**，包括原本是 `'IMPORT'` 的导入行（U3、验收 3）
- `updated_by` / `updated_at` 更新
- **同事务**写一条 `element_daily_price_log`，`action='UPDATE'`，`snapshot` 存**变更后**的值

---

## 3. 删除价格

```
DELETE /api/cpq/element-price/prices/{id}
```

### 响应 `204 No Content`

### 错误

| 码 | 触发 |
|---|---|
| `404` | `id` 不存在 |

### 副作用

- **必须先读出删除前的完整值，再删行，再写日志，三步同事务**
- `element_daily_price_log`：`action='DELETE'`，`snapshot` 存**删除前**的完整值（验收 9）
- `price_id` 冗余记录原行 id，但**日志表不建 FK**（原行已不存在，建 FK 会阻止删除）

> 前端按多选删除，逐行调用本端点，用 `runBatch` 聚合部分失败（见 fronttask F3）。**后端不提供批量删除端点**——单条语义更清晰，且每条独立留痕。

---

## 4. `ElementPriceRowDTO` —— 必须扩两个字段 ⚠️

**这是本期发现的契约缺口，需求文档未覆盖。**

现状（已核实 `ElementPriceRowDTO.java` + `PriceTableService.listDetail:61-62` 的 SELECT 列表）：DTO **既没有 `id` 也没有 `fetchStatus`**。

后果：
- 没有 `id` → 前端勾选行后**拿不到主键**，`PUT`/`DELETE` 无从调用
- 没有 `fetchStatus` → **验收 1/3 无法在 UI 上验证**（"列表出现该行 `fetch_status='MANUAL'`"）

### 扩展后形状

```json
{
  "id": "9c1e4b70-2a33-4d81-b5f2-77e0a1c4d918",
  "elementCode": "Ag",
  "elementName": "银",
  "priceDate": "2026-07-24",
  "sourceId": "3f2a1e7c-0b44-4c19-9a51-6d8e2f9c1234",
  "sourceName": "上海有色网",
  "sourceStatus": "ACTIVE",
  "price": 5860.0000,
  "currency": "CNY",
  "priceUnit": "kg",
  "fetchStatus": "MANUAL",
  "operatorName": "张三",
  "updatedAt": "2026-07-24T10:12:33+08:00"
}
```

| 新增字段 | 类型 | 来源列 | 说明 |
|---|---|---|---|
| `id` | uuid | `edp.id` | 行主键，`PUT`/`DELETE` 定位用，也用作前端 `rowKey` |
| `fetchStatus` | string | `edp.fetch_status` | 值域 `SUCCESS`/`FAILED`/`MANUAL`/`IMPORT`（表 CHECK 约束，已核实） |

**影响面**：`GET /prices`（明细列表）与 `GET /prices/export`（Excel 导出，走同一 `listDetail`）。
矩阵 Tab（`/prices/matrix`）**不受影响**——它是按元素×日期透视的聚合视图，无行主键概念。

> 前端 `rowKey` 必须从现有的 `${elementCode}__${sourceId}__${priceDate}` 组合键**改为 `id`**，否则跨页保留选中会因为组合键在不同页重复而串行（`SelectableTable` 默认开启跨页保留选中）。

---

## 5. 变更历史查询

```
GET /api/cpq/element-price/prices/history
```

### 查询参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|:--:|---|---|
| `sourceId` | uuid | 否 | — | 按价格源过滤 |
| `from` | date | 否 | — | `changed_at` 下界（含），`yyyy-MM-dd` |
| `to` | date | 否 | — | `changed_at` 上界（含） |
| `keyword` | string | 否 | — | 元素符号或中文名模糊匹配 |
| `page` | int | 否 | `0` | 0-based |
| `size` | int | 否 | `20` | 上限 200 |

> 筛选口径与明细 Tab **完全一致**（源 / 日期区间 / 元素），前端复用同一套筛选控件（U6）。
> 注意 `from`/`to` 在明细 Tab 过滤的是 `price_date`，在历史 Tab 过滤的是 **`changed_at`**（"谁在什么时候改的"），语义不同但控件相同。

### 响应 `200` —— `PageResult<PriceHistoryDTO>`

```json
{
  "content": [
    {
      "id": "a1b2...",
      "changedAt": "2026-07-24T10:15:02+08:00",
      "changedByName": "张三",
      "action": "UPDATE",
      "elementCode": "Ag",
      "elementName": "银",
      "sourceId": "3f2a...",
      "sourceName": "上海有色网",
      "priceDate": "2026-07-24",
      "targetLabel": "Ag 银 · 上海有色网 · 2026-07-24",
      "changes": [
        { "field": "price", "fieldLabel": "单价", "oldValue": "5860.0000", "newValue": "5920.0000" }
      ],
      "snapshot": { "price": 5920.0, "currency": "CNY", "priceUnit": "kg", "fetchStatus": "MANUAL" }
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 37
}
```

字段形状**逐字比照** `StrategyHistoryDTO` / `StrategyChangeDTO`（已核实源码），保持两个历史 Tab 的前端渲染可复用。

| 字段 | 说明 |
|---|---|
| `action` | `CREATE` / `UPDATE` / `DELETE` |
| `targetLabel` | 展示用标签，格式 `{元素符号} {中文名} · {源名} · {价格日期}` |
| `changes` | **仅 `UPDATE` 且存在前序快照时非空**；`CREATE`/`DELETE` 为 `[]`，前端用 `snapshot` 展示全量 |
| `snapshot` | `CREATE`/`UPDATE` 存变更后值；**`DELETE` 存删除前值** |

> **`changes` 不入库**（U0 ⑦）。`element_daily_price_log` 表**只有 `snapshot` 列**，`changes` 是查询时用同一"价格身份"下相邻两条 snapshot 比对算出来的——与 `StrategyService.listHistory:520-533` 同构。
> 「价格身份」= `(element_name, source_id, price_date)` 三元组（策略侧对应的是 `elementCode`）。

---

## 6. 下线端点（v1，`element-prices` 复数）

以下 4 个端点连同 `ElementPriceResource` 整个类删除，删除后请求返回 `404`（验收 13）：

```
GET  /api/cpq/element-prices/reference            → ElementPriceService.getReference
GET  /api/cpq/element-prices/history              → ElementPriceService.listHistory
POST /api/cpq/element-prices/manual               → ElementPriceService.upsertManual
GET  /api/cpq/element-prices/available-elements   → ElementPriceService.listAvailableElements
```

**保留不动**（活的消费方，勿误删）：

```
GET /api/cpq/element-price/latest-by-source       ← 元素抽屉「各源最新价格」区块在用（task-0722 §11.14A）
GET /api/cpq/element-price/prices                 ← 明细 Tab
GET /api/cpq/element-price/prices/matrix          ← 矩阵 Tab
GET /api/cpq/element-price/prices/export
GET /api/cpq/element-price/prices/matrix/export
POST /api/cpq/element-price/import                ← 价格导入
GET /api/cpq/element-price/import-template
```

> `available-elements` 曾在 BL-0069 #5 被修正为改读 `element` 主表；本期整体下线后该子项自然消失，**不影响其已闭合状态**。
> 新功能的元素下拉改走 `GET /api/cpq/elements`（§7）。

---

## 7. 复用的既有端点（不改动）

### 元素下拉

```
GET /api/cpq/elements?keyword={kw}
```

- 已核实：`ElementResource` @Path `/api/cpq/elements`，`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`，返回 `List<ElementDTO>`（**无 `ApiResponse` 包装**）
- 前端只取 `status='ACTIVE'` 的元素（需求 §4.3 规则 8）
- task-0722 的策略例外抽屉选元素走的也是这个端点，本次与之保持一致

### 价格源下拉

```
GET /api/cpq/element-price/sources
```

- 只列 `status='ACTIVE'` 的源（需求 §4.3 规则 7）
- **编辑既有行时源已锁定**，即使该源后来被停用也不影响编辑（源字段根本不在 `PUT` 请求体里）
- 明细 Tab 的筛选下拉已在用 `sources` prop（`ElementPriceTableDrawer` 传入），新建抽屉可直接复用同一份数据，无需重复请求

---

## 8. 契约自检清单（前后端联调前逐条勾）

- [ ] 新端点响应**未**包 `ApiResponse`，前端**未**多解一层 `.data`
- [ ] `ElementPriceRowDTO` 已含 `id` + `fetchStatus`，`listDetail` 的 SELECT 已补这两列
- [ ] 前端 `rowKey` 已从组合键改为 `id`
- [ ] `PUT` 请求 DTO **不存在**键字段（不是"忽略"，是不存在）
- [ ] 重复键新建返回 `409` 且**原值未被覆盖**（SQL 直查确认）
- [ ] 改导入行后 SQL 直查 `fetch_status` = `MANUAL`
- [ ] 三种动作各产生 1 条日志，`DELETE` 的 `snapshot` 是删除前的值
- [ ] 删代码时用 `/usr/bin/grep -a` 精确匹配 `element-prices`（复数），未误伤单数命名空间
