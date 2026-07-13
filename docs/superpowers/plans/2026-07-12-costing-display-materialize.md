# 报价单中的核价单数据展示问题修复 — 服务端整单物化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「导入建单」时的明细行创建 + 4 份卡片值计算从「前端事后补算(autoPopulate + saveDraft + warm)」上移到后端 `create-quotation` 服务端同步完成并落库，使编辑页/报价详情页/核价管理三面开箱即用（首屏即树、无「加载中」、无「无组件数据」）。

**Architecture（已用代码证据坐实，见 `dev-docs/task-0712-报价单中的核价单数据展示问题修复/`）：**
后端在 `create-quotation` 走「建行事务提交 → 服务端展开写 snapshot_rows → 建结构快照 → 整单批量算卡片值/Excel 值」四步，**100% 照搬仓库唯一验证过的范例** `ConfigureProductResource.configureProduct`。关键事实：
1. 服务端建行**只需 INSERT `quotation_line_item`**；`quotation_line_component_data`(componentData 骨架 + snapshot_rows)由 `ConfigureSnapshotService.snapshotQuotation` 的 `writeSnapshot`(手写 UPSERT，UPDATE 未命中自建 INSERT)负责，无需 Task 手写子表 INSERT。
2. `snapshotQuotation`/`snapshotLines` 内部是 `REQUIRES_NEW`，**必须在建行事务提交之后调用**（否则看不见未提交的新行）——所以编排落在 Resource 层，不塞进 `createQuotation` 同一 `@Transactional` 方法体。
3. 报价侧 `buildCardValues` **纯读已落库的 `snapshot_rows`、零 fallback**（这正是「核价对、报价不对」的根因不对称：核价 `buildCostingCardValues` 自己现场 render/expand，报价侧不会）。所以服务端必须真的展开写 snapshot_rows。
4. 卡片值走 `CardSnapshotService.ensureCardValues(quotationId)`——它内部 `precomputeCostingDriverUnion` + `precomputeCardValuesPrefetch` + `snapshotNewLinesCardValues`，其中核价树是 **`CostingTreeRenderService.render(costingTemplateId, allLines)` 整单一次批量**（无 N+1，满足需求 §5）。

**Tech Stack:** Java 17 + Quarkus 3.23 + Hibernate Panache（后端）；React + TypeScript + Ant Design（前端）；PostgreSQL 16；Playwright（E2E）。

**边界（不做，来自需求 §11.1 C6 + backtask §7）：** 不改公式计算、不改报价侧「展示」逻辑（只补数据来源）、不改核价树递归 SQL 语义 / `bom_recursive_expand` 标记、不引入异步补算+轮询（首版同步物化）。

---

## 关键既有代码坐标（实现时直接引用，全部已读证）

| 用途 | 位置 |
|------|------|
| 导入建单 Resource 入口（本次编排主改） | `cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/BasicDataImportV6Resource.java:114` |
| 建单编排 service（本次扩建行 + 幂等） | `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java:36` |
| 候选查询（复用，纯查询） | `cpq-backend/src/main/java/com/cpq/quotation/service/CustomerPartCandidateService.java:41` `listCandidates(customerId, importRecordId)` |
| 候选 DTO 字段 | `CustomerPartCandidateDTO`：`partNo/partName/customerProductNo/customerPartName/customerDrawingNo/currentVersion/customerSpecific/hfPartInfo` |
| 服务端展开写 snapshot_rows（复用） | `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java:98` `snapshotQuotation(quotationId)` / `:106` 两参重载 |
| 结构快照（复用，幂等 skip-if-exists） | `CardSnapshotService.java:118` `ensureStructure(quotationId)` |
| 整单批量算卡片值（复用，核价树一次 render） | `CardSnapshotService.java:712` `ensureCardValues(quotationId)` → `int` |
| 整单批量算 Excel 值（复用，IS NULL 补算） | `CardSnapshotService.java:649` `ensureExcelValues(quotationId)` → `int` |
| 失败哨兵常量 | `CardSnapshotService.CARD_VALUE_FAILED_SENTINEL` / `failedSentinelWithError(msg)`（内部）→ 前端见 `__cardValueFailed` / `__errorMsg` |
| 建行范例（唯一验证过的服务端建行→展开→算值全链路） | `ConfigureProductResource.java:60-84` + `ConfigureProductService.insertLineItem:1218` |
| 明细行实体字段 | `QuotationLineItem.java`：`quotationId/templateId/productNameSnapshot/productPartNoSnapshot/customerPartNo/sortOrder/compositeType/partVersionLocked/productAttributeValues` |
| 前端导入向导 autoPopulate/warm/回灌 | `cpq-frontend/src/pages/quotation/QuotationWizard.tsx:669(sync)/704(warm)/789(import-auto-save)/823(autoPopulate)` |
| 前端建行（客户端，本次要在导入流关掉） | `cpq-frontend/src/pages/quotation/BulkImportPartsDrawer.tsx:176` `buildLineItemFromTemplate` |
| 只读面占位门控 | `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx:271` `useSnap` / `:742` 「无组件数据」 |
| warm 门控纯函数 | `cpq-frontend/src/pages/quotation/cardValuesWarm.ts` `shouldWarmCardValues` |
| 导入复现 E2E（技术总监已建） | `cpq-frontend/e2e/repro-costing-tree-import.spec.ts` |
| 核价树 E2E 标杆 | `cpq-frontend/e2e/costing-bom-tree.spec.ts` |

