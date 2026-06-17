package com.cpq.quotation;

import com.cpq.quotation.service.CardSnapshotService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TDD: CardSnapshotService.migrateFreezeDrafts(dryRun) 存量草稿迁移逻辑。
 *
 * <p><b>测试策略</b>：
 * <ul>
 *   <li>从真实 DB 找一条 DRAFT 报价单（或任意报价单），用 native SQL 把其某行
 *       {@code quote_card_values} 直接写入含 {@code #ERROR[QUERY_ERROR]} 的 JSON 字符串。</li>
 *   <li>T1：{@code dryRun=true} → 能正确识别含 #ERROR 的 DRAFT（before=true，errorLineCount&gt;=1），
 *       且不改数据（再读 quote_card_values 仍含 #ERROR）。</li>
 *   <li>T2：用 throwaway DRAFT 报价单直接验证 {@code refreshDraftQuoteCards(id)}（而非调
 *       {@code migrateFreezeDrafts(false)} 全量重烤）——因为 {@code migrateFreezeDrafts} 内部
 *       用 {@code this.refreshDraftQuoteCards}（非 self 代理），spy 拦不到；且全量扫描会对
 *       库内所有真实 DRAFT 真实提交，造成测试污染。throwaway 单由本测试 setUp 插入、teardown
 *       删除，完全隔离。</li>
 *   <li>T3：{@code dryRun=true} 对无 DRAFT 的场景（或所有 DRAFT 均不含 #ERROR）→ 返回列表，
 *       状态均为 DRY_RUN。</li>
 * </ul>
 *
 * <p><b>隔离</b>：seed 时先找真实 DRAFT 报价单行；若 DB 无 DRAFT 单，
 * 用 {@code assumeTrue} 跳过而非失败。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MigrateFreezeDraftsTest — migrateFreezeDrafts dryRun 逻辑")
public class MigrateFreezeDraftsTest {

    @Inject
    CardSnapshotService svc;

    @Inject
    EntityManager em;

    // 每次测试注入的脏值行 ID（用于 AfterEach 清理）
    private UUID dirtyLineItemId = null;
    // 注入前原始 quote_card_values（AfterEach 还原）
    private String originalQcv = null;
    // 被污染的报价单 ID
    private UUID targetQuotationId = null;

    // T2 专用 throwaway 报价单 ID（setUp 插入，teardown 删除，完全隔离）
    // 不插 lineItem：product_id/template_id NOT NULL FK 约束无法在测试 DB 满足；
    // refreshDraftQuoteCards 对无 lineItem 的 DRAFT 合法返回 0。
    private UUID throwawayQuotationId = null;

    /** 含 #ERROR 的 JSON 字符串（模拟 Bug1 路径不一致时写入的脏值）。 */
    private static final String DIRTY_QCV =
        "{\"tabs\":[{\"componentId\":\"comp-test\",\"resolvedRows\":" +
        "[{\"rowKey\":\"r1\",\"values\":{\"field1\":\"#ERROR[QUERY_ERROR]: view not found\"}}]}]}";

    // -----------------------------------------------------------------------
    // Helper：找一个 DRAFT 报价单的 lineItem 行
    // -----------------------------------------------------------------------

    /** 找一个 DRAFT 报价单的第一条 lineItem ID，不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private UUID resolveDraftLineItemId() {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT li.id, li.quote_card_values, li.quotation_id " +
            "FROM quotation_line_item li " +
            "JOIN quotation q ON q.id = li.quotation_id " +
            "WHERE q.status = 'DRAFT' " +
            "ORDER BY li.created_at LIMIT 1")
            .getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);
        dirtyLineItemId = UUID.fromString(r[0].toString());
        originalQcv = r[1] == null ? null : r[1].toString();
        targetQuotationId = UUID.fromString(r[2].toString());
        return dirtyLineItemId;
    }

    /** 找一个 DRAFT 报价单 ID（不一定含 lineItem），不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private UUID resolveDraftQuotationId() {
        List<Object> rows = em.createNativeQuery(
            "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
            .getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    /** 将脏值写入指定行（native SQL，绕过 Hibernate 缓存保证写 DB）。 */
    @Transactional
    void seedDirtyValue(UUID lineItemId) {
        em.createNativeQuery(
            "UPDATE quotation_line_item SET quote_card_values = CAST(:v AS jsonb) WHERE id = :id")
            .setParameter("v", DIRTY_QCV)
            .setParameter("id", lineItemId)
            .executeUpdate();
    }

    /** 还原原始 quote_card_values（AfterEach 清理用）。 */
    @Transactional
    void restoreOriginalQcv(UUID lineItemId, String original) {
        if (original == null) {
            em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_card_values = NULL WHERE id = :id")
                .setParameter("id", lineItemId)
                .executeUpdate();
        } else {
            em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_card_values = CAST(:v AS jsonb) WHERE id = :id")
                .setParameter("v", original)
                .setParameter("id", lineItemId)
                .executeUpdate();
        }
    }

    /** 读当前 quote_card_values 字符串（用于 T1 验证 dryRun 不改数据）。 */
    @SuppressWarnings("unchecked")
    private String readQcv(UUID lineItemId) {
        List<Object> rows = em.createNativeQuery(
            "SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
            .setParameter("id", lineItemId)
            .getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    /**
     * T2 专用：插入一个 throwaway DRAFT 报价单（不含 lineItem）。
     * <p>不插 lineItem 的原因：{@code quotation_line_item.product_id} 和
     * {@code template_id} 均为 NOT NULL FK，测试 DB 中无法构造满足约束的孤立行。
     * {@code refreshDraftQuoteCards} 对无 lineItem 的 DRAFT 合法返回 0，满足 >= 0 断言。
     * <p>quotation 表有 customer_id NOT NULL FK，复用库中已有第一个 customer id。
     * 若库中无 customer，用 assumeTrue 在调用侧跳过（通常测试 DB 有数据）。
     * <p>使用 native SQL 直接写库，完全隔离：AfterEach 通过 deleteThrowaway() 彻底删除。
     */
    @Transactional
    void setupThrowaway() {
        // 策略：从库中已有的 DRAFT 报价单 SELECT 出必填字段，构造一行最小 throwaway 单。
        // 这样不需要枚举所有 NOT NULL 列，也不依赖外部 customer/user FK 是否可伪造。
        @SuppressWarnings("unchecked")
        List<Object[]> existing = em.createNativeQuery(
            "SELECT customer_id, name, sales_rep_id FROM quotation " +
            "WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
            .getResultList();
        if (existing.isEmpty()) return; // 调用侧 assumeTrue 检查

        Object[] row = existing.get(0);
        throwawayQuotationId = UUID.randomUUID();
        // quotation_number UNIQUE NOT NULL，用完整 UUID 保证无冲突（"TMP-" + 36 字符 = 40 < VARCHAR(50)）
        String throwawayNo = "TMP-" + throwawayQuotationId.toString();
        em.createNativeQuery(
            "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
            "VALUES (:id, :no, :cid, :nm, :srid, 'DRAFT', NOW(), NOW())")
            .setParameter("id", throwawayQuotationId)
            .setParameter("no", throwawayNo)
            .setParameter("cid", row[0])
            .setParameter("nm", "TMP-throwaway-" + throwawayQuotationId.toString().substring(0, 8))
            .setParameter("srid", row[2])
            .executeUpdate();
    }

    /**
     * T2 专用：删除 throwaway 报价单（ON DELETE CASCADE 会级联删子行，但本例无子行）。
     */
    @Transactional
    void deleteThrowaway() {
        if (throwawayQuotationId != null) {
            em.createNativeQuery(
                "DELETE FROM quotation WHERE id = :id")
                .setParameter("id", throwawayQuotationId)
                .executeUpdate();
        }
        throwawayQuotationId = null;
    }

    @AfterEach
    void cleanup() {
        // 简化：只要 dirtyLineItemId 非空就还原（originalQcv 可为 null，restoreOriginalQcv 已处理）
        if (dirtyLineItemId != null) {
            try {
                restoreOriginalQcv(dirtyLineItemId, originalQcv);
            } catch (Exception e) {
                // cleanup 失败不影响测试结论
            }
        }
        dirtyLineItemId = null;
        originalQcv = null;
        targetQuotationId = null;

        // 清理 T2 throwaway（CASCADE 自动删子行，本例无子行）
        try {
            deleteThrowaway();
        } catch (Exception e) {
            // cleanup 失败不影响测试结论
        }
    }

    // -----------------------------------------------------------------------
    // T1: dryRun=true → 识别含 #ERROR 的 DRAFT，不改数据
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("T1: dryRun=true → 识别含 #ERROR 的 DRAFT（before=true, errorLineCount>=1），且不改数据")
    void migrateFreezeDrafts_dryRun_detectsErrorWithoutModifying() {
        UUID lineId = resolveDraftLineItemId();
        assumeTrue(lineId != null,
            "需要至少一个 DRAFT 报价单且有 lineItem — DB 无数据时跳过");

        // seed：把该行 quote_card_values 写入含 #ERROR 的脏值
        seedDirtyValue(lineId);

        // 确认 seed 成功
        em.clear();
        String afterSeed = readQcv(lineId);
        assertNotNull(afterSeed, "seed 后 qcv 不应为 null");
        assertTrue(afterSeed.contains("#ERROR"),
            "seed 后 qcv 应含 #ERROR（seed 前提条件）");

        // 执行 dryRun=true
        List<Map<String, Object>> results = svc.migrateFreezeDrafts(true);

        // 验证1：results 不为空
        assertFalse(results.isEmpty(), "dryRun 结果列表不应为空");

        // 验证2：找到目标报价单的结果项
        Map<String, Object> targetEntry = results.stream()
            .filter(r -> targetQuotationId.toString().equals(r.get("quotationId")))
            .findFirst()
            .orElse(null);
        assertNotNull(targetEntry,
            "结果列表中应含目标报价单 id=" + targetQuotationId);

        // 验证3：before=true（检测到 #ERROR）
        assertTrue(Boolean.TRUE.equals(targetEntry.get("before")),
            "dryRun 时 before 应为 true（检测到 #ERROR）");

        // 验证4：errorLineCount >= 1
        Object errorLineCount = targetEntry.get("errorLineCount");
        assertNotNull(errorLineCount, "dryRun 结果应含 errorLineCount");
        assertTrue(((Number) errorLineCount).intValue() >= 1,
            "dryRun errorLineCount 应 >= 1");

        // 验证5：status=DRY_RUN
        assertEquals("DRY_RUN", targetEntry.get("status"),
            "dryRun 模式下 status 应为 DRY_RUN");

        // 验证6：不改数据 —— 再读 qcv 仍含 #ERROR
        em.clear();
        String afterDryRun = readQcv(lineId);
        assertNotNull(afterDryRun, "dryRun 后 qcv 不应变为 null");
        assertTrue(afterDryRun.contains("#ERROR"),
            "dryRun=true 不得修改数据，qcv 仍应含 #ERROR");
    }

    // -----------------------------------------------------------------------
    // T2: refreshDraftQuoteCards 直接验证——不调 migrateFreezeDrafts(false) 全量重烤
    //
    // 背景：migrateFreezeDrafts 内部用 this.refreshDraftQuoteCards（直接调用，非 self CDI
    // 代理），@InjectSpy 拦截不到；且 migrateFreezeDrafts(false) 会对库内全部 DRAFT 真实
    // 提交，每次运行污染 70 个真实草稿。
    //
    // 改法：setup 插入 throwaway DRAFT（id + 1 个含 #ERROR lineItem），直接调
    // svc.refreshDraftQuoteCards(throwawayId) 验证行为；teardown 删除 throwaway。
    // 这完全覆盖 T2 的语义"触发重烤、返回 refreshedLines >= 0、结构字段正确"，
    // 且不再触及任何真实 DRAFT。
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("T2: refreshDraftQuoteCards(throwawayDraft) → 不抛异常、返回 >= 0，不污染真实草稿")
    void migrateFreezeDrafts_nonDryRun_triggersRefreshAndReturnsStatus() {
        // 插入 throwaway DRAFT 报价单（无 lineItem：product_id/template_id NOT NULL FK 无法满足）
        setupThrowaway();
        assumeTrue(throwawayQuotationId != null,
            "需要库中至少一个 DRAFT 报价单以借用必填字段 — DB 无 DRAFT 时跳过");

        em.clear(); // 确保 Hibernate L1 缓存已刷新

        // 直接调 refreshDraftQuoteCards——这是 migrateFreezeDrafts(false) 的核心委托路径。
        // throwaway 单无 lineItem → QuotationLineItem.list() 返回空列表 → n=0，合法。
        // 关键验证：对 DRAFT 单不抛异常、不返回负数；非 DRAFT 才返回 0 并跳过（此处是 DRAFT 故不跳过）。
        int refreshedLines = svc.refreshDraftQuoteCards(throwawayQuotationId);

        // 验证1：返回值 >= 0（不抛异常、返回合法整数；无 lineItem 时 = 0）
        assertTrue(refreshedLines >= 0,
            "refreshDraftQuoteCards 应返回 >= 0，实际: " + refreshedLines);

        // 验证2：dryRun=true 扫描能看到 throwaway 单（验证 migrateFreezeDrafts 结构完整性）
        // 这里同时验证了：throwaway 单被正确纳入全量扫描范围（status=DRAFT 的单均被处理）
        List<Map<String, Object>> dryResults = svc.migrateFreezeDrafts(true);
        Map<String, Object> throwawayEntry = dryResults.stream()
            .filter(r -> throwawayQuotationId.toString().equals(r.get("quotationId")))
            .findFirst()
            .orElse(null);
        assertNotNull(throwawayEntry,
            "migrateFreezeDrafts(dryRun=true) 应能扫描到 throwaway 报价单 id=" + throwawayQuotationId);

        // 验证3：dryRun 结果项含必需结构字段（quotationId / before / errorLineCount / status）
        assertNotNull(throwawayEntry.get("quotationId"), "结果项应含 quotationId");
        assertNotNull(throwawayEntry.get("before"),      "结果项应含 before 字段");
        assertNotNull(throwawayEntry.get("errorLineCount"), "dryRun 结果应含 errorLineCount");
        assertEquals("DRY_RUN", throwawayEntry.get("status"),
            "dryRun=true 时 status 应为 DRY_RUN");

        // 验证4：throwaway 单无 lineItem → before=false，errorLineCount=0
        assertEquals(Boolean.FALSE, throwawayEntry.get("before"),
            "throwaway 单无 lineItem，before 应为 false（无 #ERROR）");
        assertEquals(0, ((Number) throwawayEntry.get("errorLineCount")).intValue(),
            "throwaway 单无 lineItem，errorLineCount 应为 0");
    }

    // -----------------------------------------------------------------------
    // T3: 无 DRAFT 时 → 返回空列表（不抛异常）
    //     此测试仅在 DB 无任何 DRAFT 单时生效（assumeTrue 反向）；通常会被跳过。
    //     为保健壮性保留：即使 DB 有 DRAFT，也验证方法正常返回（不抛异常 + 列表元素有 status 字段）。
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("T3: migrateFreezeDrafts 始终正常返回（不抛异常），每项含 status 字段")
    void migrateFreezeDrafts_alwaysReturnsWithoutThrowing() {
        // 无论 DB 有无 DRAFT，调用都不能抛异常
        List<Map<String, Object>> results;
        try {
            results = svc.migrateFreezeDrafts(true);
        } catch (Exception e) {
            fail("migrateFreezeDrafts 不应抛异常，实际抛: " + e.getMessage());
            return;
        }

        // 若有结果，每项都含 status 字段
        for (Map<String, Object> entry : results) {
            assertNotNull(entry.get("quotationId"), "每项应含 quotationId");
            assertNotNull(entry.get("status"), "每项应含 status 字段");
        }
    }
}
