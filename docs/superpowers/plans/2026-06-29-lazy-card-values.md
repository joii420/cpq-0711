# 卡片值懒算 + 首存后 eager warm 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让打开报价单不再触发 batch-expand/batch-evaluate 风暴——卡片值快照可靠落库；首存不变慢、batch 接口不改、第一次与后续打开都快。

**Architecture:** 把卡片值计算从 saveDraft 摘除（删那条对 jsonb 误用 `btrim` 必抛、又被 `catch(ignore)` 吞掉的查询）；新增幂等端点 `ensureCardValues`（`pg_try_advisory_xact_lock` 单飞 → 取锁后查缺失行 → 复用现成 `snapshotNewLinesCardValues` 算两侧卡片值+核价 BOM 树 → 落库）；前端在「首存后」fire-and-forget warm + 「打开」兜底 ensure，失败行落非 NULL 哨兵 + 显式占位。

**Tech Stack:** Java 17 / Quarkus 3 / Hibernate Panache / PostgreSQL（advisory lock）/ React + Ant Design / Vitest / Playwright。

**权威 spec:** `docs/superpowers/specs/2026-06-29-lazy-card-values-design.md`（v3）。本计划逐节落地其 §3/§4/§8。

**硬纪律（每个后端任务都适用）:**
- 守 `cpq-expand-layer-not-threadsafe`：单线程顺序，**禁止**并行化 expand/公式/快照层。
- 后端测试在 worktree 的 `cpq-backend` 跑 `./mvnw -o test -Dtest=Xxx`，看 `target/surefire-reports/*.txt`（见 `cpq-worktree-maven-test-tree`）。
- 只 `git add` 本次明确改动文件，**严禁 `git add -A`**（主树有其它会话 WIP）。
- DB 连接：`PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db`。

---

## 文件结构（改动地图）

**后端**
- `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`
  - 新增 `ensureCardValues(UUID): int`（单飞 + 缺失谓词 + 复用 `snapshotNewLinesCardValues`）。
  - 改 `snapshotNewLinesCardValues`：失败值 null → 写非 NULL 失败哨兵。
  - 新增常量 `CARD_VALUE_FAILED_SENTINEL` + 私有 `orSentinel(String)`。
- `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`
  - 新增端点 `POST /{id}/ensure-card-values`。
  - **删除** saveDraft 内卡片值块（约 `:144-231`，含 `catch (Exception ignore)`）。
- `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
  - `processBatchStage1` 与逐行回退路径：重建某行子表时把该行 `quoteCardValues`/`costingCardValues` 置 NULL（D-1 失效）。
- 测试：`cpq-backend/src/test/java/com/cpq/quotation/service/EnsureCardValuesTest.java`（新增）；既有 `GoldenCardValuesEquivTest` / `CardValuesBatchPersistEquivTest` 保持绿（夹具 failure-free）。

**前端**
- `cpq-frontend/src/services/quotationService.ts`：新增 `ensureCardValues(id)`。
- `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`：首存后 warm 触发（去高频）+ 打开兜底守卫。
- `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`：`buildSnapshotExpansions`/ProductCard 识别 `__cardValueFailed` → 显式「数据待重算」占位。
- 测试：`cpq-frontend/src/pages/quotation/ensureCardValues.warm.test.ts`（新增，vitest）；E2E `cpq-frontend/e2e/quotation-flow.spec.ts`（加断言）。

---

## Task 1：后端 `ensureCardValues` 核心（缺失谓词 + 单飞 + 复用落库）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（在 `ensureExcelValues` 后追加新方法）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/EnsureCardValuesTest.java`

- [ ] **Step 1: 写失败测试（幂等 + 仅核价缺 + 落库）**

