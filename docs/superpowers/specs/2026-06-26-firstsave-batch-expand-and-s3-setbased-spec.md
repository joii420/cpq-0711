# 首存收口 Spec：batch-expand Phase1 去白干 + S3 卡片值集合化落库 — 2026-06-26

> 状态：**已对抗式评审定稿 / 待实现**(2026-06-26 cpq-architect 实读代码评审,两修均 GO)。承接 `2026-06-26-firstsave-perf-analysis-report.md` 实锤的两个根因。
>
> **评审定稿要点**：FIX 1 = GO(零回归净赢,"→0.3s" 是 best-case、含 lineItemId 视图模板有 floor,见 §2.4);
> FIX 2 = GO + 3 项强制改已并入:① option ② **两遍 build-then-assign**(autoflush 主路径不触发、但两遍保确定性单 flush,§3.3);
> ② newLineIds 谓词 blank-inclusive(§3.3①);③ 删「上万行 chunking」固定单事务(§3.5)。
> 两修都是**结构性「算了又丢 / 逐行独立事务」**问题，非算力；均有 golden md5 + 持久化等价护栏。
>
> 关联：[[cpq-firstsave-real-perf-measurement]]、[[cpq-savedraft-incremental-snapshot]]、
> `docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md`（§2.5 阶段① per-row SQL 集合化同源）。

## 1. 背景与目标

首存全栈优化已落地（P1 JDBC 批处理 / P0 砍 churn / JEXL cache / P3 lazy-Excel），现状实测（用户单 77 行）：
- **batch-expand 18.9s**、**draft 9-11s（S3 7.7s 为最大段）**。

埋点实锤两个根因（均「白干 / 逐行事务」，见分析报告 §2-3）：
1. **batch-expand**：Phase 1 对 616 个 task 各做一次冷展开，结果**因不是快照被丢弃**，再由 Phase 2 合桶重算（245ms）。Phase 1 的 616 次冷展开 = **18.6s 纯白干**。
2. **draft S3**：逐行卡片值 4.1s，真算值仅 0.23s；S3 循环从**非事务**编排器逐行调 `@Transactional snapshotLineValuesWithUnion` = **77 个独立事务**（begin/2 冷 findById/JSONB UPDATE/commit）。

**目标**：batch-expand 18.9s → ~0.3s；S3 7.7s → ~3s（逐行卡片值 4.1s → ~1s）。合并后首存 ≈ **5s 量级**。
**非目标**：不改三大核心基线渲染契约；不触 lineItemId 维度视图合桶（另议）；不并行化（守非线程安全纪律）。

## 2. 修复一：batch-expand Phase 1「只窥探快照、不展开」

### 2.1 现状代价（`ComponentResource.doBatchExpandPhases` Phase 1，`:271`）
```java
if (hasContext) {
    ExpandDriverResponse snap = componentDriverService.expandWithSnapshot(...);  // ① 读不到快照会真展开
    if (snap != null && "snapshot".equals(snap.driverPath)) { r.data=snap; ...; continue; } // ② 真展开结果≠snapshot
}
phase2.add(i);   // ③ 丢弃真展开结果,塞进 Phase 2 重算
```
`expandWithSnapshot`（`ComponentDriverService:202`）：读快照命中即返；**miss 则 `return expand(...)`（真·逐 task 冷展开，`:237`）**。导入时 616 task 全 miss → Phase 1 冷展开 616 次（18.6s）→ 全丢 → Phase 2 合桶重算（8 桶 245ms）。

### 2.2 改法：抽 `tryReadSnapshot`（只读快照、miss 返 null、不 fallthrough）
- **新增** `ComponentDriverService.tryReadSnapshot(UUID componentId, UUID lineItemId)`：把 `expandWithSnapshot` 的快照读段（`:206-236`）原样抽出，命中返 `ExpandDriverResponse(driverPath="snapshot")`，**miss 返 `null`（不调 `expand`）**。
- `expandWithSnapshot` 改为：`var s = tryReadSnapshot(componentId, lineItemId); return s != null ? s : expand(...);`（行为完全不变，仅重构）。
- **Phase 1**（`:271`）把 `expandWithSnapshot(...)` 换成 `tryReadSnapshot(t.componentId, t.lineItemId)`：命中 → `r.data=s; continue;`；miss → `phase2.add(i)`（**不做真展开**）。

