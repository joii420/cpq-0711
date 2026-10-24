package com.cpq.builder.compiler;

import com.cpq.semanticgraph.entity.SemanticEdge;
import com.cpq.semanticgraph.entity.SemanticEdgeKey;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.entity.SemanticNodeColumn;
import com.cpq.semanticgraph.service.SemanticGraphLoader;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** task-261022：在测试库实际应用 Flyway 后，核对语义图、字段树与编译产物。 */
@QuarkusTest
class SelfProcessNameMigrationTest {

    @Inject SemanticGraphLoader graphLoader;
    @Inject FieldTreeBuilder fieldTreeBuilder;
    @Inject SemanticCompiler semanticCompiler;

    @Test
    void migratedGraphExposesAndCompilesProcessName() {
        SemanticGraphSnapshot graph = graphLoader.get();
        SemanticNode main = graph.nodeByKeyDialect.get("SELF_PROCESS_FEE|QUOTE");
        SemanticNode lookup = graph.nodeByKeyDialect.get("PROCESS_NAME_LK|QUOTE");
        assertNotNull(main, "缺少报价侧 SELF_PROCESS_FEE 节点");
        assertNotNull(lookup, "V452 未登记 PROCESS_NAME_LK 节点");
        assertEquals("process_master", lookup.physicalTable);

        List<SemanticNodeColumn> lookupColumns = graph.columnsOf(lookup.id);
        assertEquals(1, lookupColumns.size(), "工序查名节点必须保持瘦节点，避免字段面板冒出重复工序编号");
        assertEquals("process_name", lookupColumns.get(0).dbColumn);
        assertEquals("工序名称", lookupColumns.get(0).displayName);

        SemanticEdge lookupEdge = graph.edgesFrom(main.id).stream()
                .filter(edge -> "LOOKUP".equals(edge.edgeKind) && lookup.id.equals(edge.toNodeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("V452 未登记自制加工费到工序表的 LOOKUP 边"));
        List<SemanticEdgeKey> edgeKeys = graph.keysOf(lookupEdge.id);
        assertEquals(1, edgeKeys.size());
        assertEquals("operation_no", edgeKeys.get(0).leftColumn);
        assertEquals("process_no", edgeKeys.get(0).rightColumn);
        assertFalse(lookupEdge.fallbackToJoinKey, "查不到工序名称时应为空，不应回退显示工序编号");

        FieldTreeBuilder.FieldTreeResponse fieldTree = fieldTreeBuilder.build(
                graph, CompileDialect.QUOTE, "费用类", "SELF_PROCESS_FEE", null);
        FieldTreeBuilder.Group mainGroup = fieldTree.groups.stream()
                .filter(group -> "SELF_PROCESS_FEE".equals(group.groupKey))
                .findFirst()
                .orElseThrow();
        List<String> names = mainGroup.fields.stream().map(field -> field.displayName).toList();
        int operationNo = names.indexOf("工序编号");
        assertTrue(operationNo >= 0, "字段树缺少工序编号：" + names);
        assertTrue(operationNo + 1 < names.size(), "工序编号后没有字段：" + names);
        assertEquals("工序名称", names.get(operationNo + 1), "工序名称必须紧跟工序编号：" + names);

        BuilderConfig config = new BuilderConfig();
        config.tabType = "费用类";
        config.variantKey = "SELF_PROCESS_FEE";
        config.dialect = "QUOTE";
        config.columns = new ArrayList<>(List.of(
                new BuilderConfig.ColumnConfig("SELF_PROCESS_FEE", "operation_no", "工序编号"),
                new BuilderConfig.ColumnConfig("PROCESS_NAME_LK", "process_name", "工序名称")));
        CompileResult result = semanticCompiler.compile(graph, config, CompileDialect.QUOTE);
        assertTrue(result.sql.contains("LEFT JOIN process_master"), result.sql);
        assertTrue(result.sql.contains(".process_no = dqspf.operation_no"), result.sql);
        assertTrue(result.sql.contains(".process_name AS \"_工序_工序名称\""), result.sql);
    }
}
