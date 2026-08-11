package com.cpq.quotation;

import com.cpq.common.PrecisionHttpContractSupport;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.configure.service.ConfigureSnapshotService;
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
@TestProfile(QuotationPrecisionHttpContractTest.RbacOffProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotationPrecisionHttpContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TREE_FIXTURE_TAG = "T0810-P1-TREE";
    private static final String MATERIAL_TAB = "\u6750\u8d28\u5143\u7d20";
    private static final String PART_NO_FIELD = "\u6599\u53f7";
    private static final String SYNTHETIC_RECURSIVE_SQL = """
            WITH RECURSIVE edges(parent_no, material_no) AS (
              VALUES
                ('3120018220'::text, '2120011658'::text),
                ('3120018220'::text, '2120011659'::text),
                ('2120011658'::text, '3110520789'::text),
                ('2120011659'::text, '3110520789'::text),
                ('3110520789'::text, '2101110225'::text)
            ),
            bom AS (
              SELECT p::text AS root_no, p::text AS material_no, CAST(NULL AS text) AS bom_version,
                     CAST(NULL AS text) AS parent_no, p::text AS node_path
              FROM unnest(:production_part_nos) AS p
              UNION ALL
              SELECT b.root_no, e.material_no, CAST(NULL AS text) AS bom_version, e.parent_no,
                     (b.node_path || '/' || e.material_no)::text AS node_path
              FROM edges e JOIN bom b ON e.parent_no = b.material_no
            )
            SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom
            """;

    private static final Set<String> QUOTATION_PRECISION_FIELDS = Set.of(
            "originalAmount", "systemDiscountRate", "finalDiscountRate", "totalAmount",
            "taxRate", "taxAmount", "subtotal", "discountBaseAmount", "discountRateApplied",
            "lineDiscountAmount", "lineUnitPrice", "lineFinalPrice", "lineTotalAmount",
            "frontendValue", "backendValue", "frontendInputs", "backendInputs");

    private static final Set<String> STRUCTURAL_INTEGER_FIELDS = Set.of(
            "code", "page", "size", "number", "totalElements", "totalPages", "numberOfElements",
            "deliveryCycle", "sortOrder", "rowIndex", "row_index", "annualVolume", "recorded",
            "refreshed", "versionedGroups", "addedRows", "deletedRows", "changedRows",
            "affectedProducts", "lvl", "__lvl", "remainingOccurrences");

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @Inject
    ConfigureSnapshotService configureSnapshotService;

    private final List<UUID> quotationIds = new ArrayList<>();
    private UUID customerId;
    private String runId;
    private UUID quoteTreeConfigId;
    private UUID preExistingActiveQuoteConfigId;
    private UUID treeComponentId;
    private UUID materialComponentId;
    private UUID treeViewId;
    private UUID materialViewId;
    private UUID treeTemplateId;
    private UUID treeTemplateComponentId;
    private UUID materialTemplateComponentId;
    private UUID treeComponentSnapshotId;
    private UUID materialComponentSnapshotId;

    @BeforeEach
    void createCustomerFixture() {
        if (customerId != null) {
            return;
        }
        runId = Long.toUnsignedString(System.nanoTime());
        String body = """
                {
                  "name":"T0810 Precision Customer %s",
                  "level":"STANDARD",
                  "contacts":[{"name":"Precision Contact","phone":"139%s","isPrimary":true}]
                }
                """.formatted(runId, runId.substring(Math.max(0, runId.length() - 8)));
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/customers");
        assertEquals(200, response.statusCode(), response.asString());
        customerId = UUID.fromString(response.jsonPath().getString("data.id"));
    }

    @AfterEach
    void cleanupQuotationFixtures() {
        List<String> cleanupFailures = new ArrayList<>();
        for (UUID quotationId : quotationIds) {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id IN "
                                + "(SELECT id FROM costing_order WHERE quotation_id = :id)")
                        .setParameter("id", quotationId)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id = :id")
                        .setParameter("id", quotationId)
                        .executeUpdate();
                Quotation quotation = em.find(Quotation.class, quotationId);
                if (quotation != null) {
                    quotation.status = "DRAFT";
                    em.merge(quotation);
                }
            });
            Response deleted = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .delete("/api/cpq/quotations/" + quotationId);
            if (deleted.statusCode() != 200) {
                cleanupFailures.add(quotationId + ": status=" + deleted.statusCode()
                        + " body=" + deleted.asString());
            }
        }
        quotationIds.clear();
        cleanupTreeFixture();
        assertTrue(cleanupFailures.isEmpty(), () -> "Fixture cleanup failed: " + cleanupFailures);
    }

    @AfterAll
    void cleanupCustomerFixture() {
        if (customerId != null) {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id = :id")
                        .setParameter("id", customerId)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM customer WHERE id = :id")
                        .setParameter("id", customerId)
                        .executeUpdate();
            });
        }
    }

    @Test
    void p1_01To03_listDetailAndCreateReturnDecimalStrings() {
        Response created = createDraftQuotation("p1-crud");
        UUID quotationId = UUID.fromString(created.jsonPath().getString("data.id"));
        seedHeaderDecimals(quotationId);

        Response detail = RestAssured.given().get("/api/cpq/quotations/" + quotationId);
        assertEquals(200, detail.statusCode(), detail.asString());
        JsonNode detailJson = PrecisionHttpContractSupport.readJson(detail);
        assertHeaderDecimals(detailJson, "/data");
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(detailJson, QUOTATION_PRECISION_FIELDS);

        Response list = RestAssured.given()
                .queryParam("keyword", "T0810-p1-crud-" + runId)
                .queryParam("page", 0)
                .queryParam("size", 20)
                .get("/api/cpq/quotations");
        assertEquals(200, list.statusCode(), list.asString());
        JsonNode listJson = PrecisionHttpContractSupport.readJson(list);
        JsonNode matching = findById(listJson.at("/data/content"), quotationId.toString());
        assertNotNull(matching, list.asString());
        assertHeaderDecimals(matching, "");
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(listJson, QUOTATION_PRECISION_FIELDS);

        JsonNode createdJson = PrecisionHttpContractSupport.readJson(created);
        PrecisionHttpContractSupport.assertTextualOrNull(
                createdJson, "/data/originalAmount", "/data/finalDiscountRate", "/data/totalAmount");
    }

    @Test
    void p1_04_draftAcceptsStringsAndRejectsEveryNumericPrecisionCarrierWithoutWrites() {
        UUID quotationId = quotationId(createDraftQuotation("p1-draft"));

        Response accepted = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name":"T0810 draft accepted",
                          "finalDiscountRate":"99.123456789012",
                          "lineItems":[]
                        }
                        """)
                .put("/api/cpq/quotations/" + quotationId + "/draft");
        assertEquals(200, accepted.statusCode(), accepted.asString());
        JsonNode acceptedJson = PrecisionHttpContractSupport.readJson(accepted);
        PrecisionHttpContractSupport.assertTextualOrNull(acceptedJson, "/data/finalDiscountRate");

        assertRejectedWithoutWrites(quotationId,
                "{\"finalDiscountRate\":" + PrecisionHttpContractSupport.DECIMAL_12 + "}",
                "finalDiscountRate", PrecisionHttpContractSupport.DECIMAL_12);
        assertRejectedWithoutWrites(quotationId,
                "{\"lineItems\":[{\"subtotal\":" + PrecisionHttpContractSupport.DECIMAL_12 + "}]}",
                "lineItems[0].subtotal", PrecisionHttpContractSupport.DECIMAL_12);
        assertRejectedWithoutWrites(quotationId,
                "{\"lineItems\":[{\"productAttributeValues\":\"{\\\"amount\\\":"
                        + PrecisionHttpContractSupport.DECIMAL_12 + "}\"}]}",
                "productAttributeValues.amount", PrecisionHttpContractSupport.DECIMAL_12);
        assertRejectedWithoutWrites(quotationId,
                "{\"lineItems\":[{\"quoteExcelValues\":\"{\\\"rows\\\":[{\\\"row_index\\\":1,"
                        + "\\\"amount\\\":" + PrecisionHttpContractSupport.DECIMAL_12 + "}]}\"}]}",
                "quoteExcelValues.rows[0].amount", PrecisionHttpContractSupport.DECIMAL_12);
        assertRejectedWithoutWrites(quotationId,
                "{\"lineItems\":[{\"componentData\":[{\"rowData\":\"[{\\\"序号\\\":1,"
                        + "\\\"_项次\\\":2,\\\"annualVolume\\\":5,\\\"数量\\\":"
                        + PrecisionHttpContractSupport.DECIMAL_12 + "}]\"}]}]}",
                "rowData[0].数量", PrecisionHttpContractSupport.DECIMAL_12);
        assertRejectedWithoutWrites(quotationId,
                "{\"lineItems\":[{\"compositeProcesses\":[{\"defCode\":\"D1\",\"seqNo\":1,"
                        + "\"participatingParts\":[],\"paramValues\":{\"rate\":"
                        + PrecisionHttpContractSupport.DECIMAL_12 + "}}]}]}",
                "paramValues.rate", PrecisionHttpContractSupport.DECIMAL_12);
    }

    @Test
    void tc052_rateDecimalString_roundTripsAtExistingTwoDigitScale() throws Exception {
        UUID quotationId = quotationId(createDraftQuotation("tc052-rate"));
        String rateText = "99.12";

        Response saved = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"TC-052 rate\",\"finalDiscountRate\":\""
                        + rateText + "\",\"lineItems\":[]}")
                .put("/api/cpq/quotations/" + quotationId + "/draft");
        assertEquals(200, saved.statusCode(), saved.asString());
        JsonNode savedJson = PrecisionHttpContractSupport.readJson(saved);
        assertTrue(savedJson.at("/data/finalDiscountRate").isTextual(), saved.asString());
        assertEquals(rateText, savedJson.at("/data/finalDiscountRate").asText());

        BigDecimal stored = QuarkusTransaction.requiringNew().call(() -> (BigDecimal) em.createNativeQuery(
                "SELECT final_discount_rate FROM quotation WHERE id=:id")
            .setParameter("id", quotationId).getSingleResult());
        assertEquals(0, new BigDecimal(rateText).compareTo(stored),
            "TC-052 rate must round-trip without floating point");
        assertEquals(2, stored.scale(), "TC-052 quotation rate keeps the existing scale=2 contract");

        Response reopened = RestAssured.given().get("/api/cpq/quotations/" + quotationId);
        assertEquals(200, reopened.statusCode(), reopened.asString());
        JsonNode reopenedRate = PrecisionHttpContractSupport.readJson(reopened)
            .at("/data/finalDiscountRate");
        assertTrue(reopenedRate.isTextual(), reopened.asString());
        assertEquals(rateText, reopenedRate.asText());

        Object[] schema = QuarkusTransaction.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                "SELECT numeric_precision,numeric_scale FROM information_schema.columns "
                    + "WHERE table_schema=current_schema() AND table_name='quotation' "
                    + "AND column_name='final_discount_rate'")
            .getSingleResult());
        assertEquals(5, ((Number) schema[0]).intValue(), "TC-052 existing DB rate precision");
        assertEquals(2, ((Number) schema[1]).intValue(), "TC-052 existing DB rate scale");

        jakarta.persistence.Column mapping = Quotation.class.getDeclaredField("finalDiscountRate")
            .getAnnotation(jakarta.persistence.Column.class);
        assertNotNull(mapping);
        assertEquals(5, mapping.precision(), "TC-052 existing JPA rate precision");
        assertEquals(2, mapping.scale(), "TC-052 existing JPA rate scale");
    }

    @Test
    void p1_05To10_refreshEnsureDiscountAndRecalculateUseStringContract() {
        UUID quotationId = quotationId(createDraftQuotation("p1-refresh"));
        seedHeaderDecimals(quotationId);

        Response refresh = postEmpty("/api/cpq/quotations/" + quotationId + "/refresh-card-snapshot");
        assertEquals(200, refresh.statusCode(), refresh.asString());
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(
                PrecisionHttpContractSupport.readJson(refresh), STRUCTURAL_INTEGER_FIELDS);

        for (String route : List.of("ensure-card-values", "ensure-excel-values")) {
            Response response = postEmpty("/api/cpq/quotations/" + quotationId + "/" + route);
            assertEquals(200, response.statusCode(), response.asString());
            JsonNode json = PrecisionHttpContractSupport.readJson(response);
            assertHeaderDecimals(json, "/data");
            PrecisionHttpContractSupport.assertFieldsTextualOrNull(json, QUOTATION_PRECISION_FIELDS);
        }

        Response discount = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"originalAmount\":\"98765431.123456789012\"}")
                .post("/api/cpq/quotations/" + quotationId + "/calculate-discount");
        assertEquals(200, discount.statusCode(), discount.asString());
        JsonNode discountJson = PrecisionHttpContractSupport.readJson(discount);
        PrecisionHttpContractSupport.assertTextualOrNull(
                discountJson, "/data/originalAmount", "/data/finalDiscountRate", "/data/totalAmount");

        PrecisionHttpContractSupport.QuotationFingerprint before =
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId);
        Response rejectedDiscount = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"originalAmount\":" + PrecisionHttpContractSupport.DECIMAL_12 + "}")
                .post("/api/cpq/quotations/" + quotationId + "/calculate-discount");
        PrecisionHttpContractSupport.assertBadRequest(
                rejectedDiscount, "originalAmount", PrecisionHttpContractSupport.DECIMAL_12);
        PrecisionHttpContractSupport.assertUnchanged(
                before, PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId));

        Response recalculate = postEmpty("/api/cpq/quotations/" + quotationId + "/recalculate");
        assertEquals(200, recalculate.statusCode(), recalculate.asString());
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(
                PrecisionHttpContractSupport.readJson(recalculate), QUOTATION_PRECISION_FIELDS);
    }

    @Test
    void p1_08_quoteCardEditRejectsNumericValueAndRowDataWithoutWrites() {
        UUID quotationId = quotationId(createDraftQuotation("p1-card-edit"));
        UUID missingLineItemId = UUID.randomUUID();
        PrecisionHttpContractSupport.QuotationFingerprint before =
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId);

        Response numericValue = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"value\":" + PrecisionHttpContractSupport.DECIMAL_12 + "}")
                .put("/api/cpq/quotations/line-items/" + missingLineItemId + "/quote-card-edit");
        PrecisionHttpContractSupport.assertBadRequest(
                numericValue, "value", PrecisionHttpContractSupport.DECIMAL_12);
        PrecisionHttpContractSupport.assertUnchanged(
                before, PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId));

        Response numericRowData = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"rowData\":{\"amount\":" + PrecisionHttpContractSupport.DECIMAL_12 + "}}")
                .put("/api/cpq/quotations/line-items/" + missingLineItemId + "/quote-card-edit");
        PrecisionHttpContractSupport.assertBadRequest(
                numericRowData, "rowData.amount", PrecisionHttpContractSupport.DECIMAL_12);
        PrecisionHttpContractSupport.assertUnchanged(
                before, PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId));
    }

    @Test
    void p1_11To14_submitSnapshotTraceAndCopyKeepDecimalStrings() {
        UUID quotationId = quotationId(createDraftQuotation("p1-submit"));
        seedHeaderDecimals(quotationId);

        Response submit = postEmpty("/api/cpq/quotations/" + quotationId + "/submit");
        assertEquals(200, submit.statusCode(), submit.asString());
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(
                PrecisionHttpContractSupport.readJson(submit), QUOTATION_PRECISION_FIELDS);

        QuarkusTransaction.requiringNew().run(() -> {
            Quotation quotation = em.find(Quotation.class, quotationId);
            quotation.submissionSnapshot = "{\"totalAmount\":\"98765431.123456789012\","
                    + "\"quantity\":\"0.000000000001\",\"annualVolume\":5}";
            em.merge(quotation);
        });
        Response snapshot = RestAssured.given().get("/api/cpq/quotations/" + quotationId + "/snapshot");
        assertEquals(200, snapshot.statusCode(), snapshot.asString());
        JsonNode snapshotJson = PrecisionHttpContractSupport.readJson(snapshot);
        PrecisionHttpContractSupport.assertTextualOrNull(
                snapshotJson, "/data/totalAmount", "/data/quantity");
        assertTrue(snapshotJson.at("/data/annualVolume").isIntegralNumber(), snapshot.asString());

        Response trace = RestAssured.given()
                .queryParam("fieldPath", "lineItems[0].componentData[0].rowData.unit_price")
                .get("/api/cpq/quotations/" + quotationId + "/field-trace");
        assertEquals(200, trace.statusCode(), trace.asString());
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(
                PrecisionHttpContractSupport.readJson(trace), QUOTATION_PRECISION_FIELDS);

        Response copy = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/api/cpq/quotations/" + quotationId + "/copy");
        assertEquals(200, copy.statusCode(), copy.asString());
        UUID copyId = UUID.fromString(copy.jsonPath().getString("data.id"));
        quotationIds.add(copyId);
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(
                PrecisionHttpContractSupport.readJson(copy), QUOTATION_PRECISION_FIELDS);
    }

    @Test
    void p1_15And16_costingPreviewAndApproveUseTheSameStringContract() {
        UUID quotationId = quotationId(createDraftQuotation("p1-costing"));
        seedHeaderDecimals(quotationId);
        Response submit = postEmpty("/api/cpq/quotations/" + quotationId + "/submit");
        assertEquals(200, submit.statusCode(), submit.asString());

        Response preview = RestAssured.given()
                .get("/api/cpq/quotations/" + quotationId + "/costing-approve/preview");
        assertEquals(200, preview.statusCode(), preview.asString());
        JsonNode previewJson = PrecisionHttpContractSupport.readJson(preview);
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(previewJson, STRUCTURAL_INTEGER_FIELDS);
        String previewToken = previewJson.at("/data/previewToken").asText();
        assertFalse(previewToken.isBlank(), preview.asString());

        Response approved = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"comment\":\"precision contract\",\"previewToken\":\"" + previewToken + "\"}")
                .post("/api/cpq/quotations/" + quotationId + "/costing-approve");
        assertEquals(200, approved.statusCode(), approved.asString());
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(
                PrecisionHttpContractSupport.readJson(approved), QUOTATION_PRECISION_FIELDS);
    }

    @Test
    void p1_17_reconcileAcceptsStringsAndRejectsNumericValuesWithoutWrites() {
        UUID quotationId = quotationId(createDraftQuotation("p1-reconcile"));
        UUID lineItemId = UUID.randomUUID();
        Response accepted = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"diffs":[{
                          "componentId":"component-a","tabName":"tab-a","rowKey":"row-a","fieldName":"amount",
                          "frontendValue":"98765431.123456789012","backendValue":"98765431.123456789011",
                          "frontendInputs":{"quantity":"0.000000000001"},
                          "backendInputs":{"quantity":"0.000000000001"}
                        }]}
                        """)
                .post("/api/cpq/quotations/line-items/" + lineItemId + "/reconcile-report");
        assertEquals(202, accepted.statusCode(), accepted.asString());
        JsonNode acceptedJson = PrecisionHttpContractSupport.readJson(accepted);
        assertTrue(acceptedJson.at("/data/recorded").isIntegralNumber(), accepted.asString());

        PrecisionHttpContractSupport.QuotationFingerprint before =
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId);
        Response rejected = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"diffs\":[{\"frontendValue\":"
                        + PrecisionHttpContractSupport.DECIMAL_12 + "}]}")
                .post("/api/cpq/quotations/line-items/" + lineItemId + "/reconcile-report");
        PrecisionHttpContractSupport.assertBadRequest(
                rejected, "diffs.frontendValue", PrecisionHttpContractSupport.DECIMAL_12);
        PrecisionHttpContractSupport.assertUnchanged(
                before, PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId));
    }

    @Test
    void p1_18aTo18e_treeAndDriverDtosHaveNoPrecisionInputsAndReachRealRoutes() {
        UUID quotationId = quotationId(createDraftQuotation("p1-tree-routing"));
        UUID lineItemId = buildTreeFixture(quotationId);
        configureSnapshotService.snapshotQuotation(quotationId);

        Response addLeaf = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"componentId\":\"" + treeComponentId + "\","
                        + "\"hostNodeId\":\"3120018220/2120011658\",\"partNo\":\"2101110225\"}")
                .post("/api/cpq/quotations/" + quotationId + "/line-items/" + lineItemId + "/tree/add-leaf");
        assertHandledSuccess(addLeaf);

        String deleteNodeId = "3120018220/2120011658/3110520789";
        Response preview = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"componentId\":\"" + treeComponentId
                        + "\",\"mode\":\"PRUNE\",\"nodeId\":\"" + deleteNodeId + "\"}")
                .post("/api/cpq/quotations/" + quotationId + "/line-items/" + lineItemId + "/tree/delete-preview");
        assertHandledSuccess(preview);
        String previewToken = preview.jsonPath().getString("data.previewToken");
        assertNotNull(previewToken, preview.asString());

        Response execute = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"componentId\":\"" + treeComponentId
                        + "\",\"mode\":\"PRUNE\",\"nodeId\":\"" + deleteNodeId
                        + "\",\"previewToken\":\"" + previewToken + "\"}")
                .post("/api/cpq/quotations/" + quotationId + "/line-items/" + lineItemId + "/tree/delete");
        assertHandledSuccess(execute);

        Response deleteDriverRow = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"componentId\":\"" + materialComponentId
                        + "\",\"effKey\":\"2101110225\",\"fp\":\"\"}")
                .post("/api/cpq/quotations/" + quotationId + "/line-items/" + lineItemId + "/delete-driver-row");
        assertHandledSuccess(deleteDriverRow);

        Response restoreDriverRows = RestAssured.given().contentType(ContentType.JSON)
                .body("{\"componentId\":\"" + materialComponentId + "\"}")
                .post("/api/cpq/quotations/" + quotationId + "/line-items/" + lineItemId + "/restore-driver-rows");
        assertHandledSuccess(restoreDriverRows);
    }

    private UUID buildTreeFixture(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> activeConfigs = em.createNativeQuery(
                            "SELECT id FROM costing_bom_tree_config "
                                    + "WHERE usage = 'QUOTE' AND is_active = true")
                    .getResultList();
            if (!activeConfigs.isEmpty()) {
                preExistingActiveQuoteConfigId = toUuid(activeConfigs.get(0));
                em.createNativeQuery("UPDATE costing_bom_tree_config SET is_active = false WHERE id = :id")
                        .setParameter("id", preExistingActiveQuoteConfigId)
                        .executeUpdate();
            }

            quoteTreeConfigId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO costing_bom_tree_config "
                            + "(id, name, sql_template, is_active, usage, created_at, updated_at) "
                            + "VALUES (:id, :name, :sql, true, 'QUOTE', now(), now())")
                    .setParameter("id", quoteTreeConfigId)
                    .setParameter("name", TREE_FIXTURE_TAG + "-config")
                    .setParameter("sql", SYNTHETIC_RECURSIVE_SQL)
                    .executeUpdate();

            Component treeComponent = new Component();
            treeComponent.name = TREE_FIXTURE_TAG + "-BOM";
            treeComponent.code = TREE_FIXTURE_TAG + "-TREE-" + UUID.randomUUID().toString().substring(0, 6);
            treeComponent.fields = "[]";
            treeComponent.formulas = "[]";
            treeComponent.tabType = "BOM";
            treeComponent.bomRecursiveExpand = true;
            treeComponent.dataDriverPath = "$t0810_p1_tree_view";
            treeComponent.persist();
            treeComponentId = treeComponent.id;

            ComponentSqlView treeView = new ComponentSqlView();
            treeView.componentId = treeComponentId;
            treeView.sqlViewName = "t0810_p1_tree_view";
            treeView.sqlTemplate = "SELECT NULL::text AS material_no, NULL::text AS parent_no WHERE FALSE";
            treeView.declaredColumns = "[]";
            treeView.persist();
            treeViewId = treeView.id;

            Component materialComponent = new Component();
            materialComponent.name = TREE_FIXTURE_TAG + "-MATERIAL";
            materialComponent.code = TREE_FIXTURE_TAG + "-MAT-" + UUID.randomUUID().toString().substring(0, 6);
            materialComponent.fields = "[{\"name\":\"" + PART_NO_FIELD
                    + "\",\"field_type\":\"INPUT_TEXT\"}]";
            materialComponent.formulas = "[]";
            materialComponent.tabType = MATERIAL_TAB;
            materialComponent.partNoField = PART_NO_FIELD;
            materialComponent.dataDriverPath = "$t0810_p1_material_view";
            materialComponent.persist();
            materialComponentId = materialComponent.id;

            ComponentSqlView materialView = new ComponentSqlView();
            materialView.componentId = materialComponentId;
            materialView.sqlViewName = "t0810_p1_material_view";
            materialView.sqlTemplate = "SELECT '3120018220'::text AS hf_part_no, "
                    + "'2101110225'::text AS material_no, '2101110225'::text AS \""
                    + PART_NO_FIELD + "\"";
            materialView.declaredColumns = "[]";
            materialView.persist();
            materialViewId = materialView.id;

            Template template = new Template();
            template.templateSeriesId = UUID.randomUUID();
            template.name = TREE_FIXTURE_TAG + "-TEMPLATE";
            template.templateKind = "QUOTATION";
            template.status = "DRAFT";
            template.createdAt = OffsetDateTime.now();
            template.updatedAt = OffsetDateTime.now();
            template.persist();
            treeTemplateId = template.id;

            TemplateComponent treeTemplateComponent = new TemplateComponent();
            treeTemplateComponent.templateId = treeTemplateId;
            treeTemplateComponent.componentId = treeComponentId;
            treeTemplateComponent.tabName = "BOM";
            treeTemplateComponent.createdAt = OffsetDateTime.now();
            treeTemplateComponent.persist();
            treeTemplateComponentId = treeTemplateComponent.id;

            TemplateComponent materialTemplateComponent = new TemplateComponent();
            materialTemplateComponent.templateId = treeTemplateId;
            materialTemplateComponent.componentId = materialComponentId;
            materialTemplateComponent.tabName = MATERIAL_TAB;
            materialTemplateComponent.createdAt = OffsetDateTime.now();
            materialTemplateComponent.persist();
            materialTemplateComponentId = materialTemplateComponent.id;

            TemplateComponentSnapshot treeSnapshot = new TemplateComponentSnapshot();
            treeSnapshot.templateId = treeTemplateId;
            treeSnapshot.templateComponentId = treeTemplateComponentId;
            treeSnapshot.componentId = treeComponentId;
            treeSnapshot.sortOrder = 0;
            treeSnapshot.tabName = "BOM";
            treeSnapshot.componentName = treeComponent.name;
            treeSnapshot.componentCode = treeComponent.code;
            treeSnapshot.componentType = "NORMAL";
            treeSnapshot.fields = treeComponent.fields;
            treeSnapshot.formulas = treeComponent.formulas;
            treeSnapshot.dataDriverPath = treeComponent.dataDriverPath;
            treeSnapshot.bomRecursiveExpand = true;
            treeSnapshot.tabType = "BOM";
            treeSnapshot.persist();
            treeComponentSnapshotId = treeSnapshot.id;

            TemplateComponentSnapshot materialSnapshot = new TemplateComponentSnapshot();
            materialSnapshot.templateId = treeTemplateId;
            materialSnapshot.templateComponentId = materialTemplateComponentId;
            materialSnapshot.componentId = materialComponentId;
            materialSnapshot.sortOrder = 1;
            materialSnapshot.tabName = MATERIAL_TAB;
            materialSnapshot.componentName = materialComponent.name;
            materialSnapshot.componentCode = materialComponent.code;
            materialSnapshot.componentType = "NORMAL";
            materialSnapshot.fields = materialComponent.fields;
            materialSnapshot.formulas = materialComponent.formulas;
            materialSnapshot.dataDriverPath = materialComponent.dataDriverPath;
            materialSnapshot.tabType = MATERIAL_TAB;
            materialSnapshot.partNoField = PART_NO_FIELD;
            materialSnapshot.persist();
            materialComponentSnapshotId = materialSnapshot.id;

            try {
                var snapshot = MAPPER.createArrayNode();
                var treeEntry = snapshot.addObject();
                treeEntry.put("id", treeTemplateComponentId.toString());
                treeEntry.put("componentId", treeComponentId.toString());
                treeEntry.put("componentName", treeComponent.name);
                treeEntry.put("componentCode", treeComponent.code);
                treeEntry.put("componentType", "NORMAL");
                treeEntry.put("tabName", "BOM");
                treeEntry.put("sortOrder", BigDecimal.ZERO);
                treeEntry.set("fields", MAPPER.readTree(treeComponent.fields));
                treeEntry.set("formulas", MAPPER.readTree(treeComponent.formulas));
                treeEntry.put("data_driver_path", treeComponent.dataDriverPath);

                var materialEntry = snapshot.addObject();
                materialEntry.put("id", materialTemplateComponentId.toString());
                materialEntry.put("componentId", materialComponentId.toString());
                materialEntry.put("componentName", materialComponent.name);
                materialEntry.put("componentCode", materialComponent.code);
                materialEntry.put("componentType", "NORMAL");
                materialEntry.put("tabName", MATERIAL_TAB);
                materialEntry.put("sortOrder", BigDecimal.ONE);
                materialEntry.set("fields", MAPPER.readTree(materialComponent.fields));
                materialEntry.set("formulas", MAPPER.readTree(materialComponent.formulas));
                materialEntry.put("data_driver_path", materialComponent.dataDriverPath);

                em.createNativeQuery("UPDATE template SET components_snapshot = CAST(:snapshot AS jsonb) "
                                + "WHERE id = :id")
                        .setParameter("snapshot", MAPPER.writeValueAsString(snapshot))
                        .setParameter("id", treeTemplateId)
                        .executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to build tree template snapshot", e);
            }

            em.createNativeQuery("UPDATE quotation SET customer_template_id = :templateId WHERE id = :id")
                    .setParameter("templateId", treeTemplateId)
                    .setParameter("id", quotationId)
                    .executeUpdate();

            UUID lineItemId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO quotation_line_item "
                            + "(id, quotation_id, template_id, product_part_no_snapshot, sort_order, created_at) "
                            + "VALUES (:id, :quotationId, :templateId, '3120018220', 0, now())")
                    .setParameter("id", lineItemId)
                    .setParameter("quotationId", quotationId)
                    .setParameter("templateId", treeTemplateId)
                    .executeUpdate();
            return lineItemId;
        });
    }

    private void cleanupTreeFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (treeComponentSnapshotId != null) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE id = :id")
                        .setParameter("id", treeComponentSnapshotId).executeUpdate();
            }
            if (materialComponentSnapshotId != null) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE id = :id")
                        .setParameter("id", materialComponentSnapshotId).executeUpdate();
            }
            if (treeTemplateComponentId != null) {
                em.createNativeQuery("DELETE FROM template_component WHERE id = :id")
                        .setParameter("id", treeTemplateComponentId).executeUpdate();
            }
            if (materialTemplateComponentId != null) {
                em.createNativeQuery("DELETE FROM template_component WHERE id = :id")
                        .setParameter("id", materialTemplateComponentId).executeUpdate();
            }
            if (treeTemplateId != null) {
                em.createNativeQuery("DELETE FROM template WHERE id = :id")
                        .setParameter("id", treeTemplateId).executeUpdate();
            }
            if (treeViewId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id")
                        .setParameter("id", treeViewId).executeUpdate();
            }
            if (materialViewId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id")
                        .setParameter("id", materialViewId).executeUpdate();
            }
            if (treeComponentId != null) {
                em.createNativeQuery("DELETE FROM component WHERE id = :id")
                        .setParameter("id", treeComponentId).executeUpdate();
            }
            if (materialComponentId != null) {
                em.createNativeQuery("DELETE FROM component WHERE id = :id")
                        .setParameter("id", materialComponentId).executeUpdate();
            }
            if (quoteTreeConfigId != null) {
                em.createNativeQuery("DELETE FROM costing_bom_tree_config WHERE id = :id")
                        .setParameter("id", quoteTreeConfigId).executeUpdate();
            }
            if (preExistingActiveQuoteConfigId != null) {
                em.createNativeQuery("UPDATE costing_bom_tree_config SET is_active = true WHERE id = :id")
                        .setParameter("id", preExistingActiveQuoteConfigId).executeUpdate();
            }
        });
        quoteTreeConfigId = null;
        preExistingActiveQuoteConfigId = null;
        treeComponentId = null;
        materialComponentId = null;
        treeViewId = null;
        materialViewId = null;
        treeTemplateId = null;
        treeTemplateComponentId = null;
        materialTemplateComponentId = null;
        treeComponentSnapshotId = null;
        materialComponentSnapshotId = null;
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Response createDraftQuotation(String label) {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"customerId\":\"" + customerId + "\",\"name\":\"T0810-"
                        + label + "-" + runId + "\",\"quoteType\":\"STANDARD\"}")
                .post("/api/cpq/quotations");
        assertEquals(200, response.statusCode(), response.asString());
        quotationIds.add(quotationId(response));
        return response;
    }

    private static UUID quotationId(Response response) {
        return UUID.fromString(response.jsonPath().getString("data.id"));
    }

    private void seedHeaderDecimals(UUID quotationId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Quotation quotation = em.find(Quotation.class, quotationId);
            quotation.originalAmount = new BigDecimal(PrecisionHttpContractSupport.DECIMAL_12);
            quotation.systemDiscountRate = new BigDecimal("99.123456789012");
            quotation.finalDiscountRate = new BigDecimal("98.123456789012");
            quotation.totalAmount = new BigDecimal("96876543.123456789012");
            quotation.taxRate = new BigDecimal("0.123456789012");
            quotation.taxAmount = new BigDecimal("1196000.000000000001");
            em.merge(quotation);
        });
    }

    private static void assertHeaderDecimals(JsonNode json, String prefix) {
        PrecisionHttpContractSupport.assertTextualOrNull(json,
                prefix + "/originalAmount", prefix + "/systemDiscountRate",
                prefix + "/finalDiscountRate", prefix + "/totalAmount",
                prefix + "/taxRate", prefix + "/taxAmount");
    }

    private static JsonNode findById(JsonNode array, String id) {
        if (!array.isArray()) {
            return null;
        }
        for (JsonNode item : array) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    private void assertRejectedWithoutWrites(
            UUID quotationId, String body, String expectedPath, String originalValue) {
        PrecisionHttpContractSupport.QuotationFingerprint before =
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId);
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .put("/api/cpq/quotations/" + quotationId + "/draft");
        PrecisionHttpContractSupport.assertBadRequest(response, expectedPath, originalValue);
        PrecisionHttpContractSupport.assertUnchanged(
                before, PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId));
    }

    private static void assertHandledClientError(Response response) {
        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                () -> "Expected handled client error, status=" + response.statusCode() + " body=" + response.asString());
        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, STRUCTURAL_INTEGER_FIELDS);
    }

    private static void assertHandledSuccess(Response response) {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "Expected 2xx, status=" + response.statusCode() + " body=" + response.asString());
        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, STRUCTURAL_INTEGER_FIELDS);
    }

    private static Response postEmpty(String path) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post(path);
    }
}
