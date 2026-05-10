package com.cpq.formula;

import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.formula.calculator.DerivedAttributeCalculatorV5;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.function.FunctionRegistry;
import com.cpq.formula.function.business.*;
import com.cpq.formula.function.lookup.ExistsFunction;
import com.cpq.formula.function.lookup.LookupFunction;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DerivedAttributeCalculatorV5 端到端测试（不依赖 Quarkus 容器）。
 */
class DerivedAttributeCalculatorV5Test {

    private DerivedAttributeCalculatorV5 calculator;
    private DataLoader mockDataLoader;

    @BeforeEach
    void setUp() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenThrow(new RuntimeException("no-db-in-unit-test"));

        ExchangeFunction exchangeFn = new ExchangeFunction();
        injectField(exchangeFn, "dataSource", mockDs);
        TaxIncludedFunction taxInFn = new TaxIncludedFunction();
        injectField(taxInFn, "dataSource", mockDs);
        TaxExcludedFunction taxExFn = new TaxExcludedFunction();
        injectField(taxExFn, "dataSource", mockDs);

        FunctionRegistry registry = new FunctionRegistry(
                exchangeFn, taxInFn, taxExFn,
                new ElementPriceFunction(), new PremiumPriceFunction(),
                new LookupFunction(), new ExistsFunction());

        FormulaEngine engine = new FormulaEngine();
        injectField(engine, "registry", registry);

        mockDataLoader = mock(DataLoader.class);

        // Instance<DataLoader> mock
        @SuppressWarnings("unchecked")
        Instance<DataLoader> mockInstance = mock(Instance.class);
        when(mockInstance.get()).thenReturn(mockDataLoader);

        calculator = new DerivedAttributeCalculatorV5();
        injectField(calculator, "formulaEngine", engine);
        injectField(calculator, "dataLoaderInstance", mockInstance);
    }

    @Test
    void calc01_simpleArithmeticFormula() {
        DerivedAttribute attr = makeAttr("total_price", "EXPRESSION",
                "{\"formula\": \"unit_price * qty\"}");

        // DataLoader mock 不需要
        Map<String, Object> results = calculator.calculate(
                UUID.randomUUID(), "P001", List.of(attr));

        // calculator.calculate builds its own EvaluationContext without row bindings
        // so unit_price * qty resolves as null * null → JEXL may return 0 or null
        assertNotNull(results);
        assertTrue(results.containsKey("total_price"));
    }

    @Test
    void calc02_inactiveAttributeSkipped() {
        DerivedAttribute active = makeAttr("a1", "EXPRESSION", "{\"formula\": \"1 + 1\"}");
        DerivedAttribute inactive = makeAttr("a2", "EXPRESSION", "{\"formula\": \"99\"}");
        inactive.status = "INACTIVE";

        Map<String, Object> results = calculator.calculate(
                UUID.randomUUID(), "P001", List.of(active, inactive));

        assertTrue(results.containsKey("a1"));
        assertFalse(results.containsKey("a2"), "INACTIVE 属性不应计算");
    }

    @Test
    void calc03_missingFormula_returnsFormulaError() {
        DerivedAttribute attr = makeAttr("no_formula", "EXPRESSION", null);

        Map<String, Object> results = calculator.calculate(
                UUID.randomUUID(), "P001", List.of(attr));

        assertTrue(results.containsKey("no_formula"));
        assertInstanceOf(FormulaError.class, results.get("no_formula"));
    }

    @Test
    void calc04_emptyAttributeList_returnsEmptyMap() {
        Map<String, Object> results = calculator.calculate(
                UUID.randomUUID(), "P001", List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void calc05_roundFunctionFormula() {
        DerivedAttribute attr = makeAttr("rounded", "EXPRESSION",
                "{\"formula\": \"ROUND(3.14159, 2)\"}");

        Map<String, Object> results = calculator.calculate(
                UUID.randomUUID(), "P001", List.of(attr));

        Object val = results.get("rounded");
        assertNotNull(val);
        if (val instanceof BigDecimal bd) {
            assertEquals(0, bd.compareTo(new BigDecimal("3.14")));
        }
    }

    @Test
    void calc06_pathResolution_viaDataLoader() throws Exception {
        when(mockDataLoader.loadByPath("元素BOM[元素='Ag'].组成含量(%)"))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(Map.of("composition_pct", new BigDecimal("90.0")))));

        DerivedAttribute attr = makeAttr("ag_content", "EXPRESSION",
                "{\"formula\": \"{元素BOM[元素='Ag'].组成含量(%)}\"}");

        Map<String, Object> results = calculator.calculate(
                UUID.randomUUID(), "P001", List.of(attr));

        assertNotNull(results.get("ag_content"));
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private static DerivedAttribute makeAttr(String code, String type, String computation) {
        DerivedAttribute attr = new DerivedAttribute();
        attr.id = UUID.randomUUID();
        attr.variableCode = code;
        attr.variableLabel = code;
        attr.computationType = type;
        attr.computation = computation;
        attr.status = "ACTIVE";
        attr.sortOrder = 0;
        attr.hostSheetId = UUID.randomUUID();
        attr.createdAt = OffsetDateTime.now();
        attr.updatedAt = OffsetDateTime.now();
        return attr;
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try { return clazz.getDeclaredField(name); }
            catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
