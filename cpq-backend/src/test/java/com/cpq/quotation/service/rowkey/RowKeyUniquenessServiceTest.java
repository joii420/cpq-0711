package com.cpq.quotation.service.rowkey;

import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RowKeyUniquenessServiceTest {

    @Inject RowKeyUniquenessService svc;

    private static final ObjectMapper M = new ObjectMapper();

    private static final String STRUCT = """
        { "tabs": [
          { "componentId": "c1", "componentName": "投料", "rowKeyFields": ["child_no", "material"] },
          { "componentId": "c2", "componentName": "无键组件", "rowKeyFields": [] },
          { "componentId": "c3", "componentName": "BOM", "rowKeyFields": ["料件"] }
        ] }""";

    private RowKeyUniquenessService.LineItemComps item(String partNo, RowKeyUniquenessService.CompRows... comps) {
        return new RowKeyUniquenessService.LineItemComps("LI-" + partNo, "产品" + partNo, partNo, List.of(comps));
    }

    @Test
    void driverColumnDuplicate_detected() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P2" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Cu" }, { "material": "Cu" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("A1", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        RowKeyConflictDTO c = r.get(0);
        assertEquals("P1||Cu", c.rowKey());
        assertEquals(List.of(1, 2), c.rowIndices());   // 1 基（原断言是 0 基 [0,1]）
        assertEquals("c1", c.componentId());
        assertEquals("投料", c.tabName());
        assertEquals("LI-A1", c.lineItemId());
        assertEquals("A1", c.productPartNo());
        assertEquals("产品A1", c.productName());
    }

    @Test
    void uniqueMixedKeys_noConflict() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } }, { "driverRow": { "child_no": "P1" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Ni" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertTrue(r.isEmpty());
    }

    @Test
    void manualRowsDuplicate_detected() {
        String snap = "[]";
        String rd = """
          [ { "_origin": "manual", "child_no": "M1", "material": "X" },
            { "_origin": "manual", "child_no": "M1", "material": "X" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
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
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertEquals(1, r.size());
        assertEquals("P1||Cu", r.get(0).rowKey());
    }

    @Test
    void componentWithoutRowKeyFields_skipped() {
        String rd = """
          [ { "x": "1" }, { "x": "1" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c2", "[]", rd))));
        assertTrue(r.isEmpty());
    }

    @Test
    void allBlankKeys_notFlagged() {
        String snap = "[]";
        String rd = """
          [ { "_origin": "manual" }, { "_origin": "manual" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd))));
        assertTrue(r.isEmpty());
    }

    // =========================================================================
    // repair-0727 B5（P0）—— 树页签 nodeId 判重维度 + 墓碑消费单测
    // 复刻需求说明 §11.3 实机场景：992/AgNi11#-Ⅰ 挂两个不同父节点(S-3120014539 / S-80011)
    // =========================================================================

    private JsonNode j(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    private String fp(String materialValue) {
        return DeletedRowKeys.rowFingerprint(List.of("料件"), j("{\"料件\":\"" + materialValue + "\"}"));
    }

    /** 场景①：992 挂两父，两行都在（不删）→ nodeId 不同即合法结构，不冲突（AC-3 核心场景之一）。 */
    @Test
    void treeTab_sameRowKeyDifferentNode_bothPresent_noConflict() {
        String snap = """
          [ { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" },
            { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-80011/992" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c3", snap, "[]", null))));
        assertTrue(r.isEmpty(), "同料号挂不同父节点是合法结构，不应报行键重复");
    }

    /** 场景②：删掉其中一条(nodeId=S-3120014539/992 打墓碑) → 剩 1 行，不冲突。 */
    @Test
    void treeTab_oneRowTombstoned_noConflict() {
        String snap = """
          [ { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" },
            { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-80011/992" } ]""";
        String deletedRowKeys = "[{\"effKey\":\"x\",\"fp\":\"" + fp("AgNi11#-Ⅰ") + "\",\"nodeId\":\"S-3120014539/992\"}]";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c3", snap, "[]", deletedRowKeys))));
        assertTrue(r.isEmpty(), "行墓碑必须被消费：已删的行不应参与判重");
    }

    /** 场景③（P0 核心）：两条都删（两条墓碑）→ 不冲突（改动前 422 拦截，用户无自救路径）。 */
    @Test
    void treeTab_bothRowsTombstoned_noConflict() {
        String snap = """
          [ { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" },
            { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-80011/992" } ]""";
        String sameFp = fp("AgNi11#-Ⅰ");
        String deletedRowKeys = "["
            + "{\"effKey\":\"x1\",\"fp\":\"" + sameFp + "\",\"nodeId\":\"S-3120014539/992\"},"
            + "{\"effKey\":\"x2\",\"fp\":\"" + sameFp + "\",\"nodeId\":\"S-80011/992\"}]";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c3", snap, "[]", deletedRowKeys))));
        assertTrue(r.isEmpty(), "P0：两条重复行都删除后必须能提交，不应再报行键重复");
    }

    /** 场景④：同一节点下两条相同行键的明细 → 真撞键，仍需拦截（AC-4）。 */
    @Test
    void treeTab_sameNodeSameRowKey_stillConflict() {
        String snap = """
          [ { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" },
            { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" } ]""";
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c3", snap, "[]", null))));
        assertEquals(1, r.size(), "同一节点下两条相同行键仍是真撞键，必须拦截");
        // 树行判重键 = computeDedupKey + "@" + __nodeId（api.md §3.1.2），DTO 报出的即该组合键
        assertEquals("AgNi11#-Ⅰ@S-3120014539/992", r.get(0).rowKey());
    }

    /** 场景⑤：剪枝墓碑（deleted_tree_nodes）命中其中一个节点 → 该行不参与判重，不冲突。 */
    @Test
    void treeTab_prunedNode_excludedFromConflictCheck() {
        String snap = """
          [ { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" },
            { "driverRow": { "料件": "AgNi11#-Ⅰ" }, "__nodeId": "S-3120014539/992" } ]""";
        // 剪枝墓碑命中 S-3120014539(祖先前缀) → 两行全被剪掉 → 不参与判重
        RowKeyUniquenessService.LineItemComps li = new RowKeyUniquenessService.LineItemComps(
            "LI-P", "产品P", "P", "[\"S-3120014539\"]",
            List.of(new RowKeyUniquenessService.CompRows("c3", snap, "[]", null)));
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT, List.of(li));
        assertTrue(r.isEmpty(), "剪枝墓碑命中的节点(含子孙)不应参与判重");
    }

    /** 场景⑥（非树零回归）：非树页签(无 __nodeId)撞键，即便传了墓碑字段(其他组件的)也仍需拦截。 */
    @Test
    void nonTreeTab_duplicateStillConflict_zeroRegression() {
        String snap = """
          [ { "driverRow": { "child_no": "P1" } },
            { "driverRow": { "child_no": "P1" } } ]""";
        String rd = """
          [ { "material": "Cu" }, { "material": "Cu" } ]""";
        // deletedRowKeysJson 非空但 fp 对不上任何行 → 不影响，非树行为逐字节不变，仍应拦截撞键
        List<RowKeyConflictDTO> r = svc.collectConflicts(STRUCT,
            List.of(item("产品A", new RowKeyUniquenessService.CompRows("c1", snap, rd, "[]"))));
        assertEquals(1, r.size(), "非树页签撞键必须仍然拦截，逐字节不变");
        assertEquals("P1||Cu", r.get(0).rowKey());
    }
}
