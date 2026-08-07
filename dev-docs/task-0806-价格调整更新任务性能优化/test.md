# test.md · 价格调整更新任务性能优化 · 测试用例

- 对应文档：`需求文档.md`（验收唯一标准，AC-1~AC-10）/ `backtask.md`（§3 业务规则、§7 自检项、§8 T1~T7）/ `api.md`（settings 契约）
- 编写日期：2026-08-06　编写人：cpq-tester（同期编写，闸门 A 前主线审核）
- 环境：分支 `feat/task-0806-price-adjust-job-perf`（worktree `/home/joii/project/cpq/.claude/worktrees/task-0806-perf`）；dev DB `10.177.152.12:5432/cpq_db_0724`；后端 8081（共享）
- 用例字段：**编号｜对应 AC-x/FR-x｜前置数据｜步骤｜期望结果｜实际结果（留空待填）｜优先级**（P0 = 阻断合并 / P1 = 合并前必须过 / P2 = 时间不够可延后，需在 `test-report.md` 显式登记未跑原因）

---

## 0. 🚨 方法论声明（先读，决定本文档怎么用）

> **「抽样 0 不一致」不是安全证明。**
> job `06b54e9a` 也跨 3 个建单日却 0 不一致 —— 因为那几天没跨过价格版本边界，`f_material_element_price` 返回了相同值。**只跑它就宣布 PASS，会让 D-3 那个"5/18 张单写坏"的缺陷带着"已验证"标签进生产。** 说明问题的是**结构性事实（分组键必须含取价基准日）+ 存在性反例（`c2915208` 真的会写坏）**，不是样本通过率。
>
> **本文档的强制推论**：
> 1. `c2915208-4327-4818-a9bf-05fdca905c6a`（TC-COR-01）是**唯一不可替代**的用例 —— 其余三个 job 通过不能顶替它，`test-report.md` 里必须单独列出它的结果，不得并入"4 个 job 全过"这种汇总表述而模糊掉。
> 2. 任何时候只跑 `06b54e9a` / `1b7208ab`（两个"日期集中、3 组"的 job）就宣布"MD5 比对通过"，**视为未测**，不得作为合并依据。
> 3. 新增/改动任何 driver 组件的 SQL 视图后，`c2915208` 必须**重新跑一遍**（不能复用旧结果），因为「哪个视图跨价格版本边界」是数据相关的，不是一次性验证完就永久安全。

---

## 1. 测量手法（供执行阶段复用）

### 1.1 Method A · 探针 MD5 比对（内存态，dryRun rollback，零 DB 风险）

- 临时只读探针端点：`POST /api/cpq/tmp-perf/verify-job`，`@RoleAllowed({"SYSTEM_ADMIN"})`，请求体 `{ "jobId": "<uuid>" }`。
- 内部对该 job 的每个 line item：
  1. **逐项路径**（= FR-2/FR-3 默认 `precomputed=null` 的现状行为）：`materialVersionUpgradeService.upgrade(lineItemId, job.versionId, /*dryRun*/true, /*precomputed*/null)`，取其内部 `cardSnapshotService.refreshCostingCardValuesForLine(...)`（或等价重载）算出的 `costingCardValues`/`quoteCardValues`/内存态 `subtotal`，各自 `md5(jsonNode.toString())`。
  2. **分组路径**：先按 FR-1 分组键 `(costingCardTemplateId, priceBaseDate)` 调用 `BomTreeRenderService.render()` 预渲染，取该 lineItem 对应的 `precomputed` 结果，再 `upgrade(lineItemId, job.versionId, true, precomputed)`，同样算三个 md5。
  3. 整个探针方法体包在**一个 `@Transactional` 且强制 rollback**（用 `TransactionSynchronizationRegistry.setRollbackOnly()` 或等价手段）里，保证 DB 零痕迹。
- 返回体：`[{ lineItemId, costingMd5Sequential, costingMd5Grouped, costingMatch, quoteMatch, subtotalEqual, ... }]`。
- **用途**：AC-1/AC-2/AC-6/AC-4/AC-5 的快速反复验证（开发期天天跑，不脏库）。
- **不覆盖**：`quotation_price_revision` 三列快照（那是真实非 dryRun 提交路径才会写的凭据），见 Method B。

### 1.2 Method B · 真实入库影子对照（覆盖 AC-3 的四项落库产物，含 `quotation_price_revision`）

给定基准 job（已在库中，历史已执行过一次），步骤：

1. **快照 S0**：把该 job 涉及的 4 处数据整份 dump 到临时 scratch 表（`CREATE TABLE _t0806_baseline_<jobid>_item AS SELECT * FROM material_price_update_job_item WHERE job_id='<jobId>'`，同理对 `quotation_line_item`（按 lineItemId 集合）、`quotation_price_revision`（按 quotationId 集合）各建一份）。
2. **强制逐项模式重跑（"改造前"口径，复用 FR-2/FR-3 的 `precomputed=null` 默认行为）**：
   - `UPDATE material_price_update_job_item SET status='WAITING' WHERE job_id='<jobId>'`（测试专用直接 SQL，不经业务 API —— `retry` 端点语义是"重试失败项"，不是"批量重跑已成功批次"）。
   - 通过临时执行触发端点（同批临时端点，`POST /api/cpq/tmp-perf/run-job-sequential`）以**强制不做分组预渲染**的方式跑一遍 `executeJob` 等价逻辑（即对每个 item 都传 `precomputed=null`），得到真实落库结果 R1（四项产物）。
   - dump R1。
3. **回滚到 S0**：用 scratch 表整份 `UPDATE ... FROM _t0806_baseline_...` 写回，确保下一步是从同一起点开始（包括 `quotation_price_revision` 若在步骤 2 中被封存/新建，需要连带清理新增行）。
4. **批量模式重跑（"改造后"口径）**：同样先转 `WAITING`，触发**真实** `POST /api/cpq/price-adjust/reviews/approve` 或直接调 `PriceAdjustJobExecutionService#executeJob(jobId)`（走 FR-1~FR-8 完整批量路径），得到 R2。
5. **比对 R1 vs R2**：四项产物逐字节 MD5 相同 = 通过。
6. **收尾**：无论通过与否，把 DB 从 S0 恢复（scratch 表回填），并 `DROP` scratch 表。**这是共享 dev 库，不允许留脏数据。**

