# backtask.md · 后端任务分解

> 立项文档：`./问题说明.md`（AC 原文在其 ⑥ 节，**本文件只标编号不复制原文**）
> 分支：`feat/repair-260908-tab-dup-rows`（待建）

---

## 0. 分两批交付 —— 以及「只做前一半，系统长什么样」

并发会话 `task-260907-record层与核价回填` 正被同一缺陷卡住闸门 B 验收
（`QT-20260908-0625` 提交返「行键重复」9 处、每处恰 2 行）。故按缺陷分两批。

| 批 | 内容 | 解决什么 |
|---|---|---|
| **第一批** | `B-1` `B-1b` `B-2` `B-6` | 缺陷① 跨客户串号 |
| **第二批** | `B-3` `B-4` `B-5` + 再跑一次 `B-6` | 缺陷② 主件页签轴范围 |

🚨 **只做第一批时系统长这样**（`task-260907` 教训要求显式回答，不许留空）：

- 「主件」页签从 **28 行降到 14 行**，**仍不是 1 行** —— 缺陷②未修，闭包照旧
- 其余 15 个页签的**跨客户重复行全部消失**
- ⇒ **行键重复 422 消失**（重复行的来源是跨客户，不是闭包）⇒ 并发会话的验收可以继续
- ⇒ **不存在「比两个终态都差」的第三种状态**：第一批是一个自洽的中间态，
  用户看到的是「行少了但还没少到 1 行」，不是「按钮亮着点了必然失败」那种

🚫 **两批之间不得让 `component_sql_view` 停在手改状态** —— 第一批必须走编译器重生成，
不许为了快先手工 `UPDATE sql_template`（下一次配置器 save 会把手改覆盖掉）。

---

## 1. 任务项

| 编号 | 服务的 AC | 任务内容 | 批次 |
|---|---|---|---|
| **B-1** | AC-1b, AC-2, AC-2b, AC-12, AC-12b | `SemanticCompiler.applyFullScope()`：物理表含 `customer_no` 列时，追加谓词 `<别名>.customer_no = :customerCode` 并把 `customerCode` 记入 `requiredVars`。该方法 3 个调用点（锚点 `:344` / SUB `:780` / GRAIN `:815`）自动全覆盖。🚫 **不得改 `ensureLeftJoin()`（`:751`）** | 一 |
| **B-1b** | AC-16 | `SemanticCompiler` 的 `NARROW` 桥（`:585-587`，直接拼 `anchorWhere`，**不经 `applyFullScope`**）：桥的 target 物理表含 `customer_no` 时，子查询 `WHERE` 追加 `AND <桥别名>.customer_no = :customerCode` | 一 |
| **B-2** | AC-5 | `SqlViewExecutor.rewriteNamedParams()`（`:626-652`）：`customerCode` 未绑定时抛 400，不再静默替换成字面量 `NULL`。实现照抄同方法 `:637-646` 的 `total_material_no` 分支 | 一 |
| **B-3** | AC-7 | `CompileResult` 新增 `axisScope` 字段（`SELF` / `CLOSURE`）；`SemanticCompiler` 按 `tabType` 是否等于 `ROOT_SOURCE_TAB_TYPE`（`"主件"`，`:148`）赋值。**三个方言都产出**，不按方言分叉 | 二 |
| **B-4** | AC-7, AC-8, AC-11 | 落盘与读回：`BuilderService.save()`（`:820` 附近）把 `axisScope` 并进 `component_sql_view.builder_config`；`ComponentSqlViewService` 冻结快照时写 `axis_scope` 键、`lookup*`（`:361`/`:393`/`:479`）读回。**无 DDL**。🔑 **缺键必须退回 `CLOSURE`** | 二 |
| **B-5** | AC-1, AC-3, AC-9, AC-10, AC-13 | `ComponentDriverService` 两处加宽点按 `axisScope` 分支：单卡 `_widenedHfPartNos`（`:426`）`SELF` ⇒ `null`；合桶 `widenedPartNos`（`:744`）`SELF` ⇒ 不并入 `totalMaterialNo` | 二 |
| **B-6** | AC-14, AC-15 | 扩展**已有**端点 `POST /api/cpq/config-center/refresh-all-snapshots`（`ConfigCenterResource:126`）加可选 body 字段 `recompile`（缺省 `false`，行为逐位不变）：为 `true` 时先按各视图 `builder_config` 重放 `SemanticCompiler` 写回 `sql_template` + `builder_version`，再走既有 `TemplateService.forceRealignSnapshots`。契约见 `./api.md` | 一、二各跑一次 |

