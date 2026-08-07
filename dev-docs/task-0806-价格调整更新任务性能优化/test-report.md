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

## 4a. G2 失败隔离（AC-4）/ G3 安全降级（AC-5）/ G4 确定性（AC-6）逐条结果

### TC-FAIL-01 · 注入必然失败组件 → 仅相关 item FAILED

**状态：PASS**（单测真实执行，非本会话新写测试——沿用开发方已交付的 `PriceAdjustJobExecutionServiceBatchFallbackTest`，本会话独立重跑确认）

真实执行输出（`./mvnw -o test -Dtest=PriceAdjustJobExecutionServiceBatchFallbackTest ...`）：
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.678 s
```
关键日志（真实注入的语法错误 driver 组件触发异常，被捕获并回退）：
```
WARN [PriceAdjustJobExecutionService] jobId=... 批量预渲染分组 (template=...,priceBaseDate=2026-08-07,items=1)
  失败，回退逐项渲染（FR-5）: 核价树渲染失败：1 个组件 expand 抛异常
  (...BusinessException: driver 路径查询失败:...relation "this_table_definitely_does_not_exist_bf_test" does not exist...)
  为避免残缺数据冒充成功，本次渲染整体失败
```
测试断言（源码 `groupFailureIsolation_andNoTreeTabTemplateIsSkipped`）：模板 A（必然失败）对应 lineItem **不在** `precomputeBatch` 结果集（回退逐项，交给 `upgrade()` 默认路径精确 FAILED）；模板 B（合法 0 行）对应 lineItem **在**结果集（未被模板 A 拖累）；模板 C（无树页签）对应 lineItem 不在结果集（对齐老路径门槛）。三项断言全部通过，`precomputeBatch` 全程不抛异常（异常已在分组级 `REQUIRES_NEW` 事务内被捕获软回退，未导致整批失败）。

**优先级**：**P0**

### TC-FAIL-02 · 失败 item 之外，其余 item 与逐项路径一致

**状态：PASS（受 TC-FAIL-01 现场覆盖，未独立追加 Method A MD5 比对）**

上述单测已直接断言"模板 B 的 lineItem 在批量结果集中"（即未受模板 A 失败影响、正常走批量路径），且 §4 TC-COR-01~05 已用 75/75 item 的 Method A 比对独立证明"批量路径与逐项路径产出逐字节相同"这一更强的普适性结论——两者叠加已覆盖 TC-FAIL-02 的核心诉求。未针对"失败现场"额外单独跑一次 Method A 探针（判定：不需要，因为失败注入不改变未受影响分组的渲染算法本身）。

**优先级**：P1

---

### TC-DEG-01 · 视图塞 `:quotationId` → 判 `PER_LINE_ITEM` + WARN

**状态：PASS**（`DriverBatchSafetyAuditorTest` 真实执行）

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.303 s -- DriverBatchSafetyAuditorTest
```
关键 WARN 日志真实输出（含 `:quotationId`/`:lineItemId` 判例）：
```
WARN [DriverBatchSafetyAuditor] component=e53496b0... viewName=bsa_test_view_... 含 :quotationId/:lineItemId -> PER_LINE_ITEM 强制逐项
WARN [DriverBatchSafetyAuditor] component=075414f3... data_driver_path 为空 -> PER_LINE_ITEM（保守兜底）
WARN [DriverBatchSafetyAuditor] component=cb14a532... driverPath=mat_process.hf_part_no 非 $view 形态，解析不出视图名 -> PER_LINE_ITEM（保守兜底）
WARN [DriverBatchSafetyAuditor] component=4e3383af... viewName=nonexistent_view_xyz 读不到视图/sql_template 为空 -> PER_LINE_ITEM（保守兜底）
```
10 个测试覆盖 §5.2 判定表 4 分支（含 `worstLevelForTemplate_takesMostUnsafeAcrossComponents` 混合分支 + `worstLevelForTemplate_emptyTemplate_isGlobal` 空模板边界）全部通过。

**优先级**：**P0**

### TC-DEG-02 · 降级后实际按逐项渲染（SQL 条数验证）

**状态：PASS（代码复核，未独立插桩计数）**

代码读证：`renderGroupInNewTx` 在 `safetyAuditor.worstLevelForTemplate(key.templateId) == PER_LINE_ITEM` 时直接 `return null`（不算异常，正常跳过），该分组 item 在 `precomputeBatch` 结果集中**不存在**条目，`executeItem` 因而对它们调用 `upgrade(..., precomputed=null)`，内部走 `refreshCostingCardValuesForLine(lineItemId)`（逐项 `render()`）——SQL 条数天然与 item 数同比例增长，而非分组数。**未实测插桩计数**（§1.3 手法本轮未搭建），仅代码结构复核确认逻辑正确。

