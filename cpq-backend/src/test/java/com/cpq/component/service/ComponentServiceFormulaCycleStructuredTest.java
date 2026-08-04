package com.cpq.component.service;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.FormulaCycleException;
import com.cpq.common.exception.GlobalExceptionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803：组件保存路径的公式环 <b>结构化载荷</b> 端到端验证（不打 DB，纯单元）。
 *
 * <p>覆盖 {@code 需求文档.md} AC-10 / AC-11 / AC-15 / AC-16 与 {@code test.md} T-16 / T-18。
 * 驱动路径与 {@link ComponentServiceConditionalValidationTest} 一致 —— 直接
 * {@code new ComponentService()} 调用 package-private {@code validateFormulas}
 * （该方法不触碰任何 CDI 注入字段，纯逻辑，无需 {@code @QuarkusTest}）。
 *
 * <p>AC-11 的「响应体全文」检查经由<b>真实</b> {@link GlobalExceptionMapper#handleBusinessException}
 * 产出 {@link Response}，再用与生产环境相同的 Jackson {@link ObjectMapper} 序列化，
 * 而非重新拼一份自造 JSON —— 这样才是验证「线上真的不会吐 UUID」，不是验证我自己写的字符串。
 */
class ComponentServiceFormulaCycleStructuredTest {

    private final ComponentService svc = new ComponentService();
    private final GlobalExceptionMapper mapper = new GlobalExceptionMapper();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_LIKE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-");

    private static Map<String, Object> formulaField(String name, String formulaName) {
        return Map.of("name", name, "field_type", "FORMULA", "formula_name", formulaName);
    }

    private static Map<String, Object> condField(String name, Object condFormula) {
        return Map.of("name", name, "field_type", "FORMULA", "conditional_formula", condFormula);
    }

    private static Map<String, Object> fieldTok(String v) {
        return Map.of("type", "field", "value", v);
    }

    private static Map<String, Object> formula(String name, List<Map<String, Object>> expr) {
        return Map.of("name", name, "expression", expr);
    }

    /** 序列化 GlobalExceptionMapper 真实响应体，供 AC-11 类断言复用。 */
    private String renderResponseBody(FormulaCycleException fce) throws Exception {
        Response resp = mapper.handleBusinessException(fce);
        assertEquals(400, resp.getStatus(), "循环引用应映射为 400");
        Object entity = resp.getEntity();
        assertInstanceOf(ApiResponse.class, entity);
        return JSON.writeValueAsString(entity);
    }

    // ──────────────────────────────────────────────────────────────
    // AC-10 / AC-11：字段级环的结构化载荷
    // ──────────────────────────────────────────────────────────────

    /**
     * AC-10：A/B 互相引用 → FormulaCycleException，scope=FIELD，nodes/edges 结构齐全。
     *
     * <p><b>实测发现的缺陷（2026-08-04，测试工程师）</b>：{@code FormulaCalculator
     * .describeFormulaCyclesStructured} 直接复用 {@code cyclePathIn} 返回的 {@code path}
     * 构建 {@code nodes}/{@code edges}——但 {@code cyclePathIn}（经 {@code dfsBackTo}）
     * 为<b>文本渲染</b> {@code renderCycle} 设计，返回的 path 末尾<b>已带一个重复的闭合节点</b>
     * （2 节点环 A⇄B 的 path 实际是 {@code [B, A, B]}，长度 3 非 2）。结构化构建器又对这个
     * <b>已经闭合</b>的 path 做了一次 {@code (i+1)%path.size()} 取模闭合，双重闭合导致：
     * <ul>
     *   <li>{@code nodes} 首尾重复（实测 {@code [B, A, B]}），违反 api.md §1「首尾不重复」契约
     *       与 {@code FormulaCycleException.Cycle} 自身 javadoc；</li>
     *   <li>{@code edges} 多出一条虚假自环边（实测第 3 条 {@code B→B, viaDesc="公式"}，
     *       未命中任何真实 DepEdge，纯粹是取模算出来的伪造闭合边）。</li>
     * </ul>
     * 下面断言按<b>契约应有形态</b>（2 节点 2 边、首尾不重复）编写——当前会失败，是已知缺陷、
     * 非误报；详见测试报告。TAB 作用域（{@code TemplateService.buildTabCycles} 走
     * {@code CrossTabComponentOrder.findCyclePath}，其 {@code dfsCycle} 不重复闭合节点）
     * <b>没有</b>此问题，见 {@code TemplateCrossTabCycleStructuredTest}。
     */
    @Test void ac10_mutualFieldCycle_producesStructuredFieldScopeCycle() throws Exception {
        var fields = List.of(formulaField("A", "A公式"), formulaField("B", "B公式"));
        var formulas = List.of(
            formula("A公式", List.of(fieldTok("B"))),
            formula("B公式", List.of(fieldTok("A"))));

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));

        List<FormulaCycleException.Cycle> cycles = ex.getCycles();
        assertEquals(1, cycles.size(), "应恰好 1 个环: " + cycles);
        FormulaCycleException.Cycle cyc = cycles.get(0);
        assertEquals(FormulaCycleException.SCOPE_FIELD, cyc.scope());
        assertEquals("物料", cyc.componentName());

        var nodeNames = cyc.nodes().stream().map(FormulaCycleException.Node::fieldName).toList();
        assertTrue(nodeNames.containsAll(List.of("A", "B")), "nodes 应含 A、B: " + nodeNames);
        for (FormulaCycleException.Node n : cyc.nodes()) {
            assertEquals("物料", n.componentName());
            assertNotNull(n.formulaName(), "每个节点应带所属公式名: " + n);
        }

        // ↓↓↓ 契约断言：首尾不重复 + 边数=节点数（当前实测失败，见方法 javadoc 缺陷说明）
        assertEquals(2, cyc.nodes().size(),
            "nodes 首尾不应重复（api.md §1 契约）；实测常常是 [B,A,B] 3 项，见方法 javadoc");
        assertEquals(2, cyc.edges().size(), "2 节点环应恰好 2 条边（含 1 条闭合边），不应多出伪造自环边");
        for (FormulaCycleException.Edge e : cyc.edges()) {
            assertNotNull(e.viaFormulaName(), "每条边应带 viaFormulaName: " + e);
            assertNotEquals(e.from(), e.to(), "不应出现自环边（伪造闭合的症状）: " + e);
        }
        var edgeFroms = cyc.edges().stream().map(FormulaCycleException.Edge::from).toList();
        assertTrue(edgeFroms.containsAll(List.of("A", "B")));
    }

    /** AC-11：GlobalExceptionMapper 真实响应体全文，UUID 形态正则命中数必须为 0。 */
    @Test void ac11_responseBody_containsNoUuid() throws Exception {
        var fields = List.of(formulaField("原材料成本", "f原材料成本"), formulaField("来料加工费", "f来料加工费"));
        var formulas = List.of(
            formula("f原材料成本", List.of(fieldTok("来料加工费"))),
            formula("f来料加工费", List.of(fieldTok("原材料成本"))));

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));

        String body = renderResponseBody(ex);
        assertTrue(body.contains("\"errorType\":\"FORMULA_CYCLE\""), "缺 errorType: " + body);
        assertTrue(body.contains("原材料成本") && body.contains("来料加工费"), body);
        assertFalse(UUID_LIKE.matcher(body).find(),
            "响应体全文不得出现 UUID 形态: " + body);
    }

    // ──────────────────────────────────────────────────────────────
    // AC-15：一份配置同时存在 2 个互不相交的环
    // ──────────────────────────────────────────────────────────────

    @Test void ac15_twoDisjointCycles_reportedAsTwoSeparateCyclesWithDisjointNodes() {
        var fields = List.of(
            formulaField("甲", "f甲"), formulaField("乙", "f乙"),
            formulaField("丙", "f丙"), formulaField("丁", "f丁"));
        var formulas = List.of(
            formula("f甲", List.of(fieldTok("乙"))), formula("f乙", List.of(fieldTok("甲"))),
            formula("f丙", List.of(fieldTok("丁"))), formula("f丁", List.of(fieldTok("丙"))));

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));

        List<FormulaCycleException.Cycle> cycles = ex.getCycles();
        assertEquals(2, cycles.size(), "两个独立环应各报一条: " + cycles);

        var names0 = cycles.get(0).nodes().stream().map(FormulaCycleException.Node::fieldName)
            .collect(java.util.stream.Collectors.toSet());
        var names1 = cycles.get(1).nodes().stream().map(FormulaCycleException.Node::fieldName)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(java.util.Collections.disjoint(names0, names1),
            "两个环的节点集合不应有交集: " + names0 + " vs " + names1);

        var allNames = new java.util.HashSet<String>();
        allNames.addAll(names0);
        allNames.addAll(names1);
        assertEquals(java.util.Set.of("甲", "乙", "丙", "丁"), allNames,
            "两个环合并应恰好覆盖四个字段，互不重叠也不遗漏: " + allNames);
    }

    // ──────────────────────────────────────────────────────────────
    // AC-16（D-9 回归）：原先仅由已删除的 dfsCycleDetect 拦截的形态，仍须被拦截且带定位信息
    // ──────────────────────────────────────────────────────────────

    /**
     * T-16：自引用（字段的公式引用自己）—— 旧 {@code dfsCycleDetect} 对自引用同样报错但零定位；
     * 新路径必须仍然拦截，且抛的是携带结构化链路的 {@link FormulaCycleException}（而非退化的
     * 通用 {@code BusinessException}），证明「删除 dfsCycleDetect 后能力未降级，反而更强」。
     */
    @Test void ac16_selfReference_stillCaught_withStructuredLocation() {
        var fields = List.of(formulaField("甲", "f甲"));
        var formulas = List.of(formula("f甲", List.of(fieldTok("甲"))));

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));
        assertTrue(ex.getMessage().contains("循环引用"), ex.getMessage());
        assertEquals(1, ex.getCycles().size());
        var cyc = ex.getCycles().get(0);
        assertEquals(FormulaCycleException.SCOPE_FIELD, cyc.scope());
        assertTrue(cyc.nodes().stream().anyMatch(n -> "甲".equals(n.fieldName())),
            "自引用环应点名字段「甲」: " + cyc.nodes());
    }

    /**
     * AC-16：简单两字段互相引用（dfsCycleDetect 覆盖的最基本形态）——新路径同样必须拦截，
     * 且是携带结构化 cycles 的 {@link FormulaCycleException}，不是零信息的裸 {@code BusinessException}。
     * 与 {@link ComponentServiceConditionalValidationTest#formulaCycle_throws} 互补：那条只验
     * message 含"循环引用"，本条额外验证异常<b>类型</b>与结构化载荷完整性（AC-16 的核心诉求）。
     */
    @Test void ac16_simpleMutualReference_stillCaught_asFormulaCycleException_notGenericBusinessException() {
        var fields = List.of(
            Map.<String, Object>of("name", "A", "field_type", "FORMULA"),
            Map.<String, Object>of("name", "B", "field_type", "FORMULA"));
        var formulas = List.of(
            formula("A", List.of(fieldTok("B"))),
            formula("B", List.of(fieldTok("A"))));

        var ex = assertThrows(com.cpq.common.exception.BusinessException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));
        assertInstanceOf(FormulaCycleException.class, ex,
            "删除 dfsCycleDetect 后，同样的环必须走结构化 FormulaCycleException 路径，而非退化成裸 BusinessException");
    }

    // ──────────────────────────────────────────────────────────────
    // T-18：条件公式成环的 viaDesc 定位（规则分支 / 判断条件 / 默认分支）
    // ──────────────────────────────────────────────────────────────

    private static Map<String, Object> emptyWhen() {
        return Map.of("kind", "group", "logic", "and", "children", List.of());
    }

    private static Map<String, Object> whenOnColumn(String col) {
        return Map.of("kind", "leaf", "left", col, "op", "gt",
            "rhs", Map.of("type", "literal", "value", "1"));
    }

    /** 条件规则命中的公式引用对侧字段成环 → 边的 viaDesc 须点明「条件规则N命中的公式「X」」。 */
    @Test void t18_conditionalRuleCycle_edgeViaDescLocatesRuleBranch() {
        var fields = List.of(
            formulaField("乙", "f乙"),
            condField("甲", Map.of(
                "rules", List.of(Map.of("when", emptyWhen(), "formula", "f甲规则")),
                "default", "f甲默认")));
        var formulas = List.of(
            formula("f甲默认", List.of()),
            formula("f甲规则", List.of(fieldTok("乙"))),
            formula("f乙", List.of(fieldTok("甲"))));

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));
        var cyc = ex.getCycles().get(0);
        var edgeFromJia = cyc.edges().stream().filter(e -> "甲".equals(e.from())).findFirst().orElseThrow();
        assertEquals("f甲规则", edgeFromJia.viaFormulaName(), edgeFromJia.toString());
        assertTrue(edgeFromJia.viaDesc() != null && edgeFromJia.viaDesc().contains("条件规则1"),
            "边的 viaDesc 应定位到条件规则序号: " + edgeFromJia);
    }

    /** 条件公式的 when 判断条件引用列成环 → viaDesc 须区分「判断条件」而非误标成公式表达式。 */
    @Test void t18_conditionalWhenCycle_edgeViaDescLocatesWhenCondition() {
        var fields = List.of(
            formulaField("乙", "f乙"),
            condField("甲", Map.of(
                "rules", List.of(Map.of("when", whenOnColumn("乙"), "formula", "f甲规则")),
                "default", "f甲规则")));
        var formulas = List.of(formula("f甲规则", List.of()), formula("f乙", List.of(fieldTok("甲"))));

        FormulaCycleException ex = assertThrows(FormulaCycleException.class,
            () -> svc.validateFormulas(fields, formulas, "物料"));
        var cyc = ex.getCycles().get(0);
        var edgeFromJia = cyc.edges().stream().filter(e -> "甲".equals(e.from())).findFirst().orElseThrow();
        assertTrue(edgeFromJia.viaDesc() != null && edgeFromJia.viaDesc().contains("判断条件"),
            "when 判断条件引用应标注「判断条件」而非误标成公式: " + edgeFromJia);
    }
}
