# 3 · SQL 视图（`$view`）

> **来源**：`报价模板生成规则.md §2`（C4 dry-run）/`§3`（default_source）/`§4`（组件 SQL 视图编写规则 要点 1~7，含 §4.6 pending 改写坑 + §4.7 sort_field 排序坑）+ `核价SQL配置手册.md`（第二部·页签 `$view` 契约共性）+ `反模式.md AP-53`（禁表）+ 记忆 `cpq-chinese-identifiers-need-ascii-alias` / `cpq-sqlview-cache-key-needs-component-dim`。
> **报价/核价两侧的具体契约（客户口径 / 料号键 / 版本 / 树）分别见 `报价侧.md`、`核价侧.md`；行排序 `sort_field` 见 `4-页签属性与树.md §4.2`；V6 现役表映射速查见 `附录-速查.md §C`。**

组件 SQL 视图（`$view`）是每个多行组件的**取数源 + 行驱动**。本文写「怎么写视图」的共性规则与坑；两侧数据源到底 `FROM` 哪张表见各自 delta 文件。

---

## 3.1 `$view` 机制

- **载体 `component_sql_view`**：每个组件配一条（可多条），存 `sql_template`（SQL 文本）+ `sqlViewName`（小写字母/下划线/数字，同组件内唯一）。用 `$视图名` 引用；`scope` 默认 `COMPONENT`（跨组件引用才用 `GLOBAL` + `$$code.name`）。
- **inline 子查询，非物理视图**：运行时框架把 `sql_template` **拼成子查询执行**，**不产生 DB 视图对象**。**禁在数据库建物理视图**（无 `CREATE VIEW`/DDL），一律用组件 SQL 视图功能（R2）。也**不依赖**现网 `v_q_*_merged` 等物理视图（那是 DB 建的，违反 R2；R3）。
- **绑定两处**：组件 `data_driver_path = $view`（多行组件的行驱动，`PUT /components/{cid}/driver-view`）；字段绑值——报价侧 `default_source.path = $view._列`（type `BASIC_DATA`），核价侧 `basic_data_path = $view.列`。
- **保存即 dry-run**（create/update 同步校验，`SqlViewValidator.validate`，失败 400）：
  - 必须 `SELECT`/`WITH` 开头；禁 DDL/DML（`INSERT/UPDATE/DELETE/CREATE/DROP/ALTER/TRUNCATE/DO`…）；
  - **禁 V44/V76 废弃表**（§3.3）；
  - 禁 `:hfPartNo` 标量（用 `:hfPartNos` 数组）；`:__sk*`/`:__vf*` 前缀为框架保留。
- **dry-run 是 EXPLAIN，无 pending / 无运行时变量上下文** → 只查语法与表名，**查不出 pending 改写坑（§3.6）、变量未绑定、运行期返 0 行**。这些只在真实渲染暴露。
- **REST 端点**：`POST /components/{cid}/sql-views`（建 + dry-run）→ `PUT /components/{cid}/driver-view {sqlViewName}`（挂 `data_driver_path`）；改视图 `PUT /components/{cid}/sql-views/{viewId}`。

---

## 3.2 `hf_part_no` 驱动键 + 隐式 JOIN

- **视图必出 `hf_part_no`**，且它是**唯一不带 `_` 前缀的别名**（R4-例外）。框架对报价行做**隐式 JOIN 注入 `hf_part_no = <本报价行料号>`**，把视图收窄到本行数据。
- **报价侧**：料号 = 销售料号，把 `material_no`（或费用类 `finished_material_no`）别名成 `hf_part_no`（详见 `报价侧.md`）。**核价侧不用 hf_part_no**——核价按 `:total_material_no` 数组取数、输出 `material_no`（+ 树页签 `parent_no`）挂卡片/节点（详见 `核价侧.md` 与 `4-页签属性与树.md §4.3`）。
- **改名即返全表**：把驱动键写成 `_hf_part_no` 等会让隐式 JOIN 失去锚点 → 视图返全表 N 行 → UI 出「首值（共N项）」错乱。
- **业务列一律 `_` 前缀**（`_销售料号`/`_材质`…），避免业务列被隐式 JOIN 误当谓词注入；字段路径对应 `$view._列`。

报价侧视图骨架（`_` 前缀 + V6 表 + 三段过滤）：