**优先级**：P1

---

### TC-DET-01 · 同 job 连跑两次，MD5 全相同

**状态：PASS**（真实执行，证据见 §4 TC-COR-01 "两次独立调用"部分）

```
run1 vs run2 grouped MD5 diffs: 0 of 18
```
另有 4 个 job 的 Method B 真实执行 + 还原校验（每次还原后都重新比对 MD5，见 §4 各条 mismatches=0）交叉印证批量路径无非确定性竞态。

**优先级**：**P0**

### TC-DET-02 · 连跑三次巩固

**状态：PASS（轻量，未做严格"同一 job 连续 3 次探针"专项）**

本会话对 c2915208 做了 2 次独立 Method A 调用（0 diff）+ 1 次 Method B 真实执行（与两次 Method A 结果一致），对其余 3 个 job 各做了 1 次 Method A + 1 次 Method B（06b54e9a 额外做了第 2 次 Method B 真实执行，用于时间测量，结果同样 0 mismatch，见 §6），合计每个 job 至少被独立验证 2 次以上、结果全部一致。未做严格"同一批次连续 3 次纯 Method A"的机械重复。

**优先级**：P2

---

## 4b. G6 维度审计器（AC-8）/ G7 零影响（AC-9）逐条结果

### TC-AUD-01 · 真实库判定 = 1 PER_PRICE_BASE_DATE + 16 GLOBAL + 0 PER_LINE_ITEM

**状态：PASS**

先查出 4 个基准 job 唯一在用的核价模板：
```
SELECT DISTINCT q.costing_card_template_id ... → bc99f083-2d64-47d8-875f-d3c005ae5f2e
```
对该模板 17 个 driver 组件直接 SQL 复现判定表逻辑（LIKE 匹配 `:priceBaseDate`/`:quotationId`/`:lineItemId`，与 Java 端 `.contains()` 等价）：
```
16 行 level=GLOBAL
1  行 level=PER_PRICE_BASE_DATE （COMP-0049 | $wl_ys_bom_view）
0  行 level=PER_LINE_ITEM
```
精确匹配 AC-8 预期基线。

**优先级**：**P0**

### TC-AUD-02 · 判定表 4 分支单测

**状态：PASS**（`DriverBatchSafetyAuditorTest` 10/10，见 §4a TC-DEG-01 证据）

**优先级**：P0

### TC-AUD-03 · `sql_template` NULL / 视图缺失 → PER_LINE_ITEM + WARN

**状态：PASS**（同一测试类 `classify_viewNotFound_fallsBackToPerLineItem`/`classify_blankDriverPath_fallsBackToPerLineItem`/`classify_nullDriverPath_fallsBackToPerLineItem` 三个分支均在上述 10/10 中）

**优先级**：P1

---

### TC-REG-01 · `refreshCostingCardValuesForLine` 旧签名零影响

**状态：PASS（代码复核 + 全包单测支撑）**

代码读证：`refreshCostingCardValuesForLine(UUID lineItemId)` 委派 `refreshCostingCardValuesForLine(lineItemId, null)`，`precomputed==null` 分支内部仍走 `templateHasTreeTab` 判断 + `bomTreeRenderService.render(...)`，与改造前逐行一致（`git diff da8e5ed5~1 da8e5ed5` 显示零参方法体只剩一行委派）。

**优先级**：**P0**

### TC-REG-02 · `upgrade()` `precomputed=null` 路径逐位不变

**状态：PASS（代码复核 + 单测支撑）**

代码读证：三参 `upgrade(lineItemId, targetVersionId, dryRun)` 委派四参并传 `null`；S5 分支 `if (precomputed != null) {...} else { cardSnapshotService.refreshCostingCardValuesForLine(lineItemId); }`，`null` 时精确复现改造前调用。真实执行支撑：`MaterialVersionUpgradeServiceS1S2Test`/`MaterialVersionUpgradeServiceS3Test` 本会话真实重跑：
```
Tests run: 2, Failures: 0, Errors: 0 -- MaterialVersionUpgradeServiceS1S2Test (5.203s)
Tests run: 2, Failures: 0, Errors: 0 -- MaterialVersionUpgradeServiceS3Test (0.535s)
```

**优先级**：**P0**

### TC-REG-03 · 预算 dryRun 路径逐位不变

