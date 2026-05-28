package com.cpq.basicdata.v6.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 已解析的 Excel 行：表头列名（中文，归一化后）-> 单元格字符串值（trim 后）。
 * row.rowNo 为 1-based 含表头位移。
 */
public class SheetRow {
    public final int rowNo;
    public final Map<String, String> cells;

    public SheetRow(int rowNo, Map<String, String> cells) {
        this.rowNo = rowNo;
        this.cells = cells;
    }

    /**
     * 按"包含关键字"匹配读取列。例如 keys=["宏丰料号"] 会匹配"宏丰料号" / "宏丰料号（成品料号）" / "宏丰料号（主件料号）"。
     * 返回 trim 后的字符串；空字符串返 null。
     */
    public String getStr(String... keys) {
        for (Map.Entry<String, String> e : cells.entrySet()) {
            String header = e.getKey();
            for (String k : keys) {
                if (header.contains(k)) {
                    String v = e.getValue();
                    return (v == null || v.isBlank()) ? null : v.trim();
                }
            }
        }
        return null;
    }

    public Integer getInt(String... keys) {
        String s = getStr(keys);
        if (s == null) return null;
        try {
            return Integer.parseInt(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            try { return new BigDecimal(s.replace(",", "")).intValue(); } catch (Exception ex) { return null; }
        }
    }

    public Long getLong(String... keys) {
        String s = getStr(keys);
        if (s == null) return null;
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            try { return new BigDecimal(s.replace(",", "")).longValue(); } catch (Exception ex) { return null; }
        }
    }

    public BigDecimal getDecimal(String... keys) {
        String s = getStr(keys);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 中文/英文布尔: 是/否 / Y/N / 1/0 / true/false → Boolean，否则 null */
    public Boolean getBool(String... keys) {
        String s = getStr(keys);
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return switch (v) {
            case "是", "y", "yes", "1", "true", "t" -> Boolean.TRUE;
            case "否", "n", "no", "0", "false", "f" -> Boolean.FALSE;
            default -> null;
        };
    }

    public LocalDate getDate(String... keys) {
        String s = getStr(keys);
        if (s == null) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isEmpty() {
        return cells.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
