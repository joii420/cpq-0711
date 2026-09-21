# backtask · 审核列表秒开与按需试算

> 立项文档：`需求文档.md`（③ AC 原文 / ⑥ A0 裁决 `D-1` `D-2`）。**本文只标 AC 编号，AC 原文以立项文档 ③ 为准；有出入时以 AC 原文为准并报主线。**
> 契约：`api.md`。前端另见 `fronttask.md`，两边只通过 `api.md` 协调。
> 🔒 **A0 裁决 `D-1` = 方案甲**：先压 SQL 往返，再按依据单分组并行、**组内严格串行**。🚫 不改 `upgrade()` 的算法本身。
> 🚫 **不许新增迁移**。若实现中发现必须迁移，**停下报主线**（`budget_status` 沿用既有四态，不加枚举值）。

---

## A 组 · 进池与试算解耦（R-1）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3, AC-19 | **进池判定批量化**。`PriceAdjustBudgetService.loadScopeContext` 扩展为**一次性批量取全集**，条数与料号数无关：① 范围料号（现有 `resolveScopeMaterials`）；② **每个料号的依据单** —— 把现有逐料号的 `findBasisLine`（`:248`，含 `ORDER BY q.created_at DESC LIMIT 1`）改成**一条**窗口函数 SQL（`ROW_NUMBER() OVER (PARTITION BY li.product_part_no_snapshot ORDER BY q.created_at DESC)` 取 `rn=1`），一并带出 `quotation_id / line_item_id / customer_template_id`；③ 驳回历史（现有 `hasEverRejected` 逐个查 → 一条 `SELECT DISTINCT material_no … WHERE status='REJECTED'`）；④ 已有审核行、⑤ 已有指针（两条现成批量查询已在 `:222-225`）；⑥ 目标版本与指针版本的元素价 map（现 `loadVersionEffectivePrices` 在 `hasRelevantPriceChange` 里**每个料号各读两遍**，改为循环外按 versionId 读一次缓存复用）。<br>🔒 **判定口径一字不改**：`processMaterial:330-352` 的三个边界（`previousVersionId==null` 必进池 / `basis==null && hasRejectedHistory` 走反例外 / `review!=null` 不踢出池）与裁决 39 的保守方向（证明不了没变就进池）**逐条保留**，本任务只改「数据怎么取」，不改「怎么判」。AC-2 就是验这一条。 |
| **B-2** | AC-1, AC-3, AC-19 | **批量建审核行 + 批量推进指针**。判定完成后：① 对该进池的料号**一次批量 INSERT** `material_price_review`（`status=PENDING`、`budget_status=QUEUED`、`previous_version_id` / `basis_quotation_id` / `template_series_id` 按 B-1 的批量结果填），走 `ON CONFLICT (version_id, material_no) DO NOTHING`（`uq_mpr_version_material` 已存在）；② 对不进池的料号**一次批量 UPSERT** `material_price_version_ref`。<br>③ 这一整段在**一个事务**里提交 —— 提交即满足 AC-1「全部可搜」。<br>④ 写库前做一次 B-8① 的带锁复核（`lockVersionAndCheckPending`），版本已作废则整段不写。<br>⑤ 埋点：`[perf] budget-enqueue customer=%s N=%d pooled=%d advanced=%d sql=%d ms=%d`（`sql` 计数方式同 repair-260918 B-15）。 |
| **B-3** | AC-12, AC-15, AC-19 | **试算阶段独立成循环**。进池提交后，另起试算循环：按 `budget_status='QUEUED'` 取本版本待算行 → 逐条试算 → 成功 `READY`、失败 `FAILED`（错误文案沿用 `PriceAdjustFailureTranslator.forBudget`）。**每条一个独立事务**（沿用现 `REQUIRES_NEW` + `@ActivateRequestContext` 模式）。🔒 试算内容与现 `computeBudget`（`:513`）逐字节一致，只是调用时机变了。 |

