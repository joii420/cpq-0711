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
 * B2 等价护栏（批量 EM：模板 snapshot parse 一次 + compdata 整单 IN 预取）：证明
 * <b>prefetch 路径</b>的 buildCardValues + buildCostingCardValues 整单逐行输出 md5
 * 与逐行路径（= B1 pin 的同一 golden）逐位相同。
 *
 * <p>数据锚点：罗克韦尔 {@code 8f0c37a4}。golden 与 {@link B1ComputeRowsMemoEquivTest} 同一值——
 * 因为 B2 只改「取模板/compdata 的途径」，不改任何计算，prefetch 路径必须逐位等于逐行路径。
 */
@QuarkusTest
class B2BatchEmEquivTest {

    @Inject
    CardSnapshotService cardSnapshotService;

    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    /** 与 B1ComputeRowsMemoEquivTest 同一 golden（逐行路径捕获，master d703c1f）。 */
    private static final String GOLDEN_MD5 = "c52532b35660226493b212f6d874e35a";

    @Transactional
    @TransactionConfiguration(timeout = 600)
    String prefetchPathMd5() throws Exception {
        Quotation q = Quotation.findById(ROCKWELL_QID);
        Assumptions.assumeTrue(q != null, "罗克韦尔 " + ROCKWELL_QID + " 不存在，跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", ROCKWELL_QID);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行，跳过");

        List<UUID> allLineIds = new ArrayList<>();
        for (QuotationLineItem li : lines) allLineIds.add(li.id);

        // B2: 整单一次预取（模板 parse 一次 + compdata IN）
        CardSnapshotService.CardValuesPrefetch prefetch =
                cardSnapshotService.precomputeCardValuesPrefetch(ROCKWELL_QID, allLineIds);

        StringBuilder all = new StringBuilder();
        QuotationIdContext.set(ROCKWELL_QID);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;
                String quote = cardSnapshotService.buildCardValues(managed, q.customerTemplateId, prefetch);
                String costing = (q.costingCardTemplateId != null)
                        ? cardSnapshotService.buildCostingCardValues(
                                managed, q.costingCardTemplateId, q.customerId, q.id, null, prefetch)
                        : "";
                all.append("LI=").append(li.id).append('\n')
                   .append("Q=").append(quote == null ? "" : quote).append('\n')
                   .append("C=").append(costing == null ? "" : costing).append('\n');
            }
        } finally {
            QuotationIdContext.clear();
        }
        return md5(all.toString());
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** TC-1 prefetch 路径连跑两次 md5 一致（determinism）。 */
    @Test
    void prefetchPath_md5_isDeterministic() throws Exception {
        String m1 = prefetchPathMd5();
        String m2 = prefetchPathMd5();
        assertEquals(m1, m2, "prefetch 路径连跑两次 md5 应一致");
        System.out.println("=== B2 prefetch-path md5 = " + m1 + " (golden=" + GOLDEN_MD5 + ") ===");
    }

    /** TC-2 prefetch 路径 md5 == 逐行 golden（A/B 等价）。 */
    @Test
    void prefetchPath_md5_equalsPerRowGolden() throws Exception {
        String m = prefetchPathMd5();
        assertEquals(GOLDEN_MD5, m,
                "B2 prefetch 路径整单 card values md5 应与逐行路径 golden 逐位一致");
    }
}
