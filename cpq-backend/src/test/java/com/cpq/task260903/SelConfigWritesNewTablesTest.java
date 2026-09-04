package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-AC-1 / A-AC-6 / A-AC-7</b>：选配写入侧的正向验收 —— 数据落到 {@code ds_quote_*} 四张新表。
 *
 * <p>A-AC-1 原文：「配一个零件（2 材质，占比 70/30）+ 1 外购件，提交」⇒
 * 「① {@code ds_quote_material} 落料号主档；② {@code ds_quote_material_bom} 落材质行，
 * {@code material_ratio} 存满 12 位小数；③ {@code ds_quote_element_bom} 落元素含量；
 * ④ <b>{@code version_no} 全部 = 1</b>」。
 */
@QuarkusTest
@DisplayName("A-AC-1/6/7 选配写入 ds_quote_* 四表")
class SelConfigWritesNewTablesTest extends Task260903Base {

    /** 库里的外购件料号；取不到就硬失败（否则 A-AC-6/7 会静默退化成只测单零件）。 */
    private String anOutsourcedPartNo() {
        String no = scalar("SELECT material_no FROM material_master WHERE material_type='外购件' "
                + "ORDER BY material_no LIMIT 1");
        assertNotNull(no, "前置：需要至少 1 个 material_type='外购件' 的料号。"
                + "0 条 ⇒ A-AC-6 的双投影与 A-AC-7 的外购件身份都无从验起，本用例会假绿 ⇒ 硬失败");
        return no;
    }

    /**
     * <b>A-AC-1</b> 主场景：零件（2 材质 70/30）+ 1 外购件 → 四表落行、{@code version_no} 全 1。
     */
    @Test
    @DisplayName("A-AC-1 四表落行 + version_no 全部 = 1 + 渲染侧读得到")
    void aac1_writesFourNewTablesWithVersionOne() {
        String outsourced = anOutsourcedPartNo();
        Fx fx = newFixture("aac1");

        Response res = configure(fx, submitBody(PREFIX + "A1",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-1 提交");

        String partNo = latestLinePartNo(fx);
        // ①② 主档 + BOM 行（本方法内部已含「非空」正向断言）
        long bomRows = assertNewTablesGotRows(partNo, "A-AC-1");

        // ③ 元素含量
        long elem = dsElementBom(partNo);
        System.out.println("[A-AC-1③] ds_quote_element_bom 行数=" + elem);
        assertTrue(elem > 0,
                "A-AC-1③：ds_quote_element_bom 应落元素含量（2 个材质各自的元素组成），实际 0 行");

        // ④ version_no 全部 = 1 —— 🚨 先证明有行, 否则「全 1」在 0 行时也成立（test.md §3 第 3 号陷阱）
        assertVersionAllOne("ds_quote_material_bom", partNo, bomRows, "A-AC-1④");
        assertVersionAllOne("ds_quote_element_bom", partNo, elem, "A-AC-1④");

        // 🚨 写了 ≠ 渲染得出来（兼容视图 BOM 侧要靠 ds_quote_customer_part 反查 customer_no）
        assertVisibleThroughCompatView(partNo, "A-AC-1");
    }

    /**
     * <b>A-AC-1②</b> 的精度专项：{@code material_ratio} <b>存满 12 位小数</b>。
     *
     * <p>📌 用 70/30 验不出精度 —— 它们在任何 scale 下都是精确的。
     * ⇒ 本用例故意用<b>把 12 位小数占满</b>的占比，若写入侧有任何四舍五入/截断，这里立刻红。
     */
    @Test
    @DisplayName("A-AC-1② material_ratio 存满 12 位小数不被截断")
    void aac1_materialRatioKeeps12Decimals() {
        Fx fx = newFixture("aac1p");
        String rA = "66.666666666666";
        String rB = "33.333333333334";   // 两者之和恰为 100

        Response res = configure(fx, submitBody(PREFIX + "A1P",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, rA),
                                material(RECIPE_B, CONFIG_B, rB)),
                        List.of(PROC_1))));
        assertSubmitOk(res, "A-AC-1② 精度提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-1②");

        List<Object> ratios = col("SELECT material_ratio::text FROM ds_quote_material_bom "
                + "WHERE material_no='" + partNo + "' ORDER BY material_ratio DESC");
        System.out.println("[A-AC-1②] 落库 material_ratio=" + ratios);
        assertTrue(ratios.size() >= 2,
                "A-AC-1② 前置：应至少 2 条材质行，实际 " + ratios.size() + " ⇒ 精度断言会空跑");

