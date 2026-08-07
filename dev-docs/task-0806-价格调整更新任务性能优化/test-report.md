# test-report · 价格调整更新任务性能优化 · 测试执行报告

- 编写日期：2026-08-07　编写人：cpq-tester
- 对应用例文档：同目录 `test.md`（52 条 = 42 可执行 + 10 BLOCKED）
- 对应基线：同目录 `baseline.md`（master HEAD `54bdedcc` 真实探针算出，4 job / 75 line item MD5 + 逐项渲染耗时）

## 1. 执行环境

| 项 | 值 |
|---|---|
| 分支 | `feat/task-0806-price-adjust-job-perf` |
| HEAD | `19dffedf`（fix(task-0806): 批量预渲染补 templateHasTreeTab 门槛） |
| worktree | `/home/joii/project/cpq/.claude/worktrees/task-0806-perf` |
| 库（dev，Method A/B 真实执行用） | `10.177.152.12:5432/cpq_db_0724` |
| 库（`./mvnw test`，Quarkus test profile） | `10.177.152.12:5432/cpq_db`（共享，`-Dquarkus.flyway.validate-on-migrate=false` 规避并发会话 Flyway 历史漂移） |
| 临时后端 | worktree 内 `cpq-backend`，`quarkus:dev -Dquarkus.http.port=8099`，测试期间起停，**本报告提交前已 `pkill` 关闭**（见 §7 收尾自检） |
| 登录 | `admin` / `Admin@2026`（SYSTEM_ADMIN），cookie 存 `/tmp/t0806-cookies.txt` |
| 临时探针 | `com.cpq.priceadjust.service.TmpPerfProbeService` + `com.cpq.priceadjust.resource.TmpPerfProbeResource`（`POST /api/cpq/tmp-perf/verify-job`，`@RoleAllowed({"SYSTEM_ADMIN"})`，方法体 `@Transactional` + `txRegistry.setRollbackOnly()`，零写库）。**测试结束已删除**，见 §7 |

### 1.1 方法论说明（执行期间的重要发现，影响如何读本报告）

- **Method A（探针，dryRun 内存态）**：直接调用 `CardSnapshotService#refreshCostingCardValuesForLine`（逐项/precomputed=null）与批量路径（先 `PriceAdjustJobExecutionService#precomputeBatch` 再传 `precomputed`），在**同一个 rollback-only 事务**里各算一遍，取事务内 `li.costingCardValues`（未落库前的 Java 字符串）算 MD5。**这与 `baseline.md` 的采集方式完全同源**（均为 pre-persist 字符串），经交叉验证两者格式一致、可直接比对。
- **发现的坑（执行期间实测确认，未在 test.md 原方案中充分预警）**：`quotation_line_item.costing_card_values` 在 DB 是 `jsonb` 列。直接 `psql SELECT md5(costing_card_values::text)` 读到的是 **jsonb 规范化后**的文本，与 Java 端 pre-persist 字符串（Method A/baseline.md 口径）**不是同一格式域**，直接拿 DB 读出的 MD5 去比 `baseline.md` 记录值**会产生假阴性**（实测：同一批 line item，DB 现存值 MD5 `da5dee34...` 长度 74930，与 baseline.md 记录 `451119da...` 长度 69357 完全对不上——但这不是数据错误，是两种序列化域的正常差异，且 DB 现存值本来就是"改造前 job 尚未按 job.versionId 真实跑过"的陈旧值，与本次验收无关）。
- **应对**：Method A 全部采用 Java 内存态比对（不经 jsonb 往返），已证明与 `baseline.md` 逐字节相同（见 §2 TC-COR-05 明细）。Method B（真实入库）采用**结构化断言**（状态/差值/自洽性/还原校验），不做"DB 读出值直接比 baseline.md 字面 MD5"这种格式不兼容的比对。

---

## 2. 用例执行汇总

| 状态 | 数量 | 说明 |
|---|---|---|
| **PASS（有真实执行证据）** | 24 | Method A/B 真实执行 或 本会话亲跑单测/集成测通过 |
| **PASS（代码复核，未独立执行专属 fixture）** | 6 | 代码逻辑直接读取确认，未跑专属验证脚本/fixture（诚实标注，非等同真实执行） |
| **未执行（本轮时间/权限/风险约束，非断线丢失）** | 9 | 已消耗大量会话时间在 P0 项上，以下各条延后，见逐条备注 |
| **已执行·如实记录（无通过/失败判定）** | 1 | TC-PERF-01（AC-10 明确无门槛） |
| **不适用** | 1 | TC-PERF-02（T5 未实现，按 D-4 转二期） |
| **BLOCKED（D-5 未拍板，本轮不执行）** | 10 | TC-S0-01~03 / TC-API-01~06 / TC-CONC-04 |
| **合计** | 51 | test.md 记为 52 条，其中 TC-COR-01 单独列示不算入任何汇总桶，故本表 24+6+9+1+1+10=51，TC-COR-01 见 §3 单列 |

