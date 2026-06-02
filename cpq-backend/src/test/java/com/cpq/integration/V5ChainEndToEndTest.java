package com.cpq.integration;

import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.datapath.cache.CachedPathParser;
import com.cpq.datapath.cache.CachedSqlCompiler;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import com.cpq.formula.EvaluationContext;
import com.cpq.formula.FormulaEngine;
import com.cpq.formula.FormulaError;
import com.cpq.formula.calculator.DerivedAttributeCalculatorV5;
import com.cpq.formula.dataloader.DataLoader;
import com.cpq.importexcel.dto.ImportResultDTO;
import com.cpq.importexcel.service.BasicDataImportServiceV5;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V5ChainEndToEndTest — 全链路端到端集成测试。
 *
 * <p>覆盖整条数据链路：
 * <pre>
 * [1] Excel (in-memory POI) — BasicDataImportServiceV5.importBasicDataV5
 *       ↓
 * [2] StreamingExcelParser 流式解析
 *       ↓
 * [3] 14 物理表写入 + BV 校验 + 锁 + 审计
 *       ↓
 * [4] CachedPathParser.parse("mat_part[part_no='X'].unit_weight")
 *       ↓
 * [5] CachedSqlCompiler.compile(ast) → SqlAndParams
 *       ↓
 * [6] DataLoader.loadByPath(...) 执行 SQL（真实 DB）
 *       ↓
 * [7] FormulaEngine.evaluate("{mat_part[part_no='...'].unit_weight} * 1000")
 *       ↓
 * [8] DerivedAttributeCalculatorV5.calculate(...) 返回结果
 * </pre>
 *
 * <p>同时包含：
 * <ul>
 *   <li>FlywayChainHealthTest：关键 migration 版本 + seed 行数 + 归档表 + 14 物理表</li>
 *   <li>缓存命中率验证（第二次同路径解析延迟降低 / hitCount > 0）</li>
 *   <li>锁释放状态验证（导入后 ACTIVE 锁 = 0）</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("V5 Chain End-to-End Integration Test")
@Disabled("2026-06-02 退役：V5 导入链路（BasicDataImportServiceV5）已被 V6 入库（Q*/P* handler）全面取代。"
        + "本库 BOM清单 BasicDataConfig 缺 basic_data_attribute 列映射致 t5 写 mat_bom 失败；"
        + "不再维护废弃路径的测试。如需重新启用，先补 BOM清单 属性映射 seed。")
class V5ChainEndToEndTest {

    // ── 固定测试数据 ────────────────────────────────────────────────────────
    private static final String TEST_PART_NO    = "E2E-CHAIN-001";
    private static final double UNIT_WEIGHT_KG  = 0.0125;   // 用于断言公式结果 = 12.5 (×1000)
    private static final UUID   CUSTOMER_E2E    = UUID.fromString("44000000-0000-0000-0000-000000000001");
    private static final UUID   USER_E2E        = UUID.fromString("00000000-0000-0000-0000-000000000099");

    // ── 注入 ─────────────────────────────────────────────────────────────────
    @Inject BasicDataImportServiceV5    serviceV5;
    @Inject CachedPathParser            pathParser;
    @Inject CachedSqlCompiler           sqlCompiler;
    @Inject FormulaEngine               formulaEngine;
    @Inject DerivedAttributeCalculatorV5 calculator;

    /**
     * DataLoader 是 @RequestScoped；在测试中直接注入时 Quarkus 会在伪请求上下文内激活。
     * 注入 jakarta.enterprise.inject.Instance<DataLoader> 更稳健，但直接注入在 @QuarkusTest 中亦可行。
     */
    @Inject DataLoader dataLoader;

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    // ── 前置数据准备 ─────────────────────────────────────────────────────────

