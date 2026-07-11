# 核价 SQL 配置手册(递归树 SQL + 页签 $view SQL)

> 面向:配置核价单渲染的人(配置员 / 实施 / 测试)。
> 版本:2026-07-03(与「全量递归 + 按料号分组」渲染架构对齐;真库示例已跑通)。
> 关联:`docs/superpowers/specs/2026-07-02-核价单全量递归按料号分组渲染-design.md`(架构规格)、`docs/核价树页签组件配置指南.md`(机制说明)。
> 本手册重「怎么写 SQL」;机制原理与三渲染面细节见上面两份。

---

## 0. 一分钟心智模型

核价单渲染 = **两类 SQL 各司其职**,后端把它们的结果**按料号拼起来**:

```
① 核价递归树 SQL(全系统唯一一条,可配置)
     输入:本报价单所有生产料号  →  输出:整棵 BOM 树(每节点一行,5 列)
     用途:产「树结构」+ 算出两个料号变量
                │
                ├─ card_material_no  = 每张卡片(生产料号)树下的所有料号(含根)
                └─ total_material_no = 所有卡片料号的并集
                │
② 页签 $view SQL(每个核价组件一条)
     输入:total_material_no  →  输出:业务数据行(必含 material_no 列)
     用途:产「业务数据」,后端按 material_no 分配到各卡片
```

- **执行次数**:递归树 SQL 跑 **1 次**(全量),每个页签 $view 跑 **1 次**,其余全是后端内存分组。
- **匹配**:页签结果行的 `material_no` 落在哪张卡片的 `card_material_no` 里,就进哪张卡片(一行可进多卡;落不进任何卡 → 丢弃)。

---

## 第一部分 · 核价递归树 SQL

### 1.1 在哪配

**组件管理 → [核价树配置] 页签** → 新增一条 → 粘 SQL → 保存(后端 dry-run 校验)→ **设为生效**(全局唯一,同一时刻只有一条生效)。

### 1.2 契约(逐字,必须遵守)

| 方向 | 约定 |
|---|---|
| **输入变量** | `:production_part_nos`(`text[]`,后端注入=本报价单所有生产料号)。必须在 SQL 里引用(dry-run 会检查)。 |
| **输出列(5 列,列名逐字)** | `root_no`(根生产料号,分组用)/ `material_no`(当前料号,显示)/ `bom_version`(当前料号自身版本,显示,叶子可空)/ `parent_no`(上级料号)/ `node_path`(节点物化路径,建树结构的真理源) |

> 输出列名**少一列或拼错**→ 保存被 dry-run 拦(返 500 带「缺输出列: xxx」)。

### 1.3 硬规则

1. **只认核价分区**:BOM 父子数据必须 `system_type='PRICING'` + `customer_no='_GLOBAL_'` + `is_current=true`,与全局口径对齐;不满足则爬不出子料号(只出根一行)。
2. **展开方向**:`material_bom_item` 里 `material_no`=父、`component_no`=子;递归 `JOIN ... ON child.material_no = parent.material_no`,当前节点取 `component_no`。
3. **`node_path` 必须每节点唯一**:根=单段(料号本身),子=父路径 `|| '/' ||` 子料号。同一料号挂多父 → 不同路径 → 不同节点(多 occurrence 保留),其各自子件按各自路径**精确归位不挂错枝**。
4. **段内不含分隔符**:后端按最后一个 `/` 切父。料号本身**不能含 `/`**(本系统料号是数字,安全);若将来料号含 `/`,把路径段换成边代理键(如 `ch.id`)。
5. **全列 `::text`**:含 `CYCLE` 子句时,循环列(`material_no`)在 seed(来自 `unnest(text[])` = `text`)与递归分支(`component_no` = `varchar`)类型必须一致,否则报 `cannot compare dissimilar column types`。**统一给每列加 `::text`** 最省事(后端本就按 text 读)。
6. **不要写结尾分号 `;`**:后端会把你的 SQL 包成 `SELECT * FROM (<你的SQL>) q ...`,尾分号会造成语法错(→ 500)。
7. **成环保护**:用 `CYCLE material_no SET is_cyc USING cyc_path` 切断真环(A→…→A);它**不影响**多父多 occurrence(不同路径不算环)。

### 1.4 完整示例(真库跑通,直接可用)

