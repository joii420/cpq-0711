package com.cpq.basicdata.v6.parser;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 解析服务：把 .xlsx 加载为 Workbook，按 SheetName 取 Sheet，再按表头行解析数据。
 * <p>支持：
 * <ul>
 *   <li>表头容忍空列、首列空格、前后中英文混杂</li>
 *   <li>数据单元格统一 trim、空字符串归一为空串</li>
 *   <li>数字单元格按 DataFormatter 格式化（避免 1.0 / 1.0E2 等）</li>
 *   <li>日期单元格按 yyyy-MM-dd 格式化</li>
 *   <li>整行全空判定 isEmpty 跳过</li>
 * </ul>
 */
@ApplicationScoped
public class ExcelParserService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    /** 加载 Workbook（调用方负责关闭 InputStream）。 */
    public XSSFWorkbook open(InputStream is) {
        try {
            return new XSSFWorkbook(is);
        } catch (Exception e) {
            throw new RuntimeException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把 Sheet 解析为 SheetRow 列表。
     * <p>约定：第 1 行为表头；表头列名做 trim 去全角空格；数据从第 2 行开始。
     * <p>整行全空时跳过；保留 rowNo（1-based 含表头）。
     */
    public List<SheetRow> parseSheet(Sheet sheet) {
        List<SheetRow> result = new ArrayList<>();
        if (sheet == null || sheet.getLastRowNum() < 1) {
            return result;
        }
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return result;

        // 收集表头：列号 -> 列名（中文）。允许同名重复列（由 SheetRow 按列序/第N个解析）。
        Map<Integer, String> headerMap = new LinkedHashMap<>();
        short last = headerRow.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell cell = headerRow.getCell(c);
            String name = cellToString(cell);
            if (name != null && !name.isBlank()) {
                headerMap.put(c, normalizeHeader(name));
            }
        }
        if (headerMap.isEmpty()) return result;

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            List<String[]> ordered = new ArrayList<>();   // 保留重复列(列序)
            boolean allBlank = true;
            for (Map.Entry<Integer, String> h : headerMap.entrySet()) {
                Cell c = row.getCell(h.getKey());
                String v = cellToString(c);
                if (v != null && !v.isBlank()) allBlank = false;
                ordered.add(new String[]{h.getValue(), v});
            }
            if (allBlank) continue;
            result.add(new SheetRow(r + 1, ordered)); // +1 转为 1-based 行号
        }
        return result;
    }

    /** 归一化表头：去前后空格、全角空格、空白字符。 */
    private String normalizeHeader(String s) {
        if (s == null) return null;
        return s.replace("　", " ").replaceAll("\\s+", "").trim();
    }

    /** 把任意 Cell 转为字符串（trim 后），null/blank 返空串。 */
    private String cellToString(Cell cell) {
        if (cell == null) return "";
        try {
            CellType type = cell.getCellType();
            if (type == CellType.FORMULA) {
                type = cell.getCachedFormulaResultType();
                return switch (type) {
                    case NUMERIC -> formatNumeric(cell);
                    case STRING -> cell.getStringCellValue().trim();
                    case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                    default -> "";
                };
            }
            return switch (type) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> formatNumeric(cell);
                case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                case BLANK -> "";
                default -> DATA_FORMATTER.formatCellValue(cell).trim();
            };
        } catch (Exception e) {
            return DATA_FORMATTER.formatCellValue(cell).trim();
        }
    }

    private String formatNumeric(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DATE_FMT);
        }
        double d = cell.getNumericCellValue();
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        // 用 BigDecimal 避免 1.7E5 这种科学计数
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
