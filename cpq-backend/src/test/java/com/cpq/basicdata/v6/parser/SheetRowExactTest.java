package com.cpq.basicdata.v6.parser;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SheetRowExactTest {

    private SheetRow row(Map<String, String> m) { return new SheetRow(1, m); }

    @Test
    void exact_ignoresContainsSiblingColumn() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("投入料号", "");            // 空
        m.put("投入料号名称", "银点");     // 同前缀兄弟列有值
        SheetRow r = row(m);
        assertNull(r.exact("投入料号"), "精确读空键列应为 null，绝不串到名称列");
        assertEquals("银点", r.exact("投入料号名称"));
    }

    @Test
    void exact_trimsAndBlankToNull() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("组成件料号", "  C-1  ");
        m.put("空列", "   ");
        SheetRow r = row(m);
        assertEquals("C-1", r.exact("组成件料号"));
        assertNull(r.exact("空列"));
        assertNull(r.exact("不存在的列"));
    }
}
