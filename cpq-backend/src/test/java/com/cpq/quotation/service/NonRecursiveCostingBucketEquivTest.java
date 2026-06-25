package com.cpq.quotation.service;

import com.cpq.component.dto.ExpandDriverResponse;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2-2'（非递归 driver 单值合桶）等价护栏：
 * <ul>
 *   <li>union 路径（含非递归 partNo 合桶）四份输出 md5 == 逐行 golden（逐位等价）;</li>
 *   <li>非递归 per-line expand 兜底次数 union 路径 &lt; union=null 基线（证明合桶生效）。</li>
 * </ul>
 */
@QuarkusTest
class NonRecursiveCostingBucketEquivTest {

    @Inject CardSnapshotService cardSnapshotService;

    private static final UUID ROCKWELL = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final UUID SMALL = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");
    private static final String GOLDEN_ROCKWELL = "3837c2bd35ada869ff09799739512d6e";
    private static final String GOLDEN_SMALL = "2cc56fead05427c1a1c86ae15f417248";

    @Test
    void rockwell() throws Exception { runOne(ROCKWELL, GOLDEN_ROCKWELL); }

    @Test
    void small() throws Exception { runOne(SMALL, GOLDEN_SMALL); }

    @Transactional
    @TransactionConfiguration(timeout = 600)
    void runOne(UUID qid, String golden) throws Exception {
        Quotation q = Quotation.findById(qid);
        Assumptions.assumeTrue(q != null && q.costingCardTemplateId != null, qid + " 无核价模板,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");

        // A: union=null 基线(纯逐行),记非递归 expand 次数
        CardSnapshotService.NON_RECURSIVE_EXPAND_QUERY_COUNT.set(0);
        String md5A = buildAll(q, lines, null);
        long countA = CardSnapshotService.NON_RECURSIVE_EXPAND_QUERY_COUNT.get();

        // B: union 路径(含非递归合桶)
        Map<UUID, Map<String, ExpandDriverResponse>> union =
            cardSnapshotService.precomputeCostingDriverUnion(qid);
        CardSnapshotService.NON_RECURSIVE_EXPAND_QUERY_COUNT.set(0);
        String md5B = buildAll(q, lines, union);
        long countB = CardSnapshotService.NON_RECURSIVE_EXPAND_QUERY_COUNT.get();

        System.out.printf("[2-2'] qid=%s md5A=%s md5B=%s 非递归expand A=%d B=%d unionComps=%d%n",
            qid, md5A, md5B, countA, countB, union.size());

        assertEquals(golden, md5A, "基线(union=null)应命中 golden");
        assertEquals(golden, md5B, "union 路径(非递归合桶)必须逐位命中 golden");
        assertTrue(countB < countA || countA == 0,
            "union 路径非递归 expand 兜底次数应 < 基线(合桶生效);A=" + countA + " B=" + countB);
    }

    private String buildAll(Quotation q, List<QuotationLineItem> lines,
                            Map<UUID, Map<String, ExpandDriverResponse>> union) throws Exception {
        StringBuilder all = new StringBuilder();
        QuotationIdContext.set(q.id);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem m = QuotationLineItem.findById(li.id);
                if (m == null) continue;
                String quote = cardSnapshotService.buildCardValues(m, q.customerTemplateId, null);
                String costing = cardSnapshotService.buildCostingCardValues(
                    m, q.costingCardTemplateId, q.customerId, q.id, union, null);
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
        return md5(all.toString());
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
