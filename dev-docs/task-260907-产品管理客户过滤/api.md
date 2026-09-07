# task-260907-产品管理客户过滤 · 接口契约

> **唯一事实源**。前后端两个子代理互相看不见，这份文档是它们之间**唯一的协调物**。
> ⚠️ 开工后如需改契约，按 `docs/rules/task-docs.md` §4「开工后 AC/契约变更」四步走 —— 改完必须**主动通知在跑的子代理**，它们不会主动重读。
>
> **基址**：`/api/cpq`（前端 axios `baseURL` 已含）。响应信封 `{ code, message, data }` —— 🚨 **没有 `success` 字段**，前端 `unwrap` 只按有无 `data` 键解包。

---

## 0. 本次契约变更总览

| # | 端点 | 变更 | 服务的 AC |
|---|---|---|---|
| **A-1** | `GET /dataset/{dataset}/customers` | 🆕 **新建** | AC-1, AC-2, AC-14 |
| **A-2** | `GET /dataset/{dataset}/parts` | 加 `customerNo` 入参 + 响应加两个键 | AC-3, AC-5, AC-8, AC-16, AC-17 |
| **A-3** | `GET /dataset/{dataset}/parts/{axisValue}/overview` | 加 `customerNo` 入参 | AC-6, AC-17 |
| **A-4** | `GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows` | 加 `customerNo` 入参 | AC-6, AC-17 |
| **A-5** | `GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions` | 加 `customerNo` 入参 | AC-6, AC-17 |
| **A-6** | `PUT /dataset/{dataset}/parts/{axisValue}` | 加 `customerNo` 入参 | AC-7, AC-17 |
| — | `GET /dataset/{dataset}/customer-parts` | ❌ **不改**（已有 `customerNo`，见 §3） | AC-4 |
| — | `GET /dataset/{dataset}/customer-parts/customers` | ❌ **不改**，但**不再被前端调用**（见 §3） | — |

### 🚨 贯穿全部 6 个端点的三条硬约束

**硬约束 1 · `customerNo` 一律是 query 参数；后端语义「不传 = 不过滤」，但前端恒传。**

> 🔄 **2026-09-07 用户变更（`D-7` / `D-11`）**：UI 上**客户必选**，「所有客户」选项**已取消**，前端在任何时候都会带上 `customerNo`。
> **但后端契约保持「可选」不变** —— 这 5 个端点**三套数据集共用**（核价两套也在调），后端改强制会波及它们。
> ⇒ 强制性由**前端恒传 + UI 无「所有客户」入口**保证，不由后端 400 保证。`AC-17` 因此仍然成立，且它保护的是**其它调用方**而非本页面。
这是已裁决的 A0-2（`交接说明-customer_no.md` §⑥）：路径结构不变、向后兼容。
🚫 **不许**改成路径段（`/parts/{customerNo}/{axisValue}`）—— 破坏性变更，4 个端点 URL 全变；
🚫 **不许**把复合值编码进单段（`CUST-0001~S-80011`）—— 料号里出现分隔符就会静默解析错。
**AC-17 专门验这条**：不带该参数时响应与改动前逐字一致。

**硬约束 2 · 过滤必须在 SQL 里做，🚫 不许在内存里筛。**
内存筛会让 `total` 保持过滤前的数值 ⇒ 第 2 页取到未过滤的第 2 页，翻页整体错位。**AC-3③ 专门证伪它。**

**硬约束 3 · 三套数据集共用这套代码，核价两套没有 `customer_no` 列。**
`cost-basic` / `cost-detail` 传入 `customerNo` 时的行为**必须显式定义**，见 §4。
🚫 **绝不许静默返回空列表** —— 那会被用户读成「这个客户没有数据」，而真相是「这套数据集根本没有客户维度」。

---

## 1. 🆕 A-1 · 客户候选

```
GET /api/cpq/dataset/{dataset}/customers
```

**为什么新建而不是复用 `customer-parts/customers`**（闸门 A0 裁决 D-6）：后者的路径属于「客户料号」子资源，而本端点服务的是**整个产品管理页的全局选择器**；且后者的 `count` 字段语义是「该客户有几条**客户料号**」，与全局选择器要表达的东西不同。

### 入参

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `dataset` | path | 是 | 当前仅 `quote`；其余返 400，与列表端点同口径 |

🚫 **无分页、不接受 `keyword`** —— 候选是全集（量级等同客户数），搜索由前端在已加载的候选里做。

### 响应

