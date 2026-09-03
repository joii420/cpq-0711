package com.cpq.task260901;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260901「材质管理模块定义规则更新」验收测试的公共基座。
 *
 * <p><b>本类只做两件事：全局状态还原 + 还原自检。</b>断言一律回到 {@code 需求文档.md §③} 的 AC 原文，
 * 本类不含任何业务判据。
 *
 * <h3>🚨 全局状态纪律（test.md §1）</h3>
 * 材质库 / 元素主表是共享库 {@code 10.177.152.12:5432/cpq_db}（test profile）里的<b>公共基础数据</b>，
 * 不是临时资源。本套用例会往里写东西，因此：
 * <ul>
 *   <li>还原写在 {@link #restoreGlobalState()}（{@code @AfterEach}，等价于 finally），
 *       <b>不依赖「跑完手工清一下」</b> —— 用例中途失败照样清。</li>
 *   <li>🚫 <b>不清库、不 TRUNCATE、不 DROP</b>。删除面被 {@code symbol LIKE 'AC测%'} /
 *       {@code element_code='Xx'} / {@code config_no <> '00006-01'} 三条严格谓词限死。</li>
 *   <li>🚨 <b>{@code 00006-01} 是 S-6 存量迁移产出的真实数据，任何路径都不许删</b>；
 *       {@code 00006} 的元素组成（Ag + Ni）任何路径都不许改 —— 见 {@link #assertNoResidue()}。</li>
 * </ul>
 *
 * <h3>⚠️ 为什么断言不写死 {@code 00263 / 10096}</h3>
 * AC-3 / AC-6 的字面编号（{@code 00263} / {@code 10096}）是<b>相对 dev 库 {@code cpq_db_0724}</b>
 * 的前置库态（max=00262 / 10095）推出来的。而 {@code mvnw test} 走的是 <b>test 库 {@code cpq_db}</b>，
 * 实测 max5=99901、maxElementNo=90002（历史测试残留），字面值必然不成立。
 * 因此本套接口层用例一律先取<b>当前基线</b>再断言「基线+1 / +2 / +3 连续、无空号」——
 * 这与 AC 的可观测语义（"00262 + 1"、"最大值 + 1"）完全等价。
 * <b>字面编号 00263/10096 由主线在 dev 库亲验时确认</b>（test-report 会点名）。
 */
public abstract class MaterialAcTestBase {

    /** 本套用例专用的材质名前缀。删除面靠它限死，别改。 */
    protected static final String AC_PREFIX = "AC测";

    /** 真实存量材质，只读基线：AgNi10 / 元素组成 Ag + Ni / 已有配置 00006-01。 */
    protected static final String REAL_RECIPE_CODE = "00006";
    protected static final String REAL_RECIPE_SYMBOL = "AgNi10";
    /** 存量迁移产出的真实配置，🚨 任何路径都不许删。 */
    protected static final String PROTECTED_CONFIG_NO = "00006-01";

    @Inject
    protected EntityManager em;

    /** 用例执行前的基线，供「基线+N」断言与残留自检使用。 */
    protected String baselineMaxCode5;
    protected long baselineMaxElementNo;
    protected long baselineRecipeCount;

    /** 用例显式登记的、需要还原的额外 configNo（精确删除用）。 */
    protected final Set<String> createdConfigNos = new LinkedHashSet<>();

    @BeforeEach
    void captureBaseline() {
        // 先清一次：上一轮若中途崩溃留下残渣，这里兜底，避免「上轮残留」被误读成「本轮 bug」
        restoreGlobalState();
        // 🚨 清完立刻自检（2026-09-02 由 FT-3 证伪实验驱动补上）：
        //    FT-3 实测第二轮的**首要**失败是业务断言「AC-3 期望 00266 实际 00263」，
        //    还原自检只作为 Suppressed 挂在后面 —— 读日志的人会判成「发号坏了」（业务回归），
        //    而真因是上一轮的残留。把自检提到用例正文之前，脏库就以「残留」的名义硬失败，
        //    不会再伪装成业务缺陷。🚫 别把这行挪走。
        assertNoResidue();
        baselineMaxCode5 = maxRecipeCode5();
        baselineMaxElementNo = maxNumericElementNo();
        baselineRecipeCount = count("SELECT count(*) FROM material_recipe");
        System.out.printf("[task260901] baseline: maxCode5=%s maxElementNo=%d recipeCount=%d%n",
                baselineMaxCode5, baselineMaxElementNo, baselineRecipeCount);
    }

    @AfterEach
    void tearDownAndSelfCheck() {
        try {
            restoreGlobalState();
        } finally {
            assertNoResidue();
        }
    }

    // ─────────────────────────── 还原 ───────────────────────────

    /**
     * 还原本套用例可能改动的全部全局状态。
     * 🚫 三条谓词都是收敛的，不存在「无 WHERE 的 DELETE」。
     */
    protected void restoreGlobalState() {
        QuarkusTransaction.requiringNew().run(() -> {
            // ① AC测% 系列材质 + 三张子表（不依赖 FK cascade 是否已建，显式按序删）
            if (tableExists("material_recipe_element")) {
                if (columnExists("material_recipe_element", "config_id")) {
                    em.createNativeQuery(
                        "DELETE FROM material_recipe_element WHERE config_id IN " +
                        "(SELECT c.id FROM material_recipe_config c JOIN material_recipe r ON r.id = c.recipe_id " +
                        " WHERE r.symbol LIKE :p)")
                      .setParameter("p", AC_PREFIX + "%").executeUpdate();
                }
                if (columnExists("material_recipe_element", "recipe_id")) {
                    em.createNativeQuery(
                        "DELETE FROM material_recipe_element WHERE recipe_id IN " +
                        "(SELECT id FROM material_recipe WHERE symbol LIKE :p)")
                      .setParameter("p", AC_PREFIX + "%").executeUpdate();
                }
            }
            if (tableExists("material_recipe_config")) {
                em.createNativeQuery(
                    "DELETE FROM material_recipe_config WHERE recipe_id IN " +
                    "(SELECT id FROM material_recipe WHERE symbol LIKE :p)")
                  .setParameter("p", AC_PREFIX + "%").executeUpdate();
            }
            if (tableExists("material_recipe_composition")) {
                em.createNativeQuery(
                    "DELETE FROM material_recipe_composition WHERE recipe_id IN " +
                    "(SELECT id FROM material_recipe WHERE symbol LIKE :p)")
                  .setParameter("p", AC_PREFIX + "%").executeUpdate();
            }
            em.createNativeQuery("DELETE FROM material_recipe WHERE symbol LIKE :p")
              .setParameter("p", AC_PREFIX + "%").executeUpdate();

            // ② 挂在真实材质 00006 上的测试配置：按 configNo 精确删，
            //    🚨 硬排除 00006-01（存量迁移的真实数据）
            if (tableExists("material_recipe_config")) {
                if (tableExists("material_recipe_element")
                        && columnExists("material_recipe_element", "config_id")) {
                    em.createNativeQuery(
                        "DELETE FROM material_recipe_element WHERE config_id IN " +
                        "(SELECT c.id FROM material_recipe_config c JOIN material_recipe r ON r.id = c.recipe_id " +
                        " WHERE r.code = :code AND c.config_no <> :keep)")
                      .setParameter("code", REAL_RECIPE_CODE)
                      .setParameter("keep", PROTECTED_CONFIG_NO).executeUpdate();
                }
                em.createNativeQuery(
                    "DELETE FROM material_recipe_config WHERE config_no <> :keep AND recipe_id IN " +
                    "(SELECT id FROM material_recipe WHERE code = :code)")
                  .setParameter("keep", PROTECTED_CONFIG_NO)
                  .setParameter("code", REAL_RECIPE_CODE).executeUpdate();
            }

            // ⚠️ 这里**刻意没有**「把 00006-01 复位成 ACTIVE」的兜底：
            //    AC-22（2026-09-02 更正）已改用测试自建的 00006-02 作删除对象，
            //    00006-01 是全路径只读的存量真实数据 —— 一次都不该被删。
            //    留复位 = 给「误删真实数据」提供静默兜底，护栏就哑了。
            //    它的状态改由 assertNoResidue() 硬断言（见下），出问题必须响。

            // ③ 自动建档的测试元素 Xx
            em.createNativeQuery("DELETE FROM element WHERE element_code = 'Xx'").executeUpdate();

            // ④ 开关复位
            if (columnExists("material_recipe", "allow_custom_content")) {
                em.createNativeQuery(
                    "UPDATE material_recipe SET allow_custom_content = false WHERE code = :code")
                  .setParameter("code", REAL_RECIPE_CODE).executeUpdate();
            }
        });
        createdConfigNos.clear();
    }

    // ───────────────────── 还原自检（test.md §1 末段）─────────────────────

    /**
     * 还原自检：残留即让用例失败。
     * <p>🚨 不是形式主义 —— 不做这一步，下一轮会变成「恒定失败且长得像业务回归」。
     */
    protected void assertNoResidue() {
        long acRecipes = count("SELECT count(*) FROM material_recipe WHERE symbol LIKE '" + AC_PREFIX + "%'");
        assertEquals(0, acRecipes, "还原自检失败：仍有 " + acRecipes + " 条 " + AC_PREFIX + "% 材质残留");

        long xx = count("SELECT count(*) FROM element WHERE element_code = 'Xx'");
        assertEquals(0, xx, "还原自检失败：仍有 " + xx + " 行测试元素 Xx 残留");

        // 🚨 真实数据不变式：00006 的元素组成必须仍恰为 Ag + Ni（AC-10 的前置，绝不许被用例改掉）
        if (tableExists("material_recipe_composition")) {
            List<String> comp = strList(
                "SELECT c.element_code FROM material_recipe_composition c " +
                "JOIN material_recipe r ON r.id = c.recipe_id WHERE r.code = '" + REAL_RECIPE_CODE + "' " +
                "ORDER BY c.sort_order");
            assertEquals(List.of("Ag", "Ni"), comp,
                "🚨 真实材质 00006 的元素组成被用例改动了，实际=" + comp + "（必须仍是 [Ag, Ni]）");
        }
        // 🚨 存量迁移的真实配置 00006-01 必须还在
        if (tableExists("material_recipe_config")) {
            long keep = count("SELECT count(*) FROM material_recipe_config WHERE config_no = '"
                    + PROTECTED_CONFIG_NO + "'");
            assertEquals(1, keep, "🚨 存量真实配置 " + PROTECTED_CONFIG_NO + " 被删掉了（count=" + keep + "）");
            assertEquals("ACTIVE",
                scalar("SELECT status FROM material_recipe_config WHERE config_no = '" + PROTECTED_CONFIG_NO + "'"),
                "🚨 存量真实配置 " + PROTECTED_CONFIG_NO + " 被留在 INACTIVE 状态（AC-22 后未复位）");
        }
    }

    // ─────────────────────────── 只读工具 ───────────────────────────

    protected long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? 0L : ((Number) v).longValue();
    }

    protected String scalar(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return rows.get(0).toString();
    }

    protected List<String> strList(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        List<String> out = new ArrayList<>();
        for (Object o : rows) out.add(o == null ? null : o.toString());
        return out;
    }

    protected boolean tableExists(String table) {
        return scalar("SELECT to_regclass('public." + table + "')::text") != null;
    }

    protected boolean columnExists(String table, String column) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_name='"
                + table + "' AND column_name='" + column + "'") > 0;
    }

    /** 当前 5 位补零材质编号的最大值（AC-3 / D8：只看 ^[0-9]{5}$）。 */
    protected String maxRecipeCode5() {
        return scalar("SELECT max(code) FROM material_recipe WHERE code ~ '^[0-9]{5}$'");
    }

    /** 当前纯数字元素编号的最大值（AC-6：^[0-9]+$，天然绕开脏行 '白银'）。 */
    protected long maxNumericElementNo() {
        String s = scalar("SELECT max(element_no::bigint)::text FROM element WHERE element_no ~ '^[0-9]+$'");
        return s == null ? 0L : Long.parseLong(s);
    }

    /** 把基线编号 +n 后按同宽补零，得到期望的下 n 个材质编号。 */
    protected String nextCode(String base, int n) {
        int width = base.length();
        long v = Long.parseLong(base) + n;
        return String.format("%0" + width + "d", v);
    }

    /**
     * 🚨 反假绿护栏：断言「结果非空」，并把实际值打出来。
     * 数据为空 → 循环 0 次 / 分支没走到 → 断言压根没跑，测试照样报绿（testing.md §3）。
     */
    protected <T> List<T> assertNonEmpty(List<T> actual, String what) {
        System.out.println("[task260901] " + what + " = " + actual);
        assertTrue(actual != null && !actual.isEmpty(),
            "断言前置失败：" + what + " 为空 —— 后续断言会空跑（假绿）");
        return actual;
    }
}

