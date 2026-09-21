# backtask · 审核列表秒开与按需试算（v2）

> 立项文档：`需求文档.md`（③ AC 原文 / ⑥ 裁决台账）。**本文只标 AC 编号，AC 原文以立项文档为准；有出入以 AC 原文为准并报主线。**
> 契约：`api.md`。前端另见 `fronttask.md`，两边只通过 `api.md` 协调。
> 编号保持 v1 不变以便对照：**`B-4`、`B-8` 已删除**（见各行），v2 新增 `B-15`~`B-19`。
> 🔒 A0 裁决 `D-3` = 甲′：**只做按依据单分组并行、组内串行**。🚫 不改 `upgrade()` 及其调用路径（允许在 `MaterialVersionUpgradeService` 新增**只读**批量方法）。
> 🚫 **不新增迁移**；发现必须迁移 ⇒ 停下报主线。🚫 遇 `CLAUDE.md §3.2` 红线（清库 / DROP / 批量 DELETE / 改已应用迁移）⇒ 停下报主线，你没有批准权。

---

## A 组 · 进池与试算解耦（R-1）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3 | **进池判定批量化**。一次性取全集，**条数与料号数无关**：① 范围料号（`resolveScopeMaterials`，两种模式各 1 条）；② 每个料号的依据行 —— 一条窗口函数，次序按 `J-3`（`q.created_at DESC, li.sort_order ASC NULLS LAST, li.id ASC`，🔒 末级必须是唯一列），同时 `JOIN template` 带出 `template_series_id`（🚫 逐个 `Template.findById`）；③ 驳回史 —— 一条 `SELECT DISTINCT material_no … WHERE customer_no=? AND status='REJECTED'`（🔒 必须带 `customer_no`）；④ 已有审核行、⑤ 已有指针（现成两条批量查询）；⑥ 目标版本与**全部指针版本**的元素价 —— 一条 `version_id IN (...)`；⑦ 有指针料号的「相关元素编码」—— 在 `MaterialVersionUpgradeService` **新增只读批量方法**（冻结结构按单据 `IN` 一次、`quotation_line_component_data` 按 `(line_item_id, component_id)` 元组 `IN` 一次、组件字段按 `id IN` 一次），🚫 循环调 `collectMaterialElementCodes`。<br>🔒 **判定口径一字不改**：`processMaterial` 里的三个边界与裁决 39 的保守方向逐条保留，只改「数据怎么取」。<br>🔒 **现有逐料号函数原样保留、不许改动**（`findBasisLine` / `hasEverRejected` / `MaterialPriceVersionRef.findRef` / `hasRelevantPriceChange`）—— `AC-2` 要原样调用它们做对照。<br>🔒 **进池路径的全部查询走 `EntityManager`（Hibernate 可计数）**，🚫 调 `SqlViewExecutor` 或其它裸 JDBC（`AC-3` 的计数看不到它们）。 |
| **B-2** | AC-1, AC-3, AC-19 | **批量建行 + 批量推进指针**：① 该进池的料号用**一条原生语句**建行（`INSERT … SELECT unnest(...)` 或等价写法，`ON CONFLICT (version_id, material_no) DO NOTHING`）。🚫 逐个 `persist`（`statement-batch-size=100` 下会按批计入 `SqlStatementCounter`，`AC-3` 必挂）；② **反例外行**（无依据单、有驳回史）**直接写 `READY` + `column_count=0`**，其余写 `QUEUED`；③ 不进池的料号一条语句批量 UPSERT `material_price_version_ref`；④ 整段一个事务，写前带锁复核版本仍为 `PENDING`（沿用 `lockVersionAndCheckPending`）；⑤ 埋点 `[perf] budget-enqueue customer=%s N=%d pooled=%d advanced=%d sql=%d ms=%d`。 |
| **B-3** | AC-12, AC-15 | **试算循环**：取本版本 `status='PENDING' AND budget_status='QUEUED'` 的行，按依据单分组（分组与并行见 `B-5`），**组内按 `material_no` 升序**处理（确定次序，测试要挑「靠后才算到」的料号）。每条：`B-15` 抢占 + 取锁 → 用 `B-17` 的口径重找依据行 → 试算（与现 `computeBudget` 同一份实现，🚫 另写一份）→ `READY` / `FAILED` → 放锁。每条一个独立事务（沿用 `REQUIRES_NEW` + `@ActivateRequestContext`）。 |