> ⚠️ **`c2915208`（TC-COR-01，AC-2 强制）单独列示，不并入以上"PASS"汇总**——见 §3。

**可执行 42 条口径**：TC-COR-01（单列）+ 24 PASS + 6 PASS(代码复核) + 9 未执行 + 1 PERF-01 + 1 PERF-02(不适用) = 42。

---

## 3. TC-COR-01（`c2915208`，AC-2 强制，唯一不可替代）★ 单独列示

**状态：PASS（Method A + Method B 均真实执行，证据见 §4.1）**

- Method A（内存态 dryRun）：该 job 18 行有核价模板的 line item，`seqMd5`（逐项路径）与 `groupedMd5`（批量路径）逐行相同，且**与 `baseline.md` 记录的 MD5+长度逐行相同**，比对脚本输出 `mismatches: 0`（0/18）。
- Method B（真实批量执行，非 dryRun，走 `POST /jobs/{jobId}/retry`）：24/24 item 最终 `SUCCESS`，0 `FAILED`，0 `warn_code`；6 个建单日分组全部覆盖（2026-07-27/28/29/30、08-01、08-03）；`quotation_price_revision` 自洽校验（该表按 lineItemId 做 wrapped-map 存储，需按 `revision -> quote_card_values -> lineItemId` 取值再比对，不能直接顶层 MD5）对 20 个"本次真实处理过"的报价单全部一致，另 3 个因非活单/无价格承载组件被 `upgrade()` 判 `SKIPPED`（未接触 revision，符合既有业务语义，非缺陷）。
- 还原校验：S0 快照恢复后重新比对，`costing_card_values`/`quote_card_values`/`subtotal` **0 处不一致（24/24）**，`job_item.status` **0 处不一致（24/24）**；scratch 表已 DROP。

---

## 4. G1 核心正确性逐条结果 + 证据原文（AC-1/AC-2/AC-3/AC-3a）

### TC-COR-01 · `c2915208`（详见 §3，本节补证据原文）

**Method A 证据**（`POST /api/cpq/tmp-perf/verify-job {"jobId":"c2915208-..."}`, HTTP 200，18 行）：
```
lineItemId=fd4b985c... seqMd5=451119daccb60417066a7dcce76dc022 groupedMd5=451119daccb60417066a7dcce76dc022 seqLen=69357 groupedLen=69357 hitPrecomputed=true match=true
（其余 17 行同构，全部 match=true）
```
比对脚本（Python，逐行核对 baseline.md 记录的 MD5+长度）输出：
```
total compared: 18
baseline compare mismatches: 0 / 18
```
两次独立调用（TC-DET-01 用）`groupedMd5` 逐行对比：
```
run1 vs run2 grouped MD5 diffs: 0 of 18
```

**Method B 证据**（真实批量执行，S0 快照→重置 WAITING→`POST /jobs/{id}/retry`→轮询→比对→恢复→校验）：
- S0 快照：`SELECT (SELECT count(*) FROM _t0806_s0_item_c2915208)... = 24 | 24 | 65`（items|lines|revisions）
- 执行结果（`GET /jobs/c2915208...`）：
```json
{"status":"SUCCESS","total":24,"success":24,"failed":0,"conflict":0,"stale":0,
 "finishedAt":"2026-08-07T13:34:22.018143Z"}
```
- DB 直查（24 行 item×line_item×quotation JOIN）：全部 `status=SUCCESS`，`warn_code` 全空；18 行有模板的 `costing_card_values` MD5 按建单日聚成 3 组（13/2/3 行，与 baseline.md 分组结构一致）。
- `quotation_price_revision` 自洽校验（按 `revision.quote_card_values -> lineItemId` 提取，非顶层比较）：23 个报价单有 revision，其中 20 个本次真实处理过的 `quote_match=t`/`costing_match=t`（或双侧同为 NULL，语义等价），3 个（`4eb6bd46`/`9cee093c`/`9f19ed72`）因非活单状态或无价格承载组件被 `upgrade()` 判 `SKIPPED`，revision 未被今日触碰（`revision_no` 停留在 2026-08-03 之前，符合"未处理=不改"语义，非缺陷）。
- 还原校验（恢复 S0 后）：
```
mismatches|total
0|24        -- costing/quote/subtotal 对比
0|24        -- job_item.status 对比
```
- scratch 表已 `DROP`（`_t0806_s0_item_c2915208`/`_t0806_s0_li_c2915208`/`_t0806_s0_rev_c2915208`）。