`EnsureCardValuesTest.java`：
```java
package com.cpq.quotation.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EnsureCardValuesTest {
    @Inject CardSnapshotService svc;
    @Inject EntityManager em;

    // 用一张已存在的草稿单（罗克韦尔 170 行）做基准；清空其卡片值模拟首存后未 warm。
    static final UUID QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Transactional
    void clearCardValues() {
        em.createNativeQuery("UPDATE quotation_line_item " +
            "SET quote_card_values=NULL, costing_card_values=NULL WHERE quotation_id=:q")
            .setParameter("q", QID).executeUpdate();
    }

    long countMissing() {
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM quotation_line_item " +
            "WHERE quotation_id=:q AND quote_card_values IS NULL")
            .setParameter("q", QID).getSingleResult()).longValue();
    }

    @Test
    void ensure_fills_all_then_idempotent() {
        clearCardValues();
        long before = countMissing();
        assertTrue(before > 0, "前置：应有缺卡片值的行");

        int computed = svc.ensureCardValues(QID);
        assertEquals(before, computed, "应补算所有缺失行");
        assertEquals(0, countMissing(), "补算后无缺失");

        int second = svc.ensureCardValues(QID);
        assertEquals(0, second, "幂等：第二次返回 0");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=EnsureCardValuesTest`
Expected: 编译失败 / `ensureCardValues` 未定义。

- [ ] **Step 3: 实现 `ensureCardValues`（追加到 `CardSnapshotService.java`，紧接 `ensureExcelValues` 之后）**

```java
    /**
     * 卡片值懒算（2026-06-29）：仿 ensureExcelValues。打开/首存后 warm 触发，对缺卡片值的行
     * 复用 snapshotNewLinesCardValues 批量算两侧卡片值（含核价 BOM 树）并落库。幂等。
     *
     * <p>单飞：pg_try_advisory_xact_lock 防并发双算。取不到锁返回 -1（warming-in-progress），
     * 调用方（端点）据此返回轻量 warming 状态、不阻塞 worker。
     * <p><b>顺序铁律</b>：必须「先取锁、再查缺失行」，否则两事务都先读到 NULL → 双算。
     *
     * @return 落库行数；0=全部已就绪；-1=warm 在飞（未取到锁）。
     */
    @Transactional
    public int ensureCardValues(UUID quotationId) {
        if (quotationId == null) return 0;
        // 先取锁（事务级，commit 释放）；UUID→bigint，碰撞可忽略
        Boolean locked = (Boolean) em.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock( ('x'||substr(md5(:q),1,16))::bit(64)::bigint )")
            .setParameter("q", quotationId.toString()).getSingleResult();
        if (locked == null || !locked) return -1;   // warm 在飞

        Quotation q = Quotation.findById(quotationId);
        if (q == null) return 0;
        boolean hasCostingTpl = q.costingCardTemplateId != null;

        // 取锁之后再查缺失行（覆盖「仅核价缺」：核价模板存在但 costing_card_values 为 NULL）
        String sql = "SELECT id FROM quotation_line_item WHERE quotation_id = :q " +
            "AND ( quote_card_values IS NULL" +
            (hasCostingTpl ? " OR costing_card_values IS NULL" : "") + " )";
        @SuppressWarnings("unchecked")
        java.util.List<Object> rawIds = em.createNativeQuery(sql)
            .setParameter("q", quotationId).getResultList();
        java.util.List<UUID> missing = new java.util.ArrayList<>();
        for (Object o : rawIds) { UUID u = asUuid(o); if (u != null) missing.add(u); }
        if (missing.isEmpty()) return 0;

        // 整单全部行 id（compdata IN 预取用）
        java.util.List<UUID> allIds = QuotationLineItem.<QuotationLineItem>list("quotationId", quotationId)
            .stream().map(li -> li.id).collect(java.util.stream.Collectors.toList());

        var union = precomputeCostingDriverUnion(quotationId);
        var prefetch = precomputeCardValuesPrefetch(quotationId, allIds);
        snapshotNewLinesCardValues(quotationId, missing, union, prefetch);
        LOG.infof("[ensure-cardvalues] quotation=%s 补算 %d 行", quotationId, missing.size());
        return missing.size();
    }
```