```sql
SELECT
  <料号列>          AS hf_part_no,     -- 驱动键：销售料号别名成 hf_part_no（R4-例外，不带 _）
  <料号列>          AS _销售料号,       -- 业务列一律 _ 前缀（R4）
  <列>              AS _材质,
  ...
FROM <V6 基础表>                       -- R3：必须 V6 表
  LEFT JOIN <V6 表> ...
WHERE <表>.system_type = 'QUOTE'        -- 报价侧固定 QUOTE
  AND <表>.is_current                   -- 取当前版本
  AND <表>.customer_no = :customerCode  -- 按客户收窄（运行时注入）
ORDER BY ...
```

- `:customerCode` = 报价单客户的 `customer_no`，框架运行时注入；dry-run 时按 NULL 兜底（返 0 行不报错）。
- **一对多会让驱动行翻倍**时，用**相关子查询 `string_agg`** 把多值聚合成单值，别在主 `FROM` 直接一对多 JOIN。

---

## 3.3 禁表黑名单（dry-run 拒）

`SqlViewValidator.FORBIDDEN_TABLE_TOKENS`（AP-53）：

- **V44 废弃**：`mat_part` / `mat_bom` / `mat_process` / `mat_fee` / `plating_plan` / `mat_customer_part_mapping` / `element_price` / `element_daily_price` / `customer_tax`
- **V76 废弃**：`costing_part_*`
- **必须 `FROM` V6 基础表**：`material_master` / `material_customer_map` / `material_bom_item` / `element_bom_item` / `material_recipe` / `unit_price` / `process_master` / `capacity` / `plating_fee` / `plating_scheme` 等（列语义速查见 `附录-速查.md §C`）。
- 组件 `data_driver_path` / 字段 `basic_data_path` **禁直接写 PG 视图名 / 物理表名**（`v_q_*_merged` 等 DB 建视图违反 R2），必须用 `$<sql_view_name>` 引用；视图 `sql_template` 内部才 `FROM` V6 表。

---

## 3.4 中文标识符需 ASCII 别名

- 公式 / `$view` 路径里的**中文列名 / 字段名不能直接当 SQL 标识符** → 必须**别名或 Java 侧预解析**（记忆 `cpq-chinese-identifiers-need-ascii-alias`）。
- 报价侧业务列一律 `_中文` 别名（`_` 前缀），字段 `default_source.path = $view._列` **逐字对齐**（含全半角 / 空格），否则运行期取不到默认值（`default_source` 后端不强校验，仅软告警列名，写错不报错）。
- 核价侧输出列名 ↔ 字段 `basic_data_path = $view.列名` **逐字一致**，否则该格 `#ERROR` / 空。

---

## 3.5 缓存 key 含 `componentId`（+ 多维度）

- **同名视图跨组件串号**：导入副本（`conflictPolicy=RENAME` 仅改组件 code）后多个组件可能仍持有同 `sqlViewName` → `DataLoader.resultCache` 等**按 `$view` 的缓存 key 必须含 `componentId`**（记忆 `cpq-sqlview-cache-key-needs-component-dim`）。
- **driver expansion 的 fingerprint / cache key 还须含更多维度**：
  - `dataDriverPath` + `fieldsHash`：同一 `componentId` 在模板里**多次出现**（同组件多实例、字段 override 不同）时，cache key 必须含 `dataDriverPath + fieldsHash`，否则实例间串号（AP-37）。
  - `total_material_no`：核价树按料号数组取数，缓存 key 须含该维度，否则跨料号串号。
- 漏一维 → pre-enrich 缓存串号 / 缓存到 `EMPTY_EXPANSION` 遮蔽真实数据（AP-31/AP-37 「加载中…」永久占位族）。

---

## 3.6 🚨 pending 改写坑（版本化表 `LEFT JOIN` 禁含 `is_current`，改标量子查询）

（2026-07-22 罗克韦尔来料树事故）

