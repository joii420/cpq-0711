# 首存收口 Spec：batch-expand Phase1 去白干 + S3 卡片值集合化落库 — 2026-06-26

> 状态：设计 / 待实现。承接 `2026-06-26-firstsave-perf-analysis-report.md` 实锤的两个根因。
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

### 2.4 预期 / 风险
- **18.9s → ~0.3s**（Phase 2 八桶 245ms + Phase 1 窥探）。风险极低（纯重构 + 去废动作），无 kill switch 必要（可留 `cpq.batch-expand-bucket` 现有开关回退到 Phase1-only 老路）。

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

### 3.3 改法
**① 编排器**：一次定位新行 + 一次调用
```java
List<UUID> newLineIds = /* SELECT id FROM quotation_line_item WHERE quotation_id=:q AND quote_card_values IS NULL */;
if (!newLineIds.isEmpty()) {
    var union    = cardSnapshotService.precomputeCostingDriverUnion(id);          // 首行一次(不变)
    var prefetch = cardSnapshotService.precomputeCardValuesPrefetch(id, allLineIds);
    cardSnapshotService.snapshotNewLinesCardValues(id, newLineIds, union, prefetch);  // ★ 单次集合化
    snapshotsCreated = true;
}
```
**② 新增集合化方法**：1 事务 + 1 IN 查 + 内存算 + 批 flush
```java
@Transactional
public void snapshotNewLinesCardValues(UUID quotationId, List<UUID> newLineIds,
                                       Map<UUID,Map<String,ExpandDriverResponse>> union,
                                       CardValuesPrefetch prefetch) {
    Quotation q = Quotation.findById(quotationId);                  // 1 次
    List<QuotationLineItem> lines = QuotationLineItem.list("id IN ?1", newLineIds);  // ★ 1 次 IN 装载(托管)
    QuotationIdContext.set(quotationId);
    try {
        OffsetDateTime now = OffsetDateTime.now();
        for (QuotationLineItem li : lines) {                       // 内存循环,无独立 tx
            li.quoteCardValues = safeCall(() -> buildCardValues(li, q.customerTemplateId, prefetch));  // 0.23s 段,不动
            li.quoteValuesAt = now;
            if (q.costingCardTemplateId != null)
                li.costingCardValues = safeCall(() ->
                    buildCostingCardValues(li, q.costingCardTemplateId, q.customerId, q.id, union, prefetch));
            li.cardSnapshotAt = now;
            // 不逐行 persist;托管实体改字段 → commit 时 Hibernate 一次 flush, P1 batch 合并 77 UPDATE
        }
    } finally { QuotationIdContext.clear(); }
}
```
**③（进阶，大单可选）** Hibernate 批 flush 仍不够时，换纯 SQL 两段式批 UPDATE（`writeSnapshotBatch` 先例）：
```sql
UPDATE quotation_line_item t SET quote_card_values=v.q::jsonb, costing_card_values=v.c::jsonb,
       quote_values_at=:now, card_snapshot_at=:now
FROM (VALUES (:id1,:q1,:c1), ...) v(id,q,c) WHERE t.id=v.id      -- 分块 200 行/批
```

### 3.4 为什么等价
- `buildCardValues`/`buildCostingCardValues` 逐行**输入不变**（同 templateId/union/prefetch/managed）→ 落库卡片值**逐位相同**。
- 时间戳 `quoteValuesAt`/`cardSnapshotAt` 改整批同一 `now`（不入 golden md5，无影响）。
- 护栏：`GoldenCardValuesEquivTest`（md5 8f0c37a4=`3837c2bd...`/a8f17a74=`98d6ab6a...`）+ `BatchStage1PersistEquivTest` + **新增 `CardValuesBatchPersistEquivTest`**（单事务批量 vs 逐行 77 事务，落库值逐位相等）。

### 3.5 风险
- **事务边界**：77 行 all-or-nothing。首存本是一个逻辑操作；且「一行算错不再污染已 commit 的前几行」反而消除 AP 风格部分提交，**更安全**。
- 与 lazy-excel 不冲突（`computeExcel` 等价于这里不算 Excel，本方法只算卡片值）。
- kill switch `cpq.firstsave-cardvalues-batch`（默认 OFF 灰度验等价 → ON）。

## 4. 实施顺序与验收（DoD）
1. **先修复一（batch-expand Phase 1）**：最大收益、最低风险、纯去白干。
   - [ ] `BatchExpandBucketEquivTest` + `BatchExpandSnapshotPrefetchEquivTest` 绿；`[be-profile]` phases 从 ~18.9s 降到秒内（用户复现确认）。
2. **再修复二（S3 集合化落库）**：
   - [ ] `GoldenCardValuesEquivTest` md5 不变 + `BatchStage1PersistEquivTest` + 新增 `CardValuesBatchPersistEquivTest` 逐位等价。
   - [ ] `[s3-detail]` 逐行卡片值从 4.1s 降到 ~1s；E2E `quotation-flow` 渲染无回归 + 空闲 PUT/draft≤1。
3. 撤埋点（或降 DEBUG），收益写回 `RECORD.md` + 记忆。
4. **收尾预期**：batch-expand ~0.3s + draft ~4-5s，首存体验 ~30s → ~5s。
