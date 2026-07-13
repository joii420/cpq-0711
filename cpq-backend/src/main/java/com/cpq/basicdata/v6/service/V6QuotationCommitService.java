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
 * <p>task-0712 展示修复起：本阶段同事务内服务端建明细行（{@link QuotationLineItemMaterializeService}），
 * 不再依赖前端 autoPopulate。componentData/snapshot_rows/卡片值由 Resource 层后置物化完成。
 */
@ApplicationScoped
public class V6QuotationCommitService {

    @Inject EntityManager em;
    @Inject QuotationService quotationService;
    @Inject QuotationLineItemMaterializeService materializeService;

    @Transactional
    public CommitResult createQuotation(CreateQuotationFromImportRequest req, UUID userId) {
        ImportRecord rec = ImportRecord.findById(req.importRecordId);
        if (rec == null) throw new RuntimeException("importRecord 不存在: " + req.importRecordId);

        // 幂等重入(Task4 / backtask §4)：同 importRecordId 已建过单且单仍在 → 返回既有，不重复建单/建行。
        if (rec.quotationId != null) {
            com.cpq.quotation.entity.Quotation existing =
                    com.cpq.quotation.entity.Quotation.findById(rec.quotationId);
            if (existing != null) {
                CommitResult r = new CommitResult(existing.id, req.importRecordId,
                        rec.matchedRows == null ? 0 : rec.matchedRows);
                r.lineItemsCount = (int) com.cpq.quotation.entity.QuotationLineItem
                        .count("quotationId", existing.id);
                Log.infof("V6 commit: 幂等重入，返回既有 quotation=%s（%d 行）", existing.id, r.lineItemsCount);
                return r;
            }
        }

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
        //    注：此段必须先于 materializeLines 执行 —— CustomerPartCandidateService.listCandidatesV6
        //    靠读 import_record.metadata 的 v6=true + hfPairs 精确框定「本次导入批次候选」；
        //    若建行先于 metadata 写入，listCandidatesV6 读不到 v6 标记会静默退化为「客户全历史候选池」，
        //    建出本次未导入的历史料号行（历史数据丰富的客户上必现）。
        @SuppressWarnings("unchecked")
        List<Object[]> pairs = em.createNativeQuery(
                "SELECT DISTINCT customer_product_no, material_no FROM material_customer_map " +
                "WHERE customer_no = (SELECT code FROM customer WHERE id = :cid) " +
                "  AND updated_at BETWEEN :start AND :end " +
                "  AND customer_product_no IS NOT NULL AND material_no IS NOT NULL " +
                "  AND system_type = 'QUOTE'")
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

        // 关键：让同事务内后续 native 查询（listCandidatesV6 的 SELECT metadata）能看见刚写的 metadata。
        em.flush();

        // task-0712 展示修复：建单同事务内服务端建明细行（建单+建行强一致，不丢单）。
        List<UUID> lineIds = materializeService.materializeLines(
                q.id, req.customerId, req.importRecordId, req.customerTemplateId);
        Log.infof("V6 commit: 服务端建明细行 %d 条 (quotation=%s)", lineIds.size(), q.id);

        CommitResult result = new CommitResult(q.id, req.importRecordId, hfPairs.size());
        result.lineItemsCount = lineIds.size();
        return result;
    }

    public static class CommitResult {
        public UUID quotationId;
        public UUID importRecordId;
        public int hfPairsCount;
        // task-0712 展示修复新增（api.md §1）
        public int lineItemsCount;                      // 服务端已建明细行数
        public boolean cardValuesReady = false;         // 卡片值全部落库=true；降级=false（Resource 回填）
        public int costingTreeRows = 0;                 // 本单核价树总节点数（Resource 回填，可选）
        public java.util.List<String> warnings = new java.util.ArrayList<>();

        public CommitResult(UUID quotationId, UUID importRecordId, int hfPairsCount) {
            this.quotationId = quotationId;
            this.importRecordId = importRecordId;
            this.hfPairsCount = hfPairsCount;
        }
    }
}
