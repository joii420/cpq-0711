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
 * B1 等价护栏（memoize computeRows）：证明在 {@code assembleTabsWithFormulaResults} 引入
 * 「按 tab 公式依赖分析、仅当 tab 不读 componentSubtotals/crossTabRows 时复用 computeRows」
 * 的 per-call memo 后，<b>报价侧 {@code buildCardValues} + 核价侧 {@code buildCostingCardValues}
 * 整单逐行输出 md5 逐位不变</b>。
 *
 * <p><b>固定数据锚点：罗克韦尔 {@code 8f0c37a4-8186-4f5e-a9ca-358bd2d9662d}</b>（170 行 / 77 不同料号，
 * 含跨表/小计公式的真实模板）。只读真实数据，不写 DB（buildCardValues 复用已存 snapshot_rows，
 * buildCostingCardValues 自身 expand，均不落盘）。
 *
 * <p>验证铁律：① 连跑两次 md5 一致（determinism / 无非确定性竞态）；② md5 == pin golden（A/B 等价）。
 */
@QuarkusTest
class B1ComputeRowsMemoEquivTest {

    @Inject
    CardSnapshotService cardSnapshotService;

    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    /** pin golden（pre-B1 当前码捕获，master d703c1f；数据锚定罗克韦尔 8f0c37a4）。 */
    private static final String GOLDEN_MD5 = "c52532b35660226493b212f6d874e35a";

    @Transactional
    @TransactionConfiguration(timeout = 600)  // 170 行整单只读循环 ~50s+，避免默认 60s 事务超时（co-run 满载）
    String computeAllCardValuesMd5() throws Exception {
        Quotation q = Quotation.findById(ROCKWELL_QID);
        Assumptions.assumeTrue(q != null, "罗克韦尔报价单 " + ROCKWELL_QID + " 不存在于当前环境，跳过");
        List<QuotationLineItem> lines =
                QuotationLineItem.list("quotationId", ROCKWELL_QID);
        Assumptions.assumeTrue(!lines.isEmpty(), "罗克韦尔无行，跳过");

        StringBuilder all = new StringBuilder();
        QuotationIdContext.set(ROCKWELL_QID);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem managed = QuotationLineItem.findById(li.id);
                if (managed == null) continue;
                String quote = cardSnapshotService.buildCardValues(managed, q.customerTemplateId);
                String costing = (q.costingCardTemplateId != null)
                        ? cardSnapshotService.buildCostingCardValues(
                                managed, q.costingCardTemplateId, q.customerId, q.id, null)
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

    /** TC-1 连跑两次 md5 一致（determinism）+ 打印 golden（首次捕获）。 */
    @Test
    void cardValues_md5_isDeterministic_and_printGolden() throws Exception {
        String m1 = computeAllCardValuesMd5();
        String m2 = computeAllCardValuesMd5();
        assertEquals(m1, m2, "连跑两次 buildCardValues+buildCostingCardValues md5 应一致（无非确定性）");
        System.out.println("=== B1 GOLDEN md5 (罗克韦尔 8f0c37a4 整单 card values) = " + m1 + " ===");
    }

    /** TC-2 A/B 等价：md5 == pin golden（GOLDEN_MD5 为空时跳过，等首次捕获回填）。 */
    @Test
    void cardValues_md5_equalsGolden() throws Exception {
        Assumptions.assumeTrue(!GOLDEN_MD5.isEmpty(), "GOLDEN_MD5 未回填（首次捕获阶段），跳过");
        String m = computeAllCardValuesMd5();
        assertEquals(GOLDEN_MD5, m,
                "B1 memo 后整单 card values md5 应与 pre-B1 golden 逐位一致");
    }
}
