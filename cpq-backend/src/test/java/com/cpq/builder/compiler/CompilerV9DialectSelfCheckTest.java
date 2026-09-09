package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260819 B-41 开发自测（AC-107 / AC-108 / AC-109② / AC-110）。
 *
 * <p>⚠️ <b>定位</b>：这是后端工程师的开发自测，不是正式验收用例（正式用例归 {@code cpq-tester}，
 * 主线可直接替换/删除本文件）。之所以写成纯 JUnit 而不是 {@code @QuarkusTest}：
 * {@code application-test.properties} 的默认库就是共享开发库 {@code cpq_db_0724}，
 * 起 Quarkus 会连上去；本测试<b>零查库</b>——{@link PhysicalColumnCatalog} 用桩替掉，
 * 图用手搭的内存 {@link SemanticGraphSnapshot}。
 *
 * <p>为什么必须用手搭图：v9 的 {@code ds_*} 语义图种子（B-42，后端 #2）尚未落库，
 * 共享库里现在只有 V6 的 {@code dialect='QUOTE'} 声明——用真图根本编译不出 COST_* 产物。
 */
class CompilerV9DialectSelfCheckTest {

    // ---------- 桩：把 information_schema 换成写死的列集合 ----------
    /**
     * 🚫 {@code @Vetoed} 不是可选的：{@code jakarta.enterprise.context.ApplicationScoped}
     * 带 {@code @Inherited}，所以任何**具名**子类都会自动继承 bean 定义注解、成为第二个
     * {@code @Default} bean ⇒ 全项目任何 {@code @QuarkusTest} 启动时报
     * {@code Ambiguous dependencies for type ...}，而**报错点在别人的测试里**，别人根本不知道
     * 是本文件引起的。本类只是给纯 JUnit 直接 {@code new} 用的桩，从不被注入 ⇒ 用 {@code @Vetoed}
     * 明确逐出 bean 发现，比 {@code @Alternative @Priority}（仍然是个 bean）更贴合意图。
     * ⚠️ {@code @Vetoed} 不是 {@code @Inherited}，每个具名桩类都要各自标一次。
     */
    @jakarta.enterprise.inject.Vetoed
    static final class StubCatalog extends PhysicalColumnCatalog {
        final Map<String, Set<String>> byTable = new HashMap<>();
        @Override
        public Map<String, Set<String>> columnsOf(Collection<String> tables) {
            Map<String, Set<String>> out = new HashMap<>();
            for (String t : tables) if (byTable.containsKey(t)) out.put(t, byTable.get(t));
            return out;
        }
    }

    // ---------- 建图小工具 ----------
    private static SemanticNode sheet(String key, String display, String shortName,
                                      String table, String dialect, String anchorExpr) {
        SemanticNode n = new SemanticNode();
        n.id = UUID.randomUUID();
        n.nodeKey = key;
        n.displayName = display;
        n.shortName = shortName;
        n.nodeKind = "SHEET";
        n.physicalTable = table;
        n.dialect = dialect;
        n.anchorExpr = anchorExpr;
        n.grainColumns = new String[0];
        return n;
    }

    private static SemanticNodeColumn col(SemanticNode n, String db, String display, String type) {
        SemanticNodeColumn c = new SemanticNodeColumn();
        c.id = UUID.randomUUID();
        c.nodeId = n.id;
        c.dbColumn = db;
        c.displayName = display;
        c.dataType = type;
        return c;
    }

    private static SemanticTabView tabView(String tabType, String dialect, SemanticNode anchor) {
        SemanticTabView t = new SemanticTabView();
        t.id = UUID.randomUUID();
        t.tabType = tabType;
        t.variantKey = "";
        t.dialect = dialect;
        t.anchorNodeId = anchor.id;
        t.switches = new String[0];
        return t;
    }

    private static SemanticGraphSnapshot snap(List<SemanticNode> nodes,
                                              List<SemanticNodeColumn> cols,
                                              List<SemanticTabView> views) {
        return new SemanticGraphSnapshot(1, nodes, cols, List.of(), List.of(), views, List.of(), List.of());
    }

