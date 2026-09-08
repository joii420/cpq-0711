# 后端任务分解 · task-260908-取数配置器优化

> 🚫 **本次后端交付 = 一个 Flyway 迁移，Java 源码零改动。**
> 若你在实现过程中觉得「必须改 Java 才能做到」，**停下来报主线** —— 那意味着我对既有机制的判断错了，需要重新走 A0，而不是你顺手改编译器。
>
> 依赖的四个既有机制及其位置见 `需求文档.md` ④。动手前请**先读一遍** `SemanticCompiler.resolveLookup` 与 `FieldTreeBuilder.syntheticLookupFields`，确认与本单描述一致。

---

## B-1 · 新增 7 个「瘦查名节点」

**服务的 AC**：AC-1, AC-2, AC-4, AC-5, AC-5b, AC-6, AC-7

写入 `semantic_node` + `semantic_node_column`。样板照抄现存的 `QUOTE_MATERIAL_BRIDGE`（`node_kind='LOOKUP'`, `scope='NONE'`, `grain_columns='{}'`）。

| # | dialect | node_key | display_name | short_name | physical_table | 声明列（**只声明这一列**） | 列 display_name | roles |
|---|---|---|---|---|---|---|---|---|
| N1 | QUOTE | `MAT_NAME_LK` | 物料（查名） | `物料` | `ds_quote_material` | `material_name` | 材料名 | `{PART_NAME}` |
| N2 | QUOTE | `MAT_PROD_LK` | 物料（生产料号） | `物料` | `ds_quote_material` | `production_no` | 生产料号 | `{}` |
| N3 | QUOTE | `RECIPE_NAME_LK` | 材质（查名） | `材质` | `material_recipe` | `symbol` | 材料名 | `{PART_NAME}` |
| N4 | COST_BASIC | `MAT_NAME_LK` | 物料（查名） | `物料` | `ds_cost_basic_material` | `material_name` | 材料名 | `{PART_NAME}` |
| N5 | COST_BASIC | `RECIPE_NAME_LK` | 材质（查名） | `材质` | `material_recipe` | `symbol` | 材料名 | `{PART_NAME}` |
| N6 | COST_DETAIL | `MAT_NAME_LK` | 物料（查名） | `物料` | `ds_cost_detail_material` | `material_name` | 材料名 | `{PART_NAME}` |
| N7 | COST_DETAIL | `RECIPE_NAME_LK` | 材质（查名） | `材质` | `material_recipe` | `symbol` | 材料名 | `{PART_NAME}` |

### 🚨 四条不能改的约束（每条都有具体后果）

1. **每个节点只声明表格里写的那一列，🚫 不许把整张表的列都建进去。**
   `FieldTreeBuilder.syntheticLookupFields:525-530` 会把代表节点的**全部非 `is_code` 列**内联进锚点分组。多声明一列，用户的字段面板里就多一个莫名其妙的字段。

2. **`roles` 必须严格按表格填。** `SemanticCompiler.pickColumnByRole:738-741` 在 COALESCE 时**按角色**在兄弟节点里挑分支列，取**第一个**命中的。
   - `N1`/`N3` 都必须是 `PART_NAME`，否则 AC-2 的 `COALESCE(物料.material_name, 材质.symbol)` 配不出来。
   - `N2` 的 `roles` 必须是**空数组**。给它打 `PART_NO` 会让它被别的按角色挑列的逻辑误选。

3. **`is_code` 一律 `false`**（默认值即可）。置 `true` 的列不会被内联进面板（同上 `:529` 的 `if (!col.isCode)`），字段就不可见了。

4. **🚫 不要把这 7 个节点挂进 `semantic_tab_view_node`。**
   挂了会触发 `FieldTreeBuilder:514` 的 `nodesWithOwnGroup` 分支 ⇒ 它们**自成一个独立分组**而不是内联进锚点分组 ⇒ **AC-7 直接失败**，且面板上会多出「物料（查名）」这种用户不该看见的分组。

---

## B-2 · 新增 46 条 LOOKUP 边 + 边键

**服务的 AC**：AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-24

写入 `semantic_edge`（`edge_kind='LOOKUP'`, `cardinality='MANY_TO_ONE'`, `status='ACTIVE'`）+ `semantic_edge_key`。

### 边键的通用规则（按方言，不按数据源）

