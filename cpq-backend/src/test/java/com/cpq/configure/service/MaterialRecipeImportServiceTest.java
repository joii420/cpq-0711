package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialImportReportDTO;
import com.cpq.configure.entity.Element;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeElement;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 材质库导入服务测试（task-0708 · B6）。
 * @TestTransaction 每个用例独立事务并回滚，不污染共享 DB。
 */
@QuarkusTest
public class MaterialRecipeImportServiceTest {

    @Inject
    MaterialRecipeImportService importService;

    // ── 内存构造 workbook ──

    /** codeRows: [材质, 材质编号]；elemRows: [材质, 材质编号, 元素名称, 含量(Number|String), 元素编号] */
    private byte[] buildWorkbook(String[][] codeRows, Object[][] elemRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet cs = wb.createSheet("材质编号");
            Row ch = cs.createRow(0);
            ch.createCell(0).setCellValue("材质");
            ch.createCell(1).setCellValue("材质编号");
            for (int i = 0; i < codeRows.length; i++) {
                Row r = cs.createRow(i + 1);
                r.createCell(0).setCellValue(codeRows[i][0]);
                r.createCell(1).setCellValue(codeRows[i][1]);   // 编号按字符串写(保前导零)
            }
            Sheet es = wb.createSheet("材质对应元素");
            Row eh = es.createRow(0);
            String[] hdr = {"材质", "材质编号", "元素名称", "含量", "元素编号"};
            for (int i = 0; i < hdr.length; i++) eh.createCell(i).setCellValue(hdr[i]);
            for (int i = 0; i < elemRows.length; i++) {
                Row r = es.createRow(i + 1);
                r.createCell(0).setCellValue(str(elemRows[i][0]));
                r.createCell(1).setCellValue(str(elemRows[i][1]));
                r.createCell(2).setCellValue(str(elemRows[i][2]));
                Object c = elemRows[i][3];
                if (c instanceof Number n) r.createCell(3).setCellValue(n.doubleValue());
                else r.createCell(3).setCellValue(str(c));
                r.createCell(4).setCellValue(str(elemRows[i][4]));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    /** 只有 材质编号 一个 sheet 的残缺 workbook */
    private byte[] buildOneSheetWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet cs = wb.createSheet("材质编号");
            Row ch = cs.createRow(0);
            ch.createCell(0).setCellValue("材质");
            ch.createCell(1).setCellValue("材质编号");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private List<MaterialRecipeElement> elementsOf(String code) {
        MaterialRecipe r = MaterialRecipe.find("code", code).firstResult();
        assertNotNull(r, "材质应已落库: " + code);
        return MaterialRecipeElement.find("recipeId = ?1 ORDER BY sortOrder", r.id).list();
    }

    // ── 校验规则 ──

    @Test
    @TestTransaction
    void numericGradeElement_isKept_notSkipped() throws Exception {
        // R1(2026-07-09)：数字牌号(304)是合法组成项，不再跳过。Cu 0.5 + 304 0.5 = 1.0 正常入库。
        byte[] xlsx = buildWorkbook(
            new String[][]{{"Cu/304", "TST001"}},
            new Object[][]{
                {"Cu/304", "TST001", "Cu", 0.50, "10002"},
                {"Cu/304", "TST001", "304", 0.50, "304"},
            });
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);

        assertEquals(1, rep.materialsUpserted, "含数字牌号的复合材应正常入库");
        assertTrue(rep.skipped.isEmpty(), "数字牌号 304 不再被跳过");
        List<MaterialRecipeElement> els = elementsOf("TST001");
        assertEquals(2, els.size(), "Cu + 304 两个元素都入库");
        MaterialRecipeElement grade = els.stream().filter(e -> "304".equals(e.elementCode)).findFirst().orElseThrow();
        assertEquals(0, grade.defaultPct.compareTo(new BigDecimal("50")), "304 含量 ×100 = 50");
        assertEquals("304不锈钢", grade.elementName, "304 回填中文名");
    }

    @Test
    @TestTransaction
    void contentOutOfRange_rowSkipped() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[][]{{"AgC3", "TST002"}},
            new Object[][]{
                {"AgC3", "TST002", "Ag", 0.97, "10001"},
                {"AgC3", "TST002", "C", 0.03, "10012"},
                {"AgC3", "TST002", "Zn", 1.5, "10007"},   // 含量>1 → 跳过
            });
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("含量非法")),
            "含量>1 应被跳过且原因含'含量非法'");
        assertEquals(2, elementsOf("TST002").size(), "非法含量行不入库");
    }

    @Test
    @TestTransaction
    void materialSumNotOne_wholeMaterialSkipped() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[][]{{"BadSum", "TST003"}, {"Good", "TST004"}},
            new Object[][]{
                {"BadSum", "TST003", "Ag", 0.50, "10001"},   // Σ=0.8 ≠ 1 → 整材质跳过
                {"BadSum", "TST003", "Cu", 0.30, "10002"},
                {"Good", "TST004", "Ag", 0.90, "10001"},
                {"Good", "TST004", "Cu", 0.10, "10002"},
            });
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);

        assertNull(MaterialRecipe.find("code", "TST003").firstResult(), "Σ≠1 材质不落库");
        assertNotNull(MaterialRecipe.find("code", "TST004").firstResult(), "合规材质正常落库");
        assertEquals(1, rep.materialsUpserted);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("含量合计≠1")
                && s.raw != null && s.raw.contains("TST003")),
            "应有 TST003 的材质级 Σ≠1 跳过记录");
    }

    @Test
    @TestTransaction
    void materialWithoutCode_rowSkipped() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[][]{{"Good", "TST005"}},                 // Ghost 不在编号表
            new Object[][]{
                {"Good", "TST005", "Ag", 1.0, "10001"},
                {"Ghost", "", "Ag", 1.0, "10001"},              // 材质无对应编号 → 跳过
            });
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("材质无对应编号")
                && "Ghost".equals(s.raw)),
            "Ghost 材质无编号应被跳过");
        assertEquals(1, rep.materialsUpserted);
    }

    // ── ×100 归一 ──

    @Test
    @TestTransaction
    void content_isStoredAsPercentTimes100() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[][]{{"AgC3", "TST006"}},
            new Object[][]{
                {"AgC3", "TST006", "Ag", 0.97, "10001"},
                {"AgC3", "TST006", "C", 0.03, "10012"},
            });
        importService.importLibrary(xlsx);
        List<MaterialRecipeElement> els = elementsOf("TST006");
        BigDecimal ag = els.stream().filter(e -> "Ag".equals(e.elementCode)).findFirst().get().defaultPct;
        BigDecimal c = els.stream().filter(e -> "C".equals(e.elementCode)).findFirst().get().defaultPct;
        assertEquals(0, ag.compareTo(new BigDecimal("97")), "0.97 ×100 = 97");
        assertEquals(0, c.compareTo(new BigDecimal("3")), "0.03 ×100 = 3");
        assertEquals(0, ag.add(c).compareTo(new BigDecimal("100")), "Σ default_pct = 100");
        // locked 语义：is_locked=true, min/max=NULL
        assertTrue(els.stream().allMatch(e -> e.isLocked && e.minPct == null && e.maxPct == null));
    }

    // ── Upsert 语义 ──

    @Test
    @TestTransaction
    void upsert_overwritesExisting_addsNew_leavesUnrelatedManualUntouched() throws Exception {
        // 手工材质 ZZ999（文件外）
        MaterialRecipe manual = new MaterialRecipe();
        manual.code = "ZZ999";
        manual.symbol = "手工材质";
        manual.name = null;
        manual.recipeType = "locked";
        manual.status = "ACTIVE";
        manual.sortOrder = 9999;
        manual.createdAt = java.time.OffsetDateTime.now();
        manual.updatedAt = java.time.OffsetDateTime.now();
        manual.persist();

        // 第一次导入：TST007 = AgOld (Ag100)
        importService.importLibrary(buildWorkbook(
            new String[][]{{"AgOld", "TST007"}},
            new Object[][]{{"AgOld", "TST007", "Ag", 1.0, "10001"}}));
        MaterialRecipe first = MaterialRecipe.find("code", "TST007").firstResult();
        assertNotNull(first);
        assertEquals("AgOld", first.symbol);

        // 第二次导入：TST007 改名 AgNew(Ag90/Cu10) + 新增 TST008；不含 ZZ999
        importService.importLibrary(buildWorkbook(
            new String[][]{{"AgNew", "TST007"}, {"CuMat", "TST008"}},
            new Object[][]{
                {"AgNew", "TST007", "Ag", 0.90, "10001"},
                {"AgNew", "TST007", "Cu", 0.10, "10002"},
                {"CuMat", "TST008", "Cu", 1.0, "10002"},
            }));

        MaterialRecipe overwritten = MaterialRecipe.find("code", "TST007").firstResult();
        assertEquals("AgNew", overwritten.symbol, "TST007 应被覆盖为 AgNew");
        assertEquals(2, elementsOf("TST007").size(), "元素明细覆盖为 2 条(Ag/Cu)，不累加");
        assertNotNull(MaterialRecipe.find("code", "TST008").firstResult(), "TST008 新增");
        assertNotNull(MaterialRecipe.find("code", "ZZ999").firstResult(), "文件外手工材质 ZZ999 不被清掉");
    }

    // ── 元素主表同步 ──

    @Test
    @TestTransaction
    void elementMaster_newSymbolInserted_existingChineseNameNotOverwritten() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[][]{{"AgXx", "TST009"}},
            new Object[][]{
                {"AgXx", "TST009", "Ag", 0.90, "10001"},   // 已 seed(银)
                {"AgXx", "TST009", "Xx", 0.10, "88888"},   // 字典外新符号
            });
        importService.importLibrary(xlsx);

        Element ag = Element.find("elementCode", "Ag").firstResult();
        assertNotNull(ag);
        assertEquals("银", ag.elementName, "已有中文名不被符号覆盖");

        Element xx = Element.find("elementCode", "Xx").firstResult();
        assertNotNull(xx, "字典外新符号应 upsert 进 element");
        assertEquals("Xx", xx.elementName, "未知符号中文名回退=符号");
    }

    // ── 缺 sheet → 400 语义 ──

    @Test
    @TestTransaction
    void missingRequiredSheet_throwsBadRequest() throws Exception {
        byte[] xlsx = buildOneSheetWorkbook();   // 缺 材质对应元素
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> importService.importLibrary(xlsx));
        assertTrue(ex.getMessage().contains("材质对应元素"), "缺 sheet 报错应点名缺失 sheet");
    }

    // ── 性能：1000 元素行 < 3s ──

    @Test
    @TestTransaction
    void performance_1000ElementRows_under3s() throws Exception {
        int materials = 500;
        String[][] codeRows = new String[materials][2];
        Object[][] elemRows = new Object[materials * 2][5];
        for (int i = 0; i < materials; i++) {
            String code = String.format("PERF%04d", i);
            codeRows[i] = new String[]{"M" + i, code};
            elemRows[i * 2] = new Object[]{"M" + i, code, "Ag", 0.60, "10001"};
            elemRows[i * 2 + 1] = new Object[]{"M" + i, code, "Cu", 0.40, "10002"};
        }
        byte[] xlsx = buildWorkbook(codeRows, elemRows);

        long t0 = System.nanoTime();
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(500, rep.materialsUpserted);
        assertEquals(1000, rep.elementRowsInserted);
        assertEquals(1000, rep.totalRows);
        assertTrue(rep.durationMs < 3000, "报告耗时应 <3s，实际 " + rep.durationMs + "ms");
        assertTrue(wallMs < 3000, "墙钟耗时应 <3s，实际 " + wallMs + "ms");
    }

    // ── 真实文件验收（R1 基线：253 落库 + 1 跳 WZHF26-25；数字牌号合法不跳）──

    @Test
    @TestTransaction
    void realFile_R1Import_253Materials_1Skip() throws Exception {
        byte[] xlsx = readRealFileOrSkip();
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);

        assertEquals(654, rep.totalRows, "材质对应元素数据行=654");
        assertEquals(253, rep.materialsUpserted, "R1: 数字牌号合法→253 落库(65 复合材全部入库)");
        assertEquals(1, rep.skippedRowCount, "唯一脏数据闸门 Σ≠1 → 仅 1 跳");
        assertEquals(1, rep.skipped.size());
        MaterialImportReportDTO.SkippedRow only = rep.skipped.get(0);
        assertTrue(only.reason.contains("含量合计≠1"), "唯一跳过是材质级 Σ≠1");
        assertTrue(only.raw != null && only.raw.contains("00242"),
            "唯一跳过是 WZHF26-25(code=00242)，实际 raw=" + only.raw);
        assertTrue(rep.durationMs < 3000, "真文件导入应 <3s，实际 " + rep.durationMs + "ms");
        assertTrue(rep.elementRowsInserted > 500, "落库元素明细行合理(>500)，实际 " + rep.elementRowsInserted);

        // 数字牌号(304/316/…)一律不在 skipped（R1 推翻纯数字跳过）
        for (String pn : List.of("191", "206", "223", "258", "301", "304", "316", "430", "721")) {
            assertTrue(rep.skipped.stream().noneMatch(s -> pn.equals(s.raw)),
                "数字牌号 " + pn + " 不应被跳过(R1)");
        }
    }

    private byte[] readRealFileOrSkip() throws Exception {
        for (String p : new String[]{
                "../dev-docs/task-0708-材质库规范澄清/材质库.xlsx",
                "dev-docs/task-0708-材质库规范澄清/材质库.xlsx"}) {
            java.nio.file.Path path = java.nio.file.Paths.get(p);
            if (java.nio.file.Files.exists(path)) return java.nio.file.Files.readAllBytes(path);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "真实 材质库.xlsx 不在预期路径，跳过真文件验收");
        return null; // unreachable
    }

    // ── 干净模板下载 ──

    @Test
    void template_hasTwoSheetsWithHeaders() throws Exception {
        byte[] bytes = importService.generateTemplate();
        assertTrue(bytes.length > 0, "模板非空");
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertNotNull(wb.getSheet("材质编号"), "含 材质编号 sheet");
            Sheet es = wb.getSheet("材质对应元素");
            assertNotNull(es, "含 材质对应元素 sheet");
            Row h = es.getRow(0);
            assertEquals("材质", h.getCell(0).getStringCellValue());
            assertEquals("材质编号", h.getCell(1).getStringCellValue());
            assertEquals("元素名称", h.getCell(2).getStringCellValue());
            assertEquals("含量", h.getCell(3).getStringCellValue());
            assertEquals("元素编号", h.getCell(4).getStringCellValue());
        }
    }
}
