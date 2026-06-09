package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.service.FormulaCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯 JUnit（不启 Quarkus）：直接构造服务并注入真实 FormulaCalculator（computeRowKey 无注入依赖）。
 * 真实 CDI 装配由提交集成（Plan Task 5）验证。
 */
class RowKeyUniquenessServiceTest {

    private RowKeyUniquenessService svc;

    private static final String STRUCT = """
        { "tabs": [
          { "componentId": "c1", "componentName": "投料", "rowKeyFields": ["child_no", "elem"] },
          { "componentId": "c2", "componentName": "无键组件", "rowKeyFields": [] }
        ] }""";

    @BeforeEach
    void setUp() {
        svc = new RowKeyUniquenessService();
        svc.formulaCalculator = new FormulaCalculator();
    }

    @Test
    void duplicateCompositeKey_detected() {
        String values = """
          { "tabs": [ { "componentId": "c1", "baseRows": [
            { "driverRow": { "child_no": "P1", "elem": "Cu" } },
            { "driverRow": { "child_no": "P1", "elem": "Cu" } },
            { "driverRow": { "child_no": "P2", "elem": "Cu" } }
          ] } ] }""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(new RowKeyUniquenessService.LineItemRows("产品A", values)));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
        assertEquals(List.of(0, 1), r.get(0).rowIndices());
    }

    @Test
    void uniqueCompositeKeys_noConflict() {
        String values = """
          { "tabs": [ { "componentId": "c1", "baseRows": [
            { "driverRow": { "child_no": "P1", "elem": "Cu" } },
            { "driverRow": { "child_no": "P1", "elem": "Ni" } }
          ] } ] }""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(new RowKeyUniquenessService.LineItemRows("产品A", values)));
        assertTrue(r.isEmpty());
    }

    @Test
    void componentWithoutRowKeyFields_skipped() {
        String values = """
          { "tabs": [ { "componentId": "c2", "baseRows": [
            { "driverRow": { "x": "1" } }, { "driverRow": { "x": "1" } }
          ] } ] }""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(new RowKeyUniquenessService.LineItemRows("产品A", values)));
        assertTrue(r.isEmpty());
    }
}
