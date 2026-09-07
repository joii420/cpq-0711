# API 契约 · task-260907 取数配置器补齐

> 本文只写**本任务改动到的**端点。未列出的端点 = 本次不动。
> 合并前须按 `task-docs.md §2.5` 回写 `dev-docs/main-api.md` 总账。

---

## 0. 一条会让所有调试白做的前提

`GET /config/semantic-graph/field-tree` 的方言入参名是 **`dialect`**，🚫 **不是 `dataset`**。

**JAX-RS 静默忽略未知查询参数** —— 传错不报错，三个方言会全部返回**缺省 QUOTE 的同一份结果**。
> 🔴 主线 2026-09-06 因此误判过一次范围（把「三方言都中」报了出去，实际只有 QUOTE 中）。
> 判据：若三方言返回**逐格相同**，先怀疑参数名，再怀疑被测对象。

---

## 1. `GET /api/cpq/config/semantic-graph/field-tree` —— **响应内容变化，契约形状不变**

### 1.1 入参（不变）

| 参数 | 说明 |
|---|---|
| `dialect` | `QUOTE` / `COST_BASIC` / `COST_DETAIL` |
| `tabType` | 三段坐标之一 |
| `variantKey` | 三段坐标之一，可空 |
| `selectedConfig` | 已选列 JSON，用于算 `groups[].conflict` |

### 1.2 `availableSources` —— **条数增加，字段形状不变**（F-4，AC-11①）

```
改动前（2026-09-07 实测）：QUOTE 11 · COST_BASIC 10 · COST_DETAIL 18
改动后（预期）：          QUOTE 14 · COST_BASIC 10 · COST_DETAIL 18
                          ↑ 新增 3 项：年降系数 / 组装加工费年降 / 来料年降（仅报价侧）
```

新增三项的形状与既有项**完全一致**，`semantic` 均为 `null`（普通平铺数据源）：

```json
{ "sourceKey": "ANNUAL_DISCOUNT", "label": "年降系数",
  "tabType": "<坐标>", "variantKey": "", "dialect": "QUOTE", "semantic": null }
```

🚫 **前端不得按 `label` / `sourceKey` 硬编码判语义**，一律读 `semantic`（沿用 `task-260904` F-2 的既有纪律）。

> 🔄 **2026-09-07 更正（原文写错了，由前端工程师指出、主线复核确认）**：
> 原文写「`semantic` 必须是显式 `null` 不能是缺键，缺键会让 PRICE 组隐藏判断永不生效」—— **不成立**。
> 实现是 `SqlViewBuilderTab.tsx:784`：
> ```ts
> const semantic = selectedSource ? (selectedSource.semantic ?? null) : undefined;
> ```
> `??` 把 `undefined` 与 `null` **都**塌成 `null` ⇒ **键缺失照样得到 `null`，PRICE 组照常隐藏**。
> `undefined` 的唯一来源是 **`selectedSource` 本身不存在**（当前坐标不在 `availableSources` 里），与 JSON 有没有这个键无关。
>
> ✅ **仍然保留的建议**（理由改写）：🚫 不引入 `@JsonInclude(NON_NULL)`。
> 不是因为它会打断三态，而是因为**契约里声明为可空的字段应当稳定出现**，缺键会让「服务端没给」与「服务端给了 null」在调用方看来不可区分 ——
> 本任务的前端恰好用 `??` 抹平了，但下一个消费方未必。
>
> 📌 **主线记账**：这个错误判断在 `task-260904` 期间就形成了，当时还把 Jackson 的 null 输出策略称作「前端三态设计的地基」并据此做了一轮实测。
> 实测本身没错（Quarkus 确实未开 `NON_NULL`），**错在因果**——我把一个恰好成立的事实，当成了另一件事成立的原因。

### 1.3 `groups` —— 「物料」数据源新增一组（F-1，AC-1）

`tabType=主件 & dialect=QUOTE` 的响应中，`groups` 由 **1 组**变为 **2 组**：

| groupKey | groupKind | 说明 |
|---|---|---|
| `MATERIAL` | `MAIN` | 物料表 `ds_quote_material`，9 个业务列（不变） |
| `CUSTOMER_PART` 🆕 | `AUX` | 客户料号表 `ds_quote_customer_part`，4 个业务列 |

新增组的字段（业务列 = 物理列 − 系统列）：
`customer_no` · `customer_part_name` · `customer_product_no` · `customer_drawing_no`

