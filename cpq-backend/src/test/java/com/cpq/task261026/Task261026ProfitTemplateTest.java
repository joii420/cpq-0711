package com.cpq.task261026;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-261026 · S-BE · T-01 的文档侧独立判据。
 *
 * <p>断言只来自本任务 AC-1：正式报价模板的「物料」Sheet 为 10 列、利润在末列；
 * 字段矩阵必须独立声明 profit / numeric(26,12) / 可空。测试不读取 Registry 或迁移实现，
 * 避免「实现漏一列，测试跟着漏」的同源假绿。
 */
@DisplayName("task-261026 · 利润模板与字段矩阵（AC-1）")
class Task261026ProfitTemplateTest {

    private static final List<String> MATERIAL_HEADERS = List.of(
            "销售料号", "产品分类", "品名", "规格", "尺寸",
            "旧料号", "单重", "生产料号", "类型", "利润");

    @Test
    @DisplayName("T261026_BE_01 / AC-1：正式 XLSX 的物料 Sheet 恰为 10 列，利润在末列")
    void officialWorkbookHasProfitAsTenthMaterialHeader() throws Exception {
        Path xlsx = task260902Dir().resolve("报价 - 数据导入与表格建表.xlsx");
        assertTrue(Files.isRegularFile(xlsx), "正式报价模板不存在：" + xlsx);

        try (InputStream in = Files.newInputStream(xlsx); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet material = workbook.getSheet("物料");
            assertTrue(material != null, "正式报价模板缺少「物料」Sheet");
            Row header = material.getRow(0);
            assertTrue(header != null, "「物料」Sheet 缺少首行表头");

            List<String> actual = new ArrayList<>();
            for (int i = 0; i < header.getLastCellNum(); i++) {
                actual.add(header.getCell(i) == null ? "" : header.getCell(i).toString().trim());
            }
            System.out.println("[T261026_BE_01] 物料表头=" + actual);
            assertEquals(10, actual.size(), "AC-1：物料 Sheet 必须恰有 10 个表头");
            assertEquals(MATERIAL_HEADERS, actual,
                    "AC-1：利润只能追加在既有 9 列之后，不得重排或改名其它列");
            assertEquals("利润", actual.get(9), "AC-1：第 10 列必须是「利润」");
            String profitFill = ((org.apache.poi.xssf.usermodel.XSSFCell) header.getCell(9))
                    .getCellStyle().getFillForegroundColorColor().getARGBHex();
            assertEquals("FFFFFF00", profitFill,
                    "T-01/AC-1：利润表头必须使用官方模板的选填色（黄 FFFFFF00）");
        }
    }

    @Test
    @DisplayName("T261026_BE_02 / AC-1：字段矩阵独立声明 profit numeric(26,12) 且可空")
    void fieldMatrixDeclaresNullableFixedPointProfit() throws Exception {
        Path matrix = task260902Dir().resolve("字段矩阵.md");
        assertTrue(Files.isRegularFile(matrix), "字段矩阵不存在：" + matrix);
        List<String> lines = Files.readAllLines(matrix, StandardCharsets.UTF_8);
        List<String> hits = lines.stream()
                .filter(line -> line.contains("利润") || line.contains("profit"))
                .toList();
        assertFalse(hits.isEmpty(), "AC-1：字段矩阵中找不到利润/profit，判据不能空跑");

        String joined = String.join("\n", hits).toLowerCase();
        String wholeMatrix = String.join("\n", lines);
        System.out.println("[T261026_BE_02] 字段矩阵命中行=\n" + String.join("\n", hits));
        assertTrue(joined.contains("利润"), "AC-1：字段矩阵缺中文列名「利润」");
        assertTrue(joined.contains("profit"), "AC-1：字段矩阵缺数据库列名 profit");
        assertTrue(joined.replace(" ", "").contains("numeric(26,12)"),
                "AC-1：字段矩阵必须声明 numeric(26,12)，实际命中行=" + hits);
        assertTrue(joined.contains("ffffff00"),
                "T-01/AC-1：利润行必须保留选填底色 FFFFFF00，实际命中行=" + hits);
        assertTrue(wholeMatrix.contains("🟡 其余色 = 选填建字段"),
                "T-01/AC-1：矩阵图例必须明确黄色属于「选填建字段」，实际图例缺失");
    }

    private static Path task260902Dir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("dev-docs")
                    .resolve("task-260902-报价与核价建表与导入方案新规范");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 task-260902 规范目录，cwd=" + Path.of("").toAbsolutePath());
    }
}
