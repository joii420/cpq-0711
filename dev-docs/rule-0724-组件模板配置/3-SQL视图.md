# 3 · SQL 视图（`$view`）

> **来源**：`报价模板生成规则.md §4`（视图编写规则 + §4.6/§4.7 pending 改写坑）+ `核价SQL配置手册.md`（部分共性）+ `反模式.md AP-53`（禁表）+ 记忆 `cpq-chinese-identifiers-need-ascii-alias` / `cpq-sqlview-cache-key-needs-component-dim`。
> **状态**：🚧 骨架待填。

## 3.1 `$view` 机制

- `component_sql_view`：`$name` inline 子查询，随组件走；**禁在 DB 建物理视图**。
- 保存时 dry-run（EXPLAIN）校验。
- 组件 `data_driver_path` = `$view`；字段 `default_source.path` = `$view._列`。

## 3.2 hf_part_no 驱动键 + 隐式 JOIN

- 视图必出 `hf_part_no`（不带 `_`）；框架隐式 JOIN 注入 `hf_part_no = <本行项目料号>` 收窄。
- 报价：`material_no AS hf_part_no`；核价：桥接生产料号（见 核价侧）。

## 3.3 禁表黑名单（dry-run 拒）

- V44 废弃：`mat_part`/`mat_bom`/`mat_process`/`mat_fee`/`plating_plan`/`mat_customer_part_mapping`/`element_price*`/`customer_tax`。
- V76 废弃：`costing_part_*`。
- 必须 FROM V6 表（见 附录 V6 表映射）。

## 3.4 中文标识符需 ASCII 别名

- 公式/`$view` 路径里中文列名/字段名不能直接当标识符 → 必须别名或 Java 侧预解析。

## 3.5 缓存 key 含 componentId

- 同名视图跨组件（导入副本）会串号 → `DataLoader.resultCache` 等按 `$view` 的缓存 key 必须含 componentId（+ dataDriverPath + fieldsHash + total_material_no 维度）。

## 3.6 🚨 pending 改写坑（版本化表 LEFT JOIN 禁含 is_current）

- DRAFT 报价单渲染时 `QuotePendingRewriter` 把版本化白名单表（`unit_price`/`material_bom_item`/`material_master`/`capacity` 等）整表换成带 `:pq` 子查询。
- **坑**：版本化表写成 `LEFT JOIN unit_price up ON ... AND up.is_current=true`（is_current 在 JOIN ON）→ 产参数错位畸形 SQL → 运行期 `column index out of range` 静默返 0 行（dry-run 照过）。
- **规避**：改**相关标量子查询**（子查询 WHERE 带 is_current 安全）。
- **附带坑**：视图 ORDER BY 在 pending 管线下被丢弃 → 行排序用组件 `sort_field`（见 §4）。
