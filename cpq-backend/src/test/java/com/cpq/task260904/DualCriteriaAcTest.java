package com.cpq.task260904;

import com.cpq.configure.service.ConfigureSnapshotService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-27 / TC-21 —— <b>AC-27（双判据分流正确性）</b> + <b>AC-21（{@code bom_recursive_expand} 必须有新的写入源）</b>。
 *
 * <h3>AC-27 原文（{@code 需求文档.md §3.3}）</h3>
 * 组件：A（数据源「物料BOM」，{@code builder_version} 非空，{@code tab_type} 为空）；
 * B（存量，{@code tab_type='BOM'}，{@code builder_version} 为 NULL）；
 * C（{@code builder_version} 非空且数据源为「自制加工费」，<b>同时</b> {@code tab_type='BOM'}）。
 * 断言：⓪ 先断言 A 的 {@code builder_version} 确实非 NULL；① A 判<b>树</b>；② B 判<b>树</b>；
 * ③ C 判<b>非树</b>（有 builder_config 时以分支①为准，不被历史 {@code tab_type} 值污染）；
 * ④ 判据实现全工程只有一处（由 AC-22 佐证，见 {@link TreeJudgementHardcodeScanAcTest}）。
 *
 * <h3>🚨 A 与 C 必须由用例自己经真实保存路径产出</h3>
 * 立项时 {@code component_sql_view.builder_version} 现网 <b>0 行非 NULL</b>
 * （⚠️ 2026-09-05 已涨到 29 行，来源是 task-260819 的测试残留 —— <b>移动靶，不作断言</b>）。若依赖「库里恰好有」，
 * 分支① 一次都不会命中，<b>用例会以「通过」的形态空跑</b>（test.md §3 的本期最高假绿风险）。
 * ⇒ 本类的 A / C 全部经 {@code POST /components} + {@code PUT /components/{id}/builder} 产出，
 * 并在断言分流之前先过 {@link Task260904Base#assertBuilderVersionPresent}。
 *
 * <h3>「判为树」的可观测代理是什么，为什么选它</h3>
 * 双判据方法本身是内部 API，本套用例<b>不读实现</b>、也就不能直接调它。
 * 可观测代理取 <b>{@code component.bom_recursive_expand}</b>：
 * 需求文档 §4.2⑥ 实测该列与「是否树页签」现网 <b>1:1 绑定零例外</b>（BOM→t 21 / 其余 202 行全 f），
 * 且 AC-21 明确要求它由新判据写入。A 另外再加一层<b>行为级</b>观察：把 A 挂进真实模板与报价行，
 * 物化后断言它真的拿到了树骨架（AC-21②「能拿到 BOM union driver」）。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-27/AC-21 —— 双判据三态分流 + bom_recursive_expand 新写入源")
class DualCriteriaAcTest extends Task260904Base {

    @Inject
    ConfigureSnapshotService configureSnapshotService;

    private boolean bomRecursiveExpand(UUID componentId) {
        String v = scalar("SELECT bom_recursive_expand::text FROM component WHERE id = '" + componentId + "'");
        assertNotNull(v, "取不到组件 " + componentId + " 的 bom_recursive_expand（组件不存在？）");
        return "t".equals(v) || "true".equalsIgnoreCase(v);
    }

    private String tabTypeOf(UUID componentId) {
        return scalar("SELECT tab_type FROM component WHERE id = '" + componentId + "'");
    }

    // ═══════════════════════ AC-27 ①：A（新组件，物料BOM）═══════════════════════

    @Test
    @DisplayName("AC-27①/AC-21①：新组件绑「物料BOM」→ builder_version 非空、tab_type 为空、判为树")
    void ac27_branchOne_newComponentBoundToMaterialBom() {
        UUID a = createBlankComponent("A27-A");
        saveBuilderOk(a, CFG_MATERIAL_BOM, "AC-27①");

        // ⓪ 窗口期守卫：先证明分支①真的有输入
        assertBuilderVersionPresent(a, "AC-27⓪");

        // 新组件的 tab_type 期望为空（AC-27 对组件 A 的定义）——
        // 🚨 拆到 ac27_newComponentShouldNotWriteTabType 单独断言，不放在这里：
        //    否则它一红就会把下面「判为树」这条主断言的结论盖掉（一个用例只应回答一个问题）。
        System.out.println("[AC-27①·观察] 新组件 " + a + " 的 component.tab_type = " + tabTypeOf(a));

        // ① 判为树 —— 可观测代理：bom_recursive_expand（AC-21①）
        assertTrue(bomRecursiveExpand(a),
                "AC-27①/AC-21①：数据源「物料BOM」的新组件应被判为 BOM 树 ⇒ bom_recursive_expand 必须为 true。"
                        + "实际=false ⇒ 下拉去掉后请求不带 tabType，applyTabType 不执行、该列恒 false，"
                        + "新建的树页签会「没有数据且不报错」（需求文档 §4.2⑥）。");
    }

    // ═══════════════════════ AC-27 ②：B（存量，只有 tab_type）═══════════════════════

    @Test
    @DisplayName("AC-27②：存量组件（tab_type='BOM'、builder_version 为 NULL）仍走分支②判为树")
    void ac27_branchTwo_legacyComponent() {
        // 直接用现网存量的 21 个 BOM 树组件之一 —— 🚫 只读，一个字节都不改
        String legacy = scalar("SELECT c.id::text FROM component c "
                + "LEFT JOIN component_sql_view v ON v.component_id = c.id "
                + "WHERE c.tab_type = 'BOM' AND v.builder_version IS NULL "
                + "ORDER BY c.code LIMIT 1");
        assertNotNull(legacy, "前置未满足：现网找不到「tab_type='BOM' 且 builder_version 为 NULL」的存量组件 "
                + "⇒ AC-27② 的分支②无输入，用例会空跑。（立项时实测这样的组件有 21 个）");
        UUID b = UUID.fromString(legacy);

        assertNull(scalar("SELECT builder_version::text FROM component_sql_view WHERE component_id = '" + b + "'"),
                "前置：B 必须是「没有 builder_version」的存量组件，否则它走的是分支①，验不到分支②");
        assertEquals("BOM", tabTypeOf(b), "前置：B 的 tab_type 必须是 'BOM'");

        assertTrue(bomRecursiveExpand(b),
                "AC-27②：存量 BOM 树组件必须仍被判为树（回退读 component.tab_type）。"
                        + "组件 " + b + " 的 bom_recursive_expand=false ⇒ 存量树渲染当场失效（§1.35 硬约束②）。");
        System.out.println("[AC-27②] 存量组件 " + b + " tab_type=BOM / builder_version=NULL / bom_recursive_expand=true ✅");
    }

    // ═══════════════════════ AC-27 ③：C（两者都有）═══════════════════════

    /**
     * <b>AC-27③</b>：C 同时有 {@code builder_config}（自制加工费，semantic 为 null）与历史 {@code tab_type='BOM'}。
     * 期望<b>以分支①为准 ⇒ 判非树</b>。
     *
     * <p>🚨 这一态是本 AC 的关键：只验 A、B 两态的话，
     * <b>「分支②优先」这种写反的实现照样全绿</b> —— 而它会让存量的 tab_type 值污染新组件的判定。
     *
     * <p>构造顺序刻意是「先埋历史 tab_type，再走配置器保存」：
     * 反过来的话保存时还没有 tab_type，重新推导 {@code bom_recursive_expand} 时看不到污染源，
     * 用例就变成了一个不会失败的摆设。
     */
    @Test
    @DisplayName("AC-27③：既有 builder_config（自制加工费）又有历史 tab_type='BOM' → 判非树（分支①优先）")
    void ac27_branchOneWinsOverLegacyTabType() {
        UUID c = createBlankComponent("A27-C");

        // 先埋一个「历史 tab_type='BOM' + bom_recursive_expand=true」的污染源（模拟存量值残留）
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE component SET tab_type = 'BOM', bom_recursive_expand = true WHERE id = :id")
                .setParameter("id", c).executeUpdate());
        assertEquals("BOM", tabTypeOf(c), "构造自检：污染源 tab_type='BOM' 应已写入");
        assertTrue(bomRecursiveExpand(c), "构造自检：污染源 bom_recursive_expand=true 应已写入 —— "
                + "🚨 不先置 true 的话，下面「应为 false」的断言在实现根本没重算时也会通过（重言）");

        // 再走取数配置器的真实保存路径，绑「自制加工费」
        saveBuilderOk(c, CFG_SELF_PROCESS_FEE, "AC-27③");
        assertBuilderVersionPresent(c, "AC-27③⓪");

        System.out.println("[AC-27③·观察] C 保存后 component.tab_type = " + tabTypeOf(c)
                + "（期望仍是 BOM：§1.35 硬约束③『列永久保留、新组件不再写它』；"
                + "被改写属另一条结论，见 ac27_newComponentShouldNotWriteTabType）");

        assertTrue(!bomRecursiveExpand(c),
                "AC-27③：C 有 builder_config 时必须以分支①为准 ⇒ 数据源是「自制加工费」（semantic 为 null）"
                        + "应判非树、bom_recursive_expand 应被重算为 false。实际仍为 true "
                        + "⇒ 分支②优先（写反了），历史 tab_type 值污染了新组件的判定。");
    }

    // ═══════════════════════ AC-21 ②③ ═══════════════════════

    /**
     * <b>AC-21③</b>：数据源选「自制加工费」的新组件，{@code bom_recursive_expand} 应为 {@code false}。
     * <p>这是 AC-21① 的<b>阴性对照</b>：没有它，一个「无脑把该列置 true」的实现也能让 AC-21① 全绿。
     */
    @Test
    @DisplayName("AC-21③：新组件绑「自制加工费」→ bom_recursive_expand = false（AC-21① 的阴性对照）")
    void ac21_feeSourceIsNotTree() {
        UUID fee = createBlankComponent("A21-FEE");
        saveBuilderOk(fee, CFG_SELF_PROCESS_FEE, "AC-21③");
        assertBuilderVersionPresent(fee, "AC-21③⓪");
        assertTrue(!bomRecursiveExpand(fee),
                "AC-21③：「自制加工费」不是 BOM 树，bom_recursive_expand 应为 false，实际为 true "
                        + "⇒ 该列被无脑置 true，AC-21① 的绿没有意义。");
        System.out.println("[AC-21③·观察] 新组件 " + fee + " 的 component.tab_type = " + tabTypeOf(fee));
    }

    /**
     * <b>AC-27 对组件 A 的定义里那一句「{@code tab_type} 为空」</b>，单独成条。
     *
     * <p>🚨 单拎出来是因为它<b>可能根本不属于第一批的可达范围</b>：
     * 写 {@code component.tab_type} 的是取数配置器保存链路（{@code BuilderService}），
     * 而它是《需求文档 §①bis》点名的<b>5 个冲突文件之一</b>，第一批「严禁触碰」。
     * ⇒ 本条若红，请先判定它是「实现漏了」还是「AC 在第一批不可达、该调 AC」，
     * 🚫 不要直接让开发去改那 5 个文件。
     */
    @Test
    @DisplayName("AC-27(A 的定义)：经配置器保存的新组件，component.tab_type 应为空")
    void ac27_newComponentShouldNotWriteTabType() {
        UUID a = createBlankComponent("A27-TT");
        saveBuilderOk(a, CFG_SELF_PROCESS_FEE, "AC-27(A 定义)");
        assertBuilderVersionPresent(a, "AC-27(A 定义)⓪");
        assertNull(tabTypeOf(a),
                "AC-27 把组件 A 定义为「builder_version 非空、tab_type 为空」，"
                        + "但经取数配置器真实保存后 component.tab_type = " + tabTypeOf(a)
                        + "。⇒ 要么新组件仍在写 tab_type（S-10 未落地），"
                        + "要么该行为归属第二批的 BuilderService 改动、AC-27 的前置在第一批不可达。"
                        + "请主线裁决，🚫 不要直接改那 5 个冲突文件。");
    }

    /**
     * <b>AC-21②</b>：该组件在报价单上能拿到 BOM union driver
     * （{@code ComponentDriverService.eligibleForBomUnion()} 返回 true，可由渲染出的树行数 &gt; 0 佐证）。
     *
     * <p>🚨 test.md 的 TC-21 明确要求「必须同时验下游」：只查 DB 列值只证明写进去了，不证明下游认它。
     */
    @Test
    @DisplayName("AC-21②：配置器产出的树组件挂进真实报价行后，能渲染出树骨架（下游认它）")
    void ac21_newTreeComponentGetsUnionDriver() {
        UUID a = createBlankComponent("A21-TREE");
        saveBuilderOk(a, CFG_MATERIAL_BOM, "AC-21②");
        assertBuilderVersionPresent(a, "AC-21②⓪");

        TreeFx f = buildTreeFixture("A21", a);
        configureSnapshotService.snapshotQuotation(f.quotationId);

        String rows = readSnapshotRows(f.lineItemId, a);
        assertNotNull(rows, "AC-21②：配置器产出的树组件在报价行上没有 snapshot_rows ⇒ 它没拿到 BOM union driver。"
                + "（这正是需求文档 §4.2⑥ 说的「没有数据且不报错」）");
        assertTrue(rows.contains("__nodeId"),
                "AC-21②：snapshot_rows 里应出现树骨架的系统列 __nodeId，实际=" + rows);
        assertTrue(rows.contains("\"" + f.hostNode() + "\""),
                "AC-21②：树骨架里应能看到 " + f.hostNode() + " 这个节点（说明递归展开真的跑了，不是空数组）。实际=" + rows);
        System.out.println("[AC-21②] 配置器产出的树组件渲染出的 snapshot_rows = " + rows);
    }
}