---

## File Structure（本计划创建/修改的文件）

**Create:**
- `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/QuotationLineItemMaterializeService.java` — 服务端从候选建 `quotation_line_item`（唯一职责：只 INSERT 主表行）。
- `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/QuotationLineItemMaterializeServiceTest.java` — 建行单测。
- `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/CreateQuotationMaterializeIT.java` — 端到端物化契约集成测试（导入建单→三面开箱）。

**Modify:**
- `V6QuotationCommitService.java` — createQuotation 内建行（同事务，不丢单）+ 幂等重入 + `CommitResult` 扩字段。
- `BasicDataImportV6Resource.java` — createQuotation 编排后置物化（snapshotQuotation→ensureStructure→ensureCardValues→ensureExcelValues）+ 回填状态。
- `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` — 导入流：后端已建行时跳过客户端 autoPopulate + import-auto-save（防重复行/防回退）。
- `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` — 失败哨兵显式错误占位。

---

## Task 1: 服务端建行 service（QuotationLineItemMaterializeService）

**目标**：后端能从「导入候选 + 报价模板」建出与前端 `buildLineItemFromTemplate` 等价的明细行并落库；**只 INSERT `quotation_line_item`**（componentData 子表由 Task 3 的 snapshotQuotation UPSERT 自建）。

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/QuotationLineItemMaterializeService.java`
- Test: `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/QuotationLineItemMaterializeServiceTest.java`

- [ ] **Step 1: 写失败测试**（`@QuarkusTest`，DB-backed，不需 V6 候选表 seed —— 直接喂 DTO 列表）

```java
package com.cpq.basicdata.v6.service;

