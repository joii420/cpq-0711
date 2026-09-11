# 诊断：单张卡片内 `snapshot_rows` 每行渲染两遍

> 📅 2026-09-10 · 由主线派纯只读诊断代理产出，主线落盘。
> 🔒 本轮**只跑 `SELECT`**（无 DELETE/UPDATE/DROP/TRUNCATE）、只读 java 文件、未跑 maven、未起服务。
> 🚦 服务的裁决：**D-20**（用户裁决「本期连卡片重影一并修」）。

---

## 1. 结论一句话

**「重影」不是一个 bug，是两个，必须分开裁决：**

| 形状 | 判定 | 判据 |
|---|---|---|
| **非树页签**（`产品` / `材质元素` / `T260907-*`，`component.bom_recursive_expand=false`）逐列全等两行 | ✅ **存量，已修** | 全库最后一次出现 `snapshot_at = 2026-09-09 08:18:44+00`（= `08c99680` 那张单）。`repair-260908-页签重复行与跨客户串号` 合入 master `54863517` 于 **2026-09-09 13:17:15 UTC**。合入后至今渲染的 **262 张非树卡片，0 张有该形状** |
| **树页签**（`BOM`，`bom_recursive_expand=true`）成块重复 | 🔴 **活 bug，今天仍在产** | 卡片 `1bde53ba`（单 `e9ac790d` / `QT-20260910-0805`）`snapshot_at = 2026-09-10 12:03:56.987385+00`，晚于三个候选提交全部。合入后 51 张树卡片中 **1 张命中** |

**⇒ 「能否复现」的答案：树页签那一支能，且触发数据条件已定位（见 §4）。非树那一支复现不出来是正常的 —— 它已经被修掉了，`08c99680` 是修复前最后一批渲染的产物。**

> 🚨 **前一轮「造同型数据复现不出来」的原因已定位**：造数选的第三变量错了。
> 「同单同料号 2 个 line item」「同料号挂两客户」**都不是**触发条件。
> 真正的触发条件是 **同一 `(customer_no, 父件料号, 子件料号)` 在 `ds_quote_material_bom` 里有 ≥2 行**（`item_seq` 不同也算）。
> 前一轮用的 `S0004` 每条边只有 1 行 ⇒ 放大倍数恒为 1 ⇒ **结构上不可能复现**。

---

## 2. Q1 · 那些行是怎么产生的

### 2.1 写入方链路（已核实存在，非猜路径）

```
ConfigureSnapshotService:436   bomTreeRenderService.render(customerTemplateId, liteLines, null, "QUOTE")
  → BomTreeRenderService:524     rows = queryRecursive(cfg.sqlTemplate, seed, ...)        // 骨架递归 CTE = spine
  → BomTreeRenderService:530     CostingTreeGrouping.group(rows)                          // 分组 + 生成 nodeId
  → BomTreeRenderService:678-686 逐 spine 节点 × 该边业务行 → baseRows
  → ConfigureSnapshotService:1771/1830/1921  UPSERT quotation_line_component_data.snapshot_rows
```

文件真实路径（`find -name` 实取，非包名推测）：

- `cpq-backend/src/main/java/com/cpq/quotation/service/BomTreeRenderService.java`
- `cpq-backend/src/main/java/com/cpq/quotation/service/CostingTreeGrouping.java`
- `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java`
- `cpq-backend/src/main/java/com/cpq/quotation/service/dsrecord/DsRecordCardDeduper.java`

📌 `CardSnapshotService.java` 存在且 5506 行，但报价侧卡片值是**复用** `snapshot_rows`、不二次 expand（见其 `:51` `:1117` `:2397` 注释）⇒ **它不是本形状的写入方**。

### 2.2 根因：`m × n` 笛卡尔积（树页签，活 bug）

三处都**没有 occurrence 维度**，串起来就是乘法：

**① spine 侧无去重** — `BomTreeRenderService.java:1051`

```java
while (rs.next()) {
    out.add(new CostingTreeNode(   // ← 递归 CTE 返回几行就几个节点，无 DISTINCT
        rs.getString("root_no"), rs.getString("material_no"),
        rs.getString("bom_version"), rs.getString("parent_no"), rs.getString("node_path")));
}
```

`:1019` 的外层投影是 `SELECT root_no, material_no, bom_version, parent_no, node_path FROM (...) q` —— **无 DISTINCT**。

