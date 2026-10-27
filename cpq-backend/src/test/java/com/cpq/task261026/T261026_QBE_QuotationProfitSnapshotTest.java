package com.cpq.task261026;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-261026 · S-QBE（AC-14、AC-15、AC-17~AC-19）。
 *
 * <p>本类只使用任务文档公开的 HTTP/DB 契约与现有测试基建，不引用业务 DTO、Entity、Service。
 * 所有自建名称、客户号、客户产品编号和销售料号均使用 {@code T261026_QBE_} 前缀；清理只按
 * 本类记录的 UUID、客户号或前缀执行。
 *
 * <p>🚨 会执行 Flyway 与 committed HTTP/SQL 写入，只允许主代理提供 {@code DB_NAME} 指向一次性库后解锁。
 */
@QuarkusTest
@DisplayName("task-261026 · S-QBE 报价行利润快照")
class T261026_QBE_QuotationProfitSnapshotTest {

    private static final String PREFIX = "T261026_QBE_";
    private static final String ADMIN_PASSWORD = "T261026@QbeProfit";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Inject
    EntityManager em;

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> customerIds = new ArrayList<>();
    private final Set<String> customerNos = new LinkedHashSet<>();
    private final Set<String> materialNos = new LinkedHashSet<>();
    private final Set<String> generatedMaterialNos = new LinkedHashSet<>();
    private final Set<UUID> importRecordIds = new LinkedHashSet<>();
    private String adminUsername;
    private String sessionCookie;
    private CustomerRef customer;

    @BeforeEach
    void T261026_QBE_setUp() {
        seedPrivateAdminAndLogin();
        customer = createCustomer("PRIMARY");
    }

