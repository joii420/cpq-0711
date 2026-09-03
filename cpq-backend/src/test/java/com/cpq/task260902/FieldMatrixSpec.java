package com.cpq.task260902;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * task-260902「报价与核价建表与导入方案新规范」· <b>{@code 字段矩阵.md} 的机器读取器</b>。
 *
 * <h3>为什么解析文档而不是读 Java Registry</h3>
 * AC-2 的原文是「对 {@code 字段矩阵.md} 列出的每一张主表 …… 列名集合 <b>等于</b> 矩阵中标 ✅ 建字段的列 ∪ 系统列」。
 * 判据的<b>左边</b>是数据库，<b>右边</b>是文档。
 * 若右边改读实现里的 Registry，判据就退化成「实现 == 实现」—— 实现把某列漏掉时，Registry 与建表一起漏，测试照样全绿。
 * 所以本类<b>只认 {@code 字段矩阵.md}</b>，不 import 任何业务类，也不读 {@code src/main} 下一个字节。
 *
 * <h3>解析的格式契约</h3>
 * <pre>
 * ### 物料BOM → `ds_cost_basic_material_bom` 🕐 **带版本** + `ds_cost_basic_material_bom_history`
 * | # | Excel 列名 | 底色 | 建字段 | 字段名 | PG 类型 | 标记 |
 * | 1 | 生产料号 | FFFF0000 | ✅ | `production_no` | varchar(128) | 轴 |
 * | — | 工序名称 | ⚪ theme0 | ❌ 不建 | — | — | 主数据带出 |
 * </pre>
 * 免版本表的标题里没有「带版本」三个字。
 */
final class FieldMatrixSpec {

    /** 每张主表统一追加的系统列（需求文档 R-1）。 */
    static final Set<String> SYSTEM_COLUMNS = new LinkedHashSet<>(List.of(
            "id", "source", "created_at", "created_by", "updated_at", "updated_by"));

    /** 带版本表额外追加的两列（需求文档 R-1）。 */
    static final Set<String> VERSION_COLUMNS = new LinkedHashSet<>(List.of(
            "version_no", "row_fingerprint"));

    /** {@code _history} 表额外追加的三列归档信息（需求文档 R-5）。 */
    static final Set<String> ARCHIVE_COLUMNS = new LinkedHashSet<>(List.of(
            "archived_at", "archived_by", "archive_reason"));

    /** 一张主表的规格。 */
    static final class TableSpec {
        final String dataset;      // quote | cost-basic | cost-detail
        final String sheetName;    // Excel sheet 名（= 导入报错文案里的 sheet）
        final String tableName;    // ds_xxx
        final boolean versioned;
        /** 矩阵中标 ✅ 建字段的列（字段名，按矩阵顺序）。 */
        final List<String> builtColumns = new ArrayList<>();
        /** 矩阵中标 ❌ 不建的列（Excel 中文列名）—— AC-3 用它做「白底列未建」的反向断言。 */
        final List<String> notBuiltExcelColumns = new ArrayList<>();
        /** Excel 中文列名 → 字段名（只含已建字段）。 */
        final Map<String, String> excelToField = new LinkedHashMap<>();
        /** 字段名 → PG 类型原文。 */
        final Map<String, String> fieldType = new LinkedHashMap<>();
        /** 标「轴」的字段名。 */
        String axisField;
        /** 标「轴」的 Excel 中文列名。 */
        String axisExcelColumn;
        /** 标「对比项」的字段名。 */
        final Set<String> comparedFields = new LinkedHashSet<>();

        TableSpec(String dataset, String sheetName, String tableName, boolean versioned) {
            this.dataset = dataset;
            this.sheetName = sheetName;
            this.tableName = tableName;
            this.versioned = versioned;
        }

        String historyTableName() {
            return tableName + "_history";
        }

        /** AC-2 的期望列集合：矩阵建字段 ∪ 系统列 ∪（带版本再并上版本列）。 */
        Set<String> expectedColumns() {
            Set<String> s = new LinkedHashSet<>(builtColumns);
            s.addAll(SYSTEM_COLUMNS);
            if (versioned) {
                s.addAll(VERSION_COLUMNS);
            }
            return s;
        }

