package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialImportReportDTO;
import com.cpq.configure.entity.Element;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeComposition;
import com.cpq.configure.entity.MaterialRecipeConfig;
import com.cpq.configure.entity.MaterialRecipeElement;
import com.cpq.configure.exception.MaterialRecipeApiException;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 材质库导入服务测试（task-0708 · B6 → <b>task-260901 按新格式重写</b>）。
 *
 * <p>旧两 sheet 语义整体作废，但 backtask 要求<b>四类用例的断言意图必须保留</b>：
 * 含量越界 / Σ≠1 / ×100 归一 / 元素主表同步。另加新语义的三条：
 * 只增不改、旧模板拒收、单 sheet 模板结构。
 *
 * <p>⚠️ 编号断言一律<b>相对基线</b>（{@code max+1}），不写死 {@code 00263}/{@code 10096} ——
 * 那两个字面值只在 dev 库 {@code cpq_db_0724} 成立，而 {@code mvnw test} 跑的是
 * {@code cpq_db}（实测 max5=99901 / maxElementNo=90002）。
 *
 * <p>{@code @TestTransaction} 每个用例独立事务并回滚，不污染共享 DB。
 */
@QuarkusTest
public class MaterialRecipeImportServiceTest {

    @Inject
    MaterialRecipeImportService importService;

    @Inject
    jakarta.persistence.EntityManager em;

    // ── 内存构造 workbook（新格式：单 sheet 4 列，全部按字符串写以保 12 位小数）──

