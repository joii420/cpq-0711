package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.service.CardFormulaEvaluator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CardRowWhereDynamicTest {
    @Inject CardFormulaEvaluator evaluator;

    private QuotationLineComponentData tab(String comp, String rowDataJson) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = 0; d.subtotal = BigDecimal.ZERO;
        d.rowData = rowDataJson;
        return d;
    }

    /** RHS=product(__partNo__)：取关联号==本行料号的那行加工费。 */
    @Test void dynamic_rhs_product_partNo() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"加工费\":2},{\"关联号\":\"P9\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","__partNo__")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P9", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** RHS=product(本行产品字段)：关联号==本行 productRow 的"母件号"。 */
    @Test void dynamic_rhs_product_field() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"M1\",\"加工费\":3},{\"关联号\":\"M2\",\"加工费\":7}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","母件号")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        Map<String,Object> productRow = Map.of("母件号", "M2");
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, productRow);
        assertEquals(0, new BigDecimal("7").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** RHS=column(本行其它 CARD_FORMULA 列)：A 先算出键，B 的 WHERE 用 [A]。 */
    @Test void dynamic_rhs_column() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"K1\",\"加工费\":4},{\"关联号\":\"K2\",\"加工费\":8}]");
        var refsB = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","column","value","A")))));
        var colA = new LinkedHashMap<String,Object>();
        colA.put("col_key","A"); colA.put("source_type","CARD_FORMULA");
        colA.put("formula","='K2'"); colA.put("refs", Map.of());
        var colB = new LinkedHashMap<String,Object>();
        colB.put("col_key","B"); colB.put("source_type","CARD_FORMULA");
        colB.put("formula","=[加工.加工费(条件)]"); colB.put("refs", refsB);
        var out = evaluator.evaluateColumns(List.of(colB, colA), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("8").compareTo(new BigDecimal(out.get("B").toString())));
    }

    /** 多条件 AND：关联号==本行料号 且 类型=='电镀'。 */
    @Test void dynamic_rhs_multi_and() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"类型\":\"酸洗\",\"加工费\":2}," +
                          "{\"关联号\":\"P1\",\"类型\":\"电镀\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号", "c1", "类型"),
            "condRows", List.of(
                Map.of("left","关联号","op","eq","logic","and",
                    "rhs", Map.of("type","product","value","__partNo__")),
                Map.of("left","类型","op","eq","logic","and",
                    "rhs", Map.of("type","literal","value","电镀")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** RHS 取空（产品字段不存在）→ 不匹配 → DASH。 */
    @Test void dynamic_rhs_missing_key_returns_dash() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"加工费\":2}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","不存在字段")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(CardFormulaEvaluator.DASH, out.get("A"));
    }

    /** 旧 cond 字面量条件无 condRows → 兼容路径仍工作。 */
    @Test void legacy_cond_still_works() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"工序\":\"酸洗\",\"加工费\":2},{\"工序\":\"电镀\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(工序=电镀)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cond", "c0=='电镀'", "cols", Map.of("c0","工序")));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(工序=电镀)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** OR 多条件：类型=='镀铜' 或 类型=='镀镍' → 命中其一即取。 */
    @Test void dynamic_rhs_multi_or() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"类型\":\"酸洗\",\"加工费\":2},{\"类型\":\"镀镍\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "类型"),
            "condRows", List.of(
                Map.of("left","类型","op","eq","logic","or",
                    "rhs", Map.of("type","literal","value","镀铜")),
                Map.of("left","类型","op","eq","logic","and",
                    "rhs", Map.of("type","literal","value","镀镍")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }

    /** 撇号值（C1 回归）：关联号=='P'1' 用 partNo 动态查找应正确匹配，不被静默删引号错配。 */
    @Test void dynamic_rhs_value_with_apostrophe() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"PX\",\"加工费\":2},{\"关联号\":\"P'1\",\"加工费\":9}]");
        var refs = Map.<String,Object>of("加工.加工费(条件)", Map.of(
            "tab", comp+":0", "field", "加工费", "mode", "ROW_WHERE",
            "cols", Map.of("c0", "关联号"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","__partNo__")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=[加工.加工费(条件)]"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P'1", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(new BigDecimal(out.get("A").toString())));
    }
}
