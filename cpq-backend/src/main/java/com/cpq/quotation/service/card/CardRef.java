package com.cpq.quotation.service.card;

import java.util.HashMap;
import java.util.Map;

/** Excel 列公式里的卡片引用：指向某页签实例的小计 / 字段(首行或按条件取行) / 或作为聚合源(无 field)。 */
public final class CardRef {
    public enum Mode { FIRST_ROW, ROW_WHERE }
    public static final String SUBTOTAL = "__subtotal__";

    public final String tab;      // 页签实例标识（compId:sortOrder）
    public final String field;    // 中文字段名 / __subtotal__ / null(聚合源)
    public final Mode mode;
    public final String cond;     // ROW_WHERE 行筛选条件（用别名）
    public final Map<String, String> cols; // 别名→中文字段名

    private CardRef(String tab, String field, Mode mode, String cond, Map<String, String> cols) {
        this.tab = tab; this.field = field; this.mode = mode; this.cond = cond;
        this.cols = cols != null ? cols : Map.of();
    }

    public boolean isSubtotal() { return SUBTOTAL.equals(field); }
    public boolean isAggregateSource() { return field == null || field.isBlank(); }

    @SuppressWarnings("unchecked")
    public static CardRef fromMap(Map<String, Object> m) {
        if (m == null) return null;
        String tab = str(m.get("tab"));
        String field = str(m.get("field"));
        String modeStr = str(m.get("mode"));
        Mode mode = "ROW_WHERE".equalsIgnoreCase(modeStr) ? Mode.ROW_WHERE : Mode.FIRST_ROW;
        String cond = str(m.get("cond"));
        Map<String, String> cols = new HashMap<>();
        Object colsObj = m.get("cols");
        if (colsObj instanceof Map<?, ?> cm)
            for (Map.Entry<?, ?> e : cm.entrySet()) cols.put(e.getKey().toString(), e.getValue().toString());
        return new CardRef(tab, field, mode, cond, cols);
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
