package com.cpq.task260903;

import com.cpq.task260902.SelConfigAcTestBase;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260903「选配主数据迁至 ds_quote_* 新表体系」验收用例的公共基座。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260903-选配主数据迁新表/需求文档.md §4} 的 AC 原文，
 * 列映射指回同目录 {@code api.md §2}。<b>🚫 不读实现</b>：本套用例一行都不从
 * {@code com.cpq.configure.**} 反推，只按 AC 写断言。
 *
 * <h3>为什么继承 {@code task260902} 的基座而不是另起一套</h3>
 * {@link SelConfigAcTestBase} 已有真实登录（选配端点类级 {@code @RoleAllowed}，不登录会被挡在业务层外，
 * 而那会让断言以「业务失败」的面目失败）、{@code assertReachedBusinessLayer} 假绿守卫、
 * {@code @AfterEach} 精确还原。重写一套只会漂移。
 *
 * <h3>🚨 前缀口径（{@code test-report.md} 必须登记这一条）</h3>
 * 父类的 {@code PREFIX} 是 {@code static final "T260902-"}、{@code customer_no} 是 {@code "T2609"+uuid}，
 * <b>子类无法覆盖</b>。⇒ <b>本任务自建数据的实际前缀是 {@code T260902-} / {@code T2609}，不是 {@code T260903-}。</b>
 * ⚠️ 收尾核对若按 {@code test.md §5} 写的 {@code LIKE 'T260903%'} 去查，会得到 <b>0 条</b> ——
 * 那是<b>查错了前缀</b>，不是「很干净」。这正是本任务要防的那类假绿，特此留痕。
 *
 * <h3>环境纪律（{@code test.md §0}）</h3>
 * {@code ./mvnw test} 直接写共享开发库 {@code cpq_db_0724}。🚫 不清库、不 TRUNCATE、不 DROP。
 * 造新表数据一律走 {@link #inRollback}（<b>永不提交</b>，零残留，连 DELETE 都不需要）。
 */
public abstract class Task260903Base extends SelConfigAcTestBase {

    /** 「不双写」守卫盯的 V6 五表（需求文档 A-AC-2 原文列举，顺序照抄）。 */
    protected static final List<String> V6_TABLES = List.of(
            "material_master", "material_bom", "material_bom_item", "element_bom", "element_bom_item");

    /** 阶段 B 的三张兼容视图（api.md §2）。 */
    protected static final String COMPAT_MBI = "v_compat_material_bom_item";
    protected static final String COMPAT_MM  = "v_compat_material_master";
    protected static final String COMPAT_EBI = "v_compat_element_bom_item";

    /** 本任务在新表里造的测试料号前缀 —— 走 {@link #inRollback} 永不提交，仅作可读性标记。 */
    protected static final String DS_MARK = "T260903-";

    // ─────────────────────────── V6 零新增守卫 ───────────────────────────

    /** V6 五表的当前行数快照。 */
    protected Map<String, Long> v6Counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : V6_TABLES) m.put(t, count("SELECT count(*) FROM " + t));
        return m;
    }

    /**
     * A-AC-2：V6 五表行数不变。
     * <p>🚨 <b>本方法必须在「已证明提交成功且新表真落了行」之后才调用</b> ——
     * {@code test.md §3} 第 2 号假绿陷阱：提交失败时什么都没写，行数当然不变，
     * 于是「不双写」会因为「压根没写」而通过。调用方有责任先打正向断言。
     */
    protected void assertV6Unchanged(Map<String, Long> before, String when) {
        Map<String, Long> after = v6Counts();
        System.out.println("[" + when + "] V6 五表行数 before=" + before + " after=" + after);
        for (String t : V6_TABLES) {
            assertEquals(before.get(t), after.get(t),
                    when + "：A-AC-2 要求 V6 五表零新增（不双写），但 " + t + " 从 "
                            + before.get(t) + " 变成 " + after.get(t)
                            + " ⇒ 选配仍在写 V6。before=" + before + " after=" + after);
        }
    }

    // ─────────────────────────── 新表计数 ───────────────────────────

    protected long dsMaterial(String materialNo) {
        return count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + materialNo + "'");
    }

    protected long dsMaterialBom(String materialNo) {
        return count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='" + materialNo + "'");
    }

    protected long dsElementBom(String materialNo) {
        return count("SELECT count(*) FROM ds_quote_element_bom WHERE material_no='" + materialNo + "'");
    }

    // ─────────────────────────── 前置存在性 ───────────────────────────

    /** 视图/表是否存在（{@code to_regclass} 对不存在的对象返 NULL，不抛错）。 */
    protected boolean relationExists(String name) {
        return scalar("SELECT to_regclass('public." + name + "')::text") != null;
    }

    /**
     * 🚨 前置硬检查：兼容视图不存在时<b>立刻硬失败并说清原因</b>，
     * 而不是让后面的查询抛一句 {@code relation does not exist} —— 那句报错长得像业务缺陷，
     * 读报告的人会去查业务代码。
     */
    protected void requireCompatViews() {
        for (String v : List.of(COMPAT_MBI, COMPAT_MM, COMPAT_EBI)) {
            assertTrue(relationExists(v),
                    "前置未满足：兼容视图 " + v + " 不存在 ⇒ V410 尚未应用到 " + "cpq_db_0724。"
                            + "这是**环境前置缺失**，不是被测功能的结论。"
                            + "请先让后端把 V410 落库（起一次后端即 migrate-at-start），再跑本用例。");
        }
    }

    // ─────────────────────────── 事务内造数 + 回滚 ───────────────────────────

    /** {@link #inRollback} 用来触发回滚的哨兵，不代表失败。 */
    private static final class RollbackSignal extends RuntimeException {
        RollbackSignal() { super(null, null, false, false); }
    }

    /**
     * 在一个<b>永不提交</b>的新事务里执行 {@code body}：造数 + 断言都在里面，结束后整体回滚。
     *
     * <p>🚨 这是 {@code test.md §3} 第 1 号假绿陷阱的解药：B 阶段采基线时新表是空的，
     * {@code UNION ALL} 的新表侧是空集 —— 此时 diff 全绿只证明「V6 侧没被改坏」，
     * <b>没有证明新表侧映射正确</b>。必须自己造新表数据再验。
     *
     * <p>🚫 不用「INSERT + finally DELETE」：共享库上 DELETE 属 {@code CLAUDE.md §3.2} 红线，
     * 而且用例中途崩溃时 finally 也可能跑不到。回滚是构造性零残留，不依赖任何清理动作。
     */
    protected void inRollback(Runnable body) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                body.run();
                throw new RollbackSignal();   // 断言已全部跑完 → 主动回滚
            });
        } catch (RollbackSignal ignored) {
            // 正常路径：数据已回滚
        }
    }

    /** 造一行 {@code ds_quote_material_bom}（列名照 api.md §2.1 的新表侧）。 */
    protected void insertDsMaterialBom(String materialNo, int itemSeq, String inputMaterialNo,
                                       String outputMaterialType, String ratio) {
        em.createNativeQuery(
                "INSERT INTO ds_quote_material_bom "
                        + "(material_no,item_seq,input_material_no,output_material_type,material_ratio,"
                        + " version_no,row_fingerprint,source,created_at) "
                        + "VALUES (:mn,:seq,:in,:omt,CAST(:r AS numeric),1,:fp,'TEST',now())")
                .setParameter("mn", materialNo)
                .setParameter("seq", itemSeq)
                .setParameter("in", inputMaterialNo)
                .setParameter("omt", outputMaterialType)
                .setParameter("r", ratio)
                // row_fingerprint 是 char(N) NOT NULL；本行永不提交，指纹只需占位且唯一
                .setParameter("fp", String.format("%064x", (materialNo + itemSeq + inputMaterialNo).hashCode() & 0xffffffffL))
                .executeUpdate();
    }

    /** 造一行 {@code ds_quote_customer_part}（兼容视图靠它 JOIN 出 {@code customer_no}）。 */
    protected void insertDsCustomerPart(String customerNo, String customerProductNo, String materialNo) {
        em.createNativeQuery(
                "INSERT INTO ds_quote_customer_part "
                        + "(customer_no,customer_product_no,material_no,source,created_at) "
                        + "VALUES (:cn,:cpn,:mn,'TEST',now())")
                .setParameter("cn", customerNo)
                .setParameter("cpn", customerProductNo)
                .setParameter("mn", materialNo)
                .executeUpdate();
    }

    /** 造一行 {@code ds_quote_material}（料号主档）。 */
    protected void insertDsMaterial(String materialNo, String materialName) {
        em.createNativeQuery(
                "INSERT INTO ds_quote_material (material_no,material_name,source,created_at) "
                        + "VALUES (:mn,:nm,'TEST',now())")
                .setParameter("mn", materialNo).setParameter("nm", materialName).executeUpdate();
    }
}
