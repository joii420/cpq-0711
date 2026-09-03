package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · <b>回归清单</b>（{@code test.md §3}：本次不改但必须验没坏）。
 *
 * <p>本类不验 AC，验的是「三层模型上线后，读出侧还对不对」。五项：
 * <ol>
 *   <li><b>R-1</b> {@code v_composite_child_materials} 对同一料号从返 1 行变 <b>N 行</b>（多材质）</li>
 *   <li><b>R-2</b> {@code material_name} 的 COALESCE 兜底：B-9 把 {@code material_type} 从材质名
 *       改成 {@code '零件'}，而它是该视图 {@code material_name} 的<b>第二兜底</b> ⇒
 *       材质名不得退化成「零件」</li>
 *   <li><b>R-3</b> 选配-元素页签：多材质后从 1 组变 <b>N 组</b></li>
 *   <li>🔴 <b>R-4</b> {@code v_composite_child_elements} 的<b>视图版本错位</b>守卫（B-15）</li>
 *   <li>🟡 <b>R-5</b> 外购件会不会流进「选配-材质」页签（评审 P2-13，现状是「没人想过」）</li>
 *   <li><b>R-6</b> {@code ExistingProductService} 的 {@code source} 判据改写后，
 *       既有导入产品仍为 {@code EXISTING}</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("task-260902 · 读出侧回归（test.md §3）")
class RenderRegressionAcTest extends SelConfigAcTestBase {

    /**
     * 🟡 <b>R-5 的裁决位</b>：外购件行（{@code characteristic='OUTSOURCED'}）会不会作为一行
     * 「材质」出现在选配-材质页签里。
     *
     * <p>现状 {@code v_composite_child_materials} 的过滤是
     * {@code characteristic IS DISTINCT FROM 'ASSEMBLY'}（<b>排除法，不是白名单</b>）⇒
     * B-7 写的 {@code OUTSOURCED} 行满足该条件，会被带出来。
     * <p>🚫 {@code test.md} 要求「明确断言它出现或不出现，二选一，不许含糊带过」。
     * <b>本常量给的是默认判定：外购件不是材质，不应出现。</b>
     * 主线若裁定为「应当出现」，把它改成 {@code true} 并在 {@code test-report.md} 记下裁决理由 ——
     * 🚫 但<b>不许把这条用例删掉或改成只打印不断言</b>。
     */
    private static final boolean EXPECT_OUTSOURCED_ROW_IN_MATERIALS_TAB = false;