> 说明：`asUuid` 已是本类/资源层既有私有工具（saveDraft 块在用）；若 `CardSnapshotService` 内无此方法，复制 `QuotationResource.asUuid` 同款实现到本类（私有 static）。`precomputeCostingDriverUnion`/`precomputeCardValuesPrefetch`/`snapshotNewLinesCardValues` 均已存在于本类。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=EnsureCardValuesTest`
Expected: PASS（`ensure_fills_all_then_idempotent` 绿）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/EnsureCardValuesTest.java
git commit -m "feat(cardvalues): ensureCardValues 懒算端点核心(单飞+缺失谓词+复用落库,幂等)"
```

---

## Task 2：后端 失败哨兵（失败值 null → 非 NULL 哨兵）

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java`（`snapshotNewLinesCardValues` Pass2 + 新常量/工具）
- Test: `EnsureCardValuesTest.java`（加用例）

- [ ] **Step 1: 写失败测试（确定性失败行落非 NULL 哨兵、不卡 gate、不被反复选中）**

在 `EnsureCardValuesTest` 追加：
```java
    @Test
    void failed_row_writes_sentinel_not_null() {
        // 构造一行注定 build 失败的：把某行 templateId 置为不存在的随机 UUID（buildCardValues 抛错 → safeCall=null）
        clearCardValues();
        UUID badLine = ((java.util.UUID) em.createNativeQuery(
            "SELECT id FROM quotation_line_item WHERE quotation_id=:q LIMIT 1")
            .setParameter("q", QID).getSingleResult());
        breakLine(badLine);

        svc.ensureCardValues(QID);

        String qcv = (String) em.createNativeQuery(
            "SELECT quote_card_values::text FROM quotation_line_item WHERE id=:id")
            .setParameter("id", badLine).getSingleResult();
        assertNotNull(qcv, "失败行必须落非 NULL 哨兵(防整侧 gate 翻 false)");
        assertTrue(qcv.contains("__cardValueFailed"), "应为失败哨兵");

        // 哨兵非 NULL → 不被 IS NULL 谓词反复选中
        assertEquals(0, svc.ensureCardValues(QID), "哨兵行不应再被选中重算");
    }

    @Transactional
    void breakLine(UUID lineId) {
        em.createNativeQuery("UPDATE quotation_line_item SET template_id=gen_random_uuid() WHERE id=:id")
            .setParameter("id", lineId).executeUpdate();
    }
```
> 注：若改 `template_id` 不足以使 `buildCardValues` 确定性抛错，改为 mock 一个会抛的输入；关键是 safeCall 返回 null 这一路被哨兵接住。Step 3 后据实际跑测调整断言触发方式（保持「失败→非 NULL 哨兵」语义不变）。**测试结束回滚 `template_id`**（用 `@TestTransaction` 或测试尾 restore）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=EnsureCardValuesTest#failed_row_writes_sentinel_not_null`
Expected: FAIL（当前失败行落 null，断言 `assertNotNull` 不过）。

- [ ] **Step 3: 实现哨兵**

在 `CardSnapshotService` 顶部加常量与工具：
```java
    /** 卡片值 build 确定性失败时落库的非 NULL 哨兵（防全有或全无 gate 把整侧打回实时风暴）。 */
    public static final String CARD_VALUE_FAILED_SENTINEL = "{\"tabs\":[],\"__cardValueFailed\":true}";

    private static String orSentinel(String built) {
        return (built == null || built.isBlank()) ? CARD_VALUE_FAILED_SENTINEL : built;
    }
```
改 `snapshotNewLinesCardValues` 的 Pass2 赋值（当前 :485-487 区段）：
```java
            for (QuotationLineItem li : lines) {
                li.quoteCardValues = orSentinel(quoteVals.get(li.id));
                li.quoteValuesAt = now;
                if (costingVals.containsKey(li.id))
                    li.costingCardValues = orSentinel(costingVals.get(li.id));
                li.cardSnapshotAt = now;
            }
```
对应失败处加日志（Pass1 safeCall 旁，或赋值时检测）：
```java
            // 失败哨兵告警（不静默；spec §3.5）
            for (QuotationLineItem li : lines) {
                if (quoteVals.get(li.id) == null)
                    LOG.warnf("[cardvalues-sentinel] quote build 失败 line=%s → 落失败哨兵", li.id);
                if (costingVals.containsKey(li.id) && costingVals.get(li.id) == null)
                    LOG.warnf("[cardvalues-sentinel] costing build 失败 line=%s → 落失败哨兵", li.id);
            }
```

