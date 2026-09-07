package com.cpq.builder.compiler;

import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;

import java.util.*;

/**
 * 开发自测夹具（task-260819 B-48）：产出一份**真实编译器输出**的核价侧 CompileResult，
 * 供 {@code com.cpq.builder.service} 的预览自测使用。
 *
 * <p>放在本包是因为 {@code SemanticCompiler.catalog} 是包级可见——跨包测试注不进桩。
 * 🚫 不要改成手写 SQL 字符串：那样测的就不再是"编译端产出什么、预览端能不能消化"，
 * 而是"我自己写的字符串能不能被我自己处理"，等于空验证。
 */
public final class V9PreviewFixture {

    private V9PreviewFixture() {}

    /** COST_BASIC「主件」，锚点是 v_<主表>_all 全版本视图 ⇒ 产物必含 :versionFilter + :total_material_no。 */
    public static CompileResult costBasicWithVersionFilter() {
        SemanticNode n = new SemanticNode();
        n.id = UUID.randomUUID(); n.nodeKey = "M"; n.displayName = "核价主件"; n.shortName = "主件";
        n.nodeKind = "SHEET"; n.physicalTable = "v_ds_cost_basic_material_all";
        n.dialect = "COST_BASIC"; n.anchorExpr = "vdcbma.production_no"; n.grainColumns = new String[0];

        SemanticNodeColumn c = new SemanticNodeColumn();
        c.id = UUID.randomUUID(); c.nodeId = n.id; c.dbColumn = "part_name";
        c.displayName = "品名"; c.dataType = "TEXT";

        SemanticTabView v = new SemanticTabView();
        v.id = UUID.randomUUID(); v.tabType = "主件"; v.variantKey = ""; v.dialect = "COST_BASIC";
        v.anchorNodeId = n.id; v.switches = new String[0];

        SemanticCompiler sc = new SemanticCompiler();
        sc.catalog = new PhysicalColumnCatalog() {
            @Override public Map<String, Set<String>> columnsOf(Collection<String> t) {
                return Map.of("v_ds_cost_basic_material_all", new LinkedHashSet<>(
                        List.of("production_no", "part_name", "version_no", "is_current")));
            }
        };
        BuilderConfig cfg = new BuilderConfig();
        cfg.tabType = "主件"; cfg.variantKey = ""; cfg.dialect = "COST_BASIC";
        cfg.columns = new ArrayList<>(List.of(new BuilderConfig.ColumnConfig("M", "part_name", null)));
        return sc.compile(new SemanticGraphSnapshot(1, List.of(n), List.of(c),
                List.of(), List.of(), List.of(v), List.of(), List.of()), cfg, CompileDialect.COST_BASIC);
    }
}
