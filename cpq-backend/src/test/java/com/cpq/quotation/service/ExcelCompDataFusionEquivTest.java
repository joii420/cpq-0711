package com.cpq.quotation.service;

import com.cpq.formula.dataloader.ExcelCompDataContext;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.narayana.jta.runtime.TransactionConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel compData 融合等价护栏:逐行查 component_data(上下文未设)vs 整单 IN 预取(上下文已设),
 * 报价 Excel + 核价树 Excel 输出 md5 必须逐位相同;且往返显著下降。
 */
@QuarkusTest
class ExcelCompDataFusionEquivTest {

    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    private static final UUID ROCKWELL = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");

    @Test
    @Transactional
    @TransactionConfiguration(timeout = 600)
    void rockwell() throws Exception {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        Quotation q = Quotation.findById(ROCKWELL);
        Assumptions.assumeTrue(q != null && q.costingCardTemplateId != null, "无核价模板,跳过");
        List<QuotationLineItem> lines = QuotationLineItem.list("quotationId", ROCKWELL);
        Assumptions.assumeTrue(!lines.isEmpty(), "无行,跳过");
        List<UUID> ids = lines.stream().map(li -> li.id).collect(Collectors.toList());

        QuotationIdContext.set(ROCKWELL);
        try {
            // A: 逐行(上下文未设)
            ExcelCompDataContext.clear();
            long c0 = st.getPrepareStatementCount();
            String md5A = buildAllExcel(q, lines);
            long countA = st.getPrepareStatementCount() - c0;

            // B: 整单预取(上下文已设)
            Map<UUID, List<QuotationLineComponentData>> cdByLine = QuotationLineComponentData
                .<QuotationLineComponentData>list("lineItemId IN ?1 ORDER BY lineItemId, sortOrder, id", ids)
                .stream().collect(Collectors.groupingBy(cd -> cd.lineItemId));
            ExcelCompDataContext.set(cdByLine);
            long c1 = st.getPrepareStatementCount();
            String md5B = buildAllExcel(q, lines);
            long countB = st.getPrepareStatementCount() - c1;
            ExcelCompDataContext.clear();

            System.out.printf("[Excel融合] lines=%d md5A=%s md5B=%s 往返 A=%d B=%d%n",
                    lines.size(), md5A, md5B, countA, countB);
            assertEquals(md5A, md5B, "整单预取 Excel 输出必须与逐行逐位相同");
            assertTrue(countB < countA, "预取路径往返应 < 逐行(A=" + countA + " B=" + countB + ")");
        } finally {
            ExcelCompDataContext.clear();
            QuotationIdContext.clear();
        }
    }

    private String buildAllExcel(Quotation q, List<QuotationLineItem> lines) throws Exception {
        StringBuilder all = new StringBuilder();
        for (QuotationLineItem li0 : lines) {
            QuotationLineItem li = QuotationLineItem.findById(li0.id);
            if (li == null) continue;
            String qe = cardSnapshotService.buildExcelValues(li, q.customerTemplateId, q.customerId, li.quoteCardValues);
            String ce = cardSnapshotService.buildExcelValues(li, q.costingCardTemplateId, q.customerId, li.costingCardValues, true);
            all.append("LI=").append(li.id).append("\nQE=").append(nz(qe)).append("\nCE=").append(nz(ce)).append('\n');
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
