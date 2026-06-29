package com.cpq.configure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1 等价护栏：证明 {@link ConfigureSnapshotService#writeSnapshotBatch} +
 * {@link ConfigureSnapshotService#writeRowDataBatch} 与原逐行
 * {@link ConfigureSnapshotService#writeSnapshot} + {@link ConfigureSnapshotService#writeRowData}
 * 产生 **逐位相同** 的 quotation_line_component_data 落库状态。
 *
 * <p>测试策略：从 DB 动态取两条真实 quotation_line_item.id 作为 scratch line（满足外键约束），
 * 测试前后 DELETE 清理，不污染共享 DB。
 *
 * <p>覆盖：A/B 逐位 / UPSERT 保留语义（snapshot不清row_data / row_data不清snapshot）/
 * tab_name COALESCE（预置值不被覆盖）/ NULL rowsJson 落 NULL jsonb（不抛类型错）。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstSaveBatchWriteEquivTest {

    @Inject
    ConfigureSnapshotService svc;

    @Inject
    EntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 3 个 scratch component UUID（不要求存在于 component 表，QLCD 无外键约束 component_id）
    static final UUID CID1 = UUID.fromString("00000000-0000-0000-0001-000000000001");
    static final UUID CID2 = UUID.fromString("00000000-0000-0000-0001-000000000002");
    static final UUID CID3 = UUID.fromString("00000000-0000-0000-0001-000000000003");

    // 测试用的两条真实 line_item_id（满足外键）—— 在 @BeforeEach 第一次时动态查
    UUID lineSeq;   // 逐行路径 scratch
    UUID lineBatch; // 批量路径 scratch

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Transactional
    void initScratchLines() {
        if (lineSeq != null) return;
        // 动态取两条真实 line_item_id，满足外键约束
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery(
                "SELECT id FROM quotation_line_item ORDER BY created_at LIMIT 2")
                .getResultList();
        if (ids.size() < 2) {
            throw new IllegalStateException(
                    "测试 DB 中 quotation_line_item 行数不足 2，无法运行等价测试");
        }
        lineSeq   = UUID.fromString(ids.get(0).toString());
        lineBatch = UUID.fromString(ids.get(1).toString());
    }

    @Transactional
    void cleanup() {
        if (lineSeq == null || lineBatch == null) return;
        em.createNativeQuery(
                "DELETE FROM quotation_line_component_data " +
                "WHERE line_item_id IN (:a, :b) " +
                "  AND component_id IN (:c1, :c2, :c3)")
                .setParameter("a", lineSeq)
                .setParameter("b", lineBatch)
                .setParameter("c1", CID1)
                .setParameter("c2", CID2)
                .setParameter("c3", CID3)
                .executeUpdate();
    }

    @BeforeEach void before() { initScratchLines(); cleanup(); }
    @AfterEach  void after()  { cleanup(); }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 读某 lineItem 下三个 scratch 组件的 (snapshot_rows, row_data, tab_name) 快照 */
    @Transactional
    Map<String, String> snapshot(UUID lineItemId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT component_id::text, " +
                "       snapshot_rows::text, " +
                "       row_data::text, " +
                "       tab_name " +
                "FROM quotation_line_component_data " +
                "WHERE line_item_id = :li " +
                "  AND component_id IN (:c1, :c2, :c3) " +
                "ORDER BY component_id")
                .setParameter("li", lineItemId)
                .setParameter("c1", CID1)
                .setParameter("c2", CID2)
                .setParameter("c3", CID3)
                .getResultList();
        Map<String, String> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String cid  = r[0] != null ? r[0].toString() : "NULL";
            String snap = r[1] != null ? r[1].toString() : "NULL";
            String rd   = r[2] != null ? r[2].toString() : "NULL";
            String tab  = r[3] != null ? r[3].toString() : "NULL";
            out.put(cid, "snap=" + snap + "|rd=" + rd + "|tab=" + tab);
        }
        return out;
    }

    static String rows(String tag) {
        return "[{\"tag\":\"" + tag + "\"}]";
    }

    static ArrayNode rowData(String tag) {
        ArrayNode arr = MAPPER.createArrayNode();
        arr.addObject().put("rd_tag", tag);
        return arr;
    }

    // ------------------------------------------------------------------
    // TC-1: A/B 逐位等价（首存全新行）
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void tc1_abEquiv_newRows() throws Exception {
        String rows1 = rows("snap-cid1"); String rows2 = rows("snap-cid2"); String rows3 = rows("snap-cid3");
        ArrayNode rd1 = rowData("rd-cid1"); ArrayNode rd2 = rowData("rd-cid2"); ArrayNode rd3 = rowData("rd-cid3");

        // ── 路径 A：逐行 ──
        svc.writeSnapshot(lineSeq, CID1, "Tab1", rows1);
        svc.writeSnapshot(lineSeq, CID2, "Tab2", rows2);
        svc.writeSnapshot(lineSeq, CID3, "Tab3", rows3);
        svc.writeRowData(lineSeq, CID1, MAPPER.writeValueAsString(rd1));
        svc.writeRowData(lineSeq, CID2, MAPPER.writeValueAsString(rd2));
        svc.writeRowData(lineSeq, CID3, MAPPER.writeValueAsString(rd3));
        Map<String, String> snapSeq = snapshot(lineSeq);

        // ── 路径 B：批量 ──
        List<ConfigureSnapshotService.SnapRow> snapRows = List.of(
                new ConfigureSnapshotService.SnapRow(CID1, "Tab1", rows1),
                new ConfigureSnapshotService.SnapRow(CID2, "Tab2", rows2),
                new ConfigureSnapshotService.SnapRow(CID3, "Tab3", rows3));
        Map<UUID, ArrayNode> byComp = new LinkedHashMap<>();
        byComp.put(CID1, rd1); byComp.put(CID2, rd2); byComp.put(CID3, rd3);
        svc.writeSnapshotBatch(lineBatch, snapRows);
        svc.writeRowDataBatch(lineBatch, byComp);
        Map<String, String> snapBatch = snapshot(lineBatch);

        // ── 逐位比对 ──
        assertEquals(snapSeq.size(), snapBatch.size(), "行数应相等");
        for (UUID cid : List.of(CID1, CID2, CID3)) {
            String key = cid.toString();
            assertTrue(snapSeq.containsKey(key), "逐行结果应含 " + key);
            assertTrue(snapBatch.containsKey(key), "批量结果应含 " + key);
            assertEquals(snapSeq.get(key), snapBatch.get(key),
                    "component " + key + " 落库值应逐位相等");
        }
    }

    // ------------------------------------------------------------------
    // TC-2: UPSERT 保留语义 — writeSnapshotBatch 不清零 row_data
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void tc2_snapshotBatchPreservesRowData() throws Exception {
        String originalSnap = rows("original-snap");
        String originalRd   = MAPPER.writeValueAsString(rowData("original-rd"));
        // 预置行（含 snapshot_rows + row_data）
        svc.writeSnapshot(lineBatch, CID1, "TabX", originalSnap);
        svc.writeRowData(lineBatch, CID1, originalRd);

        // 只调 writeSnapshotBatch 写新 snapshot
        String newSnap = rows("new-snap");
        svc.writeSnapshotBatch(lineBatch,
                List.of(new ConfigureSnapshotService.SnapRow(CID1, "TabX", newSnap)));

        // row_data 仍 == originalRd
        @SuppressWarnings("unchecked")
        List<Object[]> result = em.createNativeQuery(
                "SELECT snapshot_rows::text, row_data::text " +
                "FROM quotation_line_component_data " +
                "WHERE line_item_id = :li AND component_id = :cid")
                .setParameter("li", lineBatch).setParameter("cid", CID1)
                .getResultList();
        assertFalse(result.isEmpty(), "行应存在");
        Object[] r = result.get(0);
        String actualSnap = r[0] != null ? r[0].toString() : null;
        String actualRd   = r[1] != null ? r[1].toString() : null;
        assertNotNull(actualSnap, "snapshot_rows 应非 NULL");
        assertTrue(actualSnap.contains("new-snap"), "snapshot_rows 应已更新为新值");
        assertNotNull(actualRd, "row_data 不应被清零");
        assertTrue(actualRd.contains("original-rd"), "row_data 应仍为原值");
    }

    // ------------------------------------------------------------------
    // TC-3: UPSERT 保留语义 — writeRowDataBatch 不清零 snapshot_rows
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void tc3_rowDataBatchPreservesSnapshot() throws Exception {
        String originalSnap = rows("snap-tc3");
        String originalRd   = MAPPER.writeValueAsString(rowData("rd-tc3"));
        svc.writeSnapshot(lineBatch, CID1, "TabY", originalSnap);
        svc.writeRowData(lineBatch, CID1, originalRd);

        // 只调 writeRowDataBatch 写新 row_data
        Map<UUID, ArrayNode> byComp = Map.of(CID1, rowData("new-rd-tc3"));
        svc.writeRowDataBatch(lineBatch, byComp);

        // snapshot_rows 仍 == originalSnap
        @SuppressWarnings("unchecked")
        List<Object[]> result = em.createNativeQuery(
                "SELECT snapshot_rows::text, row_data::text " +
                "FROM quotation_line_component_data " +
                "WHERE line_item_id = :li AND component_id = :cid")
                .setParameter("li", lineBatch).setParameter("cid", CID1)
                .getResultList();
        assertFalse(result.isEmpty(), "行应存在");
        Object[] r = result.get(0);
        String actualSnap = r[0] != null ? r[0].toString() : null;
        String actualRd   = r[1] != null ? r[1].toString() : null;
        assertNotNull(actualSnap, "snapshot_rows 不应被清零");
        assertTrue(actualSnap.contains("snap-tc3"), "snapshot_rows 应仍为原值");
        assertNotNull(actualRd, "row_data 应已更新");
        assertTrue(actualRd.contains("new-rd-tc3"), "row_data 应已更新为新值");
    }

    // ------------------------------------------------------------------
    // TC-4: tab_name COALESCE — 预置 tab_name 不被批量传入的新 tab 覆盖
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    void tc4_tabNameCoalesce() {
        svc.writeSnapshot(lineBatch, CID2, "TabOriginal", rows("any"));

        // 批量传 tab='TabNew'（不应覆盖 TabOriginal）
        svc.writeSnapshotBatch(lineBatch,
                List.of(new ConfigureSnapshotService.SnapRow(CID2, "TabNew", rows("updated"))));

        @SuppressWarnings("unchecked")
        List<Object> result = em.createNativeQuery(
                "SELECT tab_name FROM quotation_line_component_data " +
                "WHERE line_item_id = :li AND component_id = :cid")
                .setParameter("li", lineBatch).setParameter("cid", CID2)
                .getResultList();
        assertFalse(result.isEmpty(), "行应存在");
        assertEquals("TabOriginal", result.get(0) != null ? result.get(0).toString() : null,
                "tab_name COALESCE: 预置值不应被批量传入的新值覆盖");
    }

    // ------------------------------------------------------------------
    // TC-5: NULL 专项 — rowsJson=null 落 NULL jsonb，不抛类型错
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    void tc5_nullRowsJsonFallsNullInDb() {
        assertDoesNotThrow(() ->
            svc.writeSnapshotBatch(lineBatch,
                    List.of(new ConfigureSnapshotService.SnapRow(CID3, "TabNull", null))),
            "rowsJson=null 不应抛出异常");

        @SuppressWarnings("unchecked")
        List<Object> result = em.createNativeQuery(
                "SELECT snapshot_rows IS NULL FROM quotation_line_component_data " +
                "WHERE line_item_id = :li AND component_id = :cid")
                .setParameter("li", lineBatch).setParameter("cid", CID3)
                .getResultList();
        assertFalse(result.isEmpty(), "行应存在");
        Object isNull = result.get(0);
        assertTrue(isNull instanceof Boolean && (Boolean) isNull,
                "snapshot_rows 应为 NULL");
    }

    // ------------------------------------------------------------------
    // TC-6: A/B 逐位等价 — 更新已存在行（非首存场景）
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void tc6_abEquiv_existingRows() throws Exception {
        // 预置旧值
        String oldSnap1 = rows("old-snap1"); String oldSnap2 = rows("old-snap2");
        svc.writeSnapshot(lineSeq, CID1, "Tab1", oldSnap1);
        svc.writeSnapshot(lineSeq, CID2, "Tab2", oldSnap2);
        svc.writeRowData(lineSeq, CID1, MAPPER.writeValueAsString(rowData("old-rd1")));
        svc.writeRowData(lineSeq, CID2, MAPPER.writeValueAsString(rowData("old-rd2")));

        svc.writeSnapshotBatch(lineBatch,
                List.of(new ConfigureSnapshotService.SnapRow(CID1, "Tab1", oldSnap1),
                        new ConfigureSnapshotService.SnapRow(CID2, "Tab2", oldSnap2)));
        svc.writeRowDataBatch(lineBatch,
                new LinkedHashMap<>(Map.of(CID1, rowData("old-rd1"), CID2, rowData("old-rd2"))));

        // 写新值
        String newSnap1 = rows("new-snap1"); String newSnap2 = rows("new-snap2");
        ArrayNode newRd1 = rowData("new-rd1"); ArrayNode newRd2 = rowData("new-rd2");

        svc.writeSnapshot(lineSeq, CID1, "Tab1_new", newSnap1);
        svc.writeSnapshot(lineSeq, CID2, "Tab2_new", newSnap2);
        svc.writeRowData(lineSeq, CID1, MAPPER.writeValueAsString(newRd1));
        svc.writeRowData(lineSeq, CID2, MAPPER.writeValueAsString(newRd2));

        svc.writeSnapshotBatch(lineBatch,
                List.of(new ConfigureSnapshotService.SnapRow(CID1, "Tab1_new", newSnap1),
                        new ConfigureSnapshotService.SnapRow(CID2, "Tab2_new", newSnap2)));
        svc.writeRowDataBatch(lineBatch,
                new LinkedHashMap<>(Map.of(CID1, newRd1, CID2, newRd2)));

        Map<String, String> snapSeq = snapshot(lineSeq);
        Map<String, String> snapBatch = snapshot(lineBatch);
        assertEquals(snapSeq.size(), snapBatch.size(), "更新后行数应相等");
        for (UUID cid : List.of(CID1, CID2)) {
            String key = cid.toString();
            assertEquals(snapSeq.get(key), snapBatch.get(key),
                    "更新场景 component " + key + " 落库值应逐位相等");
        }
    }
}
