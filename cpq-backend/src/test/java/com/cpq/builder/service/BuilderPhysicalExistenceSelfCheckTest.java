package com.cpq.builder.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.compiler.CompileDialect;
import com.cpq.builder.compiler.PhysicalColumnCatalog;
import com.cpq.builder.dto.BuilderDTOs.InspectItem;
import com.cpq.builder.dto.BuilderDTOs.InspectResponse;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.service.SemanticGraphLoader;
import com.cpq.semanticgraph.service.SemanticGraphSnapshot;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260819 B-45 开发自测（AC-123）：S-20 第③道「物理存在性」在保存路径上生效，
 * 且错误信息<b>点名哪张表的哪一列</b>。
 *
 * <p>⚠️ 同 {@code CompilerV9DialectSelfCheckTest}：这是开发自测不是正式验收用例；
 * 纯 JUnit 零查库（{@code application-test.properties} 的默认库就是共享开发库 {@code cpq_db_0724}，
 * 起 {@code @QuarkusTest} 会连上去并跑 {@code migrate-at-start}）。
 */
class BuilderPhysicalExistenceSelfCheckTest {

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
    static class StubCatalog extends PhysicalColumnCatalog {
        final Map<String, Set<String>> byTable = new HashMap<>();
        @Override public Map<String, Set<String>> columnsOf(Collection<String> tables) {
            Map<String, Set<String>> out = new HashMap<>();
            for (String t : tables) if (byTable.containsKey(t)) out.put(t, byTable.get(t));
            return out;
        }
    }

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
    static final class StubLoader extends SemanticGraphLoader {
        SemanticGraphSnapshot snap;
        @Override public SemanticGraphSnapshot get() { return snap; }
    }

    /** 图：COST_BASIC 的「主件」页签，锚点 ds_cost_basic_material，声明了 4 列。 */
    private static SemanticGraphSnapshot graph() {
        SemanticNode n = new SemanticNode();
        n.id = UUID.randomUUID();
        n.nodeKey = "COST_BASIC_MATERIAL";
        n.displayName = "基础核价主件";
        n.shortName = "主件";
        n.nodeKind = "SHEET";
        n.physicalTable = "ds_cost_basic_material";
        n.dialect = "COST_BASIC";
        n.anchorExpr = "dcbm.production_no";
        n.grainColumns = new String[0];

        List<SemanticNodeColumn> cols = new ArrayList<>();
        for (String[] c : new String[][]{
                {"production_no", "生产料号"}, {"part_name", "品名"},
                {"unit_weight", "单重"},
                // ⬇️ 图里声明了、库里没有 —— AC-123 要拦的正是这一类（编译器查不出来）
                {"unit_weigth", "单重(拼错)"}}) {
            SemanticNodeColumn sc = new SemanticNodeColumn();
            sc.id = UUID.randomUUID();
            sc.nodeId = n.id;
            sc.dbColumn = c[0];
            sc.displayName = c[1];
            sc.dataType = "TEXT";
            cols.add(sc);
        }

        SemanticTabView tv = new SemanticTabView();
        tv.id = UUID.randomUUID();
        tv.tabType = "主件";
        tv.variantKey = "";
        tv.dialect = "COST_BASIC";
        tv.anchorNodeId = n.id;
        tv.switches = new String[0];

        return new SemanticGraphSnapshot(1, List.of(n), cols, List.of(), List.of(),
                List.of(tv), List.of(), List.of());
    }

    private static BuilderService serviceWith(StubCatalog cat) {
        BuilderService svc = new BuilderService();
        StubLoader ldr = new StubLoader();
        ldr.snap = graph();
        svc.loader = ldr;
        svc.physicalColumnCatalog = cat;
        return svc;
    }