| 目标节点 | 连接键（`semantic_edge_key`，`seq` 从 0 起） |
|---|---|
| **报价侧** `MAT_NAME_LK` / `MAT_PROD_LK` | **两条键**：`seq=0` `<锚点料号列> = material_no`；`seq=1` `customer_no = customer_no`（`D-11`） |
| **核价侧** `MAT_NAME_LK` | **一条键**：`seq=0` `<锚点料号列> = production_no`（核价侧无客户维度，`D-11`） |
| **任一方言** `RECIPE_NAME_LK` | **一条键**：`seq=0` `<锚点料号列> = code` |

> `left_column` = 锚点自己的列名，`right_column` = 目标节点表的列名。
> 报价侧所有 `ds_quote_*` 主表实测均有 `customer_no`（2026-09-08 核对，无一例外）。

### 边清单（42 个数据源 → 46 条边）

**规则**：物料BOM 出 2~3 条、物料与元素BOM 出 1 条（连材质表）、其余全部出 1 条（连物料表）。

#### QUOTE（14 个数据源 → 16 条边）

| 锚点 node_key | 数据源显示名 | 锚点料号列 | 出边 |
|---|---|---|---|
| `MATERIAL_BOM` | 物料BOM | `input_material_no` | → `MAT_NAME_LK`（`coalesce_group='PART_NAME'`, `fallback_order=1`）<br>→ `RECIPE_NAME_LK`（`coalesce_group='PART_NAME'`, `fallback_order=2`）<br>→ `MAT_PROD_LK`（**无 coalesce**，键用 `material_no` 不是 `input_material_no`，见下方 ⚠️） |
| `MATERIAL` | 物料 | `material_no` | → `MAT_NAME_LK` |
| `ELEMENT_BOM` | 物料与元素BOM | `material_part_no` | → `RECIPE_NAME_LK` |
| `ANNUAL_DISCOUNT` | 年降系数 | `material_no` | → `MAT_NAME_LK` |
| `ASSEMBLY_FEE` | 组装加工费 | `material_no` | → `MAT_NAME_LK` |
| `ASSEMBLY_FEE_ANNUAL` | 组装加工费年降 | `material_no` | → `MAT_NAME_LK` |
| `FINISHED_OTHER_FEE` | 成品其他费用 | `material_no` | → `MAT_NAME_LK` |
| `INCOMING_ANNUAL` | 来料年降 | `input_material_no` | → `MAT_NAME_LK` |
| `INCOMING_FIXED_FEE` | 来料固定加工费 | `input_material_no` | → `MAT_NAME_LK` |
| `INCOMING_OTHER_FEE` | 来料其他费用 | `input_material_no` | → `MAT_NAME_LK` |
| `INCOMING_RECOVERY` | 来料回收折扣 | `input_material_no` | → `MAT_NAME_LK` |
| `PLATING_FEE` | 电镀费用 | `material_no` | → `MAT_NAME_LK` |
| `SELF_PROCESS_FEE` | 自制加工费 | `input_material_no` | → `MAT_NAME_LK` |
| `SUB_COMPONENT_FEE` | 组成件其他费用 | `sub_component_no` | → `MAT_NAME_LK` |

⚠️ **`MATERIAL_BOM → MAT_PROD_LK` 是全部 46 条里唯一一条「连接键不等于锚点料号列」的边**：它按 `D-2` 用**销售料号 `material_no`**（`ds_quote_material_bom` 的轴列）去连，与材料名那条用 `input_material_no` 是两个不同的键。
🔑 **这正是 `MAT_NAME_LK` 与 `MAT_PROD_LK` 必须是两个节点而不是一个节点两条边的原因**：`semantic_edge` 的唯一约束是 `(from_node_id, to_node_id, edge_kind)`，同一对节点同类型只能一条；即使绕过它，`SemanticCompiler.ensureLeftJoin:751-754` 按 `target.id` 缓存别名，第二条边的连接键也会被**静默吞掉**（不报错、值取错）。AC-3 就是钉这一条的。

#### COST_BASIC（10 个数据源 → 11 条边）

