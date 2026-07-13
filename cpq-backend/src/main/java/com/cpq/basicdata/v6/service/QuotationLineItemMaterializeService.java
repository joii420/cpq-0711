package com.cpq.basicdata.v6.service;

import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import com.cpq.quotation.service.CustomerPartCandidateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端从「导入候选 + 报价模板」建 quotation_line_item 明细行。
 *
 * <p>与前端 buildLineItemFromTemplate 等价，但只负责 INSERT 主表 quotation_line_item：
 * componentData 子表(quotation_line_component_data) + snapshot_rows 由后置的
 * ConfigureSnapshotService.snapshotQuotation 的 writeSnapshot UPSERT 自建，本类不碰。
 *
 * <p>事务：默认 REQUIRED —— 由调用方(如 V6QuotationCommitService.createQuotation)在其
 * @Transactional 内调用时并入同一事务，保证「建单 + 建行」强一致（不丢单）。
 */
@ApplicationScoped
public class QuotationLineItemMaterializeService {

    @Inject EntityManager em;
    @Inject CustomerPartCandidateService candidateService;

    /** 便捷入口：查候选 → 建行。 */
    @Transactional
    public List<UUID> materializeLines(UUID quotationId, UUID customerId,
                                       UUID importRecordId, UUID templateId) {
        List<CustomerPartCandidateDTO> candidates =
            candidateService.listCandidates(customerId, importRecordId);
        return materializeLinesFromCandidates(quotationId, templateId, candidates);
    }

    /** 纯建行：按候选顺序 INSERT quotation_line_item（sort_order 从 0 递增）。 */
    @Transactional
    public List<UUID> materializeLinesFromCandidates(UUID quotationId, UUID templateId,
                                                     List<CustomerPartCandidateDTO> candidates) {
        List<UUID> ids = new ArrayList<>();
        if (quotationId == null || candidates == null || candidates.isEmpty()) return ids;
        int sort = 0;
        for (CustomerPartCandidateDTO c : candidates) {
            if (c == null || c.partNo == null || c.partNo.isBlank()) continue;
            UUID id = UUID.randomUUID();
            em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, template_id, product_part_no_snapshot, product_name_snapshot, " +
                " customer_part_no, composite_type, sort_order, part_version_locked, " +
                " product_attribute_values, created_at) " +
                "VALUES (:id, :q, :tid, :pn, :pname, :cpn, 'SIMPLE', :sort, :ver, cast('{}' as jsonb), NOW())")
                .setParameter("id", id)
                .setParameter("q", quotationId)
                .setParameter("tid", templateId)
                .setParameter("pn", c.partNo)
                .setParameter("pname", c.partName != null ? c.partName
                        : (c.customerPartName != null ? c.customerPartName : c.partNo))
                .setParameter("cpn", c.customerProductNo)
                .setParameter("sort", sort)
                .setParameter("ver", c.currentVersion != null ? c.currentVersion : 2000)
                .executeUpdate();
            ids.add(id);
            sort++;
        }
        return ids;
    }
}