**排除缓存假象（AC-10 性能测量同样适用）**：
- 步骤 2（逐项/慢路径）和步骤 4（批量/快路径）之间，若不清缓存，步骤 4 会白蹭步骤 2 暖出来的 `DataLoader.resultCache` / `priceBaseDateCache`，导致批量路径"看起来更快"是缓存的功劳而非分组算法的功劳。
- **正确做法**：正确性验证（MD5）顺序不敏感，可不管；**性能验证（AC-10/TC-PERF）必须让"待测路径排最前面跑"或在两次测量之间 `touch` 一个 java 文件强制 Quarkus 重启清缓存**，两种路径各自都在冷缓存下测一次，不能只有一个是冷的。

### 1.3 SQL 计数插桩（AC-10 辅助 + N+1 自检）

- 在 `SqlViewExecutor.execute` / `executeAllRows` 和 `DataLoader.executeQuery` 打日志 `[perf-t0806] phase=<render|s0|s1..s9> job=<jobId> item=<itemId> sql_count=<n>`，用相位 marker（进入/离开每个 S 步骤时打点）区分环节。
- 断言：批量路径下，同一 `(templateId, priceBaseDate)` 分组内 `render()` 触发的 SQL 条数应为**该分组的组件数**（约 17 条，不随分组内 item 数增长）；逐项路径下应为 `item 数 × 17`。

### 1.4 探针纪律

- 探针端点（`/api/cpq/tmp-perf/*`）**一律临时**，实现期间用，**T7 收尾必须全部删除**，删除后用 `curl` 确认 404。
- 探针一律 `@RoleAllowed({"SYSTEM_ADMIN"})`，不得放宽。
- Method B 涉及真实写库，**任何一次执行完（无论断言通过与否）都必须跑收尾步骤 6 恢复 S0**，避免污染共享库；建议每次 Method B 跑完立刻 `SELECT count(*) FROM material_price_update_job_item WHERE job_id='<jobId>' AND status NOT IN (原始状态集合)` 做污染自检。

---

## 2. 基准数据

| job id | 项数 | 建单日分组数 | 用途 | 强制性 |
|---|---|---|---|---|
| `c2915208-4327-4818-a9bf-05fdca905c6a` | 24 | 6 | **已知反例**（旧方案曾写坏 5/18 张单）| **AC-2 强制，不可替代** |
| `6c0aebc8-3a89-4b79-aae0-ff8a768a696b` | 29 | 6 | 日期分散 + 当前库最大批量 | 必测 |
| `06b54e9a-305a-4401-95f9-2910b4599026` | 18 | 3 | 日期集中（0 不一致但不构成安全证明，见 §0）| 必测 |
| `1b7208ab-0168-41c6-8fe6-a833de985de2` | 17 | 3 | 日期集中 | 必测 |

⚠️ **文档口径不一致，执行阶段必须先核实**：`需求文档.md` §1.1 表记 `c2915208`=24 项、`6c0aebc8`=29 项；同文档 §5.5 表记 `c2915208`=18 项/6 组、`6c0aebc8`=22 项/6 组 —— 两张表对同一 job 的项数不一致（疑似两次不同时间点的测量，期间 job 数据发生了变化，如部分 item 被 retry 或库状态变动）。本 test.md 的项数以 **`backtask.md` §8 T1 表**（24/29/18/17）为准，但**执行阶段第一步必须先 `SELECT count(*) FROM material_price_update_job_item WHERE job_id=...` 现查真实值**，三处口径都对不上要提请 PM/主线澄清，不能三选一自行拍板。

**前置 SQL**（每个 job 通用，取该 job 的 line item / quotation / 建单日分组信息）：

```sql
-- 1) job 基本信息 + 真实项数（现查，不信文档）
SELECT j.id, j.customer_no, j.status, j.version_id, count(i.id) AS item_count
FROM material_price_update_job j
JOIN material_price_update_job_item i ON i.job_id = j.id
WHERE j.id = '<jobId>'
GROUP BY j.id, j.customer_no, j.status, j.version_id;

-- 2) 每个 item 对应的 line item / quotation / 分组键
SELECT i.id AS item_id, i.line_item_id, i.status, i.warn_code,
       li.quotation_id, li.costing_card_template_id, li.subtotal,
       q.customer_id, q.created_at::date AS price_base_date
FROM material_price_update_job_item i
JOIN quotation_line_item li ON li.id = i.line_item_id
JOIN quotation q ON q.id = li.quotation_id
WHERE i.job_id = '<jobId>'
ORDER BY q.created_at::date, li.id;

-- 3) 建单日分组数（应与「建单日分组数」列一致）
SELECT count(DISTINCT q.created_at::date) AS date_groups
FROM material_price_update_job_item i
JOIN quotation_line_item li ON li.id = i.line_item_id
JOIN quotation q ON q.id = li.quotation_id
WHERE i.job_id = '<jobId>';
```

---

## 3. 用例总览（快速索引）

