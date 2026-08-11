package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * task-0729 B9 · 双端公式一致性 L1：仓库根 {@code formula-golden/*.json} 驱动的黄金用例测试。
 *
 * <p>🔒 <b>与前端 vitest 读同一份文件</b>（不是各自维护副本，见 {@code formula-golden/README.md}
 * 契约第 1 条）——本类不内嵌任何用例数据，全部从 JSON 动态加载。
 *
 * <p>🔒 <b>expectedSource 语义</b>：{@code manual-computed}/{@code frontend-engine} 才真正断言；
 * {@code pending} 显式 SKIP（{@code assumeTrue(false)}），不静默通过也不误报失败——JUnit 会把它
 * 标为绿色但可在报告里看到跳过原因，区别于"真的验证过"。
 *
 * <p><b>本轮交付边界</b>（如实说明）：JSON 里当前全部是 {@code manual-computed}（后端工程师按
 * {@link FormulaCalculator} 现有文档化行为独立推导，未对照前端引擎实际产出）。按 README 契约第 2
 * 条，权威来源仍是前端 {@code formulaEngine.ts}——manual-computed 只是"后端自洽性"的第一道门槛，
 * 不等价于"两端一致性"已验证。frontend-engine 值需前端后续补齐（README 已写清楚交接说明）。
 */
class FormulaGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    @TestFactory
    Stream<DynamicTest> goldenCases() throws Exception {
        File dir = resolveGoldenDir();
        List<DynamicTest> tests = new ArrayList<>();
        File[] files = dir.listFiles((FilenameFilter) (d, name) -> name.endsWith(".json"));
        if (files == null) files = new File[0];
        java.util.Arrays.sort(files);

        for (File f : files) {
            JsonNode arr = MAPPER.readTree(f);
            if (!arr.isArray()) continue;
            for (JsonNode caseNode : arr) {
                String id = caseNode.path("id").asText("(no-id)");
                String description = caseNode.path("description").asText("");
                tests.add(DynamicTest.dynamicTest(
                    f.getName() + " :: " + id + " — " + description,
                    () -> runCase(caseNode)));
            }
        }
        return tests.stream();
    }

    @org.junit.jupiter.api.Test
    void componentSubtotalUsesDocumentedPriorityWhenAllKeysCoexist() throws Exception {
        JsonNode tokens = MAPPER.readTree("""
            [{"type":"component_subtotal","component_code":"COMP-A",
              "tab_name":"Input","value":"__amount_total__"}]
            """);
        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        ctx.componentSubtotals = new LinkedHashMap<>();
        ctx.componentSubtotals.put("COMP-A#__amount_total__", new BigDecimal("11.000000000001"));
        ctx.componentSubtotals.put("Input#__amount_total__", new BigDecimal("22.000000000002"));
        ctx.componentSubtotals.put("COMP-A", new BigDecimal("33.000000000003"));
        ctx.componentSubtotals.put("Input", new BigDecimal("44.000000000004"));
        ctx.componentSubtotals.put("__amount_total__", new BigDecimal("55.000000000005"));

        BigDecimal actual = calc.evaluateExpression(tokens, ctx);

        assertEquals(0, new BigDecimal("11.000000000001").compareTo(actual),
            "component_code#value must win over tab_name#value and all fallback keys");
    }

    private void runCase(JsonNode caseNode) {
        String expectedSource = caseNode.path("expectedSource").asText("pending");
        assumeTrue(!"pending".equals(expectedSource),
            "expectedSource=pending，等待权威来源（前端引擎）产出 expected，暂不断言");

        JsonNode tokens = caseNode.path("tokens");
        FormulaCalculator.RowContext ctx = buildContext(caseNode.path("context"));
        BigDecimal actual = calc.evaluateExpression(tokens, ctx);

        String expectedStr = caseNode.path("expected").asText(null);
        assertEquals(0, new BigDecimal(expectedStr).compareTo(actual),
            "case=" + caseNode.path("id").asText() + " expected=" + expectedStr + " actual=" + actual);
    }

    private FormulaCalculator.RowContext buildContext(JsonNode ctxNode) {
        FormulaCalculator.RowContext ctx = new FormulaCalculator.RowContext();
        if (ctxNode == null || ctxNode.isMissingNode()) return ctx;

        ctx.componentSubtotals = readDoubleMap(ctxNode.path("componentSubtotals"));
        ctx.productAttributes = readDoubleMap(ctxNode.path("productAttributes"));
        ctx.quotationFields = readDoubleMap(ctxNode.path("quotationFields"));
        ctx.fieldValues = readDoubleMap(ctxNode.path("fieldValues"));

        Map<String, Object> basicData = new HashMap<>();
        ctxNode.path("basicDataValues").fields().forEachRemaining(e -> basicData.put(e.getKey(), rawValue(e.getValue())));
        ctx.basicDataValues = basicData;

        Map<String, Object> currentRowRaw = new HashMap<>();
        ctxNode.path("currentRowRaw").fields().forEachRemaining(e -> currentRowRaw.put(e.getKey(), rawValue(e.getValue())));
        ctx.currentRowRaw = currentRowRaw;

        JsonNode prs = ctxNode.path("previousRowSubtotal");
        ctx.previousRowSubtotal = (prs != null && prs.isNumber()) ? prs.decimalValue() : null;

        Map<String, List<Map<String, Object>>> crossTabRows = new LinkedHashMap<>();
        ctxNode.path("crossTabRows").fields().forEachRemaining(e -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode rowNode : e.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                rowNode.fields().forEachRemaining(f -> row.put(f.getKey(), rawValue(f.getValue())));
                rows.add(row);
            }
            crossTabRows.put(e.getKey(), rows);
        });
        ctx.crossTabRows = crossTabRows;

        return ctx;
    }

    private Map<String, java.math.BigDecimal> readDoubleMap(JsonNode node) {
        Map<String, java.math.BigDecimal> out = new HashMap<>();
        if (node == null || node.isMissingNode()) return out;
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isNumber()) out.put(e.getKey(), e.getValue().decimalValue());
        });
        return out;
    }

    private Object rawValue(JsonNode v) {
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        return null;
    }

    /** 兼容从 cpq-backend/ 或仓库根两种工作目录跑测试（Maven 默认 cpq-backend/，IDE 可能不同）。 */
    private File resolveGoldenDir() {
        File candidate1 = new File("formula-golden");
        if (candidate1.isDirectory()) return candidate1;
        File candidate2 = new File("../formula-golden");
        if (candidate2.isDirectory()) return candidate2;
        throw new IllegalStateException(
            "找不到 formula-golden/ 目录（尝试了 " + candidate1.getAbsolutePath() +
            " 和 " + candidate2.getAbsolutePath() + "）——本测试要求仓库根存在该目录");
    }
}