- [ ] **Step 4: 跑测试确认通过 + golden 不回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=EnsureCardValuesTest,GoldenCardValuesEquivTest,CardValuesBatchPersistEquivTest`
Expected: 全 PASS。**前提（spec §8.1）**：`GoldenCardValuesEquivTest`/`CardValuesBatchPersistEquivTest` 的夹具单必须 failure-free（无确定性失败行）→ 无哨兵 → md5 不变。若 golden 夹具含失败行，先在那两个测试里加注释声明「夹具须 failure-free，哨兵行排除 md5 比对」并换无失败基准单。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/EnsureCardValuesTest.java
git commit -m "feat(cardvalues): 失败行落非NULL哨兵+warn(防全有或全无gate打回风暴);golden夹具failure-free"
```

---

## Task 3：后端 端点 `POST /{id}/ensure-card-values`

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`（仿 `ensureExcelValues` 端点 :306）

- [ ] **Step 1: 写失败测试（端点返回带卡片值的 DTO；warm 在飞返 warming）**

`cpq-backend/src/test/java/com/cpq/quotation/resource/EnsureCardValuesEndpointTest.java`：
```java
package com.cpq.quotation.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EnsureCardValuesEndpointTest {
    static final String QID = "8f0c37a4-8186-4f5e-a9ca-358bd2d9662d";

    @Test
    void endpoint_returns_200_and_dto() {
        given().contentType("application/json")
          .when().post("/api/cpq/quotations/" + QID + "/ensure-card-values")
          .then().statusCode(200)
          .body("data", notNullValue());
    }
}
```
> 若项目端点需鉴权，参考既有 `ensureExcelValues` 端点的测试鉴权方式（同 Resource 同套）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=EnsureCardValuesEndpointTest`
Expected: 404（端点不存在）。

- [ ] **Step 3: 实现端点（紧贴 `ensureExcelValues` 端点之后）**

```java
    /** P3 lazy-cardvalues：懒算并落库整单卡片值（quote/costing card values）。warm 与打开兜底复用。 */
    @POST
    @Path("/{id}/ensure-card-values")
    public ApiResponse<QuotationDTO> ensureCardValues(@PathParam("id") UUID id) {
        int r = cardSnapshotService.ensureCardValues(id);
        if (r < 0) {
            // warm 在飞（未取到单飞锁）：返回轻量 warming 状态，不阻塞 worker
            QuotationDTO dto = new QuotationDTO();
            dto.cardValuesWarming = true;   // DTO 加一个布尔字段
            return ApiResponse.success(dto);
        }
        em.clear();                          // 驱逐陈旧 L1，让 getById 读新值
        return ApiResponse.success(quotationService.getById(id));
    }
```
在 `QuotationDTO` 加字段：
```java
    /** lazy-cardvalues：warm 在飞（未取到单飞锁）时为 true，前端据此显示 spinner/稍后重试。 */
    public boolean cardValuesWarming = false;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=EnsureCardValuesEndpointTest`
Expected: PASS（200 + data 非空）。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java \
        cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java \
        cpq-backend/src/test/java/com/cpq/quotation/resource/EnsureCardValuesEndpointTest.java
