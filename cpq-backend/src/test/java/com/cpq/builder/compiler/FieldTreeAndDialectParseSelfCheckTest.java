package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260819 B-46 开发自测：字段树按方言取数（AC-116 / AC-110）+ 未知方言显式拒绝。
 *
 * <p>⚠️ 同前两个自测：开发自测不是正式验收用例；纯 JUnit 零查库
 * （{@code application-test.properties} 默认库就是共享开发库 {@code cpq_db_0724}）。
 */
class FieldTreeAndDialectParseSelfCheckTest {

    // ---------------- 建图：同一个「主件」页签，报价 / 基础核价两套并列 ----------------

    private static SemanticNode node(String key, String shortName, String table, String dialect, String anchorExpr) {
        SemanticNode n = new SemanticNode();
        n.id = UUID.randomUUID();
        n.nodeKey = key;
        n.displayName = "主件(" + dialect + ")";
        n.shortName = shortName;
        n.nodeKind = "SHEET";
        n.physicalTable = table;
        n.dialect = dialect;
        n.anchorExpr = anchorExpr;
        n.grainColumns = new String[0];
        return n;
    }

    private static SemanticNodeColumn col(SemanticNode n, String db, String display) {
        SemanticNodeColumn c = new SemanticNodeColumn();
        c.id = UUID.randomUUID();
        c.nodeId = n.id;
        c.dbColumn = db;
        c.displayName = display;
        c.dataType = "TEXT";
        return c;
    }

    private static SemanticTabView view(String tabType, String variantKey, String label,
                                        String dialect, SemanticNode anchor) {
        SemanticTabView t = new SemanticTabView();
        t.id = UUID.randomUUID();
        t.tabType = tabType;
        t.variantKey = variantKey;
        t.variantLabel = label;
        t.dialect = dialect;
        t.anchorNodeId = anchor.id;
        t.switches = new String[0];
        return t;
    }

    private static SemanticTabViewNode tvn(SemanticTabView v, SemanticNode n) {
        SemanticTabViewNode x = new SemanticTabViewNode();
        x.id = UUID.randomUUID();
        x.viewId = v.id;
        x.nodeId = n.id;
        x.role = "MAIN";
        x.addDims = new String[0];
        return x;
    }

