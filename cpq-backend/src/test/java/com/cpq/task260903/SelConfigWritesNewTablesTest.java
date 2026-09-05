package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        // 🚨 2026-09-04 修正（本条原先是我的用例缺陷，不是产品缺陷）：
        //    「零件 + 外购件」提交出来的是 COMPOSITE，响应 lineItems 有三层：
        //      COMPOSITE 父料号 / PART 零件子料号 / PART 外购件子料号。
        //    latestLinePartNo() 拿到的是**父料号**，而元素含量属于**零件子料号** ——
        //    断在父料号上恒为 0 行。实证：同一轮里 SIMPLE 提交 element_bom=5，
        //    COMPOSITE 父料号 element_bom=0、material_bom=4。
        //    ⇒ 元素断言必须打在零件子料号上。
        String childPartNo = childPartNoOf(res, outsourced);
        System.out.println("[A-AC-1③] 父料号=" + partNo + " 零件子料号=" + childPartNo);
        assertNotNull(childPartNo,
                "A-AC-1③ 前置：响应里应有一个非外购件的 PART 子料号，实际取不到 ⇒ 断言会打错靶。响应=" + res.asString());
        long elem = dsElementBom(childPartNo);
        System.out.println("[A-AC-1③] " + childPartNo + " 的 ds_quote_element_bom 行数=" + elem);
        assertTrue(elem > 0,
                "A-AC-1③：零件子料号 " + childPartNo + " 应落元素含量（2 个材质各自的元素组成），实际 0 行");

        // ④ version_no 全部 = 1 —— 🚨 先证明有行, 否则「全 1」在 0 行时也成立（test.md §3 第 3 号陷阱）
        assertVersionAllOne("ds_quote_material_bom", partNo, bomRows, "A-AC-1④");
        assertVersionAllOne("ds_quote_element_bom", childPartNo, elem, "A-AC-1④");

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
    @DisplayName("A-AC-7 本次流程创建的料号带上 material_type")
    void aac7_outsourcedPartKeepsItsMaterialType() {
        // 前置：V409 必须已落（该列在 V409 之前不存在）
        assertTrue(count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name='ds_quote_material' AND column_name='material_type'") == 1,
                "A-AC-7 前置：ds_quote_material.material_type 列不存在 ⇒ V409 尚未落地。"
                        + "这是**环境前置缺失**，不是被测功能的结论。");

        String outsourced = anOutsourcedPartNo();
        // 🚨 口径（2026-09-04 主线裁决，解释②）：A-AC-7 约束的是**流程产出**，不是存量数据的完整性。
        //    补齐既有 IMPORT 行属于数据治理；让「选配提交」顺带改写别人导入的数据，比留 null 更危险。
        //    ⇒ 本用例只对「本轮新建的行」断言。
        boolean outsourcedPreexisted = dsMaterial(outsourced) > 0;
        String preInfo = outsourcedPreexisted
                ? String.valueOf(rows("SELECT source, coalesce(material_type,'(null)'), created_at::text "
                        + "FROM ds_quote_material WHERE material_no='" + outsourced + "'")
                        .stream().map(java.util.Arrays::toString).toList())
                : "(本轮之前不存在)";
        System.out.println("[A-AC-7] 外购件 " + outsourced + " 提交前在 ds_quote_material 的状态=" + preInfo);

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

        // ── 总能跑的正向断言：本轮新建的**零件子料号**必须带 material_type ──
        // 🚨 第三次踩同一个坑的记录（2026-09-04）：latestLinePartNo() 在 COMPOSITE 提交下返回的是
        //    **父料号**，而父件既不是零件也不是外购件。实测规律很干净：
        //      普通/子件 25 条 → material_type='零件'；COMPOSITE 父件 1 条 → null。
        //    A-AC-7 原文约束的是「外购件料号」，🚫 从未约束 COMPOSITE 父件 ⇒ 不许对父件断言。
        //    「COMPOSITE 父件该不该有 material_type」是产品决策，已单独报主线，不在本用例里替它裁决。
        String childPartNo = childPartNoOf(res, outsourced);
        assertNotNull(childPartNo, "A-AC-7 前置：响应里应有非外购件的 PART 子料号。响应=" + res.asString());
        List<Object[]> created = rows("SELECT material_no, coalesce(material_type,'(null)') "
                + "FROM ds_quote_material WHERE material_no='" + childPartNo + "'");
        System.out.println("[A-AC-7] 父料号=" + partNo + "（🚫 不对它断言）；零件子料号主档="
                + created.stream().map(java.util.Arrays::toString).toList());
        assertEquals(1, created.size(), "A-AC-7 前置：本轮铸出的零件子料号 " + childPartNo + " 应有 1 条主档");
        assertEquals("零件", String.valueOf(created.get(0)[1]),
                "A-AC-7：本次流程创建的零件子料号 " + childPartNo + " 的 material_type 应为『零件』，实际="
                        + created.get(0)[1] + " ⇒ 流程产出没有带上身份");

        // ── 外购件那一条：只有当它确实由本轮创建时才断言 ──
        if (outsourcedPreexisted) {
            // 🚩 🚫 不许在这里 assertTrue(true) 蒙混过关：场景没被构造出来就是**没验证**，
            //    JUnit 的 abort 会把它记成 aborted 而不是 passed —— 报告里看得见这个洞。
            Assumptions.abort("A-AC-7 未验证（场景不可构造，非产品结论）："
                    + "外购件 " + outsourced + " 在本轮之前就已存在于 ds_quote_material " + preInfo
                    + "，因此本次提交不会创建它，「新建行是否带 material_type」这一问无从验起。"
                    + "\n  ⚠️ 库里 material_type='外购件' 的料号只有这一个，且已被导入过 ⇒ 无法另选。"
                    + "\n  📌 真实风险仍在（已报主线进 BL）：只存在于新表、V6 里没有的外购件会丢身份；"
                    + "V6 里有的靠兼容视图 V6 侧兜得住（实测 " + outsourced + " 在 material_master 里是『"
                    + scalar("SELECT material_type FROM material_master WHERE material_no='" + outsourced + "'") + "』）。");
        }
        List<Object[]> mats = rows("SELECT material_no, material_type FROM ds_quote_material "
                + "WHERE material_no='" + outsourced + "'");
        assertEquals(1, mats.size(),
                "A-AC-7：本轮新建的外购件料号 " + outsourced + " 应在 ds_quote_material 有且仅有 1 条主档，实际 "
                        + mats.size() + " 条");
        assertEquals("外购件", String.valueOf(mats.get(0)[1]),
                "A-AC-7：本轮新建的外购件料号 " + outsourced + " 的 material_type 必须是『外购件』，实际="
                        + mats.get(0)[1]);
    }

    /**
     * <b>A-AC-7 → A-AC-8 的链路补强</b>：外购件身份必须<b>穿得过兼容视图</b>，不能只存在于表里。
     *
     * <p>A-AC-7 原文只要求「{@code ds_quote_material.material_type} = 外购件」——那是<b>写入侧</b>。
     * 但 A-AC-8 要求「产品卡片正常渲染出外购件」，而渲染侧读的是
     * {@code v_compat_material_master}。⇒ 只验表侧会漏掉「写了但渲染不出来」这一整类。
     *
     * <p>🚨 实测（2026-09-04）本条<b>现在是红的</b>，且根因已定位：
     * {@code v_compat_material_master} 的新表侧把 {@code material_type} 硬写成
     * {@code NULL::character varying(50)}，于是新表独有的外购件（如 {@code S0003}）
     * 在视图里 {@code material_type} 为空 ——
     * {@code SELECT count(*) FROM v_compat_material_master WHERE material_type='外购件'}
     * 只数得到 V6 侧那一条，新表侧的一条都数不到。
     */
    @Test
    @DisplayName("A-AC-7→A-AC-8 外购件身份必须穿得过兼容视图（非仅落表）")
    void aac7_outsourcedTypeMustSurviveCompatView() {
        requireCompatViews();
        List<Object[]> lost = rows(
                "SELECT m.material_no, m.material_type, coalesce(v.material_type,'(空)') "
                        + "FROM ds_quote_material m JOIN v_compat_material_master v ON v.material_no = m.material_no "
                        + "WHERE m.material_type IS NOT NULL AND v.material_type IS DISTINCT FROM m.material_type "
                        + "ORDER BY m.material_no");
        System.out.println("[A-AC-7→8] material_type 在兼容视图里丢失/变形的料号="
                + lost.stream().map(java.util.Arrays::toString).toList());

        long haveType = count("SELECT count(*) FROM ds_quote_material WHERE material_type IS NOT NULL");
        assertTrue(haveType > 0,
                "A-AC-7→8 前置：ds_quote_material 里应有带 material_type 的料号，实际 0 条 ⇒ 本断言会空跑（假绿）");

        assertEquals(List.of(), lost.stream().map(java.util.Arrays::toString).toList(),
                "A-AC-7→8：ds_quote_material.material_type 必须原样透传到 v_compat_material_master，"
                        + "否则渲染侧看不到外购件身份（A-AC-8 会渲染不出外购件）。"
                        + "上面列出的每一行都是 表里有值 / 视图里没有。");
    }

    /**
     * 从提交响应里取「零件」子料号：{@code compositeType='PART'} 且不是外购件的那个。
     * <p>SIMPLE 提交时没有子层，直接返回唯一的行料号。
     */
    private String childPartNoOf(Response res, String outsourcedNo) {
        List<Map<String, Object>> items = res.jsonPath().getList("lineItems");
        if (items == null || items.isEmpty()) return null;
        if (items.size() == 1) return String.valueOf(items.get(0).get("productPartNo"));
        return items.stream()
                .filter(i -> "PART".equals(String.valueOf(i.get("compositeType"))))
                .map(i -> String.valueOf(i.get("productPartNo")))
                .filter(pn -> !pn.equals(outsourcedNo))
                .findFirst().orElse(null);
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
