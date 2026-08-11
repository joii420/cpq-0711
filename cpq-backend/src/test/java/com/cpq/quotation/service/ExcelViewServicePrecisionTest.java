package com.cpq.quotation.service;

import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0801 公式计算精度优化 — B8 T2（求值点 #4 — ExcelViewService {@code evaluateFormulaColumn}）。
 *
 * <p>{@code evaluateFormulaColumn} / {@code toNumericStr} 均为私有方法。反射调用时把
 * {@code formulaByName} 传空 Map、{@code templateId}/{@code customerId}/{@code partNo} 传 null，
 * 使 {@code resolveFormulaRef} 走 cachedCells fallback 分支（见源码注释"2. cachedCells fallback"），
 * 不触发任何 @Inject 依赖（TemplateFormulaService / ExcelColumnResolver），故可用
 * {@code new ExcelViewService()} 直接测试，不需要 CDI 容器。
 */
class ExcelViewServicePrecisionTest {

    private final ExcelViewService svc = new ExcelViewService();

    @SuppressWarnings("unchecked")
    private Object invokeEvaluateFormulaColumn(String formulaExpr, Map<String, Object> cachedCells) throws Exception {
        Method m = ExcelViewService.class.getDeclaredMethod("evaluateFormulaColumn",
                String.class, String.class, UUID.class, Map.class, Map.class, List.class, UUID.class, String.class);
        m.setAccessible(true);
        return m.invoke(svc, formulaExpr, "colX", null, Map.of(), cachedCells, List.of(), null, null);
    }

    private Object invokeToNumericStr(Object v) throws Exception {
        Method m = ExcelViewService.class.getDeclaredMethod("toNumericStr", Object.class);
        m.setAccessible(true);
        return m.invoke(svc, v);
    }

    private void invokeWriteAmountCellValue(Cell cell, BigDecimal value) throws Exception {
        Method m = ExcelViewService.class.getDeclaredMethod("writeAmountCellValue", Cell.class, BigDecimal.class);
        m.setAccessible(true);
        m.invoke(svc, cell, value);
    }

    private void invokeWriteFormattedCell(XSSFWorkbook workbook, Cell cell,
                                          Map<String, Object> column, Object value) throws Exception {
        Method m = ExcelViewService.class.getDeclaredMethod("writeFormattedCell",
                XSSFWorkbook.class, DataFormat.class, Map.class, Cell.class, Map.class, Object.class);
        m.setAccessible(true);
        m.invoke(svc, workbook, workbook.createDataFormat(), new HashMap<String, CellStyle>(),
                cell, column, value);
    }

    /**
     * 0.1+0.2 必须十进制精确 = 0.3。走真实生产路径：[A]/[B] 引用经 resolveFormulaRef 从
     * cachedCells 取值 → toNumericStr 加 "B" 后缀 → DecimalJexl.newEngine() 求值。
     */
    @Test
    void t0801_decimalPrecision_pointOnePlusPointTwo() throws Exception {
        Map<String, Object> cachedCells = Map.of("A", new BigDecimal("0.1"), "B", new BigDecimal("0.2"));
        Object result = invokeEvaluateFormulaColumn("[A]+[B]", cachedCells);
        assertTrue(result instanceof BigDecimal, "结果应为 BigDecimal，实际=" + result);
        assertEquals(0, new BigDecimal("0.3").compareTo((BigDecimal) result),
                "0.1+0.2 必须精确等于 0.3，实际=" + result);
    }

    /** toNumericStr 必须给数字字面量追加 "B" 后缀（R-3 的另一个必要条件）。 */
    @Test
    void t0801_toNumericStr_appendsBSuffix() throws Exception {
        assertEquals("0.1B", invokeToNumericStr(new BigDecimal("0.1")));
        assertEquals("0B", invokeToNumericStr(null));
    }

    @Test
    void t0810_jexlPoint4_literalVariableDivisionAndLargeSignedMatrix() throws Exception {
        assertDecimal("0.333333333333", invokeEvaluateFormulaColumn("[A]/[B]",
                Map.of("A", BigDecimal.ONE, "B", new BigDecimal("3"))));
        assertDecimal("98765431.123456789011", invokeEvaluateFormulaColumn("[A]+[B]",
                Map.of("A", new BigDecimal("98765431.123456789012"),
                        "B", new BigDecimal("-0.000000000001"))));
    }

    @Test
    void rawValuesStayExactStringsAndComputedValuesUseNineDigitDisplayStrings() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Cell raw = workbook.createSheet().createRow(0).createCell(0);
            invokeWriteAmountCellValue(raw, new BigDecimal("98765431.123456789012"));
            assertEquals(CellType.STRING, raw.getCellType());
            assertEquals("98765431.123456789012", raw.getStringCellValue());

            Cell computed = workbook.getSheetAt(0).getRow(0).createCell(1);
            invokeWriteFormattedCell(workbook, computed, Map.of("source_type", "FORMULA"),
                    new BigDecimal("1.2345678915"));
            assertEquals(CellType.STRING, computed.getCellType());
            assertEquals("1.234567892", computed.getStringCellValue());
        }
    }

    private static void assertDecimal(String expected, Object actual) {
        assertTrue(actual instanceof BigDecimal, "actual=" + actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), "actual=" + actual);
    }
}