/*
 * ════════════════════════════════════════════════════════════════════════════════
 * 🚨 五个证伪实验的可执行步骤（test.md §4；实现落地后、闸门 B 之前由测试侧执行）
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * 为什么必须做：**首次 PASS 不能证明守卫接上了**。用例可能因为分支根本没走到而报绿
 * （数据为空 → 循环 0 次；命令写错 → 全程空输出，看起来和「全部通过」一模一样）。
 * 每个实验的判据统一是：**故意破坏被保护的条件后，指定用例必须硬失败（红）**。
 * 🚫 不变红 = 该用例从没真正验过那条规则，必须重写用例，不是重跑一遍了事。
 *
 * 每个实验做完**必须把改动还原**（`git checkout -- <file>`）并重跑一次确认回到绿。
 *
 * ── FT-1 · 配置编号不回收（M-2 / AC-15）────────────────────────────────────────
 *  破坏：把配置发号处的 `max(seq)` 统计范围改成只算 `status='ACTIVE'`
 *        （即：删掉 seq 水位里对 INACTIVE 行的统计）。
 *  跑  ：cd cpq-backend && ./mvnw test -Dtest=MaterialRecipeConfigApiTest#tI14_deleteConfigIsSoftDelete_andSeqNotRecycled
 *  期望：❌ 变红，且失败信息是「应得 00006-04 而不是复用 00006-02」。
 *  不变红的含义：该用例从没真正验过「不回收」——很可能是删除没生效或 seq 没被读。
 *
 * ── FT-2 · 元素集合一致校验（M-0 / AC-10）─────────────────────────────────────
 *  破坏：把「某组元素集合是否等于该材质元素组成」的校验分支直接 `return true`。
 *  跑  ：./mvnw test -Dtest=MaterialImportAcceptanceTest#tI10_groupElementSetMismatchExistingRecipe_groupSkipped_compositionUntouched
 *  期望：❌ 变红，且**导入报告里那条 `元素组合与该材质的元素组成不一致` 消失**。
 *        （只看「变红」不够 —— 必须确认消失的是那条 skip，否则可能是被别的断言拦下的。）
 *
 * ── FT-3 · 全局状态还原机制本身（§1）─────────────────────────────────────────
 *  破坏：把 restoreGlobalState() 里「删除 AC测% 材质」那两行注释掉。
 *  跑  ：./mvnw test -Dtest=MaterialImportAcceptanceTest   连跑两轮
 *  期望：❌ 第二轮失败，且失败原因必须是 **assertNoResidue() 的「AC测% 材质残留」**，
 *        🚫 不是业务断言失败。这条验的是「清理机制接上了没有」，不是业务对不对。
 *  ⚠️ 若第二轮是业务断言先红（例如编号基线对不上），说明残留会伪装成业务回归 ——
 *     那正是本实验要暴露的风险，需把 assertNoResidue 提前到 @BeforeEach 之后立即执行。
 *
 * ── FT-3b · 含量去尾随零只作用于显示（AC-30）──────────────────────────────────
 *  破坏：把前端的去尾随零函数换成会真改值的实现，例如
 *        `const strip = (s: string) => Number(s).toString()`
 *        （JS number 只有 ~15~17 位有效数字，12.345678901200 会被吃成 12.3456789012，
 *          而 90.000000000000 会变成 90 —— 一旦这个值被回传到提交体，库里就被改了）。
 *  跑  ：① 后端 ./mvnw test -Dtest=MaterialRecipeConfigApiTest#tI28_trailingZeroStrippingMustNotTouchStorageOrApi
 *        ② E2E  npx playwright test --config=e2e/task260901-material.config.ts \
 *                 e2e/task260901-material-list.spec.ts -g "AC-30"
 *  期望：❌ **E2E 的 SQL 断言变红**（库里出现 12.3456789011 / 12.3456789012 而不是 12.345678901200）。
 *  ⚠️ 若只有 UI 断言变红、SQL 断言仍绿 —— 说明那条 SQL 断言从没真正查过库（写错了库名/连错了实例），
 *     必须先修 SQL 断言本身。**先确认脚本能输出东西，再信它的结论。**
 *
 * ── FT-4 · 导入侧与 UI 侧共用同一份「元素种类一致」判据（M-0a）────────────────
 *  破坏：**只改导入侧那一处判据**（例如把集合相等比较改成「子集即可」），🚫 不动 UI 侧。
 *  跑  ：① ./mvnw test -Dtest=MaterialImportAcceptanceTest#tI25_inconsistentGroups_wholeRecipeSkipped_andOrderIndependent
 *        ② ./mvnw test -Dtest=MaterialRecipeConfigApiTest#tI27_inconsistentConfigCards_rejectedWholesale
 *  期望：❌ **两个都变红**。
 *  🚨 若只有 ① 变红、② 仍绿 ⇒ 两边各写了一套判据 —— 这正是 M-0a 明令禁止的分叉。
 *     处置：报主线，要求重构成共享方法后重跑，🚫 不许「两边各自都对就算了」。
 *
 * ── 自检脚本本身的证伪（testing.md §4.4 末段）─────────────────────────────────
 *  本类所有 SQL 断言在首次执行时必须**打印实际值**（已用 System.out 保证）。
 *  执行时先人眼确认这些输出**不是空的** —— 空输出与「全部通过」在日志里长得一模一样。
 */