    private static BuilderConfig cfg(String tabType, String dialect, String nodeKey, String... dbCols) {
        BuilderConfig c = new BuilderConfig();
        c.tabType = tabType;
        c.variantKey = "";
        c.dialect = dialect;
        c.columns = new ArrayList<>();
        for (String dc : dbCols) c.columns.add(new BuilderConfig.ColumnConfig(nodeKey, dc, null));
        return c;
    }

    private static SemanticCompiler compilerWith(StubCatalog stub) {
        SemanticCompiler sc = new SemanticCompiler();
        sc.catalog = stub;
        return sc;
    }

    // =====================================================================
    // AC-107 / AC-108：报价方言 —— 轴列 material_no，无 system_type / customer_no / 版本谓词
    // =====================================================================
    @Test
    void quoteDialect_axisIsMaterialNo_andNoLegacyV6Predicates() {
        SemanticNode n = sheet("DS_QUOTE_MATERIAL", "报价主件", "主件",
                "ds_quote_material", "QUOTE", "dqm.material_no");
        SemanticNodeColumn c1 = col(n, "material_no", "销售料号", "TEXT");
        SemanticNodeColumn c2 = col(n, "part_name", "品名", "TEXT");

        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_quote_material",
                new LinkedHashSet<>(List.of("material_no", "part_name", "version_no", "row_fingerprint")));

        CompileResult r = compilerWith(stub).compile(
                snap(List.of(n), List.of(c1, c2), List.of(tabView("主件", "QUOTE", n))),
                cfg("主件", "QUOTE", "DS_QUOTE_MATERIAL", "part_name"),
                CompileDialect.QUOTE);