```sql
WITH RECURSIVE bom AS (
  -- 种子:每个生产料号 = 根节点(node_path 单段)
  SELECT
    p::text                                        AS root_no,
    p::text                                        AS material_no,
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = p
        AND bv.customer_no = '_GLOBAL_'
        AND bv.system_type = 'PRICING'
        AND bv.is_current  = true
      LIMIT 1)                                     AS bom_version,   -- 根自身当前 BOM 版本
    NULL::text                                     AS parent_no,     -- 根无上级
    p::text                                        AS node_path      -- 根 = 单段
  FROM unnest(:production_part_nos) AS p

  UNION ALL

  -- 递归:material_no(父) → component_no(子)
  SELECT
    b.root_no,
    ch.component_no::text                          AS material_no,   -- 当前料号 = 子件
    (SELECT bv.bom_version::text
       FROM material_bom_item bv
      WHERE bv.material_no = ch.component_no
        AND bv.customer_no = '_GLOBAL_'
        AND bv.system_type = 'PRICING'
        AND bv.is_current  = true
      LIMIT 1)                                     AS bom_version,   -- 子件自身当前 BOM 版本(叶子→NULL)
    ch.material_no::text                           AS parent_no,     -- 上级料号 = 父件
    (b.node_path || '/' || ch.component_no)::text  AS node_path      -- 物化路径累加(每 occurrence 唯一)
  FROM material_bom_item ch
  JOIN bom b ON ch.material_no = b.material_no
  WHERE ch.customer_no  = '_GLOBAL_'
    AND ch.system_type  = 'PRICING'
    AND ch.is_current   = true
    AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path
FROM bom
```

**这条 SQL 对根料号 `4141111115` 跑出的真实结果(节选)**:

| lvl | 当前料号 | 版本 | 上级 | node_path |
|---|---|---|---|---|
| 1 | 4141111115 | 2000 | | `4141111115` |
| 2 | 2120011658 | 2000 | 4141111115 | `4141111115/2120011658` |
| 3 | 3110520789 | 2000 | 2120011658 | `4141111115/2120011658/3110520789` |
| 4 | 2101110225 | | 3110520789 | `4141111115/2120011658/3110520789/2101110225` |
| 2 | 2120011659 | 2000 | 4141111115 | `4141111115/2120011659` |
| 3 | 3110520789 | 2000 | 2120011659 | `4141111115/2120011659/3110520789` |
| 4 | 2101110225 | | 3110520789 | `4141111115/2120011659/3110520789/2101110225` |

> 注意 `3110520789` 与其子件 `2101110225` **各出现两次**(挂在 `2120011658` 和 `2120011659` 下),`node_path` 不同 → 后端建成两个独立分支、不挂错枝。这就是 `node_path` 存在的意义。

### 1.5 后端如何用这 5 列(理解即可,不用你操心)

- `card_material_no[卡片=root_no]` = 该 root 下 `distinct(material_no)`(**含根**)。
- `total_material_no` = 所有 `material_no` 去重(= 各卡集合并集,注入给页签 SQL)。
- 树结构:`__nodeId = node_path`、`__parentId = node_path 去最后一段`、`__lvl = 段数`(根 parentId=null、lvl=1)。
- `parent_no` 仅作「上级料号」显示/信息;结构由 `node_path` 决定。

---

## 第二部分 · 核价侧页签 $view SQL

> **树页签和普通页签的 $view 契约完全相同**;区别只在于:树页签(组件勾了「核价树」)额外由递归树 SQL 提供树骨架三列,业务数据按 material_no 挂到节点;普通页签平铺。所以下面规则对两类都适用。

### 2.1 在哪配

组件管理 → 目标核价组件 → SQL 视图列表新增一条 `$view` → 工具栏「设为驱动」→ 组件 `data_driver_path = $视图名` → 字段(fields)`basic_data_path = $视图名.列名`。

### 2.2 契约(逐字)

| 方向 | 约定 |
|---|---|
| **输入变量** | `:total_material_no`(`text[]`,后端注入=本单全部树料号)。在 `WHERE` 里写 `料号列 = ANY(:total_material_no)` 只查这批料号。 |
| **输出列(普通页签)** | **必须含 `material_no` 列**(逐字);其余为业务列。按 `material_no` 匹配到卡片。 |
| **输出列(树页签,`bom_recursive_expand=true`)** | **必须同时含 `parent_no` + `material_no` 两列**(边键,逐字)——`parent_no`=父件料号、`material_no`=子件料号(当前节点)。按 **(parent_no, material_no) 边键**匹配到树节点(2026-07-03 起)。对 `material_bom_item`:`parent_no = material_no(父)`、`material_no = component_no(子)`。 |

