package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · <b>C-1 守卫</b>：{@code v_compat_material_master} 跨客户重号去重（V435 / 方案丁）。
 *
 * <h3>为什么需要这个类</h3>
 * V435 把 {@code v_compat_material_master} 的 ds 分支收敛成 {@code DISTINCT ON (material_no)}，
 * 依据是用户在闸门 A0 的裁决：<b>「一个销售料号在两个客户下，物理属性（品名/规格/尺寸/单重/类型）
 * 按业务定义应该相同」</b>。
 *
 * <p>🚨 但「应该相同」是<b>业务定义</b>，不是数据库约束 ——
 * {@code uq_ds_quote_material(customer_no, material_no)} 结构上完全允许两个客户的属性分歧。
 * 一旦分歧，视图会<b>静默挑一个客户的属性</b>：不报错、不留痕、不掉行，
 * 而症状是「A 客户的卡片上显示了 B 客户的品名/规格」—— 与「数据本来就这样」不可区分。
 *
 * <p>⇒ 本类把这条风险从<b>静默</b>变成<b>报红</b>。
 * 🚫 <b>不要因为「今天恰好一样」就删掉它</b> —— {@code V429} 抬头段落是同一条纪律：
 * 「真正的代价不是延迟，是口径分叉……而不是靠『现在恰好一样』。」
 *
 * <h3>🚨 共库纪律（{@code CLAUDE.md §3.2}「测试也算」）</h3>
 * {@code test} profile 实连共享开发库 {@code cpq_db_0724}，当前有并发会话在导入。故本类
 * <b>零写库</b>：{@link #guardMustGoRedOnDivergence()} 的分歧数据是用
 * {@code UNION ALL} 拼在 <b>CTE 里</b>的，从不落表 ⇒ 无需清理、不可能留残留、不会与并发线抢数据。
 *
 * <h3>🚨 防恒真判据（{@code testing.md §4} 证伪要求）</h3>
 * 「守卫今天是绿的」这句话本身<b>不构成守卫有效的证据</b> —— 一个恒返 0 行的 SQL 也永远是绿的，
 * 而本任务已经踩过 7 次同族。⇒ {@link #guardMustGoRedOnDivergence()} 用<b>同一段判据 SQL</b>
 * 喂一份掺了人造分歧行的输入，<b>必须变红</b>。两个用例共用 {@link #GUARD_SQL} 模板，
 * 🚫 判据只有一份，不许各写各的（否则证伪的是另一段代码）。
 */
@QuarkusTest
public class CompatMasterCrossCustomerGuardTest {

    @Inject
    EntityManager em;

    /**
     * 守卫判据（唯一一份）。{@code %s} 处注入<b>数据源子查询</b>，必须产出这 8 列：
     * {@code material_no} + 7 个物理属性列。
     *
     * <p>🔑 只看<b>物理属性</b>，刻意<b>不看</b> {@code created_at / updated_at}：
     * 那两列跨客户天然不同（各自的导入时刻），2026-09-09 实测 16 个重号料号
     * <b>全部</b>是 {@code distinct_physical = 1} 而 {@code distinct_created = 2}。
     * 把时间戳算进判据 ⇒ 守卫开箱即红，会立刻被下一个人注释掉，等于没有守卫。
     *
     * <p>⚠️ 时间戳被去重丢弃是<b>已知且已评估</b>的降级：2026-09-09 全工程扫确认
     * <b>8 个 Java 消费点与 3 个依赖视图没有任何一个投影 {@code created_at / updated_at}</b>
     * ⇒ 实际影响为零。哪天有人开始消费它们，这条注释就是该被重新审的地方。
     */
    private static final String GUARD_SQL = """
            SELECT material_no,
                   count(DISTINCT material_name)     AS d_name,
                   count(DISTINCT specification)     AS d_spec,
                   count(DISTINCT dimension)         AS d_dim,
                   count(DISTINCT old_material_no)   AS d_old,
                   count(DISTINCT material_type)     AS d_type,
                   count(DISTINCT unit_weight)       AS d_weight,
                   count(DISTINCT production_no)     AS d_prod
              FROM ( %s ) src
             GROUP BY material_no
            HAVING count(DISTINCT material_name)   > 1
                OR count(DISTINCT specification)   > 1
                OR count(DISTINCT dimension)       > 1
                OR count(DISTINCT old_material_no) > 1
                OR count(DISTINCT material_type)   > 1
                OR count(DISTINCT unit_weight)     > 1
                OR count(DISTINCT production_no)   > 1
             ORDER BY material_no
            """;

    /** 生产输入：真实表。 */
    private static final String REAL_SOURCE = """
            SELECT material_no, material_name, specification, dimension,
                   old_material_no, material_type, unit_weight, production_no
              FROM ds_quote_material
            """;

    @SuppressWarnings("unchecked")
    private List<Object[]> runGuard(String source) {
        return em.createNativeQuery(GUARD_SQL.formatted(source)).getResultList();
    }

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ① C-1 修复本身的回归防线：视图不变量
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 🚫 <b>刻意不写「视图共 N 行」这类绝对计数</b>（{@code testing.md §4.5} 共库片禁用全局计数断言）。
     * 本任务已实证它是移动靶：2026-09-09 同一天内 {@code v_compat_material_master}
     * 从 6419 行掉到 2731 行（并发会话大批导入，{@code material_master} 1903→48）。
     * ⇒ 判据必须是<b>不变量</b>：一个料号在本视图里有且只有一行。
     */
    @Test
    @DisplayName("C-1：v_compat_material_master 每个料号有且只有一行（rows = uniq material_no = uniq id）")
    void viewMustBeOneRowPerMaterialNo() {
        long rows = scalar("SELECT count(*) FROM v_compat_material_master");
        long uniqNo = scalar("SELECT count(DISTINCT material_no) FROM v_compat_material_master");
        long uniqId = scalar("SELECT count(DISTINCT id) FROM v_compat_material_master");

        assertTrue(rows > 0, "前置：视图为空 ⇒ 下面三个相等是空跑（假绿）。实际 rows=" + rows);

        @SuppressWarnings("unchecked")
        List<Object[]> dups = em.createNativeQuery(
                "SELECT material_no, count(*) FROM v_compat_material_master "
                        + "GROUP BY material_no HAVING count(*) > 1 ORDER BY material_no").getResultList();
        System.out.println("[C-1] rows=" + rows + " uniq_no=" + uniqNo + " uniq_id=" + uniqId
                + " 重号=" + dups.stream().map(java.util.Arrays::toString).toList());

        assertEquals(List.of(), dups.stream().map(java.util.Arrays::toString).toList(),
                "C-1：v_compat_material_master 是 V6 兼容形状（material_master 没有客户列），"
                        + "而 V425 后 ds_quote_material 的轴是 (customer_no, material_no)。"
                        + "出现重号 ⇒ V435 的 DISTINCT ON 失效或被回退，"
                        + "下游 v_composite_child_materials 会扇出、外购件选择框会出双份。");
        assertEquals(rows, uniqNo, "C-1 不变量：rows 必须等于去重料号数");
        assertEquals(rows, uniqId, "C-1 不变量：rows 必须等于去重 id 数（id=md5('dqm:'||material_no) 天然与料号一一对应）");
    }

    /**
     * V6 分支必须<b>逐字未动</b>（方案丁的三条「不许动」之一）。
     * {@code material_master.material_no} 本就全局唯一 ⇒ 它对重复零贡献，
     * 对它加 {@code DISTINCT ON} 是无谓风险。这条断言防的是将来有人「顺手把去重提到最外层」。
     */
    @Test
    @DisplayName("C-1：V6 分支零丢失 —— material_master 每一行都还在视图里")
    void v6BranchMustBeUntouched() {
        long v6Rows = scalar("SELECT count(*) FROM material_master");
        assertTrue(v6Rows > 0, "前置：material_master 为空 ⇒ 本用例空跑（假绿）");

        long missing = scalar(
                "SELECT count(*) FROM material_master v "
                        + "WHERE NOT EXISTS (SELECT 1 FROM v_compat_material_master x WHERE x.id = v.id)");
        System.out.println("[C-1] material_master 行数=" + v6Rows + "，视图里缺失=" + missing);
        assertEquals(0, missing,
                "C-1：V6 存量必须逐行原样出现在视图里。缺失 ⇒ 去重被错误地提到了 UNION 之外，"
                        + "V6 与 ds 两侧被压进同一个去重窗口（方案丁明确否掉的做法）。");
    }

    /**
     * {@code id} 合成算法必须保持 {@code md5('dqm:' || material_no)}，🚫 不含 {@code customer_no}。
     * 依据：2026-09-09 全库 uuid 列扫描证明该 id 未被持久化到任何地方 ⇒ 改它零收益、有风险。
     */
    @Test
    @DisplayName("C-1：ds 分支 id 算法未变（md5('dqm:'||material_no)，不含 customer_no）")
    void dsBranchIdAlgorithmMustBeStable() {
        long dsRows = scalar(
                "SELECT count(*) FROM v_compat_material_master x "
                        + "WHERE NOT EXISTS (SELECT 1 FROM material_master v WHERE v.id = x.id)");
        assertTrue(dsRows > 0,
                "前置：视图里没有任何 ds 分支行 ⇒ 下面的断言空跑（假绿）。实际 ds 分支行数=" + dsRows);

        long mismatch = scalar(
                "SELECT count(*) FROM v_compat_material_master x "
                        + "WHERE NOT EXISTS (SELECT 1 FROM material_master v WHERE v.id = x.id) "
                        + "  AND x.id <> (md5('dqm:' || x.material_no))::uuid");
        System.out.println("[C-1] ds 分支行数=" + dsRows + "，id 不符=" + mismatch);
        assertEquals(0, mismatch,
                "C-1：ds 分支的 id 必须恒为 md5('dqm:'||material_no)。"
                        + "一旦掺进 customer_no，同一料号会再次拿到两个不同 id ⇒ 重号从别的口子回来。");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ② 守卫断言：跨客户物理属性分歧
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("守卫（绿）：ds_quote_material 跨客户物理属性无分歧 —— 有分歧则 DISTINCT ON 会静默挑错")
    void guardMustBeGreenToday() {
        long total = scalar("SELECT count(*) FROM ds_quote_material");
        long crossCustomerNos = scalar(
                "SELECT count(*) FROM (SELECT material_no FROM ds_quote_material "
                        + "GROUP BY material_no HAVING count(DISTINCT customer_no) > 1) z");

        // 🚨 前置自检：库里必须真的存在跨客户重号，否则本守卫此刻在空跑
        //    （没有重号 ⇒ 判据的 HAVING 永远不可能命中 ⇒ 绿得毫无信息量）。
        assertTrue(total > 0, "前置：ds_quote_material 为空 ⇒ 守卫空跑（假绿）");
        assertTrue(crossCustomerNos > 0,
                "前置：库里没有任何跨客户重号料号 ⇒ 本守卫此刻空跑，绿不代表任何事。"
                        + "（2026-09-09 实测有 16 个：S0001..S0014 + T260907-M1/M2）实际=" + crossCustomerNos);

        List<Object[]> divergent = runGuard(REAL_SOURCE);
        System.out.println("[守卫] ds_quote_material 总行=" + total + "，跨客户重号料号=" + crossCustomerNos
                + "，物理属性分歧=" + divergent.stream().map(java.util.Arrays::toString).toList());

        assertEquals(List.of(), divergent.stream().map(java.util.Arrays::toString).toList(),
                "守卫报红：下列料号在不同客户下的物理属性不一致。\n"
                        + "闸门 A0 用户裁决的前提是「同一销售料号跨客户物理属性应该相同」，"
                        + "V435 的 DISTINCT ON 正建立在这个前提上。前提被打破时，视图会"
                        + "【静默挑一个客户的属性】—— 不报错、不掉行，症状是「A 客户卡片上显示 B 客户的品名/规格」。\n"
                        + "🚫 不要靠给本用例加豁免来消红。正确动作是：把分歧数据修对，"
                        + "或者回头找用户重新裁决 v_compat_material_master 是否还能保持无客户维度。");
    }

    /**
     * 🚨 <b>证伪实验</b>：把一条人造的分歧行拼进判据的输入，守卫<b>必须变红</b>。
     *
     * <p>没有这个用例，{@link #guardMustBeGreenToday()} 的绿与「判据恒返 0 行」<b>不可区分</b>。
     * 本任务已经踩过 7 次同族（恒真判据 / 空跑断言），所以这一条是必需品不是加分项。
     *
     * <p><b>零写库</b>：人造行只存在于 CTE 的 {@code UNION ALL} 里，从不落表。
     */
    @Test
    @DisplayName("守卫（证伪）：掺一条人造分歧行 —— 同一段判据必须变红，否则它是恒真判据")
    void guardMustGoRedOnDivergence() {
        // 取一个真实存在的料号来伪造分歧：用真料号而不是编一个新的，
        // 是为了让人造行与真实行落进同一个 GROUP BY 桶（编新料号只会自成一桶，永远不分歧）。
        Object probe = em.createNativeQuery(
                "SELECT material_no FROM ds_quote_material ORDER BY material_no LIMIT 1").getSingleResult();
        String probeNo = String.valueOf(probe);

        // 人造行：同料号、品名故意不同、其余列取 NULL。
        // 🔑 只污染 material_name 一列 ⇒ 证明判据对【单列分歧】就有鉴别力，
        //    而不是要七列一起变才认。
        String tamperedSource = REAL_SOURCE
                + " UNION ALL SELECT CAST(:probe AS varchar(20)),"
                + " CAST('__R260908_TAMPER__' AS varchar(100)), NULL, NULL, NULL, NULL, NULL, NULL";

        @SuppressWarnings("unchecked")
        List<Object[]> divergent = em.createNativeQuery(GUARD_SQL.formatted(tamperedSource))
                .setParameter("probe", probeNo).getResultList();

        List<String> hit = divergent.stream().map(java.util.Arrays::toString).toList();
        System.out.println("[证伪] 探针料号=" + probeNo + "，掺入 1 条人造分歧行后守卫命中=" + hit);

        assertFalse(hit.isEmpty(),
                "证伪失败：掺进一条物理属性分歧的行之后，守卫判据【仍然返回 0 行】"
                        + " ⇒ 它是恒真判据，guardMustBeGreenToday 的绿毫无信息量。探针料号=" + probeNo);
        assertTrue(hit.stream().anyMatch(s -> s.contains(probeNo)),
                "证伪失败：守卫报红了，但命中的不是被污染的探针料号 " + probeNo
                        + "（说明红是别的原因造成的，判据的鉴别力仍未被证明）。实际命中=" + hit);

        // 还原实验的反向锚点：同一段判据、去掉人造行，必须回到绿。
        assertEquals(List.of(), runGuard(REAL_SOURCE).stream().map(java.util.Arrays::toString).toList(),
                "去掉人造行后守卫应回到绿；若仍红，说明红与人造行无关，上面的证伪不成立。");
    }
}