### 2.3 为什么等价
- 命中路径：与原逐位相同（同一快照读）。
- miss 路径：原本「真展开→丢→Phase2 重算」，现「直接 Phase2」——**Phase 2 产出完全不变**（Phase 2 用 `expandMulti`，不吃 `expandCache`，与 Phase 1 是否预热无关）。即只是**停止算了又丢**。
- 护栏：`BatchExpandBucketEquivTest`（合桶 ON==OFF 逐位）+ `BatchExpandSnapshotPrefetchEquivTest`（616 对快照读等价）。

### 2.4 预期 / 风险（含评审 floor 纠偏）
- **best-case：18.9s → ~0.3s**（本单 a341844a 实测 8 组件全 merged，Phase 2 八桶 245ms + Phase 1 窥探）。
- **floor（评审强制标注）**：`canMerge = idxs.size()>=2 && !viewUsesLineItemId`（`:324`）。**含 lineItemId 维度视图组件的模板**（工序 `quotation_line_item_id` 维度是典型），那些组件 Phase 2 仍走 `runSingleTask` 逐 task 冷展开。故现实 floor = `~0.3s + (lineItemId 视图组件数 × 行数 × 单次冷 expand)`，不是所有模板都到 0.3s。
- **但严格无回归(净赢)**：对逐 task 组件，今天成本 = Phase1 冷 expand（暖 cache）+ Phase2 runSingleTask **命中**该暖 cache ≈ 1 次冷 expand；FIX 1 后 = Phase2 冷 expand 1 次。**两者同为 ~1 次冷展开,中性不退化**。18.6s 收益全部来自可合桶组件（今天白干 77 次冷展开丢弃 → 改后 0 次 + 1 次 expandMulti）。
- **跨请求 cache 副作用(可忽略,记一笔)**：今天 Phase 1 顺带把 616 个 lineItem-tagged key 暖进进程级 expandCache(30s TTL)；FIX 1 后不再暖。后续 draft S3 里**非递归且 union-miss** 的核价组件走 `expand` 读 expandCache 可能由命中转未命中→S3 极轻微变慢。但实测核价 buildCostingCardValues 0.037s(union 全命中)、几乎不触发,可忽略。**无正确性风险。**
- 风险极低（纯重构 + 去废动作），可留 `cpq.batch-expand-bucket` 现有开关回退 Phase1-only 老路。

## 3. 修复二：S3 卡片值集合化落库（打破 77 行独立事务循环）

### 3.1 现状代价（`QuotationResource` S3 循环 + `CardSnapshotService.snapshotLineValuesWithUnion`）
77 行 × 一次 `@Transactional` 调用 = **77 个独立事务**；每事务 begin + `findById(li)`+`findById(q)`（跨 tx 不复用 L1，冷查）+ set 卡片值（JSONB）+ commit。`[s3-detail]` 实测 **逐行卡片值 4.1s**，真算值仅 0.23s → ~3.9s 是事务/冷 findById/逐行 UPDATE。

### 3.2 优化原理
**算值天然逐行（各行数据不同，便宜），该打破的是它外面的「逐行独立事务 + 逐行 I/O」。** 三处逐行→一次：
| 现状（77×） | 改为（1×） |
|---|---|
| 77 个独立事务 | **1 个事务**（L1 跨行复用 + 一次 flush） |
| 循环内 77 次 findById 判 hasSnapshot | **1 次 IN 查**「无快照新行 id」 |
| 77×(findById(li)+findById(q)) | **1 次 IN 查**装载全部新行 + 1 次取 Quotation |
| 77 条独立 JSONB UPDATE | **批量 flush**（P1 JDBC batch；或进阶两段式 UPDATE…FROM VALUES） |