git commit -m "feat(cardvalues): POST /quotations/{id}/ensure-card-values 端点(warm在飞返warming)"
```

---

## Task 4：后端 saveDraft 删卡片值块 + D-1 重建行失效

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`（删 :144-231 卡片值块）
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`（`processBatchStage1` + 逐行路径：重建行置 NULL）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/SaveDraftCardValuesInvalidationTest.java`

- [ ] **Step 1: 写失败测试（保存后被重建行卡片值=NULL；saveDraft 不再算卡片值）**

```java
package com.cpq.quotation.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SaveDraftCardValuesInvalidationTest {
    @Inject EntityManager em;
    static final UUID QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Test @Transactional
    void rebuild_nulls_card_values() {
        // 先确保有值
        em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values='{\"tabs\":[]}'::jsonb " +
            "WHERE quotation_id=:q").setParameter("q", QID).executeUpdate();
        // 模拟 processBatchStage1 重建该行子表后的失效写：调用被测的置 NULL 逻辑
        // —— 实际由 saveDraft 路径触发；此处断言「重建后该行 quote_card_values IS NULL」
        // 用一次 saveDraft（复用既有 SaveDraftRequest 构造）后查询，断言被重建行卡片值为 NULL。
        // (构造 SaveDraftRequest 见既有 SaveDraft 测试夹具；保存后:)
        long withVals = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM quotation_line_item WHERE quotation_id=:q AND quote_card_values IS NOT NULL")
            .setParameter("q", QID).getSingleResult()).longValue();
        assertEquals(0, withVals, "saveDraft 重建行后卡片值应被置 NULL（D-1 失效）");
    }
}
```
> 若构造完整 `SaveDraftRequest` 成本高，可改为**直接单测置 NULL 逻辑**：把「重建行置 NULL」抽成 `QuotationService` 私有可测点或在 processBatchStage1 主循环内联，测试退化为「调 saveDraft 后查询 = 0」。保持断言语义不变。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=SaveDraftCardValuesInvalidationTest`
Expected: FAIL（当前 saveDraft 不置 NULL）。

- [ ] **Step 3a: 删 saveDraft 卡片值块**

`QuotationResource.java`：删除 saveDraft 内约 `:144-231` 整段（`boolean snapshotsCreated ...` try 块 + `cardValuesBatch` 分支 + 逐行回退 else + `catch (Exception ignore)` + `finally{ExcelCompDataContext.clear()}`）。同时删除 `:243-250` 依赖 `snapshotsCreated` 的 `em.clear()+getById` 重建段（saveDraft 不再产卡片值，DTO 保持 :122 构建的那份）。保留 `:127 snapshotQuotation(id,true)`。`[draft-profile]` 日志行去掉 `newLines`/`S3.cardValues` 项或整行删除。

- [ ] **Step 3b: processBatchStage1 + 逐行路径置 NULL**

`QuotationService.processBatchStage1` 主循环内 `li.persist();`（约 :1998）**之后**加：
```java
            // D-1 失效（lazy-cardvalues）：本行子表(snapshot_rows)被重建 → 旧卡片值过期，置 NULL
            // 使 ensureCardValues 的 IS NULL 谓词下次能重新选中、用最新 snapshot_rows 重算。
            li.quoteCardValues = null;
            li.costingCardValues = null;
```
逐行回退路径（`batchStage1Enabled=false` 分支，`li.persist();` 约 :471 之后）加同样两行。

- [ ] **Step 4: 跑测试确认通过 + 首存计时回归**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=SaveDraftCardValuesInvalidationTest`
Expected: PASS。
再人工核验首存不变慢：`touch` 一个 java 触发重启 → 用真实流程存一张单 → 后端日志 `[draft-profile]` 不再含 `S3.cardValues` 大头，total 不高于现状。

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java \
        cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java \
        cpq-backend/src/test/java/com/cpq/quotation/service/SaveDraftCardValuesInvalidationTest.java
