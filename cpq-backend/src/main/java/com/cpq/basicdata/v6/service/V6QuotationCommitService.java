package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.CreateQuotationFromImportRequest;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.service.QuotationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V6 commit：导入完成后建报价单 + 写 hfPairs 到 import_record.metadata。
 *
 * <p>与 V5 ImportSessionService.commit 同语义，但 hfPairs 数据源改为 V6 表
 * {@code material_customer_map}（按 import_record.created_at 时间窗 + customer 过滤）。
 * <p>报价单本身不在此阶段填 LineItem——LineItem 由前端编辑页 autoPopulate
 * 根据 ?importRecordId=xxx 调 CustomerPartCandidateService 自动生成。
 */
@ApplicationScoped
public class V6QuotationCommitService {

    @Inject EntityManager em;
    @Inject QuotationService quotationService;

    @Transactional
    public CommitResult createQuotation(CreateQuotationFromImportRequest req, UUID userId) {
        ImportRecord rec = ImportRecord.findById(req.importRecordId);
        if (rec == null) throw new RuntimeException("importRecord 不存在: " + req.importRecordId);

        // 1. 建报价单（复用 QuotationService.create）
        CreateQuotationRequest cq = new CreateQuotationRequest();
        cq.customerId = req.customerId;
        cq.name = req.name;
        cq.customerTemplateId = req.customerTemplateId;
        cq.costingTemplateId = req.costingTemplateId;
        QuotationDTO q = quotationService.create(cq, userId);
        Log.infof("V6 commit: importRecord=%s → quotation=%s", req.importRecordId, q.id);

        // 2. 查本次导入涉及的料号对 (customer_product_no, material_no) — 写入 hfPairs
        //    数据源：V6 material_customer_map，按客户 + import_record.created_at 时间窗 ±2 分钟过滤
        @SuppressWarnings("unchecked")
        List<Object[]> pairs = em.createNativeQuery(
                "SELECT DISTINCT customer_product_no, material_no FROM material_customer_map " +
                "WHERE customer_no = (SELECT code FROM customer WHERE id = :cid) " +
                "  AND updated_at BETWEEN :start AND :end " +
                "  AND customer_product_no IS NOT NULL AND material_no IS NOT NULL")
            .setParameter("cid", req.customerId)
            .setParameter("start", rec.createdAt.minusMinutes(1))
            .setParameter("end", rec.createdAt.plusMinutes(5))
            .getResultList();

        List<Map<String, String>> hfPairs = new ArrayList<>();
        for (Object[] row : pairs) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("cpn", row[0] == null ? "" : row[0].toString());
            p.put("hf", row[1] == null ? "" : row[1].toString());
            hfPairs.add(p);
        }
        Log.infof("V6 commit: hfPairs (来自 material_customer_map) = %d 个", hfPairs.size());

        // 3. 把 quotation_id + hfPairs 回写到 import_record
        rec.quotationId = q.id;
        rec.matchedRows = hfPairs.size();
        try {
            ObjectMapper mapper = new ObjectMapper();
            // 保留已有 metadata（sheet_summary）+ 追加 v6 / hfPairs
            Map<String, Object> meta = new LinkedHashMap<>();
            if (rec.metadata != null && !rec.metadata.isBlank()) {
                try { meta.putAll(mapper.readValue(rec.metadata, Map.class)); } catch (Exception ignore) {}
            }
            meta.put("v6", true);
            meta.put("hfPairs", hfPairs);
            rec.metadata = mapper.writeValueAsString(meta);
        } catch (Exception ex) {
            Log.warnf("V6 commit: 写 hfPairs 到 metadata 失败 (非致命): %s", ex.getMessage());
        }

        return new CommitResult(q.id, req.importRecordId, hfPairs.size());
    }

    public static class CommitResult {
        public UUID quotationId;
        public UUID importRecordId;
        public int hfPairsCount;

        public CommitResult(UUID quotationId, UUID importRecordId, int hfPairsCount) {
            this.quotationId = quotationId;
            this.importRecordId = importRecordId;
            this.hfPairsCount = hfPairsCount;
        }
    }
}