        for (Object r : ratios) {
            String t = String.valueOf(r);
            int scale = t.contains(".") ? t.length() - t.indexOf('.') - 1 : 0;
            assertEquals(12, scale,
                    "A-AC-1②：material_ratio 应存满 12 位小数，实际 '" + t + "' 只有 " + scale + " 位");
        }
        assertTrue(ratios.stream().map(String::valueOf).anyMatch(t -> t.startsWith("66.666666666666")),
                "A-AC-1②：提交的 " + rA + " 必须原样落库，未被四舍五入。实际=" + ratios);
        assertTrue(ratios.stream().map(String::valueOf).anyMatch(t -> t.startsWith("33.333333333334")),
                "A-AC-1②：提交的 " + rB + " 必须原样落库，未被四舍五入。实际=" + ratios);
    }

    /**
     * <b>A-AC-6</b>：「配一个 COMPOSITE 产品（零件 + 外购件）」⇒
     * 「{@code ds_quote_material_bom} 里该父料号<b>既有 {@code output_material_type='ASSEMBLY'} 行、
     * 也有 {@code ='RECIPE'} 行</b>（对齐 V6 双投影语义）」。
     */
    @Test
    @DisplayName("A-AC-6 双投影：同一父料号 ASSEMBLY 行与 RECIPE 行并存")
    void aac6_dualProjectionAssemblyAndRecipe() {
        String outsourced = anOutsourcedPartNo();
        Fx fx = newFixture("aac6");

        Response res = configure(fx, submitBody(PREFIX + "A6",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-6 提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-6");

        List<Object[]> proj = rows("SELECT output_material_type, count(*) FROM ds_quote_material_bom "
                + "WHERE material_no='" + partNo + "' GROUP BY 1 ORDER BY 1");
        System.out.println("[A-AC-6] 父料号 " + partNo + " 的投影分布="
                + proj.stream().map(java.util.Arrays::toString).toList());

        long assembly = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='"
                + partNo + "' AND output_material_type='ASSEMBLY'");
        long recipe = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='"
                + partNo + "' AND output_material_type='RECIPE'");
        assertTrue(assembly > 0,
                "A-AC-6：组合产品的父料号应有 output_material_type='ASSEMBLY' 行，实际 0 行。投影分布=" + proj);
        assertTrue(recipe > 0,
                "A-AC-6：同一父料号还应有 output_material_type='RECIPE' 行（双投影，对齐 V6 语义），"
                        + "实际 0 行。投影分布=" + proj);
    }

    /**
     * <b>A-AC-7</b>：「外购件料号在 {@code ds_quote_material.material_type} = <b>{@code 外购件}</b>」
     * （依赖 V409 落地）。
     */
    @Test
    @DisplayName("A-AC-7 外购件料号的 material_type='外购件'")
    void aac7_outsourcedPartKeepsItsMaterialType() {
        // 前置：V409 必须已落（该列在 V409 之前不存在）
        assertTrue(count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name='ds_quote_material' AND column_name='material_type'") == 1,
                "A-AC-7 前置：ds_quote_material.material_type 列不存在 ⇒ V409 尚未落地。"
                        + "这是**环境前置缺失**，不是被测功能的结论。");

        String outsourced = anOutsourcedPartNo();
        Fx fx = newFixture("aac7");

        Response res = configure(fx, submitBody(PREFIX + "A7",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-7 提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-7");

        // 外购件料号必须作为一条 ds_quote_material 存在，且身份是「外购件」
        List<Object[]> mats = rows("SELECT material_no, material_type FROM ds_quote_material "
                + "WHERE material_no='" + outsourced + "'");
        System.out.println("[A-AC-7] ds_quote_material 中的外购件行="
                + mats.stream().map(java.util.Arrays::toString).toList());
        assertEquals(1, mats.size(),
                "A-AC-7：外购件料号 " + outsourced + " 应在 ds_quote_material 有且仅有 1 条主档，实际 "
                        + mats.size() + " 条");
        assertEquals("外购件", String.valueOf(mats.get(0)[1]),
                "A-AC-7：外购件料号的 material_type 必须是『外购件』，实际=" + mats.get(0)[1]);
    }

    /** {@code version_no} 全 1 —— 先要求行数 > 0，否则「全 1」在 0 行时也成立。 */
    private void assertVersionAllOne(String table, String materialNo, long expectRows, String when) {
        assertTrue(expectRows > 0, when + "：" + table + " 应有行才能验 version_no，实际 0 行 ⇒ 断言会空跑");
        List<Object> vs = col("SELECT DISTINCT version_no FROM " + table
                + " WHERE material_no='" + materialNo + "' ORDER BY 1");
        System.out.println("[" + when + "] " + table + " 的 version_no 取值集合=" + vs + "（" + expectRows + " 行）");
        assertEquals(List.of(1), vs.stream().map(v -> ((Number) v).intValue()).toList(),
                when + "：A-AC-1④ 要求 " + table + " 的 version_no 全部 = 1（选配阶段不升版），实际取值=" + vs);
    }
}