> **为什么树页签要 `parent_no`**:BOM 树每个节点的数据是「父→子」边特有的(如用量随父件不同);仅按 `material_no` 会把同一子件在**别父件下**的边也挂进本节点 → 重复/挂错父。加 `parent_no` 后按边键精确挂。

### 2.3 硬规则

1. **必出 `material_no`(树页签还须出 `parent_no`)**:后端靠它把行分配到卡片/节点(普通页签 `material_no ∈ card_material_no`;树页签 `(parent_no,material_no)` 边键)。**忘了输出 → 该页签所有行静默落选、整表空**(后端打 WARN;树页签缺 `parent_no` 会退化为只命中根层空父 → 全落空)。**若渲染失败(递归 SQL/视图报错),核价卡片现在会显式红字提示错误原文(BL-0030),不再只是空白/加载中。**
2. **用 `= ANY(:total_material_no)` 收窄**:一次查回全单所需料号的数据,后端再分组;不要按单料号/lineItem 过滤(后端不注入客户/行上下文,见规则 4)。
3. **分区条件自己写死**:后端**只注入料号数组变量**,不注入 `customer_id` / `lineItemId` / `quotationId`。按客户/版本/现价的过滤(如 `system_type='PRICING'`、`customer_no='_GLOBAL_'`、`is_current=true`)必须在 SQL 里自己写。
4. **列名 ↔ 字段路径逐字一致**:字段 `basic_data_path = $视图名.列名`,`列名` 必须与 SELECT 输出列名完全相同,否则该格 `#ERROR` / 空。
5. **改视图/字段后要重算报价单**:快照是算那一刻烘进去的,不追溯旧单。

### 2.4 示例:元素成分页签(料号维度取数)

需求:每个料号显示它的元素构成(Ag/Ni/Cu…)+ 含量。数据在 `element_bom_item`(一个料号一组元素)。

```sql
SELECT
  ebi.material_no                 AS material_no,   -- ★ 必出,与卡片匹配
  ebi.component_no                AS 元素,           -- 业务列
  ebi.content                     AS 含量,
  ebi.composition_qty             AS 用量
FROM element_bom_item ebi
WHERE ebi.system_type = 'PRICING'
  AND ebi.customer_no = '_GLOBAL_'
  AND ebi.is_current  = true
  AND ebi.material_no = ANY(:total_material_no)      -- ★ 只查本单树料号
```

- 后端把每行按 `material_no` 挂到对应卡片(该料号的所有元素行都进);同一料号的多行元素平铺显示。
- 若是**树页签**:同样这条 SQL,后端把 `material_no` 命中的元素行挂到该料号在树里的每个节点(多 occurrence 各挂一份)。

### 2.5 示例:带主档信息(JOIN material_master)

```sql
SELECT
  ebi.material_no                 AS material_no,
  mm.material_name                AS 物料名称,
  mm.unit_weight                  AS 单重,
  ebi.component_no                AS 元素,
  ebi.content                     AS 含量
FROM element_bom_item ebi
LEFT JOIN material_master mm ON mm.material_no = ebi.material_no
WHERE ebi.system_type = 'PRICING'
  AND ebi.customer_no = '_GLOBAL_'
  AND ebi.is_current  = true
  AND ebi.material_no = ANY(:total_material_no)
```

### 2.6 字段(fields)配置对齐

上面视图假设叫 `$element_view`,则组件字段这样配:

| 字段名(显示) | field_type | basic_data_path |
|---|---|---|
| 元素 | BASIC_DATA | `$element_view.元素` |
| 含量 | BASIC_DATA | `$element_view.含量` |
| 物料名称 | BASIC_DATA | `$element_view.物料名称` |

> `material_no` 是**匹配用**的,可以不作为显示字段(树页签的「料号」列由递归树的系统列固定带出;普通页签若要显示料号,加一个 `basic_data_path=$element_view.material_no` 的字段即可)。

---

## 第三部分 · 端到端配置流程(6 步)