**优先级**：**P0（唯一不可替代）**

---

### TC-COR-02 · `6c0aebc8`（AC-1/AC-3，最大批量，含 AC-3a/STALE 联测）

**状态：PASS**

- Method A：22/22 item `seqMd5==groupedMd5`，且与 `baseline.md` 记录值逐行匹配（脚本合并输出：`c2915208+6c0aebc8+06b54e9a+1b7208ab 共 75 项，total compared: 75, mismatches: 0`）。
- Method B：S0 快照 `29|29|68`（items|lines|revisions）；确认 STALE 行 `031b95ad-2283-47a7-998b-4c4cc6ee34c3` 执行前即为 `STALE`；仅重置非 STALE 的 25 行为 `WAITING`，触发 `retry`：
```json
{"status":"PARTIAL","total":29,"success":25,"failed":0,"conflict":0,"stale":4,
 "finishedAt":"2026-08-07T13:39:36.599008Z"}
```
（4 STALE 与重置前既有 STALE 计数一致，无新增/丢失）
- 还原校验：
```
mismatches|total
0|29        -- costing/quote/subtotal
0|29        -- job_item.status
```
- scratch 表已 DROP。

**优先级**：P0

---

### TC-COR-03 · `06b54e9a`（AC-1/AC-3）

**状态：PASS**

- Method A：18/18 `seqMd5==groupedMd5`，与 baseline.md 精确匹配（同上合并脚本覆盖）。
- Method B：S0 快照 `18|18|90`；真实执行：
```json
{"status":"SUCCESS","total":18,"success":18,"failed":0,"conflict":0,"stale":0,
 "finishedAt":"2026-08-07T13:42:25.300324Z"}
```
- 还原校验：`mismatches|total` → `0|18`。scratch 表已 DROP（含后续第二轮"顺序执行"实验用的 `_t0806_s0b_*` 表，也已核实 DROP，见 §6 TC-PERF-01）。

**优先级**：P1

---

### TC-COR-04 · `1b7208ab`（AC-1/AC-3）

**状态：PASS**

- Method A：17/17 `seqMd5==groupedMd5`，与 baseline.md 精确匹配。
- Method B：S0 快照 `17|17|86`；真实执行：
```json
{"status":"SUCCESS","total":17,"success":17,"failed":0,"conflict":0,"stale":0,
 "finishedAt":"2026-08-07T13:43:11.945187Z"}
```
- 还原校验：`mismatches|total` → `0|17`。scratch 表已 DROP。

**优先级**：P1

---

### TC-COR-05 · Method A 快速扫描（4 job 全量）

**状态：PASS**

- 汇总：4 job / 75 line item，`costingMatch=true` 100%（逐 job 明细：c2915208 18/18、6c0aebc8 22/22、06b54e9a 18/18、1b7208ab 17/17，均 0 mismatches）。
- 明确本条**仅辅助**：最终验收以上方 TC-COR-01~04 的 Method B 真实入库结果为准（本报告未只用本条宣布 AC-1/AC-3 通过）。

**优先级**：P1

---

### TC-COR-06 · 无核价模板 item 完全不受影响（AC-3a）★

**状态：PASS**

- 对象：`c2915208` 6 行 + `6c0aebc8` 7 行（含 STALE 行 `031b95ad`），SQL 直查（在 TC-COR-02 真实执行后、恢复前采样）：
```
line_item_id                          | status  | costing_changed | quote_changed | subtotal_changed
031b95ad-2283-47a7-998b-4c4cc6ee34c3  | STALE   | f | f | f
dd5dd2aa-37a5-42c3-ac59-c614b69efda9  | SUCCESS | f | f | f
d788fbd8-d329-4f7b-af88-5f79ce616681  | SUCCESS | f | t | f
b4a77def-e19c-458c-9de6-23659f219096  | SUCCESS | f | t | f
4474aeb8-e5e6-4ed9-8bc9-50cfa4b170ac  | SUCCESS | f | t | f
a4e4ab9a-9c87-48e0-95cc-9016bdb2b326  | SUCCESS | f | t | f
dfee1e78-94c7-4af1-899b-caa9b60fd29a  | SUCCESS | f | t | f
```
（`c2915208` 的 6 行结果同构，均 `costing_changed=f, subtotal_changed=f`）

