package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>task-260907 · AC-1（客户隔离真正生效，且能被证伪）。</b>
 *
 * <h3>AC-1 原文（需求文档.md 第 3 节）</h3>
 * 前置：costing_bom_tree_config 的 QUOTE 递归接上客户隔离后。<br>
 * 现网数据验不出阳性：V6 老表 QUOTE + is_current 中，<b>跨多客户的料号 0 个</b>、
 * <b>子件集合因客户而异的料号 0 个</b>，「隔离有没有生效」在当前数据下无法证伪，
 * 断言会以「通过」的形态空跑。<br>
 * 操作：<b>用例自造前缀化夹具</b> —— 同一料号挂在两个客户下，各自子件不同。<br>
 * 断言：(1) 客户 A 的报价单只展开出 A 的子件；(2) 客户 B 的只展开出 B 的；(3) 两者互不出现对方的子件。
 *
 * <h3>本类怎么把「客户 A 的报价单」这件事落成可执行判据</h3>
 * costing_bom_tree_config 是<b>全系统共用的一份配置化递归</b>，报价侧的树就是它跑出来的。
 * 本类直接执行那份 sql_template（与 task-260907-取数配置器补齐 的 AC-12 同法），
 * 把客户维度按模板<b>自己声明的入参</b>绑进去。模板若根本没有客户入参，
 * 「客户 A 的报价单」和「客户 B 的报价单」就会拿到<b>完全相同的一次展开</b> ——
 * 那时 AC-1(1)(2) 不可能同时成立，本类会把这件事硬失败并写清缘由，交主线裁决，
 * <b>不许把断言改弱成「只要不同时出现两家子件就算过」</b>。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-1 递归换表后客户隔离真正生效（自造跨客户夹具）")
class CustDimRecursionAcTest extends CustDimBase {

    private static final String ROOT = SHORT + "R";
    private static final String CHILD_A = SHORT + "A1";
    private static final String CHILD_B = SHORT + "B1";

    private String quoteTemplate() {
        String tpl = scalar("SELECT sql_template FROM costing_bom_tree_config "
                + "WHERE usage='QUOTE' AND is_active = true");
        assertNotNull(tpl, "AC-1 前置：costing_bom_tree_config 里没有 usage='QUOTE' 且 is_active 的行 —— "
                + "全系统共用的那份递归不存在，报价侧树渲染整体不可用。这是地基故障，不是 AC 结论。");
        assertFalse(tpl.isBlank(), "AC-1 前置：QUOTE 递归模板是空串，「含 customer_no」恒假、"
                + "「不含对方子件」恒真，两侧都不可信。");
        return tpl;
    }

    // ============================================================
    // 夹具：同一根料号在两个客户下各挂不同子件
    // ============================================================

    /** 两个自造客户号（varchar(20) 上限，值全由本轮生成，@AfterEach 精确删）。 */
    private static final String FX_CUST_A = SHORT + "CA";
    private static final String FX_CUST_B = SHORT + "CB";