- **背景**：DRAFT 报价单渲染时，框架 `QuotePendingRewriter` 会把版本化白名单表（`unit_price` / `material_bom_item` / `material_master` / `capacity` 等有 `pending_quotation_id` 列的表）**整表 token 替换成带 `:pq` 的子查询**，让 pending（待生效）行也被看到。
- ⏱ **生效时间线（重要，2026-07-25 task-0725）**：上述机制**在 task-0725 之前，对报价侧的驱动展开链路是失效的** —— `ComponentDriverService` / `ConfigureSnapshotService` 都没往 `SqlViewRuntimeContext` 写 `quotationId`（driver 一进来就被覆盖成 null），`SqlViewExecutor:555` 的改写门槛**恒关**，四个页签因此返 0 行、报价树只渲光根。task-0725 用 `QuotePendingScope` 窄作用域接通后**才真正全链路生效**。
  - **对配置者的含义**：① DRAFT 报价单现在**能看到本单导入的待生效（pending）数据**，这是预期行为，不是脏数据；② 以前"碰巧没暴露"的视图写法，现在会真正进入 pending 改写路径，本节及 §3.7 的坑要按新前提重新审视；③ 冻结态（`SUBMITTED`/`APPROVED`/`PUBLISHED`）**不开域**，此时数据已转正为 `is_current=true`，普通过滤即可见。
  - **核价侧不受影响**：核价链路结构性地不调用 `QuotePendingScope.open()`，`cacheTag()` 关闭态返空串，核价缓存 key 逐字不变。
- **坑**：当版本化表写成 `LEFT JOIN unit_price up ON ... AND up.is_current=true`（`is_current` 在 **JOIN ON 复合条件**里）时，pending 改写会产出**参数错位的畸形 SQL**，运行期报 `The column index is out of range: N, number of columns: M`（driver 执行失败，静默返 0 行 / 节点数据全空）。dry-run（EXPLAIN，无 pending 上下文）**照样通过**，只在真实渲染暴露。
- **规避**：把该 `LEFT JOIN <版本化表>` 改成**相关标量子查询**取值——子查询 WHERE 里带 `is_current` 是安全的（同 `NOT EXISTS` 子查询）：

```sql
-- ❌ 触发 pending 改写 bug：
LEFT JOIN unit_price up ON up.finished_material_no=x.material_no AND up.code=x.component_no
  AND up.price_type='INCOMING_MATERIAL_PROCESS' AND up.is_current=true
-- ✅ 改标量子查询：
(SELECT up.base_value FROM unit_price up
  WHERE up.finished_material_no=x.material_no AND up.code=x.component_no
    AND up.price_type='INCOMING_MATERIAL_PROCESS' AND up.is_current LIMIT 1) AS _加工费值
```

- 普通位置的 `is_current`（主 `WHERE` / 子查询 `WHERE` / `NOT EXISTS`）**不受影响**；**只有版本化表的 `LEFT JOIN ON` 内含 `is_current`** 才中招。

### 附带坑：视图 `ORDER BY` 在 DRAFT 下失效 → 用组件 `sort_field`

（2026-07-22 task-0722）

- DRAFT 渲染时 `QuotePendingRewriter` 把视图包成带锚点列的复杂子查询、执行器再套 `SELECT * FROM (...) inner_q`（**无外层 ORDER BY**）→ 视图里写的 `ORDER BY` PostgreSQL **不保证透传**，多行页签会按 driver 返回序（常见倒序）显示。视图 `ORDER BY` **只对已提交 / 冻结**报价单有效。
- **正解**：给组件设 `sort_field`（= 本组件 `fields[].name` 之一，如「项次」「序号」），快照组装层（`ConfigureSnapshotService`）按**数字感知升序**排（数字段数字序、文本段字典序），DRAFT + 冻结都稳；树页签（`tabType=BOM`）按树序不受其约束。**配法与已配清单见 `4-页签属性与树.md §4.2`。**

---

## 3.7 ✅ SQL 注释里可以写 `:命名参数`（框架已屏蔽，2026-07-25 起）

**结论先行：在视图 SQL 与树递归 SQL 的注释里书写 `:customerCode` / `:total_material_no` / `:production_part_nos` / `:pq` 等命名参数做文档说明，是安全的正当写法，无需规避。**

- **曾经的坑**（task-0725 根因 2）：占位符替换用正则扫 `:name`，**不识别 `--` 行注释 / `/* */` 块注释 / 字符串字面量** → 注释里的命名参数也被替换成 `?` 并追加一个绑定值，而 pgjdbc 解析时会忽略注释内的 `?` ⇒ **Java 侧绑定数 > pgjdbc 认到的占位符数** ⇒ 运行期报 `The column index is out of range: N, number of columns: M`，driver 静默返 0 行。实测 `bom_view`：Java 绑 3 个（`total_material_no` ×3，其中 **1 个在注释里**）/ pgjdbc 认 2 个 → 必失败。
- **已修**：抽出 `SqlTextMask.mask()`（把字面量/行注释/块注释屏蔽为**等长空白**、保留换行，故偏移量对齐、替换仍作用于原文），并在**全部 4 条链路**接入：

  | 链路 | 站点 | 作用 |
  |---|---|---|
  | 视图执行 | `SqlViewExecutor.rewriteNamedParams` | 运行期占位符替换 |
  | 视图保存校验 | `SqlViewValidator` | dry-run 与 `required_variables` 采集 |
  | pending 改写 | `QuotePendingRewriter` | 白名单表 token 定位 |
  | 树递归 SQL | `BomTreeRenderService`（`TREE_PARAM`） | `:production_part_nos`/`:pq`/`:__vfPart`/`:__vfVer` 绑定 |

