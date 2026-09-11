# api · task-260911

> 前后端唯一的协调物。本任务**前端零改动**（见 `fronttask.md`），故本文主要记**后端内部契约**。

---

## 1. HTTP 端点契约：**零变更**

不新增、不删除、不修改任何端点的方法 / 路径 / 请求参数 / 响应形状 / 错误码。

**为什么不改**（零改动也要写清判定依据，不许留空槽）：

本任务全部发生在**SQL 生成 → 参数绑定 → 展开结果分发**这三层，都在后端内部。
对调用方而言，`expand-driver` / `batch-expand` / `refresh-snapshot` 的**请求不变、响应形状不变**：
- 行数从"每行各查一次得到的 1 行"变成"合桶查回超集后挑出的 1 行"——**结果相同、次数不同**
- 客户产品编号列的**取值口径不变**（仍是该明细行自己的那一个）

⇒ 本次**无需回写 `dev-docs/main-api.md`**（`task-docs §2.5`：方法/路径/参数/响应/错误码全未变可跳过，但须在 `test-report.md` 写明）。

---

## 2. 🆕 语义图新增角色：`ROW_SCOPE`（后端内部契约）

| 项 | 约定 |
|---|---|
| **落点** | `semantic_node_column.roles`（数组列，**数据库表**，改动走迁移）|
| **两层覆盖** | 页签级 `semantic_tab_view_column` 覆盖 > 节点级 `semantic_node_column` 默认（复用 `SemanticCompiler:746` 的 `D-35` 机制）|
| **语义** | 该列参与构成对端表的**行粒度**，且其取值**因明细行而异** |
| **编译器行为** | 生成 `AND <别名>.<列> = ANY(:<列>s)` **集合成员**谓词，位置**必须在 `LEFT JOIN … ON`** |
| **分发层行为** | 该列纳入回分依据：每个明细行从桶里挑出 `<列> = 本行作用域值` 的那一行 |
| **加法式保证** | 未打该标记的列行为**逐字节不变**（`AC-7`）|

### 现状基线（2026-09-11 实查，供比对）

```
semantic_node_column.roles 全库分布：
  {}                 236 列 / 51 节点
  {ROW_KEY}           50 列 / 38 节点
  {PART_NO,ROW_KEY}   42 列 / 42 节点
  {SORT}              28 列 / 28 节点
  {PART_NAME}          8 列 /  8 节点
  {PART_NO}            2 列 /  2 节点

CUSTOMER_PART(QUOTE, ds_quote_customer_part) 5 列 roles 全为 {}：
  customer_no / customer_part_name / customer_product_no / customer_drawing_no / material_no
```

---

## 3. 运行时占位符：单数 → 复数

| | `repair-260910`（将废弃） | 本任务 |
|---|---|---|
| 占位符 | `:customerProductNo`（**标量**） | `:customerProductNos`（**集合**）|
| 值来源 | 从 `:lineItemId` 反查**该行**的 `customer_part_no` | 从 `:quotationId` 一次查出**整单去重集合** |
| SQL 形态 | `= :customerProductNo` | `= ANY(:customerProductNos)` |
| 对合桶 | **破坏**（结果依赖行） | **不破坏**（结果只依赖单）|
| 谁挑出本行那一行 | SQL | **分发层**（`backtask B-7` 的单点投影方法）|

🚨 **摆放位置不变**：只许出现在 `LEFT JOIN … ON`，**绝不许进 `WHERE`**。
理由与 `repair-260910` 相同且更强 —— 实测 **128/3970** 明细行客编为空，进 `WHERE` 会让这些卡片整页签 **0 行且不报错**。

⚠️ **不要照搬 `:customerCode` 的"未绑定硬阻断"**：`customerCode` 一定有值，而客编**本来就允许为空**。
对它硬阻断会把 `AC-5` 打死。

---

## 4. 🚨 分发层的作用域列从配置读，不硬编码

`backtask B-7` 的投影方法判断"哪一列是作用域列"时，**必须**从 `component_sql_view.builder_config` 的 `columns[]` 里读：

```json
{ "sourceNodeKey": "CUSTOMER_PART",
  "sourceColumn":  "customer_product_no",
  "viewColumn":    "_客户料号_客户产品编号" }
```

🚫 **不许在 Java 里写死 `"customer_product_no"` 或 `"_客户料号_客户产品编号"`** ——
那会让 `AC-9`（加第二个行级列只改配置不改代码）当场不成立，也就否定了本任务"做通用机制"的立项前提。