```json
{
  "code": 200, "message": "success",
  "data": { "items": [
    { "customerNo": "CUST-0001", "customerName": "罗克韦尔", "registered": true },
    { "customerNo": "CUST-0004", "customerName": "正泰",     "registered": true },
    { "customerNo": "C1",        "customerName": null,       "registered": false },
    { "customerNo": "Q13CUST0617","customerName": null,      "registered": false }
  ] }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `customerNo` | string | 客户编号，取值口径 = `customer.code`（如 `CUST-0001`）。**唯一，不重复** |
| `customerName` | string \| **null** | 客户名称。🚨 **未建档客户恒为 `null`** —— 前端必须能渲染 null，🚫 不得因它为空而过滤掉该候选项（AC-14③） |
| `registered` | boolean | `true` = 在 `customer` 表建过档；`false` = 只在报价业务表里出现过。前端据此加「未建档」标记与配色 |

### 🔑 候选口径（AC-2 的全部意义）

```sql
-- 集合 = 已建档全集 ∪ 业务表中未建档的客户号
  SELECT code AS customer_no, name AS customer_name, true  AS registered FROM customer
UNION ALL
  SELECT DISTINCT customer_no, NULL, false
    FROM <报价侧带 customer_no 的业务表>
   WHERE customer_no IS NOT NULL
     AND customer_no NOT IN (SELECT code FROM customer)