**② `nodeId` 直接等于 `node_path`，撞号** — `CostingTreeGrouping.java:26` / `:40`

```java
byRoot.computeIfAbsent(r.rootNo, k -> new ArrayList<>()).add(r);   // :26  List，不去重
...
nd.nodeId = path;                                                  // :40  两行同 node_path ⇒ 同 nodeId
```

而 QUOTE 骨架的 `node_path` 只拼料号（`(b.node_path||'/'||ch.input_material_no)`），**不含 `item_seq` / 不含边行 id** ⇒ 同一 `父→子` 的两条 BOM 行产生**两个 node_path 完全相同的节点**。

**③ 边键无 occurrence 维度** — `BomTreeRenderService.java:841-843`

```java
static String edgeKey(String parentNo, String materialNo) {
    return (parentNo == null ? "" : parentNo) + EDGE_SEP + (materialNo == null ? "" : materialNo);
}
```

业务行按 `(parent_no, material_no)` 分桶（`:697-700`），同一条边的 N 行业务行全落进**同一个桶**。

**④ 最后在 `:678-686` 做成乘法**

```java
for (CostingTreeNode node : treeRows) {                                  // m 个同 path 节点
    List<...> bizRows = byKey.get(edgeKey(node.parentNo, node.materialNo)); // n 行同边业务行
    if (bizRows != null && !bizRows.isEmpty()) {
        for (ExpandDriverResponse.Row br : bizRows) {
            baseRows.add(treeRowNode(node, br));                          // ⇒ m × n 行
        }
    } else {
        baseRows.add(treeRowNode(node, null));                            // ⇒ m 行空行
    }
}
```

正确行数应是 **n**（每条 BOM 行一行），实际是 **m × n**。

### 2.3 逐行对齐验证（活案例，13 行完全算对）

卡片 `1bde53ba-4e60-41c7-84d6-1ec2102d74b0`（单 `e9ac790d` / line `acd87376` / 产品 `0028-2609000018` / `BOM` 页签 / `snapshot_at 2026-09-10 12:03:56.987385+00`）：

```sql
select ord, r.elem->'driverRow'->>'hf_part_no' as partno, md5(r.elem::text) as h
from quotation_line_component_data cd
cross join lateral jsonb_array_elements(cd.snapshot_rows) with ordinality r(elem,ord)
where cd.id='1bde53ba-4e60-41c7-84d6-1ec2102d74b0' order by ord;
```

```
 ord |     partno      |                h
-----+-----------------+----------------------------------
   1 | 0028-2609000018 | 8a0a3fcad97bc7330ef3dc9d7c6efa45
   2 | 0028-2609000016 | 1f8c582d19a7f99a1aa1b79b52133351   ┐
   3 | 0028-2609000016 | 1fa5c613425754afbc37b524c2af709d   │ 块 A
   4 | 0028-2609000017 | 9bb8a737dea9178d325fadb48936fe29   │
   5 | 0028-2609000017 | d7cc277855c2a916243c7171b8775099   ┘
   6 | 0028-2609000016 | 1f8c582d19a7f99a1aa1b79b52133351   ┐
   7 | 0028-2609000016 | 1fa5c613425754afbc37b524c2af709d   │ 块 A 复现（逐字节相同）
   8 | 0028-2609000017 | 9bb8a737dea9178d325fadb48936fe29   │
   9 | 0028-2609000017 | d7cc277855c2a916243c7171b8775099   ┘
  10 | AgCu90          | 3263bd4d1d234069894b78470ac760cc
  11 | AgCu90          | b84b4eb6f7fbecfc9df3d54d80345940
  12 | AgCu90          | 3263bd4d1d234069894b78470ac760cc
  13 | AgCu90          | b84b4eb6f7fbecfc9df3d54d80345940
(13 rows)
```

源数据（**边行数 = 2，`item_seq` 不同 ⇒ 是合法的两条 BOM 行，不是脏重复**）：

```sql
select id, material_no, item_seq, input_material_no, component_qty,
       left(row_fingerprint,12) fp, source, created_at
from ds_quote_material_bom
where customer_no='CUST-0004' and material_no='0028-2609000018'
order by input_material_no, id;
```

