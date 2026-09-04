# 接口契约 · 产品维护能力增强（子任务）

> 父任务：`task-260903-产品管理页重做`　|　日期：2026-09-03
> 🚫 **不复制外部契约原文**。`task-260902` 的端点以其 `api.md` 为准，本文只写「本任务消费什么」「本任务改什么」。

---

## 0. 本任务的接口立场

| 项 | 结论 |
|---|---|
| **消费**对方新增端点 | 2 个（`PUT parts/{axisValue}` · `GET parts` 的新字段） |
| **改动**本任务自建端点 | 1 个（`customer-parts` 加过滤参数） |
| **新增**本任务端点 | 1 个（客户过滤的候选来源，见 §2） |
| 新建表 / 迁移 | **0** —— 建表与加列全归 `task-260902`（其 B-16） |

---

## 1. 消费 `task-260902` 的端点（归其实现，本任务只调用）

### C-1　`PUT /api/cpq/dataset/{dataset}/parts/{axisValue}`　（其 B-20）

用途：改 `ds_quote_material` 的 `production_no`。服务 **AC-6 / AC-6b / AC-10 / AC-11 / AC-12 / AC-13 / AC-15**。

```
PUT /api/cpq/dataset/quote/parts/S-1630010773
body: { "productionNo": "TEST-PROD-001" }
```

**消费方硬约束（逐条落实到 `fronttask.md`）**：

1. **只传要改的字段**。对方实现是「只更新传入的列 + `updated_at`/`updated_by`」，🚫 **不要整行回传** —— 会把没改的列一起写。
2. 🚨 **`source` 不由前端传、也不要期望它变**。它是**行级**来源（`IMPORT`/`MANUAL`），页面改一列不应把整行标成 `MANUAL`（该行绝大部分数据仍来自导入）。**AC-10 断言 `source` 保持 `IMPORT`。**
3. **白名单在后端**（Registry 的 `ColumnDef.editable`）。不在白名单的字段返回 **400**。⇒ **AC-11 必须绕过 UI 直接打接口验**，只靠"前端没渲染输入框"不算达成。
4. **权限：四个角色**（`SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`）——用户 2026-09-03 裁决，与对方原建议相反，已请其按此实现。未登录 401。
5. **清空语义**：传 `null` / 空串的行为需与对方对齐（AC-12 要求落库为 `NULL` 而非空字符串）。**联调时实测确认，不猜。**

### C-2　`GET /api/cpq/dataset/quote/parts` 的新字段　（其 B-16）

响应 `items` 将新增 `categoryCode` / `categoryName`（产品分类）。服务 **AC-8**。

- ⏸ 未落地前，`ProductSalesPartTab` 的产品分类列**渲染 `—` 且不得崩**（与父任务处理 `productionNo` 缺字段时同样的兜底）。
- 🚩 **默认值落点待确认**：DB 列 `DEFAULT` 指向 `product_category.code='000000'`（默认分类）vs 导入器在空值时填充 —— 两者在「用户显式清空」时行为不同。**AC-8 的断言以对方落地后的实测为准**，联调时对齐。

---

## 2. 本任务改动 / 新增的端点

> 这两个都落在父任务自建的 `com.cpq.product.dataset` 包内，**不涉及跨任务协调**。

### B-1　`GET /api/cpq/dataset/{dataset}/customer-parts` —— 加过滤参数（改）

服务 **AC-1 / AC-2 / AC-3 / AC-4 / AC-14**。

```
新增 Query： customerNo （可选。省略 = 所有客户）
```

- 与既有 `keyword` **可叠加**（AC-3：先按客户过滤，再按关键字搜，两者是 AND）
- `customerNo` 传入不存在的值 ⇒ 返回 `total:0` + 空 items，**不是 404**（AC-14 的空态由前端渲染）
- 🚫 **不改变** `columns` 的下发内容与既有响应形状（父任务 17 行的 AC-2 仍须成立）

### B-2　`GET /api/cpq/dataset/{dataset}/customer-parts/customers` —— 新增

用途：客户过滤器的**候选来源**。服务 **AC-5**。

```
200: { code:200, data: { items: [ { customerNo, customerName, count } ] } }
```

🚨 **候选必须来自 `ds_quote_customer_part` 里实际出现过的 `customer_no`（`SELECT DISTINCT`），而不是 `customer` 表。**

> **本条是 AC-5 的全部意义所在**：`Q13CUST0617` 与 `C1` **未在 `customer` 表建档**（父任务实证，其客户名称显示 `—`）。
> 若候选只从 `customer` 表取，**这两个客户的 3 行产品将永远无法被过滤到** —— 页面上看得见、却筛不出来。

- `customerName` 仍走 **LEFT JOIN `customer` ON `customer.code = customer_no`**（父任务实证：`customer` 表**没有 `customer_no` 列**，键是 `code`；且必须 LEFT，INNER 会丢掉未建档的 3 行）
- JOIN 不到时 `customerName` 回 `null`，前端下拉显示 `客户编号（未建档）` 之类可辨识文案（具体文案以原型为准）
- `count` 为该客户的产品行数，供下拉显示「正泰（11）」这类提示 —— **非必需，实现可省，但省了要在 `fronttask.md` 注明**

---

## 3. 不涉及的端点（写明「为什么不调」，防后续会话误加）

| 端点 | 为什么不调 |
|---|---|
| `POST /dataset/{dataset}/import` | 本页仍无导入入口。导入留在报价单管理的按钮 |
| `PUT /dataset/{dataset}/parts/{axis}/sheets/{key}/rows` | **抽屉维持全只读**（AC-7 反向断言）。调它就等于把抽屉也变成可编辑，是用户明确排除的范围 |
| `GET /dataset/{dataset}/lookup/{masterType}` | 本任务唯一的编辑控件是生产料号**自由文本输入**，无下拉候选需求。若将来改成从料号库选，再议 |
| `/api/cpq/products*` / `/api/cpq/material-masters*` | 父任务已摘 UI 入口、端点保留不动。本任务同样不碰 |

---

## 4. `main-api.md` 回写

| 端点 | 回写责任 |
|---|---|
| C-1 / C-2（对方的 B-20 / B-16） | **归 `task-260902`**，本任务不回写 |
| B-1（`customer-parts` 加 `customerNo` 参数） | **本任务回写** —— 覆盖 §6.9 该端点条目的 Query 说明 |
| B-2（`customer-parts/customers` 新增） | **本任务回写** —— 在 §6.9 追加一行 + 来源标记 |

> 时机：测试完成后、合并 master 之前（`task-docs.md §2.5`）。**未回写不得进入合并环节。**
