package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.dto.ProcessMasterImportReportDTO;
import com.cpq.basicdata.v6.entity.ProcessMaster;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工序主数据批量导入服务测试（task-0712 · childtask-1 · B1）。
 * @TestTransaction 每个用例独立事务并回滚，不污染共享 DB；用例内 process_no 一律加
 * "ZTPMI" 前缀，避免与现网真实工序码（如 Z002、K 系列、MRO 系列等）撞号。
 */
@QuarkusTest
public class ProcessMasterImportServiceTest {

    @Inject
    ProcessMasterImportService importService;

    @Inject
    EntityManager em;

    private static final String[] HEADER = {"工序编号", "工序名称", "工序类别", "是否外协", "标准币种", "标准单位", "默认不良率"};

    /** rows: 每行最多 7 列，缺省列传 null（Excel 里表现为空单元格）。 */
    private byte[] buildWorkbook(String sheetName, String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(sheetName);
            Row h = s.createRow(0);
            for (int i = 0; i < HEADER.length; i++) h.createCell(i).setCellValue(HEADER[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                String[] cols = rows[r];
                for (int c = 0; c < cols.length; c++) {
                    if (cols[c] != null) row.createCell(c).setCellValue(cols[c]);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] buildWorkbook(String[]... rows) throws Exception {
        return buildWorkbook("工序", rows);
    }

    /**
     * 按 processNo 查询工序（每次先 {@code em.clear()}）。
     * <p>导入用原生 SQL upsert，绕过 Hibernate 一级缓存；若持久化上下文里已有该实体的旧托管实例
     * （如上一轮 find 的结果），后续 find 会复用缓存对象而非反映 DB 最新值。生产环境每次请求各自独立
     * 持久化上下文，不会遇到这个问题；测试里同一事务内多次导入 + 校验需显式 clear 模拟"下一次请求"。
     */
    private ProcessMaster findByNo(String no) {
        em.clear();
        return ProcessMaster.<ProcessMaster>find("processNo", no).firstResult();
    }

    // ── 新增 + 覆盖更新 ──

    @Test
    @TestTransaction
    void insertsNew_thenUpdatesExisting_processNameOverwritten() throws Exception {
        byte[] first = buildWorkbook(new String[]{"ZTPMI001", "铣割"});
        ProcessMasterImportReportDTO r1 = importService.importProcesses(first, null);
        assertEquals(1, r1.insertedCount);
        assertEquals(0, r1.updatedCount);
        assertEquals("铣割", findByNo("ZTPMI001").processName);

        byte[] second = buildWorkbook(new String[]{"ZTPMI001", "铣割-改名"});
        ProcessMasterImportReportDTO r2 = importService.importProcesses(second, null);
        assertEquals(0, r2.insertedCount);
        assertEquals(1, r2.updatedCount);
        assertEquals("铣割-改名", findByNo("ZTPMI001").processName, "process_name 应被覆盖");
    }

    // ── 选填列 COALESCE：留空不清已有值 ──

    @Test
    @TestTransaction
    void optionalColumns_blankOnReimport_doesNotClearExistingValue() throws Exception {
        byte[] first = buildWorkbook(new String[]{"ZTPMI002", "成品清洗", "清洗", "否", "CNY", "PCS", "0.02"});
        importService.importProcesses(first, null);
        ProcessMaster after1 = findByNo("ZTPMI002");
        assertEquals("清洗", after1.processCategory);
        assertEquals(Boolean.FALSE, after1.isOutsource);
        assertEquals("CNY", after1.standardCurrency);
        assertEquals("PCS", after1.standardUnit);
        assertEquals(0, after1.defaultDefectRate.compareTo(new BigDecimal("0.02")));

        // 第二次仅改名称，选填列全部留空
        byte[] second = buildWorkbook(new String[]{"ZTPMI002", "成品清洗-v2", null, null, null, null, null});
        importService.importProcesses(second, null);
        ProcessMaster after2 = findByNo("ZTPMI002");
        assertEquals("成品清洗-v2", after2.processName);
        assertEquals("清洗", after2.processCategory, "选填列留空不应清掉原值");
        assertEquals(Boolean.FALSE, after2.isOutsource);
        assertEquals("CNY", after2.standardCurrency);
        assertEquals("PCS", after2.standardUnit);
        assertEquals(0, after2.defaultDefectRate.compareTo(new BigDecimal("0.02")), "默认不良率留空不应清掉原值");
    }

    // ── 跳过：编号/名称为空 ──

    @Test
    @TestTransaction
    void blankProcessNo_rowSkipped_notBlockingBatch() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[]{"ZTPMI003", "无缝焊接"},
            new String[]{"", "无编号工序"});
        ProcessMasterImportReportDTO rep = importService.importProcesses(xlsx, null);
        assertEquals(1, rep.insertedCount);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("工序编号为空")));
        assertNotNull(findByNo("ZTPMI003"));
    }

    @Test
    @TestTransaction
    void blankProcessName_rowSkipped_notBlockingBatch() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[]{"ZTPMI004", "无缝焊接"},
            new String[]{"ZTPMI005", ""});
        ProcessMasterImportReportDTO rep = importService.importProcesses(xlsx, null);
        assertEquals(1, rep.insertedCount);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("工序名称为空")));
        assertNull(findByNo("ZTPMI005"));
    }

    // ── 同码首行胜出 ──

    @Test
    @TestTransaction
    void duplicateProcessNoInSameFile_firstRowWins() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[]{"ZTPMI006", "首行名称"},
            new String[]{"ZTPMI006", "第二行名称-应被丢弃"});
        ProcessMasterImportReportDTO rep = importService.importProcesses(xlsx, null);
        assertEquals(1, rep.insertedCount);
        assertEquals("首行名称", findByNo("ZTPMI006").processName);
        assertTrue(rep.skipped.stream().anyMatch(s -> s.reason.contains("重复工序编号，已取首行")));
    }

    // ── 幂等：同一 xlsx 连导两次 ──

    @Test
    @TestTransaction
    void reimportSameFileTwice_isIdempotent_noDuplicateRows() throws Exception {
        byte[] xlsx = buildWorkbook(
            new String[]{"ZTPMI007", "工序A"},
            new String[]{"ZTPMI008", "工序B"});
        ProcessMasterImportReportDTO r1 = importService.importProcesses(xlsx, null);
        assertEquals(2, r1.insertedCount);
        assertEquals(0, r1.updatedCount);

        long countAfterFirst = ProcessMaster.count("processNo in (?1,?2)", "ZTPMI007", "ZTPMI008");
        assertEquals(2, countAfterFirst);

        ProcessMasterImportReportDTO r2 = importService.importProcesses(xlsx, null);
        assertEquals(0, r2.insertedCount);
        assertEquals(2, r2.updatedCount);

        long countAfterSecond = ProcessMaster.count("processNo in (?1,?2)", "ZTPMI007", "ZTPMI008");
        assertEquals(2, countAfterSecond, "重导两次不应产生重复行");
    }

    // ── 首个 sheet 命名不叫「工序」时仍可解析 ──

    @Test
    @TestTransaction
    void firstSheet_anyName_stillParsed() throws Exception {
        byte[] xlsx = buildWorkbook("Sheet1", new String[]{"ZTPMI009", "任意 sheet 名"});
        ProcessMasterImportReportDTO rep = importService.importProcesses(xlsx, null);
        assertEquals(1, rep.insertedCount);
        assertNotNull(findByNo("ZTPMI009"));
    }

    // ── operatorId 落 created_by/updated_by ──

    @Test
    @TestTransaction
    void operatorId_isRecordedAsCreatedByAndUpdatedBy() throws Exception {
        UUID operator = UUID.randomUUID();
        byte[] xlsx = buildWorkbook(new String[]{"ZTPMI010", "记录操作人"});
        importService.importProcesses(xlsx, operator);
        ProcessMaster pm = findByNo("ZTPMI010");
        assertEquals(operator, pm.createdBy);
        assertEquals(operator, pm.updatedBy);
    }

    // ── 缺表头必需列 → 400 语义 ──

    @Test
    @TestTransaction
    void missingRequiredHeaderColumn_throwsBadRequest() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("工序");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("工序编号"); // 缺 工序名称
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            byte[] xlsx = bos.toByteArray();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> importService.importProcesses(xlsx, null));
            assertTrue(ex.getMessage().contains("工序名称"));
        }
    }

    // ── 干净模板 ──

    @Test
    void template_hasSheetWithRequiredHeaders() throws Exception {
        byte[] bytes = importService.generateTemplate();
        assertTrue(bytes.length > 0, "模板非空");
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(1, wb.getNumberOfSheets());
            Sheet s = wb.getSheetAt(0);
            Row h = s.getRow(0);
            assertEquals("工序编号", h.getCell(0).getStringCellValue());
            assertEquals("工序名称", h.getCell(1).getStringCellValue());
            assertEquals("工序类别", h.getCell(2).getStringCellValue());
            assertEquals("是否外协", h.getCell(3).getStringCellValue());
            assertEquals("标准币种", h.getCell(4).getStringCellValue());
            assertEquals("标准单位", h.getCell(5).getStringCellValue());
            assertEquals("默认不良率", h.getCell(6).getStringCellValue());
        }
    }
}
