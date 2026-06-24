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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 黄金等价护栏（集合化重构的"逐位等价"标尺）。捕获**当前逐行路径**对真实单整单
 * 四份计算输出的 md5,作为后续 set-based 重构每一阶段必须逐位命中的 golden:
 * <ul>
 *   <li>Q  = {@code buildCardValues}（报价卡片值）</li>
 *   <li>C  = {@code buildCostingCardValues}（核价卡片值,纯逐行 union=null/prefetch=null）</li>
 *   <li>QE = {@code buildExcelValues}（报价 Excel,单行）</li>
 *   <li>CE = {@code buildExcelValues(costingTree=true)}（核价 Excel,BOM 树多行）</li>
 * </ul>
 * 锚点单:罗克韦尔 8f0c37a4(170 行,主锚,与 B1/B2 同单)+ 罗克韦尔 a8f17a74(77 行,小尺寸锚)。
 * 注:原复现单 939e072e 已被 autosave 风暴的"全删全建并发竞态"抹成 0 行(2026-06-25 实测),不能作锚。
 *
 * <p>纪律([[cpq-expand-layer-not-threadsafe]]):**连跑两次 md5 必须一致**(determinism)——
 * 非确定性竞态单跑看不到;TC-1 是 golden 可信的前提。golden 常量首跑后回填(见 GOLDEN_* TODO)。
 */
@QuarkusTest
class GoldenCardValuesEquivTest {

    @Inject CardSnapshotService cardSnapshotService;

    private static final UUID SMALL_QID = UUID.fromString("a8f17a74-5a32-40fc-9e3d-bd5e81181248");   // 77 行罗克韦尔
    private static final UUID ROCKWELL_QID = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");// 170 行(B1/B2 同单)

    // 首跑后从控制台输出回填(逐行路径捕获),之后任何重构改动都必须命中这两个值。
    private static final String GOLDEN_SMALL = "2cc56fead05427c1a1c86ae15f417248";      // a8f17a74 77行,逐行路径 2026-06-25 捕获
    private static final String GOLDEN_ROCKWELL = "3837c2bd35ada869ff09799739512d6e";   // 8f0c37a4 170行,逐行路径 2026-06-25 捕获

    /** 整单逐行四份输出拼接后的 md5（per-row 路径 = 当前权威基线）。 */
    @Transactional
    @TransactionConfiguration(timeout = 600)
    String perRowAllValuesMd5(UUID qid) throws Exception {
        Quotation q = Quotation.findById(qid);
        Assumptions.assumeTrue(q != null, qid + " 不存在,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", qid);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");

        StringBuilder all = new StringBuilder();
        QuotationIdContext.set(qid);
        try {
            for (QuotationLineItem li : lines) {
                QuotationLineItem m = QuotationLineItem.findById(li.id);
                if (m == null) continue;
                String quote = cardSnapshotService.buildCardValues(m, q.customerTemplateId, null);
                String costing = (q.costingCardTemplateId != null)
                        ? cardSnapshotService.buildCostingCardValues(m, q.costingCardTemplateId, q.customerId, q.id, null, null)
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
        return md5(all.toString());
    }

    @Test
    void small_determinism_and_capture() throws Exception {
        String m1 = perRowAllValuesMd5(SMALL_QID);
        String m2 = perRowAllValuesMd5(SMALL_QID);
        assertEquals(m1, m2, "77行单连跑两次 md5 应一致(determinism)");
        System.out.println("=== GOLDEN small a8f17a74 all-values md5 = " + m1 + " ===");
        if (!GOLDEN_SMALL.startsWith("TODO")) assertEquals(GOLDEN_SMALL, m1, "77行单 golden 漂移");
    }

    @Test
    void rockwell_determinism_and_capture() throws Exception {
        String m1 = perRowAllValuesMd5(ROCKWELL_QID);
        String m2 = perRowAllValuesMd5(ROCKWELL_QID);
        assertEquals(m1, m2, "罗克韦尔连跑两次 md5 应一致(determinism)");
        System.out.println("=== GOLDEN rockwell 8f0c37a4 all-values md5 = " + m1 + " ===");
        if (!GOLDEN_ROCKWELL.startsWith("TODO")) assertEquals(GOLDEN_ROCKWELL, m1, "罗克韦尔 golden 漂移");
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
