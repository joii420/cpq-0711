# 接口契约 · 产品管理页重做

> 任务：`task-260903-产品管理页重做`　|　日期：2026-09-03
> **契约主源**：`../task-260902-报价与核价建表与导入方案新规范/api.md`（已冻结）。
> 本文只写**本任务消费什么、以及需要主源补什么**，🚫 不复制主源契约原文（复制必漂移）。

---

## 0. 本任务的接口立场

| 项 | 结论 |
|---|---|
| 新建业务端点 | **0 个**（前提：§2 的两处扩展由 `task-260902` 承接） |
| 修改既有端点 | **0 个** |
| 调用写端点 | **0 个** —— 本页纯只读，**不调** `POST import`、**不调** `PUT rows` |
| 契约所有权 | 全部归 `task-260902`。本任务**无权改** `api.md`，有疑问走跨会话沟通 |

> 🚫 **为什么不自建后端**：`报价侧交接说明.md` 明写「不要为报价侧另写一套后端，那会产生第二条升版路径」。
> 本任务连写端点都不调，更不应自建。**唯一例外见 §2 退路条款。**

---

## 1. 本任务消费的端点（全部来自主源，`{dataset}` = `quote`）

| # | 端点 | 用途 | 服务的 AC |
|---|---|---|---|
| C-1 | `GET /dataset/quote/parts` | 「销售产品」页签列表数据源 | AC-4, AC-14, AC-15 |
| C-2 | `GET /dataset/quote/sheets` | 抽屉 tab 清单与顺序（**tab 数由本接口决定，前端不写死**） | AC-6 |
| C-3 | `GET /dataset/quote/parts/{axisValue}/overview` | 抽屉各 tab 的行数徽标 / 是否从未有数据 | AC-6, AC-12 |
| C-4 | `GET /dataset/quote/parts/{axisValue}/sheets/{sheetKey}/rows` | 抽屉某 tab 的行数据与列元数据 | AC-7, AC-8, AC-10 |
| C-5 | `GET /dataset/quote/parts/{axisValue}/sheets/{sheetKey}/versions` | 版本下拉 | AC-10 |
| C-6 | **`GET /dataset/quote/customer-parts`** ⚠️ **待主源新增** | 「客户产品」页签列表数据源 | AC-2, AC-3, AC-14 |

### 消费方硬约束（写进 `fronttask.md` 逐条落实）

1. **分页 `page` 是 0-based** —— 主源 §3 明确。antd `Table` 的 `current` 是 1-based，**必须减 1**，否则首页取到第二页。
2. **数值以字符串回传**，保留库中 scale。🚫 **禁止 `Number()` 转换后再格式化**，会丢精度（与既有 `part-costing` 一致）。
3. **列渲染按 `ColumnDef.type`（`STRING`/`NUMBER`/`DECIMAL`），🚫 不得按列名硬编码。**
   `task-260902` 已修正 50 列类型（31 列方向错），典型如 `pricing_unit` 由 `DECIMAL` 改为 `STRING`。
   🚨 **`pricing_unit` 不得当数字格式化 / 不得右对齐。**
4. **`role=AXIS` 的列在抽屉内隐藏**（轴值已在抽屉标题上），与核价侧一致。
5. **`role=NAME` 的列只读且不回传** —— 本任务全只读，天然满足；但渲染时要能正确显示 JOIN 出来的名称列。
6. **`versionNo` 为 `null`** = 该 sheet 该轴值从未有过数据 → tab 不打徽标，进去是空态（AC-12）。
7. **`readOnly` 字段**：主源在请求历史版本时回 `readOnly=true`。本任务**无论该字段为何值，一律按只读渲染**，不得据其推导出可编辑分支。

---

## 2. ⚠️ 需要主源补齐的两处（已于 2026-09-03 提出，待对方确认）

### 缺口 1（阻塞级）：`ds_quote_customer_part` 无任何读端点

**事实**：8 个端点中，`GET parts` 读物料表、`GET lookup/{masterType}` 读的是现有 `customer` / `process` / `element` / `material_recipe` **主数据表**，均非 `ds_quote_customer_part`。免版本表按主源 R-2「不进抽屉 tab」⇒ 客户料号表在新体系里**没有查看入口**。

**建议形状**（对齐主源 `GET parts`，仍 `{dataset}` 参数化）：

