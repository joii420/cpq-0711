package com.cpq.builder.compiler;

import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;

import java.util.*;

/**
 * B-52 接线守卫用的**带桥**语义图夹具。放在 compiler 包是因为
 * {@code SemanticCompiler.catalog} / {@code PhysicalColumnCatalog} 的桩需要同包可见。
 */
public final class B52BridgeFixture {

    private static final SemanticNode MAIN = new SemanticNode();
    private static final SemanticNode BRIDGE = new SemanticNode();
    private static final SemanticTabView VIEW = new SemanticTabView();
    private static final SemanticEdge EDGE = new SemanticEdge();
    private static final SemanticEdgeKey KEY = new SemanticEdgeKey();
    private static final List<SemanticNodeColumn> COLS = new ArrayList<>();

    static {
        MAIN.id = UUID.randomUUID(); MAIN.nodeKey = "ANCHOR"; MAIN.displayName = "核价主件";
        MAIN.shortName = "主件"; MAIN.nodeKind = "SHEET"; MAIN.physicalTable = "ds_cost_basic_material";
        MAIN.dialect = "COST_BASIC"; MAIN.anchorExpr = "dcbm.production_no"; MAIN.grainColumns = new String[0];

        BRIDGE.id = UUID.randomUUID(); BRIDGE.nodeKey = "QUOTE_MATERIAL_BRIDGE"; BRIDGE.displayName = "料号桥";
        BRIDGE.shortName = "料号桥"; BRIDGE.nodeKind = "LOOKUP"; BRIDGE.physicalTable = "ds_quote_material";
        BRIDGE.dialect = "COST_BASIC"; BRIDGE.grainColumns = new String[0];

        COLS.add(col(MAIN, "production_no"));
        COLS.add(col(MAIN, "material_name"));

        EDGE.id = UUID.randomUUID(); EDGE.fromNodeId = MAIN.id; EDGE.toNodeId = BRIDGE.id;
        EDGE.edgeKind = "NARROW"; EDGE.cardinality = "MANY_TO_MANY";
        KEY.id = UUID.randomUUID(); KEY.edgeId = EDGE.id;
        KEY.leftColumn = "production_no"; KEY.rightColumn = "production_no"; KEY.seq = 0;

        VIEW.id = UUID.randomUUID(); VIEW.tabType = "主件"; VIEW.variantKey = ""; VIEW.dialect = "COST_BASIC";
        VIEW.anchorNodeId = MAIN.id; VIEW.switches = new String[0];
    }

    public static SemanticGraphSnapshot graph() {
        return new SemanticGraphSnapshot(1, List.of(MAIN, BRIDGE), COLS,
                List.of(EDGE), List.of(KEY), List.of(VIEW), List.of(), List.of());
    }

    @jakarta.enterprise.inject.Vetoed
    static final class Cat extends PhysicalColumnCatalog {
        @Override public Map<String, Set<String>> columnsOf(Collection<String> t) {
            Map<String, Set<String>> m = new HashMap<>();
            m.put("ds_cost_basic_material", new LinkedHashSet<>(List.of("production_no", "material_name")));
            m.put("ds_quote_material", new LinkedHashSet<>(List.of("material_no", "production_no")));
            return m;
        }
    }

    /** 返回已注入桩 catalog 的编译器（{@code catalog} 是包级可见，跨包注不进去）。 */
    public static SemanticCompiler compiler() {
        SemanticCompiler sc = new SemanticCompiler();
        sc.catalog = new Cat();
        return sc;
    }

    public static List<BuilderConfig.ColumnConfig> columns() {
        return new ArrayList<>(List.of(new BuilderConfig.ColumnConfig("ANCHOR", "material_name", null)));
    }

    private static SemanticNodeColumn col(SemanticNode n, String db) {
        SemanticNodeColumn c = new SemanticNodeColumn();
        c.id = UUID.randomUUID(); c.nodeId = n.id; c.dbColumn = db; c.displayName = db; c.dataType = "TEXT";
        return c;
    }
}
