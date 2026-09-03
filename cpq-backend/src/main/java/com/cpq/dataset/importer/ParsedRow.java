package com.cpq.dataset.importer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已解析的一行（task-260902 · B-6）。
 *
 * @param excelRow Excel <b>物理</b>行号（1-based）—— 错误报告与 AC-6/7/8/9 都按物理行号定位
 * @param values   DB 列名 → 原始串（只含 Registry 声明且 Excel 表头存在的列；白底 NAME 列不收）
 */
public record ParsedRow(int excelRow, Map<String, String> values) {

    public String get(String column) { return values.get(column); }

    public Map<String, Object> asRowMap() { return new LinkedHashMap<>(values); }
}
