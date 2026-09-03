package com.cpq.dataset.importer;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.Headers;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三套数据集通用的 sheet 解析器（task-260902 · B-6）。
 *
 * <p><b>行布局（实测三份模板确认）</b>：
 * <ul>
 *   <li>带版本 sheet：第 1 行 = 中文列名，第 2 行 = 「轴」/「对比项」标记行，<b>数据从第 3 行起</b>；</li>
 *   <li>免版本 sheet：第 1 行 = 中文列名，<b>数据从第 2 行起</b>（无标记行）。</li>
 * </ul>
 * 标记行只作人读，机器一律以 Registry 为准（Registry 与 Excel 标记不符时以 Registry/DDL 为准，
 * 这是 B-3 的启动期自检负责的事，不在导入期兜底）。
 *
 * <p>🚫 <b>不复用</b> {@code com.cpq.basicdata.v6.parser.ExcelParserService}：那个解析器写死
 * 「第 1 行表头、第 2 行起数据」，用在带版本 sheet 上会把标记行当数据。
 * 且 D-13 要求本任务不碰现有代码 —— 改它会波及 Q01~Q19 / P01~P24。
 */
@ApplicationScoped
public class DatasetSheetParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    /**
     * 解析一个 sheet。
     *
     * @param sheet POI sheet（可为 null，表示本次文件没有该 sheet）
     * @param spec  Registry 声明
     */
    public ParsedSheet parse(Sheet sheet, SheetDef spec) {
        ParsedSheet out = new ParsedSheet(spec);
        if (sheet == null) {
            out.present = false;
            return out;
        }
        out.present = true;

        // ── 表头：归一化中文列名 → 列下标
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        Row header = sheet.getRow(0);
        if (header != null) {
            short last = header.getLastCellNum();
            for (int c = 0; c < last; c++) {
                String name = Headers.normalize(cellToString(header.getCell(c)));
                if (!name.isEmpty()) headerIndex.putIfAbsent(name, c);
            }
        }

        // ── Registry 声明的 DB 列必须在表头里出现（NAME 列是白底展示列，不作要求）
        Map<String, Integer> colIndex = new LinkedHashMap<>();
        for (ColumnDef c : spec.persistedColumns()) {
            Integer idx = headerIndex.get(Headers.normalize(c.label));
            if (idx == null) out.missingHeaders.add(c.label);
            else colIndex.put(c.name, idx);
        }
        if (!out.missingHeaders.isEmpty()) return out;   // 表头都不对，逐行校验没有意义

        // ── 数据行
        int firstDataRowIdx = firstDataRow(spec) - 1;   // 物理行号 → 0-based
        for (int r = firstDataRowIdx; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean allBlank = true;
            for (Map.Entry<String, Integer> e : colIndex.entrySet()) {
                String v = cellToString(row.getCell(e.getValue()));
                if (!v.isEmpty()) allBlank = false;
                values.put(e.getKey(), v.isEmpty() ? null : v);
            }
            if (allBlank) continue;           // 整行全空 → 跳过（Excel 常见尾部空行）
            // 🚩 裁决 D-23（2026-09-03 用户拍板）：「只填了轴、其余列全空」的占位行
            //    <b>不跳过</b>，一律按 R-7 走必填校验 → 报「必填项为空」并整份拒收，由用户去模板里删掉。
            //    实证形态：核价2 模板 `物料与元素BOM` 第 10~16 行（只写生产料号 2120011658 /
            //    3110520789 / 2120011659，元素与含量全空）。严格执行下该模板会报 28 处「必填项为空」，
            //    这是<b>预期行为，不是回归</b>。
            //    🚫 曾实现过 `if (payloadBlank) continue;` 跳过分支，已按 D-23 移除 —— 不要再加回来。
            out.rows.add(new ParsedRow(r + 1, values));  // 0-based → 物理行号
        }
        return out;
    }

    /**
     * Excel 数据起始<b>物理</b>行号。带版本 sheet 的第 2 行是「轴 / 对比项」标记行，数据从第 3 行起；
     * 免版本 sheet 无标记行，数据从第 2 行起。AC-6/7/8/9 的行号断言全部依赖这条。
     */
    public static int firstDataRow(SheetDef spec) {
        return spec.versioned ? 3 : 2;
    }

    /**
     * 单元格 → 字符串。数值一律走 {@link BigDecimal} 出普通十进制串，
     * 🚫 绝不出科学计数（{@code 1.7E5}）—— 那会让「组成用量 170000」在指纹里变成另一个值。
     */
    String cellToString(Cell cell) {
        if (cell == null) return "";
        try {
            CellType type = cell.getCellType();
            if (type == CellType.FORMULA) type = cell.getCachedFormulaResultType();
            return switch (type) {
                case STRING -> cell.getStringCellValue().strip();
                case NUMERIC -> formatNumeric(cell);
                case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
                case BLANK -> "";
                default -> DATA_FORMATTER.formatCellValue(cell).strip();
            };
        } catch (Exception e) {
            return DATA_FORMATTER.formatCellValue(cell).strip();
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
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
