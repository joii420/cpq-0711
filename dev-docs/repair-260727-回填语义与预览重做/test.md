# repair-0727 · 测试用例（按 AC 映射）

> 主文档：`需求说明.md` §4 验收标准；实现：`backtask.md` / `fronttask.md`
> 纪律：**每条用例先断言 DB 前后快照**（回填是写库动作，UI 绿不等于数据对）。
> 复现基准：真实事故数据 —— 组 `(QUOTE, CUST-0001, S-3120014539)` 4 行 BOM，仅 `W-1001` 行被 `characteristic='OUTSOURCED'` 页签表征。

---

## T1 · AC-R1 投影页签不丢行（D1 回归）

**构造**：某组 4 行 `material_bom_item`（RECIPE×2 / ASSEMBLY×1 / OUTSOURCED×1）为本单 pending；模板含一个 `WHERE characteristic='OUTSOURCED'` 的平铺页签（仅 1 行带 `__v6_id`）。
**动作**：核价通过。
**断言**：
- 通过后该组 `is_current=true` 行数 = **4**
- 未被页签表征的 3 行逐字段（含 `component_no`/`characteristic`/`seq_no`/`composition_qty`）与基底 pending 行相等
- 老版本行 `is_current=false` 留存（可审计）

**反向对照**：同数据在 master（修复前）跑 → 行数 = 1（证明用例真的覆盖了缺陷，不是恒绿）

---

## T2 · AC-R2 未暴露列不丢值（D2 回归）

**构造**：`element_bom_item` 基底行 `base_qty=0.624610`，模板中 `mc_view` 不暴露 `base_qty`（把 `composition_qty` 展示为 `_毛重`）。
**断言**：通过后新版本行 `base_qty=0.624610`；被暴露的列（`scrap_rate` 等）若在报价单改过则取新值。

---

## T3 · AC-R3 树锚点 + 传导（D3）

- **T3.1 锚点注入**：对 `$bom_view`（顶层 `UNION ALL`）跑 `QuotePendingRewriter.rewrite` → `anchorInjected=true`；边行分支输出 `mbi.id AS __v6_id`，根行分支输出 `NULL::uuid`；`LIMIT 0` 执行不报「UNION 分支列数不一致」。
- **T3.2 列映射**：`QuoteBackfillColumnMapper.resolve(bom_view)` → `backfillable=true`、`primaryTable=material_bom_item`、`colToBase` 含 `_组成数量→composition_qty` / `_净重→net_weight`。
- **T3.3 渲染带锚点**：走 `BomTreeRenderService` 物化后，树页签 `snapshot_rows[].driverRow.__v6_id` 非空（根节点行除外）。
- **T3.4 删行传导**：树里删一个叶子 → 通过后该行不在新版本、老版本 `is_current=false` 留存，同组其余行不受影响。
- **T3.5 改值传导**：树里改 `组成数量` → 通过后新版本该行 `composition_qty` = 新值。
- **T3.6 spine 不回归**：`BomTreeRenderService` 的递归 spine SQL 仍走 `injectAnchor=false`，树渲染行数/结构与修复前一致。

---

## T4 · AC-R4 预览 ≡ 执行（D4，最高优先级）

**方法**：同一报价单先 `GET …/preview` 拿摘要，再 `POST …/costing-approve`，比对：
- 预览 `addedRows/deletedRows/changedRows` 与执行后 DB 实际差异**逐数字相等**
- 预览显示某组 0 变更 → 执行后该组 `SELECT *` 结果集与执行前**逐字段零 diff**（行数 + 每列值）
- 预览列出的每条 DELETE，执行后确实从新版本消失；每条 CHANGE 的 `newValue` 确实落库

**关键**：本用例必须能在修复前 **失败**（旧代码：预览 0 变更、执行删 3 行）。

---

## T5 · AC-R6 闭包跨组精确

**构造**：闭包页签（`$wg_view`/`$mc_view`）同时展示 `S-3120014539` 与 `S-80011` 两个组的行，各改一行。
**断言**：两个组各自升版，各自只改自己那行；A 组的改动不落到 B 组；B 组未表征的行保留。

---

## T6 · AC-R7 既有语义零回归

复跑 task-0721 原验收：
- AC-1 延迟生效（pending 写入不翻 `is_current`）
- AC-3 他单隔离（另一单渲染看不到本单 pending，行数不翻倍）
- AC-4 闸门（未审核料号不出现在「从已有产品添加」）
- AC-9 有历史（新旧两版本并存）
- AC-13 状态机（驳回保留 / 重交覆盖 / 撤回不回滚 / 删单级联）
- AC-14 主子同步（`material_bom`/`element_bom` 与子表同版本，不撞 `uq_material_bom_item`）
- AC-17 核价侧零回归（`PRICING` 侧导入/渲染逐位不变）
- AC-18 `plating_scheme` 全局升版语义不变
- Q4 previewToken 幂等：同状态两次 preview 同 token

---

## T7 · AC-R5 预览可读性（前端）

- 抽屉按产品卡片分组，卡头显示「料号 + 品名 + 客户名」
- 行显示中文列名 + 旧值→新值；ADD/DELETE 用中文字段名
- `plating_scheme` 进独立「全局共享变更」区并有红色警示
- 0 变更时显示既有 Alert
- 截图存档：有变更 / 无变更 / 含全局共享 三态

---

## T8 · AC-R8 无 N+1

- 打开 SQL 日志跑一次预览：基底行集装载 ≤ 2 条/表；品名解析 1 条；客户名解析 1 条
- 断言不随组数线性增长（造 20 组场景对比 SQL 条数）

---

## 执行顺序与产出

1. 后端单测（T1/T2/T3.1-3.2/T4/T5/T6/T8）→ `./mvnw test` 全绿截图
2. 真库端到端（T3.3-3.6/T4）→ 附通过前后 `SELECT` 输出对照
3. 前端（T7）→ 附三态截图
4. **反向对照**（T1/T4 在 master 上必须失败）→ 附失败输出，证明用例有效
