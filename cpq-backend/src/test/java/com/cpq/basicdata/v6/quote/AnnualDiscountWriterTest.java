package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.SheetRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0804：共享读列口径的纯函数单测（不需要 CDI/DB）。
 * 关键点：三个 Sheet 的表头不同名（年降系数（%） vs 年降系数（%/年）、
 * 单次固定年降值 vs 单次固定年降金额），靠 SheetRow 的 contains 匹配用一套 key 全覆盖。
 */
class AnnualDiscountWriterTest {

    private SheetRow row(Map<String, String> cells) {
        return new SheetRow(1, new LinkedHashMap<>(cells));
    }

    @Test void readContent_incomingSheetHeaders() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "S-001");
        m.put("项次", "3");
        m.put("投入料号", "AgNi11");
        m.put("投入料号名称", "银镍11");
        m.put("年降顺序", "1");
        m.put("年降系数（%）", "5.5");
        m.put("单次固定年降值", "12.5");
        m.put("货币", "CNY");
        m.put("计价单位", "PCS");
        m.put("降价次数", "3");

        Map<String, Object> c = AnnualDiscountWriter.readContent(row(m));

        assertEquals(1, c.get("discount_order"));
        assertEquals(0, new BigDecimal("5.5").compareTo((BigDecimal) c.get("discount_ratio")));
        assertEquals(0, new BigDecimal("12.5").compareTo((BigDecimal) c.get("fixed_discount_value")));
        assertEquals("CNY", c.get("currency"));
        assertEquals("PCS", c.get("unit"));
        assertEquals(3, c.get("discount_times"));
        assertEquals(3, c.get("seq_no"));
    }

    @Test void readContent_finishedSheetHeaders_differentColumnNames() {
        // 年降系数 sheet：列名是「年降系数（%/年）」「单次固定年降金额」，且没有「项次」列
        Map<String, String> m = new LinkedHashMap<>();
        m.put("销售料号", "S-001");
        m.put("年降顺序", "2");
        m.put("年降系数（%/年）", "3.25");
        m.put("单次固定年降金额", "8");
        m.put("货币", "USD");
        m.put("计价单位", "KG");
        m.put("降价次数", "5");

        Map<String, Object> c = AnnualDiscountWriter.readContent(row(m));

        assertEquals(2, c.get("discount_order"));
        assertEquals(0, new BigDecimal("3.25").compareTo((BigDecimal) c.get("discount_ratio")));
        assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) c.get("fixed_discount_value")));
        assertEquals("USD", c.get("currency"));
        assertEquals(5, c.get("discount_times"));
        assertNull(c.get("seq_no"), "年降系数 sheet 无「项次」列，seq_no 必须为 null");
    }

    @Test void readContent_keysMatchContentColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("年降顺序", "1");
        assertEquals(
            new java.util.HashSet<>(AnnualDiscountWriter.CONTENT),
            AnnualDiscountWriter.readContent(row(m)).keySet(),
            "readContent 的键集必须与 CONTENT 列清单完全一致，否则写入器会漏列或抛非法列");
    }

    @Test void groupKey_hasAllFiveAxisColumns() {
        Map<String, Object> g = AnnualDiscountWriter.groupKey(
            "INCOMING_MATERIAL", "C001", "S-001", "AgNi11");

        assertEquals("QUOTE", g.get("system_type"));
        assertEquals("C001", g.get("customer_no"));
        assertEquals("INCOMING_MATERIAL", g.get("discount_type"));
        assertEquals("S-001", g.get("material_no"));
        assertEquals("AgNi11", g.get("target_no"));
        assertEquals(5, g.size(), "groupKey 必须恰好 5 列，多一列会把本该同组的行拆散");
    }

    @Test void groupKey_targetNoNullable() {
        Map<String, Object> g = AnnualDiscountWriter.groupKey("FINISHED", "C001", "S-001", null);
        assertNull(g.get("target_no"));
        assertTrue(g.containsKey("target_no"), "target_no 为 null 也必须在 key 里（NULL 安全比较靠 IS NOT DISTINCT FROM）");
    }

    // ------------------------------------------------------------------
    // repair-0804 补丁：accumulate 必须按「同组同 discount_order」逐字段末值非空胜归并，
    // 不能无脑 append —— 契约来源见 MaterialMasterBatchImportIntegrationTest:186
    // （同一 (料号, 年降顺序) 一行填「年降系数」、另一行补「单次固定年降值」，
    // 归并前的行为是两行都落库直接撞 uq_annual_discount 整单回滚）。
    // 归并语义原由已删除的 AnnualDiscountRepository.accDiscount 承担（见 git show 4ee9c022 该类）。
    // ------------------------------------------------------------------

    /** 造一个只含指定字段的 content map（其余 CONTENT 列留 null，模拟 Excel 行只填部分列）。 */
    private Map<String, Object> content(Object discountOrder, Object discountRatio, Object fixedDiscountValue) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (String col : AnnualDiscountWriter.CONTENT) c.put(col, null);
        c.put("discount_order", discountOrder);
        c.put("discount_ratio", discountRatio);
        c.put("fixed_discount_value", fixedDiscountValue);
        return c;
    }

    @Test void accumulate_sameOrderDifferentFields_mergesIntoOneRow() {
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        List<Object> key = List.of("S-001", "AgNi11");
        Map<String, Object> gk = AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", "C001", "S-001", "AgNi11");

        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
            content(1, new BigDecimal("5.5"), null));
        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
            content(1, null, new BigDecimal("7.5")));

        List<Map<String, Object>> rows = contentOf.get(key);
        assertEquals(1, rows.size(), "同组同 discount_order 必须合并成一行，不能新增行");
        assertEquals(0, new BigDecimal("5.5").compareTo((BigDecimal) rows.get(0).get("discount_ratio")),
            "第一行的 discount_ratio 应保留");
        assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) rows.get(0).get("fixed_discount_value")),
            "第二行补上的 fixed_discount_value 应合并进来");
    }

    @Test void accumulate_sameOrderSameField_laterWins() {
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        List<Object> key = List.of("S-001", "AgNi11");
        Map<String, Object> gk = AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", "C001", "S-001", "AgNi11");

        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
            content(1, new BigDecimal("5.5"), null));
        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
            content(1, new BigDecimal("9.9"), null));

        List<Map<String, Object>> rows = contentOf.get(key);
        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("9.9").compareTo((BigDecimal) rows.get(0).get("discount_ratio")),
            "同字段都非空时，后到的值必须覆盖先到的值（末值非空胜）");
    }

    @Test void accumulate_differentOrder_keepsTwoRows() {
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        List<Object> key = List.of("S-001", "AgNi11");
        Map<String, Object> gk = AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", "C001", "S-001", "AgNi11");

        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
            content(1, new BigDecimal("5.5"), null));
        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
            content(2, new BigDecimal("6.6"), null));

        List<Map<String, Object>> rows = contentOf.get(key);
        assertEquals(2, rows.size(), "不同 discount_order 不能被误合并");
    }

    @Test void accumulate_differentKey_staysInSeparateGroups() {
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        List<Object> keyA = List.of("S-001", "AgNi11");
        List<Object> keyB = List.of("S-001", "AgNi22");
        Map<String, Object> gkA = AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", "C001", "S-001", "AgNi11");
        Map<String, Object> gkB = AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", "C001", "S-001", "AgNi22");

        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, keyA, gkA,
            content(1, new BigDecimal("5.5"), null));
        AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, keyB, gkB,
            content(1, new BigDecimal("6.6"), null));

        assertEquals(1, contentOf.get(keyA).size());
        assertEquals(1, contentOf.get(keyB).size());
        assertEquals(0, new BigDecimal("5.5").compareTo((BigDecimal) contentOf.get(keyA).get(0).get("discount_ratio")));
        assertEquals(0, new BigDecimal("6.6").compareTo((BigDecimal) contentOf.get(keyB).get(0).get("discount_ratio")));
    }

    @Test void accumulate_nullDiscountOrder_doesNotThrow() {
        // Phase 1 已强制 discount_order 必填，但 handler 兜底分支理论上仍可能传 null，写健壮点。
        Map<List<Object>, Map<String, Object>> groupKeyOf = new LinkedHashMap<>();
        Map<List<Object>, List<Map<String, Object>>> contentOf = new LinkedHashMap<>();
        List<Object> key = List.of("S-001", "AgNi11");
        Map<String, Object> gk = AnnualDiscountWriter.groupKey("INCOMING_MATERIAL", "C001", "S-001", "AgNi11");

        assertDoesNotThrow(() -> {
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
                content(null, new BigDecimal("5.5"), null));
            AnnualDiscountWriter.accumulate(groupKeyOf, contentOf, key, gk,
                content(null, null, new BigDecimal("7.5")));
        });

        List<Map<String, Object>> rows = contentOf.get(key);
        assertEquals(1, rows.size(), "discount_order 同为 null 也应视作同一 order 归并（IS NOT DISTINCT FROM 语义）");
        assertEquals(0, new BigDecimal("5.5").compareTo((BigDecimal) rows.get(0).get("discount_ratio")));
        assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) rows.get(0).get("fixed_discount_value")));
    }
}