| 编号 | 标题 | AC/FR | 优先级 | 方法 |
|---|---|---|---|---|
| TC-COR-01 | `c2915208` 四项落库产物全量比对（反例，不可替代） | AC-1/AC-2/AC-3 | **P0** | Method B |
| TC-COR-02 | `6c0aebc8` 四项落库产物全量比对（最大批量） | AC-1/AC-3 | P0 | Method B |
| TC-COR-03 | `06b54e9a` 四项落库产物全量比对 | AC-1/AC-3 | P1 | Method B |
| TC-COR-04 | `1b7208ab` 四项落库产物全量比对 | AC-1/AC-3 | P1 | Method B |
| TC-COR-05 | Method A 快速扫描（4 job 全量，开发期回归用） | AC-1 | P1 | Method A |
| TC-FAIL-01 | 注入必然失败组件 → 仅相关 item FAILED | AC-4 | **P0** | Method A/B |
| TC-FAIL-02 | 失败 item 的 `costing_card_values` 与逐项路径一致 | AC-4 | P1 | Method A |
| TC-DEG-01 | 视图塞 `:quotationId` → 判定 `PER_LINE_ITEM` + WARN | AC-5 | **P0** | 单元 + Method A |
| TC-DEG-02 | 降级后实际按逐项渲染（SQL 条数验证） | AC-5/FR-8 | P1 | 插桩 |
| TC-DET-01 | 同 job 连跑两次，MD5 全相同 | AC-6 | **P0** | Method B ×2 |
| TC-DET-02 | 连跑三次巩固（AP-51 手法：刷新 3 次稳定） | AC-6 | P2 | Method A ×3 |
| TC-S0-01 | 开关 `true`：行为与改造前逐位一致，含 `warn_code` 落库 | AC-7 | **P0** | Method B |
| TC-S0-02 | 开关 `false`：不产生 `warn_code`，单项耗时 ↓ ≥0.3s | AC-7 | **P0** | Method B + 计时 |
| TC-S0-03 | 开关热切换：`PUT` 后不重启即时生效 | AC-7/FR-9 | P1 | API |
| TC-AUD-01 | 真实库判定 = 1 `PER_PRICE_BASE_DATE` + 16 `GLOBAL` + 0 `PER_LINE_ITEM` | AC-8 | **P0** | 单元/API |
| TC-AUD-02 | 判定表 4 分支单测（含"解析失败→逐项"） | AC-8/§5.2 | P0 | 单元 |
| TC-AUD-03 | `sql_template` 为 NULL / 视图记录缺失 → `PER_LINE_ITEM` + WARN | AC-8/§5.2 边界 | P1 | 单元 |
| TC-REG-01 | `refreshCostingCardValuesForLine` 旧签名调用方零影响 | AC-9 | **P0** | 回归 |
| TC-REG-02 | `upgrade()` `precomputed=null` 路径逐位不变 | AC-9 | **P0** | 回归 |
| TC-REG-03 | 预算 dryRun 路径逐位不变 | AC-9 | **P0** | 回归 |
| TC-PERF-01 | 4 job 端到端实测耗时（如实记录，无门槛） | AC-10 | P1 | 计时 |
| TC-PERF-02 | 方案 A vs 方案 B 在 `c2915208`（6 组）上耗时对比 | AC-10/D-4 | P1 | 计时 |
| TC-PERF-03 | render 阶段 SQL 条数：批量 vs 逐项 | AC-10/D-2 | P2 | 插桩 |
| TC-API-01 | `GET settings` 返回含新字段 `subtotalGuardEnabled` | FR-9/api.md §1 | P1 | API |
| TC-API-02 | `PUT` 只提交 `subtotalGuardThreshold` → 开关不被重置 | api.md §2 ⚠️ | **P0** | API |
| TC-API-03 | `PUT` 只提交 `subtotalGuardEnabled` → 阈值不被重置 | api.md §2 ⚠️ | **P0** | API |
| TC-API-04 | `PUT` 同时提交两字段 → 均更新 | api.md §2 | P1 | API |
| TC-API-05 | `PUT` 请求体 `{}` → 两字段都不改 | api.md §2 边界 | P1 | API |
| TC-API-06 | `PUT subtotalGuardThreshold` 负数 → `400`（既有校验回归） | api.md §2 | P2 | API |
| TC-API-07 | `GET` 权限：`PRICING_MANAGER` → 200 | api.md §1 | P1 | 权限 |
| TC-API-08 | `GET` 权限：`SYSTEM_ADMIN` → 200 | api.md §1 | P1 | 权限 |
| TC-API-09 | `GET` 权限：无相关角色 → 403 | api.md §1 | P1 | 权限 |
| TC-API-10 | `PUT` 权限：`PRICING_MANAGER` → 403（写仅 `SYSTEM_ADMIN`） | api.md §2 | **P0** | 权限 |
| TC-API-11 | `PUT` 权限：`SYSTEM_ADMIN` → 200 | api.md §2 | P1 | 权限 |
| TC-API-12 | 未登录 `GET`/`PUT` → 401 | api.md §2 错误码 | P2 | 权限 |
| TC-CONC-01 | 批次中途 item 被 supersede 转 `STALE` → 预渲染结果丢弃不写入 | FR-7 | **P0** | Method B |
| TC-CONC-02 | 同一 job 并发触发两次 `executeJob` | 探索性（非 AC 直接覆盖） | P1 | 并发 |
| TC-CONC-03 | 分组内 `customerId` 不唯一 → 抛错 | FR-6 | **P0** | 单元 |
| TC-CONC-04 | `PUT settings` 并发写不同字段 | api.md §2 边界 | P2 | 并发 |
| TC-CONC-05 | job 执行中调用 `/retry` | 探索性 | P2 | 并发 |
| TC-EDGE-01 | job 中仅 1 个 item（单组退化） | 边界 | P1 | Method A |
| TC-EDGE-02 | job 中 0 个 `WAITING` item（全部已终态）→ 循环空转不报错 | 边界 | P1 | API |
| TC-EDGE-03 | 分组恰好 = job 全部建单日各 1 张单（分组数 = item 数） | 边界 | P2 | Method A |
| TC-EDGE-04 | S0 阈值边界：diff 恰好 = threshold（等于不算超限？需核实既有语义） | 边界 | P2 | 单元 |
| TC-EDGE-05 | 预渲染跨事务传递对象类型校验（无 Hibernate 托管实体） | 边界/§4 backtask | P2 | 单元 |
| TC-EDGE-06 | 未来给某视图加维度占位符后自动降级（模拟"新增违规视图"） | AC-8 保守兜底原则 | P1 | 单元 |
| TC-FE-01 | RG-1：报价单详情核价单子视图渲染无变化 | 回归 | **P0** | 人工 + Method B 互证 |
| TC-FE-02 | RG-2：核价工作台冻结模式不空白不清零 | 回归（AP-38 历史事故） | **P0** | 人工 |
| TC-FE-03 | RG-3：比对视图三行块数值不变 | 回归 | P1 | 人工 |
| TC-REG-N1 | N+1 自检：本次新增循环体 SQL 条数不随 N 增长 | CLAUDE.md 强制项 | P1 | 插桩 |
| TC-REG-04 | 既有端点响应结构不变（`jobs`/`retry`） | api.md 无变更但受影响清单 | P2 | API |

**用例总数：44 条**（P0=15 / P1=19 / P2=10）

---

## 4. 用例详情

### G1 · 核心正确性 —— MD5 逐字节比对（AC-1 / AC-2 / AC-3）

#### TC-COR-01 · `c2915208` 四项落库产物全量比对（反例，不可替代）★

- **对应**：AC-1、AC-2（强制）、AC-3
- **前置数据**：job `c2915208-4327-4818-a9bf-05fdca905c6a`；先跑 §2 前置 SQL 现查真实项数/分组数并记录在 `test-report.md`。
- **步骤**：按 §1.2 Method B 全流程（S0 快照 → 逐项模式跑出 R1 → 回滚 S0 → 批量模式跑出 R2 → 比对 → 收尾恢复）。
- **期望结果**：
  - 该 job 全部 line item 的 `costing_card_values`、`quote_card_values`、`quotation_line_item.subtotal` 逐条 MD5（或数值）相同，不一致数 = **0**。
  - 涉及的 `quotation_price_revision` 行三列快照（`quote_card_values`/`costing_card_values`/`snapshot_rows`）MD5 相同。
  - 6 个建单日分组均被覆盖到（不能只测到部分分组就宣布通过 —— 用 §2 SQL 3 核实分组数与预期一致）。
