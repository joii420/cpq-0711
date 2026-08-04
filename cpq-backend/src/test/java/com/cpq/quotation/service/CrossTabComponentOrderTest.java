package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CrossTabComponentOrderTest {

    @Test void noDeps_keepsInputOrder() {
        var order = CrossTabComponentOrder.topoOrder(List.of("A", "B"), Map.of());
        assertEquals(List.of("A", "B"), order);
    }

    @Test void bDependsOnA_aFirst() {
        var deps = Map.of("B", Set.of("A"));
        var order = CrossTabComponentOrder.topoOrder(List.of("B", "A"), deps);
        assertTrue(order.indexOf("A") < order.indexOf("B"));
    }

    @Test void cycle_throws() {
        var deps = Map.of("A", Set.of("B"), "B", Set.of("A"));
        assertThrows(BusinessException.class,
            () -> CrossTabComponentOrder.topoOrder(List.of("A", "B"), deps));
    }

    @Test void chain_aThenBThenC() {
        var deps = Map.of("C", Set.of("B"), "B", Set.of("A"));
        var order = CrossTabComponentOrder.topoOrder(List.of("C", "B", "A"), deps);
        assertEquals(List.of("A", "B", "C"), order);
    }

    @Test void extractDeps_fromFormulaTokens() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var bFormulas = om.readTree(
            "[{\"expression\":[{\"type\":\"cross_tab_ref\",\"source\":\"A\"}]}]");
        Set<String> deps = CrossTabComponentOrder.extractSourceRefs(bFormulas);
        assertEquals(Set.of("A"), deps);
    }

    /** QT-1743: component_subtotal 跨组件引用必须被提取为依赖（component_code 优先，否则 tab_name）。 */
    @Test void extractSubtotalRefs_componentCodePreferred() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var formulas = om.readTree(
            "[{\"expression\":[" +
            "{\"type\":\"component_subtotal\",\"value\":\"材料成本\",\"tab_name\":\"材料成本\",\"component_code\":\"COMP-0028\"}," +
            "{\"type\":\"operator\",\"value\":\"+\"}," +
            "{\"type\":\"component_subtotal\",\"value\":\"费用\",\"tab_name\":\"电镀费用\",\"component_code\":\"COMP-0033\"}" +
            "]}]");
        Set<String> refs = CrossTabComponentOrder.extractSubtotalRefs(formulas);
        assertEquals(Set.of("COMP-0028", "COMP-0033"), refs);
    }

    @Test void extractSubtotalRefs_fallbackTabName_whenNoCode() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var formulas = om.readTree(
            "[{\"expression\":[{\"type\":\"component_subtotal\",\"value\":\"x\",\"tab_name\":\"来料\"}]}]");
        Set<String> refs = CrossTabComponentOrder.extractSubtotalRefs(formulas);
        assertEquals(Set.of("来料"), refs);
    }

    // ────────────────────────────────────────────────────────────────────
    // repair-0803: component_subtotal 依赖边的「列粒度」精化
    //
    // 背景（QT-20260803-0052 假环）：component_subtotal 依赖此前按<b>页签</b>粒度建边——
    // 只要 A 的公式引用了 B 的任意一列小计，就认为「B 必须先算」。但被引用列若是
    // INPUT_NUMBER 这类零依赖列，其值在 PASS1 前就已确定，与页签计算顺序无关；
    // 凭空多出的反向边会把「产品.税率 → 物料成本 → 产品.管理费」这条直线依赖链
    // 折成 产品⇄物料 闭环 → topoOrder 误抛「页签公式存在循环引用」→ 整卡渲染失败。
    //
    // 判据依据 FormulaCalculator.collectFormulaFields:1393 —— 只有 field_type=="FORMULA"
    // 的列才由公式求值（formula_assignments 亦只在 FORMULA 列内部生效），故只有引用
    // 公式列/整页签合计时顺序才有意义。
    // ────────────────────────────────────────────────────────────────────

    private static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
    }

    /** 产品页签：管理费(FORMULA, 引用物料) + 税率(INPUT_NUMBER, 零依赖)。 */
    private static CrossTabComponentOrder.TabDep productTab() throws Exception {
        return new CrossTabComponentOrder.TabDep("CID-PRD", "COMP-0160", "产品",
            json("[{\"name\":\"管理费\",\"expression\":["
                + "{\"type\":\"cross_tab_ref\",\"source\":\"CID-MAT\"},"
                + "{\"type\":\"operator\",\"value\":\"-\"},"
                + "{\"type\":\"component_subtotal\",\"value\":\"回收成本\",\"component_code\":\"COMP-0157\"}"
                + "]}]"),
            json("[{\"name\":\"管理费\",\"field_type\":\"FORMULA\"},"
                + "{\"name\":\"税率\",\"field_type\":\"INPUT_NUMBER\"}]"));
    }

    /** 物料页签：材料成本/回收成本(FORMULA)，公式除以「产品·税率」。 */
    private static CrossTabComponentOrder.TabDep materialTab(String refColumn) throws Exception {
        return new CrossTabComponentOrder.TabDep("CID-MAT", "COMP-0157", "物料",
            json("[{\"name\":\"v2-原材料成本\",\"expression\":["
                + "{\"type\":\"component_subtotal\",\"value\":\"" + refColumn + "\","
                + "\"tab_name\":\"COMP-0160\",\"component_code\":\"COMP-0160\"}"
                + "]}]"),
            json("[{\"name\":\"材料成本\",\"field_type\":\"FORMULA\"},"
                + "{\"name\":\"回收成本\",\"field_type\":\"FORMULA\"}]"));
    }

    /** 引用 INPUT_NUMBER 列（产品·税率）→ 不建边 → 无假环，且物料先算。 */
    @Test void subtotalRefToInputColumn_createsNoEdge() throws Exception {
        var deps = CrossTabComponentOrder.buildComponentDeps(List.of(productTab(), materialTab("税率")));

        assertEquals(Set.of(), deps.get("CID-MAT"), "引用零依赖 INPUT 列不应建页签依赖边");
        assertEquals(Set.of("CID-MAT"), deps.get("CID-PRD"), "产品对物料的依赖必须保留");

        var order = CrossTabComponentOrder.topoOrder(List.of("CID-PRD", "CID-MAT"), deps);
        assertEquals(List.of("CID-MAT", "CID-PRD"), order, "物料应先于产品计算");
    }

    /** QT-1743 不回归：引用的是 FORMULA 列（产品·管理费）→ 仍建边（此时才是真环，应照报）。 */
    @Test void subtotalRefToFormulaColumn_keepsEdge() throws Exception {
        var deps = CrossTabComponentOrder.buildComponentDeps(List.of(productTab(), materialTab("管理费")));

        assertEquals(Set.of("CID-PRD"), deps.get("CID-MAT"), "引用公式列必须建边（顺序敏感）");
        assertThrows(BusinessException.class,
            () -> CrossTabComponentOrder.topoOrder(List.of("CID-PRD", "CID-MAT"), deps),
            "公式列互相引用是真环，仍应报错");
    }

    /** 引用整页签合计 __amount_total__ → 合计含公式列 → 必建边。 */
    @Test void subtotalRefToTabTotal_keepsEdge() throws Exception {
        var mat = new CrossTabComponentOrder.TabDep("CID-MAT", "COMP-0157", "物料",
            json("[{\"name\":\"x\",\"expression\":[{\"type\":\"component_subtotal\","
                + "\"value\":\"__amount_total__\",\"tab_name\":\"__amount_total__\","
                + "\"is_tab_total\":true,\"component_code\":\"COMP-0160\"}]}]"),
            json("[]"));
        var deps = CrossTabComponentOrder.buildComponentDeps(List.of(productTab(), mat));
        assertEquals(Set.of("CID-PRD"), deps.get("CID-MAT"), "整页签合计依赖其全部公式列，必须建边");
    }

    /** 被引用列在目标页签 fields 里查无此列 → 保守建边（宁可多排序，不可算错）。 */
    @Test void subtotalRefToUnknownColumn_keepsEdgeConservatively() throws Exception {
        var deps = CrossTabComponentOrder.buildComponentDeps(
            List.of(productTab(), materialTab("查无此列")));
        assertEquals(Set.of("CID-PRD"), deps.get("CID-MAT"), "列不可解析时应保守建边");
    }

    /** 自引用不建边（本组件二阶列由引擎内两阶段处理），且 cross_tab_ref 边不受列粒度影响。 */
    @Test void selfRef_excluded_andCrossTabRefAlwaysKept() throws Exception {
        var self = new CrossTabComponentOrder.TabDep("CID-PRD", "COMP-0160", "产品",
            json("[{\"name\":\"二阶列\",\"expression\":[{\"type\":\"component_subtotal\","
                + "\"value\":\"管理费\",\"component_code\":\"COMP-0160\"}]}]"),
            json("[{\"name\":\"管理费\",\"field_type\":\"FORMULA\"}]"));
        var deps = CrossTabComponentOrder.buildComponentDeps(List.of(self));
        assertEquals(Set.of(), deps.get("CID-PRD"), "自引用不得建边");
    }

    /** repair-0803 FR-12/AC-14：成环文案用页签名称渲染链路，且不得出现 componentId。 */
    @Test void cycleMessage_rendersComponentNames_withoutIds() {
        String prd = "56c8a517-e770-4429-82c7-72f216daab45";
        String mat = "74c0cede-094e-478c-a8fe-8f0028d538cd";
        var deps = Map.of(prd, Set.of(mat), mat, Set.of(prd));
        var names = Map.of(prd, "产品", mat, "物料");

        var ex = assertThrows(BusinessException.class,
            () -> CrossTabComponentOrder.topoOrder(List.of(prd, mat), deps, names));

        assertTrue(ex.getMessage().contains("产品") && ex.getMessage().contains("物料"), ex.getMessage());
        assertTrue(ex.getMessage().contains("→"), "应渲染成链路形态：" + ex.getMessage());
        assertFalse(ex.getMessage().matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-.*"),
            "文案不得残留 UUID：" + ex.getMessage());
    }

    /** 名称缺失时回落 id（零破坏：两参旧签名传空映射，行为与改前一致）。 */
    @Test void cycleMessage_fallsBackToIdWhenNameMissing() {
        var deps = Map.of("A", Set.of("B"), "B", Set.of("A"));
        var ex = assertThrows(BusinessException.class,
            () -> CrossTabComponentOrder.topoOrder(List.of("A", "B"), deps));
        assertTrue(ex.getMessage().contains("A") && ex.getMessage().contains("B"), ex.getMessage());
    }

    /** 卡片外的引用（refToCid 解析不到）不入图，不影响入度。 */
    @Test void refOutsideCard_ignored() throws Exception {
        var mat = new CrossTabComponentOrder.TabDep("CID-MAT", "COMP-0157", "物料",
            json("[{\"name\":\"x\",\"expression\":[{\"type\":\"component_subtotal\","
                + "\"value\":\"某列\",\"component_code\":\"COMP-9999\"}]}]"),
            json("[]"));
        var deps = CrossTabComponentOrder.buildComponentDeps(List.of(mat));
        assertEquals(Set.of(), deps.get("CID-MAT"));
    }
}
