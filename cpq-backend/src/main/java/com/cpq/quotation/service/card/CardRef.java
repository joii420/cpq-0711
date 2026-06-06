package com.cpq.quotation.service.card;

import java.util.*;

/** Excel 列公式里的卡片引用：指向某页签实例的小计 / 字段(首行或按条件取行) / 或作为聚合源(无 field)。 */
public final class CardRef {
    public enum Mode { FIRST_ROW, ROW_WHERE }
    public enum RhsType { LITERAL, PRODUCT, COLUMN }
    public static final String SUBTOTAL = "__subtotal__";

    /** ROW_WHERE 动态条件右侧"值"引用。 */
    public static final class Rhs {
        public final RhsType type;
        public final String value; // literal:原值 / product:字段名(或 __partNo__) / column:col_key
        public Rhs(RhsType type, String value) { this.type = type; this.value = value; }
    }

    /** 一条结构化条件：left(中文字段) op rhs，logic 与下一条连接。 */
    public static final class CondRow {
        public final String left;
        public final String op;     // eq|ne|gt|gte|lt|lte|in
        public final String logic;  // and|or（末条不用）
        public final Rhs rhs;
        public CondRow(String left, String op, String logic, Rhs rhs) {
            this.left = left; this.op = op; this.logic = logic; this.rhs = rhs;
        }
    }

    public final String tab;      // 页签实例标识（compId:sortOrder）
    public final String field;    // 中文字段名 / __subtotal__ / null(聚合源)
    public final Mode mode;
    public final String cond;     // 旧式 ROW_WHERE 行筛选条件（用别名）；condRows 非空时忽略
    public final Map<String, String> cols; // 别名→中文字段名
    public final List<CondRow> condRows;   // 结构化动态条件（优先于 cond）；空 = 走旧 cond 路径

    private CardRef(String tab, String field, Mode mode, String cond,
                    Map<String, String> cols, List<CondRow> condRows) {
        this.tab = tab; this.field = field; this.mode = mode; this.cond = cond;
        this.cols = cols != null ? cols : Map.of();
        this.condRows = condRows != null ? condRows : List.of();
    }

    public boolean isSubtotal() { return SUBTOTAL.equals(field); }
    public boolean isAggregateSource() { return field == null || field.isBlank(); }
    public boolean hasCondRows() { return !condRows.isEmpty(); }

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
        return new CardRef(tab, field, mode, cond, cols, parseCondRows(m.get("condRows")));
    }

    private static List<CondRow> parseCondRows(Object o) {
        List<CondRow> out = new ArrayList<>();
        if (!(o instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rm)) continue;
            String left = str(rm.get("left"));
            String op = str(rm.get("op"));
            String logic = str(rm.get("logic"));
            out.add(new CondRow(left,
                    op == null || op.isBlank() ? "eq" : op,
                    logic == null || logic.isBlank() ? "and" : logic,
                    parseRhs(rm.get("rhs"))));
        }
        return out;
    }

    private static Rhs parseRhs(Object o) {
        if (!(o instanceof Map<?, ?> rm)) return new Rhs(RhsType.LITERAL, "");
        String t = str(rm.get("type"));
        RhsType type = switch (t == null ? "literal" : t.toLowerCase()) {
            case "product" -> RhsType.PRODUCT;
            case "column" -> RhsType.COLUMN;
            default -> RhsType.LITERAL;
        };
        String val = str(rm.get("value"));
        return new Rhs(type, val != null ? val : "");
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