⚠️ **仅 `dialect=QUOTE` 新增该组**。`COST_BASIC` / `COST_DETAIL` 下**不出现**（本期只接报价侧，见需求文档 §② 不做什么）。

### 1.4 不进语义图的表提示 —— 由 4 张减为 0 张（AC-1③）

面板当前提示「本数据集有 **4** 张表不进语义图（配置器里拖不到）」，本次 4 张**全部接入**：

| 表 | 本次去向 |
|---|---|
| `ds_quote_customer_part` | 并入「物料」数据源（AUX 组） |
| `ds_quote_annual_discount` | 独立数据源「年降系数」 |
| `ds_quote_assembly_fee_annual` | 独立数据源「组装加工费年降」 |
| `ds_quote_incoming_annual` | 独立数据源「来料年降」 |

⇒ **该提示整条消失**（或 N=0）。

> 🚨 **归属更正（2026-09-07，前端工程师指出）**：本节写在「`GET /field-tree` 响应内容变化」下，**读起来像后端产物 —— 错了**。
> 该提示的数据源是**前端硬编码常量** `SqlViewBuilderTab.tsx:60` 的 `EXCLUDED_TABLES`，
> **后端把 4 张表接进语义图不会让它消失**，必须前端改。
> ⇒ 已由前端把 `QUOTE` 的 4 条清空（机制保留、注释写明去向）。
>
> 🚦 **合并前置**：该提示现在**无条件消失**，与后端是否真的接入 4 张表**没有耦合**。
> 若 B-1/B-2 滑期，界面会既没有那 4 张表的字段、也没有「它为什么不在这儿」的解释，**且没有任何信号**。
> ⇒ **合并前必须先确认 AC-1① 与 AC-11① 实测通过**（主线 2026-09-07 裁决，采纳前端工程师的风险提示）。

### 1.5 错误（不变）

| HTTP | code | 触发 |
|---|---|---|
| 404 | `COMPILE_TABVIEW_NOT_FOUND` | `(tabType, variantKey, dialect)` 三段坐标查不到 ACTIVE 行 |

> ⚠️ 传入已退役的 `tabType=零件` / `外购件` 仍返 **200**（`task-260904` AC-25①）。
> 收缩发生在**入口**（`availableSources` 不列出），不在**校验** —— 否则 142 个存量组件会当场打不开。**本任务不得改动这一点**（AC-9④）。

---

## 2. `POST /api/cpq/components/{componentId}/builder/compile` —— **请求响应形状均不变，产物变化**

契约（`{ dialect, tabType, variantKey?, switches?, columns: [...] }`）与响应字段**一个都不改**。
变的是 `semantic='TREE'` 时**生成的 SQL 内容**（F-3，AC-4）：

### 2.1 改动前（缺陷）

```sql
SELECT dqmb.material_no AS hf_part_no, dqmb.item_seq AS "_物料BOM_项次", ...
FROM ds_quote_material_bom dqmb
WHERE dqmb.material_no = ANY(:total_material_no)
```
三个独立缺陷：① 从不产出 `parent_no`；② 过滤在**父件列**只捞一层；③ 无根分支。

### 2.2 改动后（边式契约，与存量 BOM 树页签同形）

```sql
-- 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支
SELECT dqmb.input_material_no AS material_no,   -- 子
       dqmb.material_no       AS parent_no,     -- 父
       dqmb.input_material_no AS hf_part_no, ...
FROM ds_quote_material_bom dqmb
WHERE dqmb.input_material_no = ANY(:total_material_no)
UNION ALL
SELECT dqm.material_no, NULL::text, dqm.material_no, ...   -- 根分支：无父边的成品自身
FROM ds_quote_material dqm
WHERE dqm.material_no = ANY(:total_material_no)
  AND NOT EXISTS (SELECT 1 FROM ds_quote_material_bom x WHERE x.input_material_no = dqm.material_no)
```

### 2.3 🔑 递归**不在这份 SQL 里**，也不该在这里

`:total_material_no` 是「**本单料号闭包**」，由 `costing_bom_tree_config` 里那份**全系统共用**的
`WITH RECURSIVE … CYCLE material_no SET is_cyc` 从 `unnest(:production_part_nos)`（本单根成品）展开产出；
本页签 SQL 只吐**集合内的边**，树由 Java 侧 `BomTreeRenderService` 拼。

