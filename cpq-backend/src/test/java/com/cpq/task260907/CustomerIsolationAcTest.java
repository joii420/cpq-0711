package com.cpq.task260907;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-12</b> —— 「递归换表后客户隔离仍然生效」。
 *
 * <h3>AC 原文（需求文档.md §③ AC-12）</h3>
 * ① 改造后的递归 SQL <b>仍带客户隔离条件</b>（{@code costing_bom_tree_config.sql_template} 里存在
 * {@code customer_no} 的 JOIN/过滤，不是被静默删掉）；
 * ② 同一料号在不同客户的报价单下，BOM 树子件集合<b>各自正确、不互相串</b>；
 * ③ 递归结果<b>不包含</b>不属于本单根成品闭包的料号。
 *
 * <h3>🚩 契约冲突（已停下来报主线，本类按「非回归守卫」实现）</h3>
 * <ul>
 *   <li>需求文档 §② 把 <b>B-5</b>（递归换表 + 接 {@code customer_no} JOIN）列在本任务范围内，但标注
 *       ⛔ <b>阻塞于跨会话前置</b>（28 张 {@code ds_quote_*} 加 {@code customer_no}）。</li>
 *   <li>而 <b>api.md §4 明写「{@code costing_bom_tree_config} 的递归 SQL —— 本任务不改」，
 *       整条归 {@code task-260907-报价侧加客户维度} 的 B-7。</b></li>
 * </ul>
 * ⇒ 两份文档对 AC-12 的归属<b>不一致</b>。本类按 api.md 的口径实现：递归本任务不动 ⇒
 * AC-12 退化为<b>非回归守卫</b>（「本任务没把已有的客户隔离弄掉」），
 * 并把当前架构下 ② 能不能成立<b>如实测出来</b>，🚫 不替谁下结论。
 *
 * <h3>🔑 为什么 ② 必须自造夹具</h3>
 * 需求文档实测：V6 老表 QUOTE 当前数据里<b>跨多客户的料号 0 个</b>、<b>子件集合因客户而异的料号 0 个</b>
 * ⇒ 「客户隔离有没有生效」在现网数据下<b>无法证伪</b>，断言会以「通过」的形态空跑。
 * 本类造<b>同一料号挂两个客户、子件不同</b>的前缀化夹具。
 * 🔑 本项目出过「跨客户串号」故障（森萨塔，占号表 {@code material_customer_map} 全局唯一），不是理论风险。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-12 —— 递归 SQL 的客户隔离（本任务不改递归 ⇒ 非回归守卫 + 现状取证）")
class CustomerIsolationAcTest extends Task260907Base {

    private String quoteRecursionTemplate() {
        String tpl = scalar("SELECT sql_template FROM costing_bom_tree_config WHERE usage='QUOTE' AND is_active = true");
        assertNotNull(tpl, "AC-12 前置：costing_bom_tree_config 里没有 usage='QUOTE' 且 is_active 的行 "
                + "⇒ 全系统共用的那份递归不存在，报价侧树渲染整体不可用。这是地基故障，不是 AC 结论。");
        assertFalse(tpl.isBlank(), "AC-12 前置：QUOTE 递归模板为空串 ⇒ 「含 customer_no」的断言会恒假、"
                + "「不含 X」会恒真，两侧都不可信。");
        return tpl;
    }

    @Test
    @DisplayName("AC-12①：全系统共用的 QUOTE 递归 SQL 仍带 customer_no 客户隔离条件（不是被静默删掉）；"
            + "且仍是 WITH RECURSIVE + CYCLE 防环 + 从 unnest(:production_part_nos) 出发")
    void ac12_recursionStillCarriesCustomerIsolation() {
        String tpl = quoteRecursionTemplate();
        System.out.println("[AC-12①] QUOTE 递归模板（" + tpl.length() + " 字符）:\n" + tpl);

        assertTrue(tpl.contains("customer_no"), "AC-12①：QUOTE 递归 SQL 里已经没有 customer_no 了 "
                + "⇒ 客户隔离维度被静默删掉。用户 2026-09-07 裁决是【把它补进新表体系】，不是丢掉。\nSQL=\n" + tpl);
        // 隔离必须落在递归的连接条件上，而不是只在某个无关子查询里出现一次
        assertTrue(tpl.contains("customer_no=b._cust") || tpl.contains("customer_no = b._cust")
                        || tpl.toUpperCase().contains("AND CH.CUSTOMER_NO"),
                "AC-12①：递归的连接条件上找不到 customer_no 约束 ⇒ 隔离形同虚设（子件会跨客户串）。\nSQL=\n" + tpl);
        assertTrue(tpl.toUpperCase().contains("WITH RECURSIVE"), "AC-12①：递归 SQL 不含 WITH RECURSIVE");
        assertTrue(tpl.toUpperCase().contains("CYCLE"), "AC-12①：递归 SQL 缺 CYCLE 防环 "
                + "（A0 裁决把「不重做递归」的理由之一就记在这个现成的防环上）");
        assertTrue(tpl.contains("production_part_nos"), "AC-12①：递归 SQL 不从 unnest(:production_part_nos)（本单根成品）出发 "
                + "⇒ 数据量边界不再是『这一单』，用户 A0 裁决的前提被推翻。\nSQL=\n" + tpl);
    }

