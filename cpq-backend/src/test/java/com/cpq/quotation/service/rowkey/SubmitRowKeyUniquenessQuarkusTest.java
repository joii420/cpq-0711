package com.cpq.quotation.service.rowkey;

import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.cpq.quotation.service.QuotationService;
import com.cpq.system.entity.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证 submit 期行键唯一性拦截（Plan Task 5）：
 * 播种一份"重复组合行键"的 DRAFT，断言真实 {@link QuotationService#submit} 在建 snapshot 前抛 422。
 * @TestTransaction 自动回滚，不污染 DB。
 */
@QuarkusTest
class SubmitRowKeyUniquenessQuarkusTest {

    @Inject QuotationService quotationService;

    private static final String STRUCT = """
        { "tabs": [
          { "componentId": "c1", "componentName": "投料", "rowKeyFields": ["child_no", "elem"] }
        ] }""";

    private static final String DUP_VALUES = """
        { "tabs": [ { "componentId": "c1", "baseRows": [
          { "driverRow": { "child_no": "P1", "elem": "Cu" } },
          { "driverRow": { "child_no": "P1", "elem": "Cu" } }
        ] } ] }""";

    @Test
    @TestTransaction
    void submit_withDuplicateRowKey_throws422() {
        // quotation 有 FK：customer_id→customer、sales_rep_id→"user"。取 dev DB 既有父行。
        Customer customer = Customer.<Customer>findAll().firstResult();
        User salesRep = User.<User>findAll().firstResult();
        Assumptions.assumeTrue(customer != null && salesRep != null,
            "需要至少一条 customer + user 种子数据");

        Quotation q = new Quotation();
        q.quotationNumber = "ROWKEY-TEST-" + UUID.randomUUID();
        q.customerId = customer.id;
        q.name = "行键校验测试单";
        q.salesRepId = salesRep.id;
        q.status = "DRAFT";
        q.persist();

        QuotationViewStructure st = new QuotationViewStructure();
        st.quotationId = q.id;
        st.viewKind = "QUOTE_CARD";
        st.structure = STRUCT;
        st.createdAt = OffsetDateTime.now();   // 实体无 @PrePersist，created_at NOT NULL 需手动赋值
        st.persist();

        QuotationLineItem li = new QuotationLineItem();
        li.quotationId = q.id;
        li.productNameSnapshot = "产品A";
        li.quoteCardValues = DUP_VALUES;
        li.persist();

        BusinessException ex = assertThrows(BusinessException.class,
            () -> quotationService.submit(q.id, null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("行键重复"), "报错应含『行键重复』: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("P1||Cu"), "报错应含冲突组合键 P1||Cu: " + ex.getMessage());
    }
}
