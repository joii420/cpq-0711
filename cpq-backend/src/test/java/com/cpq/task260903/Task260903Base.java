package com.cpq.task260903;

import com.cpq.task260902.SelConfigAcTestBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    protected long dsCustomerPart(String customerNo) {
        return count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='" + customerNo + "'");
    }

    protected long selProductNo(String customerNo) {
        return count("SELECT count(*) FROM sel_product_no WHERE customer_no='" + customerNo + "'");
    }

    /** 两张版本化子表的 {@code _history} 行数（A-AC-5：选配阶段不得升版 ⇒ 零新增）。 */
    protected Map<String, Long> historyCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("ds_quote_material_bom_history", count("SELECT count(*) FROM ds_quote_material_bom_history"));
        m.put("ds_quote_element_bom_history", count("SELECT count(*) FROM ds_quote_element_bom_history"));
        return m;
    }

    /**
     * 🚨 <b>正向对照：先证明「真的写进去了」，之后的「V6 没变 / 没升版」才有意义。</b>
     *
     * <p>{@code test.md §3} 第 2、3 号假绿陷阱：A-AC-2「V6 五表行数不变」与 A-AC-5「version_no 全 1」
     * <b>在提交压根没成功时同样成立</b>（什么都没写，行数当然不变、也没有版本可升）。
     * ⇒ 任何「零新增 / 不变」类断言之前，必须先过这一关。
     *
     * @return 该料号在 {@code ds_quote_material_bom} 的行数（>0）
     */
    protected long assertNewTablesGotRows(String materialNo, String when) {
        long mat = dsMaterial(materialNo);
        long bom = dsMaterialBom(materialNo);
        long elem = dsElementBom(materialNo);
        System.out.println("[" + when + "] 料号 " + materialNo + " 落库：ds_quote_material=" + mat
                + " ds_quote_material_bom=" + bom + " ds_quote_element_bom=" + elem);
        assertEquals(1L, mat,
                when + "：ds_quote_material 应落 1 条料号主档（A-AC-1①），实际 " + mat
                        + " 条。0 条 ⇒ 后面所有『V6 没变 / 没升版』的断言都会因为『压根没写』而假绿");
        assertTrue(bom > 0,
                when + "：ds_quote_material_bom 应落材质行（A-AC-1②），实际 0 行 ⇒ 同上，假绿风险");
        return bom;
    }

    /**
     * 🚨 <b>写了 ≠ 渲染得出来。</b>兼容视图的 BOM 侧要靠 {@code ds_quote_customer_part}
     * 反查 {@code customer_no}；追溯不到时<b>整组 0 行且不报错</b>。
     * ⇒ A-AC-1 只验「新表有行」会漏掉「写了但渲染不出来」这一整类缺陷，故补这一条。
     */
    protected void assertVisibleThroughCompatView(String materialNo, String when) {
        long viaView = count("SELECT count(*) FROM " + COMPAT_MBI + " WHERE material_no='" + materialNo + "'");
        long inTable = dsMaterialBom(materialNo);
        System.out.println("[" + when + "] " + materialNo + " 新表 " + inTable + " 行 → 兼容视图 " + viaView + " 行");
        assertTrue(viaView > 0,
                when + "：料号 " + materialNo + " 在 ds_quote_material_bom 有 " + inTable
                        + " 行，但兼容视图 " + COMPAT_MBI + " 读到 0 行 ⇒ 写进去了却渲染不出来。"
                        + "典型根因：ds_quote_customer_part 没有对应行，兼容视图反查不到 customer_no，"
                        + "于是整组被客户作用域过滤掉（静默，不报错）。A 阶段停写 V6 后这就是报价单空白。");
    }

    // ─────────────── 行主体：COMPOSITE 父件 / 子件必须显式区分（踩坑三次）───────────────

    /**
     * 本轮提交在报价单里落下的全部行：{@code [料号, composite_type, 是否根行]}。
     *
     * <p>🚨 <b>为什么不用 {@code latestLinePartNo()} 也不从提交响应的 JSON 猜字段名</b>：
     * {@code latestLinePartNo()} 在 COMPOSITE 提交下返回的是<b>父料号</b>，本套用例已因此
     * 把断言打错靶三次（见 {@code SelConfigWritesNewTablesTest} 里两处「第三次踩同一个坑」注释）。
     * {@code quotation_line_item.composite_type} 是权威口径 —— 实测取值只有三种：
     * {@code SIMPLE}（根）/ {@code COMPOSITE}（根）/ {@code PART}（子，{@code parent_line_item_id} 非空）。
     */
    protected List<Object[]> lineItemsOf(Fx fx) {
        return rows("SELECT product_part_no_snapshot, composite_type, (parent_line_item_id IS NULL) "
                + "FROM quotation_line_item WHERE quotation_id='" + fx.quotationId() + "' "
                + "ORDER BY composite_type, sort_order");
    }

    /** COMPOSITE <b>主产品（父件）</b>料号。SIMPLE 提交时返回 {@code null}。 */
    protected String compositeParentPartNo(Fx fx) {
        return scalar("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND composite_type='COMPOSITE' "
                + "AND parent_line_item_id IS NULL ORDER BY sort_order LIMIT 1");
    }

    /** COMPOSITE 的<b>子件</b>料号（零件子件 + 外购件子件都在内）。 */
    protected List<String> childPartNos(Fx fx) {
        return col("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND composite_type='PART' "
                + "AND parent_line_item_id IS NOT NULL ORDER BY sort_order")
                .stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    /** 本轮提交<b>铸出的全部料号</b>（COMPOSITE：父 + 全部子；SIMPLE：那一个）。 */
    protected List<String> allCastPartNos(Fx fx) {
        return col("SELECT DISTINCT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND product_part_no_snapshot IS NOT NULL "
                + "ORDER BY 1").stream().map(Object::toString).toList();
    }

    /**
     * 提交<b>之前</b> {@code ds_quote_material} 里已有的料号全集。
     * <p>🚨 用途：证明「本轮确实新造了行」。{@code testing.md §3} 第 3 号陷阱 ——
     * 「所有新料号的 {@code category_code}={@code 000000}」在一行都没新造时同样成立；
     * 而如果断言落在一条<b>存量 IMPORT 行</b>上（它本来就是 {@code 000000}），断言就变成恒真。
     */
    protected Set<String> dsMaterialNoSnapshot() {
        return col("SELECT material_no FROM ds_quote_material").stream()
                .map(String::valueOf).collect(Collectors.toSet());
    }

    /**
     * 断言这些料号<b>都是本轮新造的</b>（提交前不在 {@code ds_quote_material} 里），并落了主档。
     * <p>🚫 少了这一关，A-AC-7/A-AC-11 的取值断言就可能打在存量行上 ⇒ 恒真。
     */
    protected void assertFreshlyCast(Set<String> before, List<String> partNos, String when) {
        assertTrue(!partNos.isEmpty(), when + "：本轮应铸出至少 1 个料号，实际 0 个 ⇒ 后面的取值断言会空跑（假绿）");
        for (String pn : partNos) {
            assertFalse(before.contains(pn),
                    when + "：料号 " + pn + " 在本次提交**之前**就已存在于 ds_quote_material ⇒ "
                            + "它不是「本轮铸出的新料号」，对它断言取值等于验存量数据（恒真风险）。"
                            + "提交前快照共 " + before.size() + " 条。");
            assertEquals(1L, dsMaterial(pn),
                    when + "：本轮铸出的料号 " + pn + " 应在 ds_quote_material 有且仅有 1 条主档，实际 "
                            + dsMaterial(pn) + " 条");
        }
    }

    // ─────────────── 合成外购件（本轮独有，用来验「新建外购件行」）───────────────

    /**
     * 本轮自建的外购件料号，{@link #cleanupSyntheticOutsourced()} 负责删掉。
     */
    protected final List<String> createdOutsourcedNos = new ArrayList<>();

    /**
     * 造一个<b>本轮独有</b>的外购件（{@code material_master.material_type='外购件'}）。
     *
     * <p>🚨 <b>为什么必须自己造</b>：库里存量的外购件<b>只有 {@code TEST-Q13-CODE} 一条</b>，
     * 而它<b>已经在 {@code ds_quote_material} 里</b>（{@code source=IMPORT}，实测 2026-09-04）。
     * 用它跑 A-AC-7①/A-AC-11 时本次提交根本不会创建它 ⇒ 「新建的外购件行带不带
     * {@code material_type} / {@code category_code}」这一问<b>无从验起</b>，
     * 上一轮只能 {@code Assumptions.abort} 记成「未验证」。造一条本轮独有的，
     * 就把「未验证」变成真验证。
     *
     * <p>⚠️ {@code material_master.material_no} 是 {@code varchar(20)} ⇒ 名字必须短：
     * {@code T260902-OS-} + 6 位 RUN_ID = 17 字符。
     */
    protected String createSyntheticOutsourcedPart() {
        String no = PREFIX + "OS-" + RUN_ID;             // 17 字符，卡在 varchar(20) 以内
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "INSERT INTO material_master (id,material_no,material_name,material_type,created_at,updated_at) "
                                + "SELECT gen_random_uuid(),:no,:nm,'外购件',NOW(),NOW() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM material_master WHERE material_no=:no)")
                .setParameter("no", no).setParameter("nm", PREFIX + "合成外购件").executeUpdate());
        assertEquals(1L, count("SELECT count(*) FROM material_master WHERE material_no='" + no
                        + "' AND material_type='外购件'"),
                "前置自检：合成外购件 " + no + " 应已建好且 material_type='外购件'");
        if (!createdOutsourcedNos.contains(no)) createdOutsourcedNos.add(no);
        System.out.println("[合成外购件] 本轮自建 " + no + "（存量外购件只有 TEST-Q13-CODE 且已在 ds_quote_material，用它验不了新建行）");
        return no;
    }

    /**
     * 删掉本轮自建的外购件。
     * <p>🚫 每条 DELETE 都收敛到「本轮 RUN_ID 的那一个料号」+ {@code source='MANUAL'}（选配唯一来源），
     * 不存在无 WHERE 的删除，不碰任何存量数据。删除行数打印出来，人能看见。
     * <p>📌 JUnit 5 里子类 {@code @AfterEach} 先于父类执行 ⇒ 这里跑在
     * {@code SelConfigAcTestBase#restoreFixtures} 之前；合成外购件不出现在父类的
     * {@code partNos} 反查里（它只作 {@code input_material_no}，不作 {@code material_no}），
     * 所以必须自己收。
     */
    @AfterEach
    void cleanupSyntheticOutsourced() {
        if (createdOutsourcedNos.isEmpty()) return;
        List<String> nos = List.copyOf(createdOutsourcedNos);
        createdOutsourcedNos.clear();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (String no : nos) {
                    int el = em.createNativeQuery("DELETE FROM ds_quote_element_bom WHERE material_no=:n AND source='MANUAL'")
                            .setParameter("n", no).executeUpdate();
                    int bom = em.createNativeQuery("DELETE FROM ds_quote_material_bom WHERE material_no=:n AND source='MANUAL'")
                            .setParameter("n", no).executeUpdate();
                    int mat = em.createNativeQuery("DELETE FROM ds_quote_material WHERE material_no=:n AND source='MANUAL'")
                            .setParameter("n", no).executeUpdate();
                    int mm = em.createNativeQuery(
                                    "DELETE FROM material_master mm WHERE mm.material_no=:n "
                                            + "AND NOT EXISTS (SELECT 1 FROM material_bom_item b WHERE b.material_no=mm.material_no) "
                                            + "AND NOT EXISTS (SELECT 1 FROM material_customer_map m WHERE m.material_no=mm.material_no) "
                                            + "AND NOT EXISTS (SELECT 1 FROM sel_part_signature s WHERE s.quote_part_no=mm.material_no)")
                            .setParameter("n", no).executeUpdate();
                    System.out.println("[还原] 合成外购件 " + no + " 清理：element_bom=" + el + " material_bom=" + bom
                            + " ds_quote_material=" + mat + " material_master=" + mm);
                }
            });
        } catch (RuntimeException e) {
            System.out.println("[还原] 🚨 合成外购件清理失败（需主线登记残留）：" + nos + " → " + e);
        }
        for (String no : nos) {
            long left = count("SELECT count(*) FROM material_master WHERE material_no='" + no + "'")
                    + count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + no + "'");
            assertEquals(0L, left, "还原自检：本轮合成外购件 " + no + " 仍有 " + left
                    + " 行残留在共享库（material_master / ds_quote_material）⇒ 必须登记给主线");
        }
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

    // ─────────────────────────── 残留登记（🚫 不自行删除）───────────────────────────

    /**
     * 🚨 <b>A 阶段用例会在共享库 {@code cpq_db_0724} 留下 {@code ds_quote_*} 残留，本方法只<b>登记</b>不删除。</b>
     *
     * <p>原因有二：
     * <ol>
     *   <li>父类 {@link com.cpq.task260902.SelConfigAcTestBase} 的 {@code @AfterEach} 是为 task-260902 写的，
     *       它清 V6 与 {@code sel_product_no}，<b>不认识 {@code ds_quote_*}</b>（那时这些表还不在它的射程内）。</li>
     *   <li>{@code DELETE} 属 {@code CLAUDE.md §3.2} 红线，<b>子代理没有批准权</b>。
     *       ⇒ 这里打印精确的清理 SQL 与命中行数，由主线报用户批准后执行。</li>
     * </ol>
     *
     * <p>残留是<b>可定位</b>的：{@code customer_no} 形如 {@code T2609}+uuid，每轮唯一，不会撞存量数据。
     * 🚫 但「可定位」不等于「可以不管」—— 不登记的话，下一个人会把它当成业务数据。
     */
    @AfterEach
    void reportDsQuoteResidue() {
        List<String> custNos = fixtures.stream().map(Fx::customerNo).toList();
        if (custNos.isEmpty()) return;
        String inList = custNos.stream().map(c -> "'" + c + "'").reduce((a, b) -> a + "," + b).orElse("''");
        long cp = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no IN (" + inList + ")");
        long mat = count("SELECT count(*) FROM ds_quote_material WHERE material_no IN "
                + "(SELECT material_no FROM ds_quote_customer_part WHERE customer_no IN (" + inList + "))");
        if (cp == 0 && mat == 0) {
            System.out.println("[残留登记] 本用例未在 ds_quote_* 留下行");
            return;
        }
        System.out.println("[残留登记] 🚨 本用例在共享库留下 ds_quote_* 残留（🚫 未删除，需主线批准）：\n"
                + "    ds_quote_customer_part = " + cp + " 行\n"
                + "    ds_quote_material      = " + mat + " 行\n"
                + "  清理 SQL（交主线走 §3.2 三步前置后执行）：\n"
                + "    DELETE FROM ds_quote_customer_part WHERE customer_no IN (" + inList + ");");
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