```
  id   |   material_no   | item_seq | input_material_no | component_qty  |      fp      | source |          created_at
-------+-----------------+----------+-------------------+----------------+--------------+--------+-------------------------------
 16596 | 0028-2609000018 |        1 | 0028-2609000016   | 1.000000000000 | 4ab5f67c034a | MANUAL | 2026-09-10 12:03:55.477732+00
 16598 | 0028-2609000018 |        3 | 0028-2609000016   |                | 5c28cf6986d8 | MANUAL | 2026-09-10 12:03:55.477732+00
 16597 | 0028-2609000018 |        2 | 0028-2609000017   | 1.000000000000 | 14f031eaff61 | MANUAL | 2026-09-10 12:03:55.477732+00
 16599 | 0028-2609000018 |        4 | 0028-2609000017   |                | 0d3db819c766 | MANUAL | 2026-09-10 12:03:55.477732+00
```

算式（**逐项对上 13**）：

| 节点 | spine 节点数 m | 该边业务行 n | 渲染行数 | 实测 ord |
|---|---|---|---|---|
| 根 `…018`（`parent=NULL`） | 1 | 1（$view 根分支） | 1 | 1 |
| `…018 → …016` | **2**（16596/16598 各产一个同 path 节点） | **2**（同两行） | **4** | 2,3,6,7 |
| `…018 → …017` | **2** | **2** | **4** | 4,5,8,9 |
| `…016 → AgCu90` | 2（继承父节点重复） | 1 | 2 | 10~13 中 2 行 |
| `…017 → AgCu90` | 2 | 1 | 2 | 10~13 中 2 行 |
| | | | **13** ✅ | 13 行 |

**正确结果应为 7 行**（根 1 + `…016` 2 + `…017` 2 + `AgCu90` 2）。

📌 `ds_quote_material_bom` 的索引只有 `idx_ds_quote_material_bom_axis_ver btree (material_no, version_no)` —— **边 `(customer_no, material_no, input_material_no)` 上没有任何唯一约束**，重复边在结构上被允许。

### 2.4 `08c99680`（`S0001` / 8 行）另有其解 —— 存量，且部分**无法完全重建**

**BOM 页签（`c030e045`，8 行）：可以断定「重复来自 spine，不是业务行」**

第 1/2 行是**业务列全 NULL 的空节点行**（`treeRowNode(node, null)`，`BomTreeRenderService.java:685`）：

```json
{"__lvl":1,"__nodeId":"S0001","driverRow":{"parent_no":null,"hf_part_no":"S0001","material_no":"S0001",
 "_物料BOM_项次":null,"_物料_材料名":null,"_物料BOM_组成数量":null, ...},"basicDataValues":{...全 null}}
```

空节点行**每个 spine 节点只发一次** ⇒ 出现 2 行 ⇒ **当时的 spine 里根节点 `S0001` 有 2 个**。
而 `seed` 是 `LinkedHashSet`（`5a0461db` 版本 `:323`，当日在用；HEAD 是 `:446`）⇒ **不是种子重复**，是当时那版骨架 CTE 的**根分支自己扇出了 2 行**。

**非树页签（`产品` 6 行 / `材质元素` 4 行）：与已结案缺陷同形，判为同一根因**

```
 li       | tab_name | tot | distinct_rows | dup_groups | maxc
 f69cad35 | BOM      |   8 |             4 |          4 |    2
 f69cad35 | 产品     |   6 |             3 |          3 |    2
 f69cad35 | 材质元素 |   4 |             2 |          2 |    2
```

`dev-docs/task-260819-取数配置器/repair-260908-页签重复行与跨客户串号/` 的实测链条原文是「**产品页签 6 行 → 3 行 → 1 行**」，数字与形状完全吻合；`V435__repair260908_compat_master_dedup_ds_branch.sql` 抬头实测记录「重号料号 = **S0001..S0014** + T260907-M1/M2（各 2 行，CUST-0001 / CUST-0004）」—— **正是 `08c99680` 里那批料号**。

🚫 **这一支给不出「当日实际执行的 SQL」这一级证据**（缺什么见 §6）：

- `quotation_component_sql_snapshot` 对这两张单 **0 行**（DRAFT 不冻结 SQL）
- `costing_bom_tree_config`（QUOTE 骨架）`updated_at = 2026-09-09 17:59:16+00`，**晚于**该单渲染时刻 08:18 ⇒ 当日在用的骨架文本已被覆盖，且该表无 history
- `material_bom_item`（当日骨架按 `V427` 读的老表）里 `S0001/S0002/S0003` 现在 **0 行**（V6 退役已清）