- **实际结果**：
- **优先级**：**P0（唯一不可替代，不得被其他 job 结果替代或合并汇总掩盖）**

#### TC-COR-02 · `6c0aebc8` 四项落库产物全量比对（最大批量）

- **对应**：AC-1、AC-3
- **前置数据**：job `6c0aebc8-3a89-4b79-aae0-ff8a768a696b`（当前库最大批量，同为 6 组）
- **步骤**：同 TC-COR-01 的 Method B 流程
- **期望结果**：四项产物 0 不一致
- **实际结果**：
- **优先级**：P0

#### TC-COR-03 · `06b54e9a` 四项落库产物全量比对

- **对应**：AC-1、AC-3（**注意**：本用例通过 ≠ 安全证明，不得替代 TC-COR-01，见 §0）
- **前置数据**：job `06b54e9a-305a-4401-95f9-2910b4599026`（3 个建单日分组）
- **步骤**：同上
- **期望结果**：四项产物 0 不一致
- **实际结果**：
- **优先级**：P1

#### TC-COR-04 · `1b7208ab` 四项落库产物全量比对

- **对应**：AC-1、AC-3
- **前置数据**：job `1b7208ab-0168-41c6-8fe6-a833de985de2`（3 个建单日分组）
- **步骤**：同上
- **期望结果**：四项产物 0 不一致
- **实际结果**：
- **优先级**：P1

#### TC-COR-05 · Method A 快速扫描（4 job 全量，开发期回归用）

- **对应**：AC-1（辅助验证，非最终验收依据）
- **前置数据**：4 个基准 job
- **步骤**：对每个 job 调探针 `POST /api/cpq/tmp-perf/verify-job`（Method A，dryRun rollback），比对 `costingMd5Sequential` vs `costingMd5Grouped`
- **期望结果**：4 个 job 全部 item 的 `costingMatch=true`；**本用例仅用于开发期高频自测**（不脏库、秒级出结果），最终验收仍以 TC-COR-01~04 的 Method B 真实入库比对为准，`test-report.md` 里不得只写这一条就宣布 AC-1/AC-3 通过。
- **实际结果**：
- **优先级**：P1

---

### G2 · 失败隔离（AC-4）

#### TC-FAIL-01 · 注入必然失败组件 → 仅相关 item FAILED

- **对应**：FR-5、AC-4
- **前置数据**：选一个基准 job（建议用 `06b54e9a`，3 组，便于观察分组边界）；在该 job 覆盖的某个 driver 组件的 `component_sql_view.sql_template` 里人为注入语法错误（如把 `FROM` 拼成 `FRON`），仅影响其中一个分组能覆盖到的一个组件。
- **步骤**：
  1. 触发该 job 批量执行（真实非 dryRun，需先按 Method B 建 S0 快照以便收尾还原）。
  2. 观察各 item 最终状态。
- **期望结果**：
  - 只有**该出错组件所属分组**内、且该组件确实参与渲染的 item 状态 = `FAILED`；不属于该分组，或该分组内不依赖出错组件的 item 状态 = `SUCCESS`。
  - **不得出现**"一个组件挂 → 整批全 FAILED"（这是 FR-5 要防的退化）。
- **实际结果**：
- **优先级**：**P0**
- **收尾**：还原 `sql_template`，恢复 DB 到 S0。

#### TC-FAIL-02 · 失败 item 之外，其余 item 的 `costing_card_values` 与逐项路径逐字节一致

- **对应**：AC-4 后半句
- **前置数据**：复用 TC-FAIL-01 的现场
- **步骤**：对 TC-FAIL-01 中 `SUCCESS` 的 item，额外跑一次 Method A 探针比对
- **期望结果**：这些 item 的批量路径结果与逐项路径结果 MD5 相同（证明"回退逐项"没有引入脏值，只是精确圈定了失败范围）
- **实际结果**：
- **优先级**：P1

---

### G3 · 安全降级（AC-5）

#### TC-DEG-01 · 视图塞 `:quotationId` → 判定 `PER_LINE_ITEM` + WARN

- **对应**：FR-4、§5.2 判定表、AC-5
- **前置数据**：复制一份任意现有 `GLOBAL` 级别 driver 组件的 `component_sql_view`（新建一条测试专用记录，不改动生产在用的那条），`sql_template` 里加入 `WHERE ... = :quotationId`
- **步骤**：调维度审计器对该组件跑判定
- **期望结果**：返回级别 = `PER_LINE_ITEM`；日志出现 WARN（内容含组件标识 + 判定原因）
- **实际结果**：
- **优先级**：**P0**

#### TC-DEG-02 · 降级后实际按逐项渲染（SQL 条数验证）

- **对应**：AC-5、FR-8
- **前置数据**：复用 TC-DEG-01 的测试组件，挂到一个测试用模板/job（不影响生产模板）
- **步骤**：按 §1.3 SQL 插桩跑一次批量执行，统计该组件的渲染 SQL 触发次数
- **期望结果**：该组件的 SQL 触发次数 = job 内该组件出现的 item 数（逐项），而不是分组数（说明确实没有被错误地按分组共享）
- **实际结果**：
- **优先级**：P1
- **收尾**：删除测试专用 `component_sql_view` 记录。

---

### G4 · 确定性（AC-6）

#### TC-DET-01 · 同 job 连跑两次，MD5 全相同

- **对应**：AC-6
- **前置数据**：任选一个基准 job（建议 `c2915208`，覆盖面最广）
- **步骤**：按 §1.2 Method B 跑一次批量模式得 R2a，还原 S0，再跑一次批量模式得 R2b
- **期望结果**：R2a 与 R2b 四项产物逐字节 MD5 全部相同（**这条专门防非确定性竞态**，D-1 记录的 2026-06-22 事故就是"单跑看不出、连跑两次才暴露"）
- **实际结果**：
- **优先级**：**P0**

#### TC-DET-02 · 连跑三次巩固（AP-51 手法）

- **对应**：AC-6（加强）
- **前置数据**：同 TC-DET-01，改用 Method A 探针（省事，连跑 3 次不脏库）
- **步骤**：对同一 job 连续调 3 次探针
- **期望结果**：3 次 `costingMd5Grouped` 全部相同（呼应 AP-51 "刷新 3 次行数稳定"的自检手法，防止是"运气好凑巧两次一样"）
- **实际结果**：
- **优先级**：P2

