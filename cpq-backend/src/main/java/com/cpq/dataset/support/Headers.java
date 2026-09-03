package com.cpq.dataset.support;

/** Excel 表头归一化（与既有 {@code ExcelParserService.normalizeHeader} 同口径：去全部空白 + 全角空格）。 */
public final class Headers {
    private Headers() {}

    public static String normalize(String s) {
        if (s == null) return "";
        return s.replace("　", " ").replaceAll("\\s+", "").strip();
    }
}
