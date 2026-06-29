package com.cpq.quotation.service;

import com.cpq.quotation.dto.QuotationDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * getById N+1 融合等价护栏:逐行路径(cpq.getbyid-batch=false)vs 整单 IN 批量分组(=true),
 * lineItems DTO 序列化 md5 必须逐位相同;且往返次数显著下降。
 */
@QuarkusTest
class GetByIdBatchEquivTest {

    @Inject QuotationService quotationService;
    @Inject EntityManager em;

    private static final UUID ROCKWELL = UUID.fromString("8f0c37a4-8186-4f5e-a9ca-358bd2d9662d");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Test
    @Transactional
    @TransactionConfiguration(timeout = 600)
    void rockwell() throws Exception {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        String prev = System.getProperty("cpq.getbyid-batch");
        try {
            // A: 逐行
            System.setProperty("cpq.getbyid-batch", "false");
            long c0 = st.getPrepareStatementCount();
            QuotationDTO a = quotationService.getById(ROCKWELL);
            long countA = st.getPrepareStatementCount() - c0;
            Assumptions.assumeTrue(a != null && a.lineItems != null && !a.lineItems.isEmpty(), "无行,跳过");
            String md5A = md5(MAPPER.writeValueAsString(a.lineItems));

            // B: 批量分组
            System.setProperty("cpq.getbyid-batch", "true");
            long c1 = st.getPrepareStatementCount();
            QuotationDTO b = quotationService.getById(ROCKWELL);
            long countB = st.getPrepareStatementCount() - c1;
            String md5B = md5(MAPPER.writeValueAsString(b.lineItems));

            System.out.printf("[getById融合] lines=%d md5A=%s md5B=%s 往返 A=%d B=%d%n",
                    a.lineItems.size(), md5A, md5B, countA, countB);
            assertEquals(md5A, md5B, "批量分组路径 lineItems DTO 必须与逐行逐位相同");
            assertTrue(countB < countA, "批量路径往返应 < 逐行(A=" + countA + " B=" + countB + ")");
        } finally {
            if (prev == null) System.clearProperty("cpq.getbyid-batch");
            else System.setProperty("cpq.getbyid-batch", prev);
        }
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
