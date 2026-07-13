package com.cpq.basicdata.v6.service;

import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class QuotationLineItemMaterializeServiceTest {

    @Inject QuotationLineItemMaterializeService service;
    @Inject EntityManager em;

    /** 固定测试夹具：苏州西门子(customer) + 2205024(user)，与其它 @QuarkusTest 用例一致(见 SaveDraftSerializeLockTest / BatchStage1PersistEquivTest)。 */
    private static final UUID TEST_CUSTOMER_ID = UUID.fromString("9aee3d9d-1b4d-4698-9af6-34bd9979d887");
    private static final UUID TEST_USER_ID = UUID.fromString("896ed7d9-bf12-4ea7-9ff1-09cb14496311");

    /**
     * quotation.customer_template_id / quotation_line_item.template_id 均有 FK 指向 template(id)，
     * 不能用随机 UUID 占位，取库里任意一条真实 template 行。
     */
    @Transactional
    UUID pickAnyTemplateId() {
        Object id = em.createNativeQuery("SELECT id FROM template ORDER BY created_at LIMIT 1").getSingleResult();
        return (UUID) id;
    }

    @Transactional
    UUID seedQuotation(UUID templateId) {
        UUID qid = UUID.randomUUID();
        em.createNativeQuery(
            "INSERT INTO quotation (id, quotation_number, customer_id, sales_rep_id, name, status, " +
            "total_amount, original_amount, system_discount_rate, final_discount_rate, " +
            "tax_rate, tax_amount, is_manually_adjusted, customer_template_id, created_at, updated_at) " +
            "VALUES (:id, :num, :cid, :sid, 'IT-materialize', 'DRAFT', " +
            "0, 0, 100, 100, 0, 0, false, :tid, NOW(), NOW())")
          .setParameter("id", qid)
          .setParameter("num", "IT-MATERIALIZE-" + System.nanoTime())
          .setParameter("cid", TEST_CUSTOMER_ID)
          .setParameter("sid", TEST_USER_ID)
          .setParameter("tid", templateId)
          .executeUpdate();
        return qid;
    }

    /** 删除测试报价单及其所有子行（还原 DB）。 */
    @Transactional
    void cleanupQuotation(UUID qid) {
        em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :id")
          .setParameter("id", qid).executeUpdate();
        em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
          .setParameter("id", qid).executeUpdate();
    }

    private CustomerPartCandidateDTO cand(String partNo, String partName, String cpn, Integer ver) {
        CustomerPartCandidateDTO d = new CustomerPartCandidateDTO();
        d.partNo = partNo; d.partName = partName; d.customerProductNo = cpn; d.currentVersion = ver;
        return d;
    }

    @Test
    @Transactional
    void materialize_N_candidates_creates_N_line_items_in_order() {
        UUID templateId = pickAnyTemplateId();
        UUID qid = seedQuotation(templateId);
        try {
            List<UUID> ids = service.materializeLinesFromCandidates(qid, templateId, List.of(
                cand("S-3120014539", "根产品A", "CPN-A", 2001),
                cand("S-3120014540", "根产品B", "CPN-B", null)));
            assertEquals(2, ids.size());
            List<QuotationLineItem> lines = QuotationLineItem.list("quotationId = ?1 order by sortOrder", qid);
            assertEquals(2, lines.size());
            assertEquals("S-3120014539", lines.get(0).productPartNoSnapshot);
            assertEquals("根产品A", lines.get(0).productNameSnapshot);
            assertEquals("CPN-A", lines.get(0).customerPartNo);
            assertEquals(templateId, lines.get(0).templateId);
            assertEquals("SIMPLE", lines.get(0).compositeType);
            assertEquals(0, lines.get(0).sortOrder);
            assertEquals(2001, lines.get(0).partVersionLocked);
            assertEquals(1, lines.get(1).sortOrder);
            assertEquals(2000, lines.get(1).partVersionLocked);
        } finally {
            cleanupQuotation(qid);
        }
    }

    @Test
    @Transactional
    void empty_candidates_creates_nothing() {
        UUID qid = seedQuotation(pickAnyTemplateId());
        try {
            List<UUID> ids = service.materializeLinesFromCandidates(qid, UUID.randomUUID(), List.of());
            assertTrue(ids.isEmpty());
            assertEquals(0, QuotationLineItem.count("quotationId", qid));
        } finally {
            cleanupQuotation(qid);
        }
    }
}
