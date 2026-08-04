package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.SheetRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
}