    @AfterEach
    void T261026_QBE_cleanup() {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID quotationId : quotationIds) {
                    if (tableExists("costing_order_version_override") && tableExists("costing_order")) {
                        em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id IN "
                                        + "(SELECT id FROM costing_order WHERE quotation_id=:qid)")
                                .setParameter("qid", quotationId).executeUpdate();
                    }
                    if (tableExists("costing_order")) {
                        em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id=:qid")
                                .setParameter("qid", quotationId).executeUpdate();
                    }
                    em.createNativeQuery("DELETE FROM quotation WHERE id=:qid")
                            .setParameter("qid", quotationId).executeUpdate();
                }

                for (UUID recordId : importRecordIds) {
                    em.createNativeQuery("UPDATE import_record SET quotation_id=NULL WHERE id=:id")
                            .setParameter("id", recordId).executeUpdate();
                    em.createNativeQuery("DELETE FROM import_record WHERE id=:id")
                            .setParameter("id", recordId).executeUpdate();
                }

                for (String customerNo : customerNos) {
                    for (String table : List.of(
                            "sel_product_no", "sel_part_signature", "unit_price", "element_bom_item",
                            "material_bom_item", "ds_quote_material_bom", "ds_quote_element_bom",
                            "ds_quote_process", "ds_quote_customer_part", "material_customer_map")) {
                        if (tableExists(table) && columnExists(table, "customer_no")) {
                            em.createNativeQuery("DELETE FROM " + table + " WHERE customer_no=:cn")
                                    .setParameter("cn", customerNo).executeUpdate();
                        }
                    }
                    if (tableExists("ds_quote_material")) {
                        em.createNativeQuery("DELETE FROM ds_quote_material WHERE customer_no=:cn "
                                        + "OR material_no LIKE :prefix")
                                .setParameter("cn", customerNo)
                                .setParameter("prefix", PREFIX + "%").executeUpdate();
                    }
                }

                Set<String> allMaterials = new LinkedHashSet<>(materialNos);
                allMaterials.addAll(generatedMaterialNos);
                if (!allMaterials.isEmpty()) {
                    for (String table : List.of("ds_quote_material_bom", "ds_quote_element_bom")) {
                        if (tableExists(table) && columnExists(table, "material_no")) {
                            em.createNativeQuery("DELETE FROM " + table
                                            + " WHERE material_no IN (:nos) OR material_no LIKE :prefix")
                                    .setParameter("nos", allMaterials)
                                    .setParameter("prefix", PREFIX + "%").executeUpdate();
                        }
                    }
                }
                if (!allMaterials.isEmpty() && tableExists("material_master")) {
                    em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:nos) "
                                    + "OR material_no LIKE :prefix")
                            .setParameter("nos", allMaterials)
                            .setParameter("prefix", PREFIX + "%").executeUpdate();
                }
                for (UUID customerId : customerIds) {
                    em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id=:id")
                            .setParameter("id", customerId).executeUpdate();
                    em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                            .setParameter("id", customerId).executeUpdate();
                }
            });
        } finally {
            try {
                cleanupPrivateAdmin();
            } finally {
                quotationIds.clear();
                customerIds.clear();
                customerNos.clear();
                materialNos.clear();
                generatedMaterialNos.clear();
                importRecordIds.clear();
                sessionCookie = null;
                adminUsername = null;
            }
        }
    }

    @Test
    @DisplayName("T261026_QBE_17 / AC-14、AC-19：V455 可空列、历史 NULL GET 且不回查主数据")
    void T261026_QBE_17_schemaAndHistoricalNullStayNull() {
        @SuppressWarnings("unchecked")
        List<Object[]> columns = em.createNativeQuery("""
                SELECT data_type,numeric_precision,numeric_scale,is_nullable
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='quotation_line_item'
                  AND column_name='profit_snapshot'
                """).getResultList();
        assertEquals(1, columns.size(), "AC-14：profit_snapshot 必须存在且只能有一列");
        Object[] column = columns.get(0);
        assertEquals("numeric", String.valueOf(column[0]));
        assertEquals(26, ((Number) column[1]).intValue());
        assertEquals(12, ((Number) column[2]).intValue());
        assertEquals("YES", String.valueOf(column[3]), "AC-14：迁移必须只加可空列");

        long v455 = count("SELECT count(*) FROM flyway_schema_history "
                + "WHERE version='455' AND success=true");
        assertEquals(1L, v455, "AC-14：隔离库必须确实执行且只记录一条成功 V455");

        String materialNo = PREFIX + "HIST_NULL";
        insertMaterial(customer, materialNo, "8.750000000000");
        UUID quotationId = createQuotation(customer, "HIST_NULL");
        saveAddedLines(quotationId, List.of(lineDraft(null, materialNo, 0)));
        UUID lineId = lineId(quotationId, materialNo, 0);

        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE quotation_line_item SET profit_snapshot=NULL WHERE id=:id")
                .setParameter("id", lineId).executeUpdate());
        assertNull(scalar("SELECT profit_snapshot FROM quotation_line_item WHERE id=:id", "id", lineId),
                "AC-14 前置：历史行必须明确模拟为 NULL，不能拿空集合验证");

        updateMaterialProfit(customer, materialNo, "99.990000000000");
        Map<String, Object> line = exactLine(getQuotation(quotationId), materialNo, 0);
        assertTrue(line.containsKey("profit"), "AC-19：GET lineItems[] 必须恒含 profit 键：" + line);
        assertNull(line.get("profit"), "AC-14：历史 NULL 不得回查当前基础利润 99.99");
        assertNull(scalar("SELECT profit_snapshot FROM quotation_line_item WHERE id=:id", "id", lineId),
                "AC-14：GET 只能读快照，不能顺手把历史 NULL 回填进库");
    }

    @Test
    @DisplayName("T261026_QBE_18 / AC-15、AC-19：正/零/负/NULL/12 位字符串冻结，旧 payload 兼容")
    void T261026_QBE_18_newRowsFreezeScaleAndLegacyPayloadCannotWriteProfit() {
        LinkedHashMap<String, String> cases = new LinkedHashMap<>();
        cases.put(PREFIX + "POS", "12.500000000000");
        cases.put(PREFIX + "ZERO", "0");
        cases.put(PREFIX + "NEG", "-2.125000000000");
        cases.put(PREFIX + "ROUND", "1.234567891500");
        cases.put(PREFIX + "NULL", null);
        cases.forEach((materialNo, profit) -> insertMaterial(customer, materialNo, profit));

        UUID quotationId = createQuotation(customer, "ALL_VALUES");
        List<Map<String, Object>> added = new ArrayList<>();
        int sortOrder = 0;
        for (String materialNo : cases.keySet()) added.add(lineDraft(null, materialNo, sortOrder++));
        saveAddedLines(quotationId, added);

        Response reopened = getQuotation(quotationId);
        List<Map<String, Object>> lines = lines(reopened);
        assertEquals(5, lines.size(), "AC-15 前置：5 种利润必须实际生成 5 行，0 行不得空跑");
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            Map<String, Object> line = exactLine(reopened, entry.getKey(), 0);
            assertTrue(line.containsKey("profit"), "AC-19：profit 键不得因 NULL 被 NON_NULL 吞掉：" + line);
            String expected = entry.getValue();
            if (expected == null) {
                assertNull(line.get("profit"), "AC-15：基础利润 NULL 必须冻结为 null");
            } else {
                assertTrue(line.get("profit") instanceof String,
                        "AC-15/19：非空利润必须是 JSON string，不能是 number：" + line);
                assertEquals(new BigDecimal(expected).setScale(12).toPlainString(), line.get("profit"),
                        "AC-15：接口必须保留数据库 12 位 scale");
            }
        }

        String roundNo = PREFIX + "ROUND";
        Map<String, Object> roundLine = exactLine(reopened, roundNo, 0);
        long baseVersion = version(reopened);
        Map<String, Object> modified = lineDraft(UUID.fromString(String.valueOf(roundLine.get("id"))), roundNo, 3);
        assertFalse(modified.containsKey("profit") || modified.containsKey("profitSnapshot"),
                "AC-19：旧 payload 不得携带客户端可写利润字段");
        Response oldPayload = putDraft(quotationId, baseVersion, List.of(), List.of(modified));
        assertEquals(200, oldPayload.statusCode(), "AC-19：旧 payload 保存必须成功：" + oldPayload.asString());
        assertProfit(exactLine(getQuotation(quotationId), roundNo, 0), "1.234567891500",
                "AC-19：旧 payload 保存后利润快照不变");
    }

    @Test
    @DisplayName("T261026_QBE_20 / AC-17、AC-19：基础利润后改、修改/重算/刷新均不漂移，新行取新值")
    void T261026_QBE_20_existingSnapshotNeverDriftsAndNewLineUsesCurrentProfit() {
        String materialNo = PREFIX + "DRIFT";
        insertMaterial(customer, materialNo, "1.250000000000");
        UUID quotationId = createQuotation(customer, "DRIFT");
        saveAddedLines(quotationId, List.of(lineDraft(null, materialNo, 0)));
        assertProfit(exactLine(getQuotation(quotationId), materialNo, 0), "1.250000000000", "首次冻结");

        updateMaterialProfit(customer, materialNo, "9.500000000000");
        Response beforeModify = getQuotation(quotationId);
        Map<String, Object> oldLine = exactLine(beforeModify, materialNo, 0);
        Map<String, Object> modified = lineDraft(
                UUID.fromString(String.valueOf(oldLine.get("id"))), materialNo, 0);
        assertEquals(200, putDraft(quotationId, version(beforeModify), List.of(), List.of(modified)).statusCode(),
                "AC-17：modified saveDraft 应成功");
        assertProfit(exactLine(getQuotation(quotationId), materialNo, 0), "1.250000000000", "modified 后旧行");

        for (String action : List.of("recalculate", "refresh-card-snapshot", "ensure-card-values")) {
            Response actionResponse = postEmpty("/api/cpq/quotations/" + quotationId + "/" + action);
            assertEquals(200, actionResponse.statusCode(), action + " 失败：" + actionResponse.asString());
            assertProfit(exactLine(getQuotation(quotationId), materialNo, 0), "1.250000000000",
                    action + " 后旧行不得漂移");
        }
        Response configureRefresh = postEmpty(
                "/api/cpq/configure-product/quotations/" + quotationId + "/refresh-snapshot");
        assertEquals(200, configureRefresh.statusCode(),
                "选配刷新失败：" + configureRefresh.asString());
        assertProfit(exactLine(getQuotation(quotationId), materialNo, 0), "1.250000000000",
                "选配刷新后旧行不得漂移");

        Response beforeSecond = getQuotation(quotationId);
        Response secondSave = putDraft(quotationId, version(beforeSecond),
                List.of(lineDraft(null, materialNo, 1)), List.of());
        assertEquals(200, secondSave.statusCode(), secondSave.asString());
        Response afterSecond = getQuotation(quotationId);
        List<Map<String, Object>> samePartLines = lines(afterSecond).stream()
                .filter(line -> materialNo.equals(String.valueOf(line.get("productPartNo"))))
                .toList();
        assertEquals(2, samePartLines.size(), "AC-17：同料号必须实际存在新旧两行");
        assertProfit(samePartLines.get(0), "1.250000000000", "旧行仍为 1.25");
        assertProfit(samePartLines.get(1), "9.500000000000", "新行冻结当前 9.5");
    }

    @Test
    @DisplayName("T261026_QBE_19 / AC-15、AC-19：候选/已有产品显式字符串或 NULL，利润查询 2 行与 6 行同阶")
    void T261026_QBE_19_candidateAndExistingProductContractsHaveNoProfitNPlusOne() {
        CustomerRef twoCustomer = createCustomer("N2");
        CustomerRef sixCustomer = createCustomer("N6");
        UUID twoQuotation = createQuotation(twoCustomer, "N2");
        UUID sixQuotation = createQuotation(sixCustomer, "N6");
        for (int i = 0; i < 2; i++) seedCandidate(twoCustomer, PREFIX + "N2_" + i,
                i == 0 ? null : "2.500000000000", i);
        for (int i = 0; i < 6; i++) seedCandidate(sixCustomer, PREFIX + "N6_" + i,
                i % 2 == 0 ? null : i + ".125000000000", i);

        Statistics statistics = statistics();
        candidateResponse(twoCustomer);
        statistics.clear();
        Response twoCandidates = candidateResponse(twoCustomer);
        long candidateTwoQueries = profitQueryCount(statistics);
        List<Map<String, Object>> twoCandidateRows = candidateRows(twoCandidates, PREFIX + "N2_");
        assertEquals(2, twoCandidateRows.size(), "候选 2 行夹具必须精确命中，不能 0 行空跑");
        assertProfitKeyContract(twoCandidateRows, "partNo", "候选 2 行");

        candidateResponse(sixCustomer);
        statistics.clear();
        Response sixCandidates = candidateResponse(sixCustomer);
        long candidateSixQueries = profitQueryCount(statistics);
        List<Map<String, Object>> sixCandidateRows = candidateRows(sixCandidates, PREFIX + "N6_");
        assertEquals(6, sixCandidateRows.size(), "候选 6 行夹具必须精确命中");
        assertProfitKeyContract(sixCandidateRows, "partNo", "候选 6 行");
        assertNoProfitNPlusOne("customer-part-candidates", candidateTwoQueries, candidateSixQueries);

        existingResponse(twoQuotation, PREFIX + "N2_");
        statistics.clear();
        Response twoExisting = existingResponse(twoQuotation, PREFIX + "N2_");
        long existingTwoQueries = profitQueryCount(statistics);
        List<Map<String, Object>> twoExistingRows = existingRows(twoExisting);
        assertEquals(2, twoExistingRows.size(), "已有产品 2 行夹具必须精确命中");
        assertProfitKeyContract(twoExistingRows, "materialNo", "已有产品 2 行");

        existingResponse(sixQuotation, PREFIX + "N6_");
        statistics.clear();
        Response sixExisting = existingResponse(sixQuotation, PREFIX + "N6_");
        long existingSixQueries = profitQueryCount(statistics);
        List<Map<String, Object>> sixExistingRows = existingRows(sixExisting);
        assertEquals(6, sixExistingRows.size(), "已有产品 6 行夹具必须精确命中");
        assertProfitKeyContract(sixExistingRows, "materialNo", "已有产品 6 行");
        assertNoProfitNPlusOne("existing-products", existingTwoQueries, existingSixQueries);
    }

    @Test
    @DisplayName("T261026_QBE_19B / AC-15、AC-18：saveDraft 2 行与 6 行利润查询均最多一次")
    void T261026_QBE_19B_saveDraftProfitLookupIsBatchAndRowCountIndependent() {
        long twoQueries = saveDraftProfitQueries(2, "SAVE_N2");
        long sixQueries = saveDraftProfitQueries(6, "SAVE_N6");
        assertNoProfitNPlusOne("saveDraft", twoQueries, sixQueries);
    }

    @Test
    @DisplayName("T261026_QBE_21 / AC-18：选配 NULL、绑定 3.75、复制非空/NULL，金额与导出不受利润影响")
    void T261026_QBE_21_configureBindCopyAndCalculationBoundaries() {
        String bindNo = PREFIX + "BIND";
        seedCandidate(customer, bindNo, "3.750000000000", 0);
        UUID bindQuotation = createQuotation(customer, "BIND");
        Map<String, Object> bindBody = new LinkedHashMap<>();
        bindBody.put("productType", "SIMPLE");
        bindBody.put("parts", List.of());
        bindBody.put("compositeProcesses", List.of());
        bindBody.put("tempId", UUID.randomUUID().toString());
        bindBody.put("customerProductNo", PREFIX + "CPN_BIND");
        bindBody.put("customerProductName", PREFIX + "绑定产品");
        bindBody.put("bindExistingMaterialNo", bindNo);
        Statistics bindStatistics = statistics();
        bindStatistics.clear();
        Response bound = request().contentType(ContentType.JSON).body(bindBody)
                .post("/api/cpq/configure-product/quotations/" + bindQuotation);
        assertEquals(200, bound.statusCode(), "AC-18：绑定已有产品失败：" + bound.asString());
        long bindProfitQueries = profitQueryCount(bindStatistics);
        List<Map<String, Object>> boundLines = bound.jsonPath().getList("lineItems");
        assertNotNull(boundLines, "AC-18：绑定响应必须直接含 lineItems[]");
        assertFalse(boundLines.isEmpty(), "AC-18：绑定响应 0 行会让利润断言空跑");
        assertProfit(boundLines.get(0), "3.750000000000", "绑定已有产品冻结 3.75");
        assertTrue(bindProfitQueries <= 1,
                "AC-18：绑定一次最多一次利润批量查询，实际=" + bindProfitQueries);

        UUID configuredQuotation = createQuotation(customer, "CONFIGURED_NULL");
        Map<String, Object> configuredBody = new LinkedHashMap<>();
        configuredBody.put("productType", "SIMPLE");
        configuredBody.put("customerProductNo", PREFIX + "CPN_NEW");
        configuredBody.put("customerProductName", PREFIX + "选配新铸产品");
        configuredBody.put("parts", List.of(Map.of(
                "name", PREFIX + "零件",
                "partType", "PART",
                "partMode", "new",
                "spec", "T261026",
                "dimension", "1×1×1",
                "unitWeightGrams", "1",
                "materials", List.of(Map.of(
                        "recipeCode", "00006", "configNo", "00006-01",
                        "ratio", "100", "elements", List.of())),
                "processNos", List.of("Z100"),
                "quantity", 1)));
        Response configured = request().contentType(ContentType.JSON).body(configuredBody)
                .post("/api/cpq/configure-product/quotations/" + configuredQuotation);
        assertEquals(200, configured.statusCode(), "AC-18：选配新铸产品失败：" + configured.asString());
        List<Map<String, Object>> configuredLines = configured.jsonPath().getList("lineItems");
        assertNotNull(configuredLines, "选配响应缺 lineItems[]");
        assertFalse(configuredLines.isEmpty(), "选配响应 0 行不得空跑");
        assertTrue(configuredLines.get(0).containsKey("profit"), "选配响应必须含 profit 键");
        assertNull(configuredLines.get(0).get("profit"), "新铸产品基础利润为空必须冻结 null");
        Object configuredPartNo = configuredLines.get(0).get("productPartNo");
        if (configuredPartNo != null) generatedMaterialNos.add(String.valueOf(configuredPartNo));

        String copyValueNo = PREFIX + "COPY_VALUE";
        String copyNullNo = PREFIX + "COPY_NULL";
        insertMaterial(customer, copyValueNo, "1.250000000000");
        insertMaterial(customer, copyNullNo, null);
        UUID source = createQuotation(customer, "COPY_SOURCE");
        saveAddedLines(source, List.of(
                lineDraft(null, copyValueNo, 0), lineDraft(null, copyNullNo, 1)));
        Response sourceBefore = getQuotation(source);
        assertEquals(2, lines(sourceBefore).size(), "复制前源单必须有 2 行");
        Map<String, Object> amountFingerprint = amountFingerprint(sourceBefore);

        Response copied = request().contentType(ContentType.JSON).body(Map.of())
                .post("/api/cpq/quotations/" + source + "/copy");
        assertEquals(200, copied.statusCode(), "AC-18：复制失败：" + copied.asString());
        UUID copyId = UUID.fromString(copied.jsonPath().getString("data.id"));
        quotationIds.add(copyId);
        Response copiedGet = getQuotation(copyId);
        assertProfit(exactLine(copiedGet, copyValueNo, 0), "1.250000000000", "复制非空快照");
        assertProfit(exactLine(copiedGet, copyNullNo, 0), null, "复制历史 NULL 快照");

        Response recalculated = postEmpty("/api/cpq/quotations/" + source + "/recalculate");
        assertEquals(200, recalculated.statusCode(), recalculated.asString());
        Response sourceAfter = getQuotation(source);
        assertEquals(amountFingerprint, amountFingerprint(sourceAfter),
                "AC-18：利润不得参与小计、VAT、折扣或总额");

        Response exportView = request().get(
                "/api/cpq/quotations/" + source + "/export-excel-view");
        assertEquals(200, exportView.statusCode(), "AC-18：导出视图失败：" + exportView.asString());
        assertFalse(exportView.asString().contains("profit") || exportView.asString().contains("利润"),
                "AC-18：导出响应不得新增利润字段：" + exportView.asString());
    }

    @Test
    @DisplayName("T261026_QBE_22 / AC-15、AC-18：报价导入物化冻结 12 位利润字符串")
    void T261026_QBE_22_importMaterializationFreezesProfit() throws Exception {
        String materialNo = PREFIX + "IMPORT_MAT";
        String profit = "1.234567891500";
        byte[] workbook = quotationImportWorkbook(materialNo, profit, customer.code());
        assertQuotationImportWorkbook(workbook, customer.code(), materialNo, profit);
        Response imported = request()
                .multiPart("customerId", customer.id().toString())
                .multiPart("file", PREFIX + "quotation-import.xlsx", workbook, XLSX_MIME)
                .post("/api/cpq/dataset/quote/quotation-import");
        assertTrue(imported.statusCode() >= 200 && imported.statusCode() < 300,
                "导入受理失败：" + imported.asString());
        String recordText = imported.jsonPath().getString("data.importRecordId");
        assertNotNull(recordText, "导入响应缺 data.importRecordId：" + imported.asString());
        UUID recordId = UUID.fromString(recordText);
        importRecordIds.add(recordId);
        Map<String, Object> finalImport = awaitImport(recordId);
        assertEquals("SUCCESS", finalImport.get("status"), "导入必须成功：" + finalImport);
        assertTrue(((Number) finalImport.getOrDefault("successRows", 0)).longValue() > 0,
                "导入 successRows 必须 >0，禁止 0 行空跑：" + finalImport);

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("importRecordId", recordId.toString());
        createBody.put("customerId", customer.id().toString());
        createBody.put("name", PREFIX + "IMPORT_" + suffix());
        createBody.put("categoryId", null);
        createBody.put("customerTemplateId", null);
        createBody.put("costingTemplateId", null);
        Response created = request().contentType(ContentType.JSON).body(createBody)
                .post("/api/cpq/dataset/quote/create-quotation");
        assertEquals(200, created.statusCode(), "导入建单失败：" + created.asString());
        UUID quotationId = UUID.fromString(created.jsonPath().getString("data.quotationId"));
        quotationIds.add(quotationId);
        assertEquals(Boolean.TRUE, created.jsonPath().getBoolean("data.materializing"),
                "导入建单响应必须显式 materializing=true");

        awaitMaterializedLine(quotationId, materialNo);
        Response ensured = postEmpty("/api/cpq/quotations/" + quotationId + "/ensure-card-values");
        assertEquals(200, ensured.statusCode(), ensured.asString());
        assertProfit(exactLine(getQuotation(quotationId), materialNo, 0), "1.234567891500",
                "导入物化行必须冻结 scale=12 字符串");
    }

    // ────── HTTP contract helpers ──────

    private void seedPrivateAdminAndLogin() {
        adminUsername = PREFIX + "ADMIN_" + suffix();
        assertTrue(adminUsername.length() <= 32, "私有管理员用户名不得超过 32 字符");
        String passwordHash = BCrypt.hashpw(ADMIN_PASSWORD, BCrypt.gensalt(10));
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO "user"
                  (username,full_name,email,password_hash,role,status,is_first_login,failed_login_attempts)
                VALUES (:username,:fullName,:email,:passwordHash,'SYSTEM_ADMIN','ACTIVE',false,0)
                """).setParameter("username", adminUsername)
                .setParameter("fullName", PREFIX + "私有管理员")
                .setParameter("email", adminUsername.toLowerCase() + "@example.invalid")
                .setParameter("passwordHash", passwordHash)
                .executeUpdate());

        Response login = RestAssured.given().contentType(ContentType.JSON)
                .body(Map.of("username", adminUsername, "password", ADMIN_PASSWORD))
                .post("/api/cpq/auth/login");
        assertEquals(200, login.statusCode(), "私有 SYSTEM_ADMIN 登录失败：" + login.asString());
        assertEquals("SYSTEM_ADMIN", login.jsonPath().getString("data.role"),
                "私有测试用户必须以 SYSTEM_ADMIN 身份登录");
        sessionCookie = login.cookie("CPQ_SESSION");
        assertTrue(sessionCookie != null && !sessionCookie.isBlank(),
                "私有 SYSTEM_ADMIN 登录 200 但未返回 CPQ_SESSION");
    }

    private void cleanupPrivateAdmin() {
        if (adminUsername == null) return;
        String username = adminUsername;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM \"user\" WHERE username=:username")
                    .setParameter("username", username).executeUpdate();
            long remaining = ((Number) em.createNativeQuery(
                            "SELECT count(*) FROM \"user\" WHERE username=:username")
                    .setParameter("username", username).getSingleResult()).longValue();
            assertEquals(0L, remaining, "私有 SYSTEM_ADMIN 测试用户未清理干净：" + username);
        });
    }

    private RequestSpecification request() {
        assertTrue(sessionCookie != null && !sessionCookie.isBlank(),
                "HTTP 请求前必须建立私有 SYSTEM_ADMIN CPQ_SESSION");
        return RestAssured.given().cookie("CPQ_SESSION", sessionCookie);
    }

    private UUID createQuotation(CustomerRef owner, String label) {
        Response response = request().contentType(ContentType.JSON)
                .body(Map.of("customerId", owner.id().toString(),
                        "name", PREFIX + label + "_" + suffix()))
                .post("/api/cpq/quotations");
        assertEquals(200, response.statusCode(), "创建报价失败：" + response.asString());
        UUID id = UUID.fromString(response.jsonPath().getString("data.id"));
        quotationIds.add(id);
        assertNotNull(response.jsonPath().get("data.userDataVersion"),
                "创建响应缺 userDataVersion：" + response.asString());
        return id;
    }

    private Response getQuotation(UUID quotationId) {
        Response response = request().get("/api/cpq/quotations/" + quotationId);
        assertEquals(200, response.statusCode(), "GET quotation 失败：" + response.asString());
        List<Map<String, Object>> rows = response.jsonPath().getList("data.lineItems");
        assertNotNull(rows, "GET 必须含 data.lineItems[]：" + response.asString());
        return response;
    }

    private void saveAddedLines(UUID quotationId, List<Map<String, Object>> added) {
        assertFalse(added.isEmpty(), "added 夹具不得为空，否则冻结断言不会执行");
        Response before = getQuotation(quotationId);
        Response response = putDraft(quotationId, version(before), added, List.of());
        assertEquals(200, response.statusCode(), "PUT draft 失败：" + response.asString());
        assertEquals(added.size(), lines(getQuotation(quotationId)).size(),
                "保存后行数必须与 added 精确一致");
    }

    private Response putDraft(UUID quotationId, long baseVersion,
                              List<Map<String, Object>> added,
                              List<Map<String, Object>> modified) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("baseVersion", baseVersion);
        body.put("added", added);
        body.put("modified", modified);
        body.put("removed", List.of());
        return request().contentType(ContentType.JSON).body(body)
                .put("/api/cpq/quotations/" + quotationId + "/draft");
    }

    private Map<String, Object> lineDraft(UUID id, String materialNo, int sortOrder) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", id == null ? null : id.toString());
        if (id == null) line.put("tempId", UUID.randomUUID().toString());
        line.put("productId", null);
        line.put("templateId", null);
        line.put("productPartNo", materialNo);
        line.put("productName", PREFIX + "产品");
        line.put("customerPartNo", PREFIX + "CPN_" + sortOrder);
        line.put("customerProductNo", PREFIX + "CPN_" + sortOrder);
        line.put("productAttributeValues", "{}");
        line.put("subtotal", "0");
        line.put("sortOrder", sortOrder);
        line.put("processNos", List.of());
        line.put("componentData", List.of());
        line.put("compositeType", "SIMPLE");
        line.put("seedProcessesFromBase", false);
        line.put("compositeProcesses", List.of());
        line.put("quoteExcelValues", null);
        return line;
    }

    private Response postEmpty(String path) {
        return request().contentType(ContentType.JSON).body(Map.of()).post(path);
    }

    private static long version(Response quotation) {
        Number value = quotation.jsonPath().get("data.userDataVersion");
        assertNotNull(value, "响应缺 data.userDataVersion：" + quotation.asString());
        return value.longValue();
    }

    private static List<Map<String, Object>> lines(Response quotation) {
        List<Map<String, Object>> rows = quotation.jsonPath().getList("data.lineItems");
        assertNotNull(rows, "响应缺 data.lineItems：" + quotation.asString());
        return rows;
    }

    private static Map<String, Object> exactLine(Response quotation, String materialNo, int occurrence) {
        List<Map<String, Object>> matches = lines(quotation).stream()
                .filter(line -> materialNo.equals(String.valueOf(line.get("productPartNo"))))
                .toList();
        assertTrue(matches.size() > occurrence,
                "必须命中料号 " + materialNo + " 的第 " + (occurrence + 1) + " 行，实际=" + matches
                        + "；完整响应=" + quotation.asString());
        return matches.get(occurrence);
    }

    private UUID lineId(UUID quotationId, String materialNo, int offset) {
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery("""
                SELECT id FROM quotation_line_item
                WHERE quotation_id=:qid AND product_part_no_snapshot=:part
                ORDER BY sort_order,id OFFSET :offset LIMIT 1
                """).setParameter("qid", quotationId).setParameter("part", materialNo)
                .setParameter("offset", offset).getResultList();
        assertEquals(1, ids.size(), "报价行必须精确命中，不能 0 行空跑");
        return uuid(ids.get(0));
    }

    private static void assertProfit(Map<String, Object> line, String expected, String message) {
        assertTrue(line.containsKey("profit"), message + "：lineItems[] 缺 profit 键，实际=" + line);
        Object actual = line.get("profit");
        if (expected == null) {
            assertNull(actual, message + "：期望 null，实际=" + actual);
        } else {
            assertTrue(actual instanceof String, message + "：必须是 JSON string，实际类型="
                    + (actual == null ? "null" : actual.getClass().getName()));
            assertEquals(new BigDecimal(expected).setScale(12).toPlainString(), actual, message);
        }
    }

    private static Map<String, Object> amountFingerprint(Response quotation) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("originalAmount", "systemDiscountRate", "finalDiscountRate",
                "totalAmount", "taxRate", "taxAmount")) {
            result.put(key, quotation.jsonPath().get("data." + key));
        }
        result.put("subtotals", lines(quotation).stream().map(line -> line.get("subtotal")).toList());
        return result;
    }

    // ────── candidate/existing + SQL count ──────

    private Response candidateResponse(CustomerRef owner) {
        Response response = request().queryParam("customerId", owner.id())
                .get("/api/cpq/quotations/customer-part-candidates");
        assertEquals(200, response.statusCode(), "候选接口失败：" + response.asString());
        assertNotNull(response.jsonPath().getList("data"), "候选接口缺 data[]");
        return response;
    }

    private static List<Map<String, Object>> candidateRows(Response response, String prefix) {
        List<Map<String, Object>> rows = response.jsonPath().getList("data");
        assertNotNull(rows, "候选接口缺 data[]");
        return rows.stream().filter(row -> String.valueOf(row.get("partNo")).startsWith(prefix)).toList();
    }

    private Response existingResponse(UUID quotationId, String salesPartNo) {
        Response response = request().queryParam("page", 0).queryParam("size", 20)
                .queryParam("salesPartNo", salesPartNo)
                .get("/api/cpq/quotations/" + quotationId + "/existing-products");
        assertEquals(200, response.statusCode(), "已有产品接口失败：" + response.asString());
        return response;
    }

    private static List<Map<String, Object>> existingRows(Response response) {
        List<Map<String, Object>> rows = response.jsonPath().getList("data.content");
        assertNotNull(rows, "已有产品接口缺 data.content[]：" + response.asString());
        return rows;
    }

    private static void assertProfitKeyContract(List<Map<String, Object>> rows, String identityKey, String label) {
        assertFalse(rows.isEmpty(), label + " 返回 0 行，不能执行字段契约断言");
        boolean sawNull = false;
        boolean sawValue = false;
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get(identityKey), label + " 行缺身份字段 " + identityKey + "：" + row);
            assertTrue(row.containsKey("profit"), label + " 的 NULL profit 键也必须显式出现：" + row);
            Object profit = row.get("profit");
            if (profit == null) {
                sawNull = true;
            } else {
                sawValue = true;
                assertTrue(profit instanceof String,
                        label + " 非空 profit 必须是 string，不能是 number：" + row);
            }
        }
        assertTrue(sawNull && sawValue, label + " 必须同时覆盖 NULL 与非空值，实际=" + rows);
    }

    private Statistics statistics() {
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private static long profitQueryCount(Statistics statistics) {
        long total = 0;
        for (String query : statistics.getQueries()) {
            String lower = query.toLowerCase();
            if (lower.contains("ds_quote_material") && lower.contains("profit")
                    && (lower.startsWith("select") || lower.contains(" select "))) {
                long count = statistics.getQueryStatistics(query).getExecutionCount();
                total += count;
                System.out.printf("[T261026_QBE_SQL] executions=%d sql=%s%n", count, query);
            }
        }
        assertTrue(total > 0,
                "N+1 量具未观察到任何同时命中 ds_quote_material + profit 的 SELECT；"
                        + "不得把 0 次当作通过，应改用 statement observer 后再验");
        return total;
    }

    private static void assertNoProfitNPlusOne(String action, long twoRows, long sixRows) {
        System.out.printf("[T261026_QBE_N1] action=%s rows2=%d rows6=%d%n", action, twoRows, sixRows);
        assertTrue(twoRows <= 1, action + "：2 行最多 1 次利润批量查询，实际=" + twoRows);
        assertTrue(sixRows <= 1, action + "：6 行最多 1 次利润批量查询，实际=" + sixRows);
        assertTrue(sixRows <= twoRows,
                action + "：6 行利润查询次数不得大于 2 行，实际 2行=" + twoRows + " 6行=" + sixRows);
    }

    private long saveDraftProfitQueries(int rowCount, String label) {
        UUID quotationId = createQuotation(customer, label);
        List<Map<String, Object>> added = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            String materialNo = PREFIX + label + "_" + i;
            insertMaterial(customer, materialNo, i % 2 == 0 ? null : i + ".250000000000");
            added.add(lineDraft(null, materialNo, i));
        }
        Response before = getQuotation(quotationId);
        Statistics statistics = statistics();
        statistics.clear();
        Response saved = putDraft(quotationId, version(before), added, List.of());
        long queries = profitQueryCount(statistics);
        assertEquals(200, saved.statusCode(), label + " saveDraft 失败：" + saved.asString());
        assertEquals(rowCount, lines(getQuotation(quotationId)).size(),
                label + " 必须实际保存 " + rowCount + " 行，禁止 0 行夹具空跑");
        return queries;
    }

    // ────── DB fixtures ──────

    private CustomerRef createCustomer(String label) {
        UUID id = UUID.randomUUID();
        String customerNo = PREFIX + id.toString().replace("-", "").substring(0, 8);
        assertTrue(customerNo.length() <= 20, "customer.code 夹具不得超过 20 字符：" + customerNo);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO customer
                  (id,name,code,level,accumulated_amount,status,version,created_at,updated_at)
                VALUES (:id,:name,:code,'STANDARD',0,'ACTIVE',0,now(),now())
                """).setParameter("id", id).setParameter("name", PREFIX + label)
                .setParameter("code", customerNo).executeUpdate());
        customerIds.add(id);
        customerNos.add(customerNo);
        return new CustomerRef(id, customerNo);
    }

    private void insertMaterial(CustomerRef owner, String materialNo, String profit) {
        materialNos.add(materialNo);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
                INSERT INTO ds_quote_material
                  (material_no,material_name,customer_no,profit,source,created_at,updated_at)
                VALUES (:part,:name,:customer,CAST(:profit AS numeric),'IMPORT',now(),now())
                """).setParameter("part", materialNo).setParameter("name", PREFIX + "物料")
                .setParameter("customer", owner.code()).setParameter("profit", profit).executeUpdate());
    }

    private void updateMaterialProfit(CustomerRef owner, String materialNo, String profit) {
        QuarkusTransaction.requiringNew().run(() -> {
            int updated = em.createNativeQuery("""
                    UPDATE ds_quote_material SET profit=CAST(:profit AS numeric),updated_at=now()
                    WHERE customer_no=:customer AND material_no=:part
                    """).setParameter("profit", profit).setParameter("customer", owner.code())
                    .setParameter("part", materialNo).executeUpdate();
            assertEquals(1, updated, "基础利润更新必须精确命中 1 行");
        });
    }

    private void seedCandidate(CustomerRef owner, String materialNo, String profit, int index) {
        insertMaterial(owner, materialNo, profit);
        String customerProductNo = PREFIX + "CPN_" + owner.code().substring(owner.code().length() - 4)
                + "_" + index;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                    INSERT INTO material_master
                      (id,material_no,material_name,material_type,created_at,updated_at)
                    VALUES (gen_random_uuid(),:part,:name,'零件',now(),now())
                    """).setParameter("part", materialNo).setParameter("name", PREFIX + "候选产品")
                    .executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO material_customer_map
                      (id,system_type,material_no,customer_no,customer_product_no,created_at,updated_at)
                    VALUES (gen_random_uuid(),'QUOTE',:part,:customer,:cpn,now(),now())
                    """).setParameter("part", materialNo).setParameter("customer", owner.code())
                    .setParameter("cpn", customerProductNo).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO ds_quote_customer_part
                      (customer_no,material_no,customer_part_name,customer_product_no,source,created_at,updated_at)
                    VALUES (:customer,:part,:name,:cpn,'IMPORT',now(),now())
                    """).setParameter("customer", owner.code()).setParameter("part", materialNo)
                    .setParameter("name", PREFIX + "客户产品")
                    .setParameter("cpn", customerProductNo).executeUpdate();
        });
    }

    // ────── import materialization ──────

    private Map<String, Object> awaitImport(UUID recordId) {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        Response last = null;
        while (System.nanoTime() < deadline) {
            last = request().get(
                    "/api/cpq/dataset/quote/quotation-import/" + recordId);
            assertEquals(200, last.statusCode(), "导入轮询失败：" + last.asString());
            Map<String, Object> data = last.jsonPath().getMap("data");
            assertNotNull(data, "导入轮询缺 data：" + last.asString());
            String status = String.valueOf(data.get("status"));
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) return data;
            sleep(250);
        }
        throw new AssertionError("导入 180 秒未到终态，最后响应="
                + (last == null ? "<none>" : last.asString()));
    }

    private void awaitMaterializedLine(UUID quotationId, String materialNo) {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        Response last = null;
        while (System.nanoTime() < deadline) {
            last = request().get(
                    "/api/cpq/quotations/" + quotationId + "/materialize-status");
            assertEquals(200, last.statusCode(), "物化轮询失败：" + last.asString());
            Boolean done = last.jsonPath().getBoolean("data.done");
            if (Boolean.TRUE.equals(done)) {
                List<Map<String, Object>> hits = lines(getQuotation(quotationId)).stream()
                        .filter(line -> materialNo.equals(String.valueOf(line.get("productPartNo"))))
                        .toList();
                assertEquals(1, hits.size(), "物化完成后目标料号必须精确产生 1 行");
                return;
            }
            sleep(250);
        }
        throw new AssertionError("物化 180 秒未完成，最后响应="
                + (last == null ? "<none>" : last.asString()));
    }

    private byte[] quotationImportWorkbook(String materialNo, String profit, String customerNo) throws Exception {
        Path template = task260902Dir().resolve("报价 - 数据导入与表格建表.xlsx");
        assertTrue(Files.isRegularFile(template), "正式报价模板不存在：" + template);
        try (InputStream input = Files.newInputStream(template);
             Workbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            stripMarkerRows(workbook);
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if ("物料".equals(sheet.getSheetName()) || "客户料号".equals(sheet.getSheetName())) {
                    retainFirstDataRow(sheet);
                } else {
                    clearDataRows(sheet);
                }
            }

            Sheet material = workbook.getSheet("物料");
            Row materialRow = requireFirstDataRow(material);
            writeText(material, materialRow, "销售料号", materialNo);
            writeText(material, materialRow, "类型", "零件");
            writeText(material, materialRow, "利润", profit);

            Sheet customerPart = workbook.getSheet("客户料号");
            ensureOptionalHeader(customerPart, "结算单位", "客户料号名称");
            Row customerPartRow = requireFirstDataRow(customerPart);
            writeText(customerPart, customerPartRow, "销售料号", materialNo);
            writeText(customerPart, customerPartRow, "客户编号", customerNo);
            writeIfHeader(customerPart, customerPartRow, "客户产品编号", PREFIX + "IMPORT_CPN");
            writeIfHeader(customerPart, customerPartRow, "客户料号", PREFIX + "IMPORT_CPN");
            writeIfHeader(customerPart, customerPartRow, "客户料号名称", PREFIX + "导入产品");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void assertQuotationImportWorkbook(
            byte[] bytes, String customerNo, String materialNo, String profit) throws Exception {
        assertTrue(bytes.length > 0, "导入 workbook 序列化后不得为空");
        try (InputStream input = new ByteArrayInputStream(bytes);
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet customerPart = workbook.getSheet("客户料号");
            Row customerPartRow = requireFirstDataRow(customerPart);
            assertEquals(customerNo,
                    cellText(customerPartRow.getCell(headerIndex(customerPart, "客户编号"))),
                    "上传前回读：客户料号 Sheet 的客户编号必须是本轮私有客户");
            assertEquals(materialNo,
                    cellText(customerPartRow.getCell(headerIndex(customerPart, "销售料号"))),
                    "上传前回读：客户料号 Sheet 的销售料号必须是本轮料号");

            Sheet material = workbook.getSheet("物料");
            Row materialRow = requireFirstDataRow(material);
            assertEquals(profit,
                    cellText(materialRow.getCell(headerIndex(material, "利润"))),
                    "上传前回读：物料 Sheet 利润必须保留 12 位字符串");
        }
    }

    private static void stripMarkerRows(Workbook workbook) {
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            for (int rowIndex = sheet.getLastRowNum(); rowIndex >= 1; rowIndex--) {
                Row row = sheet.getRow(rowIndex);
                if (!isMarkerRow(row)) continue;
                sheet.removeRow(row);
                if (rowIndex < sheet.getLastRowNum()) {
                    sheet.shiftRows(rowIndex + 1, sheet.getLastRowNum(), -1);
                }
            }
        }
    }

    private static boolean isMarkerRow(Row row) {
        if (row == null) return false;
        boolean any = false;
        for (int i = 0; i < row.getLastCellNum(); i++) {
            String value = cellText(row.getCell(i));
            if (value.isBlank()) continue;
            if (!"轴".equals(value) && !"对比项".equals(value)) return false;
            any = true;
        }
        return any;
    }

    private static void retainFirstDataRow(Sheet sheet) {
        Row seed = requireFirstDataRow(sheet);
        int seedIndex = seed.getRowNum();
        for (int i = sheet.getLastRowNum(); i >= 1; i--) {
            if (i != seedIndex && sheet.getRow(i) != null) sheet.removeRow(sheet.getRow(i));
        }
        if (seedIndex == 1) return;
        Row target = sheet.getRow(1);
        if (target == null) target = sheet.createRow(1);
        copyValues(seed, target);
        sheet.removeRow(seed);
    }

    private static void clearDataRows(Sheet sheet) {
        for (int i = sheet.getLastRowNum(); i >= 1; i--) {
            if (sheet.getRow(i) != null) sheet.removeRow(sheet.getRow(i));
        }
    }

    private static Row requireFirstDataRow(Sheet sheet) {
        assertNotNull(sheet, "正式模板缺 Sheet");
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if (!cellText(row.getCell(c)).isBlank()) return row;
            }
        }
        throw new AssertionError(sheet.getSheetName() + " 没有可复用的数据行");
    }

    private static void copyValues(Row source, Row target) {
        for (int i = 0; i < source.getLastCellNum(); i++) {
            Cell src = source.getCell(i);
            Cell dst = target.getCell(i);
            if (dst == null) dst = target.createCell(i);
            if (src == null || src.getCellType() == CellType.BLANK) {
                dst.setBlank();
            } else if (src.getCellType() == CellType.NUMERIC) {
                dst.setCellValue(src.getNumericCellValue());
            } else if (src.getCellType() == CellType.BOOLEAN) {
                dst.setCellValue(src.getBooleanCellValue());
            } else {
                dst.setCellValue(cellText(src));
            }
        }
    }

    private static void writeText(Sheet sheet, Row row, String header, String value) {
        int index = headerIndex(sheet, header);
        Cell cell = row.getCell(index);
        if (cell == null) cell = row.createCell(index);
        cell.setBlank();
        if (value != null) cell.setCellValue(value);
    }

    private static void writeIfHeader(Sheet sheet, Row row, String header, String value) {
        int index = headerIndexOrMinusOne(sheet, header);
        if (index < 0) return;
        Cell cell = row.getCell(index);
        if (cell == null) cell = row.createCell(index);
        cell.setCellValue(value);
    }

    private static void ensureOptionalHeader(Sheet sheet, String headerName, String styleSourceHeader) {
        if (headerIndexOrMinusOne(sheet, headerName) >= 0) return;
        Row header = sheet.getRow(0);
        int appendAt = Math.max(0, header.getLastCellNum());
        Cell added = header.createCell(appendAt, CellType.STRING);
        added.setCellValue(headerName);
        added.setCellStyle(header.getCell(headerIndex(sheet, styleSourceHeader)).getCellStyle());
    }

    private static int headerIndex(Sheet sheet, String wanted) {
        int index = headerIndexOrMinusOne(sheet, wanted);
        if (index >= 0) return index;
        throw new AssertionError(sheet.getSheetName() + " 缺表头「" + wanted + "」");
    }

    private static int headerIndexOrMinusOne(Sheet sheet, String wanted) {
        assertNotNull(sheet, "正式模板缺 Sheet");
        Row header = sheet.getRow(0);
        assertNotNull(header, sheet.getSheetName() + " 缺表头行");
        for (int i = 0; i < header.getLastCellNum(); i++) {
            if (wanted.equals(cellText(header.getCell(i)))) return i;
        }
        return -1;
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    // ────── generic DB helpers ──────

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private Object scalar(String sql, Object... params) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i += 2) {
            query.setParameter(String.valueOf(params[i]), params[i + 1]);
        }
        List<?> rows = query.getResultList();
        assertEquals(1, rows.size(), "查询必须精确返回 1 行：" + sql + " actual=" + rows.size());
        return rows.get(0);
    }

    private boolean tableExists(String table) {
        return count("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                + "AND table_name='" + table.replace("'", "''") + "'") > 0;
    }

    private boolean columnExists(String table, String column) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
                + "AND table_name='" + table.replace("'", "''") + "' AND column_name='"
                + column.replace("'", "''") + "'") > 0;
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }

    private static Path task260902Dir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("dev-docs")
                    .resolve("task-260902-报价与核价建表与导入方案新规范");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("找不到 task-260902 正式导入模板目录，cwd="
                + Path.of("").toAbsolutePath());
    }

    private record CustomerRef(UUID id, String code) {}
}
