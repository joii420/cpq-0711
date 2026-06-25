package com.cpq.configure.service;

import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 落库批量等价护栏（<b>非破坏性</b>）：读当前 snapshot_rows → 用「整单一次写
 * {@link ConfigureSnapshotService#writeSnapshotBatchAllLines}」与「逐行写
 * {@link ConfigureSnapshotService#writeSnapshotBatch}」分别<b>原样写回</b> → DB 内容必须不变。
 * <p>不调 snapshotQuotation(不重 expand,不动 fixture 值);两路写的都是读到的同一份内容,
 * 故对正确实现是恒等回写(若 (lineId,componentId) 匹配有 bug 则内容变 → 断言捕获)。
 */
@QuarkusTest
class PersistWholeBatchEquivTest {

    @Inject ConfigureSnapshotService snapshotService;
    @Inject EntityManager em;

    private static final UUID ROCKWELL = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final UUID SMALL = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");

    @Test
    void rockwell() throws Exception { runOne(ROCKWELL); }

    @Test
    void small() throws Exception { runOne(SMALL); }

    @Transactional
    @TransactionConfiguration(timeout = 600)
    void runOne(UUID qid) {
        Map<UUID, List<ConfigureSnapshotService.SnapRow>> byLine = readSnapRows(qid);
        Assumptions.assumeTrue(!byLine.isEmpty(), qid + " 无 snapshot 行,跳过");

        String baseline = contentMd5(qid);

        // ① 整单一次写回(应恒等)
        snapshotService.writeSnapshotBatchAllLines(byLine);
        String afterWhole = contentMd5(qid);

        // ② 逐行写回(应恒等)
        for (Map.Entry<UUID, List<ConfigureSnapshotService.SnapRow>> e : byLine.entrySet()) {
            snapshotService.writeSnapshotBatch(e.getKey(), e.getValue());
        }
        String afterPerLine = contentMd5(qid);

        System.out.printf("[落库批量] qid=%s baseline=%s whole=%s perLine=%s%n",
                qid, baseline, afterWhole, afterPerLine);
        assertEquals(baseline, afterWhole, "整单批量写回必须保持 snapshot_rows 内容不变(=逐行写等价)");
        assertEquals(baseline, afterPerLine, "逐行写回必须保持内容不变(对照)");
    }

    private Map<UUID, List<ConfigureSnapshotService.SnapRow>> readSnapRows(UUID qid) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT d.line_item_id, d.component_id, d.tab_name, d.snapshot_rows::text " +
            "FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id " +
            "WHERE li.quotation_id = :q ORDER BY d.line_item_id, d.component_id")
            .setParameter("q", qid).getResultList();
        Map<UUID, List<ConfigureSnapshotService.SnapRow>> byLine = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID lid = (r[0] instanceof UUID u) ? u : UUID.fromString(r[0].toString());
            UUID cid = (r[1] instanceof UUID u) ? u : UUID.fromString(r[1].toString());
            String tab = r[2] == null ? null : r[2].toString();
            String snap = r[3] == null ? null : r[3].toString();
            byLine.computeIfAbsent(lid, k -> new ArrayList<>())
                  .add(new ConfigureSnapshotService.SnapRow(cid, tab, snap));
        }
        return byLine;
    }

    private String contentMd5(UUID qid) {
        Object r = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(" +
            "  d.line_item_id::text || '|' || d.component_id::text || '|' || " +
            "  COALESCE(d.snapshot_rows::text,'∅') || '|' || COALESCE(d.tab_name,'∅'), E'\\n' " +
            "  ORDER BY d.line_item_id, d.component_id), '')) " +
            "FROM quotation_line_component_data d " +
            "JOIN quotation_line_item li ON li.id = d.line_item_id WHERE li.quotation_id = :q")
            .setParameter("q", qid).getSingleResult();
        return r == null ? "" : r.toString();
    }
}
