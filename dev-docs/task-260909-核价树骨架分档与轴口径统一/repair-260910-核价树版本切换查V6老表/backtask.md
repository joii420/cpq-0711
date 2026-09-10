# backtask · repair-260910 核价树版本切换

> 依据 `问题说明.md` ⑤修复方案 / ⑥验收标准。**AC 原文才是验收标准**，与本文件有出入以 AC 为准并报主线。

## B-1 · `listVersionOptions` 树分支改查新表（服务 AC-3 / AC-4 / AC-5 / AC-10）

文件：`cpq-backend/src/main/java/com/cpq/quotation/service/CostingVersionService.java:105-120`

1. 去掉 `String dsCostBase = tree ? null : dsCostBaseTableOf(componentId);` 的树短路，让树分支也能拿到数据集。
2. 树分支查询替换为（视图名**按数据集解析**，🚫 不许硬编码 basic）：
   ```
   SELECT version_no::text, is_current
     FROM <v_ds_cost_basic_material_bom_all | v_ds_cost_detail_material_bom_all>
    WHERE production_no = :p AND version_no IS NOT NULL
   ```
   - `COST_BASIC` → `v_ds_cost_basic_material_bom_all`；`COST_DETAIL` → `v_ds_cost_detail_material_bom_all`
   - 该视图定义即「主表 `UNION ALL` `_history`」⇒ **一条 SQL 拿全历史+当前**，🚫 不要自己再 UNION 一次
3. `is_current=true` 的那个版本赋给 `isCurrentVersion`（override 缺失时的兜底），语义与老表 `is_current` 一一对应。
4. 🚫 **不碰** `else if (dsCostBase != null)` 非树分支与最后的 `else` 分支 —— 那两支现状正确（`AC-11` 是门禁）。

⚠️ **N+1 硬指标**：仍须是**一条** SQL，与版本数 / 行数 / 页签数无关。

## B-2 · `switchVersion` 叶子守卫（服务 AC-8）

同文件 `switchVersion`：在 PENDING 校验之后、写 override 之前，判定 `req.partNo` 是否作为 `production_no` 在对应视图中存在；不存在 → `BusinessException(400, ...)`，消息说明「该料号没有自己的 BOM，不可切换版本」。
🚫 **不得**吞掉或改动既有的 `403 仅待核价(PENDING)可切换版本`（`AC-9` 是门禁）。

## B-3 · 源码级接线守卫测试（服务 AC-9 / AC-11 / AC-15）

新增测试类，至少覆盖：
- 树分支**不再**出现 `material_bom_item` 字面量（源码级断言，防「反射直调守不住接线」——`task-260909` 已栽过一次）
- 非树分支与 `else` 分支的代码路径未被改动
- `PENDING` 校验仍在

🚫 **不许**在共享库上跑清库型测试（`CLAUDE.md §3.2`「测试也算」）。

## 🚫 本次不做

- **不碰** `WHERE :versionFilter(ch.is_current, ch.version_no, ch.production_no)` 行过滤 —— 已按 `production_no` 分档且语义正确
- **不碰** 全工程另 9 处硬编码 `FROM material_bom_item`（用户裁决：登记 BACKLOG 单独评估）
- **不碰** 报价侧 / 非树组件 / `frozenDto` 冻结路径