- ⚠️ **已作废的旧约束**：`docs/RECORD.md` 2026-07-25「树递归SQL配置」条目里的「注释里**禁写** `:production_part_nos/:pq/:__vfPart/:__vfVer`」**不再适用**（该条已就地划删除线）。同理，历史上因此症状被"规避"掉的写法，可以按可读性自由回改。
- **仍然成立的**：`::` 强制类型转换不受影响（正则本就排除）；注释**不能**用来"注释掉"一段含真实占位符的 SQL 后指望参数计数自动对上——屏蔽的语义是「注释内的 token 不参与绑定」，这正是所需行为。

---

## 3.8 🌳 BOM 闭包取数：让**子件**的数据进入平铺页签（task-0726）

### 3.8.1 什么时候需要

平铺页签默认只取「**直接挂在本行产品料号下**」的数据——因为框架对报价行隐式注入 `hf_part_no = <本行产品料号>`。于是**子件的材质/外购件/加工费全部取不到**。

> 真实事故（2026-07-26，罗克韦尔 `QT-20260726-0008`）：BOM 树能看到 `S-3120014539 → S-80011 → 00002(AgC3)`，但材料成本页签只有主件的 `00137`/`992` 两行，子件材质 `00002` **完全不出现**。业务口径要求「子件的材质/外购件/零件都要进对应页签」。

**判据**：该页签展示的是「整个产品结构的成本构成」→ 需要闭包；该页签展示的是「本产品自身属性」（如"产品"页签）→ **不要**闭包，否则会串入子件信息。

### 3.8.2 标准写法（可直接复制）

在视图前面加一段闭包 CTE，`hf_part_no` 改输出**闭包根料号**，让框架的外层注入去筛：

```sql
WITH RECURSIVE bom_closure AS (
  SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
  FROM material_bom_item b
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
  UNION                                     -- ⚠️ UNION 不是 UNION ALL（铁律3）
  SELECT c.root_no, b.component_no, c.lvl + 1
  FROM bom_closure c
    JOIN material_bom_item b ON b.material_no = c.node_no
  WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
    AND c.lvl < 10                          -- 深度上限，防环
), bom_closure_d AS (                       -- ⚠️ (root,node) 唯一化（铁律3）
  SELECT root_no, node_no, MIN(lvl) AS lvl FROM bom_closure GROUP BY root_no, node_no
)
SELECT
  COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,   -- 驱动键 = 闭包根料号（兜底见铁律2）
  ebi.material_part_no AS _料号,
  ...
  ebi.material_no      AS _归属料号,                      -- 强烈建议：见 §3.8.4
FROM element_bom_item ebi                                 -- ★ 白名单表必须在顶层 FROM（铁律1）
  LEFT JOIN bom_closure_d cl ON cl.node_no = ebi.material_no
  LEFT JOIN ...
WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.seq_no
```

换页签只需替换顶层表与 JOIN 键：

| 页签类型 | 顶层白名单表 | 闭包 JOIN 键 |
|---|---|---|
| 材质元素（材料成本） | `element_bom_item ebi` | `cl.node_no = ebi.material_no` |
| 外购件 | `material_bom_item mbi` | `cl.node_no = mbi.material_no` |
| 零件（加工费） | `unit_price up` | `cl.node_no = up.finished_material_no` |

### 3.8.3 三条铁律（漏一条必出事）

**🔒 铁律 1：白名单表必须出现在顶层 `FROM`，不能写成 `FROM bom_closure JOIN <白名单表>`**

顶层 `FROM bom_closure` 是 CTE、不在白名单 → `QuotePendingRewriter` 的主位表探测退化为「任意深度第一个 FROM」→ 选中**递归 CTE base case 里**的表 → `__v6_id` 锚点插进 base case → UNION 两侧列数不等：

```
anchorInjected=true  primaryTable=material_bom_item   ← 选错了
ERROR: each UNION query must have the same number of columns
```

