package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · <b>S-3 / AC-11</b>：快照条目缺 {@code axis_scope} 键 ⇒ 行为回到 {@code CLOSURE}。
 *
 * <h3>AC-11 原文（{@code 问题说明.md §⑥ 边界类}，逐字）</h3>
 * <blockquote>
 * 前置：人为构造一个<b>缺 {@code axis_scope} 键</b>的快照条目 ｜ 操作：渲染该模板的主件页签 ｜
 * 断言：行为<b>回到 {@code CLOSURE}</b>（与改动前逐位相同），不报错、不空行 ——
 * 证明零回归兜底真的在，而不是只写在注释里。
 * </blockquote>
 *
 * <h3>🚨 为什么天然样本证不出来（主线 2026-09-09 交办的核心要求）</h3>
 * 主线找到的天然样本（{@code 正泰测试模板1} / {@code QT-20260908-0627}，快照 3 条全无
 * {@code axis_scope} 键、产品页签 6/8 行）落在一个<b>双解释</b>上：
 * <pre>
 *   解释 A：新代码读不到 axis_scope ⇒ 兜底回落 CLOSURE ⇒ 宽行   ← 想证的
 *   解释 B：算它的那个后端根本没有 axisScope 代码（master）⇒ 也是宽行
 * </pre>
 * 两种解释<b>给同一个答案</b> ⇒ 判据落在恒成立维度上，是零证据而不是弱证据。
 *
 * <h3>✅ 本类怎么把它变成决定性样本</h3>
 * <table border="1">
 *   <tr><th>要求</th><th>本类的做法</th></tr>
 *   <tr><td>同一个后端实例</td>
 *       <td>三套夹具在<b>同一个 JVM、同一次 {@code mvnw test}</b> 里跑完，无跨实例可能</td></tr>
 *   <tr><td>排除「这个后端没有 axisScope 代码」</td>
 *       <td>{@link #t01_selfNarrowsAndClosureWidens_meterSelfProof} 在<b>同一实例</b>上先证明
 *           {@code SELF} 能把主件页签收到 1 行 —— master 版本做不到这件事，
 *           ⇒ 解释 B 被<b>实测排除</b>，不是被假设排除</td></tr>
 *   <tr><td>唯一变量 = 那个键</td>
 *       <td>{@link #t02_missingSnapshotKey_fallsBackToClosure} 的两套夹具由<b>同一个工厂方法、
 *           同一个克隆源、同一份数据、同一个料号</b>产出，{@code liveAxis} 也<b>同为 SELF</b>；
 *           两者之间<b>只有快照里那个键不同</b></td></tr>
 *   <tr><td>「与改动前逐位相同」</td>
 *       <td>{@link #t03_missingKey_isByteIdenticalToExplicitClosure} 断言「缺键」与「显式 CLOSURE」
 *           的 {@code driverRow} <b>逐行逐字节相同</b>，不是只比行数</td></tr>
 * </table>
 *
 * <h3>🚨 一个必须先跨过的坑（本片实测，写在这里免得下一个人重踩）</h3>
 * 私有克隆只改 {@code quotation_line_item.template_id} 是<b>无效的</b> ——
 * 渲染取模板走 {@code quotation.customer_template_id}，组件走 {@code template_component(_snapshot)}。
 * 克隆没接上时渲染<b>照常成功</b>，三种 {@code axis_scope} 给出<b>同一个数字</b>，
 * 而那正好是「兜底生效」的长相。⇒ 每套夹具建好都先过 {@code assertMineWasUsed}。
 *
 * <h3>🚨 证伪设计（{@code test.md §4}）</h3>
 * 把 {@code B-4} 的「缺键退回 {@code CLOSURE}」改成「缺键当 {@code SELF}」（或直接删掉读回逻辑），
 * {@link #t02_missingSnapshotKey_fallsBackToClosure} 立刻变红（缺键会给出 1 行而不是闭包宽度）。
 * 反过来，把 {@code B-5} 的 {@code SELF} 分支改回恒加宽，
 * {@link #t01_selfNarrowsAndClosureWidens_meterSelfProof} 的「SELF ⇒ 1 行」变红。
 * ⇒ <b>两个方向各有一条判据把守</b>，任一侧回退都拦得住。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac11AxisScopeMissingFallbackTest extends S3FixtureBase {

    /** 本片用的销售料号：{@code S0001}，其 BOM 闭包 = {@code S0001 / S0002 / S0003}（+ 更深子件）。 */
    private static final String PART = "S0001";

    @AfterAll
    static void tearDownClass() {
        // 清理走实例方法（要注入的 EntityManager），交给最后一个用例的 finally 语义 —— 见 t99。
    }

    /**
     * 量具自证 + 阳性对照（{@code test.md §3}：先证明量具在已知答案上给得出正确结果，且<b>既能红也能不红</b>）。
     *
     * <p>两个方向都必须成立，缺一条这套实验就退化成零证据：
     * <ul>
     *   <li>{@code axis_scope=SELF} ⇒ 主件页签 <b>恰 1 行</b>，且就是卡片自己的料号；</li>
     *   <li>{@code axis_scope=CLOSURE} ⇒ <b>严格多于 1 行</b>，且包含 BOM 子件。</li>
     * </ul>
     * 🔑 第一条同时<b>排除了「本实例没有 axisScope 代码」</b>这个竞争解释 ——
     * 改动前的代码恒走闭包，做不出 1 行。
     */
    @Test
    @Order(1)
    void t01_selfNarrowsAndClosureWidens_meterSelfProof() {
        Fixture self = createFixture("A11SELF", "SELF", "SELF", PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(self), "私有单渲染应 200");
        assertMineWasUsed(self);
        List<String> selfParts = renderedPartNos(self, self.componentId());

        Fixture closure = createFixture("A11CLO", "CLOSURE", "CLOSURE", PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(closure), "私有单渲染应 200");
        assertMineWasUsed(closure);
        List<String> closureParts = renderedPartNos(closure, closure.componentId());

        System.out.println("[AC-11·量具自证] SELF    → " + selfParts.size() + " 行 " + selfParts);
        System.out.println("[AC-11·量具自证] CLOSURE → " + closureParts.size() + " 行 " + closureParts);

        assertEquals(1, selfParts.size(),
                "量具自证失败：axis_scope=SELF 时主件页签应恰 1 行，实际 " + selfParts.size() + " 行 " + selfParts
                + "\n  ⇒ 若这里不是 1，说明本实例的 SELF 分支没生效（可能跑在改动前的代码上），"
                + "后面「缺键 ⇒ CLOSURE」的对比将无法与『本来就恒 CLOSURE』区分开 —— 那是零证据。");
        assertEquals(PART, selfParts.get(0), "SELF 时那一行应当就是卡片自己的料号");

        assertTrue(closureParts.size() > 1,
                "量具自证失败：axis_scope=CLOSURE 时主件页签应严格多于 1 行（闭包含 BOM 子件），实际 "
                + closureParts.size() + " 行 " + closureParts
                + "\n  ⇒ 两个取值给不出不同答案 ⇒ 本实验没有分辨力，任何结论都是零证据。");
        assertTrue(closureParts.contains(PART), "CLOSURE 的结果里应当仍含卡片自己的料号");
    }

    /**
     * 🎯 <b>AC-11 决定性判据</b>：唯一变量 = 快照里的 {@code axis_scope} 键。
     *
     * <p>两套夹具的<b>后端实例 / 视图 SQL / 组件配置 / 模板结构 / 数据 / 料号 / 客户 / live 的
     * {@code builder_config.axisScope}</b> 全部相同（{@code liveAxis} 都钉死在 {@code SELF}），
     * <b>只有</b> {@code template.sql_views_snapshot.<key>.axis_scope} 一个存在、一个被删掉。
     * <p>🚫 没有用「不同模板 / 不同报价单 / 不同时间点」当对照 —— 那都会引入第二个变量。
     */
    @Test
    @Order(2)
    void t02_missingSnapshotKey_fallsBackToClosure() {
        Fixture withKey = createFixture("A11K1", "SELF", "SELF", PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(withKey), "私有单渲染应 200");
        assertMineWasUsed(withKey);
        List<String> withKeyParts = renderedPartNos(withKey, withKey.componentId());

        // 唯一变量：快照里那个键被删掉；liveAxis 仍是 SELF，与对照组逐字相同
        Fixture noKey = createFixture("A11K0", "SELF", null, PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(noKey),
                "AC-11 要求「不报错」：缺 axis_scope 键时渲染仍应 200");
        assertMineWasUsed(noKey);
        List<String> noKeyParts = renderedPartNos(noKey, noKey.componentId());

        // 先把「唯一变量」这件事本身证明掉，而不是口头声明
        assertEquals("SELF", snapAxisOf(withKey), "对照组的快照键应为 SELF");
        assertEquals(null, snapAxisOf(noKey), "实验组的快照键应当已被删除（读出应为 null）");
        assertEquals(liveAxisOf(withKey), liveAxisOf(noKey),
                "两组的 live builder_config.axisScope 必须相同，否则唯一变量不成立");

        System.out.println("[AC-11] 快照键=SELF   → " + withKeyParts.size() + " 行 " + withKeyParts);
        System.out.println("[AC-11] 快照键=<缺失> → " + noKeyParts.size() + " 行 " + noKeyParts);

        assertEquals(1, withKeyParts.size(), "对照组（键在、值 SELF）应为 1 行");
        assertTrue(noKeyParts.size() > 1,
                "AC-11 失败：快照缺 axis_scope 键时主件页签应回到 CLOSURE（闭包宽度），实际只有 "
                + noKeyParts.size() + " 行 " + noKeyParts
                + "\n  ⇒ 兜底没有回落到 CLOSURE，而是沿用了 SELF —— 那正是 AC-11 要堵的『零回归兜底只写在注释里』。");
        assertTrue(!noKeyParts.isEmpty(), "AC-11 要求「不空行」");
        assertTrue(noKeyParts.contains(PART), "缺键回落 CLOSURE 后仍应含卡片自己的料号");
    }

    /**
     * AC-11 的「<b>与改动前逐位相同</b>」那一半：缺键 ⇒ 不只是「行数变宽」，
     * 而是与<b>显式 {@code CLOSURE}</b> 的渲染结果<b>逐行逐字节相同</b>。
     *
     * <p>🚫 只比行数不够：行数相同而内容不同（例如取到了别家客户的孪生行）同样是缺陷，
     * 而行数判据对它<b>恒为真</b>。
     */
    @Test
    @Order(3)
    void t03_missingKey_isByteIdenticalToExplicitClosure() {
        Fixture explicitClosure = createFixture("A11B1", "SELF", "CLOSURE", PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(explicitClosure));
        assertMineWasUsed(explicitClosure);
        List<String> a = driverRows(explicitClosure);

        Fixture missing = createFixture("A11B0", "SELF", null, PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(missing));
        assertMineWasUsed(missing);
        List<String> b = driverRows(missing);

        System.out.println("[AC-11·逐位] 显式 CLOSURE → " + a.size() + " 行");
        System.out.println("[AC-11·逐位] 缺键        → " + b.size() + " 行");
        assertTrue(!a.isEmpty(), "对照组渲染结果为空 ⇒ 后面的『相同』会退化成空对空，判据作废");
        assertEquals(a, b, "AC-11 失败：缺 axis_scope 键的渲染结果与显式 CLOSURE 不是逐位相同"
                + "\n  显式 CLOSURE = " + a + "\n  缺键        = " + b);
    }

    /**
     * 附带查明（不是 AC，但它决定了 {@link #t02_missingSnapshotKey_fallsBackToClosure}
     * 的「唯一变量」到底站不站得住）：<b>权威在模板快照，live 的
     * {@code component_sql_view.builder_config.axisScope} 在这条渲染路径上不参与。</b>
     *
     * <p>两个交叉格，各自把两个来源钉成相反值：
     * <ul>
     *   <li>{@code live=CLOSURE + snap=SELF} ⇒ 若结果是 1 行 ⇒ 快照赢；</li>
     *   <li>{@code live=SELF + snap=CLOSURE} ⇒ 若结果是宽行 ⇒ 快照赢。</li>
     * </ul>
     * 两格同时成立才下结论。🚫 只做一格会被「两个来源恰好一致」蒙混过去。
     */
    @Test
    @Order(4)
    void t04_snapshotWinsOverLiveBuilderConfig() {
        Fixture liveWide = createFixture("A11X1", "CLOSURE", "SELF", PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(liveWide));
        assertMineWasUsed(liveWide);
        List<String> x1 = renderedPartNos(liveWide, liveWide.componentId());

        Fixture liveNarrow = createFixture("A11X2", "SELF", "CLOSURE", PART, COMP_PRODUCT, VIEW_PRODUCT);
        assertEquals(200, render(liveNarrow));
        assertMineWasUsed(liveNarrow);
        List<String> x2 = renderedPartNos(liveNarrow, liveNarrow.componentId());

        System.out.println("[AC-11·权威归属] live=CLOSURE / snap=SELF    → " + x1.size() + " 行 " + x1);
        System.out.println("[AC-11·权威归属] live=SELF    / snap=CLOSURE → " + x2.size() + " 行 " + x2);

        assertEquals(1, x1.size(), "快照 SELF 应当赢过 live CLOSURE（结果 1 行）；实际 " + x1);
        assertTrue(x2.size() > 1, "快照 CLOSURE 应当赢过 live SELF（结果宽行）；实际 " + x2);
    }

    /** 清理 + 残留核验（放最后一个 {@code @Order}，等价于本类的 finally）。 */
    @Test
    @Order(99)
    void t99_cleanup() {
        // 🚨 不用裸 try/finally：destroyAll 抛异常时，finally 里的断言会「顶替」掉原异常
        // （2026-09-09 实测：外键挡住删除，报出来的却是「清理未净」，真正的原因被吞了）。
        RuntimeException destroyErr = null;
        try {
            destroyAll();
        } catch (RuntimeException e) {
            destroyErr = e;
            System.out.println("[S-3·cleanup] destroyAll 抛异常：" + e);
        }
        try {
            assertResidueFree();
        } catch (AssertionError ae) {
            if (destroyErr != null) {
                ae.addSuppressed(destroyErr);
            }
            throw ae;
        }
        if (destroyErr != null) {
            throw new AssertionError("清理过程抛异常（残留核验虽为 0，仍须查明）", destroyErr);
        }
    }

    // ═══════════════════ 读回助手 ═══════════════════

    private String snapAxisOf(Fixture f) {
        return scalarStr("SELECT sql_views_snapshot -> ?2 ->> 'axis_scope' FROM template WHERE id = CAST(?1 AS uuid)",
                f.templateId(), f.snapshotKey());
    }

    private String liveAxisOf(Fixture f) {
        String v = scalarStr("SELECT coalesce(builder_config ->> 'axisScope', '<ABSENT>') "
                + "FROM component_sql_view WHERE id = CAST(?1 AS uuid)", f.viewId());
        return v == null ? "<ABSENT>" : v;
    }

    /** 该主件页签渲染出的每行 {@code driverRow} 原文，按文本排序（消除行序噪声后可做逐位比较）。 */
    private List<String> driverRows(Fixture f) {
        return strList("SELECT r->'driverRow' FROM quotation_line_component_data d "
                + "JOIN quotation_line_item l ON l.id = d.line_item_id, "
                + "     jsonb_array_elements(d.snapshot_rows) r "
                + "WHERE l.quotation_id = CAST(?1 AS uuid) AND d.component_id = CAST(?2 AS uuid) "
                + "ORDER BY 1", f.quotationId(), f.componentId());
    }
}