```
GET /dataset/{dataset}/customer-parts
Query : page(0-based) / size / keyword / sortBy / sortDir
200   : { success:true, data:{ total: 17, items:[ {
            customerNo, customerName, customerPartName,
            customerProductNo, customerDrawingNo, materialNo } ] } }
```

- `customerName` **必须由后端 JOIN `customer` 表**得出：`ds_quote_customer_part` 只有 `customer_no`，且主源第一轮已提醒「客户名现网大量为空，要走 customer 表 JOIN 或兜底」。仅显示 `CUST-0004` 这类编号对业务不可用。

  🚨 **JOIN 列名实测确认（写错直接报错）**：`customer` 表**没有 `customer_no` 列**，客户编号列名是 **`code`**。
  ```sql
  LEFT JOIN customer c ON c.code = t.customer_no
  ```
  ⚠️ 必须是 **LEFT JOIN**：实测 17 行里 **3 行 JOIN 不到**（`Q13CUST0617` 2 行、`C1` 1 行未在客户档案登记），
  用 INNER JOIN 会**静默丢掉这 3 行**，列表总数从 17 变 14。命中情况实测：

  | 客户编号 | 客户名称 | 行数 |
  |---|---|---|
  | `CUST-0004` | 正泰 | 11 |
  | `CUST-0001` | 罗克韦尔 | 2 |
  | `CUST-0002` | 测试客户 | 1 |
  | `Q13CUST0617` | **JOIN 不到** | 2 |
  | `C1` | **JOIN 不到** | 1 |

  > JOIN 不到时回 `null`，前端渲染 `—`（AC-2）。这不是缺陷，是现网真实状态。
- `keyword` 建议匹配 `customer_no` / `customer_product_no` / `material_no` 三列（AC-14 要按 `CUST-0004` 搜出 12 行）。

### 缺口 2（次要）：`GET parts` 响应缺 `productionNo`

`ds_quote_material` 建了 7 列，主源 §3 响应体只有 `axisValue / materialName / specification / dimension / oldMaterialNo / unitWeight`，**少 `production_no`**。AC-4 要求 7 列全展示 ⇒ 请在 `items` 补 `productionNo`。

### 退路条款（未获对方承接时启用）

若 `task-260902` 明确本期不接，则**由本任务新建两个只读查询端点**，并满足全部三条：

1. 🚫 只读，不含任何写操作，不触碰 `VersionedGroupWriter` 与升版路径
2. 路径与主源风格一致，`{dataset}` 参数化，便于日后并入主源
3. **需用户在闸门 A 明确批准**（这构成对交接说明「不要另写后端」边界的一次例外）

> ⚠️ 未获确认前，`fronttask.md` 的 F-2 按 C-6 契约写对接层并用 mock 自测，**不会被这一决策阻塞**。

---

## 3. 不涉及的端点（写明「为什么不调」，防后续会话误加）

| 端点 | 为什么不调 |
|---|---|
| `POST /dataset/quote/import` | 本页无导入入口（②范围明确不做）。导入留在报价单管理的「导入报价数据」按钮 |
| `PUT /dataset/quote/parts/.../rows` | 本页纯只读。**调它就产生了第二条升版路径**，是交接说明点名禁止的事 |
| `GET /dataset/quote/lookup/{masterType}` | 该端点服务于**可编辑表格的下拉候选**。本页无编辑态 ⇒ 无下拉 ⇒ 不需要 |
| `/api/cpq/products*`（现 `ProductResource`） | `product` 表页签被本任务摘除 UI 入口，但**端点与表保留**（三张表外键引用）。本任务不调、不改、不删 |
| `/api/cpq/material-masters*` | 同上，`material_master` 的 CRUD 端点保留不动 |
| `/pricing-basic-data/*` | 核价侧既有端点，本任务一个字节都不碰（主源 AC-43） |

---

## 4. `main-api.md` 回写

| 情况 | 回写动作 |
|---|---|
| §2 两处由主源承接 | **本任务无契约变更，不回写**；由 `task-260902` 随其自身端点一并回写 |
| §2 走退路条款（本任务自建） | **必须回写** `dev-docs/main-api.md`，两个新端点各起一节 + 来源标记 `> 来源任务：task-260903-产品管理页重做｜回写日期：<实取日期>` |

> 按 `task-docs.md §2.5`：回写时机 = 测试完成后、合并 master 之前。未回写不得进入合并环节。