        /**
         * AC-5 的期望 history 列集合（🚩 2026-09-03 措辞修正后）：
         * 主表列集合（主表 {@code id} → {@code origin_id}）∪ 3 归档列 ∪ <b>{@code {id}}</b>。
         *
         * <p>最后那个 {@code id} 是 history 表<b>自己的</b> {@code bigserial} 主键 ——
         * 同一个 {@code origin_id} 会因多次升版出现多行，不能拿它当主键。
         * 旧版 AC 漏写了这一句，导致「实现合理但与 AC 字面冲突」，本轮已改 AC 不改实现。
         */
        Set<String> expectedHistoryColumns() {
            Set<String> s = new LinkedHashSet<>();
            for (String c : expectedColumns()) {
                s.add("id".equals(c) ? "origin_id" : c);
            }
            s.addAll(ARCHIVE_COLUMNS);
            s.add("id");
            return s;
        }

        @Override
        public String toString() {
            return tableName + "(" + sheetName + ", versioned=" + versioned + ")";
        }
    }

    private FieldMatrixSpec() {
    }

    /**
     * 定位 {@code 字段矩阵.md}。
     * 测试的工作目录是 {@code cpq-backend/}，任务目录是仓库根下的 {@code dev-docs/...}。
     * 逐级上溯是为了让 IDE / maven / 不同 worktree 都能找到，找不到就<b>硬失败</b>（不许静默跳过 —— 那是假绿）。
     */
    static Path locateMatrix() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve("dev-docs")
                    .resolve("task-260902-报价与核价建表与导入方案新规范")
                    .resolve("字段矩阵.md");
            if (Files.isRegularFile(p)) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException(
                "找不到 字段矩阵.md（AC-2 的判据来源）。cwd=" + Path.of("").toAbsolutePath()
                + "；🚫 不许跳过该断言，找不到就是环境不完整。");
    }

    /** 解析全部 45 张主表。 */
    static List<TableSpec> parseAll() {
        Path matrix = locateMatrix();
        List<String> lines;
        try {
            lines = Files.readAllLines(matrix, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + matrix + " 失败", e);
        }

        List<TableSpec> out = new ArrayList<>();
        String dataset = null;
        TableSpec cur = null;

        for (String raw : lines) {
            String line = raw.trim();

            if (line.startsWith("## ")) {
                if (line.contains("ds_quote_")) {
                    dataset = "quote";
                } else if (line.contains("ds_cost_basic_")) {
                    dataset = "cost-basic";
                } else if (line.contains("ds_cost_detail_")) {
                    dataset = "cost-detail";
                }
                cur = null;
                continue;
            }

            if (line.startsWith("### ")) {
                // ### 物料BOM → `ds_cost_basic_material_bom` 🕐 **带版本** + `..._history`
                String body = line.substring(4).trim();
                int arrow = body.indexOf('→');
                if (arrow < 0) {
                    cur = null;
                    continue;
                }
                String sheetName = body.substring(0, arrow).trim();
                String rest = body.substring(arrow + 1);
                String table = firstBackticked(rest);
                if (table == null) {
                    cur = null;
                    continue;
                }
                boolean versioned = rest.contains("带版本");
                cur = new TableSpec(dataset, sheetName, table, versioned);
                out.add(cur);
                continue;
            }

            if (cur == null || !line.startsWith("|")) {
                continue;
            }
            String[] cells = splitRow(line);
            // 表头行 / 分隔行
            if (cells.length < 7 || "#".equals(cells[0]) || cells[0].startsWith("---")) {
                continue;
            }
            String excelColumn = cells[1];
            String built = cells[3];
            String fieldCell = cells[4];
            String typeCell = cells[5];
            String markCell = cells[6];

            if (built.contains("❌")) {
                cur.notBuiltExcelColumns.add(excelColumn);
                continue;
            }
            if (!built.contains("✅")) {
                continue;
            }
            String field = firstBackticked(fieldCell);
            if (field == null) {
                throw new IllegalStateException(
                        "矩阵行缺字段名：" + cur.tableName + " / " + excelColumn + " → " + line);
            }
            cur.builtColumns.add(field);
            cur.excelToField.put(excelColumn, field);
            cur.fieldType.put(field, typeCell);
            if (markCell.contains("轴")) {
                cur.axisField = field;
                cur.axisExcelColumn = excelColumn;
            }
            if (markCell.contains("对比项")) {
                cur.comparedFields.add(field);
            }
        }
        return out;
    }

    private static String[] splitRow(String line) {
        String body = line;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String firstBackticked(String s) {
        int a = s.indexOf('`');
        if (a < 0) {
            return null;
        }
        int b = s.indexOf('`', a + 1);
        if (b < 0) {
            return null;
        }
        String v = s.substring(a + 1, b).trim();
        return v.isEmpty() ? null : v;
    }
}