| 锚点 node_key | 数据源显示名 | 锚点料号列 | 出边 |
|---|---|---|---|
| `MATERIAL_BOM` | 物料BOM | `component_no` | → `MAT_NAME_LK`(`PART_NAME`,1) + → `RECIPE_NAME_LK`(`PART_NAME`,2) |
| `MATERIAL` | 物料 | `production_no` | → `MAT_NAME_LK` |
| `ELEMENT_BOM` | 物料与元素BOM | `material_part_no` | → `RECIPE_NAME_LK` |
| `FINISHED_FIXED_FEE` | 成品其他固定费用 | `production_no` | → `MAT_NAME_LK` |
| `FINISHED_RATIO_FEE` | 成品其他比例费用 | `production_no` | → `MAT_NAME_LK` |
| `INCOMING_OTHER_FEE` | 来料其他费用 | `incoming_material_no` | → `MAT_NAME_LK` |
| `INCOMING_OTHER_FIXED_FEE` | 来料其他固定费用 | `incoming_material_no` | → `MAT_NAME_LK` |
| `INCOMING_PROCESS_FEE` | 来料加工费 | `incoming_material_no` | → `MAT_NAME_LK` |
| `OUTSOURCED_PROCESS` | 其他外加工成本 | `production_no` | → `MAT_NAME_LK` |
| `PROCESS_ASSEMBLY_FEE` | 加工费&组装费 | `production_no` | → `MAT_NAME_LK` |

#### COST_DETAIL（18 个数据源 → 19 条边）

| 锚点 node_key | 数据源显示名 | 锚点料号列 | 出边 |
|---|---|---|---|
| `MATERIAL_BOM` | 物料BOM | `component_no` | → `MAT_NAME_LK`(`PART_NAME`,1) + → `RECIPE_NAME_LK`(`PART_NAME`,2) |
| `MATERIAL` | 物料 | `production_no` | → `MAT_NAME_LK` |
| `ELEMENT_BOM` | 物料与元素BOM | `material_part_no` | → `RECIPE_NAME_LK` |
| `AUXILIARY_ENERGY` | 辅助设备能耗 | `production_no` | → `MAT_NAME_LK` |
| `CAPACITY` | 产能 | `production_no` | → `MAT_NAME_LK` |
| `CONSUMABLE` | 生产耗材BOM | `production_no` | → `MAT_NAME_LK` |
| `DEPRECIATION` | 设备折旧成本 | `production_no` | → `MAT_NAME_LK` |
| `FINISHED_FIXED_FEE` | 成品其他固定费用 | `production_no` | → `MAT_NAME_LK` |
| `FINISHED_RATIO_FEE` | 成品其他比例费用 | `production_no` | → `MAT_NAME_LK` |
| `INCOMING_OTHER_FEE` | 来料其他费用 | `incoming_material_no` | → `MAT_NAME_LK` |
| `INCOMING_OTHER_FIXED_FEE` | 来料其他固定费用 | `incoming_material_no` | → `MAT_NAME_LK` |
| `INCOMING_PROCESS_FEE` | 来料加工费 | `incoming_material_no` | → `MAT_NAME_LK` |
| `OUTSOURCED_PROCESS` | 其他外加工成本 | `production_no` | → `MAT_NAME_LK` |
| `PACKAGING` | 包装材料BOM | `production_no` | → `MAT_NAME_LK` |
| `PLATING_COST` | 电镀成本 | `production_no` | → `MAT_NAME_LK` |
| `PROCESS_ASSEMBLY_FEE` | 加工费&组装费 | `production_no` | → `MAT_NAME_LK` |
| `PRODUCTION_ENERGY` | 生产设备能耗 | `production_no` | → `MAT_NAME_LK` |
| `TOOLING` | 模具工装成本 | `production_no` | → `MAT_NAME_LK` |

> 🚫 **清单里没有 `PLATING_SCHEME`**，这不是遗漏：实测三方言下它都**没有 `semantic_tab_view` 行**，配置器里选不到（`D-3`）。
> 🚫 **清单里没有「零件」「外购件」**：`FieldTreeBuilder.RETIRED_TAB_TYPES` 已退役，且它们与「BOM」共用 `MATERIAL_BOM` 锚点 —— 边挂在**锚点节点**上，所以这三个 `tab_type` 天然共享同一批边，**不要重复建**。

### 建边前必须先自查的一件事

`semantic_edge` 的唯一约束是 `(from_node_id, to_node_id, edge_kind)`。写迁移前跑一次，**确认这 46 组 (from,to) 在库里都不存在**：

```sql
SELECT fn.node_key AS from_n, tn.node_key AS to_n, e.edge_kind, fn.dialect
  FROM semantic_edge e
  JOIN semantic_node fn ON fn.id = e.from_node_id
  JOIN semantic_node tn ON tn.id = e.to_node_id
 WHERE tn.node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK');
-- 期望：0 行。非 0 说明有并发会话先建了，停下报主线，不要 ON CONFLICT 吞掉。
```

---

## B-3 · 迁移落地纪律

**服务的 AC**：AC-9