⇒ 数据量边界是**这一单**，不是全表（用户 2026-09-07 A0 裁决的依据）。

🚫 **不许为每个页签生成 `WITH RECURSIVE`** —— 那是把已有递归重做一遍，且绕开现成的 `CYCLE` 防环与配置化管理。

### 2.4 反向约束（AC-8）

`semantic != 'TREE'` 的数据源，产物**不带**父子列、**不做**根分支 UNION，与改动前**逐字相同**。

---

## 3. `GET /api/cpq/component-directories` —— **新增一个响应字段**（F-2，AC-3）

> ⚠️ 端点路径与现有参数**一律不变**，仅在组件项上加字段。

🚨 **必须加在这条链路上，加错地方会静默失效**（2026-09-07 前端工程师指出，主线已复核）：

```
GET /api/cpq/component-directories
  → ComponentDirectoryResource.tree()
  → ComponentDirectoryDTO.components : List<ComponentDTO>      ← dataSourceLabel 加在这个 DTO
```
前端 `componentService.listDirectories`（`:186`）是**原样透传，无 mapper**。
⇒ 若只加在别的组件列表端点（如 `GET /components`），**徽章会 222 个全显示「—」，且不报错、不告警**。

> 📌 本文档原写「组件列表接口」而未点名端点 —— 这类含糊在本项目会稳定地变成「加了但没生效」。

### 3.1 新增字段

```json
{
  "id": "...", "code": "COMP-0043", "name": "物料BOM",
  "tabType": "BOM",                    // 保留（存量语义，前端不再用它渲染徽章）
  "dataSourceLabel": "物料BOM"          // 🆕 本次新增
}
```

| 字段 | 类型 | 语义 |
|---|---|---|
| `dataSourceLabel` | `String`（可为 `null`） | 该组件所绑数据源的**用户可见名**。未绑数据源（`builder_version` 为 NULL）时为 **`null`** |

### 3.2 前端渲染契约

| `dataSourceLabel` | 徽章显示 |
|---|---|
| 有值 | 该值（如「物料BOM」「物料与元素BOM」） |
| `null` | **「—」**（用户 2026-09-07 裁决） |

🚫 **前端不得从 `tabType` 推导徽章**（AC-3③）—— 判据：前端徽章取值处不再出现 `comp.tabType`。

> 实测存量分布（2026-09-07）：222 个组件中绑了数据源的 **20** 个 ⇒ **202 个显示「—」**（其中 107 个原本有页签类型徽章）。
> 这是用户知情选择的代价，不是漏项。

### 3.3 🚨 N+1 红线

`dataSourceLabel` 需要 join `component_sql_view` + 语义图。
🚫 **禁止在列表循环里逐个查** —— 单个业务操作的 SQL 条数必须是常数，与组件数无关（`backend.md`）。
⇒ 一次批量 join 取回，或在列表主查询里带出。

---

## 4. `costing_bom_tree_config` 的递归 SQL —— **本任务不改**

⛔ **归 `task-260907-报价侧加客户维度`（其 B-7）**。

本任务的 F-3 只改**页签 SQL 的产出形态**，不碰那份共用递归。
> 原因：递归换读新表要接 `customer_no` 客户隔离，而新表体系加客户维度已拆为独立任务。
> 本任务的 F-3 **不依赖**该前置，可先落地。

---

## 5. 本次**不改**的接口（写明判定依据，非留空）

| 端点 | 为什么不改 |
|---|---|
| `GET /components/{id}/builder` | 读回已保存配置，形状不变；新增数据源不改变 `builderConfig` 的结构 |
| `POST /builder/preview` · `/builder/inspect` | 请求响应契约不变。⚠️ `inspect` 的**行为**受 F-3 影响（树页签豁免标识列，已由 `task-260904` B-22 落地），本次不再动 |
| `PUT /components/{id}/builder` | 保存契约不变 |
| `POST /builder/detach` | 不涉及 |
| 维护端 `GET /dataset/{dataset}/parts/{axisValue}/...` | ⛔ 归 `task-260907-报价侧加客户维度`（复合轴会改 `{axisValue}` 语义）。**本任务一行不动** |

**回归确认清单**（本任务合并前须逐条确认这些端点行为未变）：
`GET /builder` 回读 · `preview` 行数与列 · `inspect` 的 ERR/WARN 集合 · `PUT /builder` 的 409 影响确认链路。
