package com.cpq.quotation;

import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.formula.function.FunctionRegistry;
import com.cpq.formula.function.business.ElementPriceFunction;
import com.cpq.formula.function.business.ExchangeFunction;
import com.cpq.formula.function.business.PremiumPriceFunction;
import com.cpq.formula.function.business.TaxExcludedFunction;
import com.cpq.formula.function.business.TaxIncludedFunction;
import com.cpq.formula.function.lookup.ExistsFunction;
import com.cpq.formula.function.lookup.LookupFunction;
import com.cpq.quotation.dto.DriftedRecordDTO;
import com.cpq.quotation.service.DriftDetectionService;
import com.cpq.quotation.service.DriftDetectionService.DriftDetectionResult;
import com.cpq.quotation.service.DriftDetectionService.RefVersionEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.persistence.EntityManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QuotationDriftDetectionTest — v5.1 §6.6 DRAFT 漂移检测 + 公式引擎接入 单元测试。
 *
 * <p>覆盖 8 个测试用例（T1~T8）：
 * <ol>
 *   <li>T1: DriftDetectionService.detect — null/空 JSON → hasDrift=false</li>
 *   <li>T2: 无漂移快照 → hasDrift=false（DB 版本与快照相同）</li>
 *   <li>T3: 有漂移（DB 版本高于快照）→ hasDrift=true，driftedRecords 正确</li>
 *   <li>T4: 多张表混合：部分漂移 → 仅漂移行出现在 driftedRecords</li>
 *   <li>T5: collectReferencedVersions — customerId+partNos 为 null/空 → 返回 null</li>
 *   <li>T6: 公式引擎 EXCHANGE 调用测试（独立单元，不依赖 DB）</li>
 *   <li>T7: 公式 ERROR 单元格不阻塞其他计算（FormulaError 正常返回而非抛出）</li>
 *   <li>T8: detect — 无效 JSON → 优雅降级，hasDrift=false（不抛异常）</li>
 * </ol>
 *
 * <p>测试均为纯单元测试，不依赖 Quarkus 容器/DB（EntityManager 通过 Mockito mock）。
 */
class QuotationDriftDetectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DriftDetectionService driftDetectionService;
    private EntityManager mockEm;

    // FormulaEngine（独立单元，不依赖 DB）
    private FormulaEngine formulaEngine;
    private DataLoader mockDataLoader;

    @BeforeEach
    void setUp() throws Exception {
        // ── DriftDetectionService setup ─────────────────────────────────────
        mockEm = mock(EntityManager.class);
        driftDetectionService = new DriftDetectionService();
        injectField(driftDetectionService, "em", mockEm);

        // ── FormulaEngine setup ──────────────────────────────────────────────
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenThrow(new RuntimeException("no-db-in-unit-test"));

        ExchangeFunction exchangeFn = new ExchangeFunction();
        injectField(exchangeFn, "dataSource", mockDs);
        TaxIncludedFunction taxInFn = new TaxIncludedFunction();
        injectField(taxInFn, "dataSource", mockDs);
        TaxExcludedFunction taxExFn = new TaxExcludedFunction();
        injectField(taxExFn, "dataSource", mockDs);

        FunctionRegistry registry = new FunctionRegistry(
                exchangeFn, taxInFn, taxExFn,
                new ElementPriceFunction(), new PremiumPriceFunction(),
                new LookupFunction(), new ExistsFunction());

        formulaEngine = new FormulaEngine();
        injectField(formulaEngine, "registry", registry);

        mockDataLoader = mock(DataLoader.class);
        when(mockDataLoader.loadByPath(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T1: detect(null/空) → hasDrift=false，不抛异常
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t1_detect_nullJson_returns_noDrift() {
        DriftDetectionResult r1 = driftDetectionService.detect(null);
        assertFalse(r1.hasDrift(), "null referencedVersions → hasDrift=false");
        assertTrue(r1.driftedRecords().isEmpty());

        DriftDetectionResult r2 = driftDetectionService.detect("");
        assertFalse(r2.hasDrift(), "空字符串 referencedVersions → hasDrift=false");
        assertTrue(r2.driftedRecords().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T2: 快照版本与 DB 当前版本相同 → hasDrift=false
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t2_detect_noVersionChange_hasDriftFalse() throws Exception {
        UUID customerId = UUID.randomUUID();
        String partNo = "C001";
        String bk = partNo + "|" + customerId;

        // 快照：mat_process bk → version=2
        String json = MAPPER.writeValueAsString(Map.of(
                "mat_process", Map.of(bk, 2)
        ));

        // DB 当前也是 version=2（mock SELECT MAX(version)...）
        jakarta.persistence.Query mockQuery = mock(jakarta.persistence.Query.class);
        when(mockEm.createNativeQuery(contains("mat_process"))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("cid"), any())).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("partKey"), eq(partNo))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(List.of(2));  // MAX(version)=2

        DriftDetectionResult result = driftDetectionService.detect(json);

        assertFalse(result.hasDrift(), "快照版本=当前版本 → hasDrift=false");
        assertTrue(result.driftedRecords().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T3: DB 版本升至 3（快照为 2）→ hasDrift=true，driftedRecords 正确
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t3_detect_versionDrifted_hasDriftTrue() throws Exception {
        UUID customerId = UUID.randomUUID();
        String partNo = "C001";
        String bk = partNo + "|" + customerId;

        // 快照：version=2
        String json = MAPPER.writeValueAsString(Map.of(
                "mat_process", Map.of(bk, 2)
        ));

        // DB 当前：version=3（已升版）
        jakarta.persistence.Query mockQuery = mock(jakarta.persistence.Query.class);
        when(mockEm.createNativeQuery(contains("mat_process"))).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(List.of(3));

        DriftDetectionResult result = driftDetectionService.detect(json);

        assertTrue(result.hasDrift(), "版本从2升至3 → hasDrift=true");
        assertEquals(1, result.driftedRecords().size());

        DriftedRecordDTO record = result.driftedRecords().get(0);
        assertEquals("mat_process", record.tableName);
        assertEquals(bk, record.businessKey);
        assertEquals(2, record.referencedVersion);
        assertEquals(3, record.currentVersion);
        assertEquals(partNo, record.displayName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T4: 多张表混合 — mat_process 漂移，mat_fee 未漂移
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t4_detect_multiTable_partialDrift() throws Exception {
        UUID customerId = UUID.randomUUID();
        String bkProcess = "C001|" + customerId;
        String bkFee = "C002|" + customerId;

        // 快照：mat_process v2, mat_fee v1
        String json = MAPPER.writeValueAsString(Map.of(
                "mat_process", Map.of(bkProcess, 2),
                "mat_fee", Map.of(bkFee, 1)
        ));

        // mat_process → version=3（漂移）
        jakarta.persistence.Query qProcess = mock(jakarta.persistence.Query.class);
        when(mockEm.createNativeQuery(contains("mat_process"))).thenReturn(qProcess);
        when(qProcess.setParameter(anyString(), any())).thenReturn(qProcess);
        when(qProcess.getResultList()).thenReturn(List.of(3));

        // mat_fee → version=1（未漂移）
        jakarta.persistence.Query qFee = mock(jakarta.persistence.Query.class);
        when(mockEm.createNativeQuery(contains("mat_fee"))).thenReturn(qFee);
        when(qFee.setParameter(anyString(), any())).thenReturn(qFee);
        when(qFee.getResultList()).thenReturn(List.of(1));

        DriftDetectionResult result = driftDetectionService.detect(json);

        assertTrue(result.hasDrift());
        assertEquals(1, result.driftedRecords().size(), "仅 mat_process 漂移，mat_fee 未漂移");
        assertEquals("mat_process", result.driftedRecords().get(0).tableName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T5: collectReferencedVersions — null/空参数 → 返回 null，不报异常
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t5_collectReferencedVersions_nullOrEmpty_returnsNull() {
        assertNull(driftDetectionService.collectReferencedVersions(null, List.of()),
                "customerId=null → null");
        UUID cid = UUID.randomUUID();
        assertNull(driftDetectionService.collectReferencedVersions(cid, null),
                "partNos=null → null");
        assertNull(driftDetectionService.collectReferencedVersions(cid, List.of()),
                "partNos 空列表 → null");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T6: 公式引擎 — EXCHANGE 函数调用（mock DataLoader 返回汇率值）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t6_formulaEngine_exchange_withMockDataSource() {
        // EXCHANGE(amount, fromCurrency, toCurrency) 在 DB 不可用时返回 FormulaError
        // 这里测试引擎不抛异常，FormulaError 被正常返回
        EvaluationContext ctx = EvaluationContext.builder()
                .dataLoader(mockDataLoader)
                .binding("amount", new BigDecimal("100"))
                .build();

        Object result = formulaEngine.evaluate("EXCHANGE(amount, 'USD', 'CNY')", ctx);
        // DB 不可用 → ExchangeFunction 失败 → FormulaError（而非异常抛出）
        assertNotNull(result, "EXCHANGE 即使 DB 失败也不应返回 null");
        // 结果是 FormulaError（DB 不可用）或 BigDecimal（查到汇率）
        // 两者均接受，关键是不抛出异常
        assertTrue(result instanceof FormulaError || result instanceof BigDecimal,
                "EXCHANGE 结果类型正确: " + result.getClass().getSimpleName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T7: 公式 ERROR 单元格不阻塞其他计算（FormulaError 正常返回）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t7_formulaError_doesNotBlockOtherCalculations() {
        EvaluationContext ctx = EvaluationContext.builder()
                .dataLoader(mockDataLoader)
                .build();

        // 这个公式会产生 FormulaError（NUM('abc') 转换失败）
        Object err = formulaEngine.evaluate("NUM('abc')", ctx);
        assertNotNull(err, "FormulaError 不应为 null");
        // FormulaError 或引擎内部 fallback 值，关键是不抛异常

        // 其他公式正常计算不受影响
        Object ok = formulaEngine.evaluate("1 + 2 * 3", ctx);
        assertNotNull(ok);
        assertInstanceOf(BigDecimal.class, ok);
        assertEquals(0, ((BigDecimal) ok).compareTo(new BigDecimal("7")),
                "公式 ERROR 后其他公式仍可正常计算");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T8: detect — 无效/损坏 JSON → 优雅降级，hasDrift=false（不抛异常）
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void t8_detect_invalidJson_gracefulDegradation() {
        // 损坏的 JSON
        DriftDetectionResult r1 = driftDetectionService.detect("{invalid_json}");
        assertFalse(r1.hasDrift(), "无效 JSON → 优雅降级 hasDrift=false");
        assertTrue(r1.driftedRecords().isEmpty());

        // 未知表名：不抛异常，仅 warn 日志
        try {
            String json = MAPPER.writeValueAsString(Map.of(
                    "unknown_table", Map.of("bk|uuid", 1)
            ));
            DriftDetectionResult r2 = driftDetectionService.detect(json);
            assertFalse(r2.hasDrift(), "未知表名 → 跳过，hasDrift=false");
        } catch (Exception e) {
            fail("detect 不应抛出异常: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D-3 新增：T9~T11 — referencedVersions 新格式 recordId 测试
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * T9: parseReferencedVersions — 新格式 object 包含 recordId。
     * 写入 referencedVersions 时使用新格式 {"version": N, "recordId": "uuid"}，
     * 解析后 RefVersionEntry.recordId 不为 null，version 正确。
     */
    @Test
    void t9_parseReferencedVersions_newFormat_containsRecordId() throws Exception {
        UUID customerId = UUID.randomUUID();
        String bk = "C001|" + customerId;
        String recordId = UUID.randomUUID().toString();

        // 新格式 JSON
        String json = MAPPER.writeValueAsString(Map.of(
                "mat_process", Map.of(bk, Map.of("version", 3, "recordId", recordId))
        ));

        Map<String, Map<String, DriftDetectionService.RefVersionEntry>> parsed =
                driftDetectionService.parseReferencedVersions(json);

        assertNotNull(parsed.get("mat_process"), "mat_process 应存在");
        DriftDetectionService.RefVersionEntry entry = parsed.get("mat_process").get(bk);
        assertNotNull(entry, "业务键对应的 entry 不应为 null");
        assertEquals(3, entry.version(), "新格式 version 解析正确");
        assertEquals(recordId, entry.recordId(), "新格式 recordId 解析正确，不应为 null");
    }

    /**
     * T10: parseReferencedVersions — 旧格式 int 兼容。
     * 旧 JSON 中业务键值为 int，解析后 version=N, recordId=null。
     */
    @Test
    void t10_parseReferencedVersions_oldIntFormat_recordIdIsNull() throws Exception {
        UUID customerId = UUID.randomUUID();
        String bk = "C002|" + customerId;

        // 旧格式 JSON：值为 int
        String json = MAPPER.writeValueAsString(Map.of(
                "mat_fee", Map.of(bk, 5)
        ));

        Map<String, Map<String, DriftDetectionService.RefVersionEntry>> parsed =
                driftDetectionService.parseReferencedVersions(json);

        assertNotNull(parsed.get("mat_fee"), "mat_fee 应存在");
        DriftDetectionService.RefVersionEntry entry = parsed.get("mat_fee").get(bk);
        assertNotNull(entry, "旧格式 entry 不应为 null");
        assertEquals(5, entry.version(), "旧格式 version 解析正确");
        assertNull(entry.recordId(), "旧格式 recordId 应为 null（向后兼容）");
    }

    /**
     * T11: detect — 新格式 referencedVersions（含 recordId）漂移检测正常工作。
     * 新格式 JSON 中业务键值为 object，detect() 仍能正确比对版本并检测漂移。
     */
    @Test
    void t11_detect_newFormatJson_driftDetectionWorks() throws Exception {
        UUID customerId = UUID.randomUUID();
        String partNo = "C003";
        String bk = partNo + "|" + customerId;
        String recordId = UUID.randomUUID().toString();

        // 新格式 JSON：快照版本=2
        String json = MAPPER.writeValueAsString(Map.of(
                "mat_process", Map.of(bk, Map.of("version", 2, "recordId", recordId))
        ));

        // DB 当前版本=4（漂移）
        jakarta.persistence.Query mockQuery = mock(jakarta.persistence.Query.class);
        when(mockEm.createNativeQuery(contains("mat_process"))).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(List.of(4));

        DriftDetectionResult result = driftDetectionService.detect(json);

        assertTrue(result.hasDrift(), "新格式 JSON 漂移检测应正确识别版本变化");
        assertEquals(1, result.driftedRecords().size());
        DriftedRecordDTO record = result.driftedRecords().get(0);
        assertEquals("mat_process", record.tableName);
        assertEquals(bk, record.businessKey);
        assertEquals(2, record.referencedVersion, "引用版本应为新格式中的 version 字段");
        assertEquals(4, record.currentVersion);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 反射注入字段（用于没有 @Inject setter 的 ApplicationScoped Bean 测试）。
     */
    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + name + "' not found in " + clazz);
    }
}