    @Test
    @DisplayName("AC-12①（非回归 · api.md §4）：本任务不改这份递归 —— 它当前仍读 V6 material_bom_item。"
            + "换表（→ ds_quote_material_bom）归 task-260907-报价侧加客户维度 的 B-7，本条只钉住现状不被顺手改掉")
    void ac12_recursionUnchangedByThisTask() {
        String tpl = quoteRecursionTemplate();
        boolean readsV6 = tpl.contains("material_bom_item");
        boolean readsDs = tpl.contains(MATERIAL_BOM_TABLE);
        System.out.println("[AC-12 非回归] 递归读 V6(material_bom_item)=" + readsV6
                + " 读新表(" + MATERIAL_BOM_TABLE + ")=" + readsDs);

        assertTrue(readsV6 || readsDs, "AC-12：递归 SQL 既不读 V6 也不读新表 ⇒ 它读的是第三张表，"
                + "与两份任务文档的描述都不符，停下来报主线。\nSQL=\n" + tpl);
        if (readsDs) {
            // 换表真的发生了 ⇒ 那属于另一条任务的 B-7；此时必须同时带上客户隔离，否则就是「静默删数据」那条风险
            assertTrue(tpl.contains("customer_no"), "AC-12①：递归已换读新表 " + MATERIAL_BOM_TABLE
                    + " 但没有 customer_no 隔离 ⇒ 跨客户串号。需求文档 §② 明写换表必须同时接客户隔离 JOIN。");
            System.out.println("[AC-12 非回归] 🔔 递归已换读新表 —— 那是 task-260907-报价侧加客户维度 的 B-7 落地了，"
                    + "请回主线确认这不是本任务顺手改的。");
        } else {
            System.out.println("[AC-12 非回归] ✅ 递归仍读 V6，与 api.md §4「本任务不改」一致");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-12② —— 同一料号在两个客户下的子件集合
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 跨客户夹具：<b>同一个根料号</b> R 在两个客户下各挂不同子件。
     *
     * <pre>
     *   客户 A：R → A1
     *   客户 B：R → B1
     * </pre>
     * 客户隔离若生效，用 A 的口径展开只该得到 {R, A1}，用 B 的口径只该得到 {R, B1}。
     */
    private record CrossFx(String root, String childA, String childB, String custA, String custB) { }

    private CrossFx buildCrossCustomerFixture() {
        // 🚨 V6 表 material_bom_item 的 material_no / component_no / customer_no 均为 varchar(20)
        //    ⇒ 用短前缀 FX（10 字符），🚫 不能用 PREFIX（2026-09-07 首跑实测被 value too long 打断）
        String tag = FX + "x";
        CrossFx f = new CrossFx(tag + "R", tag + "A1", tag + "B1", "CA" + RUN_ID, "CB" + RUN_ID);
        for (String v : List.of(f.root(), f.childA(), f.childB(), f.custA(), f.custB())) {
            assertTrue(v.length() <= 20, "构造自检：夹具值 " + v + " 长度 " + v.length()
                    + " > 20，写 material_bom_item 会报 value too long（那个错长得像业务缺陷）");
        }
        for (String mn : List.of(f.root(), f.childA(), f.childB())) {
            assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no='" + mn
                    + "' OR component_no='" + mn + "'"), "构造自检：夹具料号 " + mn + " 竟已在 V6 表里");
        }
        QuarkusTransaction.requiringNew().run(() -> {
            insertV6(f.custA(), f.root(), f.childA(), 10);
            insertV6(f.custB(), f.root(), f.childB(), 10);
        });
        // 阳性对照：两条边真的落库了，且确实是「同一料号、两个客户」
        assertEquals(2L, count("SELECT count(*) FROM material_bom_item WHERE material_no='" + f.root() + "'"),
                "夹具自检：根料号应有 2 条边（两个客户各一条）");
        assertEquals(2L, count("SELECT count(DISTINCT customer_no) FROM material_bom_item WHERE material_no='"
                + f.root() + "'"), "夹具自检：这 2 条边应分属 2 个不同客户 —— 否则本条验不到跨客户");
        System.out.println("[AC-12②·fixture] root=" + f.root() + " custA=" + f.custA() + "→" + f.childA()
                + " custB=" + f.custB() + "→" + f.childB());
        return f;
    }

