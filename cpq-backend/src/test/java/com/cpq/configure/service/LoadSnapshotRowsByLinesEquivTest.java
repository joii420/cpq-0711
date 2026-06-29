package com.cpq.configure.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2 等价护栏：证明 {@link ConfigureSnapshotService#loadSnapshotRowsByLines}
 * 与对每行调用 {@link ConfigureSnapshotService#loadSnapshotRowsByComp} 的并集结果
 * **逐键逐值相同**（含 null 值一致）。
 *
 * <p>测试策略：只读，直接对库里真实报价单（罗克韦尔 {@code 8f0c37a4-8186-4f5e-a9ca-358bd2d9662d}）
 * 的全部 lineItemId 进行对账；如果该报价单不存在，则动态取库中任意有数据的报价单。不写库。
 *
 * <p>覆盖：
 * <ul>
 *   <li>TC-1：真实报价单全行，整单查 == 逐行查并集（含 null 值一致）</li>
 *   <li>TC-2：空集合入参 → 返空 map，不抛异常</li>
 *   <li>TC-3：单行报价单退化验证（getOrDefault 空 map 与 loadSnapshotRowsByComp 空结果等价）</li>
 * </ul>
 */
@QuarkusTest
class LoadSnapshotRowsByLinesEquivTest {

    /** 计划指定的真实报价单（罗克韦尔）；如不存在则动态取。 */
    private static final UUID PREFERRED_QUOTATION_ID =
            UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Inject
    ConfigureSnapshotService svc;

    @Inject
    EntityManager em;

    // ------------------------------------------------------------------
    // TC-1: 整单查 == 逐行查并集（真实报价单）
    // ------------------------------------------------------------------

    @Test
    void tc1_bulkEqualsPerRowUnion_realQuotation() {
        UUID quotationId = resolveQuotationId();
        assertNotNull(quotationId, "库中应能找到至少一张含 quotation_line_item 的报价单");

        List<UUID> allLineIds = loadLineItemIds(quotationId);
        assertFalse(allLineIds.isEmpty(), "选定报价单应有至少一行 quotation_line_item");

        // ── 路径 A：整单一次查 ──
        Map<UUID, Map<UUID, String>> byLineBulk = svc.loadSnapshotRowsByLines(allLineIds);

        // ── 路径 B：对每行调逐行查，构造并集 ──
        Map<UUID, Map<UUID, String>> byLinePerRow = new LinkedHashMap<>();
        for (UUID lid : allLineIds) {
            Map<UUID, String> perRowResult = svc.loadSnapshotRowsByComp(lid);
            if (!perRowResult.isEmpty()) {
                byLinePerRow.put(lid, perRowResult);
            }
            // 注意：loadSnapshotRowsByComp 对无数据行返空 map（不含 key），
            // loadSnapshotRowsByLines 的 byLine 也不含该 lineItemId key；两侧均需 getOrDefault(lid, Map.of())
        }

        // ── 对账：外层键集合相同 ──
        assertEquals(byLinePerRow.keySet(), byLineBulk.keySet(),
                "整单查与逐行查的 lineItemId 键集合应相同");

        // ── 对账：每行内层逐键逐值 ──
        for (UUID lid : byLinePerRow.keySet()) {
            Map<UUID, String> bulk = byLineBulk.getOrDefault(lid, Map.of());
            Map<UUID, String> perRow = byLinePerRow.getOrDefault(lid, Map.of());

            assertEquals(perRow.keySet(), bulk.keySet(),
                    "line=" + lid + " componentId 键集合应相同");

            for (UUID cid : perRow.keySet()) {
                String bulkVal = bulk.get(cid);
                String perRowVal = perRow.get(cid);
                // null 值也要求一致（loadSnapshotRowsByComp 中 null 列返 null 值，整单查同）
                assertEquals(perRowVal, bulkVal,
                        "line=" + lid + " comp=" + cid + " snapshot_rows 值应逐位相等（含 null）");
            }
        }

        System.out.printf("[tc1] quotation=%s lineCount=%d bulkKeys=%d perRowKeys=%d%n",
                quotationId, allLineIds.size(), byLineBulk.size(), byLinePerRow.size());
    }

    // ------------------------------------------------------------------
    // TC-2: 空集合入参不抛异常，返空 map
    // ------------------------------------------------------------------

    @Test
    void tc2_emptyCollection_returnsEmptyMap() {
        Map<UUID, Map<UUID, String>> result = svc.loadSnapshotRowsByLines(Collections.emptyList());
        assertNotNull(result, "空集合入参应返非 null 空 map");
        assertTrue(result.isEmpty(), "空集合入参应返空 map（避免 IN () 语法错）");
    }

    // ------------------------------------------------------------------
    // TC-3: 单行退化验证 — getOrDefault(lid, Map.of()) 行为与 loadSnapshotRowsByComp 一致
    // ------------------------------------------------------------------

    @Test
    void tc3_singleLine_getOrDefaultEqualsPerRow() {
        UUID quotationId = resolveQuotationId();
        if (quotationId == null) return; // 跳过：无数据

        List<UUID> allLineIds = loadLineItemIds(quotationId);
        if (allLineIds.isEmpty()) return; // 跳过

        // 取第一行
        UUID lid = allLineIds.get(0);

        // 整单查（单行退化）
        Map<UUID, Map<UUID, String>> bulkResult = svc.loadSnapshotRowsByLines(List.of(lid));
        Map<UUID, String> bulkForLine = bulkResult.getOrDefault(lid, Map.of());

        // 逐行查
        Map<UUID, String> perRowForLine = svc.loadSnapshotRowsByComp(lid);

        assertEquals(perRowForLine.keySet(), bulkForLine.keySet(),
                "单行退化：componentId 键集合应相同");

        for (UUID cid : perRowForLine.keySet()) {
            assertEquals(perRowForLine.get(cid), bulkForLine.get(cid),
                    "单行退化 comp=" + cid + " snapshot_rows 值应相等（含 null）");
        }
        System.out.printf("[tc3] line=%s compCount=%d%n", lid, perRowForLine.size());
    }

    // ------------------------------------------------------------------
    // 辅助方法（只读）
    // ------------------------------------------------------------------

    /** 优先取 PREFERRED_QUOTATION_ID；不存在则取任意有 line_item 的报价单。 */
    @Transactional
    UUID resolveQuotationId() {
        @SuppressWarnings("unchecked")
        List<Object> check = em.createNativeQuery(
                "SELECT COUNT(*) FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", PREFERRED_QUOTATION_ID)
                .getResultList();
        if (!check.isEmpty()) {
            Number cnt = (Number) check.get(0);
            if (cnt.longValue() > 0) return PREFERRED_QUOTATION_ID;
        }
        // 回退：取任意有 line_item 的报价单
        @SuppressWarnings("unchecked")
        List<Object> fallback = em.createNativeQuery(
                "SELECT quotation_id FROM quotation_line_item " +
                "GROUP BY quotation_id HAVING COUNT(*) >= 1 LIMIT 1")
                .getResultList();
        if (fallback.isEmpty()) return null;
        return UUID.fromString(fallback.get(0).toString());
    }

    @Transactional
    List<UUID> loadLineItemIds(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q ORDER BY created_at")
                .setParameter("q", quotationId)
                .getResultList();
        List<UUID> out = new ArrayList<>();
        for (Object r : rows) {
            if (r != null) out.add(UUID.fromString(r.toString()));
        }
        return out;
    }
}
