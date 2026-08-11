package com.cpq.quotation;

import com.cpq.common.PrecisionHttpContractSupport;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CostingOrderPrecisionHttpContractTest.RbacOffProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CostingOrderPrecisionHttpContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PART_NO = "P-0810-VERSION";
    private static final Set<String> PRECISION_FIELDS = Set.of(
            "totalAmount", "costingTotalAmount", "amount", "subtotal", "unitPrice", "quantity", "rate");
    private static final Set<String> STRUCTURAL_INTEGER_FIELDS = Set.of("code", "sortOrder", "rowIndex");
    private static final String VERSIONED_SQL = """
            SELECT v.hf_part_no, v.material_no, v.view_version, v.is_current, v.amount
            FROM (VALUES
              ('P-0810-VERSION'::text, 'P-0810-VERSION'::text, '2000'::text, false,
               10.123456789012::numeric),
              ('P-0810-VERSION'::text, 'P-0810-VERSION'::text, '2001'::text, true,
               11.123456789012::numeric)
            ) AS v(hf_part_no, material_no, view_version, is_current, amount)
            WHERE :versionFilter(v.is_current, v.view_version, v.hf_part_no)
            """;

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    private final List<UUID> quotationIds = new ArrayList<>();
    private UUID customerId;
    private UUID quotationId;
    private UUID lineItemId;
    private UUID costingOrderId;
    private UUID componentId;
    private UUID componentViewId;
    private UUID templateId;
    private UUID templateComponentId;
    private UUID componentSnapshotId;
    private String quotationNumber;
    private String runId;

    @BeforeEach
    void createFixture() {
        createCustomerIfNeeded();
        Response created = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"customerId\":\"" + customerId + "\",\"name\":\"T0810-P4-COSTING-"
                        + runId + "\",\"quoteType\":\"STANDARD\"}")
                .post("/api/cpq/quotations");
        assertEquals(200, created.statusCode(), created.asString());
        quotationId = UUID.fromString(created.jsonPath().getString("data.id"));
        quotationNumber = created.jsonPath().getString("data.quotationNumber");
        quotationIds.add(quotationId);
        buildCostingFixture();
    }

    @AfterEach
    void cleanupFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (quotationId != null) {
                em.createNativeQuery("UPDATE quotation SET status='DRAFT' WHERE id=:id")
                        .setParameter("id", quotationId).executeUpdate();
            }
            if (costingOrderId != null) {
                em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id = :id")
                        .setParameter("id", costingOrderId).executeUpdate();
                em.createNativeQuery("DELETE FROM costing_order WHERE id = :id")
                        .setParameter("id", costingOrderId).executeUpdate();
            }
        });

        for (UUID id : quotationIds) {
            Response deleted = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .delete("/api/cpq/quotations/" + id);
            assertEquals(200, deleted.statusCode(), deleted.asString());
        }
        quotationIds.clear();

        QuarkusTransaction.requiringNew().run(() -> {
            if (componentSnapshotId != null) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE id = :id")
                        .setParameter("id", componentSnapshotId).executeUpdate();
            }
            if (templateComponentId != null) {
                em.createNativeQuery("DELETE FROM template_component WHERE id = :id")
                        .setParameter("id", templateComponentId).executeUpdate();
            }
            if (templateId != null) {
                em.createNativeQuery("DELETE FROM template WHERE id = :id")
                        .setParameter("id", templateId).executeUpdate();
            }
            if (componentViewId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id")
                        .setParameter("id", componentViewId).executeUpdate();
            }
            if (componentId != null) {
                em.createNativeQuery("DELETE FROM component WHERE id = :id")
                        .setParameter("id", componentId).executeUpdate();
            }
        });
        clearPerTestIds();
    }

    @AfterAll
    void cleanupCustomerFixture() {
        if (customerId == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id = :id")
                    .setParameter("id", customerId).executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE id = :id")
                    .setParameter("id", customerId).executeUpdate();
        });
    }

    @Test
    void p4_costingListReturnsPrecisionSafeTokensWithoutWrites() {
        CostingFingerprint before = costingFingerprint();
        Response list = RestAssured.given()
                .queryParam("keyword", quotationNumber)
                .get("/api/cpq/costing-orders");
        assertEquals(200, list.statusCode(), list.asString());
        JsonNode listJson = PrecisionHttpContractSupport.readJson(list);
        JsonNode listItem = findCostingOrder(listJson.at("/data"));
        assertFalse(listItem.isMissingNode(), list.asString());
        PrecisionHttpContractSupport.assertTextualOrNull(
                listItem, "/totalAmount", "/costingTotalAmount");
        assertPrecisionResponse(listJson);
        assertEquals(before, costingFingerprint(), "Costing list must be read-only");
    }

    @Test
    void p4_costingDetailReturnsNestedPrecisionSafeTokensWithoutWrites() {
        CostingFingerprint before = costingFingerprint();
        Response detail = RestAssured.given().get("/api/cpq/costing-orders/" + costingOrderId);
        assertEquals(200, detail.statusCode(), detail.asString());
        JsonNode detailJson = PrecisionHttpContractSupport.readJson(detail);
        PrecisionHttpContractSupport.assertTextualOrNull(
                detailJson, "/data/totalAmount", "/data/costingTotalAmount");
        assertPrecisionResponse(detailJson);
        JsonNode frozenDto = readEmbeddedJson(detailJson.at("/data/frozenDto"), "frozenDto");
        PrecisionHttpContractSupport.assertTextualOrNull(
                frozenDto, "/lineItems/0/totalAmount", "/lineItems/0/quantity");
        assertPrecisionResponse(frozenDto);
        JsonNode renderEntry = detailJson.at("/data/costingRender/" + lineItemId);
        JsonNode cachedCardValues = readEmbeddedJson(
                renderEntry.path("costingCardValues"), "costingRender.costingCardValues");
        PrecisionHttpContractSupport.assertTextualOrNull(
                cachedCardValues,
                "/tabs/0/baseRows/0/driverRow/amount",
                "/tabs/0/formulaResults/subtotal");
        assertPrecisionResponse(cachedCardValues);
        JsonNode cachedExcelValues = readEmbeddedJson(
                renderEntry.path("costingExcelValues"), "costingRender.costingExcelValues");
        PrecisionHttpContractSupport.assertTextualOrNull(cachedExcelValues, "/rows/0/amount");
        assertPrecisionResponse(cachedExcelValues);
        assertEquals(before, costingFingerprint(), "Costing detail must be read-only");
    }

    @Test
    void tc054_frozenCostingComparisonAndExportsAreStrictlyReadOnly() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE quotation SET status='SUBMITTED',customer_template_id=:templateId "
                    + "WHERE id=:id")
                .setParameter("templateId", templateId)
                .setParameter("id", quotationId).executeUpdate();
            em.createNativeQuery("UPDATE quotation_line_item SET "
                    + "quote_card_values=CAST(:quote AS jsonb),"
                    + "costing_card_values=CAST(:costing AS jsonb),"
                    + "quote_excel_values=CAST(:excel AS jsonb),"
                    + "costing_excel_values=CAST(:excel AS jsonb) WHERE id=:id")
                .setParameter("quote", comparisonCardValues())
                .setParameter("costing", comparisonCardValues())
                .setParameter("excel", "{\"rows\":[]}")
                .setParameter("id", lineItemId).executeUpdate();
        });
        CostingFingerprint baseline = costingFingerprint();

        assertCostingReadOnly(baseline, "costing detail",
            RestAssured.given().get("/api/cpq/costing-orders/" + costingOrderId));
        assertCostingReadOnly(baseline, "quotation detail",
            RestAssured.given().get("/api/cpq/quotations/" + quotationId));
        Response frozenComparison = RestAssured.given().queryParam("frozen", true)
            .get("/api/cpq/quotations/" + quotationId + "/comparison-view/data");
        assertComparisonRows(frozenComparison, "frozen comparison");
        assertCostingReadOnly(baseline, "frozen comparison", frozenComparison);
        Response liveComparison = RestAssured.given()
            .get("/api/cpq/quotations/" + quotationId + "/comparison-view/data");
        assertComparisonRows(liveComparison, "default live comparison");
        assertCostingReadOnly(baseline, "default live comparison", liveComparison);
        assertCostingReadOnly(baseline, "comparison meta",
            RestAssured.given().get("/api/cpq/quotations/" + quotationId + "/comparison-view/meta"));
        assertCostingReadOnly(baseline, "quotation HTML export",
            jsonPost("/api/cpq/quotations/" + quotationId + "/export/html"));
        assertCostingReadOnly(baseline, "quotation PDF export",
            jsonPost("/api/cpq/quotations/" + quotationId + "/export/pdf"));
        assertCostingReadOnly(baseline, "quotation Excel export",
            jsonPost("/api/cpq/quotations/" + quotationId + "/export/excel"));
        assertCostingReadOnly(baseline, "Excel-view export",
            RestAssured.given().get("/api/cpq/quotations/" + quotationId + "/export-excel-view"));
    }

    @Test
    void p4_versionOptionsReturnsVersionsWithoutWrites() {
        CostingFingerprint before = costingFingerprint();
        Response options = RestAssured.given()
                .queryParam("lineItemId", lineItemId)
                .queryParam("componentId", componentId)
                .queryParam("partNo", PART_NO)
                .get("/api/cpq/costing-orders/" + costingOrderId + "/version-options");
        assertEquals(200, options.statusCode(), options.asString());
        JsonNode optionsJson = PrecisionHttpContractSupport.readJson(options);
        assertEquals("2000", optionsJson.at("/data/currentVersion").asText(), options.asString());
        assertTrue(optionsJson.at("/data/options").isArray(), options.asString());
        assertTrue(containsText(optionsJson.at("/data/options"), "2000"), options.asString());
        assertTrue(containsText(optionsJson.at("/data/options"), "2001"), options.asString());
        assertPrecisionResponse(optionsJson);
        assertEquals(before, costingFingerprint(), "Version options must be read-only");
    }

    @Test
    void p4_versionSwitchReturnsDecimalStringsAndOnlyWritesCostingState() {
        PrecisionHttpContractSupport.QuotationFingerprint quotationBefore =
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId);
        assertEquals(0L, overrideCount());

        Response switched = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"lineItemId\":\"" + lineItemId + "\",\"componentId\":\""
                        + componentId + "\",\"partNo\":\"" + PART_NO
                        + "\",\"viewVersion\":\"2000\"}")
                .post("/api/cpq/costing-orders/" + costingOrderId + "/version-switch");
        assertEquals(200, switched.statusCode(), switched.asString());

        JsonNode json = PrecisionHttpContractSupport.readJson(switched);
        PrecisionHttpContractSupport.assertTextualOrNull(json, "/data/costingTotalAmount");
        assertPrecisionResponse(json);
        JsonNode switchedCardValues = readEmbeddedJson(
                json.at("/data/costingCardValues"), "version-switch.costingCardValues");
        assertFalse(switchedCardValues.findValues("amount").isEmpty(), switched.asString());
        assertPrecisionResponse(switchedCardValues);
        JsonNode switchedExcelValues = readEmbeddedJson(
                json.at("/data/costingExcelColumns"), "version-switch.costingExcelColumns");
        assertPrecisionResponse(switchedExcelValues);
        assertEquals(1L, overrideCount(), "Successful version switch must persist exactly one override");
        assertEquals("2000", overrideVersion());
        PrecisionHttpContractSupport.assertUnchanged(quotationBefore,
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId));
    }

    private void buildCostingFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            Component component = new Component();
            component.name = "T0810 P4 Version Component";
            component.code = "T0810-P4-VERSION-" + UUID.randomUUID().toString().substring(0, 6);
            component.fields = "[{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\"}]";
            component.formulas = "[]";
            component.tabType = "STANDARD";
            component.dataDriverPath = "$t0810_p4_version_view";
            component.persist();
            componentId = component.id;

            ComponentSqlView view = new ComponentSqlView();
            view.componentId = componentId;
            view.sqlViewName = "t0810_p4_version_view";
            view.sqlTemplate = VERSIONED_SQL;
            view.declaredColumns = "[]";
            view.persist();
            componentViewId = view.id;

            Template template = new Template();
            template.templateSeriesId = UUID.randomUUID();
            template.name = "T0810 P4 Costing Template " + runId;
            template.templateKind = "COSTING";
            template.status = "DRAFT";
            template.excelViewConfig = "[{\"col_key\":\"amount\",\"source_type\":"
                    + "\"PRODUCT_ATTRIBUTE\",\"field_key\":\"amount\"}]";
            template.createdAt = OffsetDateTime.now();
            template.updatedAt = OffsetDateTime.now();
            template.persist();
            templateId = template.id;

            TemplateComponent templateComponent = new TemplateComponent();
            templateComponent.templateId = templateId;
            templateComponent.componentId = componentId;
            templateComponent.tabName = "Versioned";
            templateComponent.createdAt = OffsetDateTime.now();
            templateComponent.persist();
            templateComponentId = templateComponent.id;

            TemplateComponentSnapshot snapshot = new TemplateComponentSnapshot();
            snapshot.templateId = templateId;
            snapshot.templateComponentId = templateComponentId;
            snapshot.componentId = componentId;
            snapshot.sortOrder = 0;
            snapshot.tabName = "Versioned";
            snapshot.componentName = component.name;
            snapshot.componentCode = component.code;
            snapshot.componentType = "NORMAL";
            snapshot.fields = component.fields;
            snapshot.formulas = component.formulas;
            snapshot.dataDriverPath = component.dataDriverPath;
            snapshot.bomRecursiveExpand = false;
            snapshot.tabType = component.tabType;
            snapshot.rowKeyFields = "[\"hf_part_no\"]";
            snapshot.persist();
            componentSnapshotId = snapshot.id;

            try {
                var components = MAPPER.createArrayNode();
                var entry = components.addObject();
                entry.put("id", templateComponentId.toString());
                entry.put("componentId", componentId.toString());
                entry.put("componentName", component.name);
                entry.put("componentCode", component.code);
                entry.put("componentType", "NORMAL");
                entry.put("tabName", "Versioned");
                entry.put("sortOrder", BigDecimal.ZERO);
                entry.set("fields", MAPPER.readTree(component.fields));
                entry.set("formulas", MAPPER.readTree(component.formulas));
                entry.put("data_driver_path", component.dataDriverPath);
                em.createNativeQuery("UPDATE template SET components_snapshot = CAST(:snapshot AS jsonb) "
                                + "WHERE id = :id")
                        .setParameter("snapshot", MAPPER.writeValueAsString(components))
                        .setParameter("id", templateId)
                        .executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to build costing template snapshot", e);
            }

            lineItemId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO quotation_line_item "
                            + "(id, quotation_id, template_id, product_part_no_snapshot, sort_order, created_at) "
                            + "VALUES (:id, :quotationId, :templateId, :partNo, 0, now())")
                    .setParameter("id", lineItemId)
                    .setParameter("quotationId", quotationId)
                    .setParameter("templateId", templateId)
                    .setParameter("partNo", PART_NO)
                    .executeUpdate();

            Quotation quotation = em.find(Quotation.class, quotationId);
            quotation.customerTemplateId = templateId;
            quotation.costingCardTemplateId = templateId;
            quotation.totalAmount = new BigDecimal("98765431.123456789012");
            quotation.snapshotCustomerName = "T0810 P4 Costing Customer";
            em.merge(quotation);

            CostingOrder costingOrder = new CostingOrder();
            costingOrder.quotationId = quotationId;
            costingOrder.costingOrderNumber = "HJ-T0810-" + UUID.randomUUID().toString().substring(0, 8);
            costingOrder.status = "PENDING";
            costingOrder.totalAmount = new BigDecimal("98765431.123456789012");
            costingOrder.costingTotalAmount = new BigDecimal("12345678.123456789012");
            String cachedCardValues = comparisonCardValues();
            String cachedExcelValues = "{\"rows\":[{\"amount\":\"11.123456789012\"}]}";
            try {
                var frozen = MAPPER.createObjectNode();
                var frozenLine = frozen.putArray("lineItems").addObject();
                frozenLine.put("id", lineItemId.toString());
                frozenLine.put("productPartNo", PART_NO);
                frozenLine.put("productName", "T0810 frozen costing line");
                frozenLine.put("quoteCardValues", cachedCardValues);
                frozenLine.put("costingCardValues", cachedCardValues);
                frozenLine.put("totalAmount", "98765431.123456789012");
                frozenLine.put("quantity", "0.000000000001");
                costingOrder.frozenDto = MAPPER.writeValueAsString(frozen);
                var render = MAPPER.createObjectNode();
                var renderEntry = render.putObject(lineItemId.toString());
                renderEntry.put("costingCardValues", cachedCardValues);
                renderEntry.put("costingExcelValues", cachedExcelValues);
                costingOrder.costingRender = MAPPER.writeValueAsString(render);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to build initial costing render", e);
            }
            costingOrder.persist();
            costingOrderId = costingOrder.id;
        });
    }

    private void createCustomerIfNeeded() {
        if (customerId != null) {
            return;
        }
        runId = Long.toUnsignedString(System.nanoTime());
        String body = "{\"name\":\"T0810 P4 Costing Customer " + runId
                + "\",\"level\":\"STANDARD\",\"contacts\":[{\"name\":\"P4 Costing\","
                + "\"phone\":\"139" + runId.substring(Math.max(0, runId.length() - 8))
                + "\",\"isPrimary\":true}]}";
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/customers");
        assertEquals(200, response.statusCode(), response.asString());
        customerId = UUID.fromString(response.jsonPath().getString("data.id"));
    }

    private JsonNode findCostingOrder(JsonNode items) {
        if (!items.isArray()) {
            return items.path("missing");
        }
        for (JsonNode item : items) {
            if (costingOrderId.toString().equals(item.path("costingOrderId").asText())) {
                return item;
            }
        }
        return items.path("missing");
    }

    private void assertPrecisionResponse(JsonNode json) {
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(json, PRECISION_FIELDS);
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, STRUCTURAL_INTEGER_FIELDS);
    }

    private JsonNode readEmbeddedJson(JsonNode value, String label) {
        assertTrue(value.isTextual(), label + " must be a JSON string, actual=" + value);
        try {
            return MAPPER.readTree(value.asText());
        } catch (Exception e) {
            throw new AssertionError(label + " must contain valid JSON: " + value.asText(), e);
        }
    }

    private static boolean containsText(JsonNode array, String expected) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private CostingFingerprint costingFingerprint() {
        Object[] costing = (Object[]) em.createNativeQuery(
                        "SELECT count(*), coalesce(min(cast(xmin as text)), ''), "
                                + "coalesce(md5(string_agg(md5(cast(to_jsonb(c) as text)), '' "
                                + "ORDER BY cast(c.id as text))), '') "
                                + "FROM costing_order c WHERE id = :id")
                .setParameter("id", costingOrderId)
                .getSingleResult();
        return new CostingFingerprint(
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId),
                ((Number) costing[0]).longValue(), String.valueOf(costing[1]), String.valueOf(costing[2]),
                overrideCount(), relatedPersistenceFingerprint());
    }

    private void assertCostingReadOnly(CostingFingerprint baseline, String label, Response response) {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
            label + " failed: " + response.asString());
        assertEquals(baseline, costingFingerprint(), "TC-054 wrote persistence after " + label);
    }

    private void assertComparisonRows(Response response, String label) {
        assertEquals(200, response.statusCode(), response.asString());
        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        JsonNode rows = json.at("/data/rows");
        assertTrue(rows.isArray() && !rows.isEmpty(), label + " must return a real frozen/live row");
        JsonNode row = rows.get(0);
        assertEquals(PART_NO, row.path("partNo").asText(), response.asString());
        assertEquals("BOTH", row.path("presence").asText(), response.asString());
        assertTrue(row.path("quote").isObject(), response.asString());
        assertTrue(row.path("costing").isObject(), response.asString());
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, STRUCTURAL_INTEGER_FIELDS);
        for (String side : List.of("quote", "costing")) {
            JsonNode comparisonSide = row.path(side);
            assertExactDecimalString(comparisonSide.path("productTotal"),
                "12345678.123456789012", label + "." + side + ".productTotal");
            JsonNode subtotal = comparisonSide.path("tabs").path("subtotal");
            assertTrue(subtotal.isObject(), label + "." + side
                + ".tabs.subtotal must be an object: " + response.asString());
            assertExactDecimalString(subtotal.path("tabTotal"),
                "12345678.123456789012", label + "." + side + ".tabs.subtotal.tabTotal");
            assertExactDecimalString(subtotal.path("subtotals").path("amount"),
                "11.123456789012", label + "." + side + ".tabs.subtotal.subtotals.amount");
        }
    }

    private static void assertExactDecimalString(JsonNode node, String expected, String label) {
        assertTrue(node.isTextual(), label + " must be a JSON string, actual=" + node);
        assertEquals(expected, node.asText(), label + " must preserve all 12 decimal places");
    }

    private static String comparisonCardValues() {
        return "{\"tabs\":[{\"componentId\":\"subtotal\","
            + "\"componentType\":\"SUBTOTAL\",\"tabName\":\"Subtotal\","
            + "\"subtotal\":\"12345678.123456789012\","
            + "\"subtotalByColumn\":{\"amount\":\"11.123456789012\"},"
            + "\"baseRows\":[{\"driverRow\":{\"hf_part_no\":\"" + PART_NO
            + "\",\"amount\":\"11.123456789012\"},\"basicDataValues\":{}}],"
            + "\"editRows\":[],\"formulaResults\":{\"subtotal\":"
            + "\"12345678.123456789012\"}}]}";
    }

    private static Response jsonPost(String path) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}").post(path);
    }

    private String relatedPersistenceFingerprint() {
        return String.join("|",
            relatedTableFingerprint("quotation_component_sql_snapshot", "quotation_id=:id"),
            relatedTableFingerprint("quotation_line_item_snapshot",
                "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)"),
            relatedTableFingerprint("quotation_price_revision", "quotation_id=:id"),
            relatedTableFingerprint("quotation_approval", "quotation_id=:id"));
    }

    private String relatedTableFingerprint(String table, String predicate) {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT count(*),COALESCE(md5(string_agg(xmin::text || ':' || "
                    + "md5(to_jsonb(t)::text),'|' ORDER BY to_jsonb(t)::text)),'none') "
                    + "FROM " + table + " t WHERE " + predicate)
            .setParameter("id", quotationId).getSingleResult();
        return row[0] + ":" + row[1];
    }

    private long overrideCount() {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM costing_order_version_override WHERE costing_order_id = :id")
                .setParameter("id", costingOrderId)
                .getSingleResult()).longValue();
    }

    private String overrideVersion() {
        Object value = em.createNativeQuery(
                        "SELECT view_version FROM costing_order_version_override WHERE costing_order_id = :id")
                .setParameter("id", costingOrderId)
                .getSingleResult();
        assertNotNull(value);
        return value.toString();
    }

    private void clearPerTestIds() {
        quotationId = null;
        lineItemId = null;
        costingOrderId = null;
        componentId = null;
        componentViewId = null;
        templateId = null;
        templateComponentId = null;
        componentSnapshotId = null;
        quotationNumber = null;
    }

    private record CostingFingerprint(
            PrecisionHttpContractSupport.QuotationFingerprint quotation,
            long costingCount,
            String costingXmin,
            String costingMd5,
            long overrideCount,
            String relatedPersistence) {
    }
}