写对之后：`primaryTable=element_bom_item`，锚点落在顶层 SELECT，正常。**与 task-0725「递归 CTE 锚点注入」是同源问题。**

**🔒 铁律 2：必须 `LEFT JOIN` + `COALESCE(cl.root_no, <本表料号>)` 兜底**

`bom_closure` 的 base case 来自 `material_bom_item`，所以**没有任何 BOM 行的单料产品根本不在闭包里**。若写成 `JOIN`（内连接）或直接取 `cl.root_no`，这类产品的页签会**整个变空白**——一个比原问题更严重的回归。

**🔒 铁律 3：闭包必须对 `(root_no, node_no)` 去重 —— 递归用 `UNION`（非 `UNION ALL`）+ 再加一层 `bom_closure_d` 聚合**（2026-07-26 实测补，原 §3.8.2 写法有此缺陷）

`material_bom_item` 里**同一对父子经常并存多条边**（现网实证：`0363-2607000009 → 0363-2607000007` 同时有 `characteristic='RECIPE'` 和 `'ASSEMBLY'` 各一行）。闭包按边递归 ⇒ 同一 `(root,node)` 产生 N 条路径 ⇒ `LEFT JOIN bom_closure` 把数据行**成倍复制**：

```
-- 未去重（旧写法）：材料成本 root=0363-2607000009 → 8 行（每行重复 2 次）
-- 去重后         ：4 行 ✅（Ag/Cu 来自 …07，Ag/Ni 来自 …08）
```

危害不止"多几行"：**页签小计直接翻倍**（成本算错，静默无报错）；深 BOM 里重复边逐层相乘还会让闭包行数指数膨胀。
- 递归段用 `UNION` 消掉同层重复路径（顺带防环）；
- 再加 `bom_closure_d AS (SELECT root_no,node_no,MIN(lvl) lvl FROM bom_closure GROUP BY root_no,node_no)`，消掉**跨层**多路径；数据页签一律 `LEFT JOIN bom_closure_d`。
- 加第二个 CTE **不破铁律 1**：顶层 `FROM` 仍是白名单表，实测框架照常把谓词包成 `... ) inner_q WHERE inner_q.hf_part_no = ANY(?)`，锚点注入正常。
- ⚠️ **`costing_bom_tree_config` 的树递归 SQL 有同源风险**（重复边 → 树里同一子件出现两次），另行核。

### 3.8.4 建议输出 `_归属料号`

闭包会把不同来源的行拉到同一页签，**同一料号可能出现多行**。实测案例：

| 料号 | 名称 | 组成数量 | 归属料号 |
|---|---|---|---|
| W-1001 | 组成件1 | 2 | S-3120014539（主件） |
| W-1001 | 组成件1 | 3 | **S-80011（子件）** |

两行的料号与名称**完全相同**，只有数量和归属不同。不输出归属料号的话用户无法分辨，会当成脏数据。

⚠️ 若把归属料号做成可见字段，**必须同步把它加进组件的 `rowKeyFields`**，否则行键撞键 → 编辑串行、末值塌缩（见 `2-组件与字段.md` 行键唯一性规则）。

### 3.8.4b 🔴 铁律 4：接价格策略时，JOIN 键必须与 `hf_part_no` 输出表达式**逐字一致**（task-0729）

> 2026-07-29 立。**只在"取价函数带料号维度"之后才适用** —— 即 task-0729 的 `f_material_element_price` 落地后。
> 现行 `f_customer_element_price(:customerCode, :priceBaseDate)` 无料号维度，JOIN 只按元素符号，不受本条约束。

新取价函数按 `(客户, 料号)` 查版本指针，所以 JOIN 要带料号条件。**照直觉写 `AND cep.material_no = ebi.material_no` 在闭包形态下是错的**：

| 视图形态 | `hf_part_no` 输出表达式 | `ebi.material_no` 的语义 |
|---|---|---|
| **平铺** | `ebi.material_no AS hf_part_no` | 本行料号 ✅ |
| **BOM 闭包**（§3.8） | `COALESCE(cl.root_no, ebi.material_no) AS hf_part_no` | **归属料号 —— 子件行上就是子件料号** ❌ |

版本指针只挂在**父件（line_item 销售料号）**上 → 闭包出来的子件行 JOIN 不中 → **子件元素价不吃版本**，静默打破"升版原子性"。

```sql
ON  cep.element_code = ebi.component_no
AND cep.material_no  = COALESCE(cl.root_no, ebi.material_no)   -- 闭包形态
AND cep.material_no  = ebi.material_no                          -- 平铺形态
```