---

## 3. Q2 · 存量还是活 bug（含全库数量与时间分布）

### 3.1 判定 SQL（本节所有表同一口径）

```sql
with e as (
  select cd.id, r.elem from quotation_line_component_data cd
  cross join lateral jsonb_array_elements(cd.snapshot_rows) r(elem)
  where cd.snapshot_rows is not null and jsonb_typeof(cd.snapshot_rows)='array'
), g as (select id, elem, count(*) c from e group by 1,2),
agg as (select id, sum(case when c>1 then 1 else 0 end) dup_groups from g group by 1)
select ... from agg join quotation_line_component_data cd on cd.id=agg.id where agg.dup_groups>0 ...
```

「逐列完全相同的两行」= 同一 `snapshot_rows` 数组内 `jsonb` 元素逐字节相等，含 `driverRow` / `basicDataValues` / `__nodeId` 全部系统列。

### 3.2 全库总量与按天分布

| `snapshot_at` 日 | 命中卡片 | 重复组数 | 最大重复度 | 涉及单 |
|---|---|---|---|---|
| 2026-09-08 | 12 | 12 | 2 | 1 |
| 2026-09-09 | 160 | 406 | 2 | 3 |
| **2026-09-10** | **1** | **6** | **2** | **1** |
| （`snapshot_at` 为 NULL） | 4 | 10 | 2 | 1 |
| **合计** | **177** | **434** | **2** | **6** |

**最大重复度全库恒为 2**（`maxc = 2`）—— 没有 3 倍及以上。

### 3.3 🔑 按页签类型切分（判「存量 vs 活着」的决定性一刀）

| 页签类型 | 命中卡片 | 重复组 | 首次出现 | **最后一次出现** |
|---|---|---|---|---|
| `flat`（`bom_recursive_expand=false`） | 145 | 292 | 2026-09-08 05:13:49+00 | **2026-09-09 08:18:44+00** |
| `tree(BOM)`（`=true`） | 32 | 142 | 2026-09-08 05:13:49+00 | **2026-09-10 12:03:56+00** |

### 3.4 与修复合入时刻对齐（阳性/阴性对照）

`repair-260908-页签重复行与跨客户串号` 合入 master：

```
$ TZ=UTC git log --format='%h | %ad UTC | %s' --date=iso -1 54863517
54863517 | 2026-09-09 06:17:15 -0700 UTC | Merge branch 'feat/repair-260908-tab-dup-rows' (AC-15 / AC-18 收口)
```

即 **2026-09-09 13:17:15 UTC**。

| 页签类型 | 修复后渲染的卡片 | 其中命中重影 |
|---|---|---|
| `flat` | **262** | **0** |
| `tree(BOM)` | **51** | **1** |

**⇒ `flat` 支**：修复后 262/262 干净，且全库最后一次命中（09-09 08:18:44）比修复合入（09-09 13:17）**早 5 小时** —— 存量、已修；按 `repair-260908` 的 `D-27` 裁决「存量单不会自愈」，库里这 145 张会一直留着。
**⇒ `tree` 支**：修复后仍产出 1 张 —— **活 bug**。命中率低是因为触发条件稀有（见 §4），不是因为已修。

### 3.5 树页签按小时分布（可见它跨越了全部三个候选提交）

| `snapshot_at` 小时 | 页签 | 卡片 | 重复组 |
|---|---|---|---|
| 2026-09-08 05:00 | `T260907-物料BOM` | 6 | 6 |
| 2026-09-09 00:00 | `T260907-物料BOM` | 8 | 42 |
| 2026-09-09 06:00 | `BOM` | 8 | 42 |
| 2026-09-09 08:00 | `BOM` | 8 | 42 |
| **2026-09-10 12:00** | **`BOM`** | **1** | **6** |
| （NULL） | `BOM` | 1 | 4 |

### 3.6 三个候选提交的时间轴订正 🚨

**主线派工时给的 `414206e8` 时间「09-08 18:22」是错的**，会把判断带偏。实取：

```
$ TZ=UTC git log --format='%h | %ad UTC | %s' --date=iso -5 -- .../BomTreeRenderService.java
414206e8 | 2026-09-09 11:22:57 -0700 UTC | feat(task-260909): 核价树骨架分档 + 轴口径统一 + 行归属键改正 + 权限收紧
5a0461db | 2026-09-08 23:55:07 -0700 UTC | fix(repair-260908): R-1 报价树渲染拿不到 customerId（主线亲验抓到的真回归）
```

