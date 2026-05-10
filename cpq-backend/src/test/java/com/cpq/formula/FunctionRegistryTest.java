package com.cpq.formula;

import com.cpq.formula.function.*;
import com.cpq.formula.function.aggregate.AvgFunction;
import com.cpq.formula.function.aggregate.CountFunction;
import com.cpq.formula.function.aggregate.SumFunction;
import com.cpq.formula.function.array.ContainsFunction;
import com.cpq.formula.function.array.InFunction;
import com.cpq.formula.function.conditional.IfErrorFunction;
import com.cpq.formula.function.conditional.IfFunction;
import com.cpq.formula.function.lookup.ExistsFunction;
import com.cpq.formula.function.lookup.LookupFunction;
import com.cpq.formula.function.math.*;
import com.cpq.formula.function.type.BoolFunction;
import com.cpq.formula.function.type.NumFunction;
import com.cpq.formula.function.type.StrFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 22 个函数的纯单元测试（不依赖 Quarkus 容器）。
 *
 * <p>覆盖：每个函数至少 1 个用例（共 22+ 用例）。
 */
class FunctionRegistryTest {

    private EvaluationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = EvaluationContext.builder().build();
    }

    // ── 类型转换 (3) ─────────────────────────────────────────────────────────

    @Test
    void t01_NUM_stringToDecimal() {
        NumFunction fn = new NumFunction();
        Object result = fn.invoke(List.of("3.14"), ctx);
        assertEquals(new BigDecimal("3.14"), result);
    }

    @Test
    void t01b_NUM_invalidString_returnsError() {
        NumFunction fn = new NumFunction();
        Object result = fn.invoke(List.of("abc"), ctx);
        assertInstanceOf(FormulaError.class, result);
        assertEquals("TYPE_MISMATCH", ((FormulaError) result).getErrorCode());
    }

    @Test
    void t02_STR_numberToString() {
        StrFunction fn = new StrFunction();
        Object result = fn.invoke(List.of(new BigDecimal("42.5")), ctx);
        assertEquals("42.5", result);
    }

    @Test
    void t02b_STR_null_returnsError() {
        StrFunction fn = new StrFunction();
        // List.of() does not allow null; use Arrays.asList to pass null argument
        Object result = fn.invoke(java.util.Arrays.asList((Object) null), ctx);
        assertInstanceOf(FormulaError.class, result);
    }

    @Test
    void t03_BOOL_numberZeroIsFalse() {
        BoolFunction fn = new BoolFunction();
        Object result = fn.invoke(List.of(BigDecimal.ZERO), ctx);
        assertEquals(Boolean.FALSE, result);
    }

    @Test
    void t03b_BOOL_nonZeroIsTrue() {
        BoolFunction fn = new BoolFunction();
        Object result = fn.invoke(List.of(new BigDecimal("1")), ctx);
        assertEquals(Boolean.TRUE, result);
    }

    // ── 数学 (6) ─────────────────────────────────────────────────────────────

    @Test
    void t04_ROUND_halfUp() {
        RoundFunction fn = new RoundFunction();
        Object result = fn.invoke(List.of(new BigDecimal("3.456"), 2), ctx);
        assertEquals(new BigDecimal("3.46"), result);
    }

    @Test
    void t04b_ROUND_typeMismatch_returnsError() {
        RoundFunction fn = new RoundFunction();
        Object result = fn.invoke(List.of("not-a-number", 2), ctx);
        assertInstanceOf(FormulaError.class, result);
    }

    @Test
    void t05_CEIL_positive() {
        CeilFunction fn = new CeilFunction();
        Object result = fn.invoke(List.of(new BigDecimal("2.1")), ctx);
        assertEquals(new BigDecimal("3"), result);
    }

    @Test
    void t06_FLOOR_negative() {
        FloorFunction fn = new FloorFunction();
        Object result = fn.invoke(List.of(new BigDecimal("-2.1")), ctx);
        assertEquals(new BigDecimal("-3"), result);
    }

    @Test
    void t07_MAX_multipleValues() {
        MaxFunction fn = new MaxFunction();
        Object result = fn.invoke(
                List.of(new BigDecimal("3"), new BigDecimal("7"), new BigDecimal("1")), ctx);
        assertEquals(new BigDecimal("7"), result);
    }

    @Test
    void t08_MIN_multipleValues() {
        MinFunction fn = new MinFunction();
        Object result = fn.invoke(
                List.of(new BigDecimal("3"), new BigDecimal("7"), new BigDecimal("1")), ctx);
        assertEquals(new BigDecimal("1"), result);
    }

    @Test
    void t09_ABS_negative() {
        AbsFunction fn = new AbsFunction();
        Object result = fn.invoke(List.of(new BigDecimal("-5.5")), ctx);
        assertEquals(new BigDecimal("5.5"), result);
    }

    // ── 聚合 (3) ─────────────────────────────────────────────────────────────

    @Test
    void t10_SUM_listOfNumbers() {
        SumFunction fn = new SumFunction();
        List<BigDecimal> nums = List.of(
                new BigDecimal("1.5"), new BigDecimal("2.5"), new BigDecimal("3.0"));
        Object result = fn.invoke(List.of(nums), ctx);
        assertEquals(new BigDecimal("7.0"), result);
    }

    @Test
    void t10b_SUM_emptyList_returnsZero() {
        SumFunction fn = new SumFunction();
        Object result = fn.invoke(List.of(List.of()), ctx);
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void t11_AVG_listOfMaps_withField() {
        AvgFunction fn = new AvgFunction();
        List<Map<String, Object>> rows = List.of(
                Map.of("price", new BigDecimal("10")),
                Map.of("price", new BigDecimal("20")),
                Map.of("price", new BigDecimal("30")));
        Object result = fn.invoke(List.of(rows, "price"), ctx);
        assertInstanceOf(BigDecimal.class, result);
        assertEquals(0, ((BigDecimal) result).compareTo(new BigDecimal("20")));
    }

    @Test
    void t11b_AVG_emptyList_returnsNull() {
        AvgFunction fn = new AvgFunction();
        Object result = fn.invoke(List.of(List.of()), ctx);
        assertNull(result);
    }

    @Test
    void t12_COUNT_list() {
        CountFunction fn = new CountFunction();
        Object result = fn.invoke(List.of(List.of("a", "b", "c")), ctx);
        assertEquals(3L, result);
    }

    // ── 查找 (2) ─────────────────────────────────────────────────────────────

    @Test
    void t13_LOOKUP_listWithField() {
        LookupFunction fn = new LookupFunction();
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "Alice", "score", 95),
                Map.of("name", "Bob", "score", 80));
        Object result = fn.invoke(List.of(rows, "name"), ctx);
        assertEquals("Alice", result);
    }

    @Test
    void t13b_LOOKUP_emptyList_returnsNull() {
        LookupFunction fn = new LookupFunction();
        Object result = fn.invoke(List.of(List.of()), ctx);
        assertNull(result);
    }

    @Test
    void t14_EXISTS_nonEmptyList_returnsTrue() {
        ExistsFunction fn = new ExistsFunction();
        Object result = fn.invoke(List.of(List.of("item")), ctx);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void t14b_EXISTS_emptyList_returnsFalse() {
        ExistsFunction fn = new ExistsFunction();
        Object result = fn.invoke(List.of(List.of()), ctx);
        assertEquals(Boolean.FALSE, result);
    }

    // ── 条件 (2) ─────────────────────────────────────────────────────────────

    @Test
    void t18_IF_condTrue_returnsTrueValue() {
        IfFunction fn = new IfFunction();
        Object result = fn.invoke(List.of(Boolean.TRUE, "yes", "no"), ctx);
        assertEquals("yes", result);
    }

    @Test
    void t18b_IF_condFalse_returnsFalseValue() {
        IfFunction fn = new IfFunction();
        Object result = fn.invoke(List.of(Boolean.FALSE, "yes", "no"), ctx);
        assertEquals("no", result);
    }

    @Test
    void t19_IFERROR_errorValue_returnsFallback() {
        IfErrorFunction fn = new IfErrorFunction();
        FormulaError error = new FormulaError("test error");
        Object result = fn.invoke(List.of(error, "fallback"), ctx);
        assertEquals("fallback", result);
    }

    @Test
    void t19b_IFERROR_normalValue_returnsValue() {
        IfErrorFunction fn = new IfErrorFunction();
        Object result = fn.invoke(List.of(new BigDecimal("42"), "fallback"), ctx);
        assertEquals(new BigDecimal("42"), result);
    }

    // ── 数组 (2) ─────────────────────────────────────────────────────────────

    @Test
    void t20_IN_valueInList_returnsTrue() {
        InFunction fn = new InFunction();
        Object result = fn.invoke(List.of("B", List.of("A", "B", "C")), ctx);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void t20b_IN_valueNotInList_returnsFalse() {
        InFunction fn = new InFunction();
        Object result = fn.invoke(List.of("D", List.of("A", "B", "C")), ctx);
        assertEquals(Boolean.FALSE, result);
    }

    @Test
    void t21_CONTAINS_stringContains_returnsTrue() {
        ContainsFunction fn = new ContainsFunction();
        Object result = fn.invoke(List.of("hello world", "world"), ctx);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void t21b_CONTAINS_stringNotContains_returnsFalse() {
        ContainsFunction fn = new ContainsFunction();
        Object result = fn.invoke(List.of("hello world", "xyz"), ctx);
        assertEquals(Boolean.FALSE, result);
    }

    // ── 业务函数 v2 占位符 (2) ─────────────────────────────────────────────

    @Test
    void t22_ELEMENT_PRICE_v1Unsupported() {
        com.cpq.formula.function.business.ElementPriceFunction fn =
                new com.cpq.formula.function.business.ElementPriceFunction();
        assertThrows(UnsupportedOperationException.class,
                () -> fn.invoke(List.of("Ag"), ctx));
    }

    @Test
    void t22b_PREMIUM_PRICE_v1Unsupported() {
        com.cpq.formula.function.business.PremiumPriceFunction fn =
                new com.cpq.formula.function.business.PremiumPriceFunction();
        assertThrows(UnsupportedOperationException.class,
                () -> fn.invoke(List.of("Ag", "customer-uuid"), ctx));
    }
}
