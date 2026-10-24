package com.cpq.builder.compiler;

import com.cpq.semanticgraph.entity.SemanticEdge;
import com.cpq.semanticgraph.entity.SemanticEdgeKey;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.entity.SemanticNodeColumn;
import com.cpq.semanticgraph.entity.SemanticTabView;
import com.cpq.semanticgraph.entity.SemanticTabViewNode;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** task-261022：自制加工费的工序名称字段与关联查询自检（纯单元测试，不访问数据库）。 */
class SelfProcessNameLookupTest {

    @Test
    void lookupFieldsFollowTheirOwnJoinKeys() {
        SemanticGraphSnapshot graph = graph();

        FieldTreeBuilder.FieldTreeResponse response = new FieldTreeBuilder().build(
                graph, CompileDialect.QUOTE, "费用类", "SELF_PROCESS_FEE", null);

        List<String> fieldNames = response.groups.get(0).fields.stream()
                .map(field -> field.displayName)
                .toList();
        assertEquals(List.of(
                "销售料号",
                "投入料号", "材料名",
                "工序项次",
                "工序编号", "工序名称",
                "值"), fieldNames);

        FieldTreeBuilder.Field processName = response.groups.get(0).fields.stream()
                .filter(field -> "工序名称".equals(field.displayName))
                .findFirst()
                .orElseThrow();
        assertEquals("PROCESS_NAME_LK", processName.sourceNodeKey);
        assertEquals("process_name", processName.sourceColumn);
        assertEquals("_工序_工序名称", processName.viewColumn);
        assertEquals("工序（查名）", processName.lookupLib);
    }

    @Test
    void selectingProcessNameGeneratesProcessMasterLeftJoin() {
        SemanticCompiler compiler = new SemanticCompiler();
        compiler.catalog = new PhysicalColumnCatalog() {
            @Override
            public Map<String, Set<String>> columnsOf(java.util.Collection<String> tables) {
                return Map.of(
                        "ds_quote_self_process_fee", new LinkedHashSet<>(List.of(
                                "material_no", "input_material_no", "operation_item_seq", "operation_no", "value")),
                        "ds_quote_material", new LinkedHashSet<>(List.of("material_no", "material_name")),
                        "process_master", new LinkedHashSet<>(List.of("process_no", "process_name")));
            }
        };

        BuilderConfig config = new BuilderConfig();
        config.tabType = "费用类";
        config.variantKey = "SELF_PROCESS_FEE";
        config.dialect = "QUOTE";
        config.columns = new ArrayList<>(List.of(
                new BuilderConfig.ColumnConfig("SELF_PROCESS_FEE", "operation_no", "工序编号"),
                new BuilderConfig.ColumnConfig("PROCESS_NAME_LK", "process_name", "工序名称")));

        CompileResult result = compiler.compile(graph(), config, CompileDialect.QUOTE);

        assertTrue(result.sql.contains("LEFT JOIN process_master"), result.sql);
        assertTrue(result.sql.contains(".process_no = dqspf.operation_no"), result.sql);
        assertTrue(result.sql.contains(".process_name AS \"_工序_工序名称\""), result.sql);
        assertFalse(result.sql.contains("COALESCE("), "查不到工序时应返回空，不回退成工序编号：\n" + result.sql);
    }

    private static SemanticGraphSnapshot graph() {
        SemanticNode main = node("SELF_PROCESS_FEE", "自制加工费", "自制加工费",
                "SHEET", "ds_quote_self_process_fee", "dqspf.material_no");
        SemanticNode material = node("MAT_NAME_LK", "物料（查名）", "物料",
                "LOOKUP", "ds_quote_material", null);
        SemanticNode process = node("PROCESS_NAME_LK", "工序（查名）", "工序",
                "LOOKUP", "process_master", null);

        List<SemanticNodeColumn> columns = List.of(
                column(main, "material_no", "销售料号", true),
                column(main, "input_material_no", "投入料号", true, "PART_NO"),
                column(main, "operation_item_seq", "工序项次", false),
                column(main, "operation_no", "工序编号", true),
                column(main, "value", "值", false),
                column(material, "material_name", "材料名", false, "PART_NAME"),
                column(process, "process_name", "工序名称", false));

        SemanticEdge materialEdge = edge(main, material);
        SemanticEdge processEdge = edge(main, process);
        SemanticEdgeKey materialKey = key(materialEdge, "input_material_no", "material_no");
        SemanticEdgeKey processKey = key(processEdge, "operation_no", "process_no");

        SemanticTabView view = new SemanticTabView();
        view.id = UUID.randomUUID();
        view.tabType = "费用类";
        view.variantKey = "SELF_PROCESS_FEE";
        view.variantLabel = "自制加工费";
        view.anchorNodeId = main.id;
        view.dialect = "QUOTE";
        view.switches = new String[0];

        SemanticTabViewNode mainViewNode = new SemanticTabViewNode();
        mainViewNode.id = UUID.randomUUID();
        mainViewNode.viewId = view.id;
        mainViewNode.nodeId = main.id;
        mainViewNode.role = "MAIN";
        mainViewNode.addDims = new String[0];

        return new SemanticGraphSnapshot(1,
                List.of(main, material, process), columns,
                List.of(materialEdge, processEdge), List.of(materialKey, processKey),
                List.of(view), List.of(mainViewNode), List.of());
    }

    private static SemanticNode node(String key, String display, String shortName,
                                     String kind, String table, String anchorExpr) {
        SemanticNode node = new SemanticNode();
        node.id = UUID.randomUUID();
        node.nodeKey = key;
        node.displayName = display;
        node.shortName = shortName;
        node.nodeKind = kind;
        node.physicalTable = table;
        node.scope = "NONE";
        node.anchorExpr = anchorExpr;
        node.grainColumns = new String[0];
        node.dialect = "QUOTE";
        return node;
    }

    private static SemanticNodeColumn column(SemanticNode node, String dbColumn, String displayName,
                                             boolean code, String... roles) {
        SemanticNodeColumn column = new SemanticNodeColumn();
        column.id = UUID.randomUUID();
        column.nodeId = node.id;
        column.dbColumn = dbColumn;
        column.displayName = displayName;
        column.dataType = "TEXT";
        column.isCode = code;
        column.roles = roles;
        return column;
    }

    private static SemanticEdge edge(SemanticNode source, SemanticNode target) {
        SemanticEdge edge = new SemanticEdge();
        edge.id = UUID.randomUUID();
        edge.fromNodeId = source.id;
        edge.toNodeId = target.id;
        edge.edgeKind = "LOOKUP";
        edge.cardinality = "MANY_TO_ONE";
        edge.assertStatus = "NA";
        return edge;
    }

    private static SemanticEdgeKey key(SemanticEdge edge, String leftColumn, String rightColumn) {
        SemanticEdgeKey key = new SemanticEdgeKey();
        key.id = UUID.randomUUID();
        key.edgeId = edge.id;
        key.leftColumn = leftColumn;
        key.rightColumn = rightColumn;
        key.seq = 0;
        return key;
    }
}