`414206e8` = **2026-09-09 18:22:57 UTC**，比 `08c99680` 渲染（09-09 08:18 UTC）**晚 10 小时**。

| 候选 | UTC 时刻 | 与 `08c99680`(09-09 08:18) | 与今日活案例(09-10 12:03) | 结论 |
|---|---|---|---|---|
| `f52b8050`（D-43 dedupe） | 09-08 08:56 | 之前 | 之前 | 与树页签重影无因果（`occurrenceKey` 含 `lineItemId`，结构上不管卡内） |
| `V437` 迁移 | **2026-09-09 10:59:16**（`flyway_schema_history.installed_on`） | **之后** ⇒ 当日未生效 | 之前 | 不是活 bug 的责任方 |
| `414206e8` | **2026-09-09 18:22:57** | **之后** ⇒ 当日未生效 | 之前 | 不是活 bug 的责任方；但它把非树桶键 `material_no → hf_part_no`（B-6），是 `flat` 支能干净的一环 |
| `54863517`（repair-260908 合并） | **2026-09-09 13:17:15** | **之后** | 之前 | ✅ **`flat` 支的真修复点** |

**三个候选提交全部排除为「活 bug 的责任方」**：树页签的 `m × n` 乘法在这三个提交之前就在（`:678-686` 的结构在 `414206e8^` 里逐字相同，只有桶键那行改了），之后也还在。它是**历史结构缺陷 + 稀有数据触发**，不是某次改动引入的回归。

---

## 4. Q3 · 为什么前一轮造的同型数据复现不出来

### 4.1 三张单的差异表

| 维度 | `08c99680`（有重影，存量） | `e9ac790d` / line `acd87376`（有重影，**今日活**） | `2d45b3e8`（前一轮造的，无重影） |
|---|---|---|---|
| 客户 | `CUST-0004`（正泰） | `CUST-0004` | `CUST-0004` |
| 料号 | `S0001` ×2 line item | `0028-2609000018` ×1 line item | `S0004` ×2 line item |
| 同单同料号多 line item | ✅ 是（4 个料号各 2 行） | ❌ **否，只有 1 个 line item** | ✅ 是 |
| 该料号同挂两客户 | ✅ 是 | ？（`0028-*` 未查） | ✅ 是 |
| **同一 `父→子` 边在 `ds_quote_material_bom` 的行数** | 当日不可查（源已清） | ✅ **2 行**（`item_seq` 1/3 与 2/4） | ❌ **1 行** |
| 结果 | 每页签 ×2 | `BOM` 13 行（应 7） | `BOM` 6 行（正确），`_record` 0 重复 |

🔑 **「同单同料号多 line item」被这张表直接否证**：今日活案例只有 1 个 line item 却照样重影。

### 4.2 触发条件的第三变量（就是这个）

```sql
select customer_no, material_no, input_material_no, count(*) c,
       array_agg(item_seq order by item_seq) seqs
from ds_quote_material_bom group by 1,2,3 having count(*)>1 order by c desc,1,2;
```

```
 customer_no |   material_no   | input_material_no | c | seqs
-------------+-----------------+-------------------+---+-------
 CUST-0001   | 0526-2609000005 | 0526-2609000004   | 2 | {1,3}
 CUST-0001   | 0526-2609000005 | TEST-Q13-CODE     | 2 | {2,4}
 CUST-0001   | VS-FG01         | 3120011203        | 2 | {1,1}   ← item_seq 也相同，纯脏重复
 CUST-0001   | VS-FG01         | VS-SA02           | 2 | {2,2}   ← 同上
 CUST-0001   | VS-SA02         | VS-RM03           | 2 | {1,1}   ← 同上
 CUST-0004   | 0028-2609000018 | 0028-2609000016   | 2 | {1,3}
 CUST-0004   | 0028-2609000018 | 0028-2609000017   | 2 | {2,4}
(7 rows)

 dup_edge_keys | rows_involved
             7 |            14
```

**触发条件（充分）**：某卡片的 BOM 闭包里，存在至少一条 `父→子` 边在 `ds_quote_material_bom` 有 ≥2 行（同 `customer_no`）。
**放大倍数** = 该边行数 m × 该边业务行数 n（业务行 `$view` 读同一张表 ⇒ 通常 n = m ⇒ **m²**）。今日案例 m=n=2 ⇒ 每条边 4 行。

