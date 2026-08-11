package com.cpq.quotation.service;

import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.dto.CreateComponentSqlViewRequest;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.service.ComponentService;
import com.cpq.component.service.ComponentSqlViewService;
import com.cpq.customer.entity.Customer;
import com.cpq.customer.entity.CustomerContact;
import com.cpq.quotation.dto.CreateQuotationRequest;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.dto.SaveDraftRequest;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.dto.CreateTemplateRequest;
import com.cpq.template.dto.PublishRequest;
import com.cpq.template.dto.TemplateDTO;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.cpq.template.service.TemplateComponentService;
import com.cpq.template.service.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One-shot persistent fixture builder for task-0810 Playwright TC-073 through TC-076.
 *
 * <p>The builder uses the same services as the production resources. It is disabled unless
 * {@code -Dtask0810.build.fixture=true} is supplied. Default execution returns successfully without
 * querying or writing fixture data. Re-running an enabled build reuses two already-valid seed
 * quotations under the stable {@link #TAG}; a partial fixture is rejected instead of duplicated.
 *
 * <p>This fixture must only be built in a disposable database whose Flyway history includes V385.
 * TC-076 submits a copied quotation and therefore creates a costing order. Production DELETE only
 * accepts DRAFT quotations, and withdraw does not remove that costing order. Cleanup is consequently
 * database snapshot restore/drop after Playwright, not a production-state-machine bypass in this
 * builder. Enabling persistence also requires {@code -Dtask0810.fixture.isolated-db=true} and an
 * exact {@code -Dtask0810.fixture.expected-database=task0810_*|e2e_*|cpq_task0810_*} match to the
 * connected database.
 */
@QuarkusTest
@TestProfile(Task0810PersistentFixtureBuilder.FixtureSafetyProfile.class)
@DisplayName("Task0810 persistent precision fixture builder (disabled by default)")
class Task0810PersistentFixtureBuilder {

    private static final String TAG = "TASK0810-PRECISION-V385";
    private static final String SIMPLE_NAME = TAG + "-SIMPLE-SEED";
    private static final String COMPOSITE_NAME = TAG + "-COMPOSITE-SEED";
    private static final String QUOTE_TEMPLATE_NAME = TAG + "-QUOTE-TEMPLATE";
    private static final String COSTING_TEMPLATE_NAME = TAG + "-COSTING-TEMPLATE";
    private static final String CUSTOMER_NAME = TAG + "-CUSTOMER";
    private static final String CUSTOMER_REMARKS = TAG + " isolated Playwright fixture";
    private static final String CUSTOMER_CONTACT_NAME = "Task0810 Fixture Contact";
    private static final String CUSTOMER_CONTACT_ROLE = "Precision E2E";
    private static final String CUSTOMER_CONTACT_PHONE = "13808100810";
    private static final String CUSTOMER_CONTACT_EMAIL = "task0810-fixture@example.invalid";
    private static final String PRECISION_COMPONENT_CODE = "TASK0810-PRECISION-ROWS";
    private static final String STABILITY_COMPONENT_CODE = "TASK0810-STABILITY-ROWS";
    private static final String PRECISION_VIEW = "task0810_precision_rows";
    private static final String STABILITY_VIEW = "task0810_stability_rows";

    private static final String PRECISION_TAB = "精度验证";
    private static final String STABILITY_TAB = "稳定页签";
    private static final String ROW_KEY_FIELD = "行号";
    private static final String INPUT_FIELD = "精度输入";
    private static final String RESULT_FIELD = "精度结果";
    private static final String FORMULA_ID = "task0810-identity-v1";
    private static final String BUILD_PROPERTY = "task0810.build.fixture";
    private static final String ISOLATED_DB_PROPERTY = "task0810.fixture.isolated-db";
    private static final String EXPECTED_DB_PROPERTY = "task0810.fixture.expected-database";

    private static final Set<String> FORBIDDEN_DATABASES = Set.of(
            "postgres", "template0", "template1", "cpq_db", "cpq_db_0724");
    private static final List<String> EXPECTED_PRECISION_VALUES = List.of(
            "1.234567891234", "98765431.123456789012");

    private static final String SIMPLE_PART_NO = "TASK0810-SIMPLE";
    private static final String COMPOSITE_PART_NO = "TASK0810-COMPOSITE";
    private static final String PART_ONE_NO = "TASK0810-PART-01";
    private static final String PART_TWO_NO = "TASK0810-PART-02";

    private record FixtureAssets(
            Customer customer,
            Component precisionComponent,
            Component stabilityComponent,
            Template quoteTemplate,
            Template costingTemplate,
            Quotation simple,
            Quotation composite) {}

    private record LogicalFingerprint(HeaderFingerprint header, List<LineFingerprint> lines) {}

    private record HeaderFingerprint(
            String id,
            String status,
            String customerId,
            String quoteTemplateId,
            String costingTemplateId,
            List<String> decimals) {}

    private record LineFingerprint(
            String id,
            String partNo,
            String compositeType,
            int sortOrder,
            String parentLineItemId,
            String templateId,
            List<String> decimals,
            List<ComponentDataFingerprint> componentData,
            SnapshotFingerprint snapshots) {}

    private record ComponentDataFingerprint(
            String id,
            String componentId,
            String tabName,
            int sortOrder,
            String subtotal,
            String rowData) {}

    private record SnapshotFingerprint(
            String quoteCardValues,
            String quoteExcelValues,
            String costingCardValues,
            String costingExcelValues) {}

    @Inject ComponentService componentService;
    @Inject ComponentSqlViewService componentSqlViewService;
    @Inject TemplateService templateService;
    @Inject TemplateComponentService templateComponentService;
    @Inject QuotationService quotationService;
    @Inject EntityManager em;
    @Inject ObjectMapper mapper;

    public static class FixtureSafetyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cpq.security.rbac.enabled", "false",
                    "quarkus.flyway.migrate-at-start", "false",
                    "quarkus.scheduler.enabled", "false",
                    "quarkus.arc.exclude-types", "com.cpq.datasource.sqlview.BnfTableMetaSyncer");
        }
    }

    @Test
    @DisplayName("build or validate SIMPLE and COMPOSITE task-0810 seeds")
    void buildPersistentFixture() throws Exception {
        if (!Boolean.getBoolean(BUILD_PROPERTY)) {
            return;
        }
        assertTrue(Boolean.getBoolean(ISOLATED_DB_PROPERTY),
                "Persistent precision fixtures and TC-076 require a disposable DB. "
                        + "Pass -D" + ISOLATED_DB_PROPERTY + "=true only for that environment.");

        String databaseName = assertIsolatedDatabase();
        assertV385Applied();

        if (hasAnyTaggedAssets()) {
            FixtureAssets assets = validateAllAssets();
            warmAndAssertStable(assets.simple.id, false);
            warmAndAssertStable(assets.composite.id, true);
            printDelivery(assets.simple.id, assets.composite.id, "REUSED", databaseName);
            return;
        }

        assertNoPartialAssets();
        UUID adminUserId = adminUserId();
        UUID customerId = createTaggedCustomerViaHttp();

        ComponentDTO precisionComponent = createPrecisionComponent(adminUserId);
        ComponentDTO stabilityComponent = createStabilityComponent(adminUserId);

        TemplateDTO quoteTemplate = createPublishedTemplate(
                QUOTE_TEMPLATE_NAME, "QUOTATION", precisionComponent.id, stabilityComponent.id);
        TemplateDTO costingTemplate = createPublishedTemplate(
                COSTING_TEMPLATE_NAME, "COSTING", precisionComponent.id, stabilityComponent.id);
        QuotationDTO simple = createQuotation(
                SIMPLE_NAME, customerId, adminUserId, quoteTemplate.id, costingTemplate.id);
        saveViaHttp(simple.id, quoteTemplate.id, costingTemplate.id,
                List.of(line(SIMPLE_PART_NO, "Task0810 simple product", "SIMPLE", 0, null,
                        quoteTemplate.id, precisionComponent.id, stabilityComponent.id)));

        QuotationDTO composite = createQuotation(
                COMPOSITE_NAME, customerId, adminUserId, quoteTemplate.id, costingTemplate.id);
        saveViaHttp(composite.id, quoteTemplate.id, costingTemplate.id, List.of(
                line(COMPOSITE_PART_NO, "Task0810 composite parent", "COMPOSITE", 0, null,
                        quoteTemplate.id, precisionComponent.id, stabilityComponent.id),
                line(PART_ONE_NO, "Task0810 part one", "PART", 1, 0,
                        quoteTemplate.id, precisionComponent.id, stabilityComponent.id),
                line(PART_TWO_NO, "Task0810 part two", "PART", 2, 0,
                        quoteTemplate.id, precisionComponent.id, stabilityComponent.id)));

        FixtureAssets assets = validateAllAssets();
        assertEquals(simple.id, assets.simple.id, "Created SIMPLE seed identity");
        assertEquals(composite.id, assets.composite.id, "Created COMPOSITE seed identity");
        warmAndAssertStable(assets.simple.id, false);
        warmAndAssertStable(assets.composite.id, true);
        printDelivery(assets.simple.id, assets.composite.id, "CREATED", databaseName);
    }

    private UUID createTaggedCustomerViaHttp() throws Exception {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("name", CUSTOMER_CONTACT_NAME);
        contact.put("role", CUSTOMER_CONTACT_ROLE);
        contact.put("phone", CUSTOMER_CONTACT_PHONE);
        contact.put("email", CUSTOMER_CONTACT_EMAIL);
        contact.put("isPrimary", true);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", CUSTOMER_NAME);
        request.put("level", "STANDARD");
        request.put("industry", "TASK0810 E2E");
        request.put("region", "ISOLATED");
        request.put("remarks", CUSTOMER_REMARKS);
        request.put("contacts", List.of(contact));

        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(request))
                .post("/api/cpq/customers");
        JsonNode data = assertSuccessfulResponse(response, "POST tagged TASK0810 customer");
        assertEquals(CUSTOMER_NAME, data.path("name").asText(), "Created customer name");
        assertEquals(CUSTOMER_REMARKS, data.path("remarks").asText(), "Created customer remarks");
        assertTrue(data.path("id").isTextual(), "Created customer data.id must be a UUID string");
        UUID customerId;
        try {
            customerId = UUID.fromString(data.path("id").asText());
        } catch (IllegalArgumentException e) {
            throw new AssertionError("Created customer data.id is not a UUID: " + data.path("id"), e);
        }

        em.clear();
        Customer created = Customer.findById(customerId);
        assertNotNull(created, "Created tagged customer must be persisted");
        validateTaggedCustomer(created);
        return customerId;
    }

    private ComponentDTO createPrecisionComponent(UUID adminUserId) {
        CreateComponentRequest request = new CreateComponentRequest();
        request.name = TAG + " precision rows";
        request.code = PRECISION_COMPONENT_CODE;
        request.rowKeyFields = List.of(ROW_KEY_FIELD);
        request.fields = List.of(
                driverField(ROW_KEY_FIELD, "INPUT_TEXT", PRECISION_VIEW, "row_key"),
                driverField(INPUT_FIELD, "INPUT_NUMBER", PRECISION_VIEW, "precision_input"),
                formulaField());
        request.formulas = List.of(Map.of(
                "id", FORMULA_ID,
                "name", RESULT_FIELD,
                "expression", List.of(
                        Map.of("type", "field", "value", INPUT_FIELD),
                        Map.of("type", "operator", "value", "*"),
                        Map.of("type", "number", "value", "1"))));
        ComponentDTO component = componentService.create(request);

        CreateComponentSqlViewRequest view = new CreateComponentSqlViewRequest();
        view.sqlViewName = PRECISION_VIEW;
        view.description = TAG + " deterministic two-row precision driver";
        view.sqlTemplate = precisionDriverSql();
        componentSqlViewService.create(component.id, view, adminUserId);
        return component;
    }

    private ComponentDTO createStabilityComponent(UUID adminUserId) {
        CreateComponentRequest request = new CreateComponentRequest();
        request.name = TAG + " stability rows";
        request.code = STABILITY_COMPONENT_CODE;
        request.rowKeyFields = List.of(ROW_KEY_FIELD);
        request.fields = List.of(
                driverField(ROW_KEY_FIELD, "INPUT_TEXT", STABILITY_VIEW, "row_key"),
                driverField("稳定值", "INPUT_TEXT", STABILITY_VIEW, "stable_value"));
        request.formulas = List.of();
        ComponentDTO component = componentService.create(request);

        CreateComponentSqlViewRequest view = new CreateComponentSqlViewRequest();
        view.sqlViewName = STABILITY_VIEW;
        view.description = TAG + " deterministic non-empty second tab";
        view.sqlTemplate = stabilityDriverSql();
        componentSqlViewService.create(component.id, view, adminUserId);
        return component;
    }

    private TemplateDTO createPublishedTemplate(
            String name, String kind, UUID precisionComponentId, UUID stabilityComponentId) {
        CreateTemplateRequest request = new CreateTemplateRequest();
        request.name = name;
        request.templateKind = kind;
        TemplateDTO template = templateService.create(request);
        templateComponentService.addComponent(template.id, precisionComponentId, PRECISION_TAB);
        templateComponentService.addComponent(template.id, stabilityComponentId, STABILITY_TAB);
        templateService.publish(template.id, new PublishRequest());
        return template;
    }

    private QuotationDTO createQuotation(
            String name, UUID customerId, UUID adminUserId, UUID quoteTemplateId, UUID costingTemplateId) {
        CreateQuotationRequest request = new CreateQuotationRequest();
        request.customerId = customerId;
        request.name = name;
        request.quoteType = "STANDARD";
        request.customerTemplateId = quoteTemplateId;
        request.costingTemplateId = costingTemplateId;
        return quotationService.create(request, adminUserId);
    }

    private void saveViaHttp(
            UUID quotationId,
            UUID quoteTemplateId,
            UUID costingTemplateId,
            List<SaveDraftRequest.LineItemDraft> lines) throws Exception {
        SaveDraftRequest request = new SaveDraftRequest();
        request.customerTemplateId = quoteTemplateId;
        request.costingCardTemplateId = costingTemplateId;
        request.finalDiscountRate = new BigDecimal("100.00");
        request.lineItems = lines;
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(request))
                .put("/api/cpq/quotations/" + quotationId + "/draft");
        assertSuccessfulResponse(response, "PUT draft " + quotationId);
    }

    private SaveDraftRequest.LineItemDraft line(
            String partNo,
            String productName,
            String compositeType,
            int sortOrder,
            Integer parentIndex,
            UUID templateId,
            UUID precisionComponentId,
            UUID stabilityComponentId) {
        SaveDraftRequest.LineItemDraft line = new SaveDraftRequest.LineItemDraft();
        line.templateId = templateId;
        line.productPartNo = partNo;
        line.productName = productName;
        line.productAttributeValues = "{\"fixtureTag\":\"" + TAG + "\"}";
        line.subtotal = BigDecimal.ZERO.setScale(12);
        line.annualVolume = 1;
        line.sortOrder = sortOrder;
        line.compositeType = compositeType;
        line.tempParentIndex = parentIndex;
        line.componentData = List.of(
                componentData(precisionComponentId, PRECISION_TAB, 0),
                componentData(stabilityComponentId, STABILITY_TAB, 1));
        return line;
    }

    private static SaveDraftRequest.ComponentDataDraft componentData(
            UUID componentId, String tabName, int sortOrder) {
        SaveDraftRequest.ComponentDataDraft data = new SaveDraftRequest.ComponentDataDraft();
        data.componentId = componentId;
        data.tabName = tabName;
        data.rowData = "[]";
        data.subtotal = BigDecimal.ZERO.setScale(12);
        data.sortOrder = sortOrder;
        return data;
    }

    private void warmAndAssertStable(UUID quotationId, boolean compositeExpected) throws Exception {
        JsonNode first = ensureAndReopen(quotationId, "first");
        LogicalFingerprint firstLogical = validateReopened(first, compositeExpected);
        List<String> firstPhysical = physicalFingerprint(quotationId, compositeExpected);

        JsonNode second = ensureAndReopen(quotationId, "second");
        LogicalFingerprint secondLogical = validateReopened(second, compositeExpected);
        List<String> secondPhysical = physicalFingerprint(quotationId, compositeExpected);

        assertEquals(firstLogical, secondLogical,
                "Repeated ensure/reopen must preserve the complete logical quotation fingerprint");
        assertEquals(firstPhysical, secondPhysical,
                "Repeated ensure/reopen must not update or replace any physical fixture row");
    }

    private JsonNode ensureAndReopen(UUID quotationId, String round) throws Exception {
        Response card = RestAssured.given().contentType(ContentType.JSON).body("{}")
                .post("/api/cpq/quotations/" + quotationId + "/ensure-card-values");
        JsonNode cardData = assertSuccessfulResponse(card, round + " ensure-card-values " + quotationId);
        assertFalse(cardData.path("cardValuesWarming").asBoolean(false),
                round + " ensure-card-values unexpectedly observed an in-flight warm");

        Response excel = RestAssured.given().contentType(ContentType.JSON).body("{}")
                .post("/api/cpq/quotations/" + quotationId + "/ensure-excel-values");
        assertSuccessfulResponse(excel, round + " ensure-excel-values " + quotationId);

        Response reopened = RestAssured.given().get("/api/cpq/quotations/" + quotationId);
        JsonNode data = assertSuccessfulResponse(reopened, round + " GET reopen " + quotationId);
        assertGetDecimalTokens(data, round);
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<String> physicalFingerprint(UUID quotationId, boolean compositeExpected) {
        em.clear();
        List<Object> rows = em.createNativeQuery(
                        "SELECT fingerprint FROM ("
                                + "SELECT 0 AS row_kind, cast(q.id AS text) AS row_id, "
                                + "'quotation|' || cast(q.id AS text) || '|' "
                                + "|| cast(q.xmin AS text) || '|' "
                                + "|| md5(cast(row_to_json(q) AS text)) AS fingerprint "
                                + "FROM quotation q WHERE q.id=:qid "
                                + "UNION ALL "
                                + "SELECT 1, cast(li.id AS text), "
                                + "'line|' || cast(li.id AS text) || '|' "
                                + "|| cast(li.xmin AS text) || '|' "
                                + "|| md5(cast(row_to_json(li) AS text)) "
                                + "FROM quotation_line_item li WHERE li.quotation_id=:qid "
                                + "UNION ALL "
                                + "SELECT 2, cast(cd.id AS text), "
                                + "'component|' || cast(cd.id AS text) || '|' "
                                + "|| cast(cd.xmin AS text) || '|' "
                                + "|| md5(cast(row_to_json(cd) AS text)) "
                                + "FROM quotation_line_component_data cd "
                                + "JOIN quotation_line_item li ON li.id=cd.line_item_id "
                                + "WHERE li.quotation_id=:qid"
                                + ") rows ORDER BY row_kind, row_id")
                .setParameter("qid", quotationId)
                .getResultList();
        int expectedRows = compositeExpected ? 10 : 4;
        assertEquals(expectedRows, rows.size(),
                "Physical fingerprint must contain quotation + line + component rows");
        return rows.stream().map(String::valueOf).toList();
    }

    private JsonNode assertSuccessfulResponse(Response response, String operation) throws Exception {
        String body = response.asString();
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                operation + " must return 2xx, actual=" + response.statusCode() + " body=" + body);
        JsonNode root = mapper.readTree(body);
        assertEquals(200, root.path("code").asInt(), operation + " API code: " + body);
        assertTrue(root.path("data").isObject(), operation + " response data must be an object: " + body);
        return root.path("data");
    }

    private void assertGetDecimalTokens(JsonNode data, String round) {
        for (String field : List.of("totalAmount", "originalAmount", "systemDiscountRate",
                "finalDiscountRate", "taxRate", "taxAmount")) {
            assertDecimalStringOrNull(data.path(field), round + " " + field);
        }
        for (JsonNode line : data.path("lineItems")) {
            for (String field : List.of("subtotal", "systemDiscountRate", "finalDiscountRate",
                    "discountBaseAmount", "discountRateApplied", "lineDiscountAmount",
                    "lineUnitPrice", "lineFinalPrice", "lineTotalAmount")) {
                assertDecimalStringOrNull(line.path(field), round + " lineItems[]." + field);
            }
            for (JsonNode component : line.path("componentData")) {
                assertDecimalStringOrNull(component.path("subtotal"),
                        round + " lineItems[].componentData[].subtotal");
            }
        }
    }

    private static void assertDecimalStringOrNull(JsonNode value, String path) {
        assertTrue(value.isTextual() || value.isNull(),
                path + " must be a decimal string or null, actual=" + value);
    }

    private LogicalFingerprint validateReopened(JsonNode quotation, boolean compositeExpected)
            throws Exception {
        assertNotNull(quotation);
        assertEquals("DRAFT", quotation.path("status").asText());
        int expectedLines = compositeExpected ? 3 : 1;
        JsonNode lineItems = quotation.path("lineItems");
        assertTrue(lineItems.isArray(), "Reopened quotation lineItems must be an array");
        assertEquals(expectedLines, lineItems.size(), "Reopened quotation line count");
        List<String> expectedPartOrder = compositeExpected
                ? List.of(COMPOSITE_PART_NO, PART_ONE_NO, PART_TWO_NO)
                : List.of(SIMPLE_PART_NO);
        assertEquals(expectedPartOrder,
                lineItems.valueStream().map(line -> line.path("productPartNo").asText()).toList(),
                "Reopened line order must be stable by sortOrder");

        List<LineFingerprint> lines = new ArrayList<>();
        for (JsonNode line : lineItems) {
            String lineId = requiredText(line, "id", "Reopened line id");
            String quoteCardValues = requiredText(line, "quoteCardValues",
                    "QUOTE card values for line " + lineId);
            String costingCardValues = requiredText(line, "costingCardValues",
                    "COSTING card values for line " + lineId);
            String quoteExcelValues = requiredText(line, "quoteExcelValues",
                    "QUOTE Excel values for line " + lineId);
            String costingExcelValues = requiredText(line, "costingExcelValues",
                    "COSTING Excel values for line " + lineId);

            List<String> quoteValues = validateCardValues(quoteCardValues, "QUOTE", lineId);
            List<String> costingValues = validateCardValues(costingCardValues, "COSTING", lineId);
            assertEquals(quoteValues, costingValues,
                    "QUOTE and COSTING formulas must use the same component contract for line " + lineId);

            JsonNode componentData = line.path("componentData");
            assertTrue(componentData.isArray(), "componentData must be an array for line " + lineId);
            assertEquals(2, componentData.size(), "Each fixture line must have exactly two component rows");
            List<ComponentDataFingerprint> componentFingerprints = new ArrayList<>();
            for (JsonNode component : componentData) {
                componentFingerprints.add(new ComponentDataFingerprint(
                        requiredText(component, "id", "Component row id for line " + lineId),
                        requiredText(component, "componentId", "Component id for line " + lineId),
                        requiredText(component, "tabName", "Component tab for line " + lineId),
                        component.path("sortOrder").asInt(Integer.MIN_VALUE),
                        decimalToken(component.path("subtotal"), "Component subtotal for line " + lineId),
                        requiredText(component, "rowData", "Component rowData for line " + lineId)));
            }
            assertEquals(List.of(PRECISION_TAB, STABILITY_TAB),
                    componentFingerprints.stream().map(ComponentDataFingerprint::tabName).toList(),
                    "Reopened component tab order for line " + lineId);
            assertEquals(List.of(0, 1),
                    componentFingerprints.stream().map(ComponentDataFingerprint::sortOrder).toList(),
                    "Reopened component sort order for line " + lineId);

            lines.add(new LineFingerprint(
                    lineId,
                    requiredText(line, "productPartNo", "Product part number for line " + lineId),
                    requiredText(line, "compositeType", "Composite type for line " + lineId),
                    line.path("sortOrder").asInt(Integer.MIN_VALUE),
                    textOrNull(line.path("parentLineItemId"), "Parent line id for line " + lineId),
                    requiredText(line, "templateId", "Template id for line " + lineId),
                    decimalTokens(line, List.of("subtotal", "systemDiscountRate", "finalDiscountRate",
                            "discountBaseAmount", "discountRateApplied", "lineDiscountAmount",
                            "lineUnitPrice", "lineFinalPrice", "lineTotalAmount"),
                            "Line decimals for " + lineId),
                    componentFingerprints,
                    new SnapshotFingerprint(quoteCardValues, quoteExcelValues,
                            costingCardValues, costingExcelValues)));
        }

        assertReopenedHierarchy(lines, compositeExpected);
        HeaderFingerprint header = new HeaderFingerprint(
                requiredText(quotation, "id", "Quotation id"),
                requiredText(quotation, "status", "Quotation status"),
                requiredText(quotation, "customerId", "Quotation customer"),
                requiredText(quotation, "customerTemplateId", "Quotation quote template"),
                requiredText(quotation, "costingCardTemplateId", "Quotation costing template"),
                decimalTokens(quotation, List.of("totalAmount", "originalAmount",
                        "systemDiscountRate", "finalDiscountRate", "taxRate", "taxAmount"),
                        "Quotation decimals"));
        return new LogicalFingerprint(header, lines);
    }

    private void assertReopenedHierarchy(List<LineFingerprint> lines, boolean compositeExpected) {
        if (!compositeExpected) {
            LineFingerprint simple = lines.get(0);
            assertEquals("SIMPLE", simple.compositeType(), "SIMPLE seed line type");
            assertEquals(0, simple.sortOrder(), "SIMPLE seed sort order");
            assertNull(simple.parentLineItemId(), "SIMPLE seed parent must be null");
            return;
        }
        assertEquals(List.of(0, 1, 2), lines.stream().map(LineFingerprint::sortOrder).toList(),
                "COMPOSITE seed sort order");
        LineFingerprint parent = lines.get(0);
        assertEquals("COMPOSITE", parent.compositeType(), "COMPOSITE parent type");
        assertNull(parent.parentLineItemId(), "COMPOSITE parent must not have a parent");
        for (LineFingerprint child : lines.subList(1, 3)) {
            assertEquals("PART", child.compositeType(), "COMPOSITE child type");
            assertEquals(parent.id(), child.parentLineItemId(),
                    "Both PART rows must point to the COMPOSITE parent line id");
        }
    }

    private List<String> decimalTokens(JsonNode object, List<String> fields, String context) {
        List<String> values = new ArrayList<>();
        for (String field : fields) {
            values.add(decimalToken(object.path(field), context + "." + field));
        }
        return values;
    }

    private String decimalToken(JsonNode value, String path) {
        assertDecimalStringOrNull(value, path);
        return value.isNull() ? "<null>" : value.textValue();
    }

    private static String requiredText(JsonNode object, String field, String context) {
        JsonNode value = object.path(field);
        assertTrue(value.isTextual(), context + " must be a string, actual=" + value);
        return value.textValue();
    }

    private static String textOrNull(JsonNode value, String context) {
        assertTrue(value.isTextual() || value.isNull() || value.isMissingNode(),
                context + " must be a string or null, actual=" + value);
        return value.isTextual() ? value.textValue() : null;
    }

    private List<String> validateCardValues(String raw, String side, String lineId) throws Exception {
        JsonNode root = mapper.readTree(raw);
        JsonNode precisionTab = findTab(root, PRECISION_TAB);
        JsonNode stabilityTab = findTab(root, STABILITY_TAB);
        assertNotNull(precisionTab, side + " precision tab missing for line " + lineId);
        assertNotNull(stabilityTab, side + " stability tab missing for line " + lineId);
        assertEquals(2, precisionTab.path("baseRows").size(),
                side + " precision baseRows must contain exactly two stable rows");
        assertEquals(2, stabilityTab.path("baseRows").size(),
                side + " second tab must contain exactly two stable rows");

        JsonNode formulaResults = precisionTab.path("formulaResults");
        assertTrue(formulaResults.isArray(), side + " precision formulaResults must be an array");
        assertEquals(2, formulaResults.size(),
                side + " precision formulaResults must contain exactly two rows");
        List<String> values = new ArrayList<>();
        for (JsonNode row : formulaResults) {
            JsonNode result = row.path("values").path(RESULT_FIELD);
            assertTrue(result.isTextual(), side + " " + RESULT_FIELD
                    + " must be a decimal string, actual=" + result);
            values.add(result.asText());
        }
        assertEquals(EXPECTED_PRECISION_VALUES, values,
                side + " identity formula must preserve both 12-place driver values in row order");
        return values;
    }

    private static JsonNode findTab(JsonNode root, String tabName) {
        for (JsonNode tab : root.path("tabs")) {
            if (tabName.equals(tab.path("tabName").asText())) return tab;
        }
        return null;
    }

    private FixtureAssets validateAllAssets() throws Exception {
        em.clear();
        Customer customer = findTaggedCustomer();
        assertNotNull(customer, "Partial fixture: tagged customer is missing");
        validateTaggedCustomer(customer);

        Component precision = findTaggedComponent(PRECISION_COMPONENT_CODE);
        Component stability = findTaggedComponent(STABILITY_COMPONENT_CODE);
        validateTaggedComponent(precision, TAG + " precision rows", PRECISION_VIEW,
                precisionDriverSql(), 3, 1);
        validateTaggedComponent(stability, TAG + " stability rows", STABILITY_VIEW,
                stabilityDriverSql(), 2, 0);
        Number sqlViewCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM component_sql_view WHERE component_id IN (:precision,:stability)")
                .setParameter("precision", precision.id)
                .setParameter("stability", stability.id)
                .getSingleResult();
        assertEquals(2, sqlViewCount.intValue(), "Fixture must have exactly two component SQL views");

        Template quoteTemplate = findTaggedTemplate(QUOTE_TEMPLATE_NAME);
        Template costingTemplate = findTaggedTemplate(COSTING_TEMPLATE_NAME);
        validateTaggedTemplate(quoteTemplate, QUOTE_TEMPLATE_NAME, "QUOTATION", precision.id, stability.id);
        validateTaggedTemplate(costingTemplate, COSTING_TEMPLATE_NAME, "COSTING", precision.id, stability.id);
        assertTemplateSymmetry(quoteTemplate.id, costingTemplate.id);

        Quotation simple = findSeed(SIMPLE_NAME);
        Quotation composite = findSeed(COMPOSITE_NAME);
        assertNotNull(simple, "Partial fixture: SIMPLE seed is missing");
        assertNotNull(composite, "Partial fixture: COMPOSITE seed is missing");
        validateSeedReadOnly(simple, SIMPLE_NAME, customer.id, quoteTemplate.id, costingTemplate.id,
                precision.id, stability.id, false);
        validateSeedReadOnly(composite, COMPOSITE_NAME, customer.id, quoteTemplate.id, costingTemplate.id,
                precision.id, stability.id, true);

        Number componentDataCount = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM quotation_line_component_data cd "
                                + "JOIN quotation_line_item li ON li.id=cd.line_item_id "
                                + "WHERE li.quotation_id IN (:simple,:composite)")
                .setParameter("simple", simple.id)
                .setParameter("composite", composite.id)
                .getSingleResult();
        assertEquals(8, componentDataCount.intValue(),
                "Fixture must have exactly eight component-data rows across both seeds");
        return new FixtureAssets(customer, precision, stability, quoteTemplate, costingTemplate,
                simple, composite);
    }

    private void validateTaggedComponent(
            Component component,
            String expectedName,
            String expectedViewName,
            String expectedSql,
            int expectedFieldCount,
            int expectedFormulaCount) throws Exception {
        assertEquals(expectedName, component.name, "Tagged component name");
        assertEquals("ACTIVE", component.status, "Tagged component status");
        assertEquals("$" + expectedViewName, component.dataDriverPath,
                "Tagged component driver path");
        assertNotNull(component.rowKeyFields, "Tagged component row-key fields");
        assertEquals(List.of(ROW_KEY_FIELD),
                mapper.readTree(component.rowKeyFields).valueStream().map(JsonNode::asText).toList(),
                "Tagged component row-key fields");
        assertEquals(expectedFieldCount, mapper.readTree(component.fields).size(),
                "Tagged component field count");
        assertEquals(expectedFormulaCount, mapper.readTree(component.formulas).size(),
                "Tagged component formula count");

        List<ComponentSqlView> views = ComponentSqlView.list(
                "componentId = ?1 ORDER BY sqlViewName", component.id);
        assertEquals(1, views.size(), "Tagged component must have exactly one SQL view");
        ComponentSqlView view = views.get(0);
        assertEquals(expectedViewName, view.sqlViewName, "Tagged component SQL view name");
        assertEquals(expectedSql, view.sqlTemplate, "Tagged component SQL template");
        assertEquals("ACTIVE", view.status, "Tagged component SQL view status");
    }

    private void validateTaggedTemplate(
            Template template,
            String expectedName,
            String expectedKind,
            UUID precisionComponentId,
            UUID stabilityComponentId) throws Exception {
        assertEquals(expectedName, template.name, "Tagged template name");
        assertPublishedTemplate(template.id, expectedKind);
        List<String> expectedContract = List.of(
                precisionComponentId + "|" + PRECISION_TAB,
                stabilityComponentId + "|" + STABILITY_TAB);
        assertEquals(expectedContract, templateComponentContract(template.id),
                expectedKind + " active template component contract");
        assertEquals(expectedContract, templateSnapshotIdentityContract(template.id),
                expectedKind + " published template snapshot contract");
        assertEquals(List.of(0, 1),
                TemplateComponent.<TemplateComponent>list(
                                "templateId = ?1 ORDER BY sortOrder", template.id)
                        .stream().map(row -> row.sortOrder).toList(),
                expectedKind + " active template component sort order");
        assertEquals(List.of(0, 1),
                TemplateComponentSnapshot.<TemplateComponentSnapshot>list(
                                "templateId = ?1 ORDER BY sortOrder", template.id)
                        .stream().map(row -> row.sortOrder).toList(),
                expectedKind + " published template snapshot sort order");
    }

    private void validateSeedReadOnly(
            Quotation quotation,
            String expectedName,
            UUID expectedCustomerId,
            UUID quoteTemplateId,
            UUID costingTemplateId,
            UUID precisionComponentId,
            UUID stabilityComponentId,
            boolean compositeExpected) {
        assertEquals(expectedName, quotation.name, "Fixture quotation name");
        assertEquals("DRAFT", quotation.status, "Seed must remain copyable/editable");
        assertEquals("STANDARD", quotation.quoteType, "Fixture quotation quote type");
        assertEquals(expectedCustomerId, quotation.customerId,
                "Fixture quotation must belong to the tagged customer");
        assertEquals(quoteTemplateId, quotation.customerTemplateId, "Fixture quote template binding");
        assertEquals(costingTemplateId, quotation.costingCardTemplateId,
                "Fixture costing template binding");

        List<QuotationLineItem> lines = QuotationLineItem.list(
                "quotationId = ?1 ORDER BY sortOrder, id", quotation.id);
        int expectedLineCount = compositeExpected ? 3 : 1;
        assertEquals(expectedLineCount, lines.size(), "Fixture quotation exact line count");
        assertEquals(compositeExpected
                        ? List.of(COMPOSITE_PART_NO, PART_ONE_NO, PART_TWO_NO)
                        : List.of(SIMPLE_PART_NO),
                lines.stream().map(line -> line.productPartNoSnapshot).toList(),
                "Fixture quotation exact part order");
        assertEquals(compositeExpected ? List.of(0, 1, 2) : List.of(0),
                lines.stream().map(line -> line.sortOrder).toList(),
                "Fixture quotation exact sort order");
        for (QuotationLineItem line : lines) {
            assertEquals(quoteTemplateId, line.templateId, "Fixture line template binding");
        }

        if (compositeExpected) {
            QuotationLineItem parent = lines.get(0);
            assertEquals("COMPOSITE", parent.compositeType, "COMPOSITE parent type");
            assertNull(parent.parentLineItemId, "COMPOSITE parent must not have a parent");
            for (QuotationLineItem child : lines.subList(1, 3)) {
                assertEquals("PART", child.compositeType, "COMPOSITE child type");
                assertEquals(parent.id, child.parentLineItemId,
                        "Both PART rows must point to the COMPOSITE parent line id");
            }
        } else {
            assertEquals("SIMPLE", lines.get(0).compositeType, "SIMPLE line type");
            assertNull(lines.get(0).parentLineItemId, "SIMPLE line parent must be null");
        }

        Map<UUID, List<QuotationLineComponentData>> componentRowsByLine = new LinkedHashMap<>();
        lines.forEach(line -> componentRowsByLine.put(line.id, new ArrayList<>()));
        List<UUID> lineIds = lines.stream().map(line -> line.id).toList();
        List<QuotationLineComponentData> componentRows = QuotationLineComponentData.list(
                "lineItemId in ?1 ORDER BY sortOrder, id", lineIds);
        componentRows.forEach(row -> {
            List<QuotationLineComponentData> ownerRows = componentRowsByLine.get(row.lineItemId);
            assertNotNull(ownerRows, "Component row must belong to the validated seed");
            ownerRows.add(row);
        });
        assertEquals(expectedLineCount * 2, componentRows.size(),
                "Fixture exact component-data count");
        for (QuotationLineItem line : lines) {
            List<QuotationLineComponentData> rows = componentRowsByLine.get(line.id);
            assertEquals(2, rows.size(), "Each fixture line must have exactly two component rows");
            assertEquals(List.of(precisionComponentId, stabilityComponentId),
                    rows.stream().map(row -> row.componentId).toList(),
                    "Fixture line component identity/order");
            assertEquals(List.of(PRECISION_TAB, STABILITY_TAB),
                    rows.stream().map(row -> row.tabName).toList(),
                    "Fixture line component tab order");
            assertEquals(List.of(0, 1), rows.stream().map(row -> row.sortOrder).toList(),
                    "Fixture line component sort order");
        }
    }

    private void assertTemplateSymmetry(UUID quoteTemplateId, UUID costingTemplateId) throws Exception {
        List<String> quote = templateComponentContract(quoteTemplateId);
        List<String> costing = templateComponentContract(costingTemplateId);
        assertEquals(List.of(PRECISION_TAB, STABILITY_TAB),
                quote.stream().map(x -> x.substring(x.indexOf('|') + 1)).toList(),
                "QUOTE template tab order");
        assertEquals(quote, costing, "QUOTE/COSTING templates must use identical components and tabs");

        List<String> quoteSnapshots = templateSnapshotContract(quoteTemplateId);
        List<String> costingSnapshots = templateSnapshotContract(costingTemplateId);
        assertEquals(2, quoteSnapshots.size(), "QUOTE normalized template snapshot count");
        assertEquals(quoteSnapshots, costingSnapshots,
                "QUOTE/COSTING normalized template snapshots must be isomorphic");

        Template quoteTemplate = Template.findById(quoteTemplateId);
        Template costingTemplate = Template.findById(costingTemplateId);
        assertEquals(normalizedComponentsSnapshot(quoteTemplate.componentsSnapshot),
                normalizedComponentsSnapshot(costingTemplate.componentsSnapshot),
                "QUOTE/COSTING components_snapshot must be isomorphic");
        assertEquals(mapper.readTree(quoteTemplate.sqlViewsSnapshot),
                mapper.readTree(costingTemplate.sqlViewsSnapshot),
                "QUOTE/COSTING sql_views_snapshot must be isomorphic");
    }

    private JsonNode normalizedComponentsSnapshot(String raw) throws Exception {
        JsonNode parsed = mapper.readTree(raw);
        assertTrue(parsed.isArray(), "components_snapshot must be an array");
        ArrayNode normalized = ((ArrayNode) parsed).deepCopy();
        for (JsonNode entry : normalized) {
            assertTrue(entry.isObject(), "components_snapshot entries must be objects");
            ((ObjectNode) entry).remove("id");
        }
        return normalized;
    }

    private List<String> templateComponentContract(UUID templateId) {
        return TemplateComponent.<TemplateComponent>list("templateId = ?1 ORDER BY sortOrder", templateId)
                .stream()
                .map(tc -> tc.componentId + "|" + tc.tabName)
                .toList();
    }

    private List<String> templateSnapshotContract(UUID templateId) {
        return TemplateComponentSnapshot.<TemplateComponentSnapshot>list(
                        "templateId = ?1 ORDER BY sortOrder", templateId)
                .stream()
                .map(snapshot -> snapshot.componentId + "|" + snapshot.sortOrder + "|"
                        + snapshot.tabName + "|" + snapshot.fields + "|" + snapshot.formulas)
                .toList();
    }

    private List<String> templateSnapshotIdentityContract(UUID templateId) {
        return TemplateComponentSnapshot.<TemplateComponentSnapshot>list(
                        "templateId = ?1 ORDER BY sortOrder", templateId)
                .stream()
                .map(snapshot -> snapshot.componentId + "|" + snapshot.tabName)
                .toList();
    }

    private void assertPublishedTemplate(UUID templateId, String kind) throws Exception {
        assertNotNull(templateId, kind + " template binding missing");
        Template template = Template.findById(templateId);
        assertNotNull(template, kind + " template missing: " + templateId);
        assertEquals("PUBLISHED", template.status);
        assertEquals(kind, template.templateKind);
        assertTrue(mapper.readTree(template.componentsSnapshot).isArray(),
                kind + " components_snapshot must be a normalized array");
        assertEquals(2, mapper.readTree(template.componentsSnapshot).size(),
                kind + " components_snapshot tab count");
        assertTrue(mapper.readTree(template.sqlViewsSnapshot).isObject(),
                kind + " sql_views_snapshot must be an object");
        assertEquals(2, mapper.readTree(template.sqlViewsSnapshot).size(),
                kind + " sql_views_snapshot count");
    }

    private String assertIsolatedDatabase() {
        String expected = System.getProperty(EXPECTED_DB_PROPERTY, "").trim();
        assertFalse(expected.isEmpty(), "Enabled fixture builds require -D" + EXPECTED_DB_PROPERTY
                + "=<exact disposable database name>");
        String normalizedExpected = expected.toLowerCase(Locale.ROOT);
        assertFalse(FORBIDDEN_DATABASES.contains(normalizedExpected),
                "Refusing fixture build against shared/system database: " + expected);
        assertTrue(normalizedExpected.startsWith("task0810_")
                        || normalizedExpected.startsWith("e2e_")
                        || normalizedExpected.startsWith("cpq_task0810_"),
                "Disposable fixture database must use a task0810_*, e2e_*, or cpq_task0810_* name: "
                        + expected);

        String actual = String.valueOf(em.createNativeQuery("SELECT current_database()").getSingleResult());
        String normalizedActual = actual.toLowerCase(Locale.ROOT);
        assertFalse(FORBIDDEN_DATABASES.contains(normalizedActual),
                "Refusing fixture build against shared/system database: " + actual);
        assertEquals(expected, actual,
                "Connected database must exactly match -D" + EXPECTED_DB_PROPERTY);
        return actual;
    }

    private void assertV385Applied() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT version, description, success FROM flyway_schema_history WHERE version='385'")
                .getResultList();
        assertEquals(1, rows.size(), "V385 must be applied exactly once before building Stage H fixtures");
        assertEquals("task0810 formula scale12", rows.get(0)[1], "Unexpected V385 description");
        assertEquals(Boolean.TRUE, rows.get(0)[2], "V385 flyway_schema_history.success must be true");
    }

    private void assertNoPartialAssets() {
        Number quotations = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM quotation WHERE name IN (:simple,:composite)")
                .setParameter("simple", SIMPLE_NAME)
                .setParameter("composite", COMPOSITE_NAME)
                .getSingleResult();
        Number templates = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM template WHERE name IN (:quote,:costing)")
                .setParameter("quote", QUOTE_TEMPLATE_NAME)
                .setParameter("costing", COSTING_TEMPLATE_NAME)
                .getSingleResult();
        Number components = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM component WHERE code IN (:precision,:stability)")
                .setParameter("precision", PRECISION_COMPONENT_CODE)
                .setParameter("stability", STABILITY_COMPONENT_CODE)
                .getSingleResult();
        Number customers = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM customer WHERE name=:name")
                .setParameter("name", CUSTOMER_NAME)
                .getSingleResult();
        if (quotations.intValue() != 0 || templates.intValue() != 0
                || components.intValue() != 0 || customers.intValue() != 0) {
            fail("Partial " + TAG + " assets exist (quotations=" + quotations + ", templates="
                    + templates + ", components=" + components + ", customers=" + customers
                    + "). Restore the disposable DB snapshot instead of guessing dependency-order deletes.");
        }
    }

    private boolean hasAnyTaggedAssets() {
        Number count = (Number) em.createNativeQuery(
                        "SELECT "
                                + "(SELECT count(*) FROM quotation WHERE name IN (:simple,:composite)) + "
                                + "(SELECT count(*) FROM template WHERE name IN (:quote,:costing)) + "
                                + "(SELECT count(*) FROM component WHERE code IN (:precision,:stability)) + "
                                + "(SELECT count(*) FROM customer WHERE name=:customer)")
                .setParameter("simple", SIMPLE_NAME)
                .setParameter("composite", COMPOSITE_NAME)
                .setParameter("quote", QUOTE_TEMPLATE_NAME)
                .setParameter("costing", COSTING_TEMPLATE_NAME)
                .setParameter("precision", PRECISION_COMPONENT_CODE)
                .setParameter("stability", STABILITY_COMPONENT_CODE)
                .setParameter("customer", CUSTOMER_NAME)
                .getSingleResult();
        return count.intValue() > 0;
    }

    private Customer findTaggedCustomer() {
        List<Customer> rows = Customer.list("name = ?1 ORDER BY createdAt", CUSTOMER_NAME);
        assertTrue(rows.size() <= 1, "Duplicate fixture customer name: " + CUSTOMER_NAME);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Component findTaggedComponent(String code) {
        List<Component> rows = Component.list("code = ?1 ORDER BY createdAt", code);
        assertTrue(rows.size() <= 1, "Duplicate fixture component code: " + code);
        assertFalse(rows.isEmpty(), "Partial fixture: component is missing: " + code);
        return rows.get(0);
    }

    private Template findTaggedTemplate(String name) {
        List<Template> rows = Template.list("name = ?1 ORDER BY createdAt", name);
        assertTrue(rows.size() <= 1, "Duplicate fixture template name: " + name);
        assertFalse(rows.isEmpty(), "Partial fixture: template is missing: " + name);
        return rows.get(0);
    }

    private void validateTaggedCustomer(Customer customer) {
        assertEquals(CUSTOMER_NAME, customer.name, "Tagged customer name");
        assertEquals(CUSTOMER_REMARKS, customer.remarks, "Tagged customer remarks");
        assertEquals("ACTIVE", customer.status, "Tagged customer status");
        List<CustomerContact> contacts = CustomerContact.list(
                "customerId = ?1 ORDER BY isPrimary DESC, createdAt", customer.id);
        assertEquals(1, contacts.size(), "Tagged customer must have exactly one contact");
        assertTrue(Boolean.TRUE.equals(contacts.get(0).isPrimary),
                "Tagged customer contact must be primary");
        assertEquals(CUSTOMER_CONTACT_NAME, contacts.get(0).name,
                "Tagged customer contact name");
        assertEquals(CUSTOMER_CONTACT_ROLE, contacts.get(0).role,
                "Tagged customer contact role");
        assertEquals(CUSTOMER_CONTACT_PHONE, contacts.get(0).phone,
                "Tagged customer contact phone");
        assertEquals(CUSTOMER_CONTACT_EMAIL, contacts.get(0).email,
                "Tagged customer contact email");
    }

    private Quotation findSeed(String name) {
        List<Quotation> rows = Quotation.list("name = ?1 ORDER BY createdAt", name);
        assertTrue(rows.size() <= 1, "Duplicate fixture seed name: " + name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private UUID adminUserId() {
        List<Object> rows = em.createNativeQuery(
                        "SELECT id FROM \"user\" WHERE role='SYSTEM_ADMIN' ORDER BY created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) {
            rows = em.createNativeQuery("SELECT id FROM \"user\" ORDER BY created_at LIMIT 1")
                    .getResultList();
        }
        assertFalse(rows.isEmpty(), "Stage H DB requires at least one user");
        return toUuid(rows.get(0));
    }

    private void printDelivery(UUID simpleId, UUID compositeId, String disposition, String databaseName) {
        Quotation simple = Quotation.findById(simpleId);
        Quotation composite = Quotation.findById(compositeId);
        System.out.println("========== " + TAG + " STAGE H " + disposition + " ==========");
        System.out.println("database=" + databaseName + " (isolated; destroy/restore after TC-076)");
        System.out.println("PW_PRECISION_SEED_QUOTATION_NO=" + simple.quotationNumber);
        System.out.println("PW_PRECISION_COMPOSITE_QUOTATION_NO=" + composite.quotationNumber);
        System.out.println("============================================================");
    }

    private static Map<String, Object> driverField(
            String name, String fieldType, String viewName, String column) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("field_type", fieldType);
        field.put("default_source", Map.of(
                "type", "BASIC_DATA",
                "path", "$" + viewName + "." + column));
        return field;
    }

    private static Map<String, Object> formulaField() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", RESULT_FIELD);
        field.put("field_type", "FORMULA");
        field.put("formula_id", FORMULA_ID);
        field.put("formula_name", RESULT_FIELD);
        return field;
    }

    private static String precisionDriverSql() {
        return driverRows("CASE row_key "
                + "WHEN '01' THEN '1.234567891234'::numeric(26,12) "
                + "ELSE '98765431.123456789012'::numeric(26,12) END AS precision_input");
    }

    private static String stabilityDriverSql() {
        return driverRows("('stable-' || row_key)::text AS stable_value");
    }

    private static String driverRows(String projectedValue) {
        String values = "SELECT * FROM (VALUES "
                + "('" + SIMPLE_PART_NO + "','01'),('" + SIMPLE_PART_NO + "','02'),"
                + "('" + COMPOSITE_PART_NO + "','01'),('" + COMPOSITE_PART_NO + "','02'),"
                + "('" + PART_ONE_NO + "','01'),('" + PART_ONE_NO + "','02'),"
                + "('" + PART_TWO_NO + "','01'),('" + PART_TWO_NO + "','02')) "
                + "AS fixture_rows(hf_part_no,row_key)";
        return "SELECT hf_part_no, row_key, " + projectedValue + " FROM (" + values + ") fixture "
                + "ORDER BY hf_part_no, row_key";
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
