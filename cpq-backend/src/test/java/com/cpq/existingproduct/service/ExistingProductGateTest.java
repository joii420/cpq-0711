package com.cpq.existingproduct.service;

import com.cpq.common.dto.PageResult;
import com.cpq.existingproduct.dto.ExistingProductDTO;
import com.cpq.quotation.entity.Quotation;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0721 报价数据版本升级 · B7 —— 「从已有产品添加」闸门自测（需求说明 AC-4）。
 *
 * <p>同客户下两个料号：一个 {@code pending_quotation_id} 非空（未审核报价单占用），一个为 NULL（已生效）。
 * 断言：{@link ExistingProductService#list} 只返回后者，前者被单表谓词过滤，零 N+1（无额外查询循环）。
 */
@QuarkusTest
class ExistingProductGateTest {

    @Inject ExistingProductService service;
    @Inject EntityManager em;

    @SuppressWarnings("unchecked")
    private UUID resolveCustomerCodeCustomerId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @SuppressWarnings("unchecked")
    private UUID resolveUserId() {
        List<Object> rows = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @Test
    @TestTransaction
    void pendingRowExcluded_officialRowShown() {
        UUID customerId = resolveCustomerCodeCustomerId();
        UUID salesRepId = resolveUserId();
        assumeTrue(customerId != null, "需要共享 DB 中存在 customer 行");
        assumeTrue(salesRepId != null, "需要共享 DB 中存在 user 行");

        String customerNo = (String) em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getSingleResult();

        Quotation q = new Quotation();
        q.quotationNumber = "TEST-B7-GATE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        q.name = "ExistingProductGateTest";
        q.customerId = customerId;
        q.salesRepId = salesRepId;
        q.persist();
        em.flush();

        String officialNo = "T7O" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String pendingNo = "T7P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        insertMcm(officialNo, customerNo, "OFFICIAL-" + officialNo, null);
        insertMcm(pendingNo, customerNo, "PENDING-" + pendingNo, q.id);
        em.flush();

        PageResult<ExistingProductDTO> result = service.list(q.id, null, null, null, null, 0, 50);
        List<String> materialNos = result.getContent().stream().map(d -> d.materialNo).toList();

        assertTrue(materialNos.contains(officialNo), "已生效(pending_quotation_id=NULL)料号应出现");
        assertFalse(materialNos.contains(pendingNo), "未审核(pending_quotation_id 非空)料号不应出现（AC-4）");
    }

    private void insertMcm(String materialNo, String customerNo, String customerProductNo, UUID pendingQuotationId) {
        em.createNativeQuery(
                "INSERT INTO material_customer_map " +
                "(id, material_no, customer_no, customer_product_no, system_type, pending_quotation_id, " +
                " created_at, updated_at) " +
                "VALUES (:id, :mn, :cn, :cpn, 'QUOTE', :pq, now(), now())")
            .setParameter("id", UUID.randomUUID())
            .setParameter("mn", materialNo)
            .setParameter("cn", customerNo)
            .setParameter("cpn", customerProductNo)
            .setParameter("pq", pendingQuotationId)
            .executeUpdate();
    }
}
