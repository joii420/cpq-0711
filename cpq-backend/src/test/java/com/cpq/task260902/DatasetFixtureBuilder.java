package com.cpq.task260902;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 夹具构造器：<b>以用户交付的三份 Excel 模板为基准</b>，就地改值后另存为临时 .xlsx。
 *
 * <h3>为什么不从零手搓表头</h3>
 * 表头列名是导入器的结构校验判据（{@code R-7}「表头列名与 Registry 一致」）。
 * 手搓表头 = 我按自己的理解重写一遍列名，一旦与模板有一个字之差，测出来的红是夹具的错不是实现的错。
 * ⇒ <b>表头一律取模板原文，只改数据行</b>。
 *
 * <h3>轴值前缀</h3>
 * 共享库红线要求全部夹具轴值带 {@code TEST-DS-} 前缀，所以 {@link #prefixAxisValues} 会把
 * 「轴列」整列的值统一加前缀。⚠️ 只加轴列，不动主数据列（元素代码 / 工序编号 / 材质料号加了前缀就查不到主数据了）。
 */
final class DatasetFixtureBuilder {

    /** 带版本 sheet：第 1 行表头、第 2 行「轴/对比项」标记、数据从第 3 行起（0-based：2）。 */
    static final int VERSIONED_FIRST_DATA_ROW0 = 2;
    /** 免版本 sheet：无标记行，数据从第 2 行起（0-based：1）。 */
    static final int PLAIN_FIRST_DATA_ROW0 = 1;

    private final Workbook wb;

    private DatasetFixtureBuilder(Workbook wb) {
        this.wb = wb;
    }

    // ═══════════════════════ 模板定位 ═══════════════════════

    static Path taskDir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve("dev-docs").resolve("task-260902-报价与核价建表与导入方案新规范");
            if (Files.isDirectory(p)) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("找不到任务目录，cwd=" + Path.of("").toAbsolutePath());
    }

    /** 基础核价模板（「核价2」）。 */
    static Path costBasicTemplate() {
        return taskDir().resolve("核价2 - 数据导入与表格建表.xlsx");
    }

    /** 报价模板。 */
    static Path quoteTemplate() {
        return taskDir().resolve("报价 - 数据导入与表格建表.xlsx");
    }

    /**
     * 详细核价模板（「核价1」）。
     * 🚩 该文件 2026-09-03 实测<b>仍然损坏</b>（不是 zip，首字节 {@code 87 7d 1c bc}），
     * 且<b>根本不在本 worktree 里</b>（只在主工作区且未提交）。所有依赖它的用例一律以「未验证」结案，不许伪造。
     */
    static Path costDetailTemplate() {
        return taskDir().resolve("核价1 - 数据导入与表格建表.xlsx");
    }

    static boolean readable(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(p); Workbook w = new XSSFWorkbook(in)) {
            return w.getNumberOfSheets() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static DatasetFixtureBuilder from(Path template) {
        if (!Files.isRegularFile(template)) {
            throw new IllegalStateException("模板不存在：" + template);
        }
        try (InputStream in = Files.newInputStream(template)) {
            return new DatasetFixtureBuilder(new XSSFWorkbook(in));
        } catch (IOException e) {
            throw new IllegalStateException("模板不可读（BadZipFile 属于此类）：" + template, e);
        }
    }

    // ═══════════════════════ 定位 ═══════════════════════

    Sheet sheet(String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) {
            throw new IllegalStateException("模板里没有 sheet「" + name + "」，实际有：" + sheetNames());
        }
        return s;
    }

    List<String> sheetNames() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            l.add(wb.getSheetName(i));
        }
        return l;
    }

    /** 按中文列名找列下标（表头在第 1 行）。找不到硬失败 —— 夹具写错必须立刻暴露，不许静默跳过。 */
    int columnIndex(String sheetName, String excelColumn) {
        Sheet s = sheet(sheetName);
        Row header = s.getRow(0);
        if (header == null) {
            throw new IllegalStateException(sheetName + " 无表头行");
        }
        for (int c = 0; c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            if (cell != null && excelColumn.equals(str(cell))) {
                return c;
            }
        }
        throw new IllegalStateException(sheetName + " 表头里没有列「" + excelColumn + "」");
    }

    /** 表头里是否已有该列（用于「模板已补列就不要重复插」）。 */
    boolean hasColumn(String sheetName, String excelColumn) {
        Row header = sheet(sheetName).getRow(0);
        if (header == null) {
            return false;
        }
        for (int c = 0; c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            if (cell != null && excelColumn.equals(str(cell))) {
                return true;
            }
        }
        return false;
    }

    /** Excel 物理行号（1-based，与错误报告里的 {@code row} 同口径）→ POI 的 0-based。 */
    Row rowAt(String sheetName, int excelRowNo) {
        Sheet s = sheet(sheetName);
        Row r = s.getRow(excelRowNo - 1);
        if (r == null) {
            throw new IllegalStateException(sheetName + " 第 " + excelRowNo + " 行不存在（夹具假设与模板不符）");
        }
        return r;
    }

    // ═══════════════════════ 改值 ═══════════════════════

    DatasetFixtureBuilder setText(String sheetName, int excelRowNo, String excelColumn, String value) {
        Cell c = cell(sheetName, excelRowNo, excelColumn);
        c.setBlank(); // 🚨 见 writeCell 的说明：inlineStr 单元格必须先清空，否则写入静默失效
        if (value != null) {
            c.setCellValue(value);
        }
        return this;
    }

    DatasetFixtureBuilder setNumber(String sheetName, int excelRowNo, String excelColumn, double value) {
        Cell c = cell(sheetName, excelRowNo, excelColumn);
        c.setBlank();
        c.setCellValue(value);
        return this;
    }

    DatasetFixtureBuilder blank(String sheetName, int excelRowNo, String excelColumn) {
        cell(sheetName, excelRowNo, excelColumn).setBlank();
        return this;
    }

    private Cell cell(String sheetName, int excelRowNo, String excelColumn) {
        Row r = rowAt(sheetName, excelRowNo);
        int ci = columnIndex(sheetName, excelColumn);
        Cell c = r.getCell(ci);
        return c != null ? c : r.createCell(ci);
    }

    String readAsString(String sheetName, int excelRowNo, String excelColumn) {
        Row r = rowAt(sheetName, excelRowNo);
        Cell c = r.getCell(columnIndex(sheetName, excelColumn));
        return c == null ? null : str(c);
    }

    // ═══════════════════════ 行操作 ═══════════════════════

    /** 交换两行的全部单元格值（AC-16「两行对调顺序、值一字不改」）。 */
    DatasetFixtureBuilder swapRows(String sheetName, int excelRowA, int excelRowB) {
        Sheet s = sheet(sheetName);
        Row ra = rowAt(sheetName, excelRowA);
        Row rb = rowAt(sheetName, excelRowB);
        int last = Math.max(ra.getLastCellNum(), rb.getLastCellNum());
        for (int c = 0; c < last; c++) {
            Object va = valueOf(ra.getCell(c));
            Object vb = valueOf(rb.getCell(c));
            writeValue(ra, c, vb);
            writeValue(rb, c, va);
        }
        s.getWorkbook();
        return this;
    }

    /** 删除一行并把后续行上移（AC-15「删掉一行」）。 */
    DatasetFixtureBuilder deleteRow(String sheetName, int excelRowNo) {
        Sheet s = sheet(sheetName);
        int idx = excelRowNo - 1;
        int last = s.getLastRowNum();
        Row victim = s.getRow(idx);
        if (victim != null) {
            s.removeRow(victim);
        }
        if (idx < last) {
            s.shiftRows(idx + 1, last, -1);
        }
        return this;
    }

    /** 复制一行到 sheet 末尾并按需改值（AC-22 追加全新料号 / AC-44 造 2000 行）。 */
    DatasetFixtureBuilder appendCopyOf(String sheetName, int sourceExcelRowNo, Consumer<Row> mutate) {
        Sheet s = sheet(sheetName);
        Row src = rowAt(sheetName, sourceExcelRowNo);
        Row dst = s.createRow(s.getLastRowNum() + 1);
        for (int c = 0; c < src.getLastCellNum(); c++) {
            writeValue(dst, c, valueOf(src.getCell(c)));
        }
        if (mutate != null) {
            mutate.accept(dst);
        }
        return this;
    }

    /** 清掉一个 sheet 的全部数据行，只留表头（AC-39「某个带版本 sheet 完全为空」）。 */
    DatasetFixtureBuilder clearDataRows(String sheetName, boolean versioned) {
        Sheet s = sheet(sheetName);
        int first = versioned ? VERSIONED_FIRST_DATA_ROW0 : PLAIN_FIRST_DATA_ROW0;
        for (int i = s.getLastRowNum(); i >= first; i--) {
            Row r = s.getRow(i);
            if (r != null) {
                s.removeRow(r);
            }
        }
        return this;
    }

    /** 数据行数（跳过整行空行）。 */
    int dataRowCount(String sheetName, boolean versioned) {
        Sheet s = sheet(sheetName);
        int first = versioned ? VERSIONED_FIRST_DATA_ROW0 : PLAIN_FIRST_DATA_ROW0;
        int n = 0;
        for (int i = first; i <= s.getLastRowNum(); i++) {
            if (!isBlankRow(s.getRow(i))) {
                n++;
            }
        }
        return n;
    }

    /** 数据行的 Excel 物理行号列表（非空行）。 */
    List<Integer> dataRowNumbers(String sheetName, boolean versioned) {
        Sheet s = sheet(sheetName);
        int first = versioned ? VERSIONED_FIRST_DATA_ROW0 : PLAIN_FIRST_DATA_ROW0;
        List<Integer> l = new ArrayList<>();
        for (int i = first; i <= s.getLastRowNum(); i++) {
            if (!isBlankRow(s.getRow(i))) {
                l.add(i + 1);
            }
        }
        return l;
    }

    private static boolean isBlankRow(Row r) {
        if (r == null) {
            return true;
        }
        for (int c = 0; c < r.getLastCellNum(); c++) {
            Cell cell = r.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !str(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════ 加列（D-18 的「客户编号」用） ═══════════════════════

    /**
     * 在指定下标插入一列（表头 + 全部数据行整体右移）。
     *
     * <p>🚩 用途：裁决 D-18 给报价「客户料号」shet 补了「客户编号」红底必填列，
     * 但 {@code 字段矩阵.md} 明写「Excel 里还没有这一列」。
     * ⇒ 夹具必须自己补上，否则每一份报价夹具都会以
     * {@code 表头列名与规范不一致：缺少「客户编号」} 整份拒收 —— 那是夹具的错，不是实现的错。
     */
    DatasetFixtureBuilder insertColumnAt(String sheetName, int index, String header, String defaultValue) {
        Sheet s = sheet(sheetName);
        for (int i = 0; i <= s.getLastRowNum(); i++) {
            Row r = s.getRow(i);
            if (r == null) {
                continue;
            }
            int last = r.getLastCellNum();
            for (int c = last; c > index; c--) {
                writeValue(r, c, valueOf(r.getCell(c - 1)));
            }
            if (i == 0) {
                writeValue(r, index, header);
            } else if (isBlankRowExcept(r, index)) {
                writeValue(r, index, null);
            } else {
                writeValue(r, index, defaultValue);
            }
        }
        return this;
    }

    /**
     * 该行是否<b>只有</b> {@code column} 一列有值、其余全空（裁决 D-23 的「占位行」判据）。
     */
    boolean isOnlyColumnFilled(String sheetName, int excelRowNo, String column) {
        Row r = rowAt(sheetName, excelRowNo);
        int keep = columnIndex(sheetName, column);
        return isBlankRowExcept(r, keep);
    }

    /** 整行除了 {@code skip} 列之外是否全空（判断「这行本来就是空行」）。 */
    private static boolean isBlankRowExcept(Row r, int skip) {
        for (int c = 0; c < r.getLastCellNum(); c++) {
            if (c == skip) {
                continue;
            }
            Cell cell = r.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !str(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════ 轴值前缀 ═══════════════════════

    /**
     * 把某个 sheet 的轴列整列加 {@code TEST-DS-} 前缀（已带前缀的跳过，空值不动）。
     * ⚠️ 只动轴列。主数据列（元素代码 / 工序编号 / 材质料号）加前缀会导致存在性校验必然失败。
     */
    DatasetFixtureBuilder prefixAxisValues(String sheetName, String axisExcelColumn, boolean versioned, String prefix) {
        Sheet s = sheet(sheetName);
        int ci = columnIndex(sheetName, axisExcelColumn);
        int first = versioned ? VERSIONED_FIRST_DATA_ROW0 : PLAIN_FIRST_DATA_ROW0;
        for (int i = first; i <= s.getLastRowNum(); i++) {
            Row r = s.getRow(i);
            if (isBlankRow(r)) {
                continue;
            }
            Cell c = r.getCell(ci);
            if (c == null || c.getCellType() == CellType.BLANK) {
                continue;
            }
            String v = str(c);
            if (v.isBlank() || v.startsWith(prefix)) {
                continue;
            }
            c.setBlank(); // 同 writeValue：inlineStr 单元格不先清空，写入会静默失效
            c.setCellValue(prefix + v);
        }
        return this;
    }

    // ═══════════════════════ 输出 ═══════════════════════

    byte[] toBytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("写夹具 xlsx 失败", e);
        }
    }

    Path writeTo(Path dir, String name) {
        try {
            Files.createDirectories(dir);
            Path p = dir.resolve(name);
            Files.write(p, toBytes());
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("落盘夹具失败", e);
        }
    }

    void close() {
        try {
            wb.close();
        } catch (IOException ignored) {
            // 关闭失败不影响断言
        }
    }

    // ═══════════════════════ 单元格值工具 ═══════════════════════

    static String str(Cell c) {
        if (c == null) {
            return "";
        }
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue().trim();
            case NUMERIC:
                double d = c.getNumericCellValue();
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    return String.valueOf((long) d);
                }
                return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            case BOOLEAN:
                return String.valueOf(c.getBooleanCellValue());
            case FORMULA:
                return c.getCellFormula();
            default:
                return "";
        }
    }

    private static Object valueOf(Cell c) {
        if (c == null) {
            return null;
        }
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue();
            case NUMERIC:
                return c.getNumericCellValue();
            case BOOLEAN:
                return c.getBooleanCellValue();
            case BLANK:
                return null;
            default:
                return str(c);
        }
    }

    /**
     * 🚨 <b>所有单元格写入的唯一出口 —— 必须先 {@code setBlank()}。</b>
     *
     * <h3>为什么（2026-09-03 实证，踩过一次）</h3>
     * 用户 04:22 重新生成的三份模板，单元格底层是 <b>{@code t="inlineStr"}</b>（内联字符串），
     * 不是常见的共享字符串表。对这种单元格，POI 的
     * {@code XSSFCell.setCellValue(String)} <b>既不报错也不生效</b> ——
     * <pre>
     *   CTCell t = inlineStr, isSetIs() = true
     *   c.setCellValue("ZZZ");
     *   c.getStringCellValue()  →  仍然是原值
     * </pre>
     * 后果是<b>夹具的每一次改值都静默失败</b>：清空必填项没清掉、把 5.5 改成 abc 没改成，
     * 于是断言打在**一份没被改过的文件**上 —— 这是最纯的假绿/假红。
     *
     * <p>{@code setBlank()} 会清掉底层的 {@code <is>} 元素，之后写入即生效。
     * ⇒ 本方法是唯一写入口，{@link #setText}/{@link #setNumber} 也照此处理。
     * {@code DatasetFixtureSelfTest#fs06} 用「写后读回」守住这条，防止它悄悄回来。
     */
    private static void writeValue(Row r, int col, Object v) {
        Cell c = r.getCell(col);
        if (c == null) {
            c = r.createCell(col);
        }
        c.setBlank();
        if (v == null) {
            return;
        }
        if (v instanceof Double d) {
            c.setCellValue(d);
        } else if (v instanceof Boolean b) {
            c.setCellValue(b);
        } else {
            c.setCellValue(String.valueOf(v));
        }
    }
}