    /** 报价那一行**排在前面** —— 漏 dialect 过滤时 findFirst() 正好会命中它。 */
    private static SemanticGraphSnapshot graph() {
        SemanticNode q = node("MAIN", "主件", "ds_quote_material", "QUOTE", "dqm.material_no");
        SemanticNode b = node("MAIN", "主件", "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        SemanticTabView qv = view("主件", "", null, "QUOTE", q);
        SemanticTabView bv = view("主件", "", null, "COST_BASIC", b);
        // 费用类变体：两套各一个，用来验 variants 也按方言过滤
        SemanticTabView qf = view("费用类", "Q_FEE", "报价来料费", "QUOTE", q);
        SemanticTabView bf = view("费用类", "B_FEE", "核价来料费", "COST_BASIC", b);
        return new SemanticGraphSnapshot(1,
                List.of(q, b),
                List.of(col(q, "part_name", "品名"), col(b, "part_name", "品名")),
                List.of(), List.of(),
                List.of(qv, bv, qf, bf),
                List.of(tvn(qv, q), tvn(bv, b), tvn(qf, q), tvn(bf, b)),
                List.of());
    }

    // =====================================================================
    // AC-116：选定数据集后，另两套的表一张都不出现（不是置灰，是不出现）
    // =====================================================================
    @Test
    void fieldTreeShowsOnlySelectedDatasetTables() {
        FieldTreeBuilder ftb = new FieldTreeBuilder();

        FieldTreeBuilder.FieldTreeResponse basic = ftb.build(graph(), CompileDialect.COST_BASIC, "主件", "", null);
        List<String> basicGroups = basic.groups.stream().map(g -> g.groupName).toList();
        List<String> basicDatasets = basic.groups.stream().map(g -> g.dialect).toList();
        System.out.println("---- COST_BASIC 字段面板 ----\n组=" + basicGroups
                + "\ndataset=" + basicDatasets
                + "\nviewColumn=" + basic.groups.get(0).fields.stream().map(f -> f.viewColumn).toList());

        assertEquals(1, basic.groups.size(), "只应出现本数据集的一个组：" + basicGroups);
        assertEquals("主件(COST_BASIC)", basic.groups.get(0).groupName);
        assertFalse(basicGroups.contains("主件(QUOTE)"), "报价侧的表必须一张都不出现：" + basicGroups);
        // 权威标注（AC-116 前端消费侧）
        assertEquals("COST_BASIC", basic.groups.get(0).dialect);
        // AC-110：核价侧视图列名是裸英文 dbColumn
        assertEquals("part_name", basic.groups.get(0).fields.get(0).viewColumn);

        FieldTreeBuilder.FieldTreeResponse quote = ftb.build(graph(), CompileDialect.QUOTE, "主件", "", null);
        assertEquals("主件(QUOTE)", quote.groups.get(0).groupName);
        assertEquals("QUOTE", quote.groups.get(0).dialect);
        // AC-110：报价侧是 _<短名>_<显示名>，且必须与核价侧不同（"不要顺手统一"）
        assertEquals("_主件_品名", quote.groups.get(0).fields.get(0).viewColumn);
        assertNotEquals(quote.groups.get(0).fields.get(0).viewColumn,
                basic.groups.get(0).fields.get(0).viewColumn, "AC-110：两侧别名规则必须保持不统一");
    }

    /** variants 下拉同样按方言过滤 —— 否则用户能选中另一套的变体，编译期才炸。 */
    @Test
    void variantsAreDialectScoped() {
        FieldTreeBuilder ftb = new FieldTreeBuilder();
        FieldTreeBuilder.FieldTreeResponse r = ftb.build(graph(), CompileDialect.COST_BASIC, "费用类", "B_FEE", null);
        List<String> labels = r.variants.stream().map(m -> m.get("label")).toList();
        System.out.println("---- COST_BASIC 费用类 variants ----\n" + labels);
        assertEquals(List.of("核价来料费"), labels, "报价侧变体不得出现在核价侧下拉里");
    }

    /**
     * 反证：本数据集**有**种子、只是没有你要的这个页签类型 → 仍然 404，且错误信息带数据集名。
     * （与下面"整个数据集没种子"的兜底分支必须可区分）
     */
    @Test
    void missingTabTypeWithinSeededDialectIsRejected() {
        FieldTreeBuilder ftb = new FieldTreeBuilder();
        BuilderApiException ex = assertThrows(BuilderApiException.class,
                () -> ftb.build(graph(), CompileDialect.COST_BASIC, "材质元素", "", null));
        assertEquals("COMPILE_TABVIEW_NOT_FOUND", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("COST_BASIC"), ex.getMessage());
    }

    /** AC-115③：availableTabTypes 按方言收窄，且保持 ALL_TAB_TYPES 的展示顺序。 */
    @Test
    void availableTabTypesAreNarrowedByDialect() {
        FieldTreeBuilder ftb = new FieldTreeBuilder();
        FieldTreeBuilder.FieldTreeResponse r = ftb.build(graph(), CompileDialect.COST_BASIC, "主件", "", null);
        System.out.println("---- COST_BASIC availableTabTypes ----\n" + r.availableTabTypes
                + " fallback=" + r.tabTypesFallback);
        assertEquals(List.of("主件", "费用类"), r.availableTabTypes, "只应出现本数据集真有的页签类型");
        assertFalse(r.tabTypesFallback, "图里有种子时不得标成兜底");
    }

    /**
     * 主线指定的兜底：该方言在图里一个页签视图都没有（种子未落库/被清空）时——
     * 返回全量 6 值 + {@code tabTypesFallback=true}，**不抛错**（不把用户锁死在全灰界面），
     * 且"图是空的"与"图正常"长得不一样。
     */
    @Test
    void emptyDialectFallsBackToAllSixWithFlag() {
        FieldTreeBuilder ftb = new FieldTreeBuilder();
        // graph() 里 COST_DETAIL 一行页签视图都没有
        FieldTreeBuilder.FieldTreeResponse r = ftb.build(graph(), CompileDialect.COST_DETAIL, "主件", "", null);
        System.out.println("---- COST_DETAIL(无种子) 兜底 ----\n" + r.availableTabTypes
                + " fallback=" + r.tabTypesFallback + " groups=" + r.groups.size());
        assertTrue(r.tabTypesFallback, "必须带兜底标记，否则与正常返回无法区分");
        assertEquals(6, r.availableTabTypes.size(), r.availableTabTypes.toString());
        assertTrue(r.availableTabTypes.contains("BOM 树"), r.availableTabTypes.toString());
        assertTrue(r.groups.isEmpty(), "没有种子就没有分组");
    }

    // =====================================================================
    // B-46 任务二：未知方言显式 400（主线指定的反证）
    // =====================================================================
    @Test
    void unknownDialectIsRejected_butDefaultStaysQuote() {
        // 缺省仍是 QUOTE
        assertEquals(CompileDialect.QUOTE, CompileDialect.parse(null));
        assertEquals(CompileDialect.QUOTE, CompileDialect.parse(""));
        assertEquals(CompileDialect.QUOTE, CompileDialect.parse("   "));
        // 三个合法值（含大小写/空白容错）
        assertEquals(CompileDialect.QUOTE, CompileDialect.parse("QUOTE"));
        assertEquals(CompileDialect.COST_BASIC, CompileDialect.parse("COST_BASIC"));
        assertEquals(CompileDialect.COST_BASIC, CompileDialect.parse(" cost_basic "));
        assertEquals(CompileDialect.COST_DETAIL, CompileDialect.parse("COST_DETAIL"));

        // 🚨 主线指定的反证：旧值 COSTING 必须 400，且文案要指路
        BuilderApiException legacy = assertThrows(BuilderApiException.class,
                () -> CompileDialect.parse("COSTING"));
        System.out.println("---- parse(\"COSTING\") ----\n" + legacy.getMessage());
        assertEquals(400, legacy.getCode());
        assertEquals("BUILDER_DIALECT_UNKNOWN", legacy.getErrorCode());
        assertTrue(legacy.getMessage().contains("COST_BASIC"), legacy.getMessage());
        assertEquals("COSTING", legacy.getExtra().get("received"));

        // 拼错也必须 400，不许静默按 QUOTE 走
        BuilderApiException typo = assertThrows(BuilderApiException.class,
                () -> CompileDialect.parse("COST_BASICC"));
        assertEquals(400, typo.getCode());
    }

    /** 反证的另一半：传 COST_BASIC 必须正常编译出核价侧产物（不是"一律拒绝"）。 */
    @Test
    void costBasicStillCompilesNormally() {
        SemanticNode n = node("MAIN", "主件", "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        SemanticNodeColumn c = col(n, "part_name", "品名");
        SemanticTabView v = view("主件", "", null, "COST_BASIC", n);

        SemanticCompiler sc = new SemanticCompiler();
        sc.catalog = new PhysicalColumnCatalog() {
            @Override public Map<String, Set<String>> columnsOf(Collection<String> t) {
                return Map.of("ds_cost_basic_material",
                        new LinkedHashSet<>(List.of("production_no", "part_name", "version_no")));
            }
        };
        BuilderConfig cfg = new BuilderConfig();
        cfg.tabType = "主件"; cfg.variantKey = ""; cfg.dialect = "COST_BASIC";
        cfg.columns = new ArrayList<>(List.of(new BuilderConfig.ColumnConfig("MAIN", "part_name", null)));

        CompileResult r = sc.compile(new SemanticGraphSnapshot(1, List.of(n), List.of(c),
                List.of(), List.of(), List.of(v), List.of(), List.of()),
                cfg, CompileDialect.parse(cfg.dialect));
        System.out.println("---- parse(\"COST_BASIC\") 后正常编译 ----\n" + r.sql);
        assertTrue(r.sql.contains("dcbm.production_no = ANY(:total_material_no)"), r.sql);
        assertTrue(r.declaredColumns.contains("part_name"), r.declaredColumns.toString());
    }
}
