package com.cpq.quotation.service;

import com.cpq.common.PrecisionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260916 B-6 / AC-11 (backend half): the user's real formula「非银点类材料成本公式」on
 * QT-20260916-0881 row H85 (料号 00144).
 *
 * <p>Formula tokens come from the frozen template snapshot {@code repair-260916/snap.jsonl}
 * (byte copy of the task evidence); row values from {@code repair-260916/h85-inputs.json}
 * (copied value-for-value from the evidence script {@code evalh85.mts}). Nothing is hand-built
 * except the one token the AC asks to swap.
 *
 * <ul>
 *   <li>加工费 as {@code component_subtotal} (column subtotal 127.5) → 0.463735546</li>
 *   <li>加工费 as {@code cross_tab_ref agg=NONE match=销售料号+料号} (row value 7.5) → 0.212585104</li>
 * </ul>
 */
class Repair260916H85FormulaTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private final FormulaCalculator calc = new FormulaCalculator();

    private static InputStream res(String name) {
        InputStream is = Repair260916H85FormulaTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(is, name + " not found");
        return is;
    }

    private static JsonNode inputs() throws Exception {
        try (InputStream is = res("repair-260916/h85-inputs.json")) { return OM.readTree(is); }
    }

    private static ArrayNode originalFormula(String componentCode, String formulaName) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(res("repair-260916/snap.jsonl"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode d = OM.readTree(line);
                if (!componentCode.equals(d.path("code").asText())) continue;
                for (JsonNode f : d.path("formulas")) {
                    if (formulaName.equals(f.path("name").asText())) return (ArrayNode) f.get("expression");
                }
            }
        }
        fail("formula " + formulaName + " of " + componentCode + " not found in snap.jsonl");
        return null;
    }

    private static boolean isProcessingFeeSubtotal(JsonNode t) {
        return "component_subtotal".equals(t.path("type").asText())
            && "加工费".equals(t.path("value").asText())
            && "COMP-0004".equals(t.path("component_code").asText());
    }

    private FormulaCalculator.RowContext ctx(JsonNode in) {
        FormulaCalculator.RowContext c = new FormulaCalculator.RowContext();
        in.get("fieldValues").fields().forEachRemaining(e ->
            c.fieldValues.put(e.getKey(), new BigDecimal(e.getValue().asText())));
        c.hostFieldValues = c.fieldValues;
        in.get("componentSubtotals").fields().forEachRemaining(e ->
            c.componentSubtotals.put(e.getKey(), new BigDecimal(e.getValue().asText())));
        in.get("row").fields().forEachRemaining(e -> c.currentRowRaw.put(e.getKey(), e.getValue().asText()));
        in.get("crossTabRows").fields().forEachRemaining(e -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode r : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                r.fields().forEachRemaining(fe -> m.put(fe.getKey(), fe.getValue().asText()));
                rows.add(m);
            }
            c.crossTabRows.put(e.getKey(), rows);
        });
        return c;
    }

    @Test
    void h85_columnSubtotal_vs_rowValue() throws Exception {
        JsonNode in = inputs();
        ArrayNode orig = originalFormula(in.get("hostComponentCode").asText(), in.get("formulaName").asText());
        assertTrue(orig.size() > 0, "formula tokens must be non-empty");
        long hits = 0;
        for (JsonNode t : orig) if (isProcessingFeeSubtotal(t)) hits++;
        assertEquals(1, hits, "original formula must contain exactly one 加工费 column-subtotal token");

        // ① component_subtotal (as stored)
        BigDecimal vSub = calc.evaluateExpression(orig, ctx(in));

        // ② cross_tab_ref(NONE, match=销售料号+料号) — the token shape AC-1 requires for a field-column ref
        ObjectNode cross = OM.createObjectNode();
        // token shape = frontend expressionToTokens result for [来料固定加工费.加工费] (coordinator-provided)
        cross.put("type", "cross_tab_ref");
        cross.put("source", in.get("processingFeeSource").asText());
        cross.put("sourceLabel", "来料固定加工费");
        cross.put("target", "加工费");
        cross.put("agg", "NONE");
        ArrayNode match = cross.putArray("match");
        match.addObject().put("a", "销售料号").put("b", "销售料号");
        match.addObject().put("a", "料号").put("b", "料号");
        ArrayNode swapped = OM.createArrayNode();
        for (JsonNode t : orig) swapped.add(isProcessingFeeSubtotal(t) ? cross : t.deepCopy());
        FormulaCalculator.RowContext c2 = ctx(in);
        c2.outDiag = new HashMap<>();
        BigDecimal vRow = calc.evaluateExpression(swapped, c2);

        System.out.println("[repair-260916 H85] component_subtotal(整列小计) = " + vSub.toPlainString()
            + " (9位显示 " + PrecisionPolicy.roundForDisplay(vSub).toPlainString() + ")");
        System.out.println("[repair-260916 H85] cross_tab_ref(NONE,按行匹配) = " + vRow.toPlainString()
            + " (9位显示 " + PrecisionPolicy.roundForDisplay(vRow).toPlainString() + ")");
        System.out.println("[repair-260916 H85] cross_tab_ref token = " + cross + " diag=" + c2.outDiag);

        assertEquals(0, new BigDecimal("0.463735546").compareTo(PrecisionPolicy.roundForDisplay(vSub)),
            "component_subtotal variant got " + vSub);
        assertEquals(0, new BigDecimal("0.212585104").compareTo(PrecisionPolicy.roundForDisplay(vRow)),
            "cross_tab_ref(NONE) variant got " + vRow);
    }
}
