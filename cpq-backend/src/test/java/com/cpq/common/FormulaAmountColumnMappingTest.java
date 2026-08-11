package com.cpq.common;

import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.entity.MaterialPriceReviewColumn;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.entity.QuotationPriceRevision;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormulaAmountColumnMappingTest {

    @Test
    void allTwentyOneFormulaAmountColumnsUseNumeric26Scale12() throws Exception {
        List<FieldRef> fields = List.of(
                ref(Quotation.class, "totalAmount"), ref(Quotation.class, "originalAmount"),
                ref(Quotation.class, "taxAmount"), ref(QuotationLineItem.class, "subtotal"),
                ref(QuotationLineItem.class, "discountBaseAmount"), ref(QuotationLineItem.class, "lineUnitPrice"),
                ref(QuotationLineItem.class, "lineFinalPrice"), ref(QuotationLineItem.class, "lineDiscountAmount"),
                ref(QuotationLineItem.class, "lineTotalAmount"), ref(QuotationLineComponentData.class, "subtotal"),
                ref(CostingOrder.class, "totalAmount"), ref(CostingOrder.class, "costingTotalAmount"),
                ref(MaterialPriceReview.class, "warnDiff"), ref(MaterialPriceReviewColumn.class, "quoteCurrent"),
                ref(MaterialPriceReviewColumn.class, "quoteAdjusted"), ref(MaterialPriceReviewColumn.class, "costingCurrent"),
                ref(MaterialPriceReviewColumn.class, "costingAdjusted"), ref(MaterialPriceReviewColumn.class, "diffCurrent"),
                ref(MaterialPriceReviewColumn.class, "diffAdjusted"), ref(QuotationPriceRevision.class, "quoteTotalAmount"),
                ref(MaterialPriceUpdateJobItem.class, "diffValue"));

        assertEquals(21, fields.size());
        for (FieldRef ref : fields) {
            Field field = ref.type.getDeclaredField(ref.name);
            Column column = field.getAnnotation(Column.class);
            assertEquals(26, column.precision(), ref.type.getSimpleName() + "." + ref.name);
            assertEquals(12, column.scale(), ref.type.getSimpleName() + "." + ref.name);
        }
    }

    private static FieldRef ref(Class<?> type, String name) {
        return new FieldRef(type, name);
    }

    private record FieldRef(Class<?> type, String name) {}
}