```

**两个半集各自解决一个问题，缺一不可**：

| 半集 | 解决什么 | 缺了会怎样 |
|---|---|---|
| `customer` 表全集 | **尚无任何产品数据的已建档客户也能选到** | 新建档客户在选择器里根本不出现，用户以为系统坏了 |
| 业务表未建档差集 | 未建档客户号的产品**筛得出来** | `C1` / `Q13CUST0617` 的产品「看得见却筛不出来」——这正是 `task-260903` AC-5 要防的缺陷 |

**排序**：`registered = true` 的按 `customerNo` 升序在前，`registered = false` 的按 `customerNo` 升序**置于列表尾部**。

🔑 **该排序现在承担了额外职责**（`D-7`）：客户必选后，**前端把本列表的第一项作为默认选中客户**（首次进入 / localStorage 记忆失效时）。
⇒ 🚫 **排序不得随意改动** —— 改了就等于改了所有用户的默认客户。
📌 已知后果（用户知情裁决）：当前第一项是 `8000137`（苏州西门子），实测 **0 行产品数据** ⇒ 首次进入会看到空列表。**这不是缺陷**，AC-13 的空态即为此设计。

**🚫 实现纪律**：
- 差集那一半**不许只扫 `ds_quote_customer_part` 一张表** —— 复合轴落地后，物料表等也会带客户号，只扫一张会漏。**扫哪些表由 Registry 元数据推导**（凡带 `customer_no` 列的报价侧业务表），🚫 不写死表名清单（写死的清单必然过期，这是 `task-260819` 栽过三次的形态）。
- 🚫 **N+1 红线**：无论扫几张表，SQL 条数必须是**常数**。用一条 `UNION ALL` 聚合，不许每张表一条查询。

### 错误

| 码 | 场景 |
|---|---|
| 400 | `dataset` 不是 `quote`（含核价两套 —— 它们没有客户维度，见 §4） |

---

## 2. 已有端点加 `customerNo`

### A-2 · `GET /dataset/{dataset}/parts`（销售产品列表）

**新增入参**

| 参数 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `customerNo` | query | 否（后端）· **前端恒传** | 省略 = 不过滤（保留给其它调用方，见硬约束 1）。传一个候选中不存在的值 ⇒ 返 `total: 0` 而**不是** 404 |

**新增响应字段**（`data.items[]` 内）

| 字段 | 类型 | 说明 |
|---|---|---|
| `customerNo` | string | 该行的客户编号 |
| `customerName` | string \| **null** | 由**同一条 SELECT** 内 `LEFT JOIN customer ON customer.code = m.customer_no` 带出；JOIN 不到时为 `null`，前端渲染 `—` |

🚨 **`customerName` 必须在同一条 SELECT 里 JOIN 带出，🚫 不许逐行查** —— 逐行查会让 SQL 条数变成 `2 + 料号数`，正是 `backend.md` N+1 硬指标的反面（**AC-8**）。
📌 实测 `customer.code` 有唯一索引 `customer_code_key` ⇒ 该 LEFT JOIN **不会放大行数**（与 `product_category.code` 那处同型）。

**🔴 行语义变更（本次最重要的一条契约变化）**

复合轴落地后，`ds_quote_material` 的唯一索引由 `(material_no)` 扩为 `(customer_no, material_no)`
⇒ **一行不再等于「一个销售料号」，而是「一个客户 × 一个销售料号」**。

- 同一 `axisValue` 可能返回**多行**，彼此 `customerNo` 不同；
- 🚨 前端 `rowKey` 必须改为 `${customerNo}|${axisValue}`。仍用 `axisValue` 会让 React 认为是同一行 ⇒ 表格渲染错乱、勾选串行（**AC-16②**）；
- `task-260903` AC-25「行数恒等于 `count(*) FROM ds_quote_material`」**依然成立且自适应** —— 物料表本身就变成一料号多行了，无需改写该判据。

**排序**：`sortBy` 白名单在**后端**，本次**不新增**可排序键（客户列暂不支持点击排序；🚫 前端不得自行发送白名单外的 `sortBy`，未命中会静默回退默认序）。

---

### A-3 / A-4 / A-5 · 抽屉三端点

```
GET /dataset/{dataset}/parts/{axisValue}/overview?customerNo=CUST-0001
GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/rows?customerNo=CUST-0001[&version=3]
GET /dataset/{dataset}/parts/{axisValue}/sheets/{sheetKey}/versions?customerNo=CUST-0001
```

**新增入参**：`customerNo`（query，非必填，语义同 A-2）。

🔑 **前端调用纪律（🔄 2026-09-07 用户变更 `D-10`）**：抽屉的 `customerNo` **取自壳页所选客户**。

> 用户原话：「销售产品抽屉内的内容都是产品管理选择的客户的数据。」

客户必选后列表只含当前客户的行 ⇒ 行携带的客户号与壳页所选**恒相等**，取谁结果一样。
🚨 **但契约以壳页为准**，实现**不得依赖「行里碰巧有这个值」** —— 那是巧合不是契约；将来列表若再支持跨客户展示，依赖行值的实现会静默出错（**AC-6**）。

**响应结构不变**（不加字段）。带 `customerNo` 时：
- `overview`：各 sheet 的配置计数只统计该客户的行；
- `rows`：只返回该客户的行；
- `versions`：只列该客户的版本，🚨 **不得把两个客户的版本号混排**（版本号按 `(customer_no, axisValue)` 各自独立递增）。

---

### A-6 · `PUT /dataset/{dataset}/parts/{axisValue}`（生产料号单列更新）

```
PUT /dataset/{dataset}/parts/{axisValue}?customerNo=CUST-0001
Body: { "productionNo": "P-123" }
```

**新增入参**：`customerNo`（query，非必填）。

🚨 **这是本次唯一的写端点，也是唯一「不传参数会造成数据损坏」的端点**：
复合轴下不传 `customerNo` ⇒ `WHERE material_no = ?` 命中**所有客户的同料号行** ⇒ 一次编辑改掉多个客户的数据，**页面上看不出来**。

⇒ **实现要求**：命中行数 > 1 时**必须报错**，🚫 不许静默更新多行。前端**恒传** `customerNo`（取自被编辑的那一行）。
> 保留「不传即不过滤」的读语义是为了向后兼容（AC-17），但**写路径必须靠「多行即报错」兜住**，两者不矛盾：向后兼容保的是单客户场景下的既有调用方，多行报错拦的是复合轴下的误伤。

**沿用的三条既有硬约束**（`task-260903-产品维护能力增强`，不得违反）：
1. 🚫 只传要改的字段，**绝不整行回传** —— 后端只更新传入的列 + `updated_at`/`updated_by`；
2. 🚨 `source` **不由前端传、也不应变**（行级来源 `IMPORT`/`MANUAL`），AC-7③ 断言它保持 `IMPORT`；
3. 🚨 可编辑列白名单在**后端** Registry 的 `ColumnDef.editable` 上。前端不渲染输入框只是第一道，**不是防线**——绕过 UI 直接打接口也必须被拦住。

`productionNo: null` = **显式清空**，落库 `NULL`（不是空字符串）。

---

## 3. 明确不改的两个端点

| 端点 | 为什么不改 |
|---|---|
| `GET /dataset/{dataset}/customer-parts` | **已经有** `customerNo` query 参数（`DatasetCustomerPartResource:63`），且已被 `listCustomerParts` 在用。本任务只把该参数的**来源**从页签内 state 换成壳页 props（S-6），**接口一个字不改** |
| `GET /dataset/{dataset}/customer-parts/customers` | 端点**保留不动**（可能有其它调用方），但前端改调 A-1 后它**不再被产品管理页调用**。🚫 **不许删** —— 删端点属破坏性变更，需单独裁决；结案时登记 `BACKLOG.md` 作为待清理项 |

---

## 4. 核价两套（`cost-basic` / `cost-detail`）传入 `customerNo` 的行为

**必须显式定义，🚫 不许静默返空**（AC-18）。

**本任务定为：忽略该参数，正常返回全部数据。**

| 选项 | 取舍 |
|---|---|
| ✅ **忽略参数，正常返回** | 三套共用代码路径下最省心；核价页签本来就不会传这个参数，忽略不影响任何真实调用方 |
| ❌ 显式 400 | 语义更严格，但会把「共用代码路径」变成「每个端点都要先判数据集」，且核价页签一旦被别的任务顺手加上该参数就整页报错 |
| 🚫 **静默返回空列表** | **绝对禁止** —— 用户会读成「这个客户没有数据」，而真相是这套数据集没有客户维度。这是最坏的失败形态 |

**实现判据**：按 **Registry 元数据**判断该数据集的物料表有没有 `customer_no` 列（沿用 `DatasetMaintenanceService` 现有的 `hasNonAxisColumn(reg, "...")` 手法），没有就跳过过滤条件。
🚫 **不许写死 `dataset.equals("quote")`** —— 将来哪套数据集加了这一列都要自动跟上，这是该文件既有的成文纪律（`listParts` 对 `production_no` / `material_type` / `category_code` 三列已是这么做的）。