🚨 **两种形态并存 → 迁移脚本必须逐视图按各自 `hf_part_no` 表达式改写，禁止统一字符串替换。**

**配套（task-0729 §11.15.3）**：接价格策略的组件还须配齐组件级三项 `element_code_field` / `element_price_field` / `element_currency_field`，且**取价函数别名固定为 `cep`**。见 `AGENT-配置入口.md §3.6.1`。

### 3.8.5 已知代价

- **性能**：CTE 对该客户**所有根料号**做全闭包展开，再靠外层 `hf_part_no` 筛。小数据量无感，**BOM 量大的生产库需实测**。框架层若将来提供「本单闭包料号集合注入」（`:hfPartNos` 管道已存在，见 `dev-docs/task-0726-*/需求说明.md`），本节写法可整体退役。
- **口径重复**：BOM 树页签走 `costing_bom_tree_config` 递归、本节走视图内递归，两套实现需人工保持一致（如 `characteristic` 过滤口径）。

### 3.8.6 落地清单（2026-07-26 · 罗克韦尔「报价系统模板0723」）

| 组件 | 视图 | 效果（`QT-20260726-0008`） |
|---|---|---|
| COMP-0027 材料成本 | `mc_view` | 2 行 → **4 行**（+ 子件 `00002` 的 Ag/C） |
| COMP-0028 外购件成本 | `wg_view` | 1 行 → **2 行**（+ 子件下的 `W-1001`） |
| COMP-0029 加工费 | `jg_view` | 1 行 → **2 行**（+ 子件自身工序 `Z381`） |
| （对照）产品 | — | 1 行 → **1 行**，未受影响 ✅ |

> ⚠️ **上表三个视图落库时用的是未去重版本（无铁律 3）**：`cpq_db_0724` 当时数据每对父子只有一条边故未暴露，但换成有 `RECIPE`+`ASSEMBLY` 双边的数据（旧库 `cpq_db` CUST-1269 实测）行数会翻倍。**这三个组件的 `sql_template` 需按铁律 3 补去重**；`客户组件模板/组件导入-罗克韦尔.json`（2026-07-26 晚版）已内置去重写法，重导即可。

### 3.8.7 闭包 × pending 改写：为什么闭包对「待审核数据」也成立（2026-07-26 实测）

报价侧基础数据**导入后先是 pending**（`is_current=false` + `pending_quotation_id=<本单>`），审核转正才 `is_current=true`（§3.6 生效时间线）。闭包 CTE 里写的是 `WHERE b.is_current`，看上去会把 pending 数据挡在闭包外 —— **实际不会**：

- `QuotePendingRewriter.rewrite` 遍历 `findTableTokens` 命中的**每一个**白名单表 token（`FROM`/`JOIN`，**不分嵌套深度**）做整表替换，并把 `is_current` 列重定义为 `(t.is_current OR t.pending_quotation_id = :pq)`。故闭包 CTE 内的 `material_bom_item`（base case 的 `FROM` 与递归段的 `JOIN`）**一并开域**，pending BOM 边照样进闭包 ⇒ 子件数据照常进平铺页签。
- **不要**为了"看见 pending"去掉闭包里的 `is_current` —— 改写后它正是开域开关，去掉反而会让**非本单的历史版本**全涌进来。
- 深度只影响**锚点注入**：主位表探测只认 `depth==0` 的 `FROM`，且 `hasTopLevelSetOp` / `hasGroupByAtDepth` 只在**顶层**判降级。所以铁律 3 引入的 `UNION`（在 CTE 内，depth 1）和 `bom_closure_d` 的 `GROUP BY`（depth 1）**都不会**触发"安全降级、不注入 `__v6_id`"。⚠️ 反过来，**顶层**写 `UNION`（如 BOM 树视图的"边 + 根分支"写法）会让 `anchorInjected=false` → 该页签只读展示、不参与 B5 回填，这是设计内的安全降级。

**实测**（DRAFT 单 `2168c574` / `S-3120014539`，按改写器算法原样模拟）：材料成本 4 行（主件 H65/Cu + AgNi11#-Ⅰ/Ag ＋ **子件 `S-80011` 的 AgC3 的 Ag/C**）、加工费 2 行（Z380 + 子件 Z381）、外购件成本 2 行（同名「组成件1」数量 2/3，靠归属料号列区分）；元素单价接价格策略出 1850/118478，无价元素留 `NULL` 且不掉行。