git commit -m "feat(cardvalues): saveDraft删卡片值块(去btrim+去吞错catch)+重建行置NULL失效(D-1)"
```

---

## Task 5：前端 service + 首存后 warm + 打开兜底守卫

**Files:**
- Modify: `cpq-frontend/src/services/quotationService.ts`（加 `ensureCardValues`）
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`（warm 触发去高频 + 打开守卫）
- Test: `cpq-frontend/src/pages/quotation/ensureCardValues.warm.test.ts`

- [ ] **Step 1: 写失败测试（warm 只在显式保存/导入完成触发，且仅在确有缺值时）**

`ensureCardValues.warm.test.ts`（vitest，纯函数级测「是否该 warm」判定）：
```ts
import { describe, it, expect } from 'vitest';
import { shouldWarmCardValues } from './QuotationWizard';

describe('shouldWarmCardValues', () => {
  it('有行缺 quoteCardValues → true', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: undefined, costingCardValues: '{}' }] as any)).toBe(true);
  });
  it('全部已算(含哨兵字符串视为已算) → false', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: '{"tabs":[]}', costingCardValues: '{"tabs":[]}' }] as any)).toBe(false);
  });
  it('空集 → false', () => {
    expect(shouldWarmCardValues([] as any)).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/ensureCardValues.warm.test.ts`
Expected: FAIL（`shouldWarmCardValues` 未导出）。

- [ ] **Step 3a: service 加 `ensureCardValues`**

`quotationService.ts`（紧贴 `saveDraft` :127 之后）：
```ts
  /** lazy-cardvalues：懒算并落库整单卡片值。warm 与打开兜底复用。返回 { data: { ..., cardValuesWarming? } }。 */
  ensureCardValues: (id: string) => api.post(`/quotations/${id}/ensure-card-values`) as Promise<any>,
```

- [ ] **Step 3b: QuotationWizard 加判定 + warm + 守卫**

在 `QuotationWizard.tsx` 导出纯函数（供测试与内部复用）：
```ts
// 判定该单是否需要 warm 卡片值（有任一行缺 quote/costing 卡片值；哨兵字符串非空 → 视为已算）。
export function shouldWarmCardValues(items: Array<{ quoteCardValues?: string; costingCardValues?: string }>): boolean {
  if (!items || items.length === 0) return false;
  return items.some(li => !li.quoteCardValues || !li.costingCardValues);
}
```
warm 触发（fire-and-forget，**仅**在 import-auto-save effect 成功后 + `handleSaveDraft` 成功后调用；不挂高频回调）：
```ts
// 首存/显式保存成功后 warm：不阻塞、不挡操作，失败静默(兜底在打开守卫)
const warmCardValues = useCallback((qId: string, items: any[]) => {
  if (!shouldWarmCardValues(items)) return;
  quotationService.ensureCardValues(qId).catch(() => { /* warm best-effort; 打开守卫兜底 */ });
}, []);
```
在 `autoSaveDraft`（:665）成功落库后、以及 `handleSaveDraft` 成功后各调一次 `warmCardValues(quotationId, lineItems)`（用保存返回的最新 items）。
打开兜底守卫——在 `loadQuotation`（:498）`applyQuotationData(res.data)` 之后加：
```ts
// 若打开时仍缺卡片值(warm 未跑/未完成/受损存量单) → 同步 ensure 一次再渲染卡片区，避免实时风暴
const opened = res.data?.lineItems ?? [];
if (shouldWarmCardValues(opened)) {
  try {
    const r = await quotationService.ensureCardValues(qId);
    if (r?.data && !r.data.cardValuesWarming) applyQuotationData(r.data);  // 回灌带卡片值的 DTO
    // cardValuesWarming=true：warm 在飞，保持 loading/spinner，由既有渲染兜底（见 Task 6 占位/§4.2）
  } catch { /* ensure 失败 → 回退现有实时渲染(同今天) */ }
}
```
> loading/进度由既有 wizard loading 态承载；窄窗口轮询进度条推迟（BL-0011）。

