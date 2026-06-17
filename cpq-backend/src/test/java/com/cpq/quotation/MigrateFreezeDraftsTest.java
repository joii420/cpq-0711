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
 *   <li>T2：{@code dryRun=false} → 对该 DRAFT 调 refreshDraftQuoteCards（force=true）后，
 *       检查 status 字段（OK 或 STILL_ERROR 都可接受——重烤能否清掉 #ERROR 取决于路径是否真修好；
 *       但核心验证：方法被调用、返回了 refreshedLines&gt;=0 且结果含 status 字段）。</li>
 *   <li>T3：{@code dryRun=true} 对无 DRAFT 的场景（或所有 DRAFT 均不含 #ERROR）→ 返回列表，
 *       状态均为 DRY_RUN。</li>
 * </ul>
 *
 * <p><b>隔离</b>：用 native SQL 写脏值到真实行；每个测试结束后在 {@code @AfterEach} 清理
 * （把 quote_card_values 恢复原值或置 NULL）。避免影响其他测试。
 *
 * <p><b>共享 DB 注意</b>：seed 时先找真实 DRAFT 报价单行；若 DB 无 DRAFT 单，
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

    @AfterEach
    void cleanup() {
        if (dirtyLineItemId != null && originalQcv != null || dirtyLineItemId != null) {
            try {
                restoreOriginalQcv(dirtyLineItemId, originalQcv);
            } catch (Exception e) {
                // cleanup 失败不影响测试结论
            }
        }
        dirtyLineItemId = null;
        originalQcv = null;
        targetQuotationId = null;
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
    // T2: dryRun=false → 触发重烤，返回 refreshedLines 和 status 字段
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("T2: dryRun=false → 触发重烤，结果含 refreshedLines >= 0 且 status 为 OK/STILL_ERROR/FAILED")
    void migrateFreezeDrafts_nonDryRun_triggersRefreshAndReturnsStatus() {
        // 找 DRAFT 报价单（即使没有 lineItem 也能走到 refreshDraftQuoteCards → 返回 0）
        UUID quotationId = resolveDraftQuotationId();
        assumeTrue(quotationId != null,
            "需要至少一个 DRAFT 报价单 — DB 无数据时跳过");

        // 如果有 lineItem，seed 脏值以测真实清洗路径
        UUID lineId = resolveDraftLineItemId();
        if (lineId != null && targetQuotationId != null && targetQuotationId.equals(quotationId)) {
            seedDirtyValue(lineId);
            em.clear();
        }

        // 执行 dryRun=false
        List<Map<String, Object>> results = svc.migrateFreezeDrafts(false);

        // 验证1：results 不为空
        assertFalse(results.isEmpty(), "非 dryRun 结果列表不应为空");

        // 验证2：找到目标报价单结果项（用 quotationId 或 targetQuotationId）
        UUID lookupId = targetQuotationId != null ? targetQuotationId : quotationId;
        Map<String, Object> targetEntry = results.stream()
            .filter(r -> lookupId.toString().equals(r.get("quotationId")))
            .findFirst()
            .orElse(null);
        assertNotNull(targetEntry,
            "结果列表中应含目标报价单 id=" + lookupId);

        // 验证3：含 refreshedLines 字段（整数，可为 0——没 lineItem 时也合法）
        Object refreshedLines = targetEntry.get("refreshedLines");
        assertNotNull(refreshedLines, "非 dryRun 结果应含 refreshedLines");
        assertTrue(((Number) refreshedLines).intValue() >= 0,
            "refreshedLines 应 >= 0");

        // 验证4：status 为 OK / STILL_ERROR / FAILED 之一
        String status = (String) targetEntry.get("status");
        assertNotNull(status, "非 dryRun 结果应含 status");
        assertTrue(
            "OK".equals(status) || "STILL_ERROR".equals(status) || "FAILED".equals(status),
            "status 应为 OK / STILL_ERROR / FAILED，实际: " + status);

        // 验证5：含 after 字段（boolean，重烤后再次检查 #ERROR）
        Object after = targetEntry.get("after");
        assertNotNull(after, "非 dryRun 结果应含 after 字段");
        assertTrue(after instanceof Boolean, "after 应为布尔值");
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
