# 后端任务分解 · task-260907 取数配置器补齐

> 后端只按本文做。与前端唯一的协调物是 `api.md`。
> 🚫 不 `git commit`（由主线提交）· 🚫 遇 `CLAUDE.md` §3.2 不可逆操作停下报告（子代理无批准权）。

---

## B-1 · `ds_quote_customer_part` 接入语义图，作为「物料」的 AUX 附属节点

**服务的 AC**：AC-1 · AC-2 · AC-6

- 加 `semantic_node`（`CUSTOMER_PART`，dialect `QUOTE`）+ 其 `semantic_node_column`（4 个业务列）
- 加 `semantic_edge`：`MATERIAL → CUSTOMER_PART`，关联键 `material_no`，**JOIN 类型 = LEFT**
- 挂 `semantic_tab_view_node`：`role = AUX`，挂在 `QUOTE / 主件` 页签上

**为什么是 AUX 而不是独立节点**：用户只要求「物料页签里能拖到」，没要求它单独成源。
AUX 靠主节点（物料）的轴带出来，与现有「主件」页签形态一致，改动最小。

🔑 **实测依据**（2026-09-07 dev 库）：
```
ds_quote_customer_part 19 行 · 19 个不同 material_no · 一个 material_no 对多行 = 0 条  ⇒ 确为 1:1
ds_quote_material      47 行                                                    ⇒ 只有 19/47 有客户料号
```
⇒ **必须 LEFT JOIN**，用 INNER 会把 28 个物料整行丢掉（AC-2②）。

⚠️ **JOIN 方向不许反**：用户 2026-09-07 明确裁决**物料为主**。
（早前曾说过「以客户料号作为主表」，**已被新裁决取代**，见需求文档 §⑤。）

---

## B-2 · 年降三表各自接入为**独立数据源**

**服务的 AC**：AC-11

三张表各建独立 `semantic_node` + 各自的 `semantic_tab_view`（`dialect=QUOTE`）：

| 表 | 数据源名 | 业务列数 |
|---|---|---|
| `ds_quote_annual_discount` | 年降系数 | 7 |
| `ds_quote_assembly_fee_annual` | 组装加工费年降 | 逐列核对 |
| `ds_quote_incoming_annual` | 来料年降 | 逐列核对 |

> `ds_quote_annual_discount` 的 7 个业务列（物理列 − 系统列 `id/version_no/row_fingerprint/source/created_*/updated_*`）：
> `material_no, discount_seq, discount_rate, fixed_discount_value, currency, pricing_unit, discount_times`

🚫 **不许挂成「物料」的 AUX** —— 实测三表与物料是 **1:N**（都带 `discount_seq` 年降档次），
挂成 AUX 会把物料的 47 行放大成 47×N（AC-11④ 专门验这条）。

🚧 **本项只能验到结构层**：三表实测**均 0 行** ⇒ 预览必然 0 行、渲染链路无法验证。
🚫 **不写「预览返回 0 行」这类断言**（恒真，掩盖「数据链路从没验过」）。已在需求文档标为缺口。

---

## B-3 · 🔴 编译器：`semantic='TREE'` 时产出**边式契约**

**服务的 AC**：AC-4 · AC-7 · AC-8　**优先级：最高**（另一条会话的模板交付卡在本项）

改 `SemanticCompiler`，`semantic='TREE'` 时**三处**（其余数据源一个字不变）：

1. **补产出父子两列**：`input_material_no AS material_no`（子）/ `material_no AS parent_no`（父）
2. **过滤改到子件列**：`WHERE input_material_no = ANY(:total_material_no)`（现在错在父件列 `material_no`）
3. **补 `UNION ALL` 根分支**：无父边的成品自身，`parent_no` 置 `NULL::text`，其余列补同类型 NULL 占位

产出形态见 `api.md §2.2` 与 `原型图/取数配置Tab-F3-BOM树SQL.html`。

### 🚫 三条禁区

- **不许生成 `WITH RECURSIVE`** —— 递归已存在于 `costing_bom_tree_config`（全系统共用、配置化、自带 `CYCLE` 防环），
  从 `unnest(:production_part_nos)` 即本单根成品出发。**每页签再递归一次是重做**，且绕开现成防环。