1. **备核价 BOM 数据**:确认 `material_bom_item` 有 `PRICING/_GLOBAL_/is_current` 的父子行(否则树只出根一行)。自检:
   ```sql
   SELECT count(*) FROM material_bom_item
   WHERE material_no='<根料号>' AND system_type='PRICING'
     AND customer_no='_GLOBAL_' AND is_current AND component_no IS NOT NULL;  -- >0 才有树
   ```
2. **配递归树 SQL**:[核价树配置] 新增(用第 1.4 示例)→ 保存 → **设为生效**。
3. **标树页签**:目标核价组件勾「核价树 / BOM 递归展开」(一个核价模板最多一个树页签)。
4. **配页签 $view**:每个核价组件配 `$view`(用第 2.4/2.5 示例),`= ANY(:total_material_no)` + 必出 `material_no`;设为驱动。
5. **对齐字段**:字段 `basic_data_path = $视图名.列名`,列名逐字对齐视图输出。
6. **重算报价单**:重开该核价单 / 走刷新,让快照按新配置重建。

---

## 第四部分 · 验证方法

### DB 级(不用登录,验树 + 分组)

```sql
-- 直接对生效递归 SQL 跑一个根料号,看能否出多层树(把 :production_part_nos 换成 ARRAY['根料号']::text[])
-- (整段就是第 1.4 的 SQL,seed 换成你的根料号)

-- 看某报价单某卡片某页签快照里的树骨架 + 业务数据
WITH tab AS (
  SELECT c FROM quotation_line_item li, jsonb_array_elements(li.costing_card_values->'tabs') c
  WHERE li.quotation_id='<报价单id>' AND li.product_part_no_snapshot='<根料号>'
    AND c->>'tabName'='<页签名>' LIMIT 1)
SELECT (r->>'__lvl')::int lvl,
       repeat('  ',(r->>'__lvl')::int-1)||(r->>'__hfPartNo') 料号,
       r->>'__parentNo' 父, r->>'__bomVersion' 版本, r->'driverRow' 业务数据
FROM tab, jsonb_array_elements(tab.c->'baseRows') r ORDER BY r->>'__nodeId';
```

### UI 级

设为生效 + 勾树页签 + 配好 $view → **重算该单** → 强刷 → 核价单 → 产品卡片视图 / Excel 视图:应见「料号 / 版本」两列 + 多层树,业务列有值。

---

## 第五部分 · 坑速查 + 保存前 checklist

### 坑速查

| 症状 | 根因 | 修 |
|---|---|---|
| 保存返 **500** | SQL 结尾有 `;` / 缺 5 列之一 / 未引用 `:production_part_nos` | 去分号;补全 5 列;确认引用变量 |
| 保存报 `cannot compare dissimilar column types` | 递归 CYCLE 列类型不一致(text vs varchar) | 每列加 `::text` |
| 树只出根一行 | BOM 数据不在 `PRICING/_GLOBAL_/is_current` | 按核价分区导入/确认 BOM |
| 某页签整表空 | $view 忘输出 `material_no`(静默落选) | 补 `material_no` 输出列;查后端 WARN 日志 |
| 某格 `#ERROR`/空 | 字段 `basic_data_path` 列名与视图输出不一致 | 列名逐字对齐 |
| 树挂错枝 / 层级乱 | `node_path` 未按祖先路径唯一累加 | 按 1.3 规则 3 写 `node_path` |
| 改了配置没生效 | 没重算报价单 / 没设为生效 / 浏览器缓存 | 设为生效 + 重算 + 强刷 |
| 打开核价单空(存量单) | 硬切换后未配置生效递归 SQL | 配好并设为生效,重开懒算重建 |

### 保存递归树 SQL 前 checklist

- [ ] 引用了 `:production_part_nos`
- [ ] 输出恰好 5 列 `root_no/material_no/bom_version/parent_no/node_path`(列名逐字)
- [ ] 每列 `::text`(CYCLE 类型一致)
- [ ] `node_path` 根单段、子=父路径 `||'/'||` 子料号(唯一)
- [ ] 分区 `PRICING/_GLOBAL_/is_current`
- [ ] 有 `CYCLE ... ` 防环
- [ ] **结尾无 `;`**

### 保存页签 $view 前 checklist

- [ ] 输出含 `material_no` 列
- [ ] `WHERE 料号列 = ANY(:total_material_no)`
- [ ] 分区/客户/现价条件 SQL 自己写死(后端不注入上下文)
- [ ] 字段 `basic_data_path` 列名 ↔ 视图输出列名逐字一致
- [ ] 结尾无 `;`
