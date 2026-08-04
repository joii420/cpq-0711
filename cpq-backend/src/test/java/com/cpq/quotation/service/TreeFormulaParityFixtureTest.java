package com.cpq.quotation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * task-0803 Task 9-A — 前后端求值引擎（BOM 父子取值 tree_ref / tree_attr）逐位比对。
 *
 * <p><b>为什么需要这份测试</b>：{@code TreeFormulaEvalTest} 只单测了 {@link FormulaCalculator#evaluateExpression}
 * 这一层（人工搭 {@code TreeEvalContext}，绕开了 {@link FormulaCalculator#calculate} 的整页签单元格拓扑排序 +
 * 环检测 + effKey 产出流程）。前端 {@code treeFormula.test.ts} 同理只单测了
 * {@code computeTabFormulasTree} 本身，两边各自独立断言各自的期望值，从未真正对比过"同一份输入，
 * 两套引擎算出的数是否逐位相同"。
 *
 * <p><b>本测试改走两端真正的整页签入口</b>：后端 {@link FormulaCalculator#calculate}（本类），
 * 前端 {@code computeTabFormulasTree}（{@code treeFormulaParityFixture.test.ts}），共享同一份
 * JSON 夹具 {@code dev-docs/task-0803-BOM页签增加父子取值公式/fixtures/tree-formula-parity-cases.json}。
 *
 * <p><b>夹具是唯一真相</b>：本文件与前端测试都从磁盘读取<b>同一个物理文件</b>（不复制），
 * 修改用例只需改一处，两端测试自动同步。找不到该文件视为测试基础设施错误（fail-fast，非静默跳过）。
 *
 * <p>纯 JUnit（不用 {@code @QuarkusTest}）—— BL-0095。
 */
class TreeFormulaParityFixtureTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    /**
     * 从 Maven 模块工作目录（通常是 {@code cpq-backend/}）向上找仓库根（同时含 {@code cpq-backend}
     * 与 {@code cpq-frontend} 两个子目录的那一层），再定位共享夹具文件。找不到直接抛异常
     * （测试基础设施问题，不该被吞成"跳过"）。
     */
    private static Path fixturePath() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("cpq-backend")) && Files.isDirectory(dir.resolve("cpq-frontend"))) {
                return dir.resolve("dev-docs")
                        .resolve("task-0803-BOM页签增加父子取值公式")
                        .resolve("fixtures")
                        .resolve("tree-formula-parity-cases.json");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "repo root（同时含 cpq-backend/cpq-frontend 的目录）未找到，起点=" + Paths.get("").toAbsolutePath());
    }

    /** 把夹具的 {@code rows[].values} 包成后端 baseRow 形状：系统列在顶层，业务值在 driverRow 内。 */
    private JsonNode toBaseRows(JsonNode rows) {
        ArrayNode out = M.createArrayNode();
        for (JsonNode r : rows) {
            ObjectNode br = M.createObjectNode();
            JsonNode nodeId = r.get("nodeId");
            JsonNode parentId = r.get("parentId");
            if (nodeId != null && !nodeId.isNull()) br.put("__nodeId", nodeId.asText());
            if (parentId != null && !parentId.isNull()) br.put("__parentId", parentId.asText());
            br.put("__lvl", r.path("lvl").asInt(0));
            br.set("driverRow", r.path("values"));
            br.putObject("basicDataValues");
            out.add(br);
        }
        return out;
    }

    @TestFactory
    Collection<DynamicTest> fixtureTests() throws IOException {
        Path path = fixturePath();
        assertTrue(Files.isRegularFile(path), "共享夹具文件不存在: " + path);
        JsonNode cases = M.readTree(Files.readString(path));
        assertTrue(cases.isArray() && cases.size() > 0, "夹具必须是非空数组");

        Collection<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.path("name").asText("(unnamed)");
            JsonNode fields = c.path("fields");
            JsonNode formulas = c.path("formulas");
            JsonNode baseRows = toBaseRows(c.path("rows"));
            JsonNode expected = c.path("expected");

            tests.add(dynamicTest(name, () -> {
                ArrayNode result = calc.calculate(fields, formulas, null, null, baseRows, null,
                    Map.of(), Map.of(), Map.of());

                // rowKey → {field → value}：无 rowKeyFields 时 rowKey = 行下标字符串（"0","1",...），
                // 与夹具 rows 数组下标、以及前端 computeTabFormulasTree 的 Record<number,...> 下标一一对应。
                Map<String, JsonNode> byRowKey = new java.util.HashMap<>();
                for (JsonNode row : result) byRowKey.put(row.path("rowKey").asText(), row.path("values"));

                for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> rowEntry = it.next();
                    String rowIdx = rowEntry.getKey();
                    JsonNode expectedRow = rowEntry.getValue();
                    JsonNode actualRow = byRowKey.get(rowIdx);
                    assertNotNull(actualRow, "[" + name + "] 行 " + rowIdx + " 在后端结果中缺失（byRowKey keys="
                        + byRowKey.keySet() + "）");
                    for (Iterator<Map.Entry<String, JsonNode>> fit = expectedRow.fields(); fit.hasNext(); ) {
                        Map.Entry<String, JsonNode> fieldEntry = fit.next();
                        String field = fieldEntry.getKey();
                        double expectedVal = fieldEntry.getValue().asDouble();
                        JsonNode actualNode = actualRow.get(field);
                        assertNotNull(actualNode, "[" + name + "] 行 " + rowIdx + " 缺列 [" + field + "]（后端结果="
                            + actualRow + "）");
                        assertEquals(expectedVal, actualNode.asDouble(), 1e-9,
                            "[" + name + "] 行 " + rowIdx + " 列 [" + field + "]");
                    }
                }
            }));
        }
        return tests;
    }
}
