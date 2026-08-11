package com.cpq.perf;

import com.cpq.quotation.service.FormulaCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * task-0803 Task 9-B — AC-29 性能实测：BOM 页签级求值（单元格拓扑，{@link FormulaCalculator#calculate}）耗时分档测量。
 *
 * <p><b>背景</b>：需求 §11.5 G7 裁决 AC-29 当前无量化阈值，只能"测了 + 数字落文档"，本测试即该实测的落地。
 * 现网实查（2026-08-03，{@code cpq_db_0724}）全库 BOM 组件真实渲染的 {@code snapshot_rows} 行数上限仅 **7 行**
 * （{@code COMP-0026}），远小于本测试的分档规模——本测试是前瞻性压测，非复刻现网已有的最大树。
 *
 * <p><b>纯 JUnit（不用 {@code @QuarkusTest}）</b>——{@link FormulaCalculator} 无 CDI 依赖，可直接 {@code new}。
 * 走的是与 {@code TreeFormulaParityFixtureTest} 相同的真实整页签入口
 * {@code calc.calculate(fields, formulas, null, null, baseRows, null, Map.of(), Map.of(), Map.of())}，
 * 而非只测 {@code evaluateExpression} 单点。
 *
 * <p><b>构造的树形</b>：root(1) + branches(≈√N) + leaves，3 层，CSUM/PGET 各半列交替，
 * 逼近 {@code T6}（双向混用）场景但不含条件公式包装（条件分支不改变拓扑复杂度 O(rows×cols)，
 * 只加常数级判定开销，故本测量是保守下界，不是上界）。
 */
class BomTreeFormulaPerfTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    /** 构造一棵近似 nRows 行的 3 层树（root → branches → leaves），返回实际行数可能与 nRows 略有出入。 */
    private ArrayNode buildTree(int nRows) {
        ArrayNode rows = M.createArrayNode();

        ObjectNode root = rows.addObject();
        root.put("__nodeId", "n0");
        root.putNull("__parentId");
        root.put("__lvl", new java.math.BigDecimal("0"));
        root.putObject("driverRow");
        root.putObject("basicDataValues");

        int branches = Math.max(1, (int) Math.round(Math.sqrt(Math.max(1, nRows - 1))));
        List<String> branchIds = new ArrayList<>();
        for (int b = 0; b < branches; b++) {
            String bid = "b" + b;
            branchIds.add(bid);
            ObjectNode br = rows.addObject();
            br.put("__nodeId", bid);
            br.put("__parentId", "n0");
            br.put("__lvl", new java.math.BigDecimal("1"));
            br.putObject("driverRow");
            br.putObject("basicDataValues");
        }

        int remaining = Math.max(0, nRows - 1 - branches);
        for (int i = 0; i < remaining; i++) {
            String bid = branchIds.get(i % branches);
            ObjectNode lf = rows.addObject();
            lf.put("__nodeId", "l" + i);
            lf.put("__parentId", bid);
            lf.put("__lvl", new java.math.BigDecimal("2"));
            ObjectNode dr = lf.putObject("driverRow");
            dr.put("取数值", (i % 17) + 1); // deterministic varied numeric value, never 0 (avoids masking 判据4 concerns)
            lf.putObject("basicDataValues");
        }
        return rows;
    }

    private ArrayNode buildFields(int mCols) {
        ArrayNode fields = M.createArrayNode();
        ObjectNode base = fields.addObject();
        base.put("name", "取数值");
        base.put("field_type", "INPUT_NUMBER");
        for (int c = 0; c < mCols; c++) {
            ObjectNode f = fields.addObject();
            f.put("name", "col" + c);
            f.put("field_type", "FORMULA");
        }
        return fields;
    }

    /** 偶数列 CSUM（bottom-up），奇数列 PGET（top-down）——逼近 T6 双向混用场景。 */
    private ArrayNode buildFormulas(int mCols) {
        ArrayNode formulas = M.createArrayNode();
        for (int c = 0; c < mCols; c++) {
            ObjectNode f = formulas.addObject();
            f.put("name", "col" + c);
            ArrayNode expr = f.putArray("expression");
            ObjectNode tok = expr.addObject();
            tok.put("type", "tree_ref");
            if (c % 2 == 0) {
                tok.put("dir", "CHILD");
                tok.put("agg", "SUM");
            } else {
                tok.put("dir", "PARENT");
                tok.put("agg", "NONE");
            }
            ArrayNode te = tok.putArray("targetExpr");
            ObjectNode fld = te.addObject();
            fld.put("type", "field");
            fld.put("value", "取数值");
        }
        return formulas;
    }

    private long timeOnceMs(JsonNode fields, JsonNode formulas, JsonNode baseRows) {
        long start = System.nanoTime();
        calc.calculate(fields, formulas, null, null, baseRows, null, Map.of(), Map.of(), Map.of());
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private void runScenario(int nRowsTarget, int mCols) {
        JsonNode fields = buildFields(mCols);
        JsonNode formulas = buildFormulas(mCols);
        JsonNode baseRows = buildTree(nRowsTarget);
        int actualRows = baseRows.size();

        // warmup（吸收首次调用的类加载/JIT 抖动，不计入采样）
        for (int i = 0; i < 3; i++) timeOnceMs(fields, formulas, baseRows);

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 7; i++) samples.add(timeOnceMs(fields, formulas, baseRows));
        Collections.sort(samples);
        long min = samples.get(0);
        long median = samples.get(samples.size() / 2);
        long max = samples.get(samples.size() - 1);

        System.out.printf(
            "[PERF][AC-29] rows(target=%d,actual=%d) cols=%d cells=%d -> min=%dms median=%dms max=%dms%n",
            nRowsTarget, actualRows, mCols, actualRows * mCols, min, median, max);
    }

    @Test
    @DisplayName("AC-29 性能实测：分档 10/50/200 行 × 3/8 公式列（结果打印到 stdout，供交付文档摘录）")
    void perfMatrix() {
        int[] rowScales = {10, 50, 200};
        int[] colScales = {3, 8};
        System.out.println("[PERF][AC-29] ==== BOM 父子取值公式 —— 页签级求值耗时分档实测 ====");
        for (int cols : colScales) {
            for (int rows : rowScales) {
                runScenario(rows, cols);
            }
        }
        System.out.println("[PERF][AC-29] ==== 完 ====");
    }

    /**
     * 需求档规模之外的延展探测（非 AC-29 强制要求，供业务方定阈值参考"何时才会变慢"）。
     * 现网真实 BOM 树最大仅 7 行（远小于本档），此处把规模推到 1000/5000 行看曲线走向。
     */
    @Test
    @DisplayName("AC-29 附加：延展规模 1000/5000 行 × 8 列，供阈值参考（非需求强制档位）")
    void perfMatrixStretch() {
        System.out.println("[PERF][AC-29][stretch] ==== 延展规模探测 ====");
        runScenario(1000, 8);
        runScenario(5000, 8);
        System.out.println("[PERF][AC-29][stretch] ==== 完 ====");
    }
}