    /** rows: [材质, 组号, 元素符号, 含量] */
    private byte[] build(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("材质含量");
            Row h = s.createRow(0);
            String[] hdr = {"材质", "组号", "元素符号", "含量"};
            for (int i = 0; i < hdr.length; i++) h.createCell(i).setCellValue(hdr[i]);
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int j = 0; j < 4; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    /** 旧两 sheet 模板（应被整体拒收）。 */
    private byte[] buildLegacy() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet cs = wb.createSheet("材质编号");
            Row ch = cs.createRow(0);
            ch.createCell(0).setCellValue("材质");
            ch.createCell(1).setCellValue("材质编号");
            Sheet es = wb.createSheet("材质对应元素");
            Row eh = es.createRow(0);
            String[] hdr = {"材质", "材质编号", "元素名称", "含量", "元素编号"};
            for (int i = 0; i < hdr.length; i++) eh.createCell(i).setCellValue(hdr[i]);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private List<MaterialRecipeElement> elementsOfSymbol(String symbol) {
        MaterialRecipe r = MaterialRecipe.find("symbol", symbol).firstResult();
        assertNotNull(r, "材质应已落库: " + symbol);
        List<MaterialRecipeConfig> cfgs = MaterialRecipeConfig
            .<MaterialRecipeConfig>find("recipeId = ?1 ORDER BY seq", r.id).list();
        assertFalse(cfgs.isEmpty(), "材质应至少有一条配置: " + symbol);
        List<MaterialRecipeElement> out = new ArrayList<>();
        for (MaterialRecipeConfig c : cfgs) {
            out.addAll(MaterialRecipeElement
                .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", c.id).list());
        }
        return out;
    }

    private String maxCode5() {
        List<?> rows = em.createNativeQuery(
            "SELECT max(code) FROM material_recipe WHERE code ~ '^[0-9]{5}$'").getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    private long maxNumericElementNo() {
        List<?> rows = em.createNativeQuery(
            "SELECT max(element_no::bigint) FROM element WHERE element_no ~ '^[0-9]+$'").getResultList();
        return rows.isEmpty() || rows.get(0) == null ? 0L : ((Number) rows.get(0)).longValue();
    }

    // ── 意图 1：数字牌号合法（R1，别改回去）──

    @Test
    @TestTransaction
    void numericGradeElement_isKept_notSkipped() throws Exception {
        // 用主表已建档的合金牌号 304（AC-27 原文点名的 9 个之一）
        assertNotNull(Element.find("elementCode", "304").firstResult(), "前置：牌号 304 应已建档");
        MaterialImportReportDTO rep = importService.importLibrary(build(new String[][]{
            {"UT材质304", "1", "Ag", "0.5"},
            {"UT材质304", "1", "304", "0.5"},
        }));

        assertEquals(1, rep.recipesCreated, "含数字牌号的复合材应正常入库");
        List<MaterialRecipeElement> els = elementsOfSymbol("UT材质304");
        assertEquals(2, els.size(), "Ag + 304 两个元素都入库");
        MaterialRecipeElement grade = els.stream()
            .filter(e -> "304".equals(e.elementCode)).findFirst().orElseThrow();
        assertEquals(0, grade.defaultPct.compareTo(new BigDecimal("50")), "304 含量 ×100 = 50");
        assertNotNull(grade.elementNo, "牌号应匹配到 element 主表既有编号");
        assertTrue(rep.skipped.stream().noneMatch(s -> "304".equals(s.raw)), "数字牌号不作为跳过行");
    }

    // ── 意图 2：含量越界 → 跳过该行 ──

    @Test
    @TestTransaction
    void contentOutOfRange_rowSkipped() throws Exception {
        MaterialImportReportDTO rep = importService.importLibrary(build(new String[][]{
            {"UT越界", "1", "Ag", "0.97"},
            {"UT越界", "1", "C", "0.03"},
            {"UT越界", "1", "Zn", "1.5"},     // >1 → 跳过该行
        }));
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("含量非法")),
            "含量>1 应被跳过且原因含'含量非法'");
        assertEquals(2, elementsOfSymbol("UT越界").size(), "非法含量行不入库，其余两行不受影响");
    }

    // ── 意图 3：Σ≠1 → 整组跳过（旧语义是整材质，新语义是整组）──

    @Test
    @TestTransaction
    void groupSumNotOne_groupSkipped_otherMaterialUnaffected() throws Exception {
        MaterialImportReportDTO rep = importService.importLibrary(build(new String[][]{
            {"UT和不对", "1", "Ag", "0.50"},   // Σ=0.8 ≠ 1 → 整组跳过 ⇒ 无有效组 ⇒ 不建材质
            {"UT和不对", "1", "Cu", "0.30"},
            {"UT正常", "1", "Ag", "0.90"},
            {"UT正常", "1", "Cu", "0.10"},
        }));

        assertNull(MaterialRecipe.find("symbol", "UT和不对").firstResult(), "Σ≠1 且无有效组 ⇒ 材质不落库");
        assertNotNull(MaterialRecipe.find("symbol", "UT正常").firstResult(), "合规材质正常落库");
        assertEquals(1, rep.recipesCreated);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("含量合计≠1")
                && s.raw != null && s.raw.contains("UT和不对")),
            "应有 UT和不对 的组级 Σ≠1 跳过记录，实际=" + rep.skipped);
    }

    // ── 意图 4：×100 归一 + locked 语义 ──

    @Test
    @TestTransaction
    void content_isStoredAsPercentTimes100() throws Exception {
        importService.importLibrary(build(new String[][]{
            {"UT归一", "1", "Ag", "0.97"},
            {"UT归一", "1", "C", "0.03"},
        }));
        List<MaterialRecipeElement> els = elementsOfSymbol("UT归一");
        BigDecimal ag = els.stream().filter(e -> "Ag".equals(e.elementCode)).findFirst().get().defaultPct;
        BigDecimal c = els.stream().filter(e -> "C".equals(e.elementCode)).findFirst().get().defaultPct;
        assertEquals(0, ag.compareTo(new BigDecimal("97")), "0.97 ×100 = 97");
        assertEquals(0, c.compareTo(new BigDecimal("3")), "0.03 ×100 = 3");
        assertEquals(0, ag.add(c).compareTo(new BigDecimal("100")), "Σ default_pct = 100");
        assertTrue(els.stream().allMatch(e -> e.isLocked && e.minPct == null && e.maxPct == null),
            "配置元素行一律 is_locked=true / min,max=NULL");
    }

    // ── 意图 5：元素主表同步（自动建档 + 编号自增，脏行 '白银' 必须被正则过滤掉）──

    @Test
    @TestTransaction
    void unknownSymbol_autoRegistered_withNextNumericElementNo() throws Exception {
        long base = maxNumericElementNo();
        assertTrue(base > 0, "前置：element 主表应有纯数字编号");

        MaterialImportReportDTO rep = importService.importLibrary(build(new String[][]{
            {"UT新元素", "1", "Qzz", "0.60"},   // 未建档符号
            {"UT新元素", "1", "Ag", "0.40"},
        }));

        Element created = Element.<Element>find("elementCode", "Qzz").firstResult();
        assertNotNull(created, "未建档符号应被自动建档");
        assertEquals(String.valueOf(base + 1), created.elementNo,
            "元素编号 = ^[0-9]+$ 最大值 + 1（脏行 '白银' 被正则排除，否则 ::bigint 会抛异常）");
        assertEquals("Qzz", created.elementName, "字典外新符号中文回退=符号");

        assertTrue(rep.createdElements.stream().anyMatch(e -> "Qzz".equals(e.elementCode)),
            "导入报告的「本次自动新建元素」清单须列出 Qzz，实际=" + rep.createdElements);

        List<MaterialRecipeElement> els = elementsOfSymbol("UT新元素");
        assertTrue(els.stream().allMatch(e -> e.elementNo != null), "元素行 element_no 全部回填");
    }

    // ── 新语义 1：只增不改（重导不同配比 = 新增一条配置，不覆盖旧的）──

    @Test
    @TestTransaction
    void reimport_addsNewConfig_neverOverwrites_andIsIdempotent() throws Exception {
        // 第一次：一组 Ag90 / Cu10
        importService.importLibrary(build(new String[][]{
            {"UT只增不改", "1", "Ag", "0.9"},
            {"UT只增不改", "1", "Cu", "0.1"},
        }));
        MaterialRecipe r = MaterialRecipe.find("symbol", "UT只增不改").firstResult();
        assertNotNull(r);
        assertEquals(1L, MaterialRecipeConfig.count("recipeId = ?1 AND status='ACTIVE'", r.id));

        // 第二次：内容逐值相同、只是组号变了 ⇒ 跳过（M-4 只看内容，组号不落库、不影响归属）
        MaterialImportReportDTO rep2 = importService.importLibrary(build(new String[][]{
            {"UT只增不改", "9", "Ag", "0.9"},
            {"UT只增不改", "9", "Cu", "0.1"},
        }));
        assertEquals(0, rep2.recipesCreated, "材质已存在 ⇒ 不新建");
        assertEquals(0, rep2.configsCreated, "内容相同 ⇒ 不新增配置（幂等）");
        assertEquals(1, rep2.configsSkippedAsDuplicate, "应计一条 configsSkippedAsDuplicate");
        assertEquals(1L, MaterialRecipeConfig.count("recipeId = ?1 AND status='ACTIVE'", r.id));

        // 第三次：内容真的变了 ⇒ 新增第二条配置，第一条<b>不被覆盖</b>
        MaterialImportReportDTO rep3 = importService.importLibrary(build(new String[][]{
            {"UT只增不改", "1", "Ag", "0.85"},
            {"UT只增不改", "1", "Cu", "0.15"},
        }));
        assertEquals(1, rep3.configsCreated, "新配比 ⇒ 新增一条配置");
        assertEquals(2L, MaterialRecipeConfig.count("recipeId = ?1 AND status='ACTIVE'", r.id));

        List<String> configNos = MaterialRecipeConfig.<MaterialRecipeConfig>find(
            "recipeId = ?1 ORDER BY seq", r.id).list().stream().map(c -> c.configNo).toList();
        assertEquals(List.of(r.code + "-01", r.code + "-02"), configNos,
            "配置编号形如 <材质编号>-01 / -02，实际=" + configNos);

        // 旧配置内容原样保留（只增不改）
        MaterialRecipeConfig first = MaterialRecipeConfig
            .<MaterialRecipeConfig>find("recipeId = ?1 AND seq = 1", r.id).firstResult();
        List<MaterialRecipeElement> firstEls = MaterialRecipeElement
            .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", first.id).list();
        assertEquals(0, firstEls.stream().filter(e -> "Ag".equals(e.elementCode))
                .findFirst().orElseThrow().defaultPct.compareTo(new BigDecimal("90")),
            "🚫 旧配置不得被后来的导入覆盖（Ag 仍是 90）");
    }

    // ── 新语义 2：新材质发号 + 元素组成落库 ──

    @Test
    @TestTransaction
    void newRecipe_allocatesNextCode_andWritesComposition() throws Exception {
        String base = maxCode5();
        assertNotNull(base, "前置：库内应已有 5 位补零材质编号");

        importService.importLibrary(build(new String[][]{
            {"UT发号A", "1", "Ag", "0.7"},
            {"UT发号A", "1", "Cu", "0.3"},
            {"UT发号B", "1", "Ag", "0.6"},
            {"UT发号B", "1", "Cu", "0.4"},
        }));

        MaterialRecipe a = MaterialRecipe.find("symbol", "UT发号A").firstResult();
        MaterialRecipe b = MaterialRecipe.find("symbol", "UT发号B").firstResult();
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(nextCode(base, 1), a.code, "第 1 个新材质 = 基线+1（文件内首次出现顺序）");
        assertEquals(nextCode(base, 2), b.code, "第 2 个新材质 = 基线+2");

        List<MaterialRecipeComposition> comp = MaterialRecipeComposition
            .<MaterialRecipeComposition>find("recipeId = ?1 ORDER BY sortOrder", a.id).list();
        assertEquals(List.of("Ag", "Cu"), comp.stream().map(c -> c.elementCode).toList(),
            "元素组成按元素在文件中首次出现的顺序写入");
        assertTrue(comp.stream().allMatch(c -> c.elementNo != null), "组成 element_no 非空");
    }

    /** 新材质各组元素集合不一致 ⇒ 整个材质跳过、不发号（M-5b / D11）。 */
    @Test
    @TestTransaction
    void newRecipe_inconsistentGroups_wholeRecipeSkipped_noCodeConsumed() throws Exception {
        String base = maxCode5();
        MaterialImportReportDTO rep = importService.importLibrary(build(new String[][]{
            {"UT不一致", "1", "Ag", "0.5"},
            {"UT不一致", "1", "Ni", "0.5"},
            {"UT不一致", "2", "Ag", "0.5"},
            {"UT不一致", "2", "Cu", "0.5"},
        }));
        assertNull(MaterialRecipe.find("symbol", "UT不一致").firstResult(), "整个材质不入库");
        assertEquals(base, maxCode5(), "未消耗材质编号");
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("同一材质内各组元素组成不一致")),
            "应有 M-5b 的跳过记录，实际=" + rep.skipped);
    }

    // ── 新语义 3：旧模板拒收 / 表头不符 / 空数据行 ──

    @Test
    @TestTransaction
    void legacyTwoSheetTemplate_rejected() throws Exception {
        MaterialRecipeApiException ex = assertThrows(MaterialRecipeApiException.class,
            () -> importService.importLibrary(buildLegacy()));
        assertEquals("IMPORT_TEMPLATE_OUTDATED", ex.getErrorCode());
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("导入模板格式已更新"), "实际=" + ex.getMessage());
    }

    @Test
    @TestTransaction
    void wrongHeader_rejected() throws Exception {
        byte[] bad;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("随便");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("材质");
            h.createCell(1).setCellValue("元素");     // 第 2 列不是「组号」
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            bad = bos.toByteArray();
        }
        MaterialRecipeApiException ex = assertThrows(MaterialRecipeApiException.class,
            () -> importService.importLibrary(bad));
        assertEquals("IMPORT_HEADER_INVALID", ex.getErrorCode());
    }

    /** 表头对但零数据行 ⇒ 200 全 0 报告，不抛异常。 */
    @Test
    @TestTransaction
    void headerOnly_returnsZeroReport_noException() throws Exception {
        MaterialImportReportDTO rep = importService.importLibrary(build(new String[0][]));
        assertEquals(0, rep.totalRows);
        assertEquals(0, rep.recipesCreated);
        assertEquals(0, rep.configsCreated);
        assertTrue(rep.skipped.isEmpty());
    }

    /** sheet 名被业务改掉也不该导入失败（按表头识别，不依赖 sheet 名）。 */
    @Test
    @TestTransaction
    void sheetNameIsIrrelevant_headerDrivesParsing() throws Exception {
        byte[] renamed;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("业务自己改的名字");
            Row h = s.createRow(0);
            String[] hdr = {"材质", "组号", "元素符号", "含量"};
            for (int i = 0; i < hdr.length; i++) h.createCell(i).setCellValue(hdr[i]);
            Row r1 = s.createRow(1);
            r1.createCell(0).setCellValue("UT改名");
            r1.createCell(1).setCellValue("1");
            r1.createCell(2).setCellValue("Ag");
            r1.createCell(3).setCellValue("1");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            renamed = bos.toByteArray();
        }
        MaterialImportReportDTO rep = importService.importLibrary(renamed);
        assertEquals(1, rep.recipesCreated, "sheet 名无关，表头对就应正常导入");
    }

    // ── 性能：1000 元素行 < 3s ──

    @Test
    @TestTransaction
    void performance_1000ElementRows_under3s() throws Exception {
        int materials = 500;
        String[][] rows = new String[materials * 2][4];
        for (int i = 0; i < materials; i++) {
            rows[i * 2] = new String[]{"UTP" + i, "1", "Ag", "0.60"};
            rows[i * 2 + 1] = new String[]{"UTP" + i, "1", "Cu", "0.40"};
        }
        byte[] xlsx = build(rows);

        long t0 = System.nanoTime();
        MaterialImportReportDTO rep = importService.importLibrary(xlsx);
        long wallMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(500, rep.recipesCreated);
        assertEquals(500, rep.configsCreated);
        assertEquals(1000, rep.elementRowsInserted);
        assertEquals(1000, rep.totalRows);
        assertTrue(rep.durationMs < 3000, "报告耗时应 <3s，实际 " + rep.durationMs + "ms");
        assertTrue(wallMs < 3000, "墙钟耗时应 <3s，实际 " + wallMs + "ms");
    }

    /**
     * 旧的真实文件基线（{@code 材质库.xlsx}，两 sheet）在新语义下的等价断言：<b>整体拒收</b>。
     * 🚫 不删这个用例 —— 它是「旧模板下线」这条决策唯一的真实文件证据。
     */
    @Test
    @TestTransaction
    void realLegacyFile_isRejectedWholesale() throws Exception {
        byte[] xlsx = readRealFileOrSkip();
        MaterialRecipeApiException ex = assertThrows(MaterialRecipeApiException.class,
            () -> importService.importLibrary(xlsx));
        assertEquals("IMPORT_TEMPLATE_OUTDATED", ex.getErrorCode(),
            "真实旧格式材质库应被整体拒收，而不是按旧语义静默执行");
    }

    private byte[] readRealFileOrSkip() throws Exception {
        for (String p : new String[]{
                "../dev-docs/task-0708-材质库规范澄清/材质库.xlsx",
                "../dev-docs/task-260708-材质库规范澄清/材质库.xlsx",
                "dev-docs/task-0708-材质库规范澄清/材质库.xlsx"}) {
            java.nio.file.Path path = java.nio.file.Paths.get(p);
            if (java.nio.file.Files.exists(path)) return java.nio.file.Files.readAllBytes(path);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "真实 材质库.xlsx 不在预期路径，跳过真文件验收");
        return null; // unreachable
    }

    // ── 干净模板下载 ──

    @Test
    void template_isSingleSheetWithFourColumns() throws Exception {
        byte[] bytes = importService.generateTemplate();
        assertTrue(bytes.length > 0, "模板非空");
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(1, wb.getNumberOfSheets(), "单 sheet，不含第二个工作表");
            Sheet s = wb.getSheetAt(0);
            Row h = s.getRow(0);
            assertEquals("材质", h.getCell(0).getStringCellValue());
            assertEquals("组号", h.getCell(1).getStringCellValue());
            assertEquals("元素符号", h.getCell(2).getStringCellValue());
            assertEquals("含量", h.getCell(3).getStringCellValue());
            assertNull(h.getCell(4), "不含「元素编号」第 5 列");
            assertEquals(2, s.getLastRowNum(), "含 2 行示例数据");
        }
    }

    private String nextCode(String base, int n) {
        int width = base.length();
        long v = Long.parseLong(base) + n;
        return String.format("%0" + width + "d", v);
    }
}
