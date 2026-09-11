# backtask · repair-260910 核价侧无销售料号子件取不到元素单价

> **只按本文件做。** AC 原文在 `问题说明.md ⑥`，本文件**只标编号不复制原文**（单一事实源）。
> 🚫 **遇 `CLAUDE.md §3.2` 不可逆操作红线一律停下报主线**，你没有批准权。本次方案里**没有任何 DROP / TRUNCATE / 无 WHERE 的 UPDATE**，出现了就说明走偏了。

## 前置：本次是「纯语义图数据配置」，Java 零改动

`SemanticCompiler:1560` 的价格 JOIN 生成逻辑里 `if (keys.size() > 1)` 只在多键时进入 ⇒ **删掉 `seq=1` 边键后单键 JOIN 自然成立**，函数签名直接取自节点 `func_signature`。
🚫 **不要改 `SemanticCompiler`**。如果你发现不改 Java 做不到，先停下报主线 —— 那意味着上面这条依据错了，方案要重议。

---

## B-1 · 迁移：换取价函数 + 双键降为单键

**服务的 AC**：AC-1, AC-2, AC-4, AC-5, AC-6, AC-7, AC-9, AC-10

1. **占号前先四方核对** Flyway 版本号（共享库 × master × 当前分支 × `target/classes`），🚫 不许凭 `ls` 猜 —— 并发线可能已抢号（`INDEX` 有两次事故记录）。
2. `UPDATE semantic_node` 两行（`26090901-0000-4000-8000-000000000001` = `COST_BASIC`、`…0002` = `COST_DETAIL`）：
   - `func_signature` → `f_customer_element_price(:customerCode, :priceBaseDate)`
   - `display_name` → `价格策略 f_customer_element_price`
   - `note` → 记 `A0-1` 裁决来由，**并显式写明**「核价侧不再读 `versioned` 冻结版本价，属知情取舍，去向见 BACKLOG 单据级元素价格表」
3. `DELETE FROM semantic_edge_key`：上述两条 `PRICE` 边的 **`seq=1`** 键（`sales_material_no` → `material_no`）。
   🚦 **删之前先 `SELECT count(*)` 报数**（§3.2 第 1 步：说不出数字不许执行）。预期命中 **2 行**，多于 2 行**停下报主线**。
   保留 `seq=0`（`element_code` → `element_code`）。
4. 🚫 **`QUOTE` 方言一行不动。**
5. 迁移末尾加**落库自检 `DO $$ … RAISE EXCEPTION`**（口径照抄 `V434` §8）：
   - 两个核价节点 `func_signature` 均为新函数 → 否则抛
   - 两条核价 `PRICE` 边各**恰好 1 条**边键 → 否则抛
   - `QUOTE` 侧 `PRICE` 边仍为 **2** 条边键 → 否则抛（防误伤）

⚠️ **本次不碰视图、不碰 `CostAllVersionViewSelfCheck`** ⇒ 不需要 `repair-260909` 那套两阶段落库纪律。若你发现需要动它们，**停下报主线**。

## B-2 · 重编译受影响的核价侧组件

**服务的 AC**：AC-1, AC-2, AC-3, AC-7

1. **现查**受影响组件清单，🚫 不许照抄文档里的数字：
   ```sql
   SELECT c.id, c.name, csv.sql_view_name
     FROM component_sql_view csv JOIN component c ON c.id = csv.component_id
    WHERE csv.sql_template LIKE '%f_material_element_price%';
   ```
   再按 `component.data_driver_path` / 所属模板判出**哪些是核价侧**（报价侧的**不要动**）。
   本库预期：核价侧 **1 个**（`54001a3b-…` / `builder_54001a3bfd2e`），报价侧 1 个（`813b4ead-…`，不动）。
2. 逐个走 `POST /api/cpq/components/{id}/builder/compile` → `save` 重新生成 `sql_template`。
3. **改动前先存一份 `md5`**，改动后比对：核价侧应变、**报价侧必须逐字节不变**（AC-4）。

## B-3 · `核价模板` 升版重新冻结

**服务的 AC**：AC-1, AC-3, AC-6, AC-8

1. 🔑 **升的是 `核价模板`（`28c595d3-…`），不是 `正泰模板`（`62dd00cb-…`）** —— 实测前者挂核价侧组件 `54001a3b`，后者挂报价侧组件 `813b4ead`。
2. 走 `new-draft` → `publish`。`sql_views_snapshot` 只在 `TemplateService.publish:262` 写入，这是**唯一**能刷到它的现成路径（`A0-2`）。
3. 🚫 **不许调 `forceRealignSnapshots` / `recompileAndRealign`** —— `BL-0223`：它们不刷 `sql_views_snapshot`，会**报成功但渲染不变**。
4. 升版后断言：`核价模板.sql_views_snapshot` 含 `f_customer_element_price`、**不含** `f_material_element_price`；`正泰模板` 的 `version`/`updated_at`/`sql_views_snapshot` **三项全部不变**。

## B-4 · 重算受影响单据的 `costing_card_values`

**服务的 AC**：AC-1, AC-3

核价侧的值**已被物化**在 `quotation_line_item.costing_card_values`（本单 16229 字节、材质元素 4 行 `元素单价: None` 已冻存）⇒ 改完 SQL 不会自动变好。
走「刷新基础数据」（`ConfigureProductResource` 的 refresh 路径，`skipRowsWithSnapshot=false`）重算 `QT-20260910-0004`。
⚠️ 重算后**必须回查 DB 确认 `costing_card_values` 里的 `元素单价` 已变成非空**，🚫 不要只看页面。

## B-5 · 自检与交付证据

**服务的 AC**：AC-10

按 `问题说明.md ⑥ AC-10` 四项逐条给**命令与其输出**。
🚨 **必须真重启应用**（`CostAllVersionViewSelfCheck` 活在 `@Observes StartupEvent` 上）——
`repair-260909` 就是因为用「事务内 `CREATE OR REPLACE` + `ROLLBACK`」做预实验、**从未重启**，漏掉了一个只活在启动路径上的失败面。**量具所在的层，和失败发生的层，不是同一层。**

---

## 反向覆盖自查（每项都指得回 AC）

| 编号 | 服务的 AC |
|---|---|
| B-1 | AC-1, 2, 4, 5, 6, 7, 9, 10 |
| B-2 | AC-1, 2, 3, 7 |
| B-3 | AC-1, 3, 6, 8 |
| B-4 | AC-1, 3 |
| B-5 | AC-10 |