- [ ] **Step 4: 跑测试 + tsc + Vite 200**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/ensureCardValues.warm.test.ts`
Expected: PASS。
Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationWizard.tsx` → 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/services/quotationService.ts \
        cpq-frontend/src/pages/quotation/QuotationWizard.tsx \
        cpq-frontend/src/pages/quotation/ensureCardValues.warm.test.ts
git commit -m "feat(cardvalues): 前端 ensureCardValues service + 首存后eager warm(去高频) + 打开兜底守卫"
```

---

## Task 6：前端 失败哨兵占位渲染

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`buildSnapshotExpansions` 解析处识别 `__cardValueFailed`；ProductCard 渲染占位）
- Test: `cpq-frontend/src/pages/quotation/cardValueFailed.placeholder.test.ts`

- [ ] **Step 1: 写失败测试（识别哨兵 line）**

`cardValueFailed.placeholder.test.ts`：
```ts
import { describe, it, expect } from 'vitest';
import { isCardValueFailed } from './QuotationStep2';

describe('isCardValueFailed', () => {
  it('哨兵 JSON → true', () => {
    expect(isCardValueFailed('{"tabs":[],"__cardValueFailed":true}')).toBe(true);
  });
  it('正常卡片值 → false', () => {
    expect(isCardValueFailed('{"tabs":[{"componentId":"x","baseRows":[]}]}')).toBe(false);
  });
  it('undefined/空 → false', () => {
    expect(isCardValueFailed(undefined)).toBe(false);
    expect(isCardValueFailed('')).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/cardValueFailed.placeholder.test.ts`
Expected: FAIL（`isCardValueFailed` 未导出）。

- [ ] **Step 3: 实现识别 + 占位**

`QuotationStep2.tsx` 导出：
```ts
// 识别失败哨兵卡片值（后端 build 确定性失败时落 {"tabs":[],"__cardValueFailed":true}）。
export function isCardValueFailed(json?: string): boolean {
  if (!json) return false;
  try { return JSON.parse(json)?.__cardValueFailed === true; } catch { return false; }
}
```
在 ProductCard 渲染入口（卡片值取 `quoteValuesJson`/`costingCardValues` 处，约 :1860/:2947），若 `isCardValueFailed(json)` → 该 line 全组件渲染显式占位（不走 BASIC_DATA/FORMULA 默认分支，避免 AP-38「加载中」）：
```tsx
{isCardValueFailed(side === 'QUOTE' ? item.quoteCardValues : item.costingCardValues) ? (
  <Alert
    type="warning" showIcon
    message="该料号卡片数据待重算"
    description={
      <Button size="small" onClick={() => onRefreshLineSnapshot?.(componentIdForRefresh)}>重算</Button>
    }
  />
) : (
  /* 既有正常渲染分支 */
)}
```
> 「重算」按钮本期复用既有 `POST /components/{id}/refresh-template-snapshots`（`onRefreshLineSnapshot` 走既有 refresh 链路）；卡内单行精细重算入口推迟（BL-0012）。占位务必**显式可见**（非空白），守 spec §3.5「不静默降级」。

- [ ] **Step 4: 跑测试 + tsc + Vite 200**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/cardValueFailed.placeholder.test.ts` → PASS。
Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误。
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx` → 200。

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx \
        cpq-frontend/src/pages/quotation/cardValueFailed.placeholder.test.ts
git commit -m "feat(cardvalues): 失败哨兵行显式『数据待重算』占位+重算入口(防AP-38静默降级)"
```

---

## Task 7：E2E + 一致性闸 + 首开延迟基准

**Files:**
- Modify: `cpq-frontend/e2e/quotation-flow.spec.ts`（加 batch=0 断言 + 一致性 + 延迟基准）

- [ ] **Step 1: 加 E2E 断言**

在既有 `quotation-flow.spec.ts` 打开流程后追加（参考 `docs/E2E测试方法.md` 选择器/Network 约定）：
```ts
// 打开已存单 → ensure 后读快照渲染：不再发 batch-expand/batch-evaluate
const calls: string[] = [];
page.on('request', r => {
  const u = r.url();
  if (u.includes('/components/batch-expand') || u.includes('/formulas/batch-evaluate')) calls.push(u);
});
// （打开报价单、等渲染稳定后）
await expect.poll(() => calls.length, { timeout: 8000 }).toBe(0);   // ensure 完成后零 batch
// 8 Tab 加载中 = 0（既有断言保持）
// 一致性闸：记录首开 ensure 阻塞时长基准（console.warn 输出，纳入回归观察）
```

- [ ] **Step 2: 跑 E2E**

Run:
```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: test `passed`；打开后 `batch-expand`/`batch-evaluate` 调用数=0；8 Tab `加载中`=0。

- [ ] **Step 3: 真实流程复现验证（用户原始故障单）**

打开 `QT-20260629-1889`（或新建一张导入单）：
- 第一次打开：ensure 一次（或 warm 已就绪秒开）；F12 Network `batch-expand`/`batch-evaluate` 第二次打开为 0。
- DB 核验：`PGPASSWORD=joii5231 psql ... -c "SELECT count(quote_card_values) qc, count(costing_card_values) cc FROM quotation_line_item WHERE quotation_id='1d1ec7ee-...'"` → qc/cc 落齐（哨兵行除外）。

- [ ] **Step 4: 提交 + 自检声明**

```bash
git add cpq-frontend/e2e/quotation-flow.spec.ts
git commit -m "test(cardvalues): E2E 打开后 batch=0 断言 + 一致性/首开延迟基准"
```
自检声明（示例）：TS 0 错误 ✅；QuotationWizard/Step2 → Vite 200 ✅；EnsureCardValues* 后端测试绿 ✅；E2E 打开 batch=0、加载中=0 ✅；QT-20260629-1889 第二次打开 batch=0、qc/cc 落库 ✅。

---

## 收尾（用户确认后自动执行）

- 全部后端测试：`cd cpq-backend && ./mvnw -o test -Dtest='EnsureCardValues*,SaveDraftCardValuesInvalidationTest,GoldenCardValuesEquivTest,CardValuesBatchPersistEquivTest'` 全绿。
- E2E `quotation-flow.spec.ts` 绿。
- 追加 `docs/RECORD.md` 一行：`[2026-06-29] 报价卡片值懒算 - ensureCardValues+eager warm 修复打开风暴(根因btrim(jsonb)被吞) | CardSnapshotService/QuotationResource/QuotationService/QuotationWizard/QuotationStep2 | 首存零影响+单飞+失败哨兵+D-1失效`。
- 走 `superpowers:finishing-a-development-branch`：切回 master → merge 特性分支 → 跑测试 → `git worktree remove` + 删分支。
- BACKLOG.md：本计划未覆盖项保持 TODO（BL-0010~0013）。

---

## 自检（写计划后对照 spec v3）

- **覆盖**：§3.1 缺失谓词→Task1；§3.3 单飞→Task1；§3.4 删块+置NULL→Task4；§3.5 哨兵(后端)→Task2 +(前端占位)→Task6；§3.2 端点→Task3；§4.1 warm 去高频→Task5；§4.2 打开守卫→Task5；§6 一致性闸→Task7;§8 测试散落各 Task；§9 验收→Task7。
- **类型一致**：`ensureCardValues(UUID):int`（-1=warming）后端 ↔ 前端 `cardValuesWarming` 布尔 ↔ `shouldWarmCardValues`/`isCardValueFailed` 命名跨 Task 一致。
- **占位非空白**：Task6 占位为显式 Alert（守 §3.5 不静默）。
- **golden 不破**：Task2 Step4 明确夹具 failure-free。