## B 组 · 提速（R-5 · A0 裁决方案甲）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-4** | AC-14, AC-15, AC-22, AC-23 | **第 1 步：压 SQL 往返（先做，零竞态）**。B-3 的试算循环按 `basis_quotation_id` **分组**，组内预取**单据级不变量**各一次，经 `upgrade()` **已有的**两个重载注入：<br>① `versionPricesOverride`（`MaterialVersionUpgradeService.java:205-206`）—— 目标版本元素价 map，注入后 S1 整段跳过读库；<br>② `precomputed`（`:186-187`，`CardSnapshotService.PrecomputedTreeRows`）—— 核价侧批量预渲染，沿用 `PriceAdjustJobExecutionService#precomputeBatch` 同一套路（先过 `cardSnapshotService.templateHasTreeTab` 门槛，`task-0806 B8` 已放宽为 `public`）。<br>🔒 **两个参数传 `null` 即现状行为**，这是既有纪律，不许改默认。<br>🚫 **不改 `upgrade()` 内部算法**。<br>③ 交付回报必须给出：改动前后同一张依据单上单次试算的 `[perf] upgrade … sql=` 数值对比。 |
| **B-5** | AC-13, AC-14, AC-23 | **第 2 步：组间并行、组内串行**。有界线程池（并发度**可配置**，`application.properties` 给默认值并允许调；`application-test.properties` 设为 **1** 以保测试确定性），每个 worker：① 独立 `@ActivateRequestContext`；② 独立事务；③ **只处理分配给它的那一组（= 同一张依据单）的料号，组内严格顺序**。<br>🚨 **硬约束**：同一 `basis_quotation_id` 的料号**永不跨 worker**。依据 = 2026-06-22 被 revert 的竞态形态正是「同一张单多行并发」（`master 934c463`）。<br>🚫 **不许改 `ComponentDriverService` 的缓存返回方式，不许动 `CardSnapshotService` 的缓存层** —— 那是 `docs/三大核心模块基线.md` 的报价单渲染层，属 A0 已否决的方案丙。碰到「不改它就并行不了」的情况 ⇒ **停下报主线**，不许自行扩范围。<br>④ 埋点：`[perf] budget-compute version=%s groups=%d materials=%d workers=%d ms=%d`。 |
| **B-6** | AC-13 | **并行正确性的可复跑判据**。提供一个**只读**的哈希导出手段（SQL 脚本或只读端点二选一，落 `api.md`）：给定 `version_id`，输出全部审核行的 `(material_no, column_id, quote_current, quote_adjusted, costing_current, costing_adjusted, diff_adjusted)` 按 `material_no, column_id` 排序后的聚合哈希。<br>🔒 AC-13 是本任务的**一票否决项**：两版哈希不等 ⇒ 并行不得交付，回落 B-4 单独交付（即 A0 的方案乙形态），并报主线。 |

## C 组 · 按需试算（R-3 / R-4）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-7** | AC-5, AC-6, AC-7, AC-20, AC-21 | **单条即时试算（插队）**。端点见 `api.md`。要求：① 复用 B-3 的同一条试算实现（🚫 不许写第二份 —— AC-6 验的就是两条路径结果逐位相同）；② 该行 `budget_status` 置 `COMPUTING` 后**立即提交**，让前端看得到「计算中」（同 repair-260918 B-5 的「执行中可见」手法）；③ **插队**：不等后台循环排到它；④ 幂等：已在算（`COMPUTING`）时第二次调用不重复算、不撞 `uq_mpr_version_material`（AC-21）；⑤ 算之前做带锁复核，版本已作废 ⇒ 不写、返回可读提示（AC-20）。 |
| **B-8** | AC-8, AC-9, AC-11 | **批量试算（通过前守门）**。端点见 `api.md`：入参一批 `reviewId`，对其中 `budget_status ∈ (QUEUED, FAILED)` 的逐条试算（复用 B-7 的实现），返回**每条的成功/失败与失败原因**。🚫 **本端点只算不升版**，不产生 `material_price_update_job`（AC-9 验的就是这条）。 |
| **B-9** | AC-8, AC-10, AC-11, AC-22 | **影响面接口承接未计算项 + 返回逐料号金额**。现有影响面 `impact`（`PriceAdjustReviewService:400-446`）扩展：返回体按 `api.md §3` 增加「未计算条数」「计算失败条数 + 料号清单」与 **`materials[]`（逐料号 `col-default` 比对列的现值/调整后/差异/状态）**；🚫 既有字段一个不删（现网确认框依赖）；🚫 `materials[]` 须**批量取**（`review_id IN (...)` 一条），不许在 `impact` 现有的逐审核循环里再加查询 —— 该循环已在 `BL-0308 ③` 的循环查库清单上，本次不得恶化；🔒 **「通过并升版」入口 `approve` 增加守门**：入参里若含 `budget_status` 仍为 `QUEUED`/`COMPUTING` 的审核行 ⇒ **拒绝**并返回可读原因（前端应先调 B-8）。这是 AC-8/AC-11 的服务端保证，🚫 不许只靠前端拦。 |

