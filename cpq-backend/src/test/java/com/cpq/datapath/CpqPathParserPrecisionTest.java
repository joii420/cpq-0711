package com.cpq.datapath;

import com.cpq.datapath.ast.EqPredicate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CpqPathParserPrecisionTest {

    @Test
    void decimalPredicateLiteralIsBigDecimalWithoutFloatingPoint() {
        var expression = new CpqPathParser().parse(
                "mat_bom[ratio=0.123456789012].input_material_no");
        EqPredicate predicate = assertInstanceOf(EqPredicate.class,
                expression.getPrimarySegment().getPredicate());
        assertEquals(new BigDecimal("0.123456789012"), predicate.getValue());
    }
}
