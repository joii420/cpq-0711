package com.cpq.quotation.service.rowkey;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RowKeyUniquenessServiceTest {

    @Inject RowKeyUniquenessService svc;

    private static final String STRUCT = """
        { "tabs": [
          { "componentId": "c1", "componentName": "投料", "rowKeyFields": ["child_no", "material"] },
          { "componentId": "c2", "componentName": "无键组件", "rowKeyFields": [] }
        ] }""";

    private RowKeyUniquenessService.LineItemComps item(String label, RowKeyUniquenessService.CompRows... comps) {
        return new RowKeyUniquenessService.LineItemComps(label, List.of(comps));
    }

    @Test
    void driverColumnDuplicate_detected() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P2" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Cu" }, { "material": "Cu" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
        assertEquals(List.of(0, 1), r.get(0).rowIndices());
    }

    @Test
    void uniqueMixedKeys_noConflict() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } }, { "driverRow": { "child_no": "P1" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Ni" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertTrue(r.isEmpty());
    }

    @Test
    void manualRowsDuplicate_detected() {
        String snap = "[]";
        String rd = """
          [ { "_origin": "manual", "child_no": "M1", "material": "X" },
            { "_origin": "manual", "child_no": "M1", "material": "X" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("M1||X", r.get(0).rowKey());
    }

    @Test
    void manualRowDuplicatesDriverRow_detected() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } } ]""";
        String rd = """
          [ { "child_no": "P1", "material": "Cu" },
            { "_origin": "manual", "child_no": "P1", "material": "Cu" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
    }

    @Test
    void componentWithoutRowKeyFields_skipped() {
        String rd = """
          [ { "x": "1" }, { "x": "1" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c2", "[]", rd))));
        assertTrue(r.isEmpty());
    }

    @Test
    void allBlankKeys_notFlagged() {
        String snap = "[]";
        String rd = """
          [ { "_origin": "manual" }, { "_origin": "manual" } ]""";
        List<RowKeyConflict> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertTrue(r.isEmpty());
    }
}