---

### G5 · S0 开关（AC-7，⚠️ D-5 待用户拍板，T6 本轮暂缓但用例先写全）

#### TC-S0-01 · 开关 `true`：行为与改造前逐位一致，含 `warn_code` 落库

- **对应**：AC-7 前半句
- **前置数据**：`PUT /price-adjust/settings {"subtotalGuardEnabled": true}`；选一个历史已知会触发 `SUBTOTAL_MISMATCH`（若当前库 123 个 job item 只响过 1 次，需先找到那条或人为构造一个会触发差异的场景，如手工改一个料号的核价卡片值制造分歧）
- **步骤**：跑批量升版，观察对应 item
- **期望结果**：
  - `material_price_update_job_item.warn_code = 'SUBTOTAL_MISMATCH'` 正常落库（与改造前行为一致，不因批量化而丢失）
  - 该 item 其余产物值与开关关闭时（若同批同时对照）逐位一致（守卫只影响 `warn_code`，不改变实际写入的核价值 —— 需求文档 §5.2 明确"warn-only 不阻断"）
- **实际结果**：
- **优先级**：**P0**

#### TC-S0-02 · 开关 `false`：不产生 `warn_code`，单项耗时下降 ≥0.3s

- **对应**：AC-7 后半句
- **前置数据**：`PUT /price-adjust/settings {"subtotalGuardEnabled": false}`
- **步骤**：跑同一批 item（或对照批次），计时 + 检查 `warn_code`
- **期望结果**：
  - 全部相关 item 的 `warn_code` 为 `NULL`（S0 段被短路，未执行）
  - 单项耗时相比开关 `true` 下降 ≥ 0.3s（对应现状基线 "S0 口径守卫 14.3%/0.46s"，允许有测量误差但方向和量级须成立）
- **实际结果**：
- **优先级**：**P0**

#### TC-S0-03 · 开关热切换：`PUT` 后不重启即时生效

- **对应**：FR-9、api.md §2 "即时生效，无需重启服务"
- **前置数据**：无
- **步骤**：`PUT enabled=true` → 立即跑一个 item 观察 S0 是否执行 → 不重启 Quarkus，紧接着 `PUT enabled=false` → 立即再跑一个 item
- **期望结果**：两次行为立刻切换，无需等待/重启（验证"每次读库不缓存"的既有范式被沿用）
- **实际结果**：
- **优先级**：P1

---

### G6 · 维度审计器（AC-8）

#### TC-AUD-01 · 真实库判定 = 1 `PER_PRICE_BASE_DATE`(COMP-0049) + 16 `GLOBAL` + 0 `PER_LINE_ITEM`

- **对应**：AC-8
- **前置数据**：当前库全部（历史）driver 组件，不新增/不修改任何 `component_sql_view`
- **步骤**：对全库 driver 组件跑一次审计器，收集每个组件的判定结果
- **期望结果**：输出恰好 = **1 个 `PER_PRICE_BASE_DATE`（组件 `COMP-0049`，`$wl_ys_bom_view`）+ 16 个 `GLOBAL` + 0 个 `PER_LINE_ITEM`**（§5.2 实测基线）
- **实际结果**：
- **优先级**：**P0**

#### TC-AUD-02 · 判定表 4 分支单测

- **对应**：§5.2 判定表、AC-8
- **前置数据**：4 组构造的 `sql_template` 字符串（无占位符 / 仅 `:priceBaseDate` / 含 `:quotationId` 或 `:lineItemId` / 语法损坏或指向不存在的视图名）
- **步骤**：JUnit 单测逐一喂给审计器
- **期望结果**：分别返回 `GLOBAL` / `PER_PRICE_BASE_DATE` / `PER_LINE_ITEM`+WARN / `PER_LINE_ITEM`+WARN（"解析失败"分支）
- **实际结果**：
- **优先级**：P0

#### TC-AUD-03 · `sql_template` 为 NULL / 视图记录缺失 → `PER_LINE_ITEM` + WARN

- **对应**：§5.2 边界"解析失败/读不到视图"
- **前置数据**：构造一个 `component_sql_view` 记录 `sql_template=NULL`，另构造一个 driver 组件指向不存在的 `sql_view_name`
- **步骤**：跑审计器
- **期望结果**：两种情况均**保守兜底**为 `PER_LINE_ITEM` + WARN（"判不出来就逐项，绝不猜"）
- **实际结果**：
- **优先级**：P1

---

### G7 · 原调用方零影响（AC-9）

#### TC-REG-01 · `refreshCostingCardValuesForLine` 旧签名调用方零影响

- **对应**：FR-2、AC-9
- **前置数据**：`QuotationAdminResource.task0729CostingViewFixPreview`（`QuotationAdminResource.java:146`）这条既有调用路径；任选一条 line item
- **步骤**：改造前后各调用一次该 admin 预览端点（或用 Method A 同款 dryRun 手法在新代码上对照"改造前应有值"）
- **期望结果**：旧签名 `refreshCostingCardValuesForLine(UUID lineItemId)` 返回/写入结果逐位不变（无新增参数导致的行为漂移）
- **实际结果**：
- **优先级**：**P0**

#### TC-REG-02 · `upgrade()` `precomputed=null` 路径逐位不变

- **对应**：FR-3、AC-9
- **前置数据**：任选一条 line item + 目标版本
- **步骤**：直接调 `materialVersionUpgradeService.upgrade(lineItemId, versionId, dryRun=true)`（不传新增的 `precomputed` 参数，或显式传 `null`）
- **期望结果**：S0~S9 全流程行为与改造前逐位一致（这是本任务对"其余调用方"的核心承诺）
- **实际结果**：
- **优先级**：**P0**

#### TC-REG-03 · 预算 dryRun 路径逐位不变

- **对应**：AC-9、需求文档 §2.2"预算阶段本期不做"
- **前置数据**：一个待审核的调价 review（`PriceAdjustReviewResource` 的 `/impact` 或 `/{reviewId}/recompute-budget` 端点触及的路径）
- **步骤**：改造前后分别跑一次预算计算
- **期望结果**：预算结果（每料号 review 的差额/影响金额）逐位不变，证明批量化改造**没有**意外波及预算路径（需求文档明确预算路径本期不动，`buildCostingCardValues` 的整单 prefetch 也不启用）
- **实际结果**：
- **优先级**：**P0**

---

### G8 · 性能实测（AC-10，无门槛，如实记录）