    private void insertV6(String customerNo, String parent, String child, int seq) {
        em.createNativeQuery("INSERT INTO material_bom_item (id,system_type,customer_no,material_no,component_no,"
                        + "item_seq,is_current,composition_qty,created_at,updated_at) "
                        + "VALUES (gen_random_uuid(),'QUOTE',:cn,:p,:c,:s,true,1,NOW(),NOW())")
                .setParameter("cn", customerNo).setParameter("p", parent).setParameter("c", child)
                .setParameter("s", seq).executeUpdate();
    }

    private void dropCrossFixture(CrossFx f) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "DELETE FROM material_bom_item WHERE customer_no IN (:a,:b)")
                .setParameter("a", f.custA()).setParameter("b", f.custB()).executeUpdate());
        long left = count("SELECT count(*) FROM material_bom_item WHERE customer_no IN ('"
                + f.custA() + "','" + f.custB() + "')");
        assertEquals(0L, left, "AC-12② 还原自检：跨客户夹具仍有 " + left + " 条残留");
        System.out.println("[AC-12②·cleanup] 跨客户夹具残留=0");
    }

    @Test
    @DisplayName("AC-12②③：把全系统共用的那份递归 SQL 真的跑一遍 —— "
            + "同一根料号在两个客户下，展开出的子件集合必须各自正确、不互串；且不含闭包外的料号")
    void ac12_crossCustomerClosureDoesNotBleed() {
        String tpl = quoteRecursionTemplate();
        CrossFx f = buildCrossCustomerFixture();
        try {
            // 直接执行那份配置化递归（把 :production_part_nos 绑成本单根成品）
            String sql = tpl.replace(":production_part_nos",
                    "ARRAY['" + f.root().replace("'", "''") + "']::text[]");
            assertFalse(sql.contains(":production_part_nos"), "轴参数替换失败");

            Set<String> expanded = new LinkedHashSet<>();
            for (Object[] r : rows("SELECT material_no::text, coalesce(parent_no,'')::text FROM (" + sql + ") t")) {
                expanded.add(String.valueOf(r[0]));
                System.out.println("    展开：料号=" + r[0] + " 父=" + r[1]);
            }
            System.out.println("[AC-12②] 根=" + f.root() + " 展开集合=" + expanded);

            // ── 阳性对照：递归至少要把根自己算进来，否则下面「不含别人的子件」会以空集恒真通过 ──
            assertFalse(expanded.isEmpty(), "AC-12②：递归展开返回空集 ⇒ 「不互串」会恒真通过（空跑）。SQL 已绑定根="
                    + f.root());
            assertTrue(expanded.contains(f.root()), "AC-12② 阳性对照：展开集合里连根料号自己都没有 ⇒ 递归没跑通，"
                    + "此时任何隔离结论都不可信。实际=" + expanded);
            assertTrue(expanded.size() > 1, "AC-12② 阳性对照：展开集合只有根一个元素（" + expanded + "）⇒ "
                    + "递归一层都没展开，「子件集合各自正确」验不到。检查夹具的 is_current / system_type。");

            // ── ②：两个客户的子件不能同时出现 ──
            boolean hasA = expanded.contains(f.childA());
            boolean hasB = expanded.contains(f.childB());
            System.out.println("[AC-12②] 含客户A子件=" + hasA + " 含客户B子件=" + hasB);
            assertFalse(hasA && hasB, "AC-12②：同一根料号一次展开里【同时】出现了两个客户的子件（"
                    + f.childA() + " 与 " + f.childB() + "）⇒ 跨客户串号。"
                    + "🔑 本项目出过森萨塔跨客户串号故障，这不是理论风险。展开集合=" + expanded);

            // ── ③：不含闭包外的料号 ──
            Set<String> allowed = new LinkedHashSet<>(List.of(f.root(), f.childA(), f.childB()));
            List<String> outsiders = expanded.stream().filter(x -> !allowed.contains(x)).toList();
            assertTrue(outsiders.isEmpty(), "AC-12③：展开结果里出现了不属于本单根成品闭包的料号 =" + outsiders);

            // ── 现状取证（🚫 不作失败判据，只如实记录）──
            System.out.println("[AC-12② 现状取证] 该递归用【根料号自己那批边里 ORDER BY customer_no LIMIT 1】"
                    + "选定的 _cust 展开子树，而不是按报价单的客户。"
                    + "⇒ 同一根料号挂多客户时，两张不同客户的报价单会拿到【同一个】(customer_no 最小的那个) 客户的子树。"
                    + "本轮实测：选中的是 " + (hasA ? "客户A(" + f.custA() + ")" : hasB ? "客户B(" + f.custB() + ")" : "两者皆非")
                    + "。这属于**递归模板的既有行为**（模板是库里的配置数据，master 与本分支逐字相同）"
                    + "⇒ 🚫 不归因本任务。AC-12② 原文『各自正确』在当前架构下能否成立，请主线裁决。");
        } finally {
            dropCrossFixture(f);
        }
    }
}
