package com.cpq.quotation;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.CardSnapshotService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 回归守卫：确保 submit 路径中 CardSnapshotService.ensureExcelValues(id)
 * 对「两套模板均配置」的报价单会填充 quoteExcelValues + costingExcelValues。
 *
 * <p>夹具：739c6aa6-a201-4675-86f5-08f6c4376890（DRAFT，报价+核价模板齐备，1 条行项）。
 *
 * <p>逻辑已在 QuotationResource.java:365 调用，本测试仅做守卫防未来回退。
 * 预期直接 PASS（守卫测试，非 RED→GREEN）。
 *
 * <p>@TestTransaction 保证测试结束后自动回滚，不污染 DB 数据。
 */
@QuarkusTest
class QuotationSubmitFreezeTest {

    @Inject
    CardSnapshotService cardSnapshotService;

    /**
     * 夹具：DRAFT 报价单，customer_template_id + costing_card_template_id 均非 null，
     * 且有 1 条 quotation_line_item 行。
     * 查询依据：SELECT id FROM quotation WHERE customer_template_id IS NOT NULL
     *   AND costing_card_template_id IS NOT NULL AND EXISTS(SELECT 1 FROM quotation_line_item ...)
     *   ORDER BY (status='DRAFT') DESC LIMIT 1;
     */
    private static final UUID QUOTE_WITH_BOTH_TEMPLATES =
            UUID.fromString("739c6aa6-a201-4675-86f5-08f6c4376890");

    @Test
    @TestTransaction
    void ensureExcelValues_populates_both_sides() {
        // 先清零两侧 Excel 值，模拟「submit 前尚未计算」的状态，
        // 确保 ensureExcelValues 的 null 判断分支真正被执行。
        List<QuotationLineItem> preLines =
                QuotationLineItem.list("quotationId", QUOTE_WITH_BOTH_TEMPLATES);
        assumeFalse(preLines.isEmpty(), "夹具应有行项（前置条件）");

        for (QuotationLineItem li : preLines) {
            li.quoteExcelValues = null;
            li.costingExcelValues = null;
            li.persist();
        }

        // 调用被测方法
        cardSnapshotService.ensureExcelValues(QUOTE_WITH_BOTH_TEMPLATES);

        // 重新从 DB 读取（@TestTransaction 内，flush 后可见）
        List<QuotationLineItem> lines =
                QuotationLineItem.list("quotationId", QUOTE_WITH_BOTH_TEMPLATES);
        assertFalse(lines.isEmpty(), "夹具应有行项");
        for (QuotationLineItem li : lines) {
            assertNotNull(li.quoteExcelValues,
                    "报价 Excel 值应已落库（quoteExcelValues != null），lineItem=" + li.id);
            assertNotNull(li.costingExcelValues,
                    "核价 Excel 值应已落库（costingExcelValues != null），lineItem=" + li.id);
        }
    }
}
