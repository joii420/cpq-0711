package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <h3>🚨 本类最大的风险是它自己假绿（{@code test.md §3} 第 2 号陷阱）</h3>
 * 「五表行数不变」<b>在提交压根没成功时同样成立</b> —— 什么都没写，行数当然不变，
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
        Map<String, Long> before = v6Counts();
        System.out.println("[A-AC-2] 提交前 V6 五表=" + before);

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
        assertV6Unchanged(before, "A-AC-2");
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
        Map<String, Long> before = v6Counts();
        System.out.println("[A-AC-2 组合] 提交前 V6 五表=" + before);

        Response res = configure(fx, submitBody(PREFIX + "A2C",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-2 组合提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-2 组合");
        assertV6Unchanged(before, "A-AC-2 组合");
    }

    /**
     * 🚨 <b>本类断言顺序的证伪说明（不是用例，是给读报告的人看的）。</b>
     *
     * <p>本方法故意<b>只</b>做第 ③ 步（不提交任何东西就断言 V6 不变）并断言它<b>通过</b> ——
     * 用来证明：<b>单独的第 ③ 步毫无鉴别力</b>。
     * 它恒绿，所以上面两条用例里第 ①② 步不是可有可无的仪式，而是让第 ③ 步产生意义的唯一来源。
     *
     * <p>🚫 一旦有人「为了让用例跑得快」删掉第 ①② 步，这条注释和本方法就是证据：
     * 剩下的那个绿等于什么都没验。
     */
    @Test
    @DisplayName("A-AC-2 证伪对照：不提交也『V6 不变』—— 证明单独第③步是空断言")
    void assertOrderIsMandatory() {
        Map<String, Long> before = v6Counts();
        // 什么都不做
        Map<String, Long> after = v6Counts();
        assertEquals(before, after,
                "本对照本就应该通过 —— 这正是问题所在：不提交时『V6 五表不变』照样成立。"
                        + "所以真用例必须先证明『提交成功且新表落了行』。");
        System.out.println("[A-AC-2 证伪对照] 未提交任何内容，V6 五表当然不变=" + after
                + " ⇒ 单独断言『V6 不变』无鉴别力，必须配正向对照");
    }
}
