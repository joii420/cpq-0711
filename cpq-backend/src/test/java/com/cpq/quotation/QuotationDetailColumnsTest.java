package com.cpq.quotation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD：验证 getById 把带 display_format 的有效 Excel 列定义随响应捎回。
 * 夹具：3f025848-c08f-4ddf-a5c9-3007bc1002c9（APPROVED，双模板齐备）。
 */
@QuarkusTest
class QuotationDetailColumnsTest {

    @Inject
    com.cpq.quotation.service.QuotationService quotationService;

    private static final UUID SUBMITTED_QUOTE_ID =
            UUID.fromString("3f025848-c08f-4ddf-a5c9-3007bc1002c9");

    @Test
    @TestTransaction
    void getById_exposes_effective_excel_columns_with_display_format() {
        var dto = quotationService.getById(SUBMITTED_QUOTE_ID);

        assertNotNull(dto.quoteExcelColumns, "报价有效列应被暴露");
        assertFalse(dto.quoteExcelColumns.isEmpty(), "报价有效列非空");
        assertTrue(
                dto.quoteExcelColumns.stream().anyMatch(c -> c.containsKey("col_key")),
                "列定义应为 snake_case col_key");
        assertNotNull(dto.costingExcelColumns, "核价有效列字段应存在");
    }
}