#### TC-PERF-01 · 4 job 端到端实测耗时

- **对应**：AC-10
- **前置数据**：4 个基准 job（**注意 §1.2 缓存排除纪律**：每次测量前 `touch` 一个 java 文件重启 Quarkus 清缓存，避免前一个 job 暖出的缓存影响下一个 job 的计时）
- **步骤**：分别计时"改造前逐项模式"（Method B 步骤 2）与"改造后批量模式"（Method B 步骤 4），每个 job 各测 1 次（若时间允许测 2 次取更保守值）
- **期望结果**：**如实记录**每个 job 的两种模式耗时、提速倍数；**本条不预设必须达到 25s / 2.3x**，达不到就在 `test-report.md` 写清楚天花板在哪个环节（对照 §5.4/§5.5 现状占比表定位）
- **实际结果**：
- **优先级**：P1

#### TC-PERF-02 · 方案 A vs 方案 B 在 `c2915208`（6 组）耗时对比

- **对应**：AC-10、D-4（"若评审认为 A 改动面过大，可只交付 B"）
- **前置数据**：`c2915208`（6 个建单日分组，是方案 A 相对方案 B 收益最大的场景）
- **步骤**：若 T5（方案 A）已实现，分别测 B-only 和 A+B 耗时；若 T5 未实现/转二期，本用例标记"不适用"并说明原因
- **期望结果**：如实记录对比数据，供后续判断是否值得保留方案 A 的改动面
- **实际结果**：
- **优先级**：P1

#### TC-PERF-03 · render 阶段 SQL 条数：批量 vs 逐项

- **对应**：AC-10 辅助、D-2（"不是 N+1，是 N 个组件 N 条查询"）
- **前置数据**：任一基准 job
- **步骤**：按 §1.3 插桩统计
- **期望结果**：逐项模式 SQL 条数 ≈ `item数 × 17`；批量模式（方案 B）≈ `分组数 × 17`；方案 A 进一步降到 `17 + 分组数`（仅 `PER_PRICE_BASE_DATE` 那 1 个组件按分组重复，其余 16 个 `GLOBAL` 全批 1 次）
- **实际结果**：
- **优先级**：P2

---

### G9 · settings API 契约与权限（api.md，T6 本轮暂缓，用例先写全）

> ⚠️ 需求文档 T6（S0 开关落库+接口）**本轮实施前需用户对 D-5 拍板**。若拍板结果是"暂缓"，本组用例整体标记"暂不执行"并在 `test-report.md` 注明；若拍板通过，以下用例全部执行。

#### TC-API-01 · `GET settings` 返回含新字段

- **对应**：api.md §1、FR-9
- **前置数据**：`SYSTEM_ADMIN` 或 `PRICING_MANAGER` token
- **步骤**：`GET /api/cpq/price-adjust/settings`
- **期望结果**：`200`，响应体含 `subtotalGuardThreshold`（既有）+ `subtotalGuardEnabled`（新增，布尔）+ `updatedAt`
- **实际结果**：
- **优先级**：P1

#### TC-API-02 · `PUT` 只提交 `subtotalGuardThreshold` → 开关不被重置 ⭐

- **对应**：api.md §2 ⚠️ 标注的"两字段均可单独提交"陷阱
- **前置数据**：先 `PUT {"subtotalGuardEnabled": true}` 确保开关处于已知状态 `true`
- **步骤**：再 `PUT {"subtotalGuardThreshold": 0.02}`（**不带 `subtotalGuardEnabled` 字段**）
- **期望结果**：`GET` 回读 `subtotalGuardThreshold=0.02` **且** `subtotalGuardEnabled` 仍为 `true`（未被静默重置为 `false`）
- **实际结果**：
- **优先级**：**P0**（这是 api.md 明确点名的实现陷阱：`null=不改` vs `null=置默认` 的经典分叉）

#### TC-API-03 · `PUT` 只提交 `subtotalGuardEnabled` → 阈值不被重置

- **对应**：同上，反向验证
- **前置数据**：先 `PUT {"subtotalGuardThreshold": 0.05}` 确保阈值处于已知状态
- **步骤**：再 `PUT {"subtotalGuardEnabled": false}`（不带阈值字段）
- **期望结果**：`GET` 回读 `subtotalGuardEnabled=false` 且 `subtotalGuardThreshold` 仍为 `0.05`
- **实际结果**：
- **优先级**：**P0**

#### TC-API-04 · `PUT` 同时提交两字段 → 均更新

- **对应**：api.md §2 正常流
- **前置数据**：无
- **步骤**：`PUT {"subtotalGuardThreshold": 0.03, "subtotalGuardEnabled": true}`
- **期望结果**：`GET` 回读两字段均为新值
- **实际结果**：
- **优先级**：P1

#### TC-API-05 · `PUT` 请求体 `{}` → 两字段都不改

- **对应**：api.md §2 边界延伸
- **前置数据**：记录当前值
- **步骤**：`PUT {}`
- **期望结果**：`200`，`GET` 回读值与 `PUT` 前完全相同（`updatedAt` 是否刷新需核实既有阈值字段的实现口径，若刷新也算合理，非阻断项）
- **实际结果**：
- **优先级**：P1

#### TC-API-06 · `PUT subtotalGuardThreshold` 负数 → `400`

- **对应**：api.md §2 错误码（既有校验，不变）
- **前置数据**：无
- **步骤**：`PUT {"subtotalGuardThreshold": -1}`
- **期望结果**：`400`
- **实际结果**：
- **优先级**：P2（回归既有行为，非本次新增）

#### TC-API-07 / TC-API-08 · `GET` 权限：`PRICING_MANAGER` / `SYSTEM_ADMIN` → 200

- **对应**：api.md §1 鉴权
- **前置数据**：两种角色 token 各一个（现网 `test_finance_c87a27ab`=PRICING_MANAGER、`admin`=SYSTEM_ADMIN，参考记忆 task-0729 环境基线）
- **步骤**：分别 `GET`
- **期望结果**：均 `200`
- **实际结果**：
- **优先级**：P1

#### TC-API-09 · `GET` 权限：无相关角色 → 403

- **对应**：api.md §1 鉴权边界
- **前置数据**：一个既非 `PRICING_MANAGER` 也非 `SYSTEM_ADMIN` 的角色 token
- **步骤**：`GET`
- **期望结果**：`403`
- **实际结果**：
- **优先级**：P1

#### TC-API-10 · `PUT` 权限：`PRICING_MANAGER` → 403