        System.out.println("---- QUOTE ----\n" + r.sql);
        assertTrue(r.sql.contains("dqm.material_no = ANY(:total_material_no)"), r.sql);
        assertFalse(r.sql.contains("system_type"), "AC-107：不许出现 system_type");
        assertFalse(r.sql.contains("customer_no"), "AC-107：不许出现 customer_no");
        assertFalse(r.sql.contains("is_current"), "报价侧节点直接指主表，没有 is_current");
        assertFalse(r.sql.contains(":versionFilter"), "AC-107：报价侧不发版本谓词");
        assertFalse(r.declaredColumns.contains("view_version"), "AC-109：报价侧不输出 view_version");
        // AC-110：报价侧别名 = _<短名>_<显示名>
        assertTrue(r.declaredColumns.contains("_主件_品名"), r.declaredColumns.toString());
        // 轴收窄只出现一次（原 QUOTE 分支重复发一遍的回归）
        assertEquals(1, countOf(r.sql, "= ANY(:total_material_no)"), "轴收窄谓词必须只出现一次\n" + r.sql);
    }

    // =====================================================================
    // AC-108 / AC-109②③ / AC-110：核价两套 —— 轴列 production_no + versionFilter(::text) + view_version + 裸别名
    // =====================================================================
    @Test
    void costingDialects_axisIsProductionNo_withVersionFilterAndBareAlias() {
        for (CompileDialect d : List.of(CompileDialect.COST_BASIC, CompileDialect.COST_DETAIL)) {
            String table = d == CompileDialect.COST_BASIC
                    ? "v_ds_cost_basic_material_all" : "v_ds_cost_detail_material_all";
            String alias = d == CompileDialect.COST_BASIC ? "vdcbma" : "vdcdma";

            SemanticNode n = sheet("COST_MATERIAL", "核价主件", "主件", table, d.name(), alias + ".production_no");
            SemanticNodeColumn c1 = col(n, "production_no", "生产料号", "TEXT");
            SemanticNodeColumn c2 = col(n, "part_name", "品名", "TEXT");

            StubCatalog stub = new StubCatalog();
            stub.byTable.put(table, new LinkedHashSet<>(
                    List.of("production_no", "part_name", "version_no", "row_fingerprint", "is_current")));

            CompileResult r = compilerWith(stub).compile(
                    snap(List.of(n), List.of(c1, c2), List.of(tabView("主件", d.name(), n))),
                    cfg("主件", d.name(), "COST_MATERIAL", "part_name"),
                    d);

            System.out.println("---- " + d + " ----\n" + r.sql);
            // AC-108
            assertTrue(r.sql.contains(alias + ".production_no = ANY(:total_material_no)"), r.sql);
            assertFalse(r.sql.contains("material_no = ANY"), "核价侧轴列不是销售料号");
            // AC-109②：宏三实参 + ::text（D-85）
            assertTrue(r.sql.contains(":versionFilter(" + alias + ".is_current, "
                    + alias + ".version_no::text, " + alias + ".production_no)"), r.sql);
            // AC-109③：view_version 约定列
            assertTrue(r.declaredColumns.contains("view_version"), r.declaredColumns.toString());
            assertTrue(r.sql.contains(alias + ".version_no::text AS view_version"), r.sql);
            // AC-107：新表没有这两列
            assertFalse(r.sql.contains("system_type"), r.sql);
            assertFalse(r.sql.contains("customer_no"), r.sql);
            // AC-110：核价侧别名 = 裸英文 dbColumn（不是 _主件_品名）
            assertTrue(r.declaredColumns.contains("part_name"), r.declaredColumns.toString());
            assertFalse(r.declaredColumns.contains("_主件_品名"), r.declaredColumns.toString());
        }
    }

    // =====================================================================
    // AC-101 配套：同 (tabType, variantKey) 三方言并列时，必须取本方言那一行
    // （漏 dialect 过滤 = 静默 FROM 另一套数据集的表）
    // =====================================================================
    @Test
    void tabViewLookupIsDialectScoped() {
        SemanticNode q = sheet("MAIN", "报价主件", "主件", "ds_quote_material", "QUOTE", "dqm.material_no");
        SemanticNode b = sheet("MAIN", "基础核价主件", "主件", "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        SemanticNodeColumn qc = col(q, "part_name", "品名", "TEXT");
        SemanticNodeColumn bc = col(b, "part_name", "品名", "TEXT");

        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_quote_material", new LinkedHashSet<>(List.of("material_no", "part_name")));
        stub.byTable.put("ds_cost_basic_material", new LinkedHashSet<>(List.of("production_no", "part_name")));

        // 注意 tabViews 里报价那行排在前面 —— 漏过滤时 findFirst() 就会拿到它
        SemanticGraphSnapshot s = snap(List.of(q, b), List.of(qc, bc),
                List.of(tabView("主件", "QUOTE", q), tabView("主件", "COST_BASIC", b)));

        CompileResult r = compilerWith(stub).compile(
                s, cfg("主件", "COST_BASIC", "MAIN", "part_name"), CompileDialect.COST_BASIC);
        System.out.println("---- dialect scoping ----\n" + r.sql);
        assertTrue(r.sql.contains("FROM ds_cost_basic_material "), r.sql);
        assertFalse(r.sql.contains("ds_quote_material"), "取到了报价侧那一行 = dialect 过滤失效\n" + r.sql);

        // 反证：图里没有 COST_DETAIL 的页签视图 → 必须报错，而不是回落到别的方言
        BuilderApiException ex = assertThrows(BuilderApiException.class, () ->
                compilerWith(stub).compile(s, cfg("主件", "COST_DETAIL", "MAIN", "part_name"),
                        CompileDialect.COST_DETAIL));
        assertEquals("COMPILE_TABVIEW_NOT_FOUND", ex.getErrorCode());
    }

    // =====================================================================
    // 轴列不存在的表（按 scheme_no 建模）：不硬造列引用，也不崩
    // =====================================================================
    @Test
    void tableWithoutAxisColumn_emitsNoAxisPredicate() {
        SemanticNode n = sheet("PLATING", "电镀方案", "电镀",
                "ds_cost_basic_plating_scheme", "COST_BASIC", "dcbps.scheme_no");
        SemanticNodeColumn c1 = col(n, "scheme_no", "方案号", "TEXT");
        SemanticNodeColumn c2 = col(n, "scheme_name", "方案名", "TEXT");

        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_cost_basic_plating_scheme",
                new LinkedHashSet<>(List.of("scheme_no", "scheme_name", "version_no", "is_current")));

        CompileResult r = compilerWith(stub).compile(
                snap(List.of(n), List.of(c1, c2), List.of(tabView("费用类", "COST_BASIC", n))),
                cfg("费用类", "COST_BASIC", "PLATING", "scheme_name"),
                CompileDialect.COST_BASIC);

        System.out.println("---- no-axis table ----\n" + r.sql);
        assertFalse(r.sql.contains(":total_material_no"), "表没有轴列就不该发轴收窄\n" + r.sql);
        assertFalse(r.sql.contains(":versionFilter"), "缺轴列 ⇒ 宏第三实参无处可取，退回裸 is_current");
        assertTrue(r.sql.contains("dcbps.is_current"), "缺轴列时仍必须收到当前版，否则 _history 行漏进来\n" + r.sql);
        assertFalse(r.declaredColumns.contains("view_version"), "没发宏就不该输出 view_version");
    }

    // =====================================================================
    // B-47：核价侧裸别名撞名必须被消歧，且 declaredColumns 无重复
    // =====================================================================
    @Test
    void collidingBareAliasesAreDisambiguated() {
        // 主表与料号桥都有 material_name（后端 #2 实测的真实场景）
        SemanticNode main = sheet("COST_MAIN", "核价主件", "主件",
                "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        SemanticNode bridge = sheet("QUOTE_BRIDGE", "料号桥", "料号桥",
                "ds_quote_material", "COST_BASIC", "dqm.material_no");
        SemanticNodeColumn mPart = col(main, "production_no", "生产料号", "TEXT");
        SemanticNodeColumn mName = col(main, "material_name", "品名", "TEXT");
        SemanticNodeColumn bNo   = col(bridge, "material_no", "销售料号", "TEXT");
        SemanticNodeColumn bName = col(bridge, "material_name", "桥品名", "TEXT");

        SemanticEdge e = new SemanticEdge();
        e.id = UUID.randomUUID(); e.fromNodeId = main.id; e.toNodeId = bridge.id;
        e.edgeKind = "LOOKUP"; e.cardinality = "MANY_TO_ONE";
        SemanticEdgeKey k = new SemanticEdgeKey();
        k.id = UUID.randomUUID(); k.edgeId = e.id;
        k.leftColumn = "production_no"; k.rightColumn = "production_no"; k.seq = 0;

        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_cost_basic_material",
                new LinkedHashSet<>(List.of("production_no", "material_name")));
        stub.byTable.put("ds_quote_material",
                new LinkedHashSet<>(List.of("production_no", "material_no", "material_name")));

        SemanticGraphSnapshot snapshot = new SemanticGraphSnapshot(1,
                List.of(main, bridge), List.of(mPart, mName, bNo, bName),
                List.of(e), List.of(k),
                List.of(tabView("主件", "COST_BASIC", main)), List.of(), List.of());

        BuilderConfig cfg = new BuilderConfig();
        cfg.tabType = "主件"; cfg.variantKey = ""; cfg.dialect = "COST_BASIC";
        cfg.columns = new ArrayList<>(List.of(
                new BuilderConfig.ColumnConfig("COST_MAIN", "production_no", null),
                new BuilderConfig.ColumnConfig("COST_MAIN", "material_name", null),
                new BuilderConfig.ColumnConfig("QUOTE_BRIDGE", "material_no", null),
                new BuilderConfig.ColumnConfig("QUOTE_BRIDGE", "material_name", null)));

        CompileResult r = compilerWith(stub).compile(snapshot, cfg, CompileDialect.COST_BASIC);
        System.out.println("---- 撞名消歧 ----\ndeclaredColumns=" + r.declaredColumns + "\n" + r.sql);

        // 核心断言：输出列名全局唯一（PG 允许重复，下游按 Map 读行会静默丢列）
        assertEquals(new LinkedHashSet<>(r.declaredColumns).size(), r.declaredColumns.size(),
                "declaredColumns 有重复：" + r.declaredColumns);
        // 首次出现的保持裸名（AC-110 既有断言零回归）
        assertTrue(r.declaredColumns.contains("material_name"), r.declaredColumns.toString());
        // 后出现的加节点短名后缀
        assertTrue(r.declaredColumns.contains("material_name_料号桥"), r.declaredColumns.toString());
        // 消歧后的名字必须真的写进 SQL 的 AS，否则 declaredColumns 与实际列名对不上（更隐蔽的失配）
        assertTrue(r.sql.contains("AS \"material_name_料号桥\""), r.sql);
    }

    /** 业务列不许抢走 hf_part_no / view_version 这两个约定列名。 */
    @Test
    void reservedColumnNamesAreProtected() {
        SemanticNode n = sheet("M", "核价主件", "主件",
                "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        SemanticNodeColumn c = col(n, "hf_part_no", "自定义料号列", "TEXT");
        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_cost_basic_material",
                new LinkedHashSet<>(List.of("production_no", "hf_part_no")));

        CompileResult r = compilerWith(stub).compile(
                snap(List.of(n), List.of(c), List.of(tabView("主件", "COST_BASIC", n))),
                cfg("主件", "COST_BASIC", "M", "hf_part_no"),
                CompileDialect.COST_BASIC);
        System.out.println("---- 约定列保护 ----\n" + r.declaredColumns);
        assertEquals(new LinkedHashSet<>(r.declaredColumns).size(), r.declaredColumns.size(),
                r.declaredColumns.toString());
        assertEquals("hf_part_no", r.declaredColumns.get(0), "第 0 列必须是约定的料号列");
        assertTrue(r.declaredColumns.contains("hf_part_no_主件"), r.declaredColumns.toString());
    }

    // =====================================================================
    // B-50：edge_kind='NARROW' 半连接收窄（桥当入参，不当输出列）
    // =====================================================================

    /** 建一张「核价主件 + 料号桥」的图；bridgeEdgeKind 决定桥是 NARROW 还是老的 LOOKUP。 */
    private static Object[] bridgeGraph(String bridgeEdgeKind, boolean attachBridgeAsAux,
                                        boolean bridgeHasMaterialNo) {
        SemanticNode main = sheet("COST_MAIN", "核价主件", "主件",
                "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        SemanticNode bridge = sheet("QUOTE_MATERIAL_BRIDGE", "料号桥", "料号桥",
                "ds_quote_material", "COST_BASIC", null);   // 实测桥的 anchor_expr 就是 NULL
        SemanticNodeColumn mPart = col(main, "production_no", "生产料号", "TEXT");
        SemanticNodeColumn mName = col(main, "part_name", "品名", "TEXT");
        SemanticNodeColumn bSales = col(bridge, "material_no", "销售料号", "TEXT");

        SemanticEdge e = new SemanticEdge();
        e.id = UUID.randomUUID(); e.fromNodeId = main.id; e.toNodeId = bridge.id;
        e.edgeKind = bridgeEdgeKind; e.cardinality = "MANY_TO_MANY";
        SemanticEdgeKey k = new SemanticEdgeKey();
        k.id = UUID.randomUUID(); k.edgeId = e.id;
        k.leftColumn = "production_no"; k.rightColumn = "production_no"; k.seq = 0;

        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_cost_basic_material",
                new LinkedHashSet<>(List.of("production_no", "part_name")));
        stub.byTable.put("ds_quote_material", bridgeHasMaterialNo
                ? new LinkedHashSet<>(List.of("material_no", "production_no"))
                : new LinkedHashSet<>(List.of("production_no")));   // 缺入参列的坏声明

        SemanticTabView v = tabView("主件", "COST_BASIC", main);
        List<SemanticTabViewNode> tvns = new ArrayList<>();
        SemanticTabViewNode mn = new SemanticTabViewNode();
        mn.id = UUID.randomUUID(); mn.viewId = v.id; mn.nodeId = main.id; mn.role = "MAIN";
        mn.addDims = new String[0];
        tvns.add(mn);
        if (attachBridgeAsAux) {
            SemanticTabViewNode bn = new SemanticTabViewNode();
            bn.id = UUID.randomUUID(); bn.viewId = v.id; bn.nodeId = bridge.id; bn.role = "AUX";
            bn.addDims = new String[0];
            tvns.add(bn);
        }
        SemanticGraphSnapshot snapshot = new SemanticGraphSnapshot(1,
                List.of(main, bridge), List.of(mPart, mName, bSales),
                List.of(e), List.of(k), List.of(v), tvns, List.of());
        return new Object[]{snapshot, stub};
    }

    /**
     * 核心：核价方言下**桥整个不发** —— 既没有 FROM 项，也没有 WHERE 半连接；
     * 收窄直接落在方言轴列上。
     */
    @Test
    void narrowEmitsSemiJoinInsteadOfLeftJoin() {
        // task-260909 D-6（方案甲）：核价侧轴统一为生产料号，COST_* 不再发 ds_quote_material 桥。
        // 本断言原属 task-260819 AC-111/AC-112，其前提「轴值是销售料号」已被 D-6 取消。
        //
        // 🔄 原断言是「必须产出 WHERE 半连接」。D-6 之后 :total_material_no 装的就是生产料号，
        //    桥（销售→生产的翻译）失去存在前提 ⇒ 翻面成「一处桥都不许有 + 必须有直接轴谓词」。
        //    🚫 没有退化成"只删不加"：正向那一半由下面的直接轴谓词断言顶上，产物仍然必须收窄。
        Object[] g = bridgeGraph("NARROW", true, true);
        CompileResult r = compilerWith((StubCatalog) g[1]).compile(
                (SemanticGraphSnapshot) g[0], cfg("主件", "COST_BASIC", "COST_MAIN", "part_name"),
                CompileDialect.COST_BASIC);
        System.out.println("---- D-6 停桥后的 COST_BASIC 产物 ----\n" + r.sql);

        assertFalse(r.sql.contains("LEFT JOIN ds_quote_material"), "桥不许出现在 FROM 侧：\n" + r.sql);
        assertFalse(r.sql.contains("JOIN ds_quote_material"), "桥不许产出任何 FROM 项：\n" + r.sql);
        assertFalse(r.sql.contains("ds_quote_material"),
                "D-6：核价产物里**任何位置**都不该再出现 ds_quote_material（半连接也算）：\n" + r.sql);
        assertTrue(r.sql.contains("dcbm.production_no = ANY(:total_material_no)"),
                "D-6：停桥后收窄必须直接落在方言轴列 production_no 上，"
                        + "否则整条 SQL 完全不收窄 = 整表全捞：\n" + r.sql);
        // 桥的列一个都不许进 SELECT（这条与桥发不发无关，原样保留）
        assertFalse(r.declaredColumns.contains("material_no"), r.declaredColumns.toString());
        assertTrue(r.requiredVariables.contains("total_material_no"), r.requiredVariables.toString());
    }

    /**
     * 正确性关键：{@code :total_material_no} 只许被<b>一种</b>语义消费 —— 恰好一处收窄入口。
     *
     * <p>两条谓词共存（桥要销售料号、轴列要生产料号）会让交集恒空 ⇒ 0 行且不报错（B-52 的形态）。
     * D-6 之后桥没了，唯一的那一处就是直接轴谓词本身。
     */
    @Test
    void narrowSuppressesDirectAxisPredicate() {
        // task-260909 D-6（方案甲）：核价侧轴统一为生产料号，COST_* 不再发 ds_quote_material 桥。
        // 本断言原属 task-260819 AC-111/AC-112，其前提「轴值是销售料号」已被 D-6 取消。
        //
        // 🔄 原断言是「有 NARROW 时不许发直接轴谓词」（因为当时数组装的是销售料号）。
        //    D-6 后数组装生产料号、桥停发 ⇒ 直接轴谓词从"错误形态"变成"唯一正确形态"，断言翻面。
        //    🔑 「恰好 1 处」这条**一个字没动** —— 它才是这个用例真正的不变量。
        Object[] g = bridgeGraph("NARROW", true, true);
        CompileResult r = compilerWith((StubCatalog) g[1]).compile(
                (SemanticGraphSnapshot) g[0], cfg("主件", "COST_BASIC", "COST_MAIN", "part_name"),
                CompileDialect.COST_BASIC);
        assertTrue(r.sql.contains("dcbm.production_no = ANY(:total_material_no)"),
                "D-6：数组装的就是生产料号，收窄必须直接落在 production_no 上：\n" + r.sql);
        assertFalse(r.sql.contains("IN (SELECT"),
                "D-6：不该再有桥半连接 —— 与上面那条直接轴谓词共存就是 B-52 的『两种号段要求相反 ⇒ "
                        + "交集恒空 ⇒ 静默 0 行』：\n" + r.sql);
        assertEquals(1, countOf(r.sql, ":total_material_no"), "收窄入口应恰好一处：\n" + r.sql);
    }

    /** 零回归：没有 NARROW 边时，轴收窄行为逐字不变。 */
    @Test
    void withoutNarrowEdgeAxisPredicateIsUnchanged() {
        SemanticNode n = sheet("M", "核价主件", "主件",
                "ds_cost_basic_material", "COST_BASIC", "dcbm.production_no");
        StubCatalog stub = new StubCatalog();
        stub.byTable.put("ds_cost_basic_material",
                new LinkedHashSet<>(List.of("production_no", "part_name")));
        CompileResult r = compilerWith(stub).compile(
                snap(List.of(n), List.of(col(n, "part_name", "品名", "TEXT")),
                        List.of(tabView("主件", "COST_BASIC", n))),
                cfg("主件", "COST_BASIC", "M", "part_name"), CompileDialect.COST_BASIC);
        assertTrue(r.sql.contains("dcbm.production_no = ANY(:total_material_no)"), r.sql);
    }

    /** NARROW 目标的列不可选，且错误文案要说清"为什么不给选"，别只说"暂不支持"。 */
    @Test
    void selectingColumnFromNarrowTargetIsRejected() {
        Object[] g = bridgeGraph("NARROW", true, true);
        BuilderApiException ex = assertThrows(BuilderApiException.class, () ->
                compilerWith((StubCatalog) g[1]).compile((SemanticGraphSnapshot) g[0],
                        cfg("主件", "COST_BASIC", "QUOTE_MATERIAL_BRIDGE", "material_no"),
                        CompileDialect.COST_BASIC));
        System.out.println("---- 取 NARROW 的列 ----\n" + ex.getMessage());
        assertEquals("COMPILE_EDGE_KIND_UNSUPPORTED", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("收窄"), ex.getMessage());
    }

    /** 字段面板不出现桥 —— 即便种子里它还以 AUX 身份挂在页签视图上。 */
    @Test
    void narrowTargetIsHiddenFromFieldPanel() {
        Object[] g = bridgeGraph("NARROW", true, true);
        FieldTreeBuilder.FieldTreeResponse resp = new FieldTreeBuilder()
                .build((SemanticGraphSnapshot) g[0], CompileDialect.COST_BASIC, "主件", "", null);
        List<String> keys = resp.groups.stream().map(x -> x.groupKey).toList();
        System.out.println("---- NARROW 下的字段面板分组 ----\n" + keys);
        assertEquals(List.of("COST_MAIN"), keys, "桥不该是可拖分组：" + keys);
    }

    /**
     * 桥表缺入参列 —— 这条用例守的是「不收窄 = 全表数据」这个后果，而不是某一个错误码。
     *
     * <p>D-6 之前：桥是核价侧唯一的收窄手段，桥发不出去就必须<b>响亮失败</b>。
     * <p>D-6 之后：核价侧压根不发桥，收窄由直接轴谓词承担 ⇒ 一个坏桥声明<b>不该再阻断编译</b>，
     * 但<b>产物仍然必须收窄</b> —— 断言因此从「必抛 COMPILE_NARROW_INPUT_COLUMN_MISSING」
     * 翻成「不抛，且轴谓词在」。后果这一头一个字没放松。
     */
    @Test
    void narrowWithoutInputColumnFailsLoudly() {
        // task-260909 D-6（方案甲）：核价侧轴统一为生产料号，COST_* 不再发 ds_quote_material 桥。
        // 本断言原属 task-260819 AC-111/AC-112，其前提「轴值是销售料号」已被 D-6 取消。
        Object[] g = bridgeGraph("NARROW", true, false);   // 桥表故意缺 material_no 入参列
        CompileResult r = assertDoesNotThrow(() ->
                compilerWith((StubCatalog) g[1]).compile((SemanticGraphSnapshot) g[0],
                        cfg("主件", "COST_BASIC", "COST_MAIN", "part_name"), CompileDialect.COST_BASIC),
                "D-6：核价侧不再发桥 ⇒ 坏桥声明不该再阻断编译");
        System.out.println("---- D-6 坏桥声明下的 COST_BASIC 产物 ----\n" + r.sql);
        assertFalse(r.sql.contains("ds_quote_material"), "坏桥更不该被发出去：\n" + r.sql);
        assertTrue(r.sql.contains("dcbm.production_no = ANY(:total_material_no)"),
                "🚨 这条才是本用例的本体：桥发不发都好，**产物绝不许一点收窄都没有**"
                        + "（不收窄 = 全表数据）：\n" + r.sql);
    }

    /**
     * B-52：{@code skipNarrowPredicates=true} 时不发半连接，且**恢复**直接轴收窄
     * （两者是一对，不能只关一半）。
     *
     * <p>D-6 之后核价方言在<b>两个重载下都不发桥</b>，故本用例还多了一条对照：
     * skip 与默认产出的收窄形态<b>一致</b>——这正是「翻译已上移到骨架种子处」的可观测结果。
     */
    @Test
    void skipNarrowRestoresDirectAxisPredicate() {
        // task-260909 D-6（方案甲）：核价侧轴统一为生产料号，COST_* 不再发 ds_quote_material 桥。
        // 本断言原属 task-260819 AC-111/AC-112，其前提「轴值是销售料号」已被 D-6 取消。
        Object[] g = bridgeGraph("NARROW", true, true);
        CompileResult skipped = compilerWith((StubCatalog) g[1]).compile(
                (SemanticGraphSnapshot) g[0], cfg("主件", "COST_BASIC", "COST_MAIN", "part_name"),
                CompileDialect.COST_BASIC, true);
        System.out.println("---- B-52 skipNarrow ----\n" + skipped.sql);
        assertFalse(skipped.sql.contains("IN (SELECT"), "不该再有半连接：\n" + skipped.sql);
        assertTrue(skipped.sql.contains("dcbm.production_no = ANY(:total_material_no)"),
                "跳过桥后必须恢复直接轴收窄，否则整条 SQL 完全不收窄：\n" + skipped.sql);

        // 默认重载（三参）—— 保存/编译/体检走的是它。
        // 🔄 原断言「默认必须带桥」已被 D-6 取消：核价侧默认重载同样不发桥。
        CompileResult normal = compilerWith((StubCatalog) bridgeGraph("NARROW", true, true)[1]).compile(
                (SemanticGraphSnapshot) bridgeGraph("NARROW", true, true)[0],
                cfg("主件", "COST_BASIC", "COST_MAIN", "part_name"), CompileDialect.COST_BASIC);
        System.out.println("---- D-6 默认重载（核价侧同样不发桥）----\n" + normal.sql);
        assertFalse(normal.sql.contains("IN (SELECT"),
                "D-6：默认重载在核价方言下也不许带桥（落库的 sql_template 走的就是它）：\n" + normal.sql);
        assertFalse(normal.sql.contains("ds_quote_material"), "同上，任何位置都不许有：\n" + normal.sql);
        assertTrue(normal.sql.contains("dcbm.production_no = ANY(:total_material_no)"),
                "默认重载同样必须有直接轴收窄：\n" + normal.sql);
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
