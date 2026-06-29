package com.cpq.component.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.formula.dataloader.SnapshotRowsContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 等价护栏(2026-06-26):证明 batch-expand「批量预载 snapshot 读」与原「逐 task 查库读」
 * 对每个 (lineItemId, componentId) 返回的 ExpandDriverResponse <b>逐位相同</b>(rows / rowCount / driverPath)。
 *
 * <p>两条路径:
 * <ul>
 *   <li>A(基线·逐 task):上下文未设 → {@code expandWithSnapshot} 走每对一次 {@code SELECT snapshot_rows ... LIMIT 1}。</li>
 *   <li>B(新·批量):{@code SnapshotRowsContext.set(prefetchSnapshotRows(allLineIds))} 后 → snapshot-read 命中 ThreadLocal,零往返。</li>
 * </ul>
 *
 * <p>只读,不写库。基准单据 {@code 87af5786}(用户实测 616 task 全快照,导入后进报价 20s 的那单);
 * 不存在则回退取任意含 snapshot 的报价单。
 */
@QuarkusTest
class BatchExpandSnapshotPrefetchEquivTest {

    private static final UUID PREFERRED_QUOTATION_ID =
            UUID.fromString("87af5786-5e1e-4598-90fa-9996e1927946");

    @Inject
    ComponentDriverService driverService;

    @Inject
    EntityManager em;

    private static final ObjectMapper M = new ObjectMapper();

    private static String fingerprint(ExpandDriverResponse r) throws Exception {
        if (r == null) return "NULL";
        // rows + rowCount + driverPath 逐位序列化作指纹
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("driverPath", r.driverPath);
        snap.put("rowCount", r.rowCount);
        snap.put("rows", r.rows);
        return M.writeValueAsString(snap);
    }

    @Test
    void prefetchReadEqualsPerTaskRead() throws Exception {
        UUID quotationId = resolveQuotationId();
        assertNotNull(quotationId, "库中应能找到至少一张含 snapshot 的报价单");

        List<UUID> lineIds = loadLineItemIds(quotationId);
        List<UUID[]> pairs = loadSnapshotPairs(quotationId); // [lineItemId, componentId]
        assertFalse(pairs.isEmpty(), "基准单据应至少有一对 (line, comp) 含 snapshot_rows");

        // ── 路径 A:逐 task(上下文未设)──
        assertFalse(SnapshotRowsContext.isSet(), "用例起始上下文必须为空");
        Map<String, String> perTask = new LinkedHashMap<>();
        Map<String, String> perTaskDriverPath = new LinkedHashMap<>();
        for (UUID[] p : pairs) {
            ExpandDriverResponse r = driverService.expandWithSnapshot(
                    p[1], null, null, null, null, null, p[0], null, null);
            String k = p[0] + "|" + p[1];
            perTask.put(k, fingerprint(r));
            perTaskDriverPath.put(k, r == null ? null : r.driverPath);
        }

        // ── 路径 B:批量预载(上下文已设)──
        Map<String, String> prefetched = driverService.prefetchSnapshotRows(lineIds);
        assertFalse(prefetched.isEmpty(), "prefetch 应载到该单据的 snapshot");
        SnapshotRowsContext.set(prefetched);
        Map<String, String> batch = new LinkedHashMap<>();
        try {
            assertTrue(SnapshotRowsContext.isSet(), "上下文应已设");
            for (UUID[] p : pairs) {
                ExpandDriverResponse r = driverService.expandWithSnapshot(
                        p[1], null, null, null, null, null, p[0], null, null);
                batch.put(p[0] + "|" + p[1], fingerprint(r));
            }
        } finally {
            SnapshotRowsContext.clear();
        }
        assertFalse(SnapshotRowsContext.isSet(), "finally 后上下文必须清空");

        // ── 对账:键集合 + 每对指纹逐位相等 ──
        assertEquals(perTask.keySet(), batch.keySet(), "两路径 (line|comp) 键集合应相同");
        int snapHits = 0;
        for (String k : perTask.keySet()) {
            assertEquals(perTask.get(k), batch.get(k),
                    "pair=" + k + " ExpandDriverResponse(rows/rowCount/driverPath) 应逐位相等");
            if ("snapshot".equals(perTaskDriverPath.get(k))) snapHits++;
        }
        assertEquals(pairs.size(), snapHits,
                "所有对都应命中 snapshot 分支(driverPath=snapshot),否则基准选错或快照缺失");

        System.out.printf("[equiv] quotation=%s lines=%d pairs=%d 全部逐位等价 ✅ (snapHits=%d)%n",
                quotationId, lineIds.size(), pairs.size(), snapHits);
    }

    @Test
    void prefetchEmptyInput_returnsEmpty() {
        assertTrue(driverService.prefetchSnapshotRows(Collections.emptyList()).isEmpty(),
                "空入参 → 空 map(避免 IN () 语法错)");
        assertTrue(driverService.prefetchSnapshotRows(null).isEmpty(), "null 入参 → 空 map");
    }

    // ------------------------------------------------------------------
    @Transactional
    UUID resolveQuotationId() {
        @SuppressWarnings("unchecked")
        List<Object> check = em.createNativeQuery(
                "SELECT COUNT(*) FROM quotation_line_component_data d " +
                "JOIN quotation_line_item li ON li.id=d.line_item_id " +
                "WHERE li.quotation_id = :q AND d.snapshot_rows IS NOT NULL")
                .setParameter("q", PREFERRED_QUOTATION_ID).getResultList();
        if (!check.isEmpty() && ((Number) check.get(0)).longValue() > 0) return PREFERRED_QUOTATION_ID;
        @SuppressWarnings("unchecked")
        List<Object> fb = em.createNativeQuery(
                "SELECT li.quotation_id FROM quotation_line_component_data d " +
                "JOIN quotation_line_item li ON li.id=d.line_item_id " +
                "WHERE d.snapshot_rows IS NOT NULL GROUP BY li.quotation_id LIMIT 1")
                .getResultList();
        return fb.isEmpty() ? null : UUID.fromString(fb.get(0).toString());
    }

    @Transactional
    List<UUID> loadLineItemIds(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q ORDER BY created_at")
                .setParameter("q", quotationId).getResultList();
        List<UUID> out = new ArrayList<>();
        for (Object r : rows) if (r != null) out.add(UUID.fromString(r.toString()));
        return out;
    }

    @Transactional
    List<UUID[]> loadSnapshotPairs(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DISTINCT d.line_item_id, d.component_id FROM quotation_line_component_data d " +
                "JOIN quotation_line_item li ON li.id=d.line_item_id " +
                "WHERE li.quotation_id = :q AND d.snapshot_rows IS NOT NULL " +
                "ORDER BY d.line_item_id, d.component_id")
                .setParameter("q", quotationId).getResultList();
        List<UUID[]> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            out.add(new UUID[]{ UUID.fromString(r[0].toString()), UUID.fromString(r[1].toString()) });
        }
        return out;
    }
}