## B 组 · 提速（R-5 · A0 裁决 `D-3`）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| ~~**B-4**~~ | — | ~~压 SQL 往返：经 `versionPricesOverride` / `precomputed` 注入单据级不变量~~ —— **按 `D-3` 删除**。实查：`precomputed` 只在 S5 且单据有核价模板时才用（`MaterialVersionUpgradeService.java:390-400`），三张大单都没有，且预算路径从不传它（javadoc :176-180）；`versionPricesOverride` 只省 S1 一条 SQL |
| **B-5** | AC-13, AC-14, AC-23, AC-26 | **组间并行、组内串行**：有界线程池，并发度取配置 `cpq.price-adjust.budget.concurrency`（**默认 3、上限 5**，超过按 5 处理并记 WARN；`application-test.properties` 设 1）。依据：每个工作线程同时占 **2 个主池连接**（外层 `REQUIRES_NEW` 挂起不释放 + 嵌套试算的 `REQUIRES_NEW`），主池上限 20。每个工作线程：独立 `@ActivateRequestContext`、独立事务、**只处理分给它的依据单组、组内严格顺序**。🚨 同一 `basis_quotation_id` 的料号**永不跨线程**。锁**逐料号取放**（不是整组持有），让点击即算能在两个料号之间插进来。🚫 改 `ComponentDriverService` 的缓存返回方式或 `CardSnapshotService` 的缓存层（A0 已否决的方案丙）；不改就并行不了 ⇒ 停下报主线。埋点 `[perf] budget-compute version=%s groups=%d materials=%d workers=%d ms=%d`，并**逐组**打 `[perf] budget-group quotation=%s materials=%d ms=%d`（`AC-14` 要用组耗时）。 |
| **B-6** | AC-13 | **并行正确性判据（只读脚本，落 `证据/`）**：① 哈希：给定 `version_id`，全部审核行的 `(material_no, column_id, quote_current, quote_adjusted, costing_current, costing_adjusted, diff_adjusted)` 按 `material_no, column_id` 排序后取聚合哈希；② **输入指纹**：目标版本与全部指针版本的元素价、三张大单全部行 `quote_card_values` 的哈希、相关料号的版本指针；③ **阳性对照**步骤：导出一份数据副本，改一个值，重算哈希必须不同。🔒 `AC-13` 一票否决：不过 ⇒ 并行不得交付，回落为串行循环（仍含 R-1 解耦）并报主线。 |

## C 组 · 按需试算与通过 / 驳回守门（R-3 / R-4 / R-9）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-7** | AC-5, AC-6, AC-8, AC-9, AC-20, AC-21 | **点击即算 `POST …/{reviewId}/compute-now`（`api.md §2`）+ 单行查询 `GET …/{reviewId}/row`**：① **一律返回 202**（受理 / 已在算都一样），试算在后台线程跑，前端轮询单行查询（前端超时 60 s = 后端事务超时 60 s，同步等会把超时显示成网络错误）；② 可抢占状态：`QUEUED` 或 `FAILED`（FAILED 仅用户显式「重新计算」时发起），走 `B-15` 抢占 + 锁；③ 只处理「待处理」行，非待处理返回 409 `REVIEW_NOT_PENDING`；④ 试算前带锁复核版本仍为 `PENDING`，已作废 ⇒ **不写**，行保持「已作废」；⑤ 与后台循环**同一份**试算实现（`AC-6` 验）。单行查询复用 `toListItem`，🚫 触发任何试算。 |
| ~~**B-8**~~ | — | ~~`compute-batch` 批量试算~~ —— **按 `J-2` 删除**：前端逐条串行调 compute-now |
| **B-9** | AC-8, AC-10, AC-11 | **影响面 `impact` 返回逐料号金额 + 既有守门补测试**：① 返回体新增 `materials[]`（逐料号 `col-default` 比对列的现值 / 调整后 / 差异 / 状态 / 缺失侧），**一条 `review_id IN (...)` 批量取**，🚫 在 `impact` 现有逐审核循环里加查询（它已在 `BL-0308 ③` 清单上，本次不许恶化）；② 🚫 既有字段一个不删（现网确认框依赖）；③ 前端只会传 READY 行，服务端不必特殊处理其它状态；④ `approve` **沿用既有守门不变**（`doApprove` 对任何非 READY 整批 409 `REVIEW_BUDGET_NOT_READY` + `invalidItems`，信封见 `GlobalExceptionMapper:87`），🚫 另造错误码，本次**补测试**覆盖 QUEUED / COMPUTING / FAILED 三种。 |
| **B-18** | AC-25 | **驳回守门（`D-6`）**：`reject` 入参中任何 `budget_status≠READY` 的行 ⇒ 整批拒绝，409 `REVIEW_BUDGET_NOT_READY` + `invalidItems`，**与 `approve` 同一异常类与信封**；🚫 批量取审核行，不在循环里逐条 `findById`。 |
| **B-19** | AC-5, AC-7 | **详情 DTO 加 `budgetError`**：`ReviewDetailDTO` 新增可空 `budgetError`，取自已加载的审核行对象，🚫 新增查询。 |

