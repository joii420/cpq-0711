package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · 接口层验收：<b>三层模型的落库与校验</b>。
 *
 * <p>覆盖 <b>AC-3 / AC-4 / AC-13 / AC-14 / AC-15a+15b / AC-17 / AC-23</b>。
 * 每个 {@code @Test} 的 javadoc 首行是它验的 <b>AC 原文</b>（{@code 需求文档.md §③}），
 * 断言只针对 AC 里写死的可观测值，🚫 不针对实现细节。
 */
@QuarkusTest
@DisplayName("task-260902 · 提交与校验（AC-3/4/13/14/15/17/23）")
class SubmitAndValidationAcTest extends SelConfigAcTestBase {

    /**
     * <b>AC-3</b>：「新建零件，品名『触点』、规格『φ5』、尺寸『5×3×2』、总重 10（克）；
     * 添加材质 AgNi10 占比 70、AgZnO12/Cu 占比 30，提交」⇒
     * ① {@code material_master} 该料号 {@code material_name='触点'}/{@code specification='φ5'}/
     * {@code dimension='5×3×2'}/{@code unit_weight=10}；
     * ② {@code material_bom_item} 落 <b>2 行</b>，{@code material_ratio} 分别 70、30，{@code characteristic='RECIPE'}；
     * ③ {@code element_bom_item} 按 2 个材质分成 <b>2 组</b>（{@code material_part_no} 各不同）。
     *
     * <p>🚨 {@code test.md §4}「落库断言只查一张表 = 假绿」⇒ 本用例<b>逐表查四张</b>
     * （master / bom / element / 报价行），任一不符即失败。
     * <p>🚫 <b>不断言 {@code config_fingerprint} 非空</b>：该列有意恒 NULL，
     * 满足它反而会撞 {@code uq_material_master_fingerprint} → 500（{@code test.md §4} 末行）。
     */
    @Test
    @DisplayName("AC-3 多材质零件：master 四列 + bom 2 行占比 70/30 + element 2 组")
    void ac3_multiMaterialPartPersistsAcrossThreeTables() {
        Fx fx = newFixture("ac3");

        Response res = configure(fx, submitBody(PREFIX + "A", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1))));
        assertSubmitOk(res, "AC-3 提交");

        String partNo = latestLinePartNo(fx);
        System.out.println("[AC-3] 销售料号=" + partNo);

        // ① material_master 四列（AC-3 断言①）
        List<Object[]> mm = rows("SELECT material_name, specification, dimension, unit_weight::text "
                + "FROM material_master WHERE material_no='" + partNo + "'");
        assertEquals(1, mm.size(), "AC-3①：material_master 应恰好 1 行，实际 " + mm.size());
        Object[] r = mm.get(0);
        System.out.println("[AC-3①] material_master=" + java.util.Arrays.toString(r));
        assertEquals("触点", r[0], "AC-3①：material_name 应为『触点』");
        assertEquals("φ5", r[1], "AC-3①：specification 应为『φ5』");
        assertEquals("5×3×2", r[2], "AC-3①：dimension 应为『5×3×2』");
        assertNotNull(r[3], "AC-3①：unit_weight 不得为 NULL");
        assertEquals(0, new BigDecimal(r[3].toString()).compareTo(new BigDecimal("10")),
                "AC-3①：unit_weight 应为 10，实际 " + r[3]);

        // ② material_bom_item 2 行 + 占比 + characteristic（AC-3 断言②）
        List<Object[]> bom = rows("SELECT component_no, material_ratio::text, characteristic, component_usage_type, seq_no "
                + "FROM material_bom_item WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo
                + "' AND is_current=true ORDER BY seq_no");
        System.out.println("[AC-3②] material_bom_item=" + bom.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, bom.size(), "AC-3②：material_bom_item 应落 2 行（每材质一行），实际 " + bom.size());
        assertEquals(0, new BigDecimal(bom.get(0)[1].toString()).compareTo(new BigDecimal("70")),
                "AC-3②：第 1 行 material_ratio 应为 70，实际 " + bom.get(0)[1]);
        assertEquals(0, new BigDecimal(bom.get(1)[1].toString()).compareTo(new BigDecimal("30")),
                "AC-3②：第 2 行 material_ratio 应为 30，实际 " + bom.get(1)[1]);
        for (Object[] row : bom) {
            assertEquals("RECIPE", row[2], "AC-3②：characteristic 应为 RECIPE，实际 " + row[2]);
        }
        assertEquals(List.of(RECIPE_A, RECIPE_B),
                bom.stream().map(x -> String.valueOf(x[0])).toList(),
                "AC-3②：两行的 component_no 应分别是两个材质编号");

        // ③ element_bom_item 按材质分 2 组（AC-3 断言③）
        List<Object[]> groups = rows("SELECT material_part_no, count(*) FROM element_bom_item "
                + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true "
                + "GROUP BY material_part_no ORDER BY material_part_no");
        System.out.println("[AC-3③] element_bom_item 分组=" + groups.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, groups.size(),
                "AC-3③：element_bom_item 应按 material_part_no 分成 2 组，实际 " + groups.size() + " 组");
        for (Object[] g : groups) {
            assertTrue(((Number) g[1]).intValue() > 0, "AC-3③：每组元素行不得为空（空组 = 断言空跑）");
        }
    }

    /**
     * <b>AC-13</b>（边界·不回归判据）：「零件只加 1 个材质，占比填 100」⇒
     * 「正常提交；{@code material_bom_item} 落 1 行，{@code material_ratio=100}（与旧的单材质行为等价）」。
     */
    @Test
    @DisplayName("AC-13 单材质占比 100 → bom 1 行 ratio=100（与旧行为等价）")
    void ac13_singleMaterialEquivalentToLegacy() {
        Fx fx = newFixture("ac13");

        Response res = configure(fx, submitBody(PREFIX + "B", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "100")), List.of(PROC_1))));
        assertSubmitOk(res, "AC-13 提交");

        String partNo = latestLinePartNo(fx);
        List<Object[]> bom = rows("SELECT component_no, material_ratio::text, characteristic FROM material_bom_item "
                + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true");
        System.out.println("[AC-13] material_bom_item=" + bom.stream().map(java.util.Arrays::toString).toList());
        assertEquals(1, bom.size(), "AC-13：单材质应落 1 行，实际 " + bom.size());
        assertEquals(RECIPE_A, bom.get(0)[0], "AC-13：component_no 应为材质编号 " + RECIPE_A);
        assertEquals(0, new BigDecimal(bom.get(0)[1].toString()).compareTo(new BigDecimal("100")),
                "AC-13：material_ratio 应为 100，实际 " + bom.get(0)[1]);
        assertEquals("RECIPE", bom.get(0)[2], "AC-13：characteristic 应为 RECIPE");
        // 元素也应落，且恰好 1 组（单材质）
        assertEquals(1, count("SELECT count(DISTINCT material_part_no) FROM element_bom_item "
                        + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true"),
                "AC-13：单材质的 element_bom_item 应恰好 1 组");
    }

    /**
     * <b>AC-4</b>：「材质占比填 70 + 20，点下一步」⇒「② 行级提示写出<b>实际合计值 90%</b>，
     * 不是『合计不正确』这种形容词」。后端侧对应 api.md 的 400 {@code MATERIAL_RATIO_SUM_INVALID}
     * 且响应带 {@code detail.actualSum="90"}。
     *
     * <p>🚫 本用例<b>不接受</b>只有「合计不正确」这类文案的响应 —— AC 明写要实际值。
     */
    @Test
    @DisplayName("AC-4 占比合计 90% → 400 + 响应带实际值 90")
    void ac4_ratioSumNot100RejectedWithActualValue() {
        Fx fx = newFixture("ac4");

        Response res = configure(fx, submitBody(PREFIX + "C", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "20")),
                List.of(PROC_1))));
        assertReachedBusinessLayer(res, "AC-4 提交");
        String body = res.asString();
        System.out.println("[AC-4] " + res.statusCode() + " " + body);

        assertEquals(400, res.statusCode(), "AC-4：占比合计 90 应被拒（400），实际 " + res.statusCode() + " " + body);
        assertTrue(body.contains("MATERIAL_RATIO_SUM_INVALID"),
                "AC-4：错误码应为 MATERIAL_RATIO_SUM_INVALID，实际=" + body);
        assertTrue(body.contains("90"),
                "AC-4：提示必须写出实际合计值 90（AC 原文：不是『合计不正确』这种形容词），实际=" + body);
        assertNothingLanded(fx, "AC-4");
    }

    /**
     * <b>AC-14</b>（边界）：「零件一个材质都不加就点下一步」⇒「阻止，提示『请至少添加一个材质』」。
     * 后端侧对应 api.md 的 400 {@code PART_HAS_NO_MATERIAL}（前端拦是体验，后端拦是正确性）。
     */
    @Test
    @DisplayName("AC-14 零材质 → 400 PART_HAS_NO_MATERIAL")
    void ac14_zeroMaterialRejected() {
        Fx fx = newFixture("ac14");

        Response res = configure(fx, submitBody(PREFIX + "D", newPart(
                "触点", "φ5", "5×3×2", "10", List.of(), List.of(PROC_1))));
        assertReachedBusinessLayer(res, "AC-14 提交");
        System.out.println("[AC-14] " + res.statusCode() + " " + res.asString());

        assertEquals(400, res.statusCode(), "AC-14：零材质应被拒，实际 " + res.statusCode() + " " + res.asString());
        assertTrue(res.asString().contains("PART_HAS_NO_MATERIAL"),
                "AC-14：错误码应为 PART_HAS_NO_MATERIAL，实际=" + res.asString());
        assertNothingLanded(fx, "AC-14");
    }

    /**
     * <b>AC-15a + AC-15b 必须成对跑</b>（{@code test.md §4} 第 1 号假绿陷阱）。
     *
     * <ul>
     *   <li><b>AC-15a</b>：{@code 33.333333333333 / 33.333333333333 / 33.333333333334} ⇒ 校验通过，
     *       且「库内 {@code material_ratio} 三行分别<b>存满 12 位小数</b>」。</li>
     *   <li><b>AC-15b</b>（证伪对照组）：{@code 0.000000000001 / 99.999999999998 / 0.000000000001} ⇒ 校验通过。
     *       🚨 AC-15a 那组在浮点下<b>恰好等于 100</b>，浮点实现照样能过，它没有分辨力；
     *       本组在浮点下 = {@code 99.99999999999999}，浮点实现会<b>错误拒绝</b>它。</li>
     * </ul>
     *
     * <p>🚨 <b>两组写在同一个 {@code @Test} 里，就是为了让它们不可能被单独跑</b> ——
     * 单跑 15a 是本任务已登记的头号假绿。
     */
    @Test
    @DisplayName("AC-15a+15b 12 位小数占比（含浮点证伪对照组，必须成对）")
    void ac15_fixedPointRatioSum_bothGroupsMustPass() {
        // 第三个材质：现网找不到「第三条能安全占用的存量材质」，事务外自建 + @AfterEach 精确删除
        String recipeC = createRecipe("15", false, List.<String[]>of(new String[]{"Ag", "100"}));

        // ── AC-15a ──────────────────────────────────────────────
        Fx fxA = newFixture("ac15a");
        Response a = configure(fxA, submitBody(PREFIX + "E", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "33.333333333333"),
                        material(RECIPE_B, CONFIG_B, "33.333333333333"),
                        material(recipeC, recipeC + "-01", "33.333333333334")),
                List.of(PROC_1))));
        assertSubmitOk(a, "AC-15a 12 位小数三等分");

        String partA = latestLinePartNo(fxA);
        List<Object> ratios = col("SELECT material_ratio::text FROM material_bom_item WHERE customer_no='"
                + fxA.customerNo() + "' AND material_no='" + partA + "' AND is_current=true ORDER BY seq_no");
        System.out.println("[AC-15a] material_ratio=" + ratios);
        assertEquals(3, ratios.size(), "AC-15a：应落 3 行占比，实际 " + ratios.size());
        for (Object v : ratios) {
            assertNotNull(v, "AC-15a：material_ratio 不得为 NULL");
            String s = v.toString();
            int scale = s.contains(".") ? s.length() - s.indexOf('.') - 1 : 0;
            assertTrue(scale >= 12,
                    "AC-15a：material_ratio 须存满 12 位小数（AC 原文：不是 33.33），实际=" + s + "（小数位 " + scale + "）");
        }
        assertTrue(ratios.stream().anyMatch(v -> new BigDecimal(v.toString())
                        .compareTo(new BigDecimal("33.333333333334")) == 0),
                "AC-15a：应能查到 33.333333333334 这一行原值，实际=" + ratios);

        // ── AC-15b（浮点实现会在这里错误拒绝）────────────────────
        Fx fxB = newFixture("ac15b");
        Response b = configure(fxB, submitBody(PREFIX + "F", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "0.000000000001"),
                        material(RECIPE_B, CONFIG_B, "99.999999999998"),
                        material(recipeC, recipeC + "-01", "0.000000000001")),
                List.of(PROC_1))));
        assertReachedBusinessLayer(b, "AC-15b 证伪对照组");
        assertEquals(200, b.statusCode(),
                "AC-15b（证伪对照组）：0.000000000001+99.999999999998+0.000000000001 合计正好 100，必须通过。"
                        + "被拒 ⇒ 合计判等用了 double（浮点下 = 99.99999999999999），须改 BigDecimal.compareTo。实际="
                        + b.statusCode() + " " + b.asString());
        String partB = latestLinePartNo(fxB);
        System.out.println("[AC-15b] 料号=" + partB + " ratios="
                + col("SELECT material_ratio::text FROM material_bom_item WHERE customer_no='"
                + fxB.customerNo() + "' AND material_no='" + partB + "' AND is_current=true ORDER BY seq_no"));
    }

    /**
     * <b>AC-17</b>（边界，后端侧）：「零件已添加材质 {@code 00006/AgNi10}，再次添加同一材质」——
     * 前端是灰显防错（E2E 验），后端必须硬拦：api.md ⇒ 400 {@code MATERIAL_DUPLICATED}。
     */
    @Test
    @DisplayName("AC-17 同一零件内材质重复 → 400 MATERIAL_DUPLICATED")
    void ac17_duplicatedRecipeInSamePartRejected() {
        Fx fx = newFixture("ac17");

        Response res = configure(fx, submitBody(PREFIX + "G", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "50"), material(RECIPE_A, CONFIG_A, "50")),
                List.of(PROC_1))));
        assertReachedBusinessLayer(res, "AC-17 提交");
        System.out.println("[AC-17] " + res.statusCode() + " " + res.asString());

        assertEquals(400, res.statusCode(),
                "AC-17：同一零件内重复 recipeCode 应被拒，实际 " + res.statusCode() + " " + res.asString());
        assertTrue(res.asString().contains("MATERIAL_DUPLICATED"),
                "AC-17：错误码应为 MATERIAL_DUPLICATED，实际=" + res.asString());
    }

    /**
     * <b>AC-23</b>（边界）：「品名输入 101 个字符」⇒ 前端拦住 <b>或</b> 后端返 400 {@code PART_TEXT_TOO_LONG}；
     * 🚨「<b>绝不允许落库截断</b> —— 截断后指纹算的是截断值、实际内容是另一个，两个不同产品会算出相同指纹 ⇒ 静默错价」。
     *
     * <p>本用例验后端半句 + 那条 🚨：无论返回什么，{@code material_master} 都不得出现截断行。
     */
    @Test
    @DisplayName("AC-23 品名 101 字符 → 400 PART_TEXT_TOO_LONG，且绝不落库截断")
    void ac23_overlongPartNameRejectedAndNeverTruncated() {
        Fx fx = newFixture("ac23");
        String longName = PREFIX + "长".repeat(101 - PREFIX.length());   // 恰好 101 字符
        assertEquals(101, longName.length(), "构造自检：品名应恰好 101 字符");

        Response res = configure(fx, submitBody(PREFIX + "H", newPart(
                longName, "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "100")), List.of(PROC_1))));
        assertReachedBusinessLayer(res, "AC-23 提交");
        System.out.println("[AC-23] " + res.statusCode() + " " + res.asString());

        assertEquals(400, res.statusCode(),
                "AC-23：品名 101 字符应被后端拦（api.md：400 PART_TEXT_TOO_LONG），实际 "
                        + res.statusCode() + " " + res.asString());
        assertTrue(res.asString().contains("PART_TEXT_TOO_LONG"),
                "AC-23：错误码应为 PART_TEXT_TOO_LONG，实际=" + res.asString());

        // 🚨 AC-23 的硬约束：不得落库截断（即使实现改成 200，这条也必须成立）
        long truncated = count("SELECT count(*) FROM material_master WHERE material_name LIKE '"
                + PREFIX + "长%'");
        assertEquals(0, truncated,
                "AC-23：material_master 出现了品名被截断的行（" + truncated + " 行）—— 截断会让指纹与实际内容不一致 ⇒ 静默错价");
    }

    /**
     * <b>AC-5 关联（api.md §1.2）</b>：{@code partType=OUTSOURCED} 且 {@code outsourcedPartNo} 为空
     * ⇒ 400 {@code OUTSOURCED_PART_REQUIRED}。
     * <p>📌 归在本类是因为它是「提交校验」族；外购件候选列表的 AC-5 正文在
     * {@code MaterialAndOutsourcedAcTest}。
     */
    @Test
    @DisplayName("AC-5(校验侧) 外购件未选料号 → 400 OUTSOURCED_PART_REQUIRED")
    void ac5_outsourcedWithoutPartNoRejected() {
        Fx fx = newFixture("ac5v");
        Map<String, Object> part = outsourcedPart(null, List.of(PROC_1));

        Response res = configure(fx, submitBody(PREFIX + "I", part));
        assertReachedBusinessLayer(res, "AC-5 校验侧提交");
        System.out.println("[AC-5校验] " + res.statusCode() + " " + res.asString());

        assertEquals(400, res.statusCode(),
                "api.md §1.2：partType=OUTSOURCED 但 outsourcedPartNo 为空应被拒，实际 " + res.statusCode());
        assertTrue(res.asString().contains("OUTSOURCED_PART_REQUIRED"),
                "错误码应为 OUTSOURCED_PART_REQUIRED，实际=" + res.asString());
    }

    /**
     * 「被拒的提交不得落库」的<b>可 hermetic 断言</b>。
     *
     * <p>🚨 <b>不要用 {@code count(*) FROM material_master} 这种全局计数</b>：
     * 本套用例跑在<b>共享开发库</b>上，别的会话（dev server、另一个 agent 的测试）随时会改它 ⇒
     * 「前后差 1」既可能是本次提交落了行，也可能是别人插了行。
     * 2026-09-03 第三轮实测就出现过一次 {@code expected:<1889> but was:<1890>} 的假失败。
     * ⇒ 一律改为<b>按本用例自建客户 / 报价单</b>取范围，与其他会话完全隔离。
     */
    private void assertNothingLanded(Fx fx, String ac) {
        long bom = count("SELECT count(*) FROM material_bom_item WHERE customer_no='" + fx.customerNo() + "'");
        long sig = count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + fx.customerNo() + "'");
        long line = count("SELECT count(*) FROM quotation_line_item WHERE quotation_id='" + fx.quotationId() + "'");
        System.out.println("[" + ac + "] 被拒后落库检查 bom=" + bom + " signature=" + sig + " lineItem=" + line);
        assertEquals(0, bom, ac + "：被拒的提交不得落 material_bom_item");
        assertEquals(0, sig, ac + "：被拒的提交不得落 sel_part_signature");
        assertEquals(0, line, ac + "：被拒的提交不得往报价单加行");
    }
}