    private static BuilderConfig cfg(String... dbCols) {
        BuilderConfig c = new BuilderConfig();
        c.tabType = "主件";
        c.variantKey = "";
        c.dialect = "COST_BASIC";
        c.columns = new ArrayList<>();
        for (String dc : dbCols) c.columns.add(new BuilderConfig.ColumnConfig("COST_BASIC_MATERIAL", dc, null));
        return c;
    }

    private static StubCatalog realShape() {
        StubCatalog cat = new StubCatalog();
        cat.byTable.put("ds_cost_basic_material", new LinkedHashSet<>(
                List.of("id", "production_no", "part_name", "unit_weight", "version_no", "row_fingerprint")));
        return cat;
    }

    /** AC-123 正向：引用库里不存在的列 → 阻断，且消息点名 表.列 + 实有列清单。 */
    @Test
    void missingColumnIsBlocked_andNamesTableAndColumn() {
        InspectResponse resp = new InspectResponse();
        boolean failed = serviceWith(realShape())
                .checkPhysicalExistence(cfg("part_name", "unit_weigth"), CompileDialect.COST_BASIC, resp);

        assertTrue(failed, "引用不存在的列必须被判定为失败");
        assertEquals(1, resp.items.size(), resp.items.toString());
        InspectItem it = resp.items.get(0);
        System.out.println("---- AC-123 错误信息 ----\n" + it.message);
        assertEquals("ERR", it.level);
        assertEquals("PHYSICAL_EXISTENCE", it.code);
        assertTrue(it.message.contains("ds_cost_basic_material"), "必须点名物理表：" + it.message);
        assertTrue(it.message.contains("unit_weigth"), "必须点名物理列：" + it.message);
        assertTrue(it.message.contains("unit_weight"), "实有列清单要给出正确拼写供改正：" + it.message);
    }

    /** AC-123 反证：把该列改对后不再阻断（证明拦截判据是列名本身，不是"逢配置必拦"）。 */
    @Test
    void correctedColumnPasses() {
        InspectResponse resp = new InspectResponse();
        boolean failed = serviceWith(realShape())
                .checkPhysicalExistence(cfg("part_name", "unit_weight"), CompileDialect.COST_BASIC, resp);
        assertFalse(failed, resp.items.toString());
        assertTrue(resp.items.isEmpty(), resp.items.toString());
    }

    /** 表整张不存在（节点 physical_table 被改名/删表）→ 也要点名，且不再逐列刷屏。 */
    @Test
    void missingTableIsBlocked_andDoesNotSpamPerColumn() {
        StubCatalog empty = new StubCatalog(); // information_schema 里查不到这张表
        InspectResponse resp = new InspectResponse();
        boolean failed = serviceWith(empty)
                .checkPhysicalExistence(cfg("part_name", "unit_weight"), CompileDialect.COST_BASIC, resp);
        assertTrue(failed);
        assertEquals(1, resp.items.size(), "表级一条即可，不要每列再报一遍：" + resp.items);
        System.out.println("---- 表不存在 ----\n" + resp.items.get(0).message);
        assertTrue(resp.items.get(0).message.contains("ds_cost_basic_material"));
    }

    /** N+1 自检的机器证据：无论选几列，columnsOf 只被调用一次。 */
    @Test
    void catalogIsQueriedExactlyOnce_regardlessOfColumnCount() {
        StubCatalog counting = new StubCatalog() {
            int calls = 0;
            @Override public Map<String, Set<String>> columnsOf(Collection<String> tables) {
                calls++;
                assertEquals(1, calls, "物理存在性校验必须一条 SQL 查完，不许按列循环查（N+1）");
                return super.columnsOf(tables);
            }
        };
        counting.byTable.putAll(realShape().byTable);
        InspectResponse resp = new InspectResponse();
        serviceWith(counting).checkPhysicalExistence(
                cfg("production_no", "part_name", "unit_weight", "unit_weight", "part_name"),
                CompileDialect.COST_BASIC, resp);
        assertTrue(resp.items.isEmpty(), resp.items.toString());
    }
}