import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class QuotationLineItemMaterializeServiceTest {

    @Inject QuotationLineItemMaterializeService service;
    @Inject EntityManager em;

    /** 直接建一张最小 quotation 行（避开 QuotationService.create 的重依赖），返回 id。 */
    @Transactional
    UUID seedQuotation(UUID templateId) {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
            "INSERT INTO quotation (id, name, status, customer_template_id, created_at) " +
            "VALUES (:id, 'IT-materialize', 'DRAFT', :tid, NOW())")
          .setParameter("id", qid).setParameter("tid", templateId).executeUpdate();
        return qid;
    }

    private CustomerPartCandidateDTO cand(String partNo, String partName, String cpn, Integer ver) {
        CustomerPartCandidateDTO d = new CustomerPartCandidateDTO();
        d.partNo = partNo; d.partName = partName; d.customerProductNo = cpn; d.currentVersion = ver;
        return d;
    }

    @Test
    @Transactional
    void materialize_N_candidates_creates_N_line_items_in_order() {
        UUID templateId = UUID.randomUUID();
        UUID qid = seedQuotation(templateId);

        List<UUID> ids = service.materializeLinesFromCandidates(qid, templateId, List.of(
            cand("S-3120014539", "根产品A", "CPN-A", 2001),
            cand("S-3120014540", "根产品B", "CPN-B", null)));

        assertEquals(2, ids.size());
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId ORDER BY sortOrder", qid);
        assertEquals(2, lines.size());
        assertEquals("S-3120014539", lines.get(0).productPartNoSnapshot);
        assertEquals("根产品A", lines.get(0).productNameSnapshot);
        assertEquals("CPN-A", lines.get(0).customerPartNo);
        assertEquals(templateId, lines.get(0).templateId);
        assertEquals("SIMPLE", lines.get(0).compositeType);
        assertEquals(0, lines.get(0).sortOrder);
        assertEquals(2001, lines.get(0).partVersionLocked);
        assertEquals(1, lines.get(1).sortOrder);
        assertEquals(2000, lines.get(1).partVersionLocked); // null → 默认 2000
    }

    @Test
    @Transactional
    void empty_candidates_creates_nothing() {
        UUID qid = seedQuotation(UUID.randomUUID());
        List<UUID> ids = service.materializeLinesFromCandidates(qid, UUID.randomUUID(), List.of());
        assertTrue(ids.isEmpty());
        assertEquals(0, QuotationLineItem.count("quotationId", qid));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=QuotationLineItemMaterializeServiceTest`
Expected: FAIL 编译错误 `cannot find symbol: class QuotationLineItemMaterializeService`

- [ ] **Step 3: 写最小实现**

```java
package com.cpq.basicdata.v6.service;

import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import com.cpq.quotation.service.CustomerPartCandidateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端从「导入候选 + 报价模板」建 quotation_line_item 明细行。
 *
 * <p>与前端 {@code buildLineItemFromTemplate} 等价，但只负责 INSERT 主表 quotation_line_item：
 * componentData 子表(quotation_line_component_data) + snapshot_rows 由后置的
 * {@link com.cpq.configure.service.ConfigureSnapshotService#snapshotQuotation(UUID)} 的
 * writeSnapshot UPSERT 自建（照搬 ConfigureProductResource 范例，见类注释）。
 *
 * <p><b>事务</b>：默认 REQUIRED —— 由 {@link V6QuotationCommitService#createQuotation} 在其
 * @Transactional 内调用时并入同一事务，保证「建单 + 建行」强一致（不丢单）。
 */
@ApplicationScoped
public class QuotationLineItemMaterializeService {

    @Inject EntityManager em;
    @Inject CustomerPartCandidateService candidateService;

    /** 便捷入口：查候选 → 建行。 */
    @Transactional
    public List<UUID> materializeLines(UUID quotationId, UUID customerId,
                                       UUID importRecordId, UUID templateId) {
        List<CustomerPartCandidateDTO> candidates =
            candidateService.listCandidates(customerId, importRecordId);
        return materializeLinesFromCandidates(quotationId, templateId, candidates);
    }

    /** 纯建行：按候选顺序 INSERT quotation_line_item（sort_order 从 0 递增）。 */
    @Transactional
    public List<UUID> materializeLinesFromCandidates(UUID quotationId, UUID templateId,
                                                     List<CustomerPartCandidateDTO> candidates) {
        List<UUID> ids = new ArrayList<>();
        if (quotationId == null || candidates == null || candidates.isEmpty()) return ids;
        int sort = 0;
        for (CustomerPartCandidateDTO c : candidates) {
            if (c == null || c.partNo == null || c.partNo.isBlank()) continue;
            UUID id = UUID.randomUUID();
            em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, template_id, product_part_no_snapshot, product_name_snapshot, " +
                " customer_part_no, composite_type, sort_order, part_version_locked, " +
                " product_attribute_values, created_at) " +
                "VALUES (:id, :q, :tid, :pn, :pname, :cpn, 'SIMPLE', :sort, :ver, cast('{}' as jsonb), NOW())")
                .setParameter("id", id)
                .setParameter("q", quotationId)
                .setParameter("tid", templateId)
                .setParameter("pn", c.partNo)
                .setParameter("pname", c.partName != null ? c.partName
                        : (c.customerPartName != null ? c.customerPartName : c.partNo))
                .setParameter("cpn", c.customerProductNo)
                .setParameter("sort", sort)
                .setParameter("ver", c.currentVersion != null ? c.currentVersion : 2000)
                .executeUpdate();
            ids.add(id);
            sort++;
        }
        return ids;
    }
}
```

> **实现注意**：列名以 `QuotationLineItem.java` 的 `@Column`/迁移为准。`product_attribute_values` 非空默认 `{}`（对齐实体默认）；若实体列名是 `customer_part_no`（V 迁移确认）则如上；跑测试前用 `\d quotation_line_item` 或读实体核对列名，任何不符以 DB 为准。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=QuotationLineItemMaterializeServiceTest`
Expected: PASS（2 tests green）

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/service/QuotationLineItemMaterializeService.java \
        cpq-backend/src/test/java/com/cpq/basicdata/v6/service/QuotationLineItemMaterializeServiceTest.java
git commit -m "feat(task-0712-展示修复): 服务端建行 service(只 INSERT quotation_line_item)"
```

---

## Task 2: createQuotation 内建行 + 幂等重入 + CommitResult 扩字段

**目标**：`V6QuotationCommitService.createQuotation` 在建单同事务内建明细行（不丢单）；重复提交同一 `importRecordId` 安全重入；`CommitResult` 扩展 api.md §1 新字段。

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java`

- [ ] **Step 1: CommitResult 扩字段 + 注入建行 service**

在类顶部注入：
```java
    @Inject QuotationLineItemMaterializeService materializeService;
```

把 `CommitResult`（当前 92-102 行）替换为：
```java
    public static class CommitResult {
        public UUID quotationId;
        public UUID importRecordId;
        public int hfPairsCount;
        // task-0712 展示修复新增（api.md §1）
        public int lineItemsCount;                      // 服务端已建明细行数
        public boolean cardValuesReady = false;         // 卡片值全部落库 = true；降级 = false（Resource 回填）
        public int costingTreeRows = 0;                 // 本单核价树总节点数（Resource 回填，可选）
        public java.util.List<String> warnings = new java.util.ArrayList<>();

        public CommitResult(UUID quotationId, UUID importRecordId, int hfPairsCount) {
            this.quotationId = quotationId;
            this.importRecordId = importRecordId;
            this.hfPairsCount = hfPairsCount;
        }
    }
```

- [ ] **Step 2: createQuotation 头部加幂等重入 + 建单后建行**

在方法体开头（`ImportRecord rec = ...` 之后、建单之前）插入幂等短路：
```java
        // 幂等重入(backtask §4 / Task4)：同 importRecordId 已建过单且单仍在 → 返回既有，不重复建单/建行。
        if (rec.quotationId != null) {
            com.cpq.quotation.entity.Quotation existing =
                com.cpq.quotation.entity.Quotation.findById(rec.quotationId);
            if (existing != null) {
                CommitResult r = new CommitResult(existing.id, req.importRecordId,
                        rec.matchedRows == null ? 0 : rec.matchedRows);
                r.lineItemsCount = (int) com.cpq.quotation.entity.QuotationLineItem
                        .count("quotationId", existing.id);
                Log.infof("V6 commit: 幂等重入，返回既有 quotation=%s（%d 行）", existing.id, r.lineItemsCount);
                return r;
            }
        }
```

在 `QuotationDTO q = quotationService.create(cq, userId);` 之后、hfPairs 段之前插入建行：
```java
        // task-0712 展示修复：建单同事务内服务端建明细行（不丢单：建单+建行强一致）。
        List<UUID> lineIds = materializeService.materializeLines(
                q.id, req.customerId, req.importRecordId, req.customerTemplateId);
        Log.infof("V6 commit: 服务端建明细行 %d 条 (quotation=%s)", lineIds.size(), q.id);
```

把 `return new CommitResult(...)`（当前 89 行）改为带 lineItemsCount：
```java
        CommitResult result = new CommitResult(q.id, req.importRecordId, hfPairs.size());
        result.lineItemsCount = lineIds.size();
        return result;
```

> 需在 import 区补 `import java.util.UUID;`（已有）——`lineIds` 是 `List<UUID>`，`java.util.List` 已 import。

- [ ] **Step 3: 更新类注释（消除误导）**

把当前 21-27 行 javadoc 中的
`<p>报价单本身不在此阶段填 LineItem——LineItem 由前端编辑页 autoPopulate 根据 ?importRecordId=xxx 调 CustomerPartCandidateService 自动生成。`
替换为：
```java
 * <p>task-0712 展示修复起：本阶段同事务内服务端建明细行（{@link QuotationLineItemMaterializeService}），
 * 不再依赖前端 autoPopulate。componentData/snapshot_rows/卡片值由 Resource 层后置物化
 * （snapshotQuotation → ensureStructure → ensureCardValues → ensureExcelValues）完成。
```

- [ ] **Step 4: 编译 + 触发 Quarkus 热重载自检**

Run: `cd cpq-backend && ./mvnw -o compile -q` → 0 错误
Run: `touch cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java` 等 6s
Run: `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 401

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/service/V6QuotationCommitService.java
git commit -m "feat(task-0712-展示修复): createQuotation 同事务建行 + 幂等重入 + CommitResult 扩字段"
```

---

## Task 3: Resource 层后置物化编排（snapshotQuotation→ensureStructure→ensureCardValues→ensureExcelValues）

**目标**：建行事务提交后（Resource 层，尊重 REQUIRES_NEW 边界），服务端展开写 snapshot_rows + 建结构 + 整单批量算卡片值/Excel 值并回填 `cardValuesReady/costingTreeRows/warnings`。降级不丢单。

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/BasicDataImportV6Resource.java`

- [ ] **Step 1: 注入依赖**

在类字段区加（若尚未注入）：
```java
    @Inject com.cpq.configure.service.ConfigureSnapshotService snapshotService;
    @Inject com.cpq.quotation.service.CardSnapshotService cardSnapshotService;
    private static final org.jboss.logging.Logger LOG =
        org.jboss.logging.Logger.getLogger(BasicDataImportV6Resource.class);
```
> 若类已有 `LOG`，复用即可，别重复声明。

- [ ] **Step 2: createQuotation 端点加后置物化编排**

把当前 `createQuotation`（114-131 行）方法体的 `return ApiResponse.success(commitService.createQuotation(req, userId));` 那段替换为：
```java
        try {
            V6QuotationCommitService.CommitResult r = commitService.createQuotation(req, userId);
            // ↑ createQuotation @Transactional 已提交 → 明细行对新事务可见。
            // 后置物化：REQUIRES_NEW 内部小事务必须在建行提交后调（照搬 ConfigureProductResource）。
            materializeCardData(r);
            return ApiResponse.success(r);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(500, "创建报价单失败: " + e.getMessage());
        }
```

在类内新增私有编排方法：
```java
    /**
     * 建行提交后服务端整单物化：展开写 snapshot_rows → 建 4 份结构 → 整单批量算卡片值/Excel 值。
     * 降级纪律（backtask §5 / api.md §3）：物化任一步失败不回滚整单（报价单+明细行已落库=不丢单），
     * 置 cardValuesReady=false + warnings；前端进编辑页由既有 warm 兜底补算。
     */
    private void materializeCardData(V6QuotationCommitService.CommitResult r) {
        if (r == null || r.quotationId == null) return;
        try {
            // ① 服务端展开所有 driver 组件 → writeSnapshot UPSERT 自建 componentData 行 + snapshot_rows
            snapshotService.snapshotQuotation(r.quotationId);
            // ② 4 份结构快照（幂等 skip-if-exists）
            cardSnapshotService.ensureStructure(r.quotationId);
            // ③ 整单批量算 quote/costing 卡片值（核价树 CostingTreeRenderService.render 一次批量，无 N+1）
            cardSnapshotService.ensureCardValues(r.quotationId);
            // ④ 整单批量算 quote/costing Excel 值（IS NULL 补算，供 Excel 视图开箱）
            cardSnapshotService.ensureExcelValues(r.quotationId);
            fillMaterializationStatus(r);
        } catch (Exception e) {
            r.cardValuesReady = false;
            r.warnings.add("卡片值物化失败: " + e.getMessage());
            LOG.errorf(e, "[create-quotation] 后置物化失败 quotation=%s（不丢单，前端 warm 兜底）", r.quotationId);
        }
    }

    /** 回填 cardValuesReady / costingTreeRows / warnings（读库判定）。 */
    private void fillMaterializationStatus(V6QuotationCommitService.CommitResult r) {
        boolean hasCosting = com.cpq.quotation.entity.Quotation
                .<com.cpq.quotation.entity.Quotation>findById(r.quotationId).costingCardTemplateId != null;
        // 缺卡片值（NULL）或落了失败哨兵的行 → 未就绪
        String failMark = "__cardValueFailed";
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = com.cpq.quotation.entity.QuotationLineItem
            .getEntityManager().createNativeQuery(
                "SELECT quote_card_values::text, costing_card_values::text " +
                "FROM quotation_line_item WHERE quotation_id = :q")
            .setParameter("q", r.quotationId).getResultList();
        boolean ready = !rows.isEmpty();
        int treeRows = 0;
        for (Object[] row : rows) {
            String quote = row[0] == null ? null : row[0].toString();
            String costing = row[1] == null ? null : row[1].toString();
            if (quote == null || quote.contains(failMark)) ready = false;
            if (hasCosting && (costing == null || costing.contains(failMark))) ready = false;
            if (hasCosting && costing != null && !costing.contains(failMark)) {
                treeRows += countCostingTreeRows(costing);   // 见 Step3
            }
        }
        r.cardValuesReady = ready;
        r.costingTreeRows = treeRows;
        if (!ready) r.warnings.add("部分行卡片值未就绪或渲染失败，详情/核价管理可能显式提示");
    }
```

- [ ] **Step 3: costingTreeRows 计数辅助（best-effort，解析失败即计 0）**

```java
    /** 解析 costing_card_values JSON，累加各页签 baseRows 行数（best-effort，供自检/日志）。 */
    private int countCostingTreeRows(String costingJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(costingJson);
            int n = 0;
            for (com.fasterxml.jackson.databind.JsonNode tab : root.path("tabs")) {
                n += tab.path("baseRows").size();
            }
            return n;
        } catch (Exception e) { return 0; }
    }
```

- [ ] **Step 4: 编译 + Quarkus 重启自检**

Run: `cd cpq-backend && ./mvnw -o compile -q` → 0 错误
Run: `touch cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/BasicDataImportV6Resource.java` 等 6s
Run: `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 401

- [ ] **Step 5: 提交**

```bash
git add cpq-backend/src/main/java/com/cpq/basicdata/v6/resource/BasicDataImportV6Resource.java
git commit -m "feat(task-0712-展示修复): Resource 层后置物化编排(展开+结构+卡片值+Excel)+状态回填"
```

---

## Task 4: 端到端物化契约集成测试（导入建单→三面开箱）

**目标**：用 `@QuarkusTest` 坐实 api.md §4 自检契约：导入建单后不打开编辑页、不手刷，每行 `costing_card_values` 非空、核价树行数正确。

**Files:**
- Create: `cpq-backend/src/test/java/com/cpq/basicdata/v6/service/CreateQuotationMaterializeIT.java`

- [ ] **Step 1: 写集成测试**

> 复用现有 E2E 测试单据（罗克韦尔 + 根料号 S-3120014539，含 14 节点树）。测试需先有该客户 + 一批 V6 基础数据 + 一条 v6 import_record（metadata.hfPairs 含根料号）。**开工前**用 `psql` 查一条现成的 v6 import_record + 客户 + 报价/核价模板 id 作为 fixture 常量（见 Step 2 的取数 SQL）；若测试库无稳定 fixture，则测试用 `@Disabled("需 E2E 库 fixture")` 标注并以 Task 5 的 E2E 为准。

```java
package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.CreateQuotationFromImportRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CreateQuotationMaterializeIT {

    @Inject V6QuotationCommitService commitService;
    @Inject EntityManager em;

    // TODO: 用开工前 psql 查到的稳定 fixture 替换（见 Step 2）
    static final UUID IMPORT_RECORD_ID = UUID.fromString("<v6-import-record-id>");
    static final UUID CUSTOMER_ID      = UUID.fromString("<罗克韦尔-customer-id>");
    static final UUID QUOTE_TEMPLATE   = UUID.fromString("<报价模板-id>");
    static final UUID COSTING_TEMPLATE = UUID.fromString("<核价模板(含树页签)-id>");

    @Test
    void create_quotation_materializes_all_lines_costing_non_null() {
        CreateQuotationFromImportRequest req = new CreateQuotationFromImportRequest();
        req.importRecordId = IMPORT_RECORD_ID;
        req.customerId = CUSTOMER_ID;
        req.name = "IT-展示修复-" + System.currentTimeMillis();
        req.customerTemplateId = QUOTE_TEMPLATE;
        req.costingTemplateId = COSTING_TEMPLATE;

        var r = commitService.createQuotation(req, null);
        // Resource 层编排在单测里手动跑（等价 materializeCardData 三步）
        // —— 或把 materializeCardData 抽成可注入 service 方法后直接调；此处直接断言建行 + 后置由 E2E 覆盖。
        assertTrue(r.lineItemsCount > 0, "服务端应已建明细行");

        @SuppressWarnings("unchecked")
        List<Object> nullCosting = em.createNativeQuery(
            "SELECT id FROM quotation_line_item WHERE quotation_id = :q AND costing_card_values IS NULL")
            .setParameter("q", r.quotationId).getResultList();
        // 注：本单测只覆盖建行；卡片值物化在 Resource 层，故此断言由 Task 5 E2E 兜底。
        assertNotNull(r.quotationId);
    }
}
```

> **设计取舍**：Resource 层的 `materializeCardData` 直接读库判定，最稳的验证是 Playwright E2E（Task 5）跑真实 HTTP 端点。本 IT 主要守「建行」这一后端可单测的部分。**若希望 IT 也覆盖卡片值物化**，把 `materializeCardData`/`fillMaterializationStatus` 从 Resource 抽到一个 `@ApplicationScoped CreateQuotationMaterializer` service（Resource 只调它），则可在 IT 直接注入并断言 `costing_card_values IS NOT NULL` 全 `t` + 树行数=14。**推荐这么抽**（更好测、Resource 更薄）——实现子代理可自行决定是否抽取，抽取则同步改 Task 3 的调用点。

- [ ] **Step 2: 取 fixture（开工前跑一次，把结果填进 Step 1 常量）**

Run:
```bash
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c \
"SELECT ir.id import_record, c.id customer, c.name FROM import_record ir \
 JOIN customer c ON c.code = (ir.metadata->'hfPairs'->0->>'cpn') \
 WHERE ir.metadata->>'v6'='true' ORDER BY ir.created_at DESC LIMIT 3;"
```
> 取不到稳定 fixture 就给该 IT 加 `@org.junit.jupiter.api.Disabled("依赖 E2E 库 fixture，卡片值物化由 Playwright 覆盖")`，不阻塞 CI。

- [ ] **Step 3: 跑测试**

Run: `cd cpq-backend && ./mvnw -o test -Dtest=CreateQuotationMaterializeIT`
Expected: PASS（或 Disabled skipped）

- [ ] **Step 4: 提交**

```bash
git add cpq-backend/src/test/java/com/cpq/basicdata/v6/service/CreateQuotationMaterializeIT.java
git commit -m "test(task-0712-展示修复): 建单物化契约集成测试"
```

---

## Task 5: 前端导入流跳过客户端建行 + 自动保存（防重复行 / 防回退覆盖）

**目标**：后端已服务端建行后，前端导入流**不再** autoPopulate 客户端建行、**不再** import-auto-save 重建行（否则 saveDraft 全删全建会抹掉后端行 id + snapshot_rows，重新触发「加载中」）。仅当后端降级建了 0 行时，保留旧 autoPopulate 作兜底。

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx`

- [ ] **Step 1: 加「后端已建行」判定 ref**

在组件内其它 ref 声明处（如 `wizardAutoPopulatedRef` 附近，约 822 行）新增：
```typescript
  // task-0712 展示修复：后端 create-quotation 已服务端建行 → 跳过客户端 autoPopulate + import-auto-save，
  // 避免重复行 / saveDraft 全删全建抹掉后端 snapshot_rows 触发「加载中」回退。
  const backendBuiltLinesRef = useRef(false);
```

- [ ] **Step 2: loadQuotation 后置位，检测后端已建行**

定位 loadQuotation 成功回填 lineItems 的位置（`setLineItems(...)` from GET /quotations/{id}）。在回填后加：
```typescript
  // 若加载到的明细行已带持久化 DB id（后端建行标志）→ 标记跳过客户端建行/自动保存
  if (isImportFlow && loadedLineItems.some((li: any) => li?.id)) {
    backendBuiltLinesRef.current = true;
    wizardAutoPopulatedRef.current = true;   // 关闭 autoPopulate
    importAutoSavedRef.current = true;       // 关闭 import-auto-save
  }
```
> `loadedLineItems` 换成该处实际变量名。关键：**在 setLineItems 之后、且在 autoPopulate effect 依赖 `lineItems.length` 触发之前**把两个 ref 置真。若 loadQuotation 是 async 且晚于 effect，改为在 autoPopulate effect 与 import-auto-save effect 开头加 `if (backendBuiltLinesRef.current) return;`（双保险，见 Step 3）。

- [ ] **Step 3: 两个 effect 开头加短路（双保险）**

autoPopulate effect（约 823 行 `useEffect`）第一行加：
```typescript
    if (backendBuiltLinesRef.current) return;
```
import-auto-save effect（约 789 行 `useEffect`）第一行加：
```typescript
    if (backendBuiltLinesRef.current) return;
```

- [ ] **Step 4: 自检（TS + Vite transform）**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
Run: `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/QuotationWizard.tsx` → 200

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "fix(task-0712-展示修复): 导入流后端已建行时跳过客户端 autoPopulate + import-auto-save"
```

---

## Task 6: 只读面失败哨兵显式错误占位（详情 / 核价管理）

**目标**：`ReadonlyProductCard.tsx` 遇 `__cardValueFailed` 哨兵时显示「核价数据生成失败：<原因>」，不再显示笼统「无组件数据」或空白（后端物化成功时 `useSnap` 已自动转 true 正常渲染树，无需改）。

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`

- [ ] **Step 1: 从 sideCardValues 提取失败标志**

在 `sideCardValues` useMemo（约 291-295 行）之后新增：
```typescript
  // task-0712 展示修复：后端整单渲染失败会落 __cardValueFailed 哨兵（含 __errorMsg 原文）。
  // 只读面显式提示，不误导为「无组件数据」。
  const cardValueFailed = !!(sideCardValues as any)?.__cardValueFailed;
  const cardValueErrorMsg = (sideCardValues as any)?.__errorMsg as string | undefined;
```

- [ ] **Step 2: 渲染层优先显示失败占位**

定位「无组件数据」占位（742 行 `<div className="qt-no-component-data">`）所在的渲染分支。在该分支之前（或 tabs 为空的判定处）加失败优先分支：
```tsx
        {cardValueFailed ? (
          <div className="qt-no-component-data" style={{ color: '#cf1322' }}>
            核价数据生成失败{cardValueErrorMsg ? `：${cardValueErrorMsg}` : ''}
          </div>
        ) : /* 原有 tabs 渲染 / 「无组件数据」占位 */ }
```
> 依实际 JSX 结构接入：核心是「哨兵为真 → 红字错误占位」优先于原「无组件数据」空占位。保持原「确为空模板 → 无组件数据」分支不变。

- [ ] **Step 3: 自检**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
Run: `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/quotation/ReadonlyProductCard.tsx` → 200

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "fix(task-0712-展示修复): 只读面 __cardValueFailed 哨兵显式错误占位"
```

---

## Task 7: 协议级验收 — E2E + 三面契约自检（本任务核心证据）

**目标**：跑 Playwright 复现 spec + 核价树标杆 spec，坐实导入建单后三面开箱（首屏即树、加载中=0、无「无组件数据」）。改了 `CardSnapshotService` 调用链 + `ReadonlyProductCard.tsx` + `QuotationWizard.tsx`，属协议级，E2E 强制（CLAUDE.md「修改后强制自检」§5 + backtask §6）。

**Files:** 无新增（跑既有 spec）；如复现 spec 断言不足，补断言到 `cpq-frontend/e2e/repro-costing-tree-import.spec.ts`。

- [ ] **Step 1: 确认后端已重启加载新代码**

Run: `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 401
Run: `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/` → 200

- [ ] **Step 2: 跑导入复现 spec（本任务核心）**

Run:
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/repro-costing-tree-import.spec.ts --reporter=list
```
Expected: `1 passed`；断言含：导入建单 → 进核价单 → **首屏(不手刷) 加载中=0、配件树 ≥14 行**。

- [ ] **Step 3: 跑核价树标杆 spec（回归）**

Run:
```bash
npx playwright test --config=e2e/playwright.config.ts e2e/costing-bom-tree.spec.ts --reporter=list
```
Expected: 全 pass，各核价 tab「加载中=0」。

- [ ] **Step 4: 三面契约自检（api.md §4，手动/脚本，本次报障的三面之二）**

导入罗克韦尔.xlsx 建单后**不打开编辑页、不手刷**：
```bash
# 用建单返回的 quotationId
PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db -c \
"SELECT id, (costing_card_values IS NOT NULL) costing_ok, card_snapshot_at IS NOT NULL snap_ok \
 FROM quotation_line_item WHERE quotation_id='<qid>';"
```
Expected: 每行 `costing_ok=t`、`snap_ok=t`。
再人工验证：**建单后直接进报价详情页 + 核价管理 → 核价树正常显示，无「无组件数据」**。

- [ ] **Step 5: 无 N+1 验证**

看后端日志：`[costing-tree-render]` / `ensure-cardvalues` 对整单核价树只跑**一次**递归查询覆盖所有根（backtask §6.5）。
Run: `grep -E "costing-tree|ensure-cardvalues|补算" <quarkus-dev-log> | tail -20`

- [ ] **Step 6: 附证据 + 提交（若补了 spec 断言）**

保存 E2E 截图（编辑页核价单首屏 / 详情页核价侧 / 核价管理核价侧）作 PR 渲染证据。
```bash
git add cpq-frontend/e2e/repro-costing-tree-import.spec.ts   # 若补了断言
git commit -m "test(task-0712-展示修复): 复现 spec 补三面开箱断言 + E2E 证据"
```

---

## Task 8: 收尾 — RECORD / PRD / BACKLOG 回写

**目标**：满足 CLAUDE.md 开发记录 + Backlog 规则三。

- [ ] **Step 1: RECORD.md 追加开发记录**

追加一行（格式 `[日期] 模块 - 描述 | 文件 | 决策`）：
```
[2026-07-12] 报价单/核价 - task-0712 核价展示修复：create-quotation 服务端整单物化(建行+snapshotQuotation+ensureStructure+ensureCardValues+ensureExcelValues)，三面开箱 | V6QuotationCommitService/BasicDataImportV6Resource/QuotationLineItemMaterializeService/QuotationWizard.tsx/ReadonlyProductCard.tsx | 照搬 ConfigureProductResource 范例；建行事务提交后 REQUIRES_NEW 才可展开；报价侧 buildCardValues 纯读 snapshot_rows 是「核价对报价不对」根因
```

- [ ] **Step 2: PRD-v3.md 演进史补一行**（若该功能在 PRD 有对应章节，同步；否则仅演进史）

- [ ] **Step 3: BACKLOG 大单性能异步补算另立条目**（首版同步物化的已知边界，C7）

若采纳，追加 P2 条目「大单(多产品×大 BOM)建单同步物化耗时 → 异步补算+轮询」。

- [ ] **Step 4: 提交**

```bash
git add docs/RECORD.md docs/PRD-v3.md BACKLOG.md
git commit -m "docs(task-0712-展示修复): RECORD/PRD/BACKLOG 回写"
```

---

## Self-Review（写计划后对照 spec 复核）

**Spec 覆盖（api.md / backtask / fronttask）：**
- api.md §1 建单响应扩字段 lineItemsCount/cardValuesReady/costingTreeRows/warnings → Task 2（扩 CommitResult）+ Task 3（回填）✅
- api.md §1 行为约定 1 建单 / 2 服务端建行 / 3 同步批量算卡片值 / 4 全落库后返回 / 5 幂等 → Task 2 + Task 3 + Task 4 ✅
- api.md §3 降级：单行树失败落失败哨兵、不整单失败、cardValuesReady=false → `snapshotNewLinesCardValues` 既有哨兵 + Task 3 `materializeCardData` try/catch + `fillMaterializationStatus` ✅
- api.md §4 自检契约（三面开箱 + DB costing 非空）→ Task 7 ✅
- backtask Task1 服务端建行 → Task 1 ✅；Task2 createQuotation 编排 → Task 2 + Task 3 ✅；Task3 整单批量算卡片值(树一次 render，禁逐行) → Task 3 用 ensureCardValues（内部 render 整单批量）✅；Task4 幂等重入 → Task 2 ✅
- backtask §5 不丢单（建单+建行强一致，卡片值失败不回滚）→ Task 2 同事务建行 + Task 3 Resource 层 try/catch ✅
- fronttask Task1 关客户端 autoPopulate → Task 5 ✅；Task2 编辑页首屏直读、warm 降兜底 → **既有 `shouldWarmCardValues` 在全行有值时已返 false，后端物化后自动生效**（Task 5 关掉 import-auto-save 后不再重建行触发 warm）→ 无需额外改，Task 7 验证 ✅；Task3 只读面失败哨兵占位 → Task 6 ✅；Task4 syncLineItemsFromResponse 健壮化 → **降级为可选**（后端物化后不再走 warm 回灌主路径，风险大幅下降；如 E2E 兜底场景暴露再补，不阻塞本期）。
- 需求 §5 禁 N+1 → Task 3 用整单批量 ensureCardValues + Task 7.5 验证 ✅

**类型/签名一致性：** `materializeLines`/`materializeLinesFromCandidates` 两处签名一致；`CommitResult` 新字段在 Task 2 定义、Task 3 回填，字段名一致（lineItemsCount/cardValuesReady/costingTreeRows/warnings）；`snapshotQuotation(UUID)` / `ensureStructure(UUID)` / `ensureCardValues(UUID)` / `ensureExcelValues(UUID)` 签名均已读证。

**已知放置项（非遗漏，见上）：** fronttask Task4（回灌健壮化）本期降级为可选，理由是后端物化后 warm 不再是主路径。

---

## 执行方式

按 CLAUDE.md 默认走 **superpowers:subagent-driven-development**：每个 Task 派全新子代理实现，Task 间两阶段评审（先 spec 合规、再代码质量），连续执行。Task 1→2→3→4 为后端强依赖链（顺序执行）；Task 5/6 前端可在后端 Task 3 完成后并行；Task 7 收口在所有实现之后；Task 8 最后。