### 3.3 改法（评审定稿：option ② 两遍 build-then-assign）

> **评审纠偏(关键)**：原 spec 把「autoflush 击穿批处理」当头号风险、要求 option ③ 裸 SQL。实读代码证伪——
> `buildCardValues`/`buildCostingCardValues` 在 **prefetch+union 命中**主路径里对请求线程 em **只有 `em.find`**
> (BomClosure 走裸 JDBC、DataLoader 异步裸 JDBC、driverComps/模板 snapshot 走 prefetch、公式 DB 函数走裸
> DataSource);`em.find` 不触发 autoflush。**故 autoflush 在主路径不触发,option ③ 非必须**(且裸 SQL 要手搓
> JSONB 转义、丢脏检查,更易错,不作首选)。
> **但为「不依赖 prefetch 覆盖率、确定性拿单次 flush」,option ② 强制改两遍**:Pass1 只 build 字符串到内存
> (不碰实体,脏窗口为空 → 即便 fallback `em.createNativeQuery`(prefetch/union miss)mid-loop 跑也 flush 空);
> Pass2 一次性给托管实体赋值(中间零查询)→ commit 单次 flush。build 只读 `li`(id/partNo/compositeType)、不写,
> 两遍拆分零成本。

**① 编排器**：一次定位新行(blank-inclusive 谓词,与现有 `!isBlank()` 逐位对齐)+ 一次调用
```java
// 谓词须 blank-inclusive:现有 hasSnapshot 判定是 quoteCardValues!=null && !isBlank(),
// 故空串行也算"无快照需重做";单纯 IS NULL 会漏掉空串行 → 行为分叉。
List<UUID> newLineIds = /* SELECT id FROM quotation_line_item
                           WHERE quotation_id=:q AND (quote_card_values IS NULL OR btrim(quote_card_values)='') */;
if (!newLineIds.isEmpty()) {
    var union    = cardSnapshotService.precomputeCostingDriverUnion(id);          // 首行一次(不变)
    var prefetch = cardSnapshotService.precomputeCardValuesPrefetch(id, allLineIds);
    cardSnapshotService.snapshotNewLinesCardValues(id, newLineIds, union, prefetch);  // ★ 单次集合化
    snapshotsCreated = true;
}
```
**② 集合化方法(两遍 build-then-assign,单事务 + 1 IN 装载 + 单次 flush)**
```java
@Transactional   // 首存行数有界(几十~低百),固定单事务 → 原子 all-or-nothing
public void snapshotNewLinesCardValues(UUID quotationId, List<UUID> newLineIds,
                                       Map<UUID,Map<String,ExpandDriverResponse>> union,
                                       CardValuesPrefetch prefetch) {
    Quotation q = Quotation.findById(quotationId);                                   // 1 次
    List<QuotationLineItem> lines = QuotationLineItem.list("id IN ?1", newLineIds);  // ★ 1 次 IN 装载(托管,省逐行 findById)
    QuotationIdContext.set(quotationId);
    try {
        // ── Pass1:只 build 字符串到内存(脏窗口为空;任何 fallback em 查此刻 flush 空,无害)──
        Map<UUID,String> quoteVals = new HashMap<>(), costingVals = new HashMap<>();
        for (QuotationLineItem li : lines) {
            quoteVals.put(li.id, safeCall(() -> buildCardValues(li, q.customerTemplateId, prefetch)));  // 只读 li,不写
            if (q.costingCardTemplateId != null)
                costingVals.put(li.id, safeCall(() ->
                    buildCostingCardValues(li, q.costingCardTemplateId, q.customerId, q.id, union, prefetch)));
        }
        // ── Pass2:一次性赋托管实体 4 字段(中间零查询)→ commit 时单次 flush,P1 JDBC batch 合并 N 条 UPDATE ──
        OffsetDateTime now = OffsetDateTime.now();
        for (QuotationLineItem li : lines) {
            li.quoteCardValues = quoteVals.get(li.id);
            li.quoteValuesAt = now;
            if (costingVals.containsKey(li.id)) li.costingCardValues = costingVals.get(li.id);
            li.cardSnapshotAt = now;
        }
    } finally { QuotationIdContext.clear(); }
}   // ★ commit:单次 flush
```
*(option ③ 裸 SQL `UPDATE…FROM(VALUES)` 仅作"两遍 option② 仍不够"的后备;首存无需,已删除原 chunking 讨论。)*