**为什么 `S0004` 造不出来**：直接回放骨架 CTE，`S0004` 子树每个节点 `occurrences = 1`：

```sql
WITH RECURSIVE bom AS (  -- 逐字取自 costing_bom_tree_config(usage='QUOTE', is_active)
  SELECT p::text AS root_no, p::text AS material_no, NULL::text AS bom_version,
         NULL::text AS parent_no, p::text AS node_path,
         COALESCE('CUST-0004'::varchar, (SELECT bc.customer_no FROM ds_quote_material_bom bc
           WHERE bc.material_no=p ORDER BY bc.customer_no LIMIT 1)) AS _cust
  FROM unnest(ARRAY['S0001','S0004','S0008','S0012']::text[]) AS p
  UNION ALL
  SELECT b.root_no, ch.input_material_no::text, NULL::text, ch.material_no::text,
         (b.node_path||'/'||ch.input_material_no)::text, b._cust
  FROM ds_quote_material_bom ch JOIN bom b
    ON ch.material_no=b.material_no AND ch.customer_no=b._cust
  WHERE ch.input_material_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, parent_no, node_path, count(*) occurrences
FROM bom GROUP BY 1,2,3,4 ORDER BY root_no, node_path;
```

```
 root_no | material_no | parent_no |     node_path     | occurrences
 S0004   | S0004       |           | S0004             |           1
 S0004   | S0005       | S0004     | S0004/S0005       |           1
 S0004   | 00006       | S0005     | S0004/S0005/00006 |           1
 S0004   | S0006       | S0004     | S0004/S0006       |           1
 S0004   | 00168       | S0006     | S0004/S0006/00168 |           1
 S0004   | S0007       | S0004     | S0004/S0007       |           1
 ...（S0001/S0008/S0012 同样全 1）
```

⇒ m=1 ⇒ `m × n = n` ⇒ **今天的 HEAD + 今天的 `S0004` 数据，结构上不可能出重影**。
前一轮的「复现不出来」是**正确的阴性结果**，不是掩盖。

### 4.3 顺带排除的两条

- **「同料号挂两客户」不是触发条件**：`ds_quote_material` 有 `uq_ds_quote_material UNIQUE (customer_no, material_no)` ⇒ 主表侧结构上不会双发；`ds_quote_customer_part` 的唯一键是 `(customer_no, customer_product_no)`（**不含 `material_no`**），它**会**在「同客户同料号绑多个客户产品编号」时对**非树页签**扇出 —— 今天 `S0004`/`CUST-0004` 就有 4 行（`B17-DUP-0910-1`/`-2`/`B18-BIND-0910B`/`RW-A004`），实跑 `产品` 视图确认 S0004 出 **4 行 4 个不同 `cpn`** ⇒ **内容不同，不构成「逐列全等」的重影**，是另一个话题。
- **`DsRecordCardDeduper` 确认无责**：`occurrenceKey`（`:135` / `:253`）刻意含 `lineItemId`（AP-60 纪律），结构上不收敛卡内。今日 `e9ac790d` 的 `ds_quote_material_bom_record` 12 行 / 10 认领 / **2 unanchored**，其中 `0028-2609000018` 的 4 条按 `item_seq 1~4` **一行不多一行不少**（`id 10195~10198`）⇒ **卡片的 13 行重影并没有传导成 `_record` 翻倍**，record 侧那 2 条 unanchored 是跨卡片认领问题，与本形状无关。

---

## 5. Q4 · 要改哪里、风险多大、能不能证伪

⚠️ `BomTreeRenderService` / `CardSnapshotService` 属 `docs/三大核心模块基线.md` **报价单渲染核心模块**，下列均需**闸门 A0 裁决**。

### 修法甲：spine 去重（建议先评估）

**改点**：`CostingTreeGrouping.java:26`（或 `BomTreeRenderService.java:1019` 外层加 `SELECT DISTINCT`）—— 按 `(rootNo, materialNo, bomVersion, parentNo, nodePath)` 五元组去重后再入 `byRoot`。

