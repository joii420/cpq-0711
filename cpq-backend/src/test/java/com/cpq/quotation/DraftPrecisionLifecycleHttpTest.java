package com.cpq.quotation;

import com.cpq.component.entity.Component;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.product.entity.Product;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(DraftPrecisionLifecycleHttpTest.RbacOffProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DraftPrecisionLifecycleHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALUE_A = "100.123456789012";
    private static final String VALUE_B = "200.000000000001";
    private static final String HEADER_TOTAL = "300.123456789000";
    private static final String PRECISION_SENTINEL = "98765431.123456789012";
    private static final String TAB_NAME = "TC-058 Precision";

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "cpq.security.rbac.enabled", "false",
                "quarkus.scheduler.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Fixture fixture : fixtures) {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id IN "
                        + "(SELECT id FROM costing_order WHERE quotation_id=:qid)")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id=:qid")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id=:qid")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id IN (:quote,:cost)")
                    .setParameter("quote", fixture.quoteTemplateId())
                    .setParameter("cost", fixture.costingTemplateId()).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id IN (:quote,:cost)")
                    .setParameter("quote", fixture.quoteTemplateId())
                    .setParameter("cost", fixture.costingTemplateId()).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id IN (:quote,:cost)")
                    .setParameter("quote", fixture.quoteTemplateId())
                    .setParameter("cost", fixture.costingTemplateId()).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id IN (:normal,:excel)")
                    .setParameter("normal", fixture.componentId())
                    .setParameter("excel", fixture.costingExcelComponentId()).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id IN (:c2,:c3)")
                    .setParameter("c2", fixture.secondaryInputComponentId())
                    .setParameter("c3", fixture.secondaryFormulaComponentId()).executeUpdate();
                em.createNativeQuery("DELETE FROM product WHERE id IN (:a,:b)")
                    .setParameter("a", fixture.productIds().get(0))
                    .setParameter("b", fixture.productIds().get(1)).executeUpdate();
                em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                    .setParameter("id", fixture.customerId()).executeUpdate();
            });
        }
        fixtures.clear();
    }

    @Test
    @Order(1)
    void tc058_canonicalDraftSaveEnsureRefreshAndReopenRemainExactAndStable() throws Exception {
        Fixture fixture = createFixture();

        Response saved = putCanonicalDraft(fixture);
        assertEquals(200, saved.statusCode(), saved.asString());
        assertResponseDecimals(saved, fixture);
        CanonicalState afterSave = canonicalState(fixture);
        assertCanonicalState(afterSave, fixture);

        assertPhysicalReadOnly(fixture, afterSave.physicalFingerprint(), "GET after save",
            RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId()));

        Response ensuredCard = postEmpty(fixture.quotationId(), "ensure-card-values");
        assertEquals(200, ensuredCard.statusCode(), ensuredCard.asString());
        assertResponseDecimals(ensuredCard, fixture);
        CanonicalState afterCard = canonicalState(fixture);
        assertCanonicalState(afterCard, fixture);
        assertCardValues(afterCard, fixture, false);

        Response ensuredExcel = postEmpty(fixture.quotationId(), "ensure-excel-values");
        assertEquals(200, ensuredExcel.statusCode(), ensuredExcel.asString());
        assertResponseDecimals(ensuredExcel, fixture);
        CanonicalState afterExcel = canonicalState(fixture);
        assertCanonicalState(afterExcel, fixture);
        assertCardValues(afterExcel, fixture);

        Response refreshed = postEmpty(fixture.quotationId(), "refresh-card-snapshot");
        assertEquals(200, refreshed.statusCode(), refreshed.asString());
        CanonicalState afterRefresh = canonicalState(fixture);
        assertCanonicalState(afterRefresh, fixture);
        assertCardValues(afterRefresh, fixture);
        assertEquals(afterExcel.logicalFingerprint(), afterRefresh.logicalFingerprint(),
            "TC-058 explicit refresh must not drift decimal/raw values, ids, or row counts; response="
                + refreshed.asString());

        Response reopened = RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId());
        assertEquals(200, reopened.statusCode(), reopened.asString());
        assertResponseDecimals(reopened, fixture);
        CanonicalState afterReopen = canonicalState(fixture);
        assertEquals(afterRefresh, afterReopen,
            "TC-058 reopen must be physically read-only, including xmin/timestamps/full hashes");

        Response secondRefresh = postEmpty(fixture.quotationId(), "refresh-card-snapshot");
        assertEquals(200, secondRefresh.statusCode(), secondRefresh.asString());
        CanonicalState afterSecondRefresh = canonicalState(fixture);
        assertCanonicalState(afterSecondRefresh, fixture);
        assertCardValues(afterSecondRefresh, fixture);
        assertEquals(afterRefresh.logicalFingerprint(), afterSecondRefresh.logicalFingerprint(),
            "TC-058 repeated explicit refresh must be logically idempotent");
        assertEquals(afterRefresh.lineIds(), afterSecondRefresh.lineIds(),
            "TC-058 repeated refresh must not replace line ids");
    }

    @Test
    @Order(2)
    void tc058_refreshFailureInjectionRollsBackEveryLineAndHeaderAtomically() {
        Fixture fixture = createFixture();
        assertEquals(200, putCanonicalDraft(fixture).statusCode());
        assertEquals(200, postEmpty(fixture.quotationId(), "ensure-card-values").statusCode());
        assertEquals(200, postEmpty(fixture.quotationId(), "ensure-excel-values").statusCode());

        List<UUID> productionOrder = lineIdsInProductionQueryOrder(fixture.quotationId());
        assertEquals(2, productionOrder.size(), "TC-058 failure fixture requires two lines");
        UUID blockedLineId = productionOrder.get(productionOrder.size() - 1);
        UUID precedingLineId = productionOrder.get(0);

        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_card_values="
                    + "jsonb_build_object('tabs',jsonb_build_array(),'marker','before-refresh') "
                    + "WHERE quotation_id=:qid")
            .setParameter("qid", fixture.quotationId()).executeUpdate());
        AtomicFingerprint before = atomicFingerprint(fixture.quotationId());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String triggerName = "tc058_trg_" + nonce;
        String functionName = "tc058_fn_" + nonce;
        Response response = null;
        try {
            installFailureTrigger(triggerName, functionName, blockedLineId);
            response = postEmpty(fixture.quotationId(), "refresh-card-snapshot");
            assertTrue(response.statusCode() < 500,
                "TC-058 injected per-line failure must not escape as HTTP 500: " + response.asString());
            AtomicFingerprint after = atomicFingerprint(fixture.quotationId());
            assertEquals(before, after,
                "TC-058 refresh must be all-or-nothing. A prior line was allowed to commit before the "
                    + "injected later-line failure. precedingLine=" + precedingLineId
                    + ", blockedLine=" + blockedLineId + ", http=" + response.statusCode()
                    + ", body=" + response.asString() + ", before=" + before + ", after=" + after);
        } finally {
            dropFailureTrigger(triggerName, functionName);
            assertTriggerArtifactsAbsent(triggerName, functionName);
        }
    }

    @Test
    @Order(3)
    void tc078_formulaDryRunSqlCountRemainsConstantWhenRowsDouble() throws Exception {
        int n = 32;
        Fixture fixture = createFixture();
        UUID nLineId = fixture.lineIds().get(0);
        UUID twiceNLineId = fixture.lineIds().get(1);

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE template_component_snapshot SET tab_type='BOM',"
                    + "bom_recursive_expand=true WHERE template_id=:tid AND component_id=:cid")
                .setParameter("tid", fixture.quoteTemplateId())
                .setParameter("cid", fixture.componentId()).executeUpdate();
            replaceSnapshotRows(nLineId, snapshotRows(n));
            replaceSnapshotRows(twiceNLineId, snapshotRows(2 * n));
        });

        assertDryRunResponse(dryRunToken(fixture.componentId(), nLineId), n);
        assertDryRunResponse(dryRunToken(fixture.componentId(), twiceNLineId), 2 * n);

        Statistics statistics = em.getEntityManagerFactory()
            .unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        componentDriverService.evictForLineItem(nLineId);
        componentDriverService.evictForLineItem(twiceNLineId);
        statistics.clear();
        Response nResponse = dryRunToken(fixture.componentId(), nLineId);
        long nSql = statistics.getPrepareStatementCount();
        assertDryRunResponse(nResponse, n);

        componentDriverService.evictForLineItem(nLineId);
        componentDriverService.evictForLineItem(twiceNLineId);
        statistics.clear();
        Response twiceNResponse = dryRunToken(fixture.componentId(), twiceNLineId);
        long twiceNSql = statistics.getPrepareStatementCount();
        assertDryRunResponse(twiceNResponse, 2 * n);

        System.out.printf("[TC-078] N=%d sql=%d 2N=%d sql=%d%n", n, nSql, 2 * n, twiceNSql);
        assertTrue(nSql > 0 && twiceNSql > 0,
            "TC-078 Hibernate statistics must observe the real HTTP persistence path");
        assertEquals(nSql, twiceNSql,
            "TC-078 formula-chain SQL must be constant when row count doubles");
    }

    @Test
    @Order(4)
    void tc079_decimalStringSnapshotGrowthDoesNotClipPaginateOrTimeout() throws Exception {
        int rowCount = 48;
        Fixture fixture = createFixture();
        ObjectNode numericSnapshot = quoteCardPayload(fixture.componentId(), rowCount, false);
        ObjectNode stringSnapshot = quoteCardPayload(fixture.componentId(), rowCount, true);
        assertEquals(canonicalSha256(stringSnapshot),
            canonicalSha256(decimalTokensAsStrings(numericSnapshot)),
            "TC-079 size comparison inputs must represent the same logical snapshot");

        int numberBytes = MAPPER.writeValueAsBytes(numericSnapshot).length;
        int stringBytes = MAPPER.writeValueAsBytes(stringSnapshot).length;
        int delta = stringBytes - numberBytes;
        BigDecimal ratio = new BigDecimal(stringBytes)
            .divide(new BigDecimal(numberBytes), 6, RoundingMode.HALF_UP);
        System.out.printf("[TC-079] numberBytes=%d stringBytes=%d delta=%d ratio=%s%n",
            numberBytes, stringBytes, delta, ratio.toPlainString());
        assertTrue(delta > 0, "TC-079 decimal strings should record their expected quote overhead");

        UUID selectedLineId = fixture.lineIds().get(0);
        String persistedJson = MAPPER.writeValueAsString(stringSnapshot);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_card_values=CAST(:json AS jsonb) WHERE id=:id")
            .setParameter("json", persistedJson)
            .setParameter("id", selectedLineId).executeUpdate());

        long startedAt = System.nanoTime();
        Response response = RestAssured.given()
            .config(RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", 30_000)
                .setParam("http.socket.timeout", 30_000)))
            .header("Accept-Encoding", "identity")
            .get("/api/cpq/quotations/" + fixture.quotationId());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        byte[] responseBytes = response.asByteArray();
        System.out.printf("[TC-079] responseBytes=%d elapsedMs=%d%n", responseBytes.length, elapsedMs);

        assertFalse(Set.of(206, 413, 504).contains(response.statusCode()),
            "TC-079 response must not be partial, rejected for size, or timed out");
        assertEquals(200, response.statusCode(), response.asString());
        assertNull(response.header("Content-Range"), "TC-079 full quotation response must not be ranged");

        JsonNode responseData = MAPPER.readTree(responseBytes).path("data");
        JsonNode responseLines = responseData.path("lineItems");
        assertTrue(responseLines.isArray(), response.asString());
        Set<UUID> responseLineIds = new HashSet<>();
        JsonNode selectedLine = null;
        for (JsonNode line : responseLines) {
            UUID lineId = UUID.fromString(line.path("id").asText());
            responseLineIds.add(lineId);
            if (selectedLineId.equals(lineId)) selectedLine = line;
        }
        Set<UUID> databaseLineIds = QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> ids = em.createNativeQuery(
                    "SELECT id FROM quotation_line_item WHERE quotation_id=:qid")
                .setParameter("qid", fixture.quotationId()).getResultList();
            Set<UUID> result = new HashSet<>();
            ids.forEach(id -> result.add(uuid(id)));
            return result;
        });
        assertEquals(databaseLineIds, responseLineIds,
            "TC-079 quotation GET must return every persisted line without pagination or clipping");
        assertNotNull(selectedLine, "TC-079 persisted snapshot line must be returned");
        JsonNode quoteCardValueNode = selectedLine.path("quoteCardValues");
        assertTrue(quoteCardValueNode.isTextual(),
            "TC-079 quoteCardValues outer transport contract must remain a JSON string");

        JsonNode returnedSnapshot = MAPPER.readTree(quoteCardValueNode.asText());
        assertEquals(canonicalSha256(stringSnapshot), canonicalSha256(returnedSnapshot),
            "TC-079 nested quoteCardValues must round-trip completely");
        JsonNode tab = returnedSnapshot.path("tabs").path(0);
        assertEquals(1, returnedSnapshot.path("tabs").size());
        assertEquals(rowCount, tab.path("baseRows").size());
        assertEquals(rowCount, tab.path("editRows").size());
        assertEquals(rowCount, tab.path("formulaResults").size());
        assertEquals(rowCount, tab.path("resolvedRows").size());
        assertPrecisionSentinelIsTextual(returnedSnapshot);
    }

    @Test
    @Order(5)
    void inputNumberRoundTripsVerbatimAndFormulaNumericTokenIsAtomic400() throws Exception {
        Fixture fixture = createFixture();
        String rowData = "[{\"rowKey\":\"R0\",\"项次\":1,\"输入单价\":\"1.2300\","
                + "\"公式金额\":\"0.410000001\",\"amount\":\"" + VALUE_A + "\"}]";
        String body = singleLineDraft(fixture, rowData);

        Response saved = RestAssured.given().contentType(ContentType.JSON).body(body)
                .put("/api/cpq/quotations/" + fixture.quotationId() + "/draft");
        assertEquals(200, saved.statusCode(), saved.asString());
        String stored = componentRowData(fixture.quotationId());
        assertTrue(stored.contains("\"项次\": 1") || stored.contains("\"项次\":1"), stored);
        assertTrue(stored.contains("\"输入单价\": \"1.2300\"")
                || stored.contains("\"输入单价\":\"1.2300\""), stored);
        Response reopened = RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId());
        assertEquals(200, reopened.statusCode(), reopened.asString());
        assertTrue(reopened.asString().contains("1.2300"), reopened.asString());

        String before = componentFingerprint(fixture.quotationId());
        String invalid = singleLineDraft(fixture, rowData.replace(
                "\"公式金额\":\"0.410000001\"", "\"公式金额\":0.410000001"));
        Response rejected = RestAssured.given().contentType(ContentType.JSON).body(invalid)
                .put("/api/cpq/quotations/" + fixture.quotationId() + "/draft");
        assertEquals(400, rejected.statusCode(), rejected.asString());
        assertTrue(rejected.asString().contains("公式金额"), rejected.asString());
        assertEquals(before, componentFingerprint(fixture.quotationId()));
    }

    @Test
    @Order(6)
    void frozenMetadataSqlIsConstantForT1T2C1C2C3WhenLineCountDoubles() {
        Fixture fixture = createFixture();
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        statistics.clear();
        Response n = RestAssured.given().contentType(ContentType.JSON)
                .body(classificationDraft(fixture, 2))
                .put("/api/cpq/quotations/" + fixture.quotationId() + "/draft");
        long nSql = statistics.getPrepareStatementCount();
        assertEquals(400, n.statusCode(), n.asString());
        assertTrue(n.asString().contains("lineItems[1].componentData[1].rowData[0].公式值"), n.asString());

        statistics.clear();
        Response twiceN = RestAssured.given().contentType(ContentType.JSON)
                .body(classificationDraft(fixture, 4))
                .put("/api/cpq/quotations/" + fixture.quotationId() + "/draft");
        long twiceNSql = statistics.getPrepareStatementCount();
        assertEquals(400, twiceN.statusCode(), twiceN.asString());
        assertTrue(twiceN.asString().contains("lineItems[1].componentData[1].rowData[0].公式值"), twiceN.asString());

        System.out.printf("[TC-0811-META] T1/T2 C1/C2/C3 N=2 sql=%d 2N=4 sql=%d%n", nSql, twiceNSql);
        assertTrue(nSql > 0);
        assertEquals(nSql, twiceNSql, "批量冻结元数据 SQL 不得随产品行/组件引用数增长");
    }

    private void replaceSnapshotRows(UUID lineItemId, String rowsJson) {
        em.createNativeQuery("UPDATE quotation_line_component_data SET snapshot_rows=CAST(:rows AS jsonb) "
                + "WHERE line_item_id=:lineId")
            .setParameter("rows", rowsJson)
            .setParameter("lineId", lineItemId).executeUpdate();
    }

    private static Response dryRunToken(UUID componentId, UUID lineItemId) {
        String body = "{\"lineItemId\":\"" + lineItemId + "\","
            + "\"tokens\":[{\"type\":\"field\",\"value\":\"amount\"}],"
            + "\"selfRowKeyFields\":[\"rowKey\"]}";
        return RestAssured.given().contentType(ContentType.JSON).body(body)
            .post("/api/cpq/components/" + componentId + "/dry-run-token");
    }

    private static void assertDryRunResponse(Response response, int expectedRows) throws Exception {
        assertEquals(200, response.statusCode(), response.asString());
        JsonNode data = MAPPER.readTree(response.asString()).path("data");
        assertTrue(data.path("errors").isArray() && data.path("errors").isEmpty(), response.asString());
        JsonNode rows = data.path("rows");
        assertEquals(expectedRows, rows.size(), response.asString());
        assertEquals("R-0", rows.path(0).path("rowKey").asText(), response.asString());
        assertTrue(rows.path(0).path("value").isTextual(), response.asString());
        assertEquals(0, new BigDecimal("100.000000000000")
            .compareTo(new BigDecimal(rows.path(0).path("value").asText())), response.asString());
        assertEquals("R-" + (expectedRows - 1),
            rows.path(expectedRows - 1).path("rowKey").asText(), response.asString());
        assertTrue(rows.path(expectedRows - 1).path("value").isTextual(), response.asString());
        assertEquals(0, new BigDecimal("100.000000000000").add(new BigDecimal(expectedRows - 1))
            .compareTo(new BigDecimal(rows.path(expectedRows - 1).path("value").asText())),
            response.asString());
    }

    private static String snapshotRows(int count) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < count; i++) {
            ObjectNode snapshotRow = rows.addObject();
            ObjectNode driverRow = snapshotRow.putObject("driverRow");
            driverRow.put("rowKey", "R-" + i);
            driverRow.put("amount", new BigDecimal("100.000000000000").add(new BigDecimal(i)).toPlainString());
            snapshotRow.putObject("basicDataValues");
        }
        return rows.toString();
    }

    private static ObjectNode quoteCardPayload(UUID componentId, int rowCount, boolean textualDecimals) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode tab = root.putArray("tabs").addObject();
        tab.put("componentId", componentId.toString());
        ArrayNode baseRows = tab.putArray("baseRows");
        ArrayNode editRows = tab.putArray("editRows");
        ArrayNode formulaResults = tab.putArray("formulaResults");
        ArrayNode resolvedRows = tab.putArray("resolvedRows");
        for (int i = 0; i < rowCount; i++) {
            String value = i == 0
                ? PRECISION_SENTINEL
                : new BigDecimal("1000.000000000000").add(new BigDecimal(i)).toPlainString();
            addDecimalRow(baseRows, i, value, textualDecimals, false);
            addDecimalRow(editRows, i, value, textualDecimals, false);
            addDecimalRow(formulaResults, i, value, textualDecimals, true);
            addDecimalRow(resolvedRows, i, value, textualDecimals, false);
        }
        putDecimal(tab, "subtotal", PRECISION_SENTINEL, textualDecimals);
        putDecimal(tab.putObject("subtotalByColumn"), "amount", PRECISION_SENTINEL, textualDecimals);
        return root;
    }

    private static void addDecimalRow(ArrayNode rows, int index, String value,
                                      boolean textualDecimals, boolean valuesWrapper) {
        ObjectNode row = rows.addObject();
        row.put("rowKey", "R-" + index);
        putDecimal(valuesWrapper ? row.putObject("values") : row,
            "amount", value, textualDecimals);
    }

    private static void putDecimal(ObjectNode target, String field, String value, boolean textual) {
        if (textual) target.put(field, value);
        else target.put(field, new BigDecimal(value));
    }

    private static void assertPrecisionSentinelIsTextual(JsonNode snapshot) {
        List<JsonNode> decimalNodes = new ArrayList<>(snapshot.findValues("amount"));
        decimalNodes.addAll(snapshot.findValues("subtotal"));
        assertTrue(decimalNodes.stream().anyMatch(
            node -> node.isTextual() && PRECISION_SENTINEL.equals(node.asText())),
            "TC-079 exact 12-digit sentinel must survive nested snapshot transport");
        assertTrue(decimalNodes.stream().allMatch(JsonNode::isTextual),
            "TC-079 persisted precision leaves must all be decimal strings");
        assertTrue(decimalNodes.stream().noneMatch(JsonNode::isNumber),
            "TC-079 persisted precision leaves must contain no numeric token");
    }

    private static String canonicalSha256(JsonNode value) throws Exception {
        byte[] canonicalBytes = MAPPER.writeValueAsBytes(canonicalize(value));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
    }

    private static JsonNode decimalTokensAsStrings(JsonNode value) {
        if (value.isNumber()) {
            return MAPPER.getNodeFactory().textNode(value.decimalValue().toPlainString());
        }
        if (value.isObject()) {
            ObjectNode object = MAPPER.createObjectNode();
            value.fields().forEachRemaining(
                entry -> object.set(entry.getKey(), decimalTokensAsStrings(entry.getValue())));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            value.forEach(child -> array.add(decimalTokensAsStrings(child)));
            return array;
        }
        return value.deepCopy();
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            ObjectNode sorted = MAPPER.createObjectNode();
            fields.forEach((key, child) -> sorted.set(key, canonicalize(child)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            value.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return value.deepCopy();
    }

    private Fixture createFixture() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO customer(id,code,name) VALUES (:id,:code,:name)")
                .setParameter("id", customerId)
                .setParameter("code", "TC058-" + customerId.toString().substring(0, 8))
                .setParameter("name", "TC-058 precision customer").executeUpdate();

            Component component = new Component();
            component.name = "TC-058 precision component";
            component.code = "TC058-" + UUID.randomUUID().toString().substring(0, 8);
            component.fields = fieldsJson();
            component.formulas = "[]";
            component.rowKeyFields = "[\"rowKey\"]";
            component.persist();

            Component costingExcelComponent = new Component();
            costingExcelComponent.name = "TC-058 costing Excel component";
            costingExcelComponent.code = "TC058-EXCEL-" + UUID.randomUUID().toString().substring(0, 8);
            costingExcelComponent.componentType = "EXCEL";
            costingExcelComponent.fields = "[]";
            costingExcelComponent.formulas = "[]";
            costingExcelComponent.excelColumns = "[{\"col_key\":\"amount\",\"title\":\"Amount\","
                + "\"source_type\":\"PRODUCT_ATTRIBUTE\",\"field_key\":\"amount\",\"hidden\":false}]";
            costingExcelComponent.persist();

            Component secondaryInput = new Component();
            secondaryInput.name = "TC-0811 secondary input";
            secondaryInput.code = "TC0811-C2-" + UUID.randomUUID().toString().substring(0, 8);
            secondaryInput.fields = "[{\"name\":\"输入值\",\"field_type\":\"INPUT_NUMBER\"}]";
            secondaryInput.formulas = "[]";
            secondaryInput.persist();

            Component secondaryFormula = new Component();
            secondaryFormula.name = "TC-0811 secondary formula";
            secondaryFormula.code = "TC0811-C3-" + UUID.randomUUID().toString().substring(0, 8);
            secondaryFormula.fields = "[{\"name\":\"公式值\",\"field_type\":\"FORMULA\"}]";
            secondaryFormula.formulas = "[]";
            secondaryFormula.persist();

            Template quoteTemplate = createTemplate("QUOTATION", component, null);
            Template costingTemplate = createTemplate("COSTING", component, costingExcelComponent);
            addFrozenTab(costingTemplate, secondaryInput, 2);
            addFrozenTab(costingTemplate, secondaryFormula, 3);

            List<UUID> productIds = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Product product = new Product();
                product.name = "TC-058 product " + i;
                product.partNo = "TC058-P" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
                product.category = "TEST";
                product.specification = "precision fixture";
                product.persist();
                productIds.add(product.id);
            }

            Quotation quotation = new Quotation();
            quotation.quotationNumber = "TC058-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.name = "TC-058 canonical lifecycle";
            quotation.salesRepId = firstUserId();
            quotation.status = "DRAFT";
            quotation.customerTemplateId = quoteTemplate.id;
            quotation.costingCardTemplateId = costingTemplate.id;
            quotation.finalDiscountRate = new BigDecimal("100.00");
            quotation.persist();

            List<UUID> lineIds = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String value = i == 0 ? VALUE_A : VALUE_B;
                QuotationLineItem line = new QuotationLineItem();
                line.quotationId = quotation.id;
                line.productId = productIds.get(i);
                line.templateId = quoteTemplate.id;
                line.productNameSnapshot = "TC-058 product " + i;
                line.productPartNoSnapshot = "TC058-LINE-" + i;
                line.productAttributeValues = productAttributes(value, i);
                line.subtotal = new BigDecimal(value);
                line.sortOrder = i;
                line.persist();
                lineIds.add(line.id);

                QuotationLineComponentData data = new QuotationLineComponentData();
                data.lineItemId = line.id;
                data.componentId = component.id;
                data.tabName = TAB_NAME;
                data.rowData = rowData(value, i);
                data.snapshotRows = snapshotRows(value, i);
                data.subtotal = new BigDecimal(value);
                data.sortOrder = 0;
                data.persist();
            }

            return new Fixture(quotation.id, customerId, component.id, costingExcelComponent.id,
                secondaryInput.id, secondaryFormula.id,
                quoteTemplate.id, costingTemplate.id, productIds, lineIds);
        });
        fixtures.add(fixture);
        return fixture;
    }

    private Template createTemplate(String kind, Component component, Component excelComponent) {
        Template template = new Template();
        template.templateSeriesId = UUID.randomUUID();
        template.name = "TC-058 " + kind + " template";
        template.templateKind = kind;
        template.status = "PUBLISHED";
        template.productAttributes = "[{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\"}]";
        template.componentsSnapshot = componentSnapshotJson(component, excelComponent);
        template.sqlViewsSnapshot = "{}";
        template.templateSqlViewsSnapshot = "{}";
        template.excelViewConfig = excelComponent == null
            ? "[]"
            : "{\"version\":2,\"excel_component_id\":\"" + excelComponent.id
                + "\",\"column_overrides\":[]}";
        template.persist();

        TemplateComponent mounted = new TemplateComponent();
        mounted.templateId = template.id;
        mounted.componentId = component.id;
        mounted.tabName = TAB_NAME;
        mounted.sortOrder = 0;
        mounted.persist();

        TemplateComponentSnapshot snapshot = new TemplateComponentSnapshot();
        snapshot.templateId = template.id;
        snapshot.templateComponentId = mounted.id;
        snapshot.componentId = component.id;
        snapshot.sortOrder = 0;
        snapshot.tabName = TAB_NAME;
        snapshot.componentName = component.name;
        snapshot.componentCode = component.code;
        snapshot.componentType = "NORMAL";
        snapshot.fields = component.fields;
        snapshot.formulas = component.formulas;
        snapshot.rowKeyFields = component.rowKeyFields;
        snapshot.persist();

        if (excelComponent != null) {
            TemplateComponent excelMounted = new TemplateComponent();
            excelMounted.templateId = template.id;
            excelMounted.componentId = excelComponent.id;
            excelMounted.tabName = "TC-058 Costing Excel";
            excelMounted.sortOrder = 1;
            excelMounted.persist();

            TemplateComponentSnapshot excelSnapshot = new TemplateComponentSnapshot();
            excelSnapshot.templateId = template.id;
            excelSnapshot.templateComponentId = excelMounted.id;
            excelSnapshot.componentId = excelComponent.id;
            excelSnapshot.sortOrder = 1;
            excelSnapshot.tabName = excelMounted.tabName;
            excelSnapshot.componentName = excelComponent.name;
            excelSnapshot.componentCode = excelComponent.code;
            excelSnapshot.componentType = "EXCEL";
            excelSnapshot.fields = "[]";
            excelSnapshot.formulas = "[]";
            excelSnapshot.excelColumns = excelComponent.excelColumns;
            excelSnapshot.persist();
        }
        return template;
    }

    private void addFrozenTab(Template template, Component component, int sortOrder) {
        TemplateComponent mounted = new TemplateComponent();
        mounted.templateId = template.id;
        mounted.componentId = component.id;
        mounted.tabName = component.name;
        mounted.sortOrder = sortOrder;
        mounted.persist();
        TemplateComponentSnapshot snapshot = new TemplateComponentSnapshot();
        snapshot.templateId = template.id;
        snapshot.templateComponentId = mounted.id;
        snapshot.componentId = component.id;
        snapshot.sortOrder = sortOrder;
        snapshot.tabName = component.name;
        snapshot.componentName = component.name;
        snapshot.componentCode = component.code;
        snapshot.componentType = "NORMAL";
        snapshot.fields = component.fields;
        snapshot.formulas = component.formulas;
        snapshot.persist();
        try {
            ArrayNode components = (ArrayNode) MAPPER.readTree(template.componentsSnapshot);
            ObjectNode json = components.addObject();
            json.put("id", UUID.randomUUID().toString());
            json.put("componentId", component.id.toString());
            json.put("componentName", component.name);
            json.put("componentCode", component.code);
            json.put("componentType", "NORMAL");
            json.put("tabName", component.name);
            json.put("sortOrder", sortOrder);
            json.set("fields", MAPPER.readTree(component.fields));
            json.set("formulas", MAPPER.readTree(component.formulas));
            json.set("formula_assignments", MAPPER.createObjectNode());
            template.componentsSnapshot = MAPPER.writeValueAsString(components);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Response putCanonicalDraft(Fixture fixture) {
        String body = "{\"name\":\"TC-058 canonical lifecycle\","
            + "\"finalDiscountRate\":\"100.00\",\"lineItems\":["
            + linePayload(fixture, 0, VALUE_A) + ","
            + linePayload(fixture, 1, VALUE_B) + "]}";
        return RestAssured.given().contentType(ContentType.JSON).body(body)
            .put("/api/cpq/quotations/" + fixture.quotationId() + "/draft");
    }

    private String linePayload(Fixture fixture, int index, String value) {
        return "{\"id\":\"" + fixture.lineIds().get(index) + "\","
            + "\"productId\":\"" + fixture.productIds().get(index) + "\","
            + "\"templateId\":\"" + fixture.quoteTemplateId() + "\","
            + "\"productPartNo\":\"TC058-LINE-" + index + "\","
            + "\"productName\":\"TC-058 product " + index + "\","
            + "\"productAttributeValues\":" + jsonString(productAttributes(value, index)) + ","
            + "\"subtotal\":\"" + value + "\",\"sortOrder\":" + index + ","
            + "\"compositeType\":\"SIMPLE\",\"annualVolume\":1,"
            + "\"discountSource\":\"TC058\",\"discountBaseAmount\":\"" + value + "\","
            + "\"discountRateApplied\":\"100.00\",\"lineDiscountAmount\":\"0.000000000000\","
            + "\"lineUnitPrice\":\"" + value + "\",\"lineFinalPrice\":\"" + value + "\","
            + "\"lineTotalAmount\":\"" + value + "\","
            + "\"quoteExcelValues\":" + jsonString("{\"rows\":[{\"amount\":\"" + value + "\"}]}") + ","
            + "\"componentData\":[{\"componentId\":\"" + fixture.componentId() + "\","
            + "\"tabName\":\"" + TAB_NAME + "\",\"rowData\":"
            + jsonString(rowData(value, index)) + ",\"subtotal\":\"" + value
            + "\",\"sortOrder\":0}]}";
    }

    private void assertResponseDecimals(Response response, Fixture fixture) throws Exception {
        JsonNode json = MAPPER.readTree(response.asString()).path("data");
        assertTrue(json.path("finalDiscountRate").isTextual(), response.asString());
        JsonNode lines = json.path("lineItems");
        assertTrue(lines.isArray() && lines.size() == 2, response.asString());
        Map<String, String> expected = expectedByLine(fixture);
        for (JsonNode line : lines) {
            String value = expected.get(line.path("id").asText());
            assertNotNull(value, response.asString());
            for (String field : List.of("subtotal", "discountBaseAmount", "lineDiscountAmount",
                    "lineUnitPrice", "lineFinalPrice", "lineTotalAmount")) {
                JsonNode node = line.path(field);
                assertTrue(node.isTextual() || node.isNull(), field + " must be string/null: " + response.asString());
            }
            assertEquals(value, line.path("subtotal").asText(), response.asString());
            assertEquals(value, line.path("lineTotalAmount").asText(), response.asString());
        }
    }

    private CanonicalState canonicalState(Fixture fixture) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] header = (Object[]) em.createNativeQuery(
                    "SELECT original_amount::text,scale(original_amount),total_amount::text,scale(total_amount),"
                        + "xmin::text,updated_at::text,md5(to_jsonb(q)::text) FROM quotation q WHERE id=:id")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT id,sort_order,subtotal::text,scale(subtotal),discount_base_amount::text,"
                        + "scale(discount_base_amount),line_discount_amount::text,scale(line_discount_amount),"
                        + "line_unit_price::text,scale(line_unit_price),line_final_price::text,scale(line_final_price),"
                        + "line_total_amount::text,scale(line_total_amount),"
                        + "product_attribute_values->>'amount',jsonb_typeof(product_attribute_values->'amount'),"
                        + "quote_card_values::text,quote_excel_values::text,costing_card_values::text,"
                        + "costing_excel_values::text,card_snapshot_at::text,quote_values_at::text,xmin::text,"
                        + "md5(to_jsonb(li)::text) FROM quotation_line_item li WHERE quotation_id=:id ORDER BY sort_order,id")
                .setParameter("id", fixture.quotationId()).getResultList();
            @SuppressWarnings("unchecked")
            List<Object[]> components = em.createNativeQuery(
                    "SELECT cd.id,cd.line_item_id,cd.subtotal::text,scale(cd.subtotal),"
                        + "cd.row_data->0->>'amount',jsonb_typeof(cd.row_data->0->'amount'),"
                        + "cd.snapshot_rows->0->'driverRow'->>'amount',"
                        + "jsonb_typeof(cd.snapshot_rows->0->'driverRow'->'amount'),"
                        + "cd.xmin::text,md5(to_jsonb(cd)::text) FROM quotation_line_component_data cd "
                        + "JOIN quotation_line_item li ON li.id=cd.line_item_id "
                        + "WHERE li.quotation_id=:id ORDER BY li.sort_order,cd.sort_order,cd.id")
                .setParameter("id", fixture.quotationId()).getResultList();
            long structures = ((Number) em.createNativeQuery(
                    "SELECT count(*) FROM quotation_view_structure WHERE quotation_id=:id")
                .setParameter("id", fixture.quotationId()).getSingleResult()).longValue();
            List<LineState> lineStates = new ArrayList<>();
            for (Object[] row : rows) lineStates.add(LineState.from(row));
            List<ComponentState> componentStates = new ArrayList<>();
            for (Object[] row : components) componentStates.add(ComponentState.from(row));
            String physical = fullFingerprint(fixture.quotationId());
            return new CanonicalState(string(header[0]), integer(header[1]), string(header[2]),
                integer(header[3]), string(header[4]), string(header[5]), string(header[6]),
                lineStates, componentStates, structures, physical);
        });
    }

    private void assertCanonicalState(CanonicalState state, Fixture fixture) {
        assertEquals(2, state.lines().size(), "TC-058 line count must remain stable");
        assertEquals(2, state.components().size(), "TC-058 component-row count must remain stable");
        assertEquals(4L, state.structureCount(), "TC-058 must retain four unique view structures");
        assertEquals(HEADER_TOTAL, state.originalAmount());
        assertEquals(12, state.originalScale());
        assertEquals(HEADER_TOTAL, state.totalAmount());
        assertEquals(12, state.totalScale());
        Map<String, String> expected = expectedByLine(fixture);
        for (LineState line : state.lines()) {
            String value = expected.get(line.id().toString());
            assertNotNull(value, "Unexpected line id " + line.id());
            assertEquals(value, line.subtotal());
            assertEquals(12, line.subtotalScale());
            assertEquals(value, line.discountBaseAmount());
            assertEquals(12, line.discountBaseScale());
            assertEquals("0.000000000000", line.lineDiscountAmount());
            assertEquals(12, line.lineDiscountScale());
            assertEquals(value, line.lineUnitPrice());
            assertEquals(12, line.lineUnitScale());
            assertEquals(value, line.lineFinalPrice());
            assertEquals(12, line.lineFinalScale());
            assertEquals(value, line.lineTotalAmount());
            assertEquals(12, line.lineTotalScale());
            assertEquals(value, line.productAmount());
            assertEquals("string", line.productAmountType());
            assertFalse(containsFailureSentinel(line.quoteCardValues()), "quote card sentinel: " + line);
            assertFalse(containsFailureSentinel(line.quoteExcelValues()), "quote excel sentinel: " + line);
            assertFalse(containsFailureSentinel(line.costingCardValues()), "costing card sentinel: " + line);
            assertFalse(containsFailureSentinel(line.costingExcelValues()), "costing excel sentinel: " + line);
        }
        for (ComponentState component : state.components()) {
            String value = expected.get(component.lineItemId().toString());
            assertEquals(value, component.subtotal());
            assertEquals(12, component.subtotalScale());
            assertEquals(value, component.rowAmount());
            assertEquals("string", component.rowAmountType());
            assertEquals(value, component.snapshotAmount());
            assertEquals("string", component.snapshotAmountType());
        }
        assertEquals(fixture.lineIds(), state.lineIds(), "TC-058 line ids must remain stable");
    }

    private void assertCardValues(CanonicalState state, Fixture fixture) {
        assertCardValues(state, fixture, true);
    }

    private void assertCardValues(CanonicalState state, Fixture fixture, boolean expectCostingExcel) {
        Map<String, String> expected = expectedByLine(fixture);
        for (LineState line : state.lines()) {
            String value = expected.get(line.id().toString());
            assertJsonContainsExactString(line.quoteCardValues(), "amount", value,
                "quote_card_values line=" + line.id());
            assertJsonContainsExactString(line.quoteCardValues(), "subtotal", value,
                "quote_card_values subtotal line=" + line.id());
            assertJsonContainsExactString(line.quoteExcelValues(), "amount", value,
                "quote_excel_values line=" + line.id());
            assertNotNull(line.costingCardValues(), "costing_card_values must be materialized");
            if (expectCostingExcel) {
                assertNotNull(line.costingExcelValues(), "costing_excel_values must be materialized");
            } else {
                assertNull(line.costingExcelValues(),
                    "ensure-card-values must preserve lazy costing Excel state until ensure-excel-values");
            }
            assertNotNull(line.cardSnapshotAt(), "card_snapshot_at must be populated");
            assertNotNull(line.quoteValuesAt(), "quote_values_at must be populated");
        }
    }

    private void assertPhysicalReadOnly(Fixture fixture, String baseline, String label, Response response) {
        assertEquals(200, response.statusCode(), response.asString());
        assertEquals(baseline, QuarkusTransaction.requiringNew().call(
            () -> fullFingerprint(fixture.quotationId())), label + " must not write persistence");
    }

    private AtomicFingerprint atomicFingerprint(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] header = (Object[]) em.createNativeQuery(
                    "SELECT xmin::text,updated_at::text,original_amount::text,total_amount::text,"
                        + "md5(to_jsonb(q)::text) FROM quotation q WHERE id=:id")
                .setParameter("id", quotationId).getSingleResult();
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT id,xmin::text,subtotal::text,quote_values_at::text,quote_card_values::text,"
                        + "md5(to_jsonb(li)::text) FROM quotation_line_item li WHERE quotation_id=:id "
                        + "ORDER BY sort_order,id")
                .setParameter("id", quotationId).getResultList();
            List<String> lineFacts = rows.stream()
                .map(row -> String.join("|", java.util.Arrays.stream(row).map(DraftPrecisionLifecycleHttpTest::string).toList()))
                .toList();
            return new AtomicFingerprint(string(header[0]), string(header[1]), string(header[2]),
                string(header[3]), string(header[4]), lineFacts,
                tableFingerprint("quotation_line_component_data",
                    "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)", quotationId));
        });
    }

    private String fullFingerprint(UUID quotationId) {
        return String.join("|",
            tableFingerprint("quotation", "id=:id", quotationId),
            tableFingerprint("quotation_line_item", "quotation_id=:id", quotationId),
            tableFingerprint("quotation_line_component_data",
                "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)", quotationId),
            tableFingerprint("quotation_view_structure", "quotation_id=:id", quotationId),
            tableFingerprint("quotation_price_revision", "quotation_id=:id", quotationId));
    }

    private String tableFingerprint(String table, String predicate, UUID quotationId) {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT count(*),COALESCE(md5(string_agg(xmin::text || ':' || md5(to_jsonb(t)::text),"
                    + "'|' ORDER BY to_jsonb(t)::text)),'none') FROM " + table + " t WHERE " + predicate)
            .setParameter("id", quotationId).getSingleResult();
        return row[0] + ":" + row[1];
    }

    @SuppressWarnings("unchecked")
    private List<UUID> lineIdsInProductionQueryOrder(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> ((List<Object>) em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id=:id")
            .setParameter("id", quotationId).getResultList()).stream()
            .map(DraftPrecisionLifecycleHttpTest::uuid).toList());
    }

    private void installFailureTrigger(String triggerName, String functionName, UUID blockedLineId) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("CREATE FUNCTION " + functionName + "() RETURNS trigger LANGUAGE plpgsql AS '"
                    + "BEGIN RAISE EXCEPTION ''TC058 injected failure for " + blockedLineId + "''; END'")
                .executeUpdate();
            em.createNativeQuery("CREATE TRIGGER " + triggerName
                    + " BEFORE UPDATE OF quote_card_values ON quotation_line_item FOR EACH ROW "
                    + "WHEN (OLD.id = '" + blockedLineId + "'::uuid AND "
                    + "NEW.quote_card_values IS DISTINCT FROM OLD.quote_card_values) "
                    + "EXECUTE FUNCTION " + functionName + "()")
                .executeUpdate();
        });
    }

    private void dropFailureTrigger(String triggerName, String functionName) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DROP TRIGGER IF EXISTS " + triggerName + " ON quotation_line_item")
                .executeUpdate();
            em.createNativeQuery("DROP FUNCTION IF EXISTS " + functionName + "()")
                .executeUpdate();
        });
    }

    private void assertTriggerArtifactsAbsent(String triggerName, String functionName) {
        QuarkusTransaction.requiringNew().run(() -> {
            long triggers = ((Number) em.createNativeQuery(
                    "SELECT count(*) FROM pg_trigger WHERE tgname=:name")
                .setParameter("name", triggerName).getSingleResult()).longValue();
            long functions = ((Number) em.createNativeQuery(
                    "SELECT count(*) FROM pg_proc WHERE proname=:name")
                .setParameter("name", functionName).getSingleResult()).longValue();
            assertEquals(0L, triggers, "TC-058 trigger cleanup");
            assertEquals(0L, functions, "TC-058 function cleanup");
        });
    }

    private UUID firstUserId() {
        Object value = em.createNativeQuery("SELECT id FROM \"user\" ORDER BY id LIMIT 1")
            .getSingleResult();
        return uuid(value);
    }

    private static Response postEmpty(UUID quotationId, String route) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/quotations/" + quotationId + "/" + route);
    }

    private static Map<String, String> expectedByLine(Fixture fixture) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(fixture.lineIds().get(0).toString(), VALUE_A);
        values.put(fixture.lineIds().get(1).toString(), VALUE_B);
        return values;
    }

    private static String fieldsJson() {
        return "[{\"name\":\"rowKey\",\"field_type\":\"INPUT_TEXT\",\"sort_order\":0},"
            + "{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\",\"sort_order\":1,"
            + "\"is_amount\":true,\"is_subtotal\":true},"
            + "{\"name\":\"项次\",\"field_type\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"输入单价\",\"field_type\":\"INPUT_NUMBER\"},"
            + "{\"name\":\"公式金额\",\"field_type\":\"FORMULA\"}]";
    }

    private String singleLineDraft(Fixture fixture, String rowData) {
        return "{\"name\":\"TC-0811 input precision\",\"finalDiscountRate\":\"100.00\","
            + "\"lineItems\":[{\"id\":\"" + fixture.lineIds().get(0) + "\","
            + "\"productId\":\"" + fixture.productIds().get(0) + "\",\"templateId\":\""
            + fixture.quoteTemplateId() + "\",\"productName\":\"TC-058 product 0\","
            + "\"subtotal\":\"" + VALUE_A + "\",\"sortOrder\":0,\"componentData\":[{"
            + "\"componentId\":\"" + fixture.componentId() + "\",\"tabName\":\"" + TAB_NAME
            + "\",\"rowData\":" + jsonString(rowData) + ",\"subtotal\":\"" + VALUE_A
            + "\",\"sortOrder\":0}]}]}";
    }

    private String componentRowData(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
            "SELECT cd.row_data::text FROM quotation_line_component_data cd JOIN quotation_line_item li "
                + "ON li.id=cd.line_item_id WHERE li.quotation_id=:qid ORDER BY li.sort_order,cd.sort_order LIMIT 1")
            .setParameter("qid", quotationId).getSingleResult()));
    }

    private String componentFingerprint(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> String.valueOf(em.createNativeQuery(
            "SELECT md5(string_agg(cd.id::text||':'||cd.row_data::text,'|' ORDER BY cd.id)) "
                + "FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id=cd.line_item_id "
                + "WHERE li.quotation_id=:qid")
            .setParameter("qid", quotationId).getSingleResult()));
    }

    private String classificationDraft(Fixture fixture, int lineCount) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) lines.append(',');
            boolean firstTemplate = i % 2 == 0;
            UUID templateId = firstTemplate ? fixture.quoteTemplateId() : fixture.costingTemplateId();
            lines.append("{\"productId\":\"").append(fixture.productIds().get(i % 2))
                .append("\",\"templateId\":\"").append(templateId)
                .append("\",\"productName\":\"classification\",\"subtotal\":\"1\",\"sortOrder\":")
                .append(i).append(",\"componentData\":[");
            if (firstTemplate) {
                lines.append("{\"componentId\":\"").append(fixture.componentId())
                    .append("\",\"tabName\":\"").append(TAB_NAME)
                    .append("\",\"rowData\":\"[{\\\"rowKey\\\":\\\"R").append(i)
                    .append("\\\",\\\"amount\\\":\\\"1.2300\\\"}]\",\"subtotal\":\"1\"}");
            } else {
                lines.append("{\"componentId\":\"").append(fixture.secondaryInputComponentId())
                    .append("\",\"tabName\":\"C2\",\"rowData\":\"[{\\\"输入值\\\":\\\"1.2300\\\"}]\",\"subtotal\":\"1\"},")
                    .append("{\"componentId\":\"").append(fixture.secondaryFormulaComponentId())
                    .append("\",\"tabName\":\"C3\",\"rowData\":\"[{\\\"公式值\\\":1.25}]\",\"subtotal\":\"1\"}");
            }
            lines.append("]}");
        }
        return "{\"name\":\"classification\",\"finalDiscountRate\":\"100\",\"lineItems\":["
                + lines + "]}";
    }

    private static String componentSnapshotJson(Component component, Component excelComponent) {
        String normal = "{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + component.id
            + "\",\"componentName\":\"" + component.name + "\",\"componentCode\":\""
            + component.code + "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAB_NAME
            + "\",\"sortOrder\":0,\"fields\":" + fieldsJson()
            + ",\"formulas\":[],\"formula_assignments\":{}}";
        if (excelComponent == null) return "[" + normal + "]";
        String excel = "{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + excelComponent.id
            + "\",\"componentName\":\"" + excelComponent.name + "\",\"componentCode\":\""
            + excelComponent.code + "\",\"componentType\":\"EXCEL\",\"tabName\":\"TC-058 Costing Excel\""
            + ",\"sortOrder\":1,\"fields\":[],\"formulas\":[],\"excelColumns\":"
            + excelComponent.excelColumns + ",\"formula_assignments\":{}}";
        return "[" + normal + "," + excel + "]";
    }

    private static String productAttributes(String value, int index) {
        return "{\"amount\":\"" + value + "\",\"rowKey\":\"R" + index + "\"}";
    }

    private static String rowData(String value, int index) {
        return "[{\"rowKey\":\"R" + index + "\",\"amount\":\"" + value + "\"}]";
    }

    private static String snapshotRows(String value, int index) {
        return "[{\"driverRow\":{\"rowKey\":\"R" + index + "\",\"amount\":\"" + value
            + "\"},\"basicDataValues\":{}}]";
    }

    private static String jsonString(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertJsonContainsExactString(String json, String field, String expected, String label) {
        assertNotNull(json, label + " must not be null");
        try {
            JsonNode root = MAPPER.readTree(json);
            List<JsonNode> matches = root.findValues(field);
            assertTrue(matches.stream().anyMatch(n -> n.isTextual() && expected.equals(n.asText())),
                label + " must contain exact string " + field + "=" + expected + ", actual=" + json);
            assertTrue(matches.stream().noneMatch(JsonNode::isNumber),
                label + " must not contain numeric token for " + field + ", actual=" + json);
        } catch (Exception e) {
            throw new AssertionError(label + " must be valid JSON: " + json, e);
        }
    }

    private static boolean containsFailureSentinel(String json) {
        return json != null && json.contains("__cardValueFailed");
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value) {
        return ((Number) value).intValue();
    }

    private record Fixture(
            UUID quotationId, UUID customerId, UUID componentId, UUID costingExcelComponentId,
            UUID secondaryInputComponentId, UUID secondaryFormulaComponentId,
            UUID quoteTemplateId, UUID costingTemplateId,
            List<UUID> productIds, List<UUID> lineIds) {
    }

    private record LineState(
            UUID id, int sortOrder, String subtotal, int subtotalScale,
            String discountBaseAmount, int discountBaseScale,
            String lineDiscountAmount, int lineDiscountScale,
            String lineUnitPrice, int lineUnitScale,
            String lineFinalPrice, int lineFinalScale,
            String lineTotalAmount, int lineTotalScale,
            String productAmount, String productAmountType,
            String quoteCardValues, String quoteExcelValues,
            String costingCardValues, String costingExcelValues,
            String cardSnapshotAt, String quoteValuesAt, String xmin, String rowHash) {

        static LineState from(Object[] row) {
            return new LineState(uuid(row[0]), integer(row[1]), string(row[2]), integer(row[3]),
                string(row[4]), integer(row[5]), string(row[6]), integer(row[7]),
                string(row[8]), integer(row[9]), string(row[10]), integer(row[11]),
                string(row[12]), integer(row[13]), string(row[14]), string(row[15]),
                string(row[16]), string(row[17]), string(row[18]), string(row[19]),
                string(row[20]), string(row[21]), string(row[22]), string(row[23]));
        }
    }

    private record ComponentState(
            UUID id, UUID lineItemId, String subtotal, int subtotalScale,
            String rowAmount, String rowAmountType,
            String snapshotAmount, String snapshotAmountType,
            String xmin, String rowHash) {

        static ComponentState from(Object[] row) {
            return new ComponentState(uuid(row[0]), uuid(row[1]), string(row[2]), integer(row[3]),
                string(row[4]), string(row[5]), string(row[6]), string(row[7]),
                string(row[8]), string(row[9]));
        }
    }

    private record CanonicalState(
            String originalAmount, int originalScale, String totalAmount, int totalScale,
            String quotationXmin, String quotationUpdatedAt, String quotationHash,
            List<LineState> lines, List<ComponentState> components,
            long structureCount, String physicalFingerprint) {

        List<UUID> lineIds() {
            return lines.stream().map(LineState::id).toList();
        }

        String logicalFingerprint() {
            return originalAmount + "|" + originalScale + "|" + totalAmount + "|" + totalScale
                + "|" + structureCount + "|" + lines.stream().map(line -> line.id + ":" + line.sortOrder
                    + ":" + line.subtotal + ":" + line.discountBaseAmount + ":" + line.lineDiscountAmount
                    + ":" + line.lineUnitPrice + ":" + line.lineFinalPrice + ":" + line.lineTotalAmount
                    + ":" + line.productAmount + ":" + line.quoteCardValues + ":" + line.quoteExcelValues
                    + ":" + line.costingCardValues + ":" + line.costingExcelValues).toList()
                + "|" + components.stream().map(component -> component.id + ":" + component.lineItemId
                    + ":" + component.subtotal + ":" + component.rowAmount + ":" + component.snapshotAmount)
                    .toList();
        }
    }

    private record AtomicFingerprint(
            String quotationXmin, String quotationUpdatedAt,
            String originalAmount, String totalAmount, String quotationHash,
            List<String> lineFacts, String componentFingerprint) {
    }
}