- **对应**：api.md §2"写权限不放宽"
- **前置数据**：`PRICING_MANAGER` token
- **步骤**：`PUT {"subtotalGuardEnabled": true}`
- **期望结果**：`403`（读写权限不对称，读可 `PRICING_MANAGER`，写仅 `SYSTEM_ADMIN`）
- **实际结果**：
- **优先级**：**P0**（权限收紧点，漏测会导致越权写入生产调价参数）

#### TC-API-11 · `PUT` 权限：`SYSTEM_ADMIN` → 200

- **对应**：api.md §2
- **前置数据**：`SYSTEM_ADMIN` token
- **步骤**：`PUT`
- **期望结果**：`200`
- **实际结果**：
- **优先级**：P1

#### TC-API-12 · 未登录 `GET`/`PUT` → 401

- **对应**：api.md §2 错误码
- **前置数据**：无 token
- **步骤**：分别请求
- **期望结果**：均 `401`
- **实际结果**：
- **优先级**：P2

---

### G10 · 并发 / 重复提交

#### TC-CONC-01 · 批次中途 item 被 supersede 转 `STALE` → 预渲染结果丢弃不写入

- **对应**：FR-7
- **前置数据**：一个多分组 job（建议 `06b54e9a`，3 组）
- **步骤**：
  1. 触发批量执行。
  2. 在预渲染阶段完成、item 循环尚未处理到某个特定 item 之前（可通过临时 `Thread.sleep` 或断点式延迟手段人为制造窗口，或利用真实并发：另开一个请求把该 item 关联的报价单/版本做出会触发 supersede 的操作，如提交了同料号更高优先级的新调价审核），使该 item 的状态在循环处理到它之前变为 `STALE`（由乐观锁/版本机制天然产生，或该行 `row_version` 被并发修改）。
  3. 观察最终结果。
- **期望结果**：该 item 保持 `STALE`（或按业务规则应有的终态），**不**被预渲染结果覆盖写入 `costing_card_values`；其余未受影响 item 正常 `SUCCESS`
- **实际结果**：
- **优先级**：**P0**

#### TC-CONC-02 · 同一 job 并发触发两次 `executeJob`

- **对应**：探索性（非 AC 直接覆盖，测试工程师主动补充的并发风险点）
- **前置数据**：一个 job，全部 item 处于 `WAITING`
- **步骤**：几乎同时（如用两个并发线程/两个 curl 同时发起）触发同一 `jobId` 的执行（通过 review approve 或临时触发端点）
- **期望结果**：每个 item 最终只被处理一次，不出现同一 item 被两次 `upgrade` 产生冲突写入或死锁；两次触发中应有一次被拒绝/短路（如 job 状态已是 `RUNNING` 则第二次调用应识别并跳过或报错，而不是两条线程各自跑一遍批量预渲染）
- **实际结果**：
- **优先级**：P1
- **⚠️ 备注**：若代码目前**没有**对"同一 jobId 并发触发"做互斥保护，这很可能是一个**既有缺陷**（非本次任务引入，但本次预渲染分组会放大风险面——两条线程各自缓存不同分组结果，交叉写入的复杂度比逐项模式更高）。若测出问题，需与 PM/架构确认：是否属本次任务验收范围，还是登记新 BACKLOG 条目转二期。**不得因为"不在 AC 列表里"就跳过不测**——这正是测试工程师该主动指出的 PRD 覆盖盲区。

#### TC-CONC-03 · 分组内 `customerId` 不唯一 → 抛错

- **对应**：FR-6
- **前置数据**：人为构造一个"假批次"：取两个不同客户名下、但恰好 `costingCardTemplateId` 相同、建单日相同的 line item，塞进同一次批量渲染调用（绕过真实 job 数据，直接单测/集成测 `BomTreeRenderService` 分组预渲染逻辑）
- **步骤**：调用分组预渲染入口
- **期望结果**：抛出明确异常（不是静默取第一个 customerId 继续跑），异常信息包含冲突的两个 customerId
- **实际结果**：
- **优先级**：**P0**

#### TC-CONC-04 · `PUT settings` 并发写不同字段

- **对应**：api.md §2 边界（探索性）
- **前置数据**：无
- **步骤**：两个并发请求几乎同时到达：A 只带 `subtotalGuardThreshold`，B 只带 `subtotalGuardEnabled`
- **期望结果**：最终两个字段都被各自的请求成功写入（不存在"后写者覆盖前写者未提交字段"的问题，因为设计上 `null=不改`，两者字段互不冲突；若实现是"读-改-写"非原子，理论上存在竞态丢更新的可能，需验证）
- **实际结果**：
- **优先级**：P2

#### TC-CONC-05 · job 执行中调用 `/retry`

- **对应**：探索性
- **前置数据**：一个正在 `RUNNING` 的 job
- **步骤**：执行期间调 `POST /jobs/{jobId}/retry`
- **期望结果**：应有合理拒绝（如 409）或幂等处理，不应引发状态机混乱
- **实际结果**：
- **优先级**：P2

---

### G11 · 边界值

#### TC-EDGE-01 · job 中仅 1 个 item（单组退化）

- **对应**：边界，隐含于 AC-1
- **前置数据**：构造/挑选一个仅含 1 个 `WAITING` item 的 job（或用真实小 job）
- **步骤**：跑批量路径
- **期望结果**：分组预渲染正常处理"1 组 1 item"的退化情况，不因分组逻辑除零/空指针等报错；耗时不应比逐项模式更慢（分组开销可忽略）
- **实际结果**：
- **优先级**：P1

#### TC-EDGE-02 · job 中 0 个 `WAITING` item（全部已终态）

- **对应**：边界
- **前置数据**：一个全部 item 已是 `SUCCESS`/`FAILED` 的历史 job
- **步骤**：对该 job 触发执行（若业务允许重复触发）
- **期望结果**：循环空转，不报错，不产生任何变更；`finalizeJob` 正常收尾
- **实际结果**：
- **优先级**：P1

#### TC-EDGE-03 · 分组数 = item 数（每个 item 各自不同建单日）

- **对应**：边界（方案 B 收益最差场景）
- **前置数据**：构造/挑选一个"建单日高度分散"的 job（每个 item 建单日都不同）
- **步骤**：跑批量路径，测耗时 + 正确性
- **期望结果**：正确性仍 0 不一致（分组退化为逐项不应影响正确性）；性能上退化为约等于逐项模式（如实记录，方案 A 应能缓解这个退化场景，见 D-4）
- **实际结果**：
- **优先级**：P2