- **效果**：m 压回 1 ⇒ 今日案例 13 → **7 行**（= n，正确）
- **改动面**：1 个纯函数类 + 0 DDL + 0 SQL 契约变更。`queryRecursive` 的 2 个调用点（`:192` `collectTotalMaterialNoUnion` / `:524` `renderInternal`）都覆盖；前者产物本来就是 `LinkedHashSet` ⇒ **对闭包料号并集逐位无影响**
- **是否违反既有契约**：`BomTreeRenderService` 类注释写着「**同料号多 occurrence 保留**」。多 occurrence 的合法形态是**同子件挂不同父**（`node_path` 不同）⇒ 五元组去重**保留**它，只折叠 `node_path` 逐字相同的那些。⇒ 不破契约，但**必须在改动里就地写明这条区分**，否则下一个人会当成把 occurrence 语义删了
- **残留风险**：`n` 行业务行仍全部挂在同一个树节点下（`__nodeId` 相同的 2 行）—— 前端树控件按 `__nodeId` 建父子关系时，同 id 多行的行为需单独验（这是**已存在**的形态，不是本修法新引入的）
- **回归面**：报价卡片 BOM 页签行数、`row_data` 物化行序（`ConfigureSnapshotService:3603` 记「`row_data` 行序 = `snapshot_rows` 减墓碑行」）、页签小计（AP-51 行数权威）、核价侧 `T260907-*` 树页签同链路

### 修法乙：给边加 occurrence 维度（语义最正，代价最大）

**改点**：骨架 CTE 契约（`root_no/material_no/bom_version/parent_no/node_path` **加 `item_seq` 或边行 id**）+ `queryRecursive:1019` 投影 + `CostingTreeNode` + `edgeKey():841` + `treeRowNode():1109` + 树页签 `$view` 必须输出同一列 + `CostingTreeSqlValidator` 探针桩。

- **效果**：m 个节点与 n 行业务行 **1:1 配对** ⇒ 7 行且每行挂对自己那条 BOM 行；`node_path` 也不再撞号
- **改动面**：**跨 SQL 契约 + 配置器生成器 + 全库 5 个树页签视图 + 骨架配置数据**（`costing_bom_tree_config` 4 条 usage）。属 `change-protocol.md` 强联动改动
- **风险**：高。任一处漏改的失败形态是**静默 0 行**（参照 `414206e8` B-6 的教训原文：「原先写死 `material_no` 的后果不是报错，是恒 0 行，连红都不红」）
- 建议：**本期不做**，登 BACKLOG

### 修法丙：渲染后按逐字节相同折叠（🚫 不建议）

**改点**：`:686` 之后对 `baseRows` 去重。

- 今日案例能压回 7 行，但会**同时误伤合法的逐字节相同行** —— 例如 `VS-FG01 → 3120011203` 的 `seqs {1,1}`（`item_seq` 都相同的真重复行）在业务上究竟该显示 1 行还是 2 行，**是数据问题不是渲染问题**，折叠等于在渲染层替业务做裁决
- 与 **AP-51**「driver 行数权威、绝不 Math.max、绝不在渲染层调行数」、**AP-60**「不拿渲染投影当权威」两条纪律直接冲突

### 修法丁（配套，非替代）：数据侧收口

- 给 `ds_quote_material_bom` 加 `UNIQUE (customer_no, material_no, input_material_no, item_seq)` —— 能挡住 `seqs {1,1}` 那 3 组纯脏重复，**挡不住** `{1,3}` 这种合法两行 ⇒ **单独用它修不了本 bug**
- 🚫 涉及既有 14 行的清理 = `CLAUDE.md §3.2` 红线（`DELETE`），必须先给影响面数字再由用户批准。**本轮未执行任何清理**

### 🧪 能否证伪：有（阳性对照可造，且今天已被真实用户无意造出）

**造数配方（必然触发，零随机）**：

1. 取一个客户（如 `CUST-0004`）下的成品料号 `P`，子件 `C`
2. 在 `ds_quote_material_bom` 里让 `(CUST-0004, P, C)` 存在 **2 行**，`item_seq` 不同（今日现场就是用 UI 手工加 BOM 行产生的，`source='MANUAL'`，`created_at 2026-09-10 12:03:52`）
3. 新建报价单选 `P` 建卡 ⇒ `BOM` 页签 `snapshot_rows` 应为 **4 行**（2 组两两全等），修复后应为 **2 行**

**三条断言（AP-51 / `testing.md §4.4` 口径，全部非空正向）**：