    private void buildCrossCustomerFixture(boolean intoV6, boolean intoDs) {
        for (String v : List.of(ROOT, CHILD_A, CHILD_B, FX_CUST_A, FX_CUST_B)) {
            assertTrue(v.length() <= 20, "夹具自检：值 " + v + " 长 " + v.length()
                    + " > 20，写 V6 material_bom_item 会报 value too long（那个错长得像业务缺陷）");
        }
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE material_no='" + ROOT
                        + "' OR component_no='" + ROOT + "'"),
                "夹具自检：根料号 " + ROOT + " 竟已存在于 V6 表，断言会被存量行冒充");

        QuarkusTransaction.requiringNew().run(() -> {
            if (intoV6) {
                insertV6(FX_CUST_A, ROOT, CHILD_A, 10);
                insertV6(FX_CUST_B, ROOT, CHILD_B, 10);
            }
            if (intoDs) {
                insertDs(FX_CUST_A, ROOT, CHILD_A, 10);
                insertDs(FX_CUST_B, ROOT, CHILD_B, 10);
            }
        });

        // 阳性对照：两条边真的落库了，且确实是「同一料号、两个客户」
        if (intoV6) {
            assertEquals(2L, count("SELECT count(*) FROM material_bom_item WHERE material_no='" + ROOT + "'"),
                    "夹具自检(V6)：根料号应有 2 条边（两个客户各一条）");
            assertEquals(2L, count("SELECT count(DISTINCT customer_no) FROM material_bom_item "
                            + "WHERE material_no='" + ROOT + "'"),
                    "夹具自检(V6)：这 2 条边应分属 2 个不同客户，否则验不到跨客户");
        }
        if (intoDs) {
            assertEquals(2L, count("SELECT count(*) FROM " + T_MATERIAL_BOM
                            + " WHERE material_no='" + ROOT + "'"),
                    "夹具自检(新表)：根料号应有 2 条边");
            assertEquals(2L, count("SELECT count(DISTINCT customer_no) FROM " + T_MATERIAL_BOM
                            + " WHERE material_no='" + ROOT + "'"),
                    "夹具自检(新表)：这 2 条边应分属 2 个不同客户");
        }
        System.out.println("[AC-1 夹具] root=" + ROOT + "  " + FX_CUST_A + "->" + CHILD_A
                + "  " + FX_CUST_B + "->" + CHILD_B + "  (V6=" + intoV6 + ", 新表=" + intoDs + ")");
    }

    private void insertV6(String customerNo, String parent, String child, int seq) {
        em.createNativeQuery("INSERT INTO material_bom_item (id,system_type,customer_no,material_no,"
                        + "component_no,item_seq,is_current,composition_qty,created_at,updated_at) "
                        + "VALUES (gen_random_uuid(),'QUOTE',:cn,:p,:c,:s,true,1,NOW(),NOW())")
                .setParameter("cn", customerNo).setParameter("p", parent)
                .setParameter("c", child).setParameter("s", seq).executeUpdate();
    }

    private void insertDs(String customerNo, String parent, String child, int seq) {
        em.createNativeQuery("INSERT INTO " + T_MATERIAL_BOM + " (customer_no,material_no,item_seq,"
                        + "input_material_no,component_qty,version_no,row_fingerprint,source,created_at) "
                        + "VALUES (:cn,:p,:s,:c,1,1,:fp,'IMPORT',NOW())")
                .setParameter("cn", customerNo).setParameter("p", parent)
                .setParameter("c", child).setParameter("s", seq)
                .setParameter("fp", md5(customerNo + "|" + parent + "|" + child) + md5(child + "|" + parent))
                .executeUpdate();
    }

    private void dropCrossCustomerFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM material_bom_item WHERE customer_no IN (:a,:b)")
                    .setParameter("a", FX_CUST_A).setParameter("b", FX_CUST_B).executeUpdate();
            if (columnExists(T_MATERIAL_BOM, "customer_no")) {
                em.createNativeQuery("DELETE FROM " + T_MATERIAL_BOM + " WHERE customer_no IN (:a,:b)")
                        .setParameter("a", FX_CUST_A).setParameter("b", FX_CUST_B).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM " + T_MATERIAL_BOM + " WHERE material_no IN (:r,:a,:b)"
                            + " OR input_material_no IN (:a,:b)")
                    .setParameter("r", ROOT).setParameter("a", CHILD_A).setParameter("b", CHILD_B)
                    .executeUpdate();
        });
        assertEquals(0L, count("SELECT count(*) FROM material_bom_item WHERE customer_no IN ('"
                + FX_CUST_A + "','" + FX_CUST_B + "')"), "AC-1 还原自检：V6 跨客户夹具仍有残留");
        assertEquals(0L, count("SELECT count(*) FROM " + T_MATERIAL_BOM + " WHERE material_no = '"
                + ROOT + "'"), "AC-1 还原自检：新表跨客户夹具仍有残留");
    }

    // ============================================================
    // AC-1 主体
    // ============================================================

    @Test
    @DisplayName("AC-1：同一料号挂两个客户、子件不同 —— 客户A的展开只含A的子件，客户B的只含B的，互不出现对方的")
    void ac1_crossCustomerExpansionIsIsolated() {
        String tpl = quoteTemplate();
        System.out.println("[AC-1] QUOTE 递归模板（" + tpl.length() + " 字符）:\n" + tpl);

        // 前置一：隔离条件还在（B-7 明写「换表 + 接上客户隔离 JOIN」，不许换表时丢掉维度）
        assertTrue(tpl.contains("customer_no"),
                "AC-1 前置：QUOTE 递归 SQL 里已经没有 customer_no —— 客户隔离维度被删掉了。"
                        + "用户 2026-09-07 的裁决是【把它补进新表体系】，不是丢掉。\nSQL=\n" + tpl);

        boolean readsV6 = tpl.contains("material_bom_item");
        boolean readsDs = tpl.contains(T_MATERIAL_BOM);
        boolean readsCompat = tpl.contains("v_compat_material_bom_item");
        System.out.println("[AC-1] 递归读 V6=" + readsV6 + " 读新表=" + readsDs + " 读兼容视图=" + readsCompat);
        assertTrue(readsV6 || readsDs || readsCompat,
                "AC-1 前置：递归既不读 V6、也不读新表、也不读兼容视图，读的是第三张表，"
                        + "与任务文档描述都不符，停下来报主线。\nSQL=\n" + tpl);

        // 夹具落在递归实际读的那一侧；读兼容视图时两侧都落（视图是 V6 UNION ALL ds_，V6 优先）
        boolean dsHasCustomer = columnExists(T_MATERIAL_BOM, "customer_no");
        boolean intoDs = (readsDs || readsCompat) && dsHasCustomer;
        boolean intoV6 = readsV6 || readsCompat || !dsHasCustomer;
        if ((readsDs || readsCompat) && !dsHasCustomer) {
            System.out.println("[AC-1] 递归读新表/兼容视图，但 " + T_MATERIAL_BOM
                    + " 还没有 customer_no 列（AC-3 会先红）；本条夹具退到 V6 侧构造。");
        }

        buildCrossCustomerFixture(intoV6, intoDs);
        try {
            List<String> params = remainingParams(tpl);
            System.out.println("[AC-1] 模板里除 :production_part_nos 外的入参 = " + params);

            String custParam = params.stream()
                    .filter(p -> p.toLowerCase().contains("cust"))
                    .findFirst().orElse(null);

            if (custParam == null) {
                // 阳性对照先跑：证明这份递归对本夹具确实能展开，再谈它为什么不满足 AC-1
                Set<String> once = expand(tpl, null, null);
                assertFalse(once.isEmpty(),
                        "AC-1：递归对本夹具展开为空集 —— 「不互串」会恒真通过（空跑）。根=" + ROOT);
                assertTrue(once.contains(ROOT),
                        "AC-1 阳性对照：展开集合里连根料号自己都没有，递归没跑通，任何隔离结论都不可信。实际=" + once);
                assertTrue(once.size() > 1,
                        "AC-1 阳性对照：展开集合只有根一个元素（" + once + "），递归一层都没展开，"
                                + "「各自正确」验不到。先检查夹具的 is_current / system_type / version。");
                System.out.println("[AC-1] 单次展开（无客户入参）= " + once);

                fail("AC-1 无法达成 —— 这份递归<b>没有客户入参</b>：模板里除 :production_part_nos 之外的入参 = "
                        + params + "，客户是靠根料号自己那批边里 ORDER BY customer_no LIMIT 1 推出来的（_cust）。"
                        + "\n  后果：客户 " + FX_CUST_A + " 的报价单与客户 " + FX_CUST_B
                        + " 的报价单会拿到【完全相同的一次展开】" + once
                        + "，其中恰好只含 customer_no 最小的那家的子件。"
                        + "\n  AC-1(1)(2) 要求两家各自正确，在当前形状下不可能同时成立。"
                        + "\n  🚦 这与 test.md 第 6 节登记的「已知的非本任务问题①」是同一件事，"
                        + "但 AC-1 又把它写成了本任务的验收判据 —— 两者冲突，请主线裁决："
                        + "要么 B-7 给递归加客户入参，要么把 AC-1 改写成可达成的形态。"
                        + "\n  🚫 本类不擅自把断言改弱成「只要不同时出现两家子件就算过」。");
            }

            // 有客户入参 ⇒ 按 AC-1 原文逐条验
            Set<String> byA = expand(tpl, custParam, FX_CUST_A);
            Set<String> byB = expand(tpl, custParam, FX_CUST_B);
            System.out.println("[AC-1] 客户A(" + FX_CUST_A + ") 展开 = " + byA);
            System.out.println("[AC-1] 客户B(" + FX_CUST_B + ") 展开 = " + byB);

            // 守卫在被守卫对象上游：先要求集合非空且含根，再谈集合内元素合格
            assertFalse(byA.isEmpty(), "AC-1(1)：客户A 展开为空集，「只含A的子件」恒真（空跑）");
            assertFalse(byB.isEmpty(), "AC-1(2)：客户B 展开为空集，「只含B的子件」恒真（空跑）");
            assertTrue(byA.contains(ROOT) && byB.contains(ROOT),
                    "AC-1 阳性对照：展开集合里没有根料号，递归没跑通。A=" + byA + " B=" + byB);
            assertTrue(byA.size() > 1 && byB.size() > 1,
                    "AC-1 阳性对照：至少一侧一层都没展开（A=" + byA + " B=" + byB + "），隔离验不到");

            assertTrue(byA.contains(CHILD_A),
                    "AC-1(1)：客户A 的展开里没有 A 自己的子件 " + CHILD_A + "，实际=" + byA);
            assertFalse(byA.contains(CHILD_B),
                    "AC-1(3)：客户A 的展开里出现了客户B 的子件 " + CHILD_B + " —— 跨客户串号。实际=" + byA);
            assertTrue(byB.contains(CHILD_B),
                    "AC-1(2)：客户B 的展开里没有 B 自己的子件 " + CHILD_B + "，实际=" + byB);
            assertFalse(byB.contains(CHILD_A),
                    "AC-1(3)：客户B 的展开里出现了客户A 的子件 " + CHILD_A + " —— 跨客户串号。实际=" + byB);

            Set<String> allowed = new LinkedHashSet<>(List.of(ROOT, CHILD_A, CHILD_B));
            List<String> outsiders = new ArrayList<>();
            byA.stream().filter(x -> !allowed.contains(x)).forEach(outsiders::add);
            byB.stream().filter(x -> !allowed.contains(x)).forEach(outsiders::add);
            assertTrue(outsiders.isEmpty(),
                    "AC-1：展开结果里出现了不属于本单根成品闭包的料号 = " + outsiders);
        } finally {
            dropCrossCustomerFixture();
        }
    }

    /**
     * 模板里除 :production_part_nos 外还剩哪些 :xxx 入参。
     *
     * <p>负向前瞻 {@code (?<!:)} 必须有：PG 的类型转换写作 {@code p::text}，
     * 不排除的话会把 {@code text} 认成一个入参 —— 2026-09-07 首跑实测被这个假入参打断，
     * 报出来的「还有未绑定的入参 [text]」<b>长得像契约缺口，其实是正则写错</b>。
     */
    private List<String> remainingParams(String tpl) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![:\\w]):([A-Za-z_][A-Za-z0-9_]*)").matcher(tpl);
        while (m.find()) {
            String p = m.group(1);
            if (!"production_part_nos".equals(p) && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 执行递归模板，返回展开出的 material_no 集合。 */
    private Set<String> expand(String tpl, String custParam, String custValue) {
        String sql = tpl.replace(":production_part_nos",
                "ARRAY['" + ROOT.replace("'", "''") + "']::text[]");
        assertFalse(sql.contains(":production_part_nos"), "AC-1：轴参数替换失败");
        if (custParam != null) {
            sql = sql.replace(":" + custParam, "'" + custValue.replace("'", "''") + "'");
        }
        // 仍有未绑定的入参就不要硬跑 —— 报错会长得像业务缺陷
        List<String> left = remainingParams(sql);
        assertTrue(left.isEmpty(),
                "AC-1：递归模板还有未绑定的入参 " + left + "，本类无法在不读实现的前提下自行推断其取值。"
                        + "停下来报主线：需要契约说明这些参数的绑定口径。\nSQL=\n" + sql);
        Set<String> out = new LinkedHashSet<>();
        for (Object[] r : rows("SELECT material_no::text, coalesce(parent_no,'')::text FROM (" + sql + ") t")) {
            out.add(String.valueOf(r[0]));
            System.out.println("    展开：料号=" + r[0] + " 父=" + r[1]);
        }
        return out;
    }
}
