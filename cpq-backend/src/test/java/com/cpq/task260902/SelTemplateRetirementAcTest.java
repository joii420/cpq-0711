package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · 接口层验收：<b>选配模板下线（S-9）</b>。
 *
 * <p>覆盖 <b>AC-25</b>（无模板也能选配）与 <b>AC-26 的反向断言</b>（表与数据不得被删）。
 * AC-26 的菜单/路由半句在 {@code e2e/task260902-template-retire.spec.ts}。
 *
 * <h3>⚠️ AC-25 的前置在现网构造不出「纯净版」，本类如实披露而不是跳过</h3>
 * AC-25 要求 {@code getEffective} 返回 {@code hasTemplate=false}，即「客户分类无模板 <b>且</b> 默认分类也无模板」。
 * 但实查：全库唯一的 {@code sel_template}（名为「11」）就挂在<b>默认分类</b>上 ⇒
 * 任何客户都会回退命中它。要造出 {@code hasTemplate=false} 只能停用/删除那条真实模板，
 * 那是 {@code testing.md §4.3} 明令禁止的「改模板发布态」全局状态污染。
 * <p>⇒ 本类的做法：<b>不碰那条模板</b>，改为
 * ① 打印该客户的 {@code hasTemplate} 实际值（让审阅者看到覆盖边界）；
 * ② 断言<b>无模板分类的客户提交成功</b>且<b>落库与有模板时逐字段一致</b>（AC-25②③ 的可观测内核）；
 * ③ 门禁本身（前端 footer 只剩「取消」）由 E2E 用 {@code page.route()} 把
 * {@code hasTemplate:false} 注入响应来构造 —— 那是前端门禁的真实输入，且零全局副作用。
 * <p>🚫 <b>不用 {@code Assumptions.assumeTrue} 跳过</b>：静默跳过的用例长得和通过一模一样，
 * 属于本任务点名要防的假绿。
 */
@QuarkusTest
@DisplayName("task-260902 · 选配模板下线（AC-25/26）")
class SelTemplateRetirementAcTest extends SelConfigAcTestBase {

    private static final String EFFECTIVE = "/api/cpq/sel-templates/effective";
    /** fixture基线：全库唯一的存量选配模板（名为「11」，挂默认分类）。AC-26 反向断言的对象。 */
    private static final String LEGACY_TEMPLATE_ID = "68db189f-e8ad-4140-9464-a1fd77d0bb89";