```sql
-- A1 阳性对照（修复前必须为 4，即 bug 在）
select jsonb_array_length(snapshot_rows) from quotation_line_component_data
where line_item_id = :li and component_id = :bomCompId;     -- 期望 4（修复后 2）

-- A2 逐字节重复组必须为 0（修复后）
with e as (select r.elem from quotation_line_component_data cd
           cross join lateral jsonb_array_elements(cd.snapshot_rows) r(elem)
           where cd.id = :cardId)
select count(*) from (select elem, count(*) c from e group by 1 having count(*)>1) t;  -- 期望 0

-- A3 行数必须等于该边的 BOM 行数（不是 1，防「修成过度折叠」）
select count(*) from ds_quote_material_bom
where customer_no='CUST-0004' and material_no=:P and input_material_no=:C;  -- 期望 2，且 A1 == 2
```

🚨 **A3 不可省**：只验 A2（无重复）会把「把 2 行合法 BOM 行折叠成 1 行」也判成通过 —— 那是**过度修复**，症状是用户少看到一条 BOM 明细，且同样不报错。

**现成阴性对照**：`S0004` 那套数据（每条边 1 行）—— 修复前后行数必须逐位不变（6 行），用来证明修法没有误伤单边场景。

---

## 6. 结论里「无法判定」的那一半：缺什么证据、怎么取

**已判定（有硬证据，可直接进 A0）**：树页签 `BOM` 重影 = **活 bug**，根因 = `m × n` 笛卡尔积，`file:line` 已定位，触发条件已定位，阳性对照可造。

**无法判定（缺证据）**：`08c99680` 那张单**当日实际执行的 SQL 文本**，因此「非树页签 6 行」是否 100% 等同 `repair-260908` 的已修缺陷，只能说**同形 + 同料号 + 同时间窗 + 修复后 262/0**，不能说**逐字证明**。

| 缺的证据 | 为什么现在拿不到 | 怎么才能拿到 |
|---|---|---|
| 当日 `component_sql_view.sql_template`（`产品`/`材质元素`） | 表无 history，`updated_at` 只记最后一次（`2026-09-09 01:26:53`） | 只能从 `repair-260908` 分支的迁移/文档里反推；或今后给该表加 history（BACKLOG 候选） |
| 当日 `costing_bom_tree_config`（QUOTE 骨架）文本 | 同上，`updated_at = 2026-09-09 17:59:16` 已覆盖 | 同上 |
| 当日 `material_bom_item` 中 `S0001` 的边行 | V6 退役已清空（现 0 行） | 不可恢复 |
| 当日 DRAFT 单的 SQL 冻结 | `quotation_component_sql_snapshot` 对该单 0 行（DRAFT 按设计不冻结） | 不可恢复 |

**若要把这一半也钉死**：在 `feat/repair-260908-tab-dup-rows` 的提交里取出 `V435` 之前的 `v_compat_material_master` / `产品` 视图文本，在**测试库**（`cpq_db_test`，🚫 不在 `cpq_db_0724`）上按 `08c99680` 的料号集回放一次，看 `产品` 是否出 6 行。**这需要建库级夹具，属独立任务，不建议塞进本期。**

---

## 7. 下一步建议（主线执行）

1. **拆成两件事**：`flat` 支 = 存量清理（145 张卡片，是否修数据 / 是否提供重算入口，注意 `repair-260908 D-27` 已裁决「存量单不自愈」）；`tree` 支 = 本期真 bug
2. **`tree` 支按修法甲/乙/丙/丁进闸门 A0**，阳性对照按 §5 三条断言写进 AC
3. **登记 BACKLOG**：`ds_quote_material_bom` 的边唯一性缺失（7 组重复边 / 14 行，其中 3 组是 `item_seq` 也相同的纯脏数据），含清理影响面数字，清理动作走 §3.2 报批
4. **订正 `INDEX.md`「按症状反查」**：现有条目只覆盖「非树页签重复行」（`repair-260908`），需补一条「**树页签成块重复 = spine 同 node_path 多节点 × 同边多业务行的 m×n**」，否则下一个人会再次把它误认成 `repair-260908` 复发而不去查边行数
5. **订正时间轴认知**：`414206e8` 是 **2026-09-09 18:22 UTC**（不是 09-08 18:22），`V437` 是 **09-09 10:59 UTC** —— 按错的时间轴会把 `08c99680` 判成「修复后仍在产」，结论整体翻面
