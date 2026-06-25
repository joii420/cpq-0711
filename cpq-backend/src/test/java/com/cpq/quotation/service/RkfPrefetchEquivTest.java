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
 * F1（方案 B）等价护栏：rowKeyFields 整单预取路径 vs 逐行路径,四份输出 md5 必须逐位命中
 * {@link GoldenCardValuesEquivTest} 的 golden;且 row_key_fields 单行查往返由 ~2550 → 0(全命中预取)。
 */
@QuarkusTest
class RkfPrefetchEquivTest {

    @Inject CardSnapshotService cardSnapshotService;

    private static final UUID SMALL_QID = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");
    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final String GOLDEN_SMALL = "98d6ab6a99865f6ec0374ebd3c66f574";
    private static final String GOLDEN_ROCKWELL = "3837c2bd35ada869ff09799739512d6e";

    @Test
    void rockwell_prefetch_equiv_and_zero_roundtrips() throws Exception {
        runOne(ROCKWELL_QID, GOLDEN_ROCKWELL);
    }

    @Test
    void small_prefetch_equiv_and_zero_roundtrips() throws Exception {
        runOne(SMALL_QID, GOLDEN_SMALL);
    }

    @Transactional
    @TransactionConfiguration(timeout = 600)
    void runOne(UUID qid, String golden) throws Exception {
        Quotation q = Quotation.findById(qid);
        Assumptions.assumeTrue(q != null, qid + " 不存在,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");

        List<UUID> allLineIds = new ArrayList<>();
        for (QuotationLineItem li : lines) allLineIds.add(li.id);

        // 整单一次预取（含 F1 的 rowKeyFieldsByComp）
        CardSnapshotService.CardValuesPrefetch prefetch =
            cardSnapshotService.precomputeCardValuesPrefetch(qid, allLineIds);
        assertFalse(prefetch.rowKeyFieldsByComp.isEmpty(), "预取应至少含若干组件的 rowKeyFields");

        // 计数清零：预取后、逐行构建期间 loadRowKeyFields 应 0 次（全命中内存）
        CardSnapshotService.ROW_KEY_FIELDS_QUERY_COUNT.set(0);

        StringBuilder all = new StringBuilder();
        QuotationIdContext.set(qid);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem m = QuotationLineItem.findById(li.id);
                if (m == null) continue;
                String quote = cardSnapshotService.buildCardValues(m, q.customerTemplateId, prefetch);
                String costing = (q.costingCardTemplateId != null)
                    ? cardSnapshotService.buildCostingCardValues(m, q.costingCardTemplateId, q.customerId, q.id, null, prefetch)
                    : "";
                String quoteExcel = cardSnapshotService.buildExcelValues(m, q.customerTemplateId, q.customerId, quote);
                String costingExcel = (q.costingCardTemplateId != null)
                    ? cardSnapshotService.buildExcelValues(m, q.costingCardTemplateId, q.customerId, costing, true)
                    : "";
                all.append("LI=").append(li.id).append('\n')
                   .append("Q=").append(nz(quote)).append('\n')
                   .append("C=").append(nz(costing)).append('\n')
                   .append("QE=").append(nz(quoteExcel)).append('\n')
                   .append("CE=").append(nz(costingExcel)).append('\n');
            }
        } finally {
            QuotationIdContext.clear();
        }
        long rkfQueries = CardSnapshotService.ROW_KEY_FIELDS_QUERY_COUNT.get();
        String md5 = md5(all.toString());

        System.out.printf("[F1] qid=%s prefetch路径 md5=%s rkf单查次数=%d (组件预取数=%d)%n",
            qid, md5, rkfQueries, prefetch.rowKeyFieldsByComp.size());

        assertEquals(golden, md5, "预取路径四份输出 md5 必须逐位命中逐行路径 golden");
        assertEquals(0, rkfQueries, "预取命中后 loadRowKeyFields 应 0 次往返（整单由 ~2550 → 0）");
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