    /**
     * <b>R-1 + R-2 + R-3</b>：提交一个双材质零件后，两个渲染视图必须同时对。
     */
    @Test
    @DisplayName("R-1/2/3 双材质料号：材质视图 2 行 + 名字不退化成「零件」+ 元素 2 组")
    void r1r2r3_multiMaterialRendersTwoRowsAndKeepsMaterialName() {
        Fx fx = newFixture("reg123");
        assertSubmitOk(configure(fx, submitBody(PREFIX + "R1", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1)))), "R-1 双材质提交");
        String partNo = latestLinePartNo(fx);

        // R-1：材质视图应返 2 行
        // 🚨 列映射（实查 viewdef，2026-09-03）：两个视图的 child_hf_part_no 语义**不同**，别照抄：
        //   v_composite_child_materials: hf_part_no = asy.material_no（销售料号）
        //                                child_hf_part_no = asy.component_no（**材质码** 00006/00123）
        //   v_composite_child_elements : child_hf_part_no = ebi.material_no（销售料号）
        // ⇒ 拿销售料号去匹配材质视图的 child_hf_part_no 必然 0 行（本用例首轮就栽在这儿，
        //   靠断言信息里那句「0 行 ⇒ 断言空跑」才没被误读成实现缺陷）。材质视图必须查 hf_part_no。
        List<Object[]> mats = rows("SELECT material_name, chemical_symbol, material_code FROM v_composite_child_materials "
                + "WHERE hf_part_no='" + partNo + "' ORDER BY material_code");
        System.out.println("[R-1] v_composite_child_materials=" + mats.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, mats.size(),
                "R-1：多材质料号在 v_composite_child_materials 应返 2 行（改造前只有 1 行），实际 " + mats.size()
                        + "。0 行 ⇒ 断言空跑；1 行 ⇒ 材质被折叠（AP-22『X (共2项)』族）");

        // R-2：material_name 不得退化成「零件」
        for (Object[] m : mats) {
            String name = String.valueOf(m[0]);
            assertFalse("零件".equals(name),
                    "R-2：material_name 退化成了「零件」—— B-9 把 material_type 改成 '零件' 后，"
                            + "component_usage_type 没写材质名，COALESCE 落到了第二兜底。实际行=" + java.util.Arrays.toString(m));
        }
        Set<String> names = Set.copyOf(mats.stream().map(m -> String.valueOf(m[0])).toList());
        assertEquals(Set.of(RECIPE_A_SYMBOL, RECIPE_B_SYMBOL), names,
                "R-2：两行的材质名应分别是 " + RECIPE_A_SYMBOL + " / " + RECIPE_B_SYMBOL + "，实际=" + names);

        // R-3：元素视图应含两个材质各自的元素
        List<Object[]> els = rows("SELECT element_name, composition_pct::text, seq_no FROM v_composite_child_elements "
                + "WHERE child_hf_part_no='" + partNo + "' ORDER BY seq_no");
        System.out.println("[R-3] v_composite_child_elements=" + els.stream().map(java.util.Arrays::toString).toList());
        assertFalse(els.isEmpty(), "R-3：元素视图返 0 行 ⇒ 下面的断言会空跑");
        assertEquals(5, els.size(),
                "R-3：AgNi10(Ag,Ni) + AgZnO12/Cu(Ag,Cu,Zn) 共 5 行元素，实际 " + els.size()
                        + " —— 少于 5 说明有一组被吞（B-15 视图版本错位族）");
    }

    /**
     * 🔴 <b>R-4：{@code v_composite_child_elements} 的视图版本错位守卫</b>（B-15，A 轮遗漏）。
     *
     * <p>{@code element_bom_item.characteristic} 是 {@code VersionedV6Writer} 的<b>版本列</b>，
     * 按 groupKey <b>独立递增</b>；而该视图的相关子查询
     * {@code AND ebi.characteristic = (SELECT max(...) WHERE system_type/customer_no/material_no 相同)}
     * <b>缺 {@code material_part_no} 维度</b> ⇒ 材质 A 升到 2001、材质 B 还停在 2000 时，
     * {@code max()} 取 2001 ⇒ <b>材质 B 的元素整组从页签消失，无任何报错</b>。
     *
     * <p>🚨 <b>存量扫描 0 组 ⇒ 本任务是第一次给这个潜伏缺陷通电，首次写入必然假绿</b>
     * （两组同时写入、版本相同，看不出问题）⇒ 本用例<b>直接在数据层把版本错位造出来</b>，
     * 再读视图。这样它不依赖「写入侧什么时候会升版」这个实现细节，只验读出侧的正确性。
     * <p>🚫 构造面严格限定在本用例自建客户的这一个料号上，@AfterEach 随客户整体清除。
     */
    @Test
    @DisplayName("R-4 只有一个材质升版时，元素页签必须仍是 2 组（B-15 守卫）")
    void r4_viewMustNotDropGroupsWhenOneMaterialVersionAdvances() {
        Fx fx = newFixture("reg4");
        assertSubmitOk(configure(fx, submitBody(PREFIX + "R4", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1)))), "R-4 双材质提交");
        String partNo = latestLinePartNo(fx);

        List<Object[]> before = rows("SELECT material_part_no, characteristic, count(*) FROM element_bom_item "
                + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true "
                + "GROUP BY material_part_no, characteristic ORDER BY material_part_no");
        System.out.println("[R-4] 升版前分组=" + before.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, before.size(), "R-4 前置：应有 2 组元素，实际 " + before.size() + " —— 前置不成立则本守卫空跑");
        assertEquals(1, Set.copyOf(before.stream().map(b -> String.valueOf(b[1])).toList()).size(),
                "R-4 前置：两组的 characteristic（版本）此刻应相同，实际=" + before.stream()
                        .map(b -> String.valueOf(b[1])).toList());
        long viewBefore = count("SELECT count(*) FROM v_composite_child_elements WHERE child_hf_part_no='" + partNo + "'");
        assertTrue(viewBefore > 0, "R-4 前置：视图此刻应能读到元素行");

        // 构造版本错位：只把「第一组」的版本 +1，另一组原地不动
        String driftGroup = String.valueOf(before.get(0)[0]);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE element_bom_item SET characteristic = (characteristic::int + 1)::text "
                                + "WHERE customer_no=:c AND material_no=:m AND material_part_no=:p AND is_current=true")
                .setParameter("c", fx.customerNo()).setParameter("m", partNo).setParameter("p", driftGroup)
                .executeUpdate());
        List<Object[]> after = rows("SELECT material_part_no, characteristic FROM element_bom_item "
                + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true "
                + "GROUP BY material_part_no, characteristic ORDER BY material_part_no");
        System.out.println("[R-4] 升版后分组=" + after.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, Set.copyOf(after.stream().map(a -> String.valueOf(a[1])).toList()).size(),
                "R-4 构造自检：两组的版本此刻应<b>不同</b>，否则这个守卫根本没被通电（假绿）");

        long groups = count("SELECT count(DISTINCT child_part_name) FROM v_composite_child_elements "
                + "WHERE child_hf_part_no='" + partNo + "'");
        long viewRows = count("SELECT count(*) FROM v_composite_child_elements WHERE child_hf_part_no='" + partNo + "'");
        System.out.println("[R-4] 视图行数 升版前=" + viewBefore + " 升版后=" + viewRows + "（分组数=" + groups + "）");
        assertEquals(viewBefore, viewRows,
                "🔴 R-4：只有一个材质升版后，v_composite_child_elements 的行数变了（"
                        + viewBefore + " → " + viewRows + "）⇒ max(characteristic) 子查询缺 material_part_no 维度，"
                        + "停在旧版本的那组元素被<b>静默吞掉</b>（无任何报错）。修法见 backtask B-15");
    }

    /**
     * 🟡 <b>R-5</b>：外购件行会不会作为一行「材质」出现在选配-材质页签。
     * 判定见 {@link #EXPECT_OUTSOURCED_ROW_IN_MATERIALS_TAB} 的注释。
     */
    @Test
    @DisplayName("R-5 外购件是否出现在选配-材质页签（明确断言，不许含糊）")
    void r5_outsourcedRowInMaterialsTab() {
        String outsourcedNo = scalar("SELECT material_no FROM material_master WHERE material_type='外购件' LIMIT 1");
        assertTrue(outsourcedNo != null && !outsourcedNo.isBlank(),
                "R-5 前置：库里应至少有 1 条外购件（fixture基线 §3.1）");

        Fx fx = newFixture("reg5");
        assertSubmitOk(configure(fx, submitBody(PREFIX + "R5",
                        newPart("触点", "φ5", "5×3×2", "10",
                                List.of(material(RECIPE_A, CONFIG_A, "100")), List.of(PROC_1)),
                        outsourcedPart(outsourcedNo, List.of(PROC_2)))),
                "R-5 零件 + 外购件提交");

        // 列映射同 R-1：材质视图按 hf_part_no（销售料号）关联，child_hf_part_no 是材质码
        List<Object[]> mats = rows("SELECT v.hf_part_no, v.child_hf_part_no, v.material_name "
                + "FROM v_composite_child_materials v JOIN material_bom_item b "
                + "  ON b.material_no = v.hf_part_no AND b.component_no = v.child_hf_part_no "
                + " AND b.customer_no='" + fx.customerNo() + "' GROUP BY 1,2,3");
        System.out.println("[R-5] 本客户在材质视图里的行=" + mats.stream().map(java.util.Arrays::toString).toList());
        long outsourcedRows = count("SELECT count(*) FROM material_bom_item WHERE customer_no='"
                + fx.customerNo() + "' AND characteristic='OUTSOURCED' AND is_current=true");
        System.out.println("[R-5] 本客户的 OUTSOURCED 行数=" + outsourcedRows);
        assertTrue(outsourcedRows > 0,
                "R-5 前置：外购件应落 material_bom_item 且 characteristic='OUTSOURCED'（B-7；实测该值现网 0 行，"
                        + "本任务是第一次写它）—— 0 行则本判定空跑");

        boolean appears = count("SELECT count(*) FROM v_composite_child_materials v "
                + "WHERE EXISTS (SELECT 1 FROM material_bom_item b WHERE b.customer_no='" + fx.customerNo() + "' "
                + "AND b.characteristic='OUTSOURCED' AND b.is_current=true "
                + "AND b.material_no = v.hf_part_no AND b.component_no = v.child_hf_part_no)") > 0;
        System.out.println("[R-5] 外购件出现在材质视图？ " + appears);
        assertEquals(EXPECT_OUTSOURCED_ROW_IN_MATERIALS_TAB, appears,
                "R-5：外购件是否作为『材质』出现在选配-材质页签，与裁决不一致（当前裁决="
                        + EXPECT_OUTSOURCED_ROW_IN_MATERIALS_TAB + "，实际=" + appears + "）。"
                        + "视图过滤用的是排除法 characteristic IS DISTINCT FROM 'ASSEMBLY'，OUTSOURCED 会满足它。"
                        + "改判定请改常量并在 test-report.md 写明理由，🚫 不许删本用例");
    }

    /**
     * <b>R-6</b>：{@code ExistingProductService} 的 {@code source} 判据从
     * 「{@code customer_product_no} 是否为空」改为「按<b>来源表</b>判定」（B-16b）后，
     * <b>既有导入产品的 {@code source} 仍必须是 {@code EXISTING}</b>。
     */
    @Test
    @DisplayName("R-6 既有导入产品的 source 仍为 EXISTING（判据改写不回归）")
    void r6_importedProductStillMarkedExisting() {
        Fx fx = newFixture("reg6");
        String partNo = "T2609" + fx.customerId().toString().replace("-", "").substring(0, 8);
        String productNo = PREFIX + "IMPORTED";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO material_master (id,material_no,material_name,material_type,created_at,updated_at) "
                            + "VALUES (gen_random_uuid(),:p,:n,'零件',NOW(),NOW())")
                    .setParameter("p", partNo).setParameter("n", PREFIX + "导入产品").executeUpdate();
            em.createNativeQuery("INSERT INTO material_customer_map (id,system_type,material_no,customer_no,customer_product_no,created_at,updated_at) "
                            + "VALUES (gen_random_uuid(),'QUOTE',:p,:c,:pn,NOW(),NOW())")
                    .setParameter("p", partNo).setParameter("c", fx.customerNo())
                    .setParameter("pn", productNo).executeUpdate();
        });

        Response list = RestAssured.given()
                .get("/api/cpq/quotations/" + fx.quotationId() + "/existing-products").thenReturn();
        assertReachedBusinessLayer(list, "R-6 从产品库添加列表");
        assertEquals(200, list.statusCode(), "R-6：列表端点应 200，实际=" + list.asString());
        String body = list.asString();
        System.out.println("[R-6] existing-products=" + body);
        assertTrue(body.contains(partNo), "R-6：导入产品应出现在列表里，实际=" + body);
        assertTrue(body.contains("EXISTING"),
                "R-6：来自 material_customer_map 的产品 source 必须仍是 EXISTING（不得被 B-16b 的判据改写静默翻转），实际=" + body);
    }
}