### 3.4 为什么等价
- `buildCardValues`/`buildCostingCardValues` 逐行**输入不变**（同 templateId/union/prefetch/managed）→ 落库卡片值**逐位相同**。
- 时间戳 `quoteValuesAt`/`cardSnapshotAt` 改整批同一 `now`（不入 golden md5，无影响）。
- 护栏：`GoldenCardValuesEquivTest`（md5 8f0c37a4=`3837c2bd...`/a8f17a74=`98d6ab6a...`）+ `BatchStage1PersistEquivTest` + **新增 `CardValuesBatchPersistEquivTest`**（单事务批量 vs 逐行 77 事务，落库值逐位相等）。

### 3.5 风险
- **事务边界(评审澄清,去除自相矛盾)**：首存行数**有界**(一张报价单几十~低百行,**不是上万**),**固定单事务**——`all-or-nothing` 正好,「一行算错不再污染已 commit 的前几行」消除 AP 风格部分提交、更安全。**原 spec「上万行 200 行 chunking」与此互斥(分块=重新引入部分提交)、已删**;首存不存在上万行场景。
- **REQUIRES_NEW 不在本路径(评审确认)**：卡片值路径只读+赋字段,不调 `writeRowData`/`writeSnapshotBatch`(那俩属 S1 row_data 物化、S3 前早已 commit)。折叠单事务不改任何 REQUIRES_NEW 语义/顺序。
- **时间戳(评审低风险)**：`quoteValuesAt` 是前端 Excel 视图刷新信号(`excelRefreshSignal`)。整批同一 `now` 每次首存仍是**新值**→ "变化即刷"照常触发;`cardSnapshotAt` 是 `refreshQuoteCardValues` 的 baked 门闸,整批设 now 与逐行设 now 语义一致。实现前确认前端无"跨行比较 quoteValuesAt 单调"的逻辑(判定低风险,不入 golden md5)。
- **托管实体(评审确认)**：IN-load 在 tx 内返回托管实体,赋字段即脏 → flush;且**省掉现状逐行 `findById(li.id)` 重载**(那次冷查正是 4.1s 的一部分,收益来源之一)。IN-load 的 `.list` 在 tx 起点跑、无脏实体 → autoflush 空、无害。
- 与 lazy-excel 不冲突（本方法只算卡片值,Excel 仍懒算）。
- kill switch `cpq.firstsave-cardvalues-batch`（默认 OFF 灰度验等价 → ON）。

## 4. 实施顺序与验收（DoD）
1. **先修复一（batch-expand Phase 1）**：最大收益、最低风险、纯去白干。
   - [ ] `BatchExpandBucketEquivTest` + `BatchExpandSnapshotPrefetchEquivTest` 绿；`[be-profile]` phases 从 ~18.9s 降到秒内（用户复现确认）。
2. **再修复二（S3 集合化落库）**：
   - [ ] `GoldenCardValuesEquivTest` md5 不变 + `BatchStage1PersistEquivTest` + 新增 `CardValuesBatchPersistEquivTest` 逐位等价。
   - [ ] `[s3-detail]` 逐行卡片值从 4.1s 降到 ~1s；E2E `quotation-flow` 渲染无回归 + 空闲 PUT/draft≤1。
3. 撤埋点（或降 DEBUG），收益写回 `RECORD.md` + 记忆。
4. **收尾预期**：batch-expand ~0.3s + draft ~4-5s，首存体验 ~30s → ~5s。