#### TC-EDGE-04 · S0 阈值边界：diff 恰好等于 threshold

- **对应**：边界（需先核实既有语义：`>` 还是 `>=` 触发 warn）
- **前置数据**：构造一个 diff 恰好等于当前 `subtotalGuardThreshold` 的场景
- **步骤**：跑 `upgrade`，开关设为 `true`
- **期望结果**：与改造前的既有边界语义一致（**本用例先确认改造前代码本身的边界行为，再断言改造后一致**，不是凭空定义新语义）
- **实际结果**：
- **优先级**：P2

#### TC-EDGE-05 · 预渲染跨事务传递对象类型校验

- **对应**：backtask §4"纯 JsonNode、无 Hibernate 托管实体"
- **前置数据**：无
- **步骤**：代码审查 + 单测断言预渲染结果类型为 `Map<UUID, Map<String, ArrayNode>>`，不含任何 Panache 实体引用
- **期望结果**：类型检查通过，`REQUIRES_NEW` 事务边界穿越不触发 `LazyInitializationException`
- **实际结果**：
- **优先级**：P2

#### TC-EDGE-06 · 未来给某视图加维度占位符后自动降级

- **对应**：AC-8 保守兜底原则、FR-4 风险登记"高"
- **前置数据**：模拟"未来有人给 `GLOBAL` 组件的视图加了 `:lineItemId`"这一变更
- **步骤**：审计器重新扫描
- **期望结果**：该组件判定从 `GLOBAL` 自动降级为 `PER_LINE_ITEM`，无需人工同步任何白名单/硬编码列表（判定完全从 SQL 文本动态推导）
- **实际结果**：
- **优先级**：P1（风险登记表标"高"的那条，值得列为正式用例而非仅口头提醒）

---

### G12 · 前端回归 / 历史功能回归

#### TC-FE-01 · RG-1：报价单详情 → 核价单子视图渲染无变化

- **对应**：`fronttask.md` RG-1
- **前置数据**：TC-COR-01~04 涉及的报价单中任选 2~3 张（覆盖至少一张来自 `c2915208`）
- **步骤**：改造前后各打开一次详情页核价单子视图，逐页签核对数值/行数
- **期望结果**：与改造前一致；**与 Method B 的 MD5 比对互为印证**（MD5 全等则渲染必然一致，本项是人眼兜底，防"MD5 比的不是渲染真正读的那份"）
- **实际结果**：
- **优先级**：**P0**

#### TC-FE-02 · RG-2：核价工作台冻结模式不空白不清零

- **对应**：`fronttask.md` RG-2（历史事故基线：2026-08-03 曾"17 个 tab 清零却全报 SUCCESS"）
- **前置数据**：一张已提交（冻结）的核价单
- **步骤**：改造后打开该核价工作台
- **期望结果**：不空白、页签不清零；结合 TC-FAIL-01 的失败注入场景，确认"该报错时真的报错"而不是"清零却报成功"
- **实际结果**：
- **优先级**：**P0**

#### TC-FE-03 · RG-3：比对视图三行块数值不变

- **对应**：`fronttask.md` RG-3
- **前置数据**：一张有比对视图配置的报价单
- **步骤**：改造前后打开比对视图
- **期望结果**：报价/核价/差异三行块数值不变（比对视图读的是同一份冻结快照，理论上应随 AC-1/AC-3 通过而自动满足，本用例做终端层面确认）
- **实际结果**：
- **优先级**：P1

#### TC-REG-N1 · N+1 自检：本次新增循环体 SQL 条数不随 N 增长

- **对应**：`CLAUDE.md`"后端严禁 N+1 查库"强制项
- **前置数据**：本次改动新增的循环（`executeJob` 分组分发循环、维度审计器扫描 driver 组件集合的循环、装配阶段按 lineItem 合并结果的循环）
- **步骤**：人工过一遍每个新增循环体，确认无 repository 调用/`SqlViewExecutor.execute`/懒加载 getter；对"分组分发"循环额外做批量对照（如构造一个 5 item 和一个 20 item 的等价 job，SQL 条数应保持"分组数相关"而非"item 数相关"的常数关系）
- **期望结果**：SQL 条数与 item 数无关，只与分组数相关（符合 D-2 结论 + 本任务改造目标）
- **实际结果**：
- **优先级**：P1

#### TC-REG-04 · 既有端点响应结构不变

- **对应**：api.md"无变更但受影响的既有端点"清单
- **前置数据**：无
- **步骤**：`GET /jobs`、`GET /jobs/{jobId}`、`POST /jobs/{jobId}/retry`、`POST .../items/{itemId}/retry` 各调一次
- **期望结果**：响应体结构（`JobDTO`/`JobItemDTO` 字段）与状态码与改造前一致，仅内部执行更快
- **实际结果**：
- **优先级**：P2

---

## 5. 收尾核查（非独立 TC，执行完整批用例后必做）

- [ ] 全部临时探针端点（`/api/cpq/tmp-perf/*`）已删除，`curl` 确认 404
- [ ] `git status` 干净（无遗留探针代码 / 无遗留测试专用 `component_sql_view` 记录）
- [ ] 所有 Method B 用例执行后 DB 均已恢复 S0（用 §2 前置 SQL 重新查一遍涉及 job/item/quotation_line_item/quotation_price_revision，确认与最初状态一致）
- [ ] `test-report.md` 的 AC 对照表逐条打勾，TC-COR-01（`c2915208`）单独列出、不与其他 job 结果合并表述

---

## 6. AC 覆盖对照表

| AC | 用例 |
|---|---|
| AC-1 | TC-COR-01~05 |
| AC-2 | TC-COR-01（唯一） |
| AC-3 | TC-COR-01~04 |
| AC-4 | TC-FAIL-01, TC-FAIL-02 |
| AC-5 | TC-DEG-01, TC-DEG-02 |
| AC-6 | TC-DET-01, TC-DET-02 |
| AC-7 | TC-S0-01, TC-S0-02, TC-S0-03 |
| AC-8 | TC-AUD-01, TC-AUD-02, TC-AUD-03, TC-EDGE-06 |
| AC-9 | TC-REG-01, TC-REG-02, TC-REG-03 |
| AC-10 | TC-PERF-01, TC-PERF-02, TC-PERF-03 |
| FR-6 | TC-CONC-03 |
| FR-7 | TC-CONC-01 |
| api.md null=不改 | TC-API-02, TC-API-03 |
| 权限 | TC-API-07~12 |
| 前端回归 | TC-FE-01~03 |
| N+1 | TC-REG-N1 |