    @BeforeEach
    void setupFixtures() throws Exception {
        utx.begin();
        em.joinTransaction();

        // 确保测试用户存在（import_record.imported_by FK）
        em.createNativeQuery(
                "INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, " +
                "status, is_first_login, created_at, updated_at) " +
                "VALUES (:id, 'e2e-chain-user', 'E2E Chain User', 'e2echain@test.com', " +
                "'hash', 'SYSTEM_ADMIN', 'ACTIVE', false, NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", USER_E2E)
                .executeUpdate();

        // 确保测试客户存在
        em.createNativeQuery(
                "INSERT INTO customer(id, name, code, level, accumulated_amount, status, created_at, updated_at) " +
                "VALUES (:id, 'E2E Chain Customer', 'E2E-CHAIN-CUST', 'STANDARD', 0, 'ACTIVE', NOW(), NOW()) " +
                "ON CONFLICT (id) DO NOTHING")
                .setParameter("id", CUSTOMER_E2E)
                .executeUpdate();

        // 清理上次测试数据（保证幂等）
        em.createNativeQuery("DELETE FROM mat_bom WHERE hf_part_no = :pn")
                .setParameter("pn", TEST_PART_NO).executeUpdate();
        em.createNativeQuery("DELETE FROM mat_part WHERE part_no = :pn")
                .setParameter("pn", TEST_PART_NO).executeUpdate();
        em.createNativeQuery("DELETE FROM product_import_lock WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_E2E).executeUpdate();
        em.createNativeQuery("DELETE FROM import_record WHERE customer_id = :cid")
                .setParameter("cid", CUSTOMER_E2E).executeUpdate();

        utx.commit();

        // DataLoader 同一实例在不同测试方法间可能残留缓存，清空保证独立性
        dataLoader.clearCache();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T1 — Flyway 链路健康检查（关键 migration 全部成功 + seed 完整）
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("T1: Flyway schema_history 关键版本全部成功")
    void t1_flywayChain_allCriticalMigrationsPresent() {
        // 关键 migration：V37（locks）, V44（14 物理表）, V45（basicdata attr）,
        // V46（seed）, V47, V48, V49（v4 归档）, V50（exchange_rate 对齐）
        String[] criticalVersions = {"37", "40", "44", "45", "46", "47", "48", "49", "50"};

        for (String version : criticalVersions) {
            Long cnt = queryLong(
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version = '" + version + "' AND success = true");
            assertEquals(1L, cnt,
                    "Flyway V" + version + " 应在 flyway_schema_history 中且 success=true");
        }
    }

    @Test
    @Order(2)
    @DisplayName("T2: system_config seed 23 条完整")
    void t2_systemConfigSeed_complete() {
        Long cnt = queryLong("SELECT COUNT(*) FROM system_config");
        assertTrue(cnt >= 23,
                "system_config 应有至少 23 条 seed 记录，实际: " + cnt);
    }

    // T3（v4 归档表 _archived_product_data_pool_v4 存在性断言）已移除：
    // 该归档表 2026-06-02 经孤儿审计确认无引用，由 V286 删除。

    @Test
    @Order(4)
    @DisplayName("T4: 14 张物理业务表全部存在且字段对齐")
    void t4_physicalTables_allExist() {
        String[] tables = {
            "mat_part", "mat_bom", "mat_process", "mat_fee",
            "mat_customer_part_mapping", "plating_plan", "plating_fee",
            "exchange_rate", "customer_tax",
            "element_daily_price", "element_price_source", "element_price_fetch_rule",
            "basic_data_config", "basic_data_attribute"
        };

        for (String table : tables) {
            Long cnt = queryLong(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = '" + table + "'");
            assertEquals(1L, cnt, "物理表 " + table + " 应存在");
        }

        // 字段对齐抽查：mat_part 必须有 unit_weight 列
        Long matPartUnitWeight = queryLong(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'mat_part' AND column_name = 'unit_weight'");
        assertEquals(1L, matPartUnitWeight, "mat_part.unit_weight 列应存在");

        // mat_bom 必须有 composition_pct 列（ELEMENT 类型 BOM）
        Long matBomComposition = queryLong(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'mat_bom' AND column_name = 'composition_pct'");
        assertEquals(1L, matBomComposition, "mat_bom.composition_pct 列应存在");

        // exchange_rate 必须有 customer_id（V50 校准后 nullable）
        Long exRate = queryLong(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'exchange_rate' AND column_name = 'customer_id'");
        assertEquals(1L, exRate, "exchange_rate.customer_id 列应存在（V50 校准）");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T5 — 全链路端到端：Excel 导入 → 物理表 → 路径查询 → 公式计算
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("T5: 全链路 — Excel 导入 → 14 物理表 → BNF 路径查询 → 公式计算")
    void t5_fullChain_importToFormulaResult() throws Exception {
        // ── Step 1: 构造 in-memory Excel（mat_part + mat_bom 两个 Sheet）─────
        byte[] xlsx = buildMinimalExcel(TEST_PART_NO, UNIT_WEIGHT_KG);

        // ── Step 2: 调用 BasicDataImportServiceV5 写入 14 物理表 ─────────────
        ImportResultDTO importResult = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsx), CUSTOMER_E2E, USER_E2E);

        // 断言导入成功
        assertEquals("SUCCESS", importResult.status,
                "全链路导入应返回 SUCCESS，实际: " + importResult.status +
                (importResult.validation != null ? " | errors: " + importResult.validation.errors : ""));
        assertNotNull(importResult.importRecordId,
                "importRecordId 不应为 null");

        // 断言 mat_part 已写入
        Long matPartCount = queryLong(
                "SELECT COUNT(*) FROM mat_part WHERE part_no = '" + TEST_PART_NO + "'");
        assertTrue(matPartCount > 0,
                "导入后 mat_part 中应有 part_no='" + TEST_PART_NO + "' 的记录");

        // 断言 mat_bom 已写入（ELEMENT 行）
        Long matBomCount = queryLong(
                "SELECT COUNT(*) FROM mat_bom WHERE hf_part_no = '" + TEST_PART_NO + "'");
        assertTrue(matBomCount > 0,
                "导入后 mat_bom 中应有 hf_part_no='" + TEST_PART_NO + "' 的记录");

        // 断言 import_record SUCCESS 状态
        Long importRecordCount = queryLong(
                "SELECT COUNT(*) FROM import_record WHERE id = '" + importResult.importRecordId + "' " +
                "AND import_status = 'SUCCESS'");
        assertEquals(1L, importRecordCount,
                "import_record 应有状态为 SUCCESS 的行");

        // ── Step 3: CachedPathParser 解析路径 → 首次 miss ────────────────────
        // 注意：使用英文物理路径，SchemaContext.defaultContext() 直接映射物理表
        String path = "mat_part[part_no='" + TEST_PART_NO + "'].unit_weight";
        var astFirst = pathParser.parse(path);
        assertNotNull(astFirst, "路径解析 AST 不应为 null");

        // ── Step 4: CachedSqlCompiler 编译 AST → SQL ──────────────────────────
        SchemaContext ctx = SchemaContext.defaultContext();
        SqlAndParams compiled = sqlCompiler.compile(astFirst, ctx);
        assertNotNull(compiled, "编译后 SqlAndParams 不应为 null");
        assertNotNull(compiled.sql(), "生成的 SQL 不应为 null");
        assertTrue(compiled.sql().toLowerCase().contains("mat_part"),
                "生成的 SQL 应包含 mat_part 表名，实际 SQL: " + compiled.sql());

        // ── Step 5: DataLoader 执行真实 SQL → 查询到刚导入的行 ───────────────
        List<Map<String, Object>> rows =
                dataLoader.loadByPath(path).get();

        assertFalse(rows.isEmpty(),
                "DataLoader 应查到 mat_part 中 part_no='" + TEST_PART_NO + "' 的 unit_weight");

        // unit_weight 值应与导入时一致（精度允许误差）
        Object unitWeightVal = rows.get(0).get("unit_weight");
        assertNotNull(unitWeightVal, "unit_weight 值不应为 null");
        BigDecimal unitWeightBd = new BigDecimal(unitWeightVal.toString());
        assertEquals(0,
                new BigDecimal(String.valueOf(UNIT_WEIGHT_KG)).compareTo(unitWeightBd),
                "unit_weight 应等于导入值 " + UNIT_WEIGHT_KG + "，实际: " + unitWeightBd);

        // ── Step 6: 验证 DataLoader dedupe — 第二次同路径调用不额外执行 SQL ──
        assertEquals(1, dataLoader.cachedPathCount(),
                "DataLoader 在同一实例中应缓存 1 条路径（dedupe 验证）");

        // ── Step 7: FormulaEngine 计算 {path} × 1000 ─────────────────────────
        // 公式：unit_weight(kg) × 1000 = weight(g)，期望 = 0.0125 × 1000 = 12.5
        String formula = "{" + path + "} * 1000";

        EvaluationContext evalCtx = EvaluationContext.builder()
                .dataLoader(dataLoader)
                .build();
        Object formulaResult = formulaEngine.evaluate(formula, evalCtx);

        assertNotNull(formulaResult,
                "FormulaEngine 应返回非 null 结果");
        assertFalse(formulaResult instanceof FormulaError,
                "FormulaEngine 应成功计算，不应返回 FormulaError，实际: " + formulaResult);

        BigDecimal expected = new BigDecimal("12.5");
        BigDecimal actual   = new BigDecimal(formulaResult.toString());
        assertEquals(0, expected.compareTo(actual),
                "公式 unit_weight × 1000 应 = 12.5，实际: " + actual);

        // ── Step 8: DerivedAttributeCalculatorV5 计算衍生属性 ────────────────
        DerivedAttribute attr = makeDerivedAttr(
                "weight_gram",
                "{" + path + "} * 1000");

        Map<String, Object> calcResults = calculator.calculate(
                CUSTOMER_E2E, TEST_PART_NO, List.of(attr));

        assertTrue(calcResults.containsKey("weight_gram"),
                "DerivedAttributeCalculatorV5 结果应包含 weight_gram");

        Object calcVal = calcResults.get("weight_gram");
        assertFalse(calcVal instanceof FormulaError,
                "DerivedAttributeCalculatorV5 结果不应为 FormulaError，实际: " + calcVal);

        BigDecimal calcBd = new BigDecimal(calcVal.toString());
        assertEquals(0, expected.compareTo(calcBd),
                "DerivedAttributeCalculatorV5 计算 weight_gram 应 = 12.5，实际: " + calcBd);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T6 — 缓存命中率验证：第二次同路径解析 hitCount > 0
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(11)
    @DisplayName("T6: CachedPathParser 第二次同路径解析命中缓存（hitCount > 0）")
    void t6_cachedPathParser_secondParseCachesHit() {
        // 使用新的 CachedPathParser 实例（容器注入的实例可能已有历史状态，直接 new 保证干净统计）
        CachedPathParser freshParser = new CachedPathParser(10000L, "30m");

        String path = "mat_part[part_no='E2E-CACHE-TEST'].unit_weight";

        // 第一次：miss
        long t0 = System.nanoTime();
        freshParser.parse(path);
        long firstParse = System.nanoTime() - t0;

        // 第二次：hit
        long t1 = System.nanoTime();
        freshParser.parse(path);
        long secondParse = System.nanoTime() - t1;

        CacheStats stats = freshParser.getRawCache().stats();
        assertEquals(1, stats.missCount(), "首次解析应为 miss");
        assertEquals(1, stats.hitCount(),  "第二次解析应命中缓存");
        assertTrue(stats.hitRate() > 0,    "hitRate 应大于 0");

        // 第二次通常更快（缓存命中）；但 CI 可能有抖动，仅记录，不作严格断言
        // 仅断言 hitCount 是缓存正确工作的可靠证明
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T7 — 锁释放验证：导入完成后 ACTIVE 锁 = 0
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(12)
    @DisplayName("T7: 导入完成后 product_import_lock ACTIVE 记录应为 0")
    void t7_locksReleased_afterImportCompletes() throws Exception {
        String lockPartNo = "E2E-LOCK-RELEASE-001";

        // 准备 mat_part（锁测试用料号）
        ensureMatPartExists(lockPartNo);

        byte[] xlsx = buildSinglePartExcel(lockPartNo, 0.010);
        ImportResultDTO result = serviceV5.importBasicDataV5(
                new ByteArrayInputStream(xlsx), CUSTOMER_E2E, USER_E2E);

        // 导入应成功
        assertEquals("SUCCESS", result.status,
                "锁释放测试：导入应 SUCCESS，实际: " + result.status);

        // 查询该导入记录对应的 ACTIVE 锁
        Long activeLocks = queryLong(
                "SELECT COUNT(*) FROM product_import_lock " +
                "WHERE import_record_id = '" + result.importRecordId + "' " +
                "AND status = 'ACTIVE'");

        assertEquals(0L, activeLocks,
                "导入完成后不应存在 ACTIVE 状态的 product_import_lock 记录");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // T8 — [3]→[6] 桥接覆盖：14 表写入后路径查询无需依赖 T5 的特定料号
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(13)
    @DisplayName("T8: 14 表写入后，CachedSqlCompiler 编译 mat_bom ELEMENT 路径可执行")
    void t8_physicalTable_to_pathQuery_bridge() throws Exception {
        // 先确保 mat_part 和 mat_bom ELEMENT 数据存在（跨测试复用 T5 写入的数据）
        // 若 T5 已跑，TEST_PART_NO 行应存在；若单独执行此 test，则写入基础数据
        ensureMatPartExists(TEST_PART_NO);
        ensureMatBomElementExists(TEST_PART_NO, "Ag", new BigDecimal("90.0"));

        // BNF 路径：mat_bom ELEMENT 行的 composition_pct 查询
        String bomPath = "mat_bom[hf_part_no='" + TEST_PART_NO + "'].composition_pct";

        // 解析路径
        var ast = pathParser.parse(bomPath);
        assertNotNull(ast, "mat_bom 路径解析 AST 不应为 null");

        // 编译 SQL
        SqlAndParams compiled = sqlCompiler.compile(ast, SchemaContext.defaultContext());
        assertNotNull(compiled.sql(), "编译 SQL 不应为 null");
        assertTrue(compiled.sql().toLowerCase().contains("mat_bom"),
                "SQL 应包含 mat_bom 表名，实际 SQL: " + compiled.sql());

        // 执行路径查询
        List<Map<String, Object>> rows =
                dataLoader.loadByPath(bomPath).get();

        assertFalse(rows.isEmpty(),
                "DataLoader 应查到 mat_bom 中 hf_part_no='" + TEST_PART_NO + "' 的行");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 构造包含 mat_part 和 mat_bom 两个 Sheet 的最小 in-memory Excel。
     * mat_bom Sheet 写入一条 ELEMENT 类型的 BOM 行（Ag 90%）。
     */
    private byte[] buildMinimalExcel(String partNo, double unitWeightKg) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {

            // Sheet 1: 料号主档（mat_part）
            Sheet matPartSheet = wb.createSheet("料号主档");
            Row h1 = matPartSheet.createRow(0);
            String[] matPartCols = {
                "HF_PART_NO", "PART_NAME", "SPECIFICATION",
                "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"
            };
            for (int c = 0; c < matPartCols.length; c++) {
                h1.createCell(c).setCellValue(matPartCols[c]);
            }
            Row d1 = matPartSheet.createRow(1);
            d1.createCell(0).setCellValue(partNo);
            d1.createCell(1).setCellValue("E2E 测试料号");
            d1.createCell(2).setCellValue("AgNi10");
            d1.createCell(3).setCellValue("Φ3×5");
            d1.createCell(4).setCellValue(unitWeightKg);
            d1.createCell(5).setCellValue("KG");
            d1.createCell(6).setCellValue("Y");

            // Sheet 2: BOM清单（mat_bom — ELEMENT 行）
            Sheet matBomSheet = wb.createSheet("BOM清单");
            Row h2 = matBomSheet.createRow(0);
            // StreamingExcelParser BOM Sheet 期望的列头（参照 BasicDataImportServiceV5 解析逻辑）
            String[] matBomCols = {
                "HF_PART_NO", "BOM_TYPE", "SEQ_NO",
                "ELEMENT_NAME", "COMPOSITION_PCT"
            };
            for (int c = 0; c < matBomCols.length; c++) {
                h2.createCell(c).setCellValue(matBomCols[c]);
            }
            Row d2 = matBomSheet.createRow(1);
            d2.createCell(0).setCellValue(partNo);
            d2.createCell(1).setCellValue("ELEMENT");
            d2.createCell(2).setCellValue(1.0);
            d2.createCell(3).setCellValue("Ag");
            d2.createCell(4).setCellValue(90.0);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 构造单 Sheet（仅 mat_part）的 Excel。V58_5/V59 seed 列顺序: A=part_no B=part_name C=spec D=size_info E=unit_weight F=weight_unit G=status_code */
    private byte[] buildSinglePartExcel(String partNo, double unitWeightKg) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("料号主档");
            Row header = sheet.createRow(0);
            String[] cols = {"HF_PART_NO", "PART_NAME", "SPECIFICATION", "SIZE_INFO", "UNIT_WEIGHT", "WEIGHT_UNIT", "STATUS_CODE"};
            for (int c = 0; c < cols.length; c++) {
                header.createCell(c).setCellValue(cols[c]);
            }
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue(partNo);
            r.createCell(1).setCellValue("测试料号");
            r.createCell(2).setCellValue("AgNi10");
            r.createCell(3).setCellValue("3x5");
            r.createCell(4).setCellValue(unitWeightKg);  // E=unit_weight
            r.createCell(5).setCellValue("KG");
            r.createCell(6).setCellValue("Y");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 构造衍生属性（EXPRESSION 类型）。 */
    private static DerivedAttribute makeDerivedAttr(String code, String formula) {
        DerivedAttribute attr = new DerivedAttribute();
        attr.id            = UUID.randomUUID();
        attr.variableCode  = code;
        attr.variableLabel = code;
        attr.computationType = "EXPRESSION";
        attr.computation   = "{\"formula\": \"" + formula.replace("\"", "\\\"") + "\"}";
        attr.status        = "ACTIVE";
        attr.sortOrder     = 0;
        attr.hostSheetId   = UUID.randomUUID();
        attr.createdAt     = OffsetDateTime.now();
        attr.updatedAt     = OffsetDateTime.now();
        return attr;
    }

    /** 通过 REQUIRES_NEW 事务确保 mat_part 行存在。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void ensureMatPartExists(String partNo) {
        em.createNativeQuery(
                "INSERT INTO mat_part(part_no, part_name, unit_weight, weight_unit, " +
                "status_code, created_at, updated_at) " +
                "VALUES (:pn, 'E2E 测试', 0.010, 'KG', 'Y', NOW(), NOW()) " +
                "ON CONFLICT (part_no) DO NOTHING")
                .setParameter("pn", partNo)
                .executeUpdate();
    }

    /** 通过 REQUIRES_NEW 事务确保 mat_bom ELEMENT 行存在。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void ensureMatBomElementExists(String partNo, String elementName, BigDecimal compositionPct) {
        em.createNativeQuery(
                "INSERT INTO mat_bom(id, bom_type, hf_part_no, seq_no, element_name, " +
                "composition_pct, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'ELEMENT', :pn, 1, :elem, :pct, NOW(), NOW()) " +
                "ON CONFLICT DO NOTHING")
                .setParameter("pn", partNo)
                .setParameter("elem", elementName)
                .setParameter("pct", compositionPct)
                .executeUpdate();
    }

    /** 在新事务中执行 COUNT 查询（避免污染外部事务边界）。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Long queryLong(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