1. **迁移号**：立项时实测源码树与共享库 `flyway_schema_history` 最大均为 `V429`，拟用 **`V430`**。
   🚨 **写文件前必须重新做四方核对**（`RECORD.md` 2026-09-07 两次迁移事故的直接产物）：
   ```
   共享库 flyway_schema_history  ×  master 的 db/migration  ×  当前分支的 db/migration  ×  target/classes/db/migration
   ```
   前三方判归属（只比两方时「我落后」和「真孤儿」长得一模一样），第四方判可运行性，且第四方要**双向**看（`target` 有而 `src` 无 = stale 残留，会让 8081 起不来）。

2. **🚨 迁移文件一落共享库，必须当场进 git。** 这两个动作之间不允许有间隙 —— 2026-09-07 一小时内这条规则以两种不同形态各落空一次（一次是 `target/` 残留、一次是写了没提交）。

3. **幂等**：所有 INSERT 用固定 UUID（在迁移里写死，不要 `gen_random_uuid()`）+ `ON CONFLICT DO NOTHING`。理由：本迁移可能在多个 worktree 的 `mvnw test` 中被重放。
   ⚠️ 但 **B-2 的自查 SQL 例外**：那一步要的就是「已存在就停下」，🚫 不许用 `ON CONFLICT` 把并发冲突吞掉。

4. **🚫 不许写任何 `DROP` / `DELETE` / `UPDATE` 既有行**。本次是纯新增。若你发现必须改既有行才能work，停下报主线。

---

## B-4 · 后端自检（交付前必须跑，把输出贴进回报）

**服务的 AC**：AC-1~AC-7, AC-9, AC-24

1. **迁移生效**：`select version, success from flyway_schema_history where version='430'` → `success = t`
2. **节点/边计数**：
   ```sql
   SELECT dialect, count(*) FROM semantic_node
    WHERE node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK') AND status='ACTIVE'
    GROUP BY 1;   -- 期望 QUOTE 3 · COST_BASIC 2 · COST_DETAIL 2
   ```
   ```sql
   SELECT fn.dialect, count(*) FROM semantic_edge e
     JOIN semantic_node fn ON fn.id=e.from_node_id
     JOIN semantic_node tn ON tn.id=e.to_node_id
    WHERE tn.node_key IN ('MAT_NAME_LK','MAT_PROD_LK','RECIPE_NAME_LK') AND e.status='ACTIVE'
    GROUP BY 1;   -- 期望 QUOTE 16 · COST_BASIC 11 · COST_DETAIL 19（合计 46）
   ```
3. **编译产物**：用 `POST /api/cpq/config/semantic-graph/compile`（或配置器界面）对 AC-1~AC-6 的每个场景各编一次，把生成的 SQL 片段贴进回报。**至少要能看出**：
   - AC-2：`COALESCE(` 两个不同别名
   - AC-3：`ds_quote_material` 出现 2 次、别名不同、`ON` 条件一个用 `input_material_no` 一个用 `material_no`
   - AC-5：`ds_quote_material` 只出现 1 次
   - AC-6：核价侧 `ON` 只有一个条件（无 `customer_no`）
4. **存量零变化（AC-9）**：迁移**前后**各采一次 `select id, md5(sql_template) from component_sql_view where sql_template is not null order by id`，`diff` 两份文件 → 必须**完全一致**。
   🚨 **先证明这个量具会动**：随便挑一个组件重新保存一次，确认它的 md5 确实变了，再拿这个 diff 当证据。否则你可能在比较两个都没采到数据的空文件（`RECORD.md` 2026-09-07 记录的假绿形态之一）。
5. **N+1**：本次不新增任何 Java 查询路径，无需 N+1 自检；但若你发现自己在写 Java，见本文件开头第一行。

---

## 两个已知的、不算缺陷的行为（写进回报，别当 bug 修）

1. **核价侧「材料名」的视图列名在不同数据源下不同名**：`AliasGenerator.viewColumn:30` 对核价两套返回**裸 `dbColumn`** ⇒ 走 `MAT_NAME_LK` 的数据源产出 `material_name`、走 `RECIPE_NAME_LK` 的（物料与元素BOM）产出 `symbol`。这是核价侧既有的命名规则，不是本次引入。

2. **核价「物料」数据源会触发列名去重**：锚点 `ds_cost_*_material` 自己就有 `material_name` 列，查名节点又产出一个 `material_name` ⇒ 撞名，由 `SemanticCompiler.dedupeAlias:1524` 加后缀消解。**这是预期行为**，回报里注明实际生成的列名即可。
