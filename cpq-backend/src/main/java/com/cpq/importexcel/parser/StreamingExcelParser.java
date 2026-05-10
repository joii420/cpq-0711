package com.cpq.importexcel.parser;

import com.cpq.common.exception.BusinessException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * Apache POI SAX 流式解析器 —— 支持 ≤2000 行，内存占用 O(rows) 而非 O(cells)。
 *
 * <p>使用方式：
 * <pre>
 *   StreamingExcelParser parser = new StreamingExcelParser();
 *   List<List<String>> rows = parser.parseSheet(inputStream, "Sheet1", maxRows);
 * </pre>
 *
 * <p>每行返回为 List&lt;String&gt;，按列索引对齐（null 代表空单元格）。
 */
public class StreamingExcelParser {

    /**
     * 解析 xlsx 流中指定 Sheet（按名称），返回原始字符串行列表。
     *
     * @param xlsx      Excel 输入流（调用方负责关闭）
     * @param sheetName Sheet 名称（精确匹配）
     * @param maxRows   硬上限行数（不含表头行，超过抛 BusinessException）
     * @param headerRow 0-based 表头行号（表头行不计入 maxRows）
     * @return 数据行列表，每行为按列索引对齐的字符串（含 null）
     */
    public List<List<String>> parseSheet(InputStream xlsx, String sheetName,
                                         int maxRows, int headerRow) {
        try (OPCPackage pkg = OPCPackage.open(xlsx)) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings sst = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();

            // 找到目标 Sheet 的输入流
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
            InputStream sheetIs = null;
            while (iter.hasNext()) {
                InputStream s = iter.next();
                if (sheetName.equals(iter.getSheetName())) {
                    sheetIs = s;
                    break;
                } else {
                    s.close();
                }
            }
            if (sheetIs == null) {
                return Collections.emptyList();
            }

            RowCollector collector = new RowCollector(headerRow, maxRows);

            try (InputStream finalSheetIs = sheetIs) {
                XMLReader xmlReader = XMLReaderFactory.createXMLReader();
                xmlReader.setContentHandler(
                        new XSSFSheetXMLHandler(styles, sst, collector, false));
                xmlReader.parse(new InputSource(finalSheetIs));
            }

            return collector.rows;

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(400, "Excel 解析失败(" + sheetName + "): " + e.getMessage());
        }
    }

    /**
     * 返回 xlsx 中所有 Sheet 名称（保留顺序）。
     */
    public List<String> listSheetNames(InputStream xlsx) {
        try (OPCPackage pkg = OPCPackage.open(xlsx)) {
            XSSFReader reader = new XSSFReader(pkg);
            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
            List<String> names = new ArrayList<>();
            while (iter.hasNext()) {
                try (InputStream s = iter.next()) {
                    names.add(iter.getSheetName());
                }
            }
            return names;
        } catch (Exception e) {
            throw new BusinessException(400, "无法读取 Excel Sheet 列表: " + e.getMessage());
        }
    }

    // ── 内部行收集器 ──────────────────────────────────────────────────────

    private static class RowCollector implements SheetContentsHandler {

        private final int headerRowIdx;
        private final int maxRows;
        final List<List<String>> rows = new ArrayList<>();
        private final Map<Integer, String> headerMap = new LinkedHashMap<>();  // colIdx -> colName

        private int currentRowIdx = -1;
        private Map<Integer, String> currentRow;

        RowCollector(int headerRowIdx, int maxRows) {
            this.headerRowIdx = headerRowIdx;
            this.maxRows = maxRows;
        }

        @Override
        public void startRow(int rowNum) {
            currentRowIdx = rowNum;
            currentRow = new LinkedHashMap<>();
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRow == null) return;

            if (rowNum == headerRowIdx) {
                headerMap.putAll(currentRow);
                currentRow = null;
                return;
            }
            if (rowNum <= headerRowIdx) {
                currentRow = null;
                return;
            }

            // 数据行
            int dataRowCount = rows.size();
            if (dataRowCount >= maxRows) {
                throw new BusinessException(400,
                        "导入行数超过上限 " + maxRows + " 行，请拆分后分批导入");
            }

            // 构建按列索引对齐的列表
            int maxCol = currentRow.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> aligned = new ArrayList<>(maxCol + 1);
            for (int i = 0; i <= maxCol; i++) {
                aligned.add(currentRow.get(i));
            }
            // 跳过整行为空
            boolean allBlank = aligned.stream().allMatch(v -> v == null || v.isBlank());
            if (!allBlank) {
                rows.add(aligned);
            }
            currentRow = null;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (currentRow == null || cellReference == null) return;
            int colIdx = colRefToIndex(cellReference);
            if (formattedValue != null && !formattedValue.isBlank()) {
                currentRow.put(colIdx, formattedValue.trim());
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {}

        private int colRefToIndex(String ref) {
            // e.g. "AB3" -> col index
            int col = 0;
            for (char c : ref.toCharArray()) {
                if (c < 'A' || c > 'Z') break;
                col = col * 26 + (c - 'A' + 1);
            }
            return col - 1;
        }

        /** 表头名 -> 列索引的反向映射（供外部使用） */
        Map<String, Integer> getHeaderIndex() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : headerMap.entrySet()) {
                if (e.getValue() != null) m.put(e.getValue(), e.getKey());
            }
            return m;
        }
    }

    // ── 辅助：解析一个 Sheet，同时返回表头映射 ───────────────────────────

    public SheetData parseSheetWithHeader(InputStream xlsx, String sheetName,
                                          int maxRows, int headerRowIdx) {
        try (OPCPackage pkg = OPCPackage.open(xlsx)) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings sst = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();

            XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator) reader.getSheetsData();
            InputStream sheetIs = null;
            while (iter.hasNext()) {
                InputStream s = iter.next();
                if (sheetName.equals(iter.getSheetName())) {
                    sheetIs = s;
                    break;
                } else {
                    s.close();
                }
            }
            if (sheetIs == null) {
                return new SheetData(Collections.emptyMap(), Collections.emptyList());
            }

            RowCollector collector = new RowCollector(headerRowIdx, maxRows);
            try (InputStream finalSheetIs = sheetIs) {
                XMLReader xmlReader = XMLReaderFactory.createXMLReader();
                xmlReader.setContentHandler(
                        new XSSFSheetXMLHandler(styles, sst, collector, false));
                xmlReader.parse(new InputSource(finalSheetIs));
            }

            return new SheetData(collector.getHeaderIndex(), collector.rows);

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            throw new BusinessException(400, "Excel Sheet 解析失败(" + sheetName + "): " + e.getMessage());
        }
    }

    /** 表头+数据容器 */
    public record SheetData(Map<String, Integer> headerIndex, List<List<String>> rows) {
        public String get(List<String> row, String colName) {
            Integer idx = headerIndex.get(colName);
            if (idx == null || idx >= row.size()) return null;
            return row.get(idx);
        }

        public BigDecimal getDecimal(List<String> row, String colName) {
            String v = get(row, colName);
            if (v == null || v.isBlank()) return null;
            try { return new BigDecimal(v.replace(",", "")); }
            catch (NumberFormatException e) { return null; }
        }

        public Integer getInt(List<String> row, String colName) {
            String v = get(row, colName);
            if (v == null || v.isBlank()) return null;
            try { return (int) Double.parseDouble(v.replace(",", "")); }
            catch (NumberFormatException e) { return null; }
        }
    }
}
