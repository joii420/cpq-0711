package com.cpq.basicdata.v6.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已解析的 Excel 行。内部以有序列表 [归一化表头, 值] 为权威，保留**重复表头**（按列顺序）。
 * <p>{@link #cells} 为首现优先派生的 Map（向后兼容 + 程序化/测试构造）。
 * <p>取值：{@code getStr/getInt...} 首现优先；{@code getStrNth/getIntNth} 按 {@code contains} 计数取第 N 个。
 */
public class SheetRow {
    public final int rowNo;
    /** 首现优先视图（同名列只保留第一个）。向后兼容用。 */
    public final Map<String, String> cells;
    /** 有序全列 [归一化表头, 值]，保留重复列（列顺序）。取值权威来源。 */
    private final List<String[]> ordered;

    /** 程序化/测试构造：Map（无重复键）。 */
    public SheetRow(int rowNo, Map<String, String> cells) {
        this.rowNo = rowNo;
        this.cells = cells;
        this.ordered = new ArrayList<>();
        for (Map.Entry<String, String> e : cells.entrySet()) {
            this.ordered.add(new String[]{e.getKey(), e.getValue()});
        }
    }

    /** 解析器构造：有序全列（保留重复）。cells 派生为首现优先。 */
    public SheetRow(int rowNo, List<String[]> ordered) {
        this.rowNo = rowNo;
        this.ordered = ordered;
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] hc : ordered) m.putIfAbsent(hc[0], hc[1]);  // 首现优先
        this.cells = m;
    }

    /**
     * 按"包含关键字"匹配读取列，返回**第一个**匹配列。
     * 例如 keys=["宏丰料号"] 匹配"宏丰料号" / "宏丰料号（成品料号）"。空字符串返 null。
     */
    public String getStr(String... keys) {
        for (String[] hc : ordered) {
            String header = hc[0];
            for (String k : keys) {
                if (header.contains(k)) {
                    String v = hc[1];
                    return (v == null || v.isBlank()) ? null : v.trim();
                }
            }
        }
        return null;
    }

    /**
     * 按**精确表头**读取单元格值（非 contains），空白→null、trim。
     * 用于读「料号」键列，避开 {@link #getStr(String...)} 的 contains 匹配命中「…名称」列（如 投入料号 vs 投入料号名称）。
     */
    public String exact(String header) {
        String v = cells.get(header);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** 取第 n 个（1-based）表头 contains(name) 的列值；不足 n 个返 null。 */
    public String getStrNth(String name, int n) {
        int count = 0;
        for (String[] hc : ordered) {
            if (hc[0].contains(name)) {
                if (++count == n) {
                    String v = hc[1];
                    return (v == null || v.isBlank()) ? null : v.trim();
                }
            }
        }
        return null;
    }

    public Integer getInt(String... keys) {
        return parseInt(getStr(keys));
    }

    /** 取第 n 个同名列并解析为整数。 */
    public Integer getIntNth(String name, int n) {
        return parseInt(getStrNth(name, n));
    }

    private static Integer parseInt(String s) {
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
        return ordered.stream().allMatch(hc -> hc[1] == null || hc[1].isBlank());
    }
}
