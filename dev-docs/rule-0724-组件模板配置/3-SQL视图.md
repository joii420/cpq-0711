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
