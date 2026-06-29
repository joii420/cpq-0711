package com.cpq.basicdata.v6.quote;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #3 异步导入机制护栏：验证 {@link QuoteImportService#processImport} 经 {@link ManagedExecutor}
 * 在**后台线程**执行时，{@code @ActivateRequestContext} + request-scoped EntityManager + 各 Sheet 的
 * REQUIRES_NEW 事务 + finalize 能完整跑通，import_record 从 PROCESSING 收敛到终态（不会卡死/静默失败）。
 *
 * <p>用最小空 workbook（仅一个空「物料BOM」sheet）→ 0 行 → 期望 SUCCESS。此测试只证"后台执行机制"，
 * 不重复验证数据处理逻辑（后者由 processImport body 与原同步路径逐字一致 + #2 等价测试覆盖）。
 */
@QuarkusTest
class AsyncImportProcessTest {

    @Inject QuoteImportService svc;
    @Inject ManagedExecutor managedExecutor;
    @Inject EntityManager em;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    static final String FNAME = "async-mech-test.xlsx";

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM import_record WHERE original_file_name = :f")
          .setParameter("f", FNAME).executeUpdate();
    }

    @AfterEach void after() { cleanup(); }

    private byte[] minimalWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            wb.createSheet("物料BOM");
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    @Transactional
    UUID anyCustomerId() {
        return (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
    }

    /** 复用已存在导入记录里的 imported_by，保证 FK(import_record_imported_by_fkey) 有效。 */
    @Transactional
    UUID anyUserId() {
        return (UUID) em.createNativeQuery(
            "SELECT imported_by FROM import_record WHERE imported_by IS NOT NULL LIMIT 1")
            .getSingleResult();
    }

    @Transactional
    String statusOf(UUID recordId) {
        return (String) em.createNativeQuery(
            "SELECT import_status FROM import_record WHERE id = :id")
            .setParameter("id", recordId).getSingleResult();
    }

    @Transactional
    String metadataOf(UUID recordId) {
        return (String) em.createNativeQuery(
            "SELECT metadata FROM import_record WHERE id = :id")
            .setParameter("id", recordId).getSingleResult();
    }

    @Test
    void processImport_onManagedExecutorThread_finalizesRecord() throws Exception {
        UUID user = anyUserId();
        UUID recordId = svc.createImportRecord(anyCustomerId(), FNAME, user);
        assertEquals("PROCESSING", statusOf(recordId), "建记录后初始应为 PROCESSING");

        byte[] bytes = minimalWorkbook();
        // 真实生产路径：后台线程跑 processImport
        managedExecutor.runAsync(() -> svc.processImport(recordId, "TEST_CUST_ASYNC", FNAME, bytes, user))
            .get(30, TimeUnit.SECONDS);

        String finalStatus = statusOf(recordId);
        assertNotEquals("PROCESSING", finalStatus, "后台处理后应已 finalize（非 PROCESSING）");
        assertTrue(List.of("SUCCESS", "PARTIAL", "FAILED").contains(finalStatus),
            "终态应为 SUCCESS/PARTIAL/FAILED，实际=" + finalStatus);
        assertEquals("SUCCESS", finalStatus, "0 行空 workbook 期望 SUCCESS（机制跑通）");
    }

    /** updateProgress 应把 {progress:{done,total,current}} 提交到 metadata（供前端轮询渲染进度条）。 */
    @Test
    void updateProgress_writesCommittedProgressJson() {
        UUID recordId = svc.createImportRecord(anyCustomerId(), FNAME, anyUserId());
        svc.updateProgress(recordId, 3, 18, "元素单价");
        String meta = metadataOf(recordId);
        assertNotNull(meta, "metadata 应已写入进度");
        String compact = meta.replaceAll("\\s+", "");   // jsonb 列回读带空格，去空白后比对
        assertTrue(compact.contains("\"done\":3"), "进度 done=3，实际 metadata=" + meta);
        assertTrue(compact.contains("\"total\":18"), "进度 total=18，实际 metadata=" + meta);
        assertTrue(meta.contains("元素单价"), "应含当前 Sheet 名，实际 metadata=" + meta);
    }
}
