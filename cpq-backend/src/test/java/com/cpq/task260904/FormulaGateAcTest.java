package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-18 —— <b>AC-18（反向 · 公式 token 闸门不得失效）</b>。
 *
 * <p>AC 原文（{@code 需求文档.md §3.3}）：
 * 「两个组件，A 绑定数据源「物料BOM」（{@code semantic='TREE'}），B 绑定「自制加工费」（{@code semantic=null}）。
 *  ① 在 B 的公式里使用 {@code tree_ref} 并保存 ⇒ <b>400</b> 且文案点名是哪条公式；
 *  ② 在 A 的公式里使用 {@code tree_ref} 并保存 ⇒ <b>200</b>；
 *  ③ 在 A 的公式里使用「上一行」类 token 并保存 ⇒ <b>400</b>（树页签禁「上一行」）」。
 *
 * <h3>🚨 三个断言缺一不可</h3>
 * test.md §3 点名：只验第一条的话，<b>把闸门改成「全部拒绝」也能过</b>。
 * ② 是正向对照（证明闸门不是无脑拒），③ 是反向对照（证明树页签的另一半规则还在）。
 *
 * <h3>本 AC 与既有 {@code ComponentServiceTreeTokenGateTest} 的区别</h3>
 * 那份单测直接给方法传 {@code tabType} 字符串。本次改造后判据换成
 * 「该组件绑定的数据源 {@code semantic}」，{@code tab_type} 对新组件恒为空 ——
 * 传字符串的单测<b>验不到这条链路</b>。所以本用例必须走
 * 「配置器真实保存 → 组件保存」的完整 HTTP 路径。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-18 —— 公式 token 闸门按数据源 semantic 判，三态缺一不可")
class FormulaGateAcTest extends Task260904Base {

    private static String treeRefFormula(String formulaName, String targetField) {
        return "{\"formulas\":[{\"name\":\"" + formulaName + "\",\"expression\":["
                + "{\"type\":\"tree_ref\",\"dir\":\"PARENT\",\"agg\":\"NONE\",\"targetExpr\":["
                + "{\"type\":\"field\",\"value\":\"" + targetField + "\"}]}]}]}";
    }

    private static String previousRowFormula(String formulaName) {
        return "{\"formulas\":[{\"name\":\"" + formulaName + "\",\"expression\":["
                + "{\"type\":\"previous_row_subtotal\"}]}]}";
    }

    private Response saveComponent(UUID id, String body) {
        Response r = given().contentType(ContentType.JSON).body(body)
                .put("/api/cpq/components/" + id).thenReturn();
        System.out.println("[task260904·component-save] " + id + " → " + r.statusCode() + " " + r.asString());
        return r;
    }

    @Test
    @DisplayName("AC-18：B(自制加工费)+tree_ref → 400；A(物料BOM)+tree_ref → 200；A+上一行 → 400")
    void ac18_formulaTokenGateFollowsDataSourceSemantic() {
        // ── 组件 A：数据源「物料BOM」⇒ semantic = TREE ──
        UUID a = createBlankComponent("A18-A");
        saveBuilderOk(a, CFG_MATERIAL_BOM, "AC-18 前置A");
        assertBuilderVersionPresent(a, "AC-18 前置A⓪");
        assertEquals(null, scalar("SELECT tab_type FROM component WHERE id = '" + a + "'"),
                "AC-18 前置：A 的 component.tab_type 应为空 —— 正是这一点让「传 tabType 字符串」的老单测验不到本条链路");

        // ── 组件 B：数据源「自制加工费」⇒ semantic = null ──
        UUID b = createBlankComponent("A18-B");
        saveBuilderOk(b, CFG_SELF_PROCESS_FEE, "AC-18 前置B");
        assertBuilderVersionPresent(b, "AC-18 前置B⓪");

        // ① B + tree_ref → 400，且文案点名是哪条公式
        String formulaNameB = PREFIX + "父取值公式B";
        Response r1 = saveComponent(b, treeRefFormula(formulaNameB, "自制加工费"));
        assertReachedBusinessLayer(r1, "AC-18①");
        assertEquals(400, r1.statusCode(),
                "AC-18①：非树数据源的组件用 tree_ref 应被拒 400，实际=" + r1.statusCode() + " body=" + r1.asString());
        assertTrue(r1.asString().contains(formulaNameB),
                "AC-18①：文案必须点名是哪条公式（AC 原文要求），实际 body=" + r1.asString());

        // ② A + tree_ref → 200（正向对照：证明闸门不是「全部拒绝」）
        Response r2 = saveComponent(a, treeRefFormula(PREFIX + "父取值公式A", "组成数量"));
        assertReachedBusinessLayer(r2, "AC-18②");
        assertEquals(200, r2.statusCode(),
                "AC-18②：数据源为「物料BOM」(semantic=TREE) 的组件应放行 tree_ref，实际=" + r2.statusCode()
                        + " body=" + r2.asString()
                        + " ⇒ 后端仍在看 component.tab_type（新组件为空）而不是数据源 semantic。");

        // ③ A + 「上一行」类 token → 400（树页签禁「上一行」）
        String formulaNamePrev = PREFIX + "上一行小计公式";
        Response r3 = saveComponent(a, previousRowFormula(formulaNamePrev));
        assertReachedBusinessLayer(r3, "AC-18③");
        assertEquals(400, r3.statusCode(),
                "AC-18③：树页签应禁用「上一行」类 token，实际=" + r3.statusCode() + " body=" + r3.asString());
        assertTrue(r3.asString().contains(formulaNamePrev),
                "AC-18③：文案应点名是哪条公式，实际 body=" + r3.asString());
    }
}