**状态：PASS**

`PriceAdjustBudgetServiceDecision39Test` 本会话真实重跑：
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.224 s
```
代码读证：`PriceAdjustBudgetService` 预算路径调用 `upgrade()` 时全部走三参签名（未见新增 `precomputed` 实参传递），与需求文档"预算路径本期不动"的约束一致。

**优先级**：**P0**

---

## 4c. G8 性能（AC-10）/ G9 API 权限 / G10 并发 / G11 边界 / G12 前端回归逐条结果

### TC-PERF-01 · 4 job 端到端实测耗时（如实记录，无门槛）

**状态：已执行·如实记录（不作通过/失败判定）**

**⚠️ 重要方法论限制（务必先读）**：`baseline.md` 记录的"逐项渲染耗时"（14652~17774ms）**只测量 `render()+buildCostingCardValues` 这一步**（探针 dryRun，非真实入库、非完整 `upgrade()` 全流程），而本节测的是**真实批量执行 `/jobs/{id}/retry` 端到端耗时**（含 S0~S9 全部步骤、真实逐 item `REQUIRES_NEW` 事务提交、异步调度开销）。**两者测量范围不同，不能直接相减算加速比**——本会话未能在同一 JVM 内构造出一个真正"改造前、同样端到端全流程、单线程逐项执行"的可信基线（尝试用逐 item `/job-items/{id}/retry` 循环模拟"逐项"时，发现该端点走 `managedExecutor.runAsync` 异步执行，18 个请求连续发出后会被线程池**并发**处理而非真正单线程顺序执行，实测 18 项仅耗时 4.1s——这不是真实的"逐项对照组"，本报告**不采用该数字**，如实标注舍弃原因）。

**实测数字**（真实批量执行，`retry` 触发 → 轮询 `GET /jobs/{id}` 至非 RUNNING）：

| job | item 数 | 建单日分组数 | 端到端耗时（批量/方案B真实执行） | JVM 缓存状态 |
|---|---|---|---|---|
| `c2915208` | 24 | 6 | ~14-16s（轮询粒度 2s，8 次轮询命中 SUCCESS） | 4 次 Method A 探针预热后 |
| `6c0aebc8` | 29 | 6 | ~16-18s（轮询粒度 2s，9 次轮询命中 PARTIAL） | 同上，续跑 |
| `06b54e9a` | 18 | 3 | **29.24s**（精确计时，`date +%s.%N` 首尾差） | **冷 JVM**（Quarkus 刚 `touch` 重启后第一个真实请求） |
| `1b7208ab` | 17 | 3 | **17.22s**（精确计时） | 暖 JVM（紧接 06b54e9a 之后） |

- backtask.md §6 给的"改造前现状"是 **18 项 job 端到端 58s**（同样是端到端口径，不是 render-only）。本会话 `06b54e9a`（18 项，3 组）冷 JVM 端到端实测 **29.24s**，粗略对比"58s → 29.24s"方向上是提速的，但**这不是同 JVM 严格对照实验**（58s 的原始测量条件、是否同样冷启动、是否同一批数据未知），**不作为确定的加速倍数结论**，仅如实列出两个数字供参考。
- ~25s 目标：`06b54e9a` 冷 JVM 29.24s 略高于目标，`1b7208ab` 暖 JVM 17.22s 低于目标——**同一批量算法在冷/暖 JVM 下耗时差 12 秒**，JIT/连接池预热是本次实测中最大的单一波动因素，比分组算法本身的边际差异更显著。
- **本条不设通过/失败结论**，如实记录到此为止。

**优先级**：P1

### TC-PERF-02 · 方案 A vs 方案 B 耗时对比

**状态：不适用（T5 未实现，按 D-4 转二期）**

**优先级**：P1

### TC-PERF-03 · render 阶段 SQL 条数：批量 vs 逐项

**状态：未执行（本轮时间约束，未搭建 §1.3 插桩）**

代码结构支持定性判断：`precomputeBatch` 对每个 `(templateId,priceBaseDate)` 分组只调用 1 次 `render()`（覆盖该模板全部 17 个 driver 组件），故 3 组的 job（06b54e9a/1b7208ab）理论 SQL 条数应为 `3×17=51`，远低于逐项 `18×17=306`——但**本会话未实测插桂计数**，仅代码读证，不作为已验证结论。

**优先级**：P2

---

### TC-API-07/08/09 · GET 权限（PRICING_MANAGER/SYSTEM_ADMIN/无角色）

- **TC-API-08（SYSTEM_ADMIN → 200）：PASS（真实执行）**
```
curl ... -b admin cookie ... GET /api/cpq/price-adjust/settings
{"subtotalGuardThreshold":0.010000,"updatedAt":"2026-08-06T15:36:56.689107Z"}
```
（响应体**不含** `subtotalGuardEnabled` 字段，进一步印证 T6/D-5 确未落地，BLOCKED 判定成立）
- **TC-API-07（PRICING_MANAGER → 200）：未执行** —— 现网 `test_finance_c87a27ab` 账号密码未知（历史文档未记录明文密码），本会话未尝试通过 `SYSTEM_ADMIN` 的 `/users/{id}/reset-password` 重置（该账号被多个历史测试文档引用为跨会话共享 fixture，重置会破坏其他并发会话，风险大于收益，主动放弃）。仅代码读证 `@RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})` 注解本次未改动。
- **TC-API-09（无角色 → 403）：未执行**（同上，无可用的第三角色账号凭据）

**优先级**：P1（07/09 均为 P1，未执行）

### TC-API-10 · PUT 权限：PRICING_MANAGER → 403

**状态：未执行**（同上，无凭据）。代码读证 `PriceAdjustSettingsResource.put()` 注解 `@RoleAllowed({"SYSTEM_ADMIN"})`，本次改造未触碰。

**优先级**：**P0（未执行，需在有凭据的环境补测）**

### TC-API-11 · PUT 权限：SYSTEM_ADMIN → 200

**状态：PASS（真实执行）**
```
curl ... -b admin cookie ... -d '{"subtotalGuardThreshold":0.01}' -X PUT .../settings
{"subtotalGuardThreshold":0.01,"updatedAt":"2026-08-07T06:46:42.415901151-07:00"}
HTTP 200
```
（同值回写，无副作用变更，仅 `updatedAt` 时间戳刷新——符合既有阈值字段既定行为，非本次新引入）

**优先级**：P1

### TC-API-12 · 未登录 GET/PUT → 401

**状态：PASS（真实执行）**
```
GET  (无cookie) → 401
PUT  (无cookie) → 401
```

**优先级**：P2

---

### TC-CONC-01 · 批次中途 STALE → 预渲染结果丢弃不写入

**状态：PASS（真实 STALE 行覆盖场景），窄时间窗口场景未覆盖**

见 §4 TC-COR-06 证据：真实 STALE 行 `031b95ad`（`6c0aebc8`）批量执行后仍为 `STALE`，`costing_changed=f, quote_changed=f, subtotal_changed=f`（三列 0 变化）。
**"预渲染前已是 STALE"场景已用真实数据验证。"执行过程中途转 STALE"这一更窄的时间窗口场景本轮未覆盖**——未加任何 `Thread.sleep`/断点式测试钩子（诚实标注：未使用任何临时钩子，也未尝试构造该窄窗口，非"尝试后无法复现"，是主动未尝试，时间约束）。

**优先级**：**P0（窄窗口子场景未执行，需补测或改用专项并发测试工具）**

### TC-CONC-02 · 同一 job 并发触发两次 executeJob

**状态：未执行**（时间约束）

**优先级**：P1

### TC-CONC-03 · 分组内 customerId 不唯一 → 抛错

**状态：PASS（代码复核，未独立执行专属 fixture）**

代码读证 `renderGroupInNewTx`：循环比较 `groupItems` 各自 `quotationById.get(li.quotationId).customerId`，首个作为基准，后续不等则 `throw new IllegalStateException(...)`，异常信息含两个冲突 `customerId`。逻辑直接、无歧义，但**本会话未构造双客户同模板同日期的专属 fixture 去实跑触发该异常**。

**优先级**：**P0（未独立执行，代码复核通过）**

### TC-CONC-05 · job 执行中调用 /retry

**状态：未执行**（时间约束）

**优先级**：P2

---

### TC-EDGE-01 · 单组退化

**状态：PASS（增量覆盖，未构造专属单 item job）**

`06b54e9a`/`1b7208ab` 的真实分组内已天然出现 1~2 item 的小分组（如 `06b54e9a` 2026-08-05 分组仅 1 item），随同 TC-COR-03/04 的批量执行一并验证无崩溃、无除零/空指针异常。**未构造"整个 job 只有 1 个 item"的专属场景**。

**优先级**：P1

### TC-EDGE-02 · 0 个 WAITING item → 循环空转不报错

**状态：PASS（真实执行）**

对已完成的 `1b7208ab`（0 WAITING/CONFLICT）触发 `retry`：
```json
before: {"status":"SUCCESS","total":17,"success":17,...,"finishedAt":"2026-08-07T13:44:28..."}
POST /jobs/.../retry → HTTP 202
after:  {"status":"SUCCESS","total":17,"success":17,...,"finishedAt":"2026-08-07T13:49:06..."}
```
计数 17/17/0/0/0 不变，仅 `finishedAt` 刷新，无异常、无数据变更（`loadWaitingItems` 返回空集，循环体不执行）。

**优先级**：P1

### TC-EDGE-03 · 分组数=item数

**状态：未执行**（时间约束）

**优先级**：P2

### TC-EDGE-04 · S0 阈值边界（严格大于）

**状态：PASS（代码复核）**

代码读证 `MaterialVersionUpgradeService`：`if (diff.compareTo(guardThreshold) > 0)` ——严格大于，`diff==threshold` 不触发。此为既有代码，本次改造未触碰比较符。**未独立构造专属边界值单测**。

**优先级**：P2

### TC-EDGE-05 · 预渲染跨事务类型校验

**状态：PASS（代码复核）**

`CardSnapshotService.PrecomputedTreeRows.baseRowsByComponent` 类型为 `Map<String, ArrayNode>`（Jackson 类型，非 Hibernate 托管实体），代码直接读证无懒加载引用。

**优先级**：P2

### TC-EDGE-06 · 未来加维度占位符后自动降级

**状态：PASS**（`DriverBatchSafetyAuditorTest.worstLevelForTemplate_takesMostUnsafeAcrossComponents` 已验证：判定完全从 SQL 文本动态解析，无硬编码白名单，见 §4a TC-DEG-01 10/10 输出）

**优先级**：P1

---

### TC-FE-01/02/03 · 前端回归（RG-1/RG-2/RG-3）

**状态：未执行（本轮未做浏览器/UI 验证）**

原因：本会话临时后端跑在 worktree 独立 8099 端口；前端共享 dev server（5174）通过 Vite proxy 固定指向 **8081（主工作区 master 代码）**，无法反映本分支改动，且本环境未提供 Playwright/浏览器工具用于对本分支后端单独起一套前端做 E2E。

**间接证据（不能替代 UI 验证，仅供参考）**：`ReadonlyProductCard`/`QuotationStep2`/核价工作台均直接读取 `quotation_line_item.costing_card_values`/`quote_card_values`/`subtotal` 渲染；上述 §4 已用 Method A（内存态）+ Method B（真实入库+还原校验）证明这三列在批量代码与逐项代码下逐字节相同——若渲染层无其他变更（本次任务未改任何前端文件，`git diff` 范围仅限 `cpq-backend/src/main/java`），理论上不应有可见差异。**但这是推断，不是执行结果**，建议合并前人工点开至少 1 张 `c2915208` 涉及的报价单核价单子视图做最终肉眼确认。

**优先级**：**P0（TC-FE-01/02 均未执行，建议合并前补做）**

---

### TC-REG-N1 · N+1 自检

**状态：PASS（代码复核）**

逐一读证本次新增的 3 个循环体：
1. `precomputeBatch` 分组循环：循环外先 `QuotationLineItem.list("id in ?1", lineItemIds)` + `Quotation.list("id in ?1", ...)` 各一次 IN 批量查询，循环体内只操作已加载的 Java 对象，不含 repository 调用。
2. `renderGroupInNewTz` 的 customerId 比较循环：纯内存字段比较，`quotationById` 由外层批量加载。
3. `classifyTemplateDriverComponents` 组件遍历：循环体调 `classifyComponent`（单组件精确查 `component_sql_view`，属既有既定查法，非本次新引入的 N+1，且组件数上限几十个）。
未发现新增循环体内嵌套 repository/`SqlViewExecutor.execute` 调用。

**优先级**：P1

### TC-REG-04 · 既有端点响应结构不变

**状态：PASS（真实执行）**

```
GET /jobs?page=1&size=2 → JobDTO 字段：jobId/customerNo/versionNo/triggeredBy/triggeredAt/status/total/success/failed/conflict/stale/finishedAt/notified（与改造前 PriceAdjustJobResource.toDto 逐字段一致）
GET /jobs/{id}/items?page=1&size=2 → JobItemDTO 字段：itemId/quotationId/quotationNo/materialNo/lineItemId/status/errorCode/errorMessage/diffValue/retryCount/updatedAt（与 toItemDto 逐字段一致）
```

**优先级**：P2

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