- **不许去掉过滤全表捞** —— 用户当场否掉（数据量）。集合边界是这一单。
- **不碰 `costing_bom_tree_config`** —— 归 `task-260907-报价侧加客户维度` 的 B-7。

### ⚠️ 一个必须自己确认的点

`UNION ALL` 两分支的**列数与类型必须逐位对齐**。存量视图注释里有明确铁律：
> 白名单表必须在顶层 `FROM`，不能写成 `FROM bom_closure JOIN <白名单表>`，
> 否则 `QuotePendingRewriter` 主位表探测退化选中 CTE base case 的表 → 锚点注入报
> `each UNION query must have the same number of columns`

⇒ 生成的 SQL 必须能通过 `QuotePendingRewriter` 的锚点注入。**这条要实测，不要推断。**

---

## B-4 · 组件列表接口返回 `dataSourceLabel`

**服务的 AC**：AC-3

- 列表项加 `dataSourceLabel`（`String`，可 `null`）
- 取值：该组件 `component_sql_view.builder_config` 的三段坐标 → 反查语义图 → 锚点节点 `display_name`
- 未绑数据源（`builder_version` 为 NULL）→ **`null`**（前端渲染成「—」）

🚨 **N+1 硬指标**：🚫 禁止在列表循环里逐个查。
单个业务操作的 SQL 条数必须是**常数，与组件数无关**（222 个组件不能变成 222 条查询）。
⇒ 一次批量 join 取回，或并进列表主查询。**回报里给出实际 SQL 条数。**

---

## B-5 · ⛔ 递归 SQL 换表 + 接客户隔离 —— **本任务不做**

**已移交** `task-260907-报价侧加客户维度` 的 **B-7**。

原因：换读新表 `ds_quote_material_bom` 要接 `customer_no` 客户隔离，而新表体系加客户维度
（28 张表 DDL + 轴模型单列改复合 + `VersionedGroupWriter` 6 个调用方）已由用户裁决拆为独立任务。

✅ **B-3 不依赖本项**，可先落地。

---

## 自检（每项完成后逐条跑，回报里贴命令原始输出）

- `mvnw -o clean test-compile` 通过
- 相关测试类全绿（至少：`Sec34PriceStrategyTest` / `TabTypeValueDomainSelfCheckTest` / `FieldTreeAndDialectParseSelfCheckTest` / `com.cpq.task260904.**`）
- 业务端点返 200/401（**不要 500**）
- 迁移 `success = true`（查 `flyway_schema_history`）
- **N+1 自检**：说清每个新增/改动路径的 SQL 条数是否与 N 无关

## 🚨 环境坑（本任务线已实证两次）

1. **worktree 里跑 `quarkus:dev` 与在同目录跑 `mvnw` 共用 `target/`**，会互相踢出**假红**：
   `NoClassDefFoundError` / `Could not load class with name: XxxTest`。
   ⇒ 跑测试用**隔离副本**（拷 `src/ pom.xml mvnw .mvn` 到 scratch），**拷完必须 `diff -rq` 确认与源一致**。
2. **`pkill -f "quarkus:dev"` 会误伤他人进程** —— 必须按端口精确定位 PID（`ss -lptn "sport = :<port>"`）。
3. 🚫 **绝不 cd 回主仓跑 `mvnw`** —— 会测到另一棵树，报假绿。

## 🚫 红线

- 不动 `FieldTreeBuilder.ALL_TAB_TYPES`（`TabTypeValueDomainSelfCheckTest` 钉死它与 `VALID_TAB_TYPES` 相等）；要过滤在 `build()` **输出侧**做
- 不改 V413 种子里 `FUNC_ELEMENT_PRICE` 的 AUX 挂载（有意为之 + `Sec34PriceStrategyTest` 5 条靠它 + 已应用共享库）
- 共享 dev 库**不许跑清库型测试**；夹具用完按主键删净并给出「残留=0」证据
- 迁移号是移动靶：取号前先看 master 最新；**已应用到共享库的迁移禁改名改号**