## D 组 · 状态机重定义（R-6）

> 🚨 这三处是 `repair-260918`（2026-09-20 结案）的交付物。改之前先读它的 `backtask.md` B-8 / B-11 / B-12。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-10** | AC-12, AC-16 | **续跑判据**：进池阶段跳过「已有审核行 ∨ 指针已指向本版本」的料号（判定不必重做）；试算阶段只认 `READY` / `FAILED` 为已处理，`QUEUED` / `COMPUTING` **必须续算**。日志「续跑跳过=n」的口径同步改。 |
| **B-11** | AC-17, AC-20, AC-27 | **作废覆盖未计算 + 「只对待处理有意义」**：① `voidPendingByVersion` 按 `status='PENDING'` 作废，`QUEUED` 行天然覆盖；② 试算循环每条开始前带锁复核版本状态，已作废即停，**每个工作线程各自停**；③ 规则落地：**`budget_status` 只在 `status='PENDING'` 时有意义** —— `notComputedTotal` 只数待处理行、后台循环与 compute-now 只处理待处理行（前端呈现与轮询见 `F-1` / `F-3`）。🚫 新增枚举值。 |
| **B-12** | AC-16, AC-18 | **启动收尾（评审【1】的根因就在这）**：① `PriceAdjustStartupRecovery.findVersionsNeedingBudgetResume` 的判定**增补一个条件**（与原条件 OR）：该待处理版本下存在 `status='PENDING' AND budget_status IN ('QUEUED','COMPUTING')` 的审核行 —— 否则解耦后进池一完成，这条查询对任何版本都返回空，`resumeBudgets` 永远不被调用；② 把最后更新时间**早于本实例启动时刻**的 `COMPUTING` 行重置为 `QUEUED`（进程已死，不可能还在算），再续跑；③ 两件事都是**常数条** SQL；④ 仍受 `cpq.price-adjust.startup-recovery.enabled` 约束，测试配置仍为 `false`（`AC-16/18` 在测试里显式开启后调用 `runOnStartup()`）。 |

## E 组 · 列表（R-2）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-13** | AC-1, AC-27, AC-28 | **列表**：① `budget_status≠READY` 时金额字段返回 **`null`**（🚫 返 0）；② 返回体新增 `notComputedTotal`（当前筛选条件下、**待处理**行中 `QUEUED`/`COMPUTING` 的总数，一条计数查询）；③ **排序补确定次键**（`J-1`）：`created_at DESC, material_no ASC, id ASC`；④ 🚫 `toListItem` 内新增查询。 |
| **B-14** | AC-4 | **「只看标红」**：`breachedOnly=true` 时条件改为 `breachedCount > 0 AND budget_status='READY'`，并返回 `excludedByNotComputed`（因未计算而未参与筛选的待处理行数）。 |