    /**
     * <b>AC-25</b>：「一个客户，其产品分类<b>没有配任何选配模板</b> → 打开『选配添加』，
     * 完整配一个零件并提交」⇒ ②「<b>能完整走完 4 步并提交成功</b>，落库结果与有模板时<b>逐字段一致</b>」；
     * ③「页面不出现『缺少选配模板』之类的空态或阻断提示」。
     */
    @Test
    @DisplayName("AC-25 无模板的客户照样能提交，且落库与有模板时逐字段一致")
    void ac25_submitWorksWithoutSelTemplate() {
        UUID catNoTpl = createCategory("NOTPL");
        UUID catWithTpl = createCategory("WITHTPL");
        createSelTemplate(catWithTpl);                 // 对照组：本任务自建的模板，@AfterEach 精确删除

        Fx noTpl = newFixture("ac25-notpl", catNoTpl);
        Fx withTpl = newFixture("ac25-withtpl", catWithTpl);

        // 覆盖边界如实披露：打印两边的 hasTemplate 实际值
        System.out.println("[AC-25] 无模板分类客户 effective=" + effective(noTpl.customerNo()));
        System.out.println("[AC-25] 有模板分类客户 effective=" + effective(withTpl.customerNo()));

        Map<String, Object> part = newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1));

        Response a = configure(noTpl, submitBody(PREFIX + "N25-1", part));
        assertSubmitOk(a, "AC-25 无模板客户提交");
        // ③ 不得出现「缺少选配模板」这类阻断
        assertFalse(a.asString().contains("选配模板"),
                "AC-25③：响应不得出现任何『选配模板』相关的阻断提示，实际=" + a.asString());

        Response b = configure(withTpl, submitBody(PREFIX + "N25-2", part));
        assertSubmitOk(b, "AC-25 有模板客户提交（对照组）");

        // ② 落库逐字段一致（销售料号与 customer_no 天然不同，其余必须一致）
        String pa = latestLinePartNo(noTpl), pb = latestLinePartNo(withTpl);
        assertEquals(masterFields(pa), masterFields(pb),
                "AC-25②：material_master 的品名/规格/尺寸/总重必须与有模板时逐字段一致");
        assertEquals(bomFields(noTpl, pa), bomFields(withTpl, pb),
                "AC-25②：material_bom_item 的材质/占比/characteristic 必须与有模板时逐字段一致");
        assertEquals(elementGroupCount(noTpl, pa), elementGroupCount(withTpl, pb),
                "AC-25②：element_bom_item 的分组数必须与有模板时一致");
        System.out.println("[AC-25②] 无模板=" + masterFields(pa) + " / " + bomFields(noTpl, pa));
        System.out.println("[AC-25②] 有模板=" + masterFields(pb) + " / " + bomFields(withTpl, pb));
        assertFalse(bomFields(noTpl, pa).isEmpty(),
                "AC-25②：落库结果为空 ⇒ 上面的『一致』是空对空的假绿");
    }

    /**
     * <b>AC-26 的反向断言</b>：「🚫 <b>路由与页面代码保留、不删除</b>（直接访问 URL 仍可打开），
     * 🚫 <b>{@code sel_template} 系列表与数据不删</b> —— 仅停用入口，为将来重新引入留路」。
     *
     * <p>🚨 <b>这是防「实现时顺手清理」的守卫</b>：菜单隐藏是本期要做的，删表删数据不是。
     */
    @Test
    @DisplayName("AC-26(反向) sel_template 表与存量数据必须原样保留")
    void ac26_selTemplateTablesAndDataMustSurvive() {
        assertTrue(tableExists("sel_template"), "AC-26：🚫 sel_template 表不得被删除（本期只停用入口）");
        assertTrue(tableExists("sel_template_item"), "AC-26：🚫 sel_template_item 表不得被删除");
        assertTrue(tableExists("sel_template_item_value"), "AC-26：🚫 sel_template_item_value 表不得被删除");

        long legacy = count("SELECT count(*) FROM sel_template WHERE id='" + LEGACY_TEMPLATE_ID + "'");
        System.out.println("[AC-26] 存量模板「11」还在？ " + (legacy == 1));
        assertEquals(1, legacy,
                "AC-26：存量选配模板（id=" + LEGACY_TEMPLATE_ID + "，名为「11」）不得被删除 —— "
                        + "本期是『停用』不是『清理』，删了等于把历史配置一并丢掉");
        long items = count("SELECT count(*) FROM sel_template_item WHERE template_id='" + LEGACY_TEMPLATE_ID + "'");
        assertEquals(3, items,
                "AC-26：该模板的 3 条 sel_template_item（MATERIAL/ELEMENT/PROCESS）应原样保留，实际 " + items);

        // 端点保留：effective 仍可调用（前端不再据它做门禁，但接口不删）
        Response res = given().queryParam("customerNo", "___T260902_NOT_EXIST___")
                .get(EFFECTIVE).thenReturn();
        System.out.println("[AC-26] effective 端点仍可用：" + res.statusCode());
        assertTrue(res.statusCode() != 404,
                "AC-26：/sel-templates/effective 端点不得被删除（前端仍在读候选值域），实际=" + res.statusCode());
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private String effective(String customerNo) {
        Response res = given().queryParam("customerNo", customerNo).get(EFFECTIVE).thenReturn();
        return res.statusCode() + " " + res.asString();
    }

    private String masterFields(String partNo) {
        List<Object[]> r = rows("SELECT material_name, specification, dimension, unit_weight::text "
                + "FROM material_master WHERE material_no='" + partNo + "'");
        return r.isEmpty() ? "" : java.util.Arrays.toString(r.get(0));
    }

    private String bomFields(Fx fx, String partNo) {
        return rows("SELECT component_no, material_ratio::text, characteristic FROM material_bom_item "
                + "WHERE customer_no='" + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true "
                + "ORDER BY seq_no").stream().map(java.util.Arrays::toString).toList().toString();
    }

    private long elementGroupCount(Fx fx, String partNo) {
        return count("SELECT count(DISTINCT material_part_no) FROM element_bom_item WHERE customer_no='"
                + fx.customerNo() + "' AND material_no='" + partNo + "' AND is_current=true");
    }
}
