package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工序主数据导出测试（task-260902 · B-2）。
 *
 * <p>造数一律用本任务专属前缀 {@code T260902}；{@code @TestTransaction} 每个用例独立事务并<b>回滚</b>，
 * 不在共享开发库留残留。🚫 无清库 / 无 WHERE 的删除语句。
 */
@QuarkusTest
public class ProcessMasterExportServiceTest {

    private static final String NO_A = "T260902A";
    private static final String NO_B = "T260902B";

    @Inject
    ProcessMasterExportService exportService;

    private void seed() {
        ProcessMaster a = new ProcessMaster();
        a.processNo = NO_A;
        a.processName = "T260902自制工序";
        a.processCategory = "制造";
        a.isOutsource = Boolean.FALSE;
        a.standardCurrency = "CNY";
        a.standardUnit = "PCS";
        a.defaultDefectRate = new BigDecimal("0.010000000000");
        a.persist();

        ProcessMaster b = new ProcessMaster();
        b.processNo = NO_B;
        b.processName = "T260902外协工序";
        b.processCategory = "外协";
        b.isOutsource = Boolean.TRUE;
        b.standardCurrency = "USD";
        b.standardUnit = "KG";
        b.defaultDefectRate = null;
        b.persist();
        ProcessMaster.flush();
    }

    private Sheet read(byte[] xlsx) throws Exception {
        Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx));
        return wb.getSheetAt(0);
    }

    private String str(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> new BigDecimal(Double.toString(c.getNumericCellValue()))
                                .stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    /**
     * AC-10：7 个列名逐字＝导入端 {@code COL_*} 常量（工序导入按<b>列名</b>取列）。
     * ⚠️ 第 3 / 5 列刻意与页面表头不同名（页面叫「工序分类」「标准货币」）——
     * 导出要能回导，用的就必须是导入端认识的名字。
     */
    @Test
    @TestTransaction
    void headerUsesImportColumnNames() throws Exception {
        Sheet s = read(exportService.export(null, null, null));
        Row h = s.getRow(0);
        List<String> expected = List.of("工序编号", "工序名称", "工序类别", "是否外协",
                                        "标准币种", "标准单位", "默认不良率");
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) actual.add(str(h.getCell(i)));
        assertEquals(expected, actual);
        assertEquals(expected, ProcessMasterExportService.HEADER,
            "表头常量必须与导入端 COL_* 一致");
    }

    /** AC-10：是否外协写 是/否（不是 true/false）；默认不良率写原始小数（不是 1.00%）。 */
    @Test
    @TestTransaction
    void outsourceAndDefectRateFormats() throws Exception {
        seed();
        Sheet s = read(exportService.export("T260902", null, null));
        assertEquals(2, s.getLastRowNum(), "夹具 2 行");

        Row a = rowOf(s, NO_A);
        assertEquals("否", str(a.getCell(3)), "自制必须写「否」，不是 false");
        assertEquals("0.01", str(a.getCell(6)), "默认不良率写原始小数，不是 1.00%");
        assertEquals("制造", str(a.getCell(2)));
        assertEquals("CNY", str(a.getCell(4)));
        assertEquals("PCS", str(a.getCell(5)));

        Row b = rowOf(s, NO_B);
        assertEquals("是", str(b.getCell(3)), "外协必须写「是」，不是 true");
        assertEquals("", str(b.getCell(6)), "不良率为 NULL 时留空，不写 0");
    }

    /** AC-11：跟随筛选且是全量（不受分页限制）——筛外协后每行「是否外协」都是「是」。 */
    @Test
    @TestTransaction
    void followsFiltersAndExportsAll() throws Exception {
        seed();
        Sheet outsourced = read(exportService.export("T260902", Boolean.TRUE, null));
        assertEquals(1, outsourced.getLastRowNum());
        assertEquals("是", str(outsourced.getRow(1).getCell(3)));

        Sheet byCategory = read(exportService.export("T260902", null, "制造"));
        assertEquals(1, byCategory.getLastRowNum());
        assertEquals(NO_A, str(byCategory.getRow(1).getCell(0)));

        // 不加筛选时必须覆盖全表（含夹具两行），而不是某一页
        long total = ProcessMaster.count();
        Sheet all = read(exportService.export(null, null, null));
        assertEquals(total, all.getLastRowNum(),
            "无筛选导出的数据行数必须等于全表条数（不是 pageSize）");
        assertTrue(total >= 2, "断言前的非空保护：全表至少含夹具 2 行");
    }

    /** AC-23 后端兜底：0 条结果仍返只有表头的 xlsx，不报错。 */
    @Test
    @TestTransaction
    void emptyResultStillProducesHeaderOnlyWorkbook() throws Exception {
        Sheet s = read(exportService.export("zzz不存在zzz260902", null, null));
        assertEquals(0, s.getLastRowNum());
        assertEquals("工序编号", str(s.getRow(0).getCell(0)));
    }

    private Row rowOf(Sheet s, String processNo) {
        for (int r = 1; r <= s.getLastRowNum(); r++) {
            if (processNo.equals(str(s.getRow(r).getCell(0)))) return s.getRow(r);
        }
        return fail("导出文件里找不到工序 " + processNo);
    }
}