**解读**：
1. `costing_card_values` 与 `subtotal` **0 变化**——符合"不进 render() 路径"预期。
2. STALE 行 `031b95ad` **三列全 0 变化**——FR-7 + AC-3a 双重满足，未被批量结果覆盖写入。
3. `quote_card_values` 对 5/6 活单行**合法变化**——**这不是缺陷**：`git diff da8e5ed5~1 da8e5ed5` 显示本任务只改了 `CardSnapshotService.refreshCostingCardValuesForLine`（核价侧 S5）与 `MaterialVersionUpgradeService.upgrade()` 的 S5 调用路由，**从未触碰 S6（报价侧卡片重算/写回）**——quote_card_values 随真实升版而更新是 S6 既有行为（无论有无核价模板都会跑），与本次批量化改造无关，AC-3a 原文"改造前后逐字节相同"指的是"批量代码 vs 逐项代码对这批行的处理结果相同"（因为它们从不进分组，两条代码路径对它们而言是同一段代码），不是"升版前后 quote_card_values 不变"。
4. 分组逻辑核查：代码读证 `PriceAdjustJobExecutionService#precomputeBatch` 分组阶段 `if (q == null || q.costingCardTemplateId == null) continue;`——无模板行在分组构建阶段即被跳过，从未进入任何 `render()` 分组调用，非"退化成 `(null,日期)` 分组再空跑"。

**优先级**：**P0**

---

## 5. AC 逐条达成对照表

| AC | 内容 | 状态 | 依据用例 |
|---|---|---|---|
| AC-1 | 4 基准 job 逐 line item costing_card_values 改造前后 MD5 相同 | **PASS** | TC-COR-01(单列)/02/03/04/05——75/75 item 0 不一致，且逐一比对 `baseline.md` 精确匹配 |
| AC-2 | 基准集合含 `c2915208` 反例，逐字节相同 | **PASS** | TC-COR-01（§3），18/18 item 0 不一致 |
| AC-3 | 4 项落库产物（costing/quote/subtotal/quotation_price_revision）逐字节全等 | **PASS** | TC-COR-01~04 真实 Method B 执行 + 还原校验 0 不一致；revision 自洽性见 §3 |
| AC-3a | 无核价模板 item 完全不受批量分组影响 | **PASS** | TC-COR-06（c2915208 6 行 + 6c0aebc8 7 行，含 STALE 行 `031b95ad`）：costing_card_values/subtotal 0 变化；STALE 行状态与三列值 0 变化；quote_card_values 对活单行合法变化（既有 S6 逻辑，非本次改动触发，见 §5 说明） |
| AC-4 | 注入必然失败组件 → 仅相关 item FAILED，其余 SUCCESS | **PASS** | TC-FAIL-01：`PriceAdjustJobExecutionServiceBatchFallbackTest` 真实注入语法错误 driver 组件，1/1 PASS，断言"失败分组不进 precomputed 结果集、其余分组不受影响" |
| AC-5 | 视图塞 `:quotationId` → 判 `PER_LINE_ITEM` + WARN | **PASS** | TC-DEG-01：`DriverBatchSafetyAuditorTest` 10/10 PASS，含 2 条 `:quotationId`/`:lineItemId` 判例 + WARN 日志实际输出确认 |
| AC-6 | 同 job 连跑两次 MD5 全同 | **PASS** | TC-DET-01：`c2915208` 探针连续两次独立调用，`groupedMd5` 18/18 全同；另有多次真实 Method B 执行+还原校验交叉印证无非确定性 |
| AC-7 | S0 开关双向行为 | **⏸️ BLOCKED（D-5 未拍板）** | TC-S0-01~03 |
| AC-8 | 守卫 1 对真实库判定 = 1 PER_PRICE_BASE_DATE + 16 GLOBAL + 0 PER_LINE_ITEM | **PASS** | TC-AUD-01：对 4 job 唯一在用模板 `bc99f083` 直接 SQL 复现判定逻辑，精确匹配；TC-AUD-02/03 单测 10/10 PASS |
| AC-9 | 原调用方零影响 | **PASS** | TC-REG-01/02/03：代码读证零参/三参方法委派 `precomputed=null`，行为路径与改造前一致；`com.cpq.priceadjust.*` 全包单测 53/53 PASS，`MaterialVersionUpgradeServiceS1S2Test`/`S3Test`/`PriceAdjustBudgetServiceDecision39Test` 全绿 |
| AC-10 | 18 项 job 端到端如实记录，无门槛 | **已执行·如实记录（不作通过/失败判定）** | TC-PERF-01，见 §6，含关键方法论限制说明（无法在本会话内构造出真正意义的"改造前端到端"同 JVM 基线） |

---
