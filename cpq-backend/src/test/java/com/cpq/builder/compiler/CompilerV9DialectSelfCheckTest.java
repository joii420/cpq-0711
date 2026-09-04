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

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