---

## 2. 双向覆盖自检（闸门 A）

**正向 —— 每条 AC 至少被一个 `B-x` 覆盖**：

| AC | 认领 | AC | 认领 |
|---|---|---|---|
| AC-1 | B-5 | AC-9 | B-5 |
| AC-1b | B-1 | AC-10 | B-5 |
| AC-2 | B-1 | AC-11 | B-4 |
| AC-2b | B-1 | AC-12 / AC-12b | B-1 |
| AC-3 | B-5 | AC-13 | B-5 |
| AC-4 | B-1（反向：不发即达成）| AC-14 | B-6 |
| AC-5 | B-2 | AC-15 | B-6 |
| AC-6 | B-1（反向：不改 `ensureLeftJoin`）| AC-16 | B-1b |
| AC-7 | B-3, B-4 | AC-8 | B-4 |

**反向 —— 每个 `B-x` 至少指回一条 AC**：B-1 / B-1b / B-2 / B-3 / B-4 / B-5 / B-6 全部有映射，**无孤儿任务项**。

---

## 3. 硬约束（违反即打回，不接受"我判断没问题"）

1. 🚫 **判据按列存在性，不按方言、不按视图数量。**
   28 个 `builder_*` = QUOTE 25 + COST_BASIC 3。`ds_cost_*` 45 张表**没有 `customer_no` 列**，
   写成「28 个全加」会得到 `column "customer_no" does not exist`。
2. 🚫 **不许碰 `ensureLeftJoin()`。** `task-260908` 的 46 条查名边走**列对列**（客户维度形态③），
   本来就安全；挪进 `WHERE` 会把 `LEFT JOIN` 收成 `INNER` 静默丢行。
3. 🚫 **不许手工 `UPDATE component_sql_view.sql_template`。** 一律走编译器重生成（B-6）。
4. 🚫 **不许 `new-draft` + `publish` 升版来修存量。** 升版产生新 `template.id`，
   存量 `quotation_line_item.template_id` 仍指旧版 ⇒ 救不了存量单。只能原地改写快照。
5. 🚨 **B-6 的 `confirm=true` 属 `CLAUDE.md §3.2` 边缘（改写共享库 28 视图 + 5 模板快照）。**
   子代理**只跑 `confirm=false` 预览**，把影响面数字交回主线；**真执行由主线报用户批准后进行**。
6. 🚨 **迁移号四方核对**：本任务**预期不需要迁移**（B-3/B-4 都是既有 jsonb 列加键）。
   若确实需要，动手前核对 `共享库 flyway_schema_history × master × 当前分支 × target/classes/db/migration`
   （第四方双向看），且**迁移一落共享库，文件必须当场进 master**。
7. **表名匹配一律用标识符边界** `(?<![A-Za-z0-9_])<表名>(?![A-Za-z0-9_])` ——
   `ds_quote_material` 是 `ds_quote_material_bom` 的前缀，裸 substring 会让断言两个方向同时出错。
8. **并发**：提交一律 `git commit -- <明确路径>`；起/重启共享端口（8081 / 5174）**前**先广播；
   worktree 各用各的端口，别抢 8099。并发会话 `task-260907-record层与核价回填` 已约定**不动**
   `component_sql_view` 与 `template`。

---

## 4. 需回归确认的既有功能

| 项 | 为什么会波及 | 怎么确认 |
|---|---|---|
| 核价两方言全部页签 | `applyFullScope` 是三方言共用 | AC-4：编译产物逐字节 diff 为空 |
| `task-260908` 的 46 条 LOOKUP 边 | 同在编译器 JOIN 生成路径上 | AC-6：`ON` 子句逐字不变 |
| `/api/cpq/builder/preview` | 走 `bindLiterals` 字面量替换，**不经** `rewriteNamedParams` | AC-5 反向断言 |
| `QuoteViewValidationService` / `CostingTreeSqlValidator` | 各自把 `:customerCode` 替换成 `NULL` 字面量 | AC-5 反向断言 |
| BOM 树渲染（报价侧 + 核价侧） | `hfPartNos` 加宽点被改 | AC-3 / AC-13 |
| 报价单提交行键校验 | 重复行消失后行键唯一性变化 | AC-10（保存草稿后重开值保留） |