## F 组 · 并发安全、收编与依据单口径（R-7 / R-8）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-15** | AC-6, AC-13, AC-21, AC-22, AC-23, AC-26 | **统一抢占 + 按依据单互斥（评审【2】、`D-4`、`D-9`）**：<br>① **抢占**放在所有试算入口的汇合点：`UPDATE material_price_review SET budget_status='COMPUTING', updated_at=now() WHERE id=? AND status='PENDING' AND budget_status IN (<本入口允许的状态>)`，**更新到 1 行才算**，否则直接返回当前状态。允许的状态：后台循环 = `QUEUED`；compute-now = `QUEUED`/`FAILED`；既有「重算」`recompute-budget` = `QUEUED`/`FAILED`/`READY`（显式重算）。`markComputing` 的无条件置位**改为这条条件更新**；<br>② **按 `basis_quotation_id` 的进程内互斥锁**（单实例前提），**逐料号取放**，覆盖：后台循环 / compute-now / `recompute-budget` / 抽屉详情里的元素影响试算（`detail` → `safeDryRunAdjustedSubtotal`）/ **正式升版任务**（`PriceAdjustJobExecutionService` 处理每个明细前取该单的锁，`D-9`）；<br>③ 取锁一律在任何数据库写之前（统一次序，避免与数据库行锁形成死锁）；<br>④ 🔒 正式升版路径**只多取锁 / 放锁**，其余逐字不动（`AC-22` 用 diff 证明）。 |
| **B-16** | AC-24 | **收编改策略 / 改比对列的重算（`D-4`）**：`PriceAdjustStrategyService.dispatchRecompute`（及其前的 `markPendingForRecompute`）与 `PriceAdjustComparisonColumnService.putColumns`：改为**一条语句**把相关待处理行标为 `QUEUED` 并**唤起**对应版本的后台循环（已在跑则复用，沿用 `runningVersions` 判重），🚫 再逐条 `managedExecutor.runAsync(processMaterial)`。 |
| **B-17** | AC-7 | **依据单与依据行口径（`D-5`、`J-3`）**：① **依据行选取的唯一规则**（`J-3`）：`q.created_at DESC, li.sort_order ASC NULLS LAST, li.id ASC` 取第一行 —— 进池（`B-1`）/ 试算（`B-3`）/ 抽屉 `detail`（`PriceAdjustReviewService.detail` 现为「结果里第一条匹配的行」）**三处统一**；② 试算时按审核行上记下的 `basis_quotation_id` + 料号重找依据行；该单已不在 `ACTIVE_STATUSES` 或找不到该料号的行 ⇒ `FAILED`，`budget_error`=「判断依据单已不在活单范围内」，**不推进指针、不作废、不退回 QUEUED**；③ `basis_quotation_id` 为空的行（反例外）维持 READY + 0 列；④ 顺带修掉 `processMaterial:319-322` 的潜伏缺陷：已存在的审核行不再因「依据单消失且无驳回史」而推进指针后停在 `QUEUED`。 |

---

## 自检要求（交付回报必须逐条给出，缺一条视为未完成）

1. **N+1 自检**（`docs/rules/backend.md §1`）：逐个列出本次新增 / 改动的循环体，声明其中无查库；给出 `AC-3` 两个夹具（N=5 / N=50）的 `[perf] budget-enqueue … sql=` 数值（必须相等），并**逐条列出进池路径的每条查询及其通道**（Hibernate / 裸 JDBC）。
2. **`AC-13` 自检**：在 worktree 临时后端上（并发度显式设 3）连跑两版取哈希，并做一次阳性对照，把两次哈希与对照结果贴进回报。不等 ⇒ 不许报完成，按 `B-6` 回落并报主线。
3. **`AC-22` 自检**：给出正式升版路径的 diff（只应有取锁 / 放锁）与现有相关测试类的计数和退出码。
4. 服务在 **worktree 自己的临时后端**上启动无错误日志，必带 `-Dquarkus.scheduler.enabled=false -Dcpq.price-adjust.startup-recovery.enabled=false`；改动端点返 200/202/401，无 500。🚫 拿共享 8081 当自检对象（它跑的是主仓代码）。
5. `mvnw test` 在 **worktree 的 `cpq-backend/`** 里跑（默认连 `cpq_db_test`），给出点名测试类的计数与退出码。⚠️ `AC-16/18` 的测试会在测试库上调 `runOnStartup()`，它作用于测试库里**全部**待处理版本 —— 这两条放在最后串行跑，跑前采样测试库的待处理版本数写进回报。
6. 🚨 不新增迁移；遇 `§3.2` 红线停下报主线。
