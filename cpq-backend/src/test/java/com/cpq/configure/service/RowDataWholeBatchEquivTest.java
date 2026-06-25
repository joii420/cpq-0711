package com.cpq.configure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * #2 物化批量等价护栏(非破坏性):读当前 row_data → 整单一次写回
 * ({@link ConfigureSnapshotService#writeRowDataBatchAllLines}) vs 逐行写回
 * ({@link ConfigureSnapshotService#writeRowDataBatch}) → DB row_data 内容必须不变。
 */
@QuarkusTest
class RowDataWholeBatchEquivTest {

    @Inject ConfigureSnapshotService snapshotService;
    @Inject EntityManager em;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID ROCKWELL = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final UUID SMALL = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");

    @Test void rockwell() throws Exception { runOne(ROCKWELL); }
    @Test void small() throws Exception { runOne(SMALL); }

    @Transactional
    @TransactionConfiguration(timeout = 600)
    void runOne(UUID qid) throws Exception {
        Map<UUID, Map<UUID, ArrayNode>> byLineComp = readRowData(qid);
        Assumptions.assumeTrue(!byLineComp.isEmpty(), qid + " 无 row_data,跳过");

        String baseline = contentMd5(qid);
        snapshotService.writeRowDataBatchAllLines(byLineComp);
        String afterWhole = contentMd5(qid);
        for (Map.Entry<UUID, Map<UUID, ArrayNode>> e : byLineComp.entrySet()) {
            snapshotService.writeRowDataBatch(e.getKey(), e.getValue());
        }
        String afterPerLine = contentMd5(qid);

        System.out.printf("[物化批量] qid=%s baseline=%s whole=%s perLine=%s%n",
                qid, baseline, afterWhole, afterPerLine);
        assertEquals(baseline, afterWhole, "整单批量写回 row_data 内容必须不变(=逐行写等价)");
        assertEquals(baseline, afterPerLine, "逐行写回 row_data 内容必须不变(对照)");
    }

    private Map<UUID, Map<UUID, ArrayNode>> readRowData(UUID qid) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
            "SELECT d.line_item_id, d.component_id, d.row_data::text FROM quotation_line_component_data d " +
            "JOIN quotation_line_item li ON li.id=d.line_item_id WHERE li.quotation_id = :q " +
            "AND d.row_data IS NOT NULL ORDER BY d.line_item_id, d.component_id")
            .setParameter("q", qid).getResultList();
        Map<UUID, Map<UUID, ArrayNode>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID lid = (r[0] instanceof UUID u) ? u : UUID.fromString(r[0].toString());
            UUID cid = (r[1] instanceof UUID u) ? u : UUID.fromString(r[1].toString());
            JsonNode n = MAPPER.readTree(r[2].toString());
            if (n != null && n.isArray()) out.computeIfAbsent(lid, k -> new LinkedHashMap<>()).put(cid, (ArrayNode) n);
        }
        return out;
    }

    private String contentMd5(UUID qid) {
        Object r = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(d.line_item_id::text||'|'||d.component_id::text||'|'||" +
            "COALESCE(d.row_data::text,'∅'), E'\\n' ORDER BY d.line_item_id, d.component_id),'')) " +
            "FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id " +
            "WHERE li.quotation_id = :q").setParameter("q", qid).getSingleResult();
        return r == null ? "" : r.toString();
    }
}
