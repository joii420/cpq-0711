package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · 接口层验收：<b>材质选择器数据源、含量配置、外购件</b>。
 *
 * <p>覆盖 <b>AC-5 / AC-5b / AC-6 / AC-16 / AC-18 / AC-18b / AC-21</b> 的<b>后端半句</b>；
 * 灰显、tooltip、空态文案等 UI 半句在 {@code e2e/task260902-selectors.spec.ts}。
 *
 * <h3>🚨 全局状态登记（{@code testing.md §4.3}）</h3>
 * {@link #ac16_emptyOutsourcedListReturnsEmptyNotError()} 是本套用例<b>唯一一处「改现存行」</b>：
 * 为了构造「外购件 0 条」（现网恰好 1 条，直接跑会看到列表而不是空态），它会临时把
 * {@code material_master.material_type='外购件'} 的行改成一个哨兵值，<b>并在 {@code finally} 里逐行还原 + 还原自检</b>。
 * 🚫 不删行、不改其它列。
 */
@QuarkusTest
@DisplayName("task-260902 · 材质与外购件（AC-5/5b/6/16/18/18b/21）")
class MaterialAndOutsourcedAcTest extends SelConfigAcTestBase {

    private static final String RECIPES = "/api/cpq/material-recipes";
    private static final String OUTSOURCED_TYPE = "外购件";
    /** AC-16 构造空态时用的哨兵值，仅在该用例的事务窗口内存在。 */
    private static final String SENTINEL_TYPE = "T260902-临时非外购件";

    /**
     * <b>AC-5</b>：「选『外购件』类型 → 打开外购件选择列表」⇒
     * 「列表<b>只列</b> {@code material_master.material_type='外购件'} 的料号
     * （SQL 对账：列表项集合 = 该条件的查询结果集合）」。
     *
     * <p>⚠️ 实测当前只有 1 条（{@code TEST-Q13-CODE / 组成件1}，规格与单重<b>均为空</b>），
     * 🚫 用例<b>不写死条数</b>（共享库会漂移），改为与 SQL 结果<b>集合相等</b>对账。
     */
    @Test
    @DisplayName("AC-5 外购件候选集合 == SQL 对账集合")
    void ac5_outsourcedCandidatesMatchSqlExactly() {
        Set<String> expected = new LinkedHashSet<>(col(
                "SELECT material_no FROM material_master WHERE material_type='" + OUTSOURCED_TYPE + "' ORDER BY material_no")
                .stream().map(String::valueOf).toList());
        System.out.println("[AC-5] SQL 对账集合=" + expected);
        assertFalse(expected.isEmpty(),
                "AC-5 前置：库里应至少有 1 条外购件（fixture基线 §3.1：TEST-Q13-CODE）。"
                        + "0 条时本用例会退化成空对空的假绿 ⇒ 前置不成立就硬失败，请先补数据");

        Response res = RestAssured.given().queryParam("page", 1).queryParam("size", 200)
                .get(OUTSOURCED_PARTS).thenReturn();
        assertReachedBusinessLayer(res, "AC-5 外购件候选");
        assertEquals(200, res.statusCode(), "AC-5：端点应返回 200，实际=" + res.asString());
        System.out.println("[AC-5] 响应=" + res.asString());

        Set<String> actual = new LinkedHashSet<>(res.jsonPath().getList("items.materialNo", String.class));
        assertEquals(expected, actual,
                "AC-5：候选集合必须与 material_type='外购件' 的查询结果集合逐项相等（多一条少一条都算错）");
    }

    /**
     * <b>AC-16</b>（边界）：「外购件列表为<b>空</b>（{@code material_type='外购件'} 零条时）」⇒
     * 「显示空态文案 + 指路到料号维护，<b>不得</b>显示『加载中…』永久占位（AP-31 族）」。
     *
     * <p>后端半句 = <b>返回 0 条是正常业务状态，不是错误</b>：HTTP 200 + {@code total=0} + 空数组，
     * 🚫 不得 4xx/5xx（那会让前端渲染成错误态而不是空态）。空态文案由 E2E 覆盖。
     *
     * <p>🚨 <b>本用例自己构造 0 条场景，不依赖库当时的状态</b>（{@code test.md §4} 第 4 号假绿陷阱：
     * 现网有 1 条，直接跑会看到列表而非空态）。改动 + 还原都在本方法的 try/finally 里。
     */
    @Test
    @DisplayName("AC-16 外购件 0 条 → 200 + total=0（不是错误、不是加载中）")
    void ac16_emptyOutsourcedListReturnsEmptyNotError() {
        List<Object> affected = col("SELECT material_no FROM material_master WHERE material_type='"
                + OUTSOURCED_TYPE + "' ORDER BY material_no");
        System.out.println("[AC-16] 将临时改写这些行的 material_type：" + affected);
        assertFalse(affected.isEmpty(), "AC-16 前置：需要至少 1 条外购件才能构造『改成 0 条』的场景");

        try {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                            "UPDATE material_master SET material_type=:s WHERE material_type=:o")
                    .setParameter("s", SENTINEL_TYPE).setParameter("o", OUTSOURCED_TYPE).executeUpdate());
            assertEquals(0, count("SELECT count(*) FROM material_master WHERE material_type='" + OUTSOURCED_TYPE + "'"),
                    "AC-16 构造自检：外购件应已变成 0 条，否则本用例验的不是空态场景（假绿）");

            Response res = RestAssured.given().queryParam("page", 1).queryParam("size", 20)
                    .get(OUTSOURCED_PARTS).thenReturn();
            assertReachedBusinessLayer(res, "AC-16 空外购件列表");
            System.out.println("[AC-16] " + res.statusCode() + " " + res.asString());
            assertEquals(200, res.statusCode(),
                    "AC-16：外购件 0 条是<b>正常业务状态</b>，端点必须 200（4xx/5xx 会让前端渲染成错误态），实际=" + res.asString());
            assertEquals(0, ((Number) res.jsonPath().get("total")).intValue(),
                    "AC-16：total 应为 0，实际=" + res.asString());
            assertTrue(res.jsonPath().getList("items").isEmpty(),
                    "AC-16：items 应为空数组，实际=" + res.asString());
        } finally {
            // 🚨 还原写在 finally：用例中途崩溃也照样还原（testing.md §4.3）
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                            "UPDATE material_master SET material_type=:o WHERE material_type=:s")
                    .setParameter("o", OUTSOURCED_TYPE).setParameter("s", SENTINEL_TYPE).executeUpdate());
            long restored = count("SELECT count(*) FROM material_master WHERE material_type='" + OUTSOURCED_TYPE + "'");
            long residue = count("SELECT count(*) FROM material_master WHERE material_type='" + SENTINEL_TYPE + "'");
            System.out.println("[AC-16] 还原自检：外购件 " + restored + " 条，哨兵残留 " + residue + " 条");
            assertEquals(affected.size(), restored, "AC-16 还原自检：外购件条数应还原为 " + affected.size());
            assertEquals(0, residue, "AC-16 还原自检：不得残留哨兵 material_type");
        }
    }

    /**
     * <b>AC-5b</b>：「材质（<b>0 组 ACTIVE 配置</b>，事务外自建）→ 用它配零件并<b>直接 POST 提交</b>
     * （绕过前端灰显）」⇒「返回 <b>409 {@code RECIPE_HAS_NO_CONFIG}</b>」。
     *
     * <p>📌 前端灰显（AC-18b）是体验，本条是<b>后端正确性</b>，两者都要有。
     * <p>🚨 fixture：现网 258 条 ACTIVE 材质<b>每条恰好 1 组</b>，0 组的一条都没有 ⇒ 必须自建 + 清除。
     */
    @Test
    @DisplayName("AC-5b 材质无 ACTIVE 含量配置 → 409 RECIPE_HAS_NO_CONFIG")
    void ac5b_recipeWithoutConfigRejected() {
        String recipe = createRecipe("0", false, List.of());   // 0 组配置
        Fx fx = newFixture("ac5b");

        Response res = configure(fx, submitBody(PREFIX + "J", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(recipe, recipe + "-01", "100")), List.of(PROC_1))));
        assertReachedBusinessLayer(res, "AC-5b 提交");
        System.out.println("[AC-5b] " + res.statusCode() + " " + res.asString());

        assertEquals(409, res.statusCode(),
                "AC-5b：无 ACTIVE 含量配置的材质应返回 409，实际 " + res.statusCode() + " " + res.asString());
        assertTrue(res.asString().contains("RECIPE_HAS_NO_CONFIG"),
                "AC-5b：错误码应为 RECIPE_HAS_NO_CONFIG，实际=" + res.asString());
    }

    /**
     * <b>AC-6</b>：「材质 {@code 00006/AgNi10} 的 {@code allow_custom_content=false} →
     * 在该材质旁点『自定义含量』」⇒「入口<b>可见但禁用</b>」（UI 半句由 E2E 验）。
     * <p>后端半句（{@code test.md} T-06）：<b>强行 POST {@code elements} → 403
     * {@code RECIPE_CUSTOM_NOT_ALLOWED}</b>。
     * <p>⚠️ 前置直接取存量材质 {@code 00006}（实测 {@code allow_custom_content=false}），
     * 🚫 <b>本用例不修改它的开关</b> —— 改共享库的材质开关属全局状态污染。
     */
    @Test
    @DisplayName("AC-6 不支持自定义含量的材质 → 403 RECIPE_CUSTOM_NOT_ALLOWED")
    void ac6_customContentRejectedWhenSwitchOff() {
        assertEquals("false", scalar("SELECT allow_custom_content::text FROM material_recipe WHERE code='" + RECIPE_A + "'"),
                "AC-6 前置：材质 " + RECIPE_A + " 的 allow_custom_content 应为 false（fixture基线 §1.3：现网 0 条为 true）");
        Fx fx = newFixture("ac6");

        Response res = configure(fx, submitBody(PREFIX + "K", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(materialCustom(RECIPE_A, "100", List.of(el("Ag", "0.88"), el("Ni", "0.12")))),
                List.of(PROC_1))));
        assertReachedBusinessLayer(res, "AC-6 提交");
        System.out.println("[AC-6] " + res.statusCode() + " " + res.asString());

        assertEquals(403, res.statusCode(),
                "AC-6：allow_custom_content=false 时自定义含量应被拒（403），实际 "
                        + res.statusCode() + " " + res.asString());
        assertTrue(res.asString().contains("RECIPE_CUSTOM_NOT_ALLOWED")
                        || res.asString().contains("CUSTOM_CONTENT_NOT_ALLOWED"),
                "AC-6：错误码应为 RECIPE_CUSTOM_NOT_ALLOWED（api.md §1.2），实际=" + res.asString());
    }

    /**
     * <b>AC-21</b>：「事务外自建一条 {@code allow_custom_content=true} 的材质（标准配方 Ag=90/Ni=10）→
     * 切换到自定义含量，改成 Ag=88 / Ni=12，提交」⇒
     * ①「{@code element_bom_item} 落<b>自定义值</b> Ag=88 / Ni=12，<b>不是</b>标准配方的 90/10」；
     * ②「{@code material_recipe_config} / {@code material_recipe_element} <b>无任何新增行</b>（自定义不回流）」；
     * ③「提交成功，无 403」。
     *
     * <p>🚨 这是 S-5 的<b>正向路径</b>，也是 {@code task-260901} 打开 {@code allow_custom_content}
     * 之后<b>第一次真跑</b>它。
     * <p>🚫 fixture 不得引用 {@code AgCu85/AgCu90/AgNi90/AgNi95}（已清理的污染数据）。
     */
    @Test
    @DisplayName("AC-21 自定义含量正向落库 88/12，且不回流材质库")
    void ac21_customContentPersistsAndDoesNotFlowBack() {
        String recipe = createRecipe("21", true, List.of(new String[]{"Ag", "90"}, new String[]{"Ni", "10"}));
        long cfgBefore = count("SELECT count(*) FROM material_recipe_config");
        long eleBefore = count("SELECT count(*) FROM material_recipe_element");
        Fx fx = newFixture("ac21");

        Response res = configure(fx, submitBody(PREFIX + "L", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(materialCustom(recipe, "100", List.of(el("Ag", "0.88"), el("Ni", "0.12")))),
                List.of(PROC_1))));
        // ③ 提交成功，无 403
        assertSubmitOk(res, "AC-21 自定义含量提交");
        assertFalse(res.statusCode() == 403, "AC-21③：不得 403");

        // ① element_bom_item 落 88 / 12
        String partNo = latestLinePartNo(fx);
        List<Object[]> els = rows("SELECT component_no, content::text FROM element_bom_item "
                + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true "
                + "ORDER BY seq_no");
        System.out.println("[AC-21①] element_bom_item=" + els.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, els.size(), "AC-21①：应落 2 行元素（Ag/Ni），实际 " + els.size() + " —— 0 行会让下面的断言空跑");
        BigDecimal ag = null, ni = null;
        for (Object[] r : els) {
            if ("Ag".equals(String.valueOf(r[0]))) ag = new BigDecimal(String.valueOf(r[1]));
            if ("Ni".equals(String.valueOf(r[0]))) ni = new BigDecimal(String.valueOf(r[1]));
        }
        assertNotNull(ag, "AC-21①：应能查到 Ag 那一行");
        assertNotNull(ni, "AC-21①：应能查到 Ni 那一行");
        assertEquals(0, ag.compareTo(new BigDecimal("88")),
                "AC-21①：Ag 应落自定义值 88（不是标准配方的 90），实际=" + ag);
        assertEquals(0, ni.compareTo(new BigDecimal("12")),
                "AC-21①：Ni 应落自定义值 12（不是标准配方的 10），实际=" + ni);

        // ② 不回流材质库
        assertEquals(cfgBefore, count("SELECT count(*) FROM material_recipe_config"),
                "AC-21②：material_recipe_config 不得新增（自定义含量不回流材质库）");
        assertEquals(eleBefore, count("SELECT count(*) FROM material_recipe_element"),
                "AC-21②：material_recipe_element 不得新增（自定义含量不回流材质库）");
    }

    /**
     * <b>AC-18</b>（边界）的<b>数据源半句</b>：材质选择器的六次搜索输入，逐条对账后端返回。
     * UI 半句（每行显示什么、空态文案、虚拟滚动）在 E2E。
     *
     * <p>断言逐条对应 AC-18 原文：
     * ①「每行显示材质编号/材质名/首个配置的含量/<b>含量配置组数</b>/<b>是否支持自定义</b>」⇒
     *   响应须含 {@code code} / {@code symbol} / {@code configCount} / {@code allowCustomContent}；
     * ②「{@code 00006} 筛出 {@code 00006/AgNi10} <b>恰好 1 条</b>」；
     * ③「{@code AgNi} 命中 <b>≥40 条</b>，且<b>必含</b> {@code 00006/AgNi10} 与 {@code 00197/AgNi10/Ag15CuP}」；
     * ④「{@code agni} 与③<b>编号集合完全相同</b>（大小写不敏感）—— 🚫 不断言条数，会漂移」；
     * ⑤「{@code 镀铜} 筛出 {@code 00150/DCO3镀铜} 与 {@code 00151/铁镀铜}（中文名可搜）」；
     * ⑥「{@code 法兰} → 空集」。
     */
    @Test
    @DisplayName("AC-18 材质选择器六种搜索输入（后端数据源对账）")
    void ac18_recipeSearchSixInputs() {
        // ① 空输入：字段齐全
        Response all = searchRecipes("");
        assertEquals(200, all.statusCode(), "AC-18①：材质列表应 200，实际=" + all.asString());
        assertFalse(all.jsonPath().getList("code").isEmpty(),
                "AC-18①：ACTIVE 材质列表不得为空（空 ⇒ 后面所有断言空跑）");
        System.out.println("[AC-18①] 共 " + all.jsonPath().getList("code").size() + " 条");
        assertNotNull(all.jsonPath().get("[0].symbol"), "AC-18①：每行须有材质名 symbol");
        assertNotNull(all.jsonPath().get("[0].configCount"), "AC-18①：每行须有含量配置组数 configCount");
        assertNotNull(all.jsonPath().get("[0].allowCustomContent"), "AC-18①：每行须有是否支持自定义 allowCustomContent");

        // ② 00006 → 恰好 1 条
        List<String> byCode = searchRecipes("00006").jsonPath().getList("code", String.class);
        System.out.println("[AC-18②] 00006 → " + byCode);
        assertEquals(List.of(RECIPE_A), byCode, "AC-18②：搜 00006 应恰好命中 1 条 00006/AgNi10，实际=" + byCode);

        // ③ AgNi → ≥40 条且必含 00006 / 00197
        List<String> upper = searchRecipes("AgNi").jsonPath().getList("code", String.class);
        System.out.println("[AC-18③] AgNi → " + upper.size() + " 条");
        assertTrue(upper.size() >= 40, "AC-18③：搜 AgNi 应命中 ≥40 条（取数当日 42），实际 " + upper.size());
        assertTrue(upper.contains(RECIPE_A), "AC-18③：结果必含 00006/AgNi10");
        assertTrue(upper.contains("00197"), "AC-18③：结果必含 00197/AgNi10/Ag15CuP");

        // ④ agni → 与③编号集合完全相同（🚫 不断言条数）
        List<String> lower = searchRecipes("agni").jsonPath().getList("code", String.class);
        assertEquals(new LinkedHashSet<>(upper), new LinkedHashSet<>(lower),
                "AC-18④：大小写不敏感 ⇒ agni 与 AgNi 的编号集合必须完全相同");

        // ⑤ 镀铜 → 中文名可搜
        List<String> cn = searchRecipes("镀铜").jsonPath().getList("code", String.class);
        System.out.println("[AC-18⑤] 镀铜 → " + cn);
        assertTrue(cn.contains("00150"), "AC-18⑤：应筛出 00150/DCO3镀铜（中文名可搜），实际=" + cn);
        assertTrue(cn.contains("00151"), "AC-18⑤：应筛出 00151/铁镀铜，实际=" + cn);

        // ⑥ 法兰 → 空集（空态由 E2E 验文案）
        List<String> none = searchRecipes("法兰").jsonPath().getList("code", String.class);
        System.out.println("[AC-18⑥] 法兰 → " + none);
        assertTrue(none.isEmpty(), "AC-18⑥：搜『法兰』应无匹配，实际=" + none);
    }

    /**
     * <b>AC-18b</b>（边界）的<b>数据源半句</b>：「事务外构造一条 <b>0 组 ACTIVE 配置</b>的材质 →
     * 打开材质选择器」⇒「该材质<b>出现在列表中但灰显</b>…🚫 <b>不得从列表中过滤掉</b>」。
     *
     * <p>后端半句 = 该材质<b>必须出现在候选响应里</b>且 {@code configCount=0}（灰显的依据）。
     * 若后端把它过滤掉，前端就无从灰显 ⇒ 能力被隐藏（§1.2）。
     */
    @Test
    @DisplayName("AC-18b 0 组配置的材质仍出现在候选列表，且 configCount=0")
    void ac18b_zeroConfigRecipeStillListedWithZeroCount() {
        String recipe = createRecipe("18b", false, List.of());

        Response res = searchRecipes(PREFIX + "M18b");
        assertEquals(200, res.statusCode(), "AC-18b：材质列表应 200，实际=" + res.asString());
        List<String> codes = res.jsonPath().getList("code", String.class);
        System.out.println("[AC-18b] 命中=" + codes + " 原始=" + res.asString());
        assertTrue(codes.contains(recipe),
                "AC-18b：0 组配置的材质<b>不得被过滤掉</b>，应出现在列表里供前端灰显，实际=" + codes);
        Object configCount = res.jsonPath().get("find { it.code == '" + recipe + "' }.configCount");
        assertNotNull(configCount, "AC-18b：该行须带 configCount 供前端显示『0 组』");
        assertEquals(0, ((Number) configCount).intValue(),
                "AC-18b：含量配置组数应为 0（前端据此显示红色『0 组』+ 禁用『选择』），实际=" + configCount);
    }

    private Response searchRecipes(String keyword) {
        Response res = RestAssured.given()
                .queryParam("keyword", keyword).queryParam("status", "ACTIVE")
                .get(RECIPES).thenReturn();
        assertReachedBusinessLayer(res, "材质选择器数据源 keyword=" + keyword);
        return res;
    }
}
