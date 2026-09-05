package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-AC-2</b>：「选配新建产品，提交前后对比」⇒
 * 「{@code material_master} / {@code material_bom} / {@code material_bom_item} /
 * {@code element_bom} / {@code element_bom_item} <b>五张表行数不变</b>」。
 *
 * <h3>📌 本类是「不双写」这条用户裁决的守卫</h3>
 * 用户裁决：选配改往新表落库，<b>不再写 V6，且不做双写</b>。
 * 漏掉任何一处 V6 写入，症状不会立刻出现 —— 它只会让 V6 悄悄长出脏行，
 * 等到有人以为 V6 还是权威时才炸。⇒ 必须有一条机械守卫。
 *
 * <h3>🚨 判据（2026-09-04 改写）</h3>
 * AC 原文写的是「五张表<b>行数不变</b>」，但共享库上绝对行数会被第三方删改带偏 ——
 * 实测一次 {@code material_master 1894 → 1893}（<b>减少</b>一行）也把本类判红，
 * 而 A-AC-2 管的是<b>不双写 = 零新增</b>，别人删行不是本 AC 的违规。
 * ⇒ 判据改为「{@code created_at} 窗口内新增行数 = 0」，行级、免疫删行、抵消不掉。
 * 详见 {@code Task260903Base#assertV6Unchanged}，鉴别力由
 * {@link #falsify_guardMustGoRedWhenV6IsActuallyWritten} 每轮证伪。
 *
 * <h3>🚨 本类最大的风险是它自己假绿（{@code test.md §3} 第 2 号陷阱）</h3>
 * 「V6 零新增」<b>在提交压根没成功时同样成立</b> —— 什么都没写，当然零新增，
 * 而那个绿和真通过<b>长得一模一样</b>。
 * ⇒ 断言顺序被写死为三步，{@link #assertOrderIsMandatory} 里再解释一遍：
 * <ol>
 *   <li>{@code assertSubmitOk} —— 提交真的进了业务层并返回 200</li>
 *   <li>{@code assertNewTablesGotRows} —— 新表真落了行（证明写入路径确实跑过）</li>
 *   <li>{@code assertV6Unchanged} —— 到这一步，「V6 没变」才是个有内容的结论</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("A-AC-2 不双写：V6 五表零新增")
class V6ZeroWriteGuardTest extends Task260903Base {

    @Test
    @DisplayName("A-AC-2 单零件提交 → V6 五表行数不变")
    void aac2_simplePartSubmitDoesNotTouchV6() {
        Fx fx = newFixture("aac2");
        // 🚨 判据已从「全表绝对行数」改成「窗口内零新增」，见 Task260903Base#assertV6Unchanged
        String t0 = v6ClockNow();
        System.out.println("[A-AC-2] 零新增窗口起点=" + t0 + "，当前 V6 五表行数（仅供阅读）=" + v6Counts());

        // ① 提交必须真的成功
        Response res = configure(fx, submitBody(PREFIX + "A2",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1))));
        assertSubmitOk(res, "A-AC-2 提交");

        // ② 新表真落了行 —— 没有这一步，第 ③ 步就是空断言
        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-2");

        // ③ 到这里，「V6 没变」才有意义
        assertV6Unchanged(t0, "A-AC-2");
    }

    /**
     * <b>A-AC-2</b>（组合场景）：零件 + 外购件一起提交，V6 五表<b>仍然</b>零新增。
     *
     * <p>📌 单独一条的理由：外购件走的是<b>另一条写入分支</b>（它没有材质/元素，
     * 但有料号主档与 BOM 行）。只测单零件时，外购件那条分支上的 V6 写入不会被发现。
     */
    @Test
    @DisplayName("A-AC-2 零件+外购件提交 → V6 五表行数仍不变")
    void aac2_compositeWithOutsourcedDoesNotTouchV6() {
        String outsourced = scalar(
                "SELECT material_no FROM material_master WHERE material_type='外购件' ORDER BY material_no LIMIT 1");
        assertTrue(outsourced != null,
                "A-AC-2 前置：需要一个外购件料号来构造组合产品。库里 0 条 ⇒ 本用例会退化成"
                        + "「只测了单零件分支」，外购件分支上的 V6 写入漏检 ⇒ 硬失败，请先补数据");
        System.out.println("[A-AC-2 组合] 外购件=" + outsourced);

        Fx fx = newFixture("aac2c");
        String t0 = v6ClockNow();
        System.out.println("[A-AC-2 组合] 零新增窗口起点=" + t0 + "，当前 V6 五表行数（仅供阅读）=" + v6Counts());

        Response res = configure(fx, submitBody(PREFIX + "A2C",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-2 组合提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-2 组合");
        assertV6Unchanged(t0, "A-AC-2 组合");
    }

    /**
     * 🚨 <b>本类断言顺序的说明（不是业务用例，是给读报告的人看的）。</b>
     *
     * <p>本方法故意<b>只</b>做第 ③ 步（不提交任何东西就断言 V6 零新增）并断言它<b>通过</b> ——
     * 用来证明：<b>单独的第 ③ 步毫无鉴别力</b>。
     * 它恒绿，所以上面两条用例里第 ①② 步不是可有可无的仪式，而是让第 ③ 步产生意义的唯一来源。
     *
     * <p>🚫 一旦有人「为了让用例跑得快」删掉第 ①② 步，这条注释和本方法就是证据：
     * 剩下的那个绿等于什么都没验。
     */
    @Test
    @DisplayName("A-AC-2 对照：不提交也『零新增』—— 证明单独第③步是空断言")
    void assertOrderIsMandatory() {
        String t0 = v6ClockNow();
        // 什么都不做
        assertV6Unchanged(t0, "A-AC-2 空断言对照");
        System.out.println("[A-AC-2 空断言对照] 未提交任何内容，窗口内当然零新增 "
                + "⇒ 单独断言『V6 零新增』无鉴别力，必须配正向对照（第①②步）");
    }

    /**
     * 🚨 <b>守卫自身的证伪实验（每轮都跑，不是一次性验证）。</b>
     *
     * <p>{@code testing.md §3}：新加的守卫<b>首次 PASS 证明不了它接上了</b> ——
     * 必须故意破坏它保护的条件，确认它<b>硬失败</b>。
     * 本方法在一个<b>永不提交的事务</b>里真往 {@code material_master} 插一行（共享库零残留），
     * 然后断言 {@code assertV6Unchanged} <b>必须抛断言错误</b>。
     *
     * <p>三段结构缺一不可：
     * <ol>
     *   <li><b>干预前</b>先跑一次守卫并要求它<b>绿</b> —— 否则后面的红说明不了是这一行造成的</li>
     *   <li><b>证明干预真的生效</b>（假行确实插进去了）—— 否则「守卫报红」可能是别的原因</li>
     *   <li>断言守卫<b>报红</b>，并把它的报错原文打出来</li>
     * </ol>
     *
     * <p>📌 假行料号 {@code ZZFAKE-*} 刻意<b>不用</b> {@code T260902-} 前缀 ——
     * 那个前缀会被 {@code MM_SUITE_NS} 排除掉，用它就证伪不了任何东西（假行会被守卫忽略，
     * 于是「没报红」看起来像守卫失效，实则是我自己把它排除了）。
     */
    @Test
    @DisplayName("A-AC-2 证伪：真往 V6 写一行，守卫必须报红（回滚事务，共享库零残留）")
    void falsify_guardMustGoRedWhenV6IsActuallyWritten() {
        String fake = "ZZFAKE-" + RUN_ID;   // 13 字符，material_master.material_no 是 varchar(20)
        String t0 = v6ClockNow();

        // ① 干预前：守卫必须是绿的
        assertV6Unchanged(t0, "A-AC-2 证伪·干预前");

        inRollback(() -> {
            em.createNativeQuery("INSERT INTO material_master (id,material_no,material_name,created_at,updated_at) "
                            + "VALUES (gen_random_uuid(),:n,'证伪用假行·永不提交',NOW(),NOW())")
                    .setParameter("n", fake).executeUpdate();

            // ② 先证明干预真的生效
            assertEquals(1L, count("SELECT count(*) FROM material_master WHERE material_no='" + fake + "'"),
                    "证伪前置：假行没插进去 ⇒ 下面的『守卫报红』无从谈起，这次证伪等于没做");

            // ③ 守卫必须报红
            AssertionError err = assertThrows(AssertionError.class,
                    () -> assertV6Unchanged(t0, "A-AC-2 证伪·干预后"),
                    "🚨 证伪失败：已经真往 material_master 写了一行，A-AC-2 守卫却没报红 ⇒ "
                            + "这个守卫是空的，它平时的绿证明不了任何事");
            System.out.println("[A-AC-2 证伪] 守卫如期报红 ✅ 原文：" + err.getMessage());
        });

        // 还原自检：回滚是构造性的，不依赖任何 DELETE
        assertEquals(0L, count("SELECT count(*) FROM material_master WHERE material_no='" + fake + "'"),
                "证伪还原自检：假行必须随事务回滚消失，共享库零残留");
    }
}
