package com.cpq.quotation.service;

import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F4 等价护栏：driver 组件清单整单预取路径 vs 逐行查,四份输出 md5 必须逐位命中 golden;
 * 且 driver 组件清单查由 ~170 → 0(全命中预取)。rkf(F1)亦顺带验证保持 0。
 */
@QuarkusTest
class DriverCompsPrefetchEquivTest {

    @Inject CardSnapshotService cardSnapshotService;

    private static final UUID SMALL_QID = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");
    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final String GOLDEN_SMALL = "2cc56fead05427c1a1c86ae15f417248";
    private static final String GOLDEN_ROCKWELL = "3837c2bd35ada869ff09799739512d6e";

    @Test
    void rockwell() throws Exception { runOne(ROCKWELL_QID, GOLDEN_ROCKWELL); }

    @Test
    void small() throws Exception { runOne(SMALL_QID, GOLDEN_SMALL); }

    @Transactional
    @TransactionConfiguration(timeout = 600)
    void runOne(UUID qid, String golden) throws Exception {
        Quotation q = Quotation.findById(qid);
        Assumptions.assumeTrue(q != null && q.costingCardTemplateId != null, qid + " 无核价模板,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");

        List<UUID> allLineIds = new ArrayList<>();
        for (QuotationLineItem li : lines) allLineIds.add(li.id);

        CardSnapshotService.CardValuesPrefetch prefetch =
            cardSnapshotService.precomputeCardValuesPrefetch(qid, allLineIds);
        assertNotNull(prefetch.driverCompsByTemplate.get(q.costingCardTemplateId),
            "核价模板 driver 组件清单应被预取");

        CardSnapshotService.ROW_KEY_FIELDS_QUERY_COUNT.set(0);
        CardSnapshotService.DRIVER_COMPS_QUERY_COUNT.set(0);

        StringBuilder all = new StringBuilder();
        QuotationIdContext.set(qid);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem m = QuotationLineItem.findById(li.id);
                if (m == null) continue;
                String quote = cardSnapshotService.buildCardValues(m, q.customerTemplateId, prefetch);
                String costing = cardSnapshotService.buildCostingCardValues(
                    m, q.costingCardTemplateId, q.customerId, q.id, null, prefetch);
                String quoteExcel = cardSnapshotService.buildExcelValues(m, q.customerTemplateId, q.customerId, quote);
                String costingExcel = cardSnapshotService.buildExcelValues(
                    m, q.costingCardTemplateId, q.customerId, costing, true);
                all.append("LI=").append(li.id).append('\n')
                   .append("Q=").append(nz(quote)).append('\n')
                   .append("C=").append(nz(costing)).append('\n')
                   .append("QE=").append(nz(quoteExcel)).append('\n')
                   .append("CE=").append(nz(costingExcel)).append('\n');
            }
        } finally {
            QuotationIdContext.clear();
        }
        long driverQueries = CardSnapshotService.DRIVER_COMPS_QUERY_COUNT.get();
        long rkfQueries = CardSnapshotService.ROW_KEY_FIELDS_QUERY_COUNT.get();
        String md5 = md5(all.toString());

        System.out.printf("[F4] qid=%s md5=%s driverComps查=%d rkf查=%d (lines=%d)%n",
            qid, md5, driverQueries, rkfQueries, lines.size());

        assertEquals(golden, md5, "F4 预取路径四份输出 md5 必须逐位命中 golden");
        assertEquals(0, driverQueries, "driver 组件清单查应 0 次（整单由 ~170 → 0）");
        assertEquals(0, rkfQueries, "rkf 单查应保持 0（F1）");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
