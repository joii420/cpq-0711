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
class CardAggregateDynamicTest {
    @Inject CardFormulaEvaluator evaluator;

    private QuotationLineComponentData tab(String comp, String rowDataJson) {
        var d = new QuotationLineComponentData();
        d.componentId = UUID.fromString(comp); d.sortOrder = 0; d.subtotal = BigDecimal.ZERO;
        d.rowData = rowDataJson;
        return d;
    }

    private BigDecimal num(Object v) { return new BigDecimal(v.toString()); }

    /** 动态 SUMIF: SUM_OVER([加工#1], c1) WHERE 关联号==本行料号 → 求和命中行的费。 */
    @Test void dynamic_aggregate_product_partNo() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"费\":2}," +
                          "{\"关联号\":\"P9\",\"费\":9},{\"关联号\":\"P9\",\"费\":5}]");
        var refs = Map.<String,Object>of("加工#1", Map.of(
            "tab", comp+":0", "cols", Map.of("c0","关联号","c1","费"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","__partNo__")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1], c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P9", null, Map.of());
        assertEquals(0, new BigDecimal("14").compareTo(num(out.get("A")))); // 9+5
    }

    /** 同一列对同页签两个条件不同的动态聚合互不串值(唯一 keying 核心)。 */
    @Test void two_dynamic_aggregates_same_tab_no_crosstalk() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P9\",\"类型\":\"酸洗\",\"费\":9}," +
                          "{\"关联号\":\"P1\",\"类型\":\"电镀\",\"费\":3}]");
        var refs = Map.<String,Object>of(
            "加工#1", Map.of("tab", comp+":0", "cols", Map.of("c0","关联号","c1","费"),
                "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                    "rhs", Map.of("type","product","value","__partNo__")))),
            "加工#2", Map.of("tab", comp+":0", "cols", Map.of("c0","类型","c1","费"),
                "condRows", List.of(Map.of("left","类型","op","eq","logic","and",
                    "rhs", Map.of("type","literal","value","电镀")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1], c1) + SUM_OVER([加工#2], c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P9", null, Map.of());
        assertEquals(0, new BigDecimal("12").compareTo(num(out.get("A")))); // 9(关联P9) + 3(类型电镀)
    }

    /** 同页签两个静态聚合用不同字段不再 cols collision(潜在缺陷修复回归)。 */
    @Test void two_static_aggregates_same_tab_distinct_fields() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"工序\":\"酸洗\",\"区\":\"东\",\"费\":2}," +
                          "{\"工序\":\"电镀\",\"区\":\"西\",\"费\":7}]");
        var refs = Map.<String,Object>of(
            "加工#1", Map.of("tab", comp+":0", "cols", Map.of("c0","工序","c1","费")),
            "加工#2", Map.of("tab", comp+":0", "cols", Map.of("c0","区","c1","费")));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1] WHERE c0=='电镀', c1) + SUM_OVER([加工#2] WHERE c0=='东', c1)");
        col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(num(out.get("A")))); // 7(工序电镀) + 2(区东)
    }

    /** RHS 取空(产品字段不存在) → 不匹配 → 空集 → 0。 */
    @Test void dynamic_aggregate_missing_key_is_zero() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"关联号\":\"P1\",\"费\":2}]");
        var refs = Map.<String,Object>of("加工#1", Map.of(
            "tab", comp+":0", "cols", Map.of("c0","关联号","c1","费"),
            "condRows", List.of(Map.of("left","关联号","op","eq","logic","and",
                "rhs", Map.of("type","product","value","不存在字段")))));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工#1], c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "P1", null, Map.of());
        assertEquals(0, new BigDecimal("0").compareTo(num(out.get("A"))));
    }

    /** 旧 token(无#后缀、文本 WHERE、无 condRows)静态聚合兼容回归。 */
    @Test void legacy_static_aggregate_still_works() {
        var comp = "55555555-5555-5555-5555-555555555555";
        var d = tab(comp, "[{\"工序\":\"酸洗\",\"费\":2},{\"工序\":\"电镀\",\"费\":9}]");
        var refs = Map.<String,Object>of("加工", Map.of(
            "tab", comp+":0", "cols", Map.of("c0","工序","c1","费")));
        var col = new LinkedHashMap<String,Object>();
        col.put("col_key","A"); col.put("source_type","CARD_FORMULA");
        col.put("formula","=SUM_OVER([加工] WHERE c0=='电镀', c1)"); col.put("refs", refs);
        var out = evaluator.evaluateColumns(List.of(col), List.of(d), null, "PX", null, Map.of());
        assertEquals(0, new BigDecimal("9").compareTo(num(out.get("A"))));
    }
}
