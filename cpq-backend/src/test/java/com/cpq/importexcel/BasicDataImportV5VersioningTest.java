package com.cpq.importexcel;

import com.cpq.versioning.VersionedWriter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 #12+#13 VersionedWriter 版本化写入集成测试（≥11 用例）。
 *
 * <p>覆盖范围：
 *   T1:  首次插入 mat_process → v1 + 0 change_log（isFirstInsert=true）
 *   T2:  ACCEPT_NEW 修改 1 字段 → v2 + 1 change_log
 *   T3:  ACCEPT_NEW 修改 3 字段 → v2 + 3 change_log（同一新版本号）
 *   T4:  KEEP_OLD（caller 不调用 VersionedWriter）→ v1 仍 is_current=true, 0 change_log
 *   T5:  ACCEPT_NEW 但 oldValue == newValue → noChange=true, v1 不变, 0 change_log
 *   T6:  mat_fee 升版（fee_type + seq_no 三元组业务键）
 *   T7:  plating_fee 升版（四元组业务键）
 *   T8:  同事务异常回滚 → is_current 不变，change_log 无新条目
 *   T9:  mat_process (seq_no=1, sub_seq_no=1) 与 (seq_no=1, sub_seq_no=2) 独立升版互不影响
 *   T10: affectsCalculation=false 字段变化 → 仍写 change_log（落 affects_calculation=false）
 *   T11: change_source/import_record_id/note 三字段在 log 表正确落库
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicDataImportV5VersioningTest {

    @Inject
    VersionedWriter versionedWriter;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private static final UUID CUSTOMER_ID   = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID       = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID IMPORT_RECORD = UUID.fromString("52000000-0000-0000-0000-000000000003");

    private static final String PART_T1 = "VER-T1-001";
    private static final String PART_T2 = "VER-T2-001";
    private static final String PART_T3 = "VER-T3-001";
    private static final String PART_T4 = "VER-T4-001";
    private static final String PART_T5 = "VER-T5-001";
    private static final String PART_T6 = "VER-T6-001";
    private static final String PART_T7 = "VER-T7-001";
    private static final String PART_T8 = "VER-T8-001";
    private static final String PART_T9 = "VER-T9-001";
    private static final String PART_T10 = "VER-T10-001";
    private static final String PART_T11 = "VER-T11-001";

    @BeforeEach
    void setup() throws Exception {
        // 若有残留事务（上个测试失败未清理），先回滚
        try {
            if (utx.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                utx.rollback();
            }
        } catch (Exception ignored) {}

        utx.begin();
        em.joinTransaction();

        // 确保测试用户存在
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'ver-tester', 'Versioning Tester', 'ver@test.com', 'hash', 'SALES_MANAGER', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", USER_ID).executeUpdate();

        // 确保客户存在
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'Versioning Test Customer', 'VER-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", CUSTOMER_ID).executeUpdate();

        // 确保所有测试料号存在
        for (String pn : List.of(PART_T1, PART_T2, PART_T3, PART_T4, PART_T5,
                                  PART_T6, PART_T7, PART_T8, PART_T9, PART_T10, PART_T11)) {
            em.createNativeQuery(
                    "INSERT INTO mat_part(part_no, part_name, status_code, created_at, updated_at) " +
                    "VALUES (:pn, :pn, 'Y', NOW(), NOW()) ON CONFLICT (part_no) DO NOTHING")
                    .setParameter("pn", pn).executeUpdate();
        }

        // 清理客户级数据
        em.createNativeQuery("DELETE FROM basic_data_change_log WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM plating_fee WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_fee WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_process WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_ID).executeUpdate();

        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T1: 首次插入 mat_process → v1 + isFirstInsert=true + 0 change_log
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    void T1_firstInsert_matProcess_v1_noChangeLog() throws Exception {
        VersionedWriter.WriteResult result = writeMatProcess(PART_T1, 1, null,
                new BigDecimal("100.00"), "CNY");

        assertTrue(result.isFirstInsert(), "首次插入应为 isFirstInsert=true");
        assertFalse(result.noChange(), "首次插入不应为 noChange");
        assertEquals(1, result.newVersion(), "首次插入版本号应为 1");
        assertEquals(0, result.changeLogEntriesWritten(), "首次插入不写 change_log");
        assertNotNull(result.newRowId(), "应返回新行 ID");

        // 验证数据库
        utx.begin();
        em.joinTransaction();
        Number cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn AND is_current = true AND version = 1")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T1).getSingleResult();
        assertEquals(1, cnt.intValue(), "DB 中应有 1 条 is_current=true v1 记录");
        Number logCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log WHERE customer_id = :cid AND hf_part_no = :pn")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T1).getSingleResult();
        assertEquals(0, logCnt.intValue(), "首次插入不写 change_log");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T2: ACCEPT_NEW 修改 1 字段 → v2 + 1 change_log
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    void T2_acceptNew_1field_v2_1ChangeLog() throws Exception {
        // 建立 v1
        writeMatProcess(PART_T2, 1, null, new BigDecimal("100.00"), "CNY");

        // 修改 unit_price → v2
        VersionedWriter.WriteResult result = writeMatProcess(PART_T2, 1, null,
                new BigDecimal("150.00"), "CNY");

        assertFalse(result.isFirstInsert());
        assertFalse(result.noChange(), "有字段变化应非 noChange");
        assertEquals(2, result.newVersion(), "应升至 v2");
        assertEquals(1, result.changeLogEntriesWritten(), "修改 1 字段写 1 条 change_log");

        utx.begin();
        em.joinTransaction();
        // v1 应 is_current=false
        Number v1Current = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn AND version = 1 AND is_current = false")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T2).getSingleResult();
        assertEquals(1, v1Current.intValue(), "v1 应标记 is_current=false");
        // v2 应 is_current=true
        Number v2Current = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn AND version = 2 AND is_current = true")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T2).getSingleResult();
        assertEquals(1, v2Current.intValue(), "v2 应 is_current=true");
        // change_log 1 条
        Number logCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log WHERE customer_id = :cid AND hf_part_no = :pn")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T2).getSingleResult();
        assertEquals(1, logCnt.intValue());
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T3: ACCEPT_NEW 修改 3 字段 → v2 + 3 change_log（同一新版本号）
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    void T3_acceptNew_3fields_v2_3ChangeLogs() throws Exception {
        // 建立 v1
        writeMatProcess(PART_T3, 1, null, new BigDecimal("100.00"), "CNY");

        // 修改 unit_price + currency + price_unit 三字段
        VersionedWriter.WriteResult result;
        utx.begin();
        em.joinTransaction();
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("seq_no", 1);
        bk.put("sub_seq_no", null);
        Map<String, Object> fv = buildMatProcessFields(new BigDecimal("200.00"), "USD", "KG");
        result = versionedWriter.writeWithVersioning(new VersionedWriter.WriteRequest(
                "mat_process", CUSTOMER_ID, PART_T3, bk, fv,
                USER_ID, IMPORT_RECORD, "V5_IMPORT", "three-field test"));
        utx.commit();

        assertEquals(2, result.newVersion(), "应升至 v2");
        assertEquals(3, result.changeLogEntriesWritten(), "修改 3 字段写 3 条 change_log");

        utx.begin();
        em.joinTransaction();
        Number logCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log WHERE customer_id = :cid AND hf_part_no = :pn AND version_after = 2")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T3).getSingleResult();
        assertEquals(3, logCnt.intValue(), "3 条 log 都应标注 version_after=2");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T4: KEEP_OLD（调用方跳过，不调用 VersionedWriter）→ v1 仍 is_current=true, 0 change_log
    //
    // 设计：KEEP_OLD 时 writePhysicalTables 中 continue 跳过，不调 VersionedWriter。
    // 本测试验证：插入 v1 后不再调用 VersionedWriter，v1 保持 is_current=true。
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(4)
    void T4_keepOld_noVersionChange_noChangeLog() throws Exception {
        // 插入 v1
        writeMatProcess(PART_T4, 1, null, new BigDecimal("100.00"), "CNY");

        // 模拟 KEEP_OLD：不调用 VersionedWriter（caller 直接 continue）
        // 验证 v1 保持 is_current=true
        utx.begin();
        em.joinTransaction();
        Number cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn AND is_current = true AND version = 1")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T4).getSingleResult();
        assertEquals(1, cnt.intValue(), "KEEP_OLD 场景 v1 应仍 is_current=true");
        Number logCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log WHERE customer_id = :cid AND hf_part_no = :pn")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T4).getSingleResult();
        assertEquals(0, logCnt.intValue(), "KEEP_OLD 无 change_log");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T5: ACCEPT_NEW 但 oldValue == newValue（其实没变）→ noChange=true, v1 不变, 0 change_log
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(5)
    void T5_noActualChange_noChange_noLog() throws Exception {
        // 插入 v1
        writeMatProcess(PART_T5, 1, null, new BigDecimal("100.00"), "CNY");

        // 再次写入完全相同的值
        VersionedWriter.WriteResult result = writeMatProcess(PART_T5, 1, null,
                new BigDecimal("100.00"), "CNY");

        assertTrue(result.noChange(), "字段值未变应返回 noChange=true");
        assertFalse(result.isFirstInsert());
        assertEquals(1, result.newVersion(), "版本号保持 1");
        assertEquals(0, result.changeLogEntriesWritten(), "无变化不写 change_log");

        // 验证数据库中只有 1 条 is_current=true 且版本仍为 1
        utx.begin();
        em.joinTransaction();
        Number cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T5).getSingleResult();
        assertEquals(1, cnt.intValue(), "无变化不应新增行");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T6: mat_fee 升版（fee_type + seq_no 三元组业务键）
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(6)
    void T6_matFee_versioning() throws Exception {
        // 首次插入 mat_fee v1
        VersionedWriter.WriteResult r1 = writeMatFee(PART_T6, "INCOMING_FIXED", 1,
                new BigDecimal("50.00"));
        assertTrue(r1.isFirstInsert(), "mat_fee 首次插入应为 isFirstInsert");
        assertEquals(1, r1.newVersion());
        assertEquals(0, r1.changeLogEntriesWritten());

        // 修改 fee_value → v2
        VersionedWriter.WriteResult r2 = writeMatFee(PART_T6, "INCOMING_FIXED", 1,
                new BigDecimal("60.00"));
        assertFalse(r2.isFirstInsert());
        assertFalse(r2.noChange());
        assertEquals(2, r2.newVersion(), "mat_fee 应升至 v2");
        assertTrue(r2.changeLogEntriesWritten() >= 1, "至少 1 条 change_log");

        // 验证只有 v2 is_current=true
        utx.begin();
        em.joinTransaction();
        Number v1c = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_fee WHERE customer_id = :cid AND hf_part_no = :pn " +
                "AND fee_type = 'INCOMING_FIXED' AND seq_no = 1 AND version = 1 AND is_current = false")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T6).getSingleResult();
        assertEquals(1, v1c.intValue(), "mat_fee v1 应 is_current=false");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T7: plating_fee 升版（四元组业务键）
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(7)
    void T7_platingFee_versioning() throws Exception {
        // 首次插入
        VersionedWriter.WriteResult r1 = writePlatingFee(PART_T7, "PP-001", "v1",
                new BigDecimal("20.00"), new BigDecimal("10.00"));
        assertTrue(r1.isFirstInsert());
        assertEquals(1, r1.newVersion());

        // 修改 plating_process_fee → v2
        VersionedWriter.WriteResult r2 = writePlatingFee(PART_T7, "PP-001", "v1",
                new BigDecimal("25.00"), new BigDecimal("10.00"));
        assertFalse(r2.isFirstInsert());
        assertEquals(2, r2.newVersion(), "plating_fee 应升至 v2");
        assertTrue(r2.changeLogEntriesWritten() >= 1);

        utx.begin();
        em.joinTransaction();
        Number curr = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM plating_fee WHERE customer_id = :cid AND hf_part_no = :pn " +
                "AND plating_plan_code = 'PP-001' AND plan_version = 'v1' AND is_current = true")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T7).getSingleResult();
        assertEquals(1, curr.intValue(), "plating_fee 只应有 1 条 is_current=true");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T8: 同事务异常回滚 → is_current 不变，change_log 无新条目
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(8)
    void T8_transactionRollback_noSideEffect() throws Exception {
        // 先成功插入 v1
        writeMatProcess(PART_T8, 1, null, new BigDecimal("100.00"), "CNY");

        // 在事务中执行 VersionedWriter 后强制回滚
        utx.begin();
        em.joinTransaction();
        try {
            writeMatProcessInTx(PART_T8, 1, null, new BigDecimal("999.00"), "CNY");
            // 强制回滚
            utx.setRollbackOnly();
        } finally {
            try { utx.rollback(); } catch (Exception ignored) {}
        }

        // 验证状态未变
        utx.begin();
        em.joinTransaction();
        Number v1cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn AND version = 1 AND is_current = true")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T8).getSingleResult();
        assertEquals(1, v1cnt.intValue(), "回滚后 v1 应仍 is_current=true");
        Number v2cnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn AND version = 2")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T8).getSingleResult();
        assertEquals(0, v2cnt.intValue(), "回滚后不应有 v2 行");
        Number logCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log WHERE customer_id = :cid AND hf_part_no = :pn")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T8).getSingleResult();
        assertEquals(0, logCnt.intValue(), "回滚后不应有 change_log");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T9: mat_process (seq_no=1,sub_seq_no=1) 与 (seq_no=1,sub_seq_no=2) 独立升版互不影响
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(9)
    void T9_independentBusinessKeys_noInterference() throws Exception {
        // 插入两个独立业务键的 v1
        writeMatProcess(PART_T9, 1, 1, new BigDecimal("100.00"), "CNY");
        writeMatProcess(PART_T9, 1, 2, new BigDecimal("200.00"), "CNY");

        // 只升级 sub_seq_no=1
        VersionedWriter.WriteResult result = writeMatProcess(PART_T9, 1, 1,
                new BigDecimal("150.00"), "CNY");
        assertEquals(2, result.newVersion(), "(1,1) 应升至 v2");

        // sub_seq_no=2 应仍为 v1 is_current=true
        utx.begin();
        em.joinTransaction();
        Number ssn2v1 = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM mat_process WHERE customer_id = :cid AND hf_part_no = :pn " +
                "AND seq_no = 1 AND COALESCE(sub_seq_no,-1) = 2 AND version = 1 AND is_current = true")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T9).getSingleResult();
        assertEquals(1, ssn2v1.intValue(), "(1,2) 应仍 v1 is_current=true，不受 (1,1) 升版影响");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T10: affectsCalculation=false 字段变化 → 仍写 change_log（落 affects_calculation=false）
    //
    // mat_process 中 component_name / supplier_name 等为 affectsCalculation=false。
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(10)
    void T10_nonCalcField_changeLog_affectsCalcFalse() throws Exception {
        // 插入 v1，component_name="OldName"
        utx.begin();
        em.joinTransaction();
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("seq_no", 1);
        bk.put("sub_seq_no", null);
        Map<String, Object> fv = buildMatProcessFields(new BigDecimal("100.00"), "CNY", "PCS");
        fv.put("component_name", "OldName");
        versionedWriter.writeWithVersioning(new VersionedWriter.WriteRequest(
                "mat_process", CUSTOMER_ID, PART_T10, bk, fv,
                USER_ID, IMPORT_RECORD, "V5_IMPORT", null));
        utx.commit();

        // 修改 component_name（affectsCalculation=false 字段）
        utx.begin();
        em.joinTransaction();
        fv = buildMatProcessFields(new BigDecimal("100.00"), "CNY", "PCS");
        fv.put("component_name", "NewName");
        VersionedWriter.WriteResult result = versionedWriter.writeWithVersioning(
                new VersionedWriter.WriteRequest(
                        "mat_process", CUSTOMER_ID, PART_T10, bk, fv,
                        USER_ID, IMPORT_RECORD, "V5_IMPORT", null));
        utx.commit();

        assertTrue(result.changeLogEntriesWritten() >= 1, "非计算字段变化也应写 change_log");

        utx.begin();
        em.joinTransaction();
        Number acFalseLog = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log " +
                "WHERE customer_id = :cid AND hf_part_no = :pn AND field_name = 'component_name' AND affects_calculation = false")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T10).getSingleResult();
        assertEquals(1, acFalseLog.intValue(), "change_log 应落 affects_calculation=false");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // T11: change_source/import_record_id/note 三字段在 log 表正确落库
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @Order(11)
    void T11_changeSourceImportRecordNote_correctInLog() throws Exception {
        // 插入 v1
        writeMatProcess(PART_T11, 1, null, new BigDecimal("100.00"), "CNY");

        // 修改，带 change_source + importRecordId + note
        utx.begin();
        em.joinTransaction();
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("seq_no", 1);
        bk.put("sub_seq_no", null);
        Map<String, Object> fv = buildMatProcessFields(new BigDecimal("888.00"), "USD", "PCS");
        UUID customImportId = UUID.randomUUID();
        versionedWriter.writeWithVersioning(new VersionedWriter.WriteRequest(
                "mat_process", CUSTOMER_ID, PART_T11, bk, fv,
                USER_ID, customImportId, "V5_IMPORT", "T11 note"));
        utx.commit();

        utx.begin();
        em.joinTransaction();
        // 验证 change_source
        Number srcCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log " +
                "WHERE customer_id = :cid AND hf_part_no = :pn AND change_source = 'V5_IMPORT'")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T11).getSingleResult();
        assertTrue(srcCnt.intValue() >= 1, "change_source 应为 V5_IMPORT");

        // 验证 import_record_id
        Number ridCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log " +
                "WHERE customer_id = :cid AND hf_part_no = :pn AND import_record_id = :rid")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T11)
                .setParameter("rid", customImportId).getSingleResult();
        assertTrue(ridCnt.intValue() >= 1, "import_record_id 应正确落库");

        // 验证 note
        Number noteCnt = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM basic_data_change_log " +
                "WHERE customer_id = :cid AND hf_part_no = :pn AND note = 'T11 note'")
                .setParameter("cid", CUSTOMER_ID).setParameter("pn", PART_T11).getSingleResult();
        assertTrue(noteCnt.intValue() >= 1, "note 应正确落库");
        utx.commit();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────────────────────────────────

    /** 在新事务中写入 mat_process */
    private VersionedWriter.WriteResult writeMatProcess(String hfPartNo, int seqNo,
                                                         Integer subSeqNo,
                                                         BigDecimal unitPrice,
                                                         String currency) throws Exception {
        utx.begin();
        em.joinTransaction();
        try {
            VersionedWriter.WriteResult result = writeMatProcessInTx(hfPartNo, seqNo, subSeqNo, unitPrice, currency);
            utx.commit();
            return result;
        } catch (Exception e) {
            try { utx.rollback(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /** 在当前事务中写入 mat_process（供回滚测试用） */
    private VersionedWriter.WriteResult writeMatProcessInTx(String hfPartNo, int seqNo,
                                                              Integer subSeqNo,
                                                              BigDecimal unitPrice,
                                                              String currency) {
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("seq_no", seqNo);
        bk.put("sub_seq_no", subSeqNo);
        Map<String, Object> fv = buildMatProcessFields(unitPrice, currency, "PCS");
        return versionedWriter.writeWithVersioning(new VersionedWriter.WriteRequest(
                "mat_process", CUSTOMER_ID, hfPartNo, bk, fv,
                USER_ID, IMPORT_RECORD, "V5_IMPORT", null));
    }

    /** 在新事务中写入 mat_fee */
    private VersionedWriter.WriteResult writeMatFee(String hfPartNo, String feeType,
                                                     int seqNo, BigDecimal feeValue) throws Exception {
        utx.begin();
        em.joinTransaction();
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("fee_type", feeType);
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("seq_no", seqNo);
        fv.put("fee_value", feeValue);
        fv.put("fee_ratio", null);
        fv.put("currency", "CNY");
        fv.put("price_unit", "PCS");
        fv.put("dim_input_material_no", null);
        fv.put("dim_input_material_name", null);
        fv.put("dim_element_name", null);
        fv.put("dim_assembly_process", null);
        fv.put("dim_sub_seq_no", null);
        fv.put("price_floating", null);
        fv.put("settlement_rise_ratio", null);
        fv.put("fixed_rise_value", null);
        fv.put("rise_currency", null);
        fv.put("rise_unit", null);
        fv.put("reject_rate", null);
        fv.put("status", "ACTIVE");
        try {
            VersionedWriter.WriteResult result = versionedWriter.writeWithVersioning(
                    new VersionedWriter.WriteRequest(
                            "mat_fee", CUSTOMER_ID, hfPartNo, bk, fv,
                            USER_ID, IMPORT_RECORD, "V5_IMPORT", null));
            utx.commit();
            return result;
        } catch (Exception e) {
            try { utx.rollback(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /** 在新事务中写入 plating_fee */
    private VersionedWriter.WriteResult writePlatingFee(String hfPartNo,
                                                          String planCode, String planVersion,
                                                          BigDecimal processFee,
                                                          BigDecimal materialFee) throws Exception {
        utx.begin();
        em.joinTransaction();
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("plating_plan_code", planCode);
        bk.put("plan_version", planVersion);
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("plating_process_fee", processFee);
        fv.put("plating_material_fee", materialFee);
        fv.put("currency", "CNY");
        fv.put("price_unit", "M2");
        fv.put("defect_rate", null);
        fv.put("status", "ACTIVE");
        try {
            VersionedWriter.WriteResult result = versionedWriter.writeWithVersioning(
                    new VersionedWriter.WriteRequest(
                            "plating_fee", CUSTOMER_ID, hfPartNo, bk, fv,
                            USER_ID, IMPORT_RECORD, "V5_IMPORT", null));
            utx.commit();
            return result;
        } catch (Exception e) {
            try { utx.rollback(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /** 构建 mat_process 字段值 Map */
    private Map<String, Object> buildMatProcessFields(BigDecimal unitPrice, String currency,
                                                       String priceUnit) {
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("process_code", "P001");
        fv.put("assembly_process", "冲压");
        fv.put("component_part_no", "COMP-001");
        fv.put("component_name", "组成件名称");
        fv.put("supplier_code", "SUP-001");
        fv.put("supplier_name", "供应商名称");
        fv.put("quantity", new BigDecimal("1.00"));
        fv.put("quantity_unit", "PCS");
        fv.put("unit_price", unitPrice);
        fv.put("freight", null);
        fv.put("currency", currency);
        fv.put("price_unit", priceUnit);
        fv.put("status", "ACTIVE");
        return fv;
    }
}
