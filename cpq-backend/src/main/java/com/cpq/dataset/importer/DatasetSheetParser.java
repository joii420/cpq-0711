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
 * <p><b>行布局（🚩 2026-09-03 用户澄清后更正，B-21）</b>：
 * <b>所有 sheet 一律「第 1 行 = 中文列名，数据从第 2 行起」</b>，带版本与免版本没有区别。
 *
 * <p>🚩 <b>本类原先写的「带版本 sheet 第 2 行是轴/对比项标记行、数据从第 3 行起」是错的</b>，
 * 已按用户原话更正：
 * <blockquote>刚刚那个标记行是为了告诉你结构，<b>正式的导入数据没有标记行</b></blockquote>
 * 标记行只存在于<b>设计沟通用的模板</b>里，用来告诉实现者哪列是轴、哪列是比对项；
 * 真实导入的数据文件<b>没有它</b>。
 * <p>🚨 按旧口径实现的后果：39 张带版本表<b>每张都被静默吃掉第一条真实数据行</b>，不报错、不告警。
 * 🚫 不要因为「模板里确实见过标记行」而把 {@code versioned ? 3 : 2} 加回来。
 *
 * <p>📌 <b>比对项从哪来不受影响</b>：{@code SheetDef.comparedColumns()} 读的是 Registry 的
 * {@code compared} 标志，<b>从来就不解析 Excel 标记行</b> —— 本次改动只影响「从第几行开始读」，
 * 指纹算法一个字节都没动。
 *
 * <p>🚫 <b>仍然不复用</b> {@code com.cpq.basicdata.v6.parser.ExcelParserService}：
 * 虽然行布局现在与它一致，但 D-13 要求本任务不碰现有代码 —— 改它会波及 Q01~Q19 / P01~P24。
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
     * Excel 数据起始<b>物理</b>行号 —— <b>恒为 2</b>（第 1 行表头，数据紧跟其后）。
     *
     * <p>🚩 <b>B-21（2026-09-03）</b>：原实现是 {@code spec.versioned ? 3 : 2}，
     * 依据是「带版本 sheet 第 2 行是标记行」。用户澄清<b>正式导入数据没有标记行</b>，
     * 该分支会让 39 张带版本表<b>各丢一条真实数据</b>且完全静默，故收敛为常量。
     *
     * <p>参数 {@code spec} 保留：调用方按 sheet 传入，将来若真出现「某类 sheet 布局不同」，
     * 改这一个方法即可，不必再去改调用点。
     *
     * <p>AC-6/7/8/9 的行号断言依赖本方法 —— <b>改它必须同步核对那四条 AC 的夹具行号</b>
     * （B-21 已核对：带版本 sheet 的数据行物理行号整体 −1）。
     */
    public static int firstDataRow(SheetDef spec) {
        return 2;
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