## D 组 · 状态机重定义（R-6）

> 🚨 这三处是 `repair-260918`（2026-09-20 刚结案）的交付物，判定地基被本次改动直接抽掉。**改之前先读** `dev-docs/task-260729-…/repair-260918-大单升版超时与涨跌率显示/backtask.md` 的 B-8 / B-11 / B-12 三项。

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-10** | AC-12, AC-16 | **续跑判据改写**。现 `loadScopeContext:222-225` 的 `alreadyProcessed` = 「本版本已有审核行」∪「指针已指向本版本」。解耦后**有审核行 ≠ 已算过** ⇒ 改为：**进池阶段**跳过「已有审核行 ∨ 指针已指向本版本」的料号（判定不必重做）；**试算阶段**只认 `budget_status ∈ (READY, FAILED)` 为已处理，`QUEUED` / `COMPUTING` **必须续算**。<br>日志「续跑跳过=n」的口径同步改（AC-16 会查这行日志）。 |
| **B-11** | AC-17 | **作废即停覆盖 `QUEUED`**。`MaterialPriceReview.voidPendingByVersion`（`:123`）按 `status=PENDING` 作废 —— 确认 `QUEUED` 行的 `status` 确为 `PENDING`（B-2 如此写入）故天然覆盖；**并补**：试算循环在每组开始前、每条开始前做带锁复核，版本已作废即停（沿用现 `VersionNotPendingException` 机制），并行下**每个 worker 各自停**。 |
| **B-12** | AC-16, AC-18 | **启动收尾处理 `COMPUTING`**。`PriceAdjustStartupRecovery.resumeBudgets` 扩展：启动时把**本实例启动时刻之前**留下的 `COMPUTING` 行重置为 `QUEUED`（进程已死，不可能还在算），再交给续跑。🚫 SQL 条数与行数无关（批量 UPDATE）。🔒 受 `cpq.price-adjust.startup-recovery.enabled` 开关约束（repair-260918 B-14），`application-test.properties` 仍为 `false`。 |

## E 组 · 列表呈现的服务端部分（R-2）

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-13** | AC-1, AC-4 | **列表返回计算状态 + 未计算行不返回假金额**。`ReviewListItemDTO` 已有 `budgetStatus`（repair-260918 B-16 加了 `budgetError`）⇒ 确认 `QUEUED` 能原样返回；**并保证** `budget_status != READY` 时 `quoteCostAdjusted` / `diffAdjusted` 等金额字段返回 **`null`**（🚫 不许返 0 —— 0 会被前端渲染成真值）。🔒 `toListItem` 内**不得新增查询**（它本就在 `BL-0308 ③` 的循环查库清单上，本次不恶化）。 |
| **B-14** | AC-4 | **「仅看超阈值」筛选排除未计算行**。现 `list` 的 `breachedOnly` 走 `breachedCount > 0`（`:83-85`）—— 未计算行 `breachedCount` 为 0 会被静默当作「未超阈值」。改为该筛选下**同时要求 `budget_status='READY'`**，并在返回体带上「因未计算而未纳入筛选的条数」（字段见 `api.md`），供前端出提示文案。 |

---

## 自检要求（交付回报必须逐条给出，缺一条视为未完成）

1. **N+1 自检**（`docs/rules/backend.md §1`）：逐个列出本次新增/改动的循环体，声明其中无查库；给出 **AC-3** 口径下 `CUST-0004`（4558 料号）与 `CUST-0001`（1 料号）两次生成的 `[perf] budget-enqueue … sql=` 数值，**必须相等**。
2. **AC-13 自检**：自己先跑一遍连生两版的哈希比对，把两次哈希值贴进回报。**不等 ⇒ 不许报完成**，按 B-6 回落并报主线。
3. 服务在 **worktree 自己的临时后端**上启动无错误日志（带 `-Dquarkus.scheduler.enabled=false -Dcpq.price-adjust.startup-recovery.enabled=false`）；改动端点返 200/401、无 500。🚫 **不要拿共享 8081 当自检对象**，它跑的是主仓代码。
4. `mvnw test` 在 **worktree 的 `cpq-backend/`** 里跑（默认连 `cpq_db_test`），给出点名测试类的计数与退出码。
5. 🚨 **本任务不新增迁移。** 发现必须迁移 ⇒ 停下报主线。
6. 🚨 **遇 `CLAUDE.md §3.2` 红线（清库 / DROP / 批量 DELETE / 改已应用迁移）一律停下报主线，你没有批准权。**
