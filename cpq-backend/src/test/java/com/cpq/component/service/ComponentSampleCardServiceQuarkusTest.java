package com.cpq.component.service;

import com.cpq.customer.entity.Customer;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.system.entity.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 碰库回归 IT：{@link ComponentSampleCardService#sampleCardsForComponent} 的 DB 反查路径。
 *
 * <p>背景：该方法曾用 Panache 方法引用 {@code QuotationLineItem::findById} /
 * {@code Quotation::findById}（:85/:88），运行时抛
 * {@code IllegalStateException: ...did you forget to annotate your entity with @Entity?}
 * → 端点 500 → 抽屉「样本卡片加载失败」。已改 lambda 修复。
 *
 * <p>本 IT 断言<b>业务契约</b>（非"先复现红"）：有引用 → 返回正确样本卡且不抛异常；无引用 → 返空。
 * @TestTransaction 自动回滚，不污染 DB。
 */
@QuarkusTest
class ComponentSampleCardServiceQuarkusTest {

    @Inject
    ComponentSampleCardService service;

    @Test
    @TestTransaction
    void sampleCardsForComponent_withReferencingComponentData_returnsCard() {
        // Quotation 有 FK：customer_id→customer、sales_rep_id→"user"。取既有种子父行。
        Customer customer = Customer.<Customer>findAll().firstResult();
        User salesRep = User.<User>findAll().firstResult();
        Assumptions.assumeTrue(customer != null && salesRep != null,
            "需要至少一条 customer + user 种子数据");

        UUID compId = UUID.randomUUID();

        Quotation q = new Quotation();
        q.quotationNumber = "SC-" + UUID.randomUUID();  // ≤50 (varchar 限制)
        q.customerId = customer.id;
        q.name = "样本卡测试单";
        q.salesRepId = salesRep.id;
        q.status = "DRAFT";
        q.persist();

        QuotationLineItem li = new QuotationLineItem();
        li.quotationId = q.id;
        li.productNameSnapshot = "样本产品A";
        li.persist();

        QuotationLineComponentData cd = new QuotationLineComponentData();
        cd.lineItemId = li.id;
        cd.componentId = compId;
        cd.tabName = "投料";
        cd.rowData = "[]";
        cd.persist();

        // 关键：此调用会经 QuotationLineItem.findById + Quotation.findById（曾是方法引用 bug 点）
        List<Map<String, Object>> cards = service.sampleCardsForComponent(compId);

        assertEquals(1, cards.size(), "应反查到 1 张样本卡");
        Map<String, Object> card = cards.get(0);
        assertEquals(li.id.toString(), card.get("lineItemId"));
        assertEquals(q.id.toString(), card.get("quotationId"));
        assertEquals(q.quotationNumber, card.get("quotationNo"), "经 Quotation.findById 取到报价号");
        assertEquals("样本产品A", card.get("cardName"), "经 QuotationLineItem.findById 取到产品名");
    }

    @Test
    @TestTransaction
    void sampleCardsForComponent_noReference_returnsEmpty() {
        // 全新随机 componentId，无任何 componentData 引用 → 空列表（早返，不碰 findById）
        assertTrue(service.sampleCardsForComponent(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void sampleCardsForComponent_nullId_returnsEmpty() {
        assertTrue(service.sampleCardsForComponent(null).isEmpty());
    }
}
