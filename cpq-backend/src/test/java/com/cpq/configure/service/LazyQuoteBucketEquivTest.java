package com.cpq.configure.service;

import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 懒触发报价合桶等价护栏:增量 snapshotQuotation(skipRowsWithSnapshot=true)且全行已有快照 →
 * 全跳过 → 不再 evictAll + 报价合桶 expand。验证:① snapshot_rows 内容不变(等价);② 往返很低(省了合桶)。
 */
@QuarkusTest
class LazyQuoteBucketEquivTest {

    @Inject ConfigureSnapshotService snapshotService;
    @Inject EntityManager em;

    private static final UUID ROCKWELL = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Test
    @Transactional
    @TransactionConfiguration(timeout = 600)
    void incrementalAllSkip() {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        // 前置:确保全行有快照(本单 170 行全 snapshot_rows;否则跳过)
        Number missing = (Number) em.createNativeQuery(
            "SELECT count(*) FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id " +
            "JOIN component c ON c.id=d.component_id " +
            "WHERE li.quotation_id=:q AND c.data_driver_path IS NOT NULL AND c.data_driver_path<>'' AND d.snapshot_rows IS NULL")
            .setParameter("q", ROCKWELL).getSingleResult();
        Assumptions.assumeTrue(missing != null && missing.longValue() == 0, "存在缺快照 driver 行,非全跳过,跳过本测");

        String before = contentMd5();
        long c0 = st.getPrepareStatementCount();
        snapshotService.snapshotQuotation(ROCKWELL, true);   // 增量,全行应跳过
        long rt = st.getPrepareStatementCount() - c0;
        String after = contentMd5();

        System.out.printf("[lazy合桶] 增量全跳过 snapshotQuotation 往返=%d md5 before=%s after=%s%n", rt, before, after);
        assertEquals(before, after, "增量全跳过不应改 snapshot_rows(等价)");
        assertTrue(rt < 30, "懒触发后增量 draft 往返应很低(无报价合桶 expand),实测=" + rt);
    }

    private String contentMd5() {
        Object r = em.createNativeQuery(
            "SELECT md5(COALESCE(string_agg(d.line_item_id::text||'|'||d.component_id::text||'|'||" +
            "COALESCE(d.snapshot_rows::text,'∅'), E'\\n' ORDER BY d.line_item_id, d.component_id),'')) " +
            "FROM quotation_line_component_data d JOIN quotation_line_item li ON li.id=d.line_item_id " +
            "WHERE li.quotation_id=:q").setParameter("q", ROCKWELL).getSingleResult();
        return r == null ? "" : r.toString();
    }
}
