package com.cpq.quotation.service;

import com.cpq.basicdata.entity.DerivedAttribute;
import com.cpq.product.entity.Product;
import com.cpq.quotation.entity.CostingOrder;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.cpq.costing.service.ComparisonViewService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(NonDraftPrecisionReadOnlyTest.RbacOffProfile.class)
class NonDraftPrecisionReadOnlyTest {

    private static final String LEGACY_DECIMAL = "98765431.123456789012";

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @Inject
    QuotationService quotationService;

    @Inject
    CardSnapshotService cardSnapshotService;

    @Inject
    QuotationExportService exportService;

    @Inject
    ComparisonViewService comparisonViewService;

    private final Set<UUID> quotationIds = new LinkedHashSet<>();
    private final Set<UUID> templateIds = new LinkedHashSet<>();
    private final Set<UUID> customerIds = new LinkedHashSet<>();
    private final Set<UUID> productIds = new LinkedHashSet<>();
    private final Set<UUID> basicConfigIds = new LinkedHashSet<>();
    private final Set<UUID> derivedAttributeIds = new LinkedHashSet<>();

    @AfterEach
    void cleanupOwnedFixtures() {
        if (quotationIds.isEmpty() && templateIds.isEmpty() && customerIds.isEmpty()
                && productIds.isEmpty() && basicConfigIds.isEmpty()) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID quotationId : quotationIds) {
                em.createNativeQuery("DELETE FROM costing_order_version_override WHERE costing_order_id IN "
                        + "(SELECT id FROM costing_order WHERE quotation_id=:id)")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM costing_order WHERE quotation_id=:id")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_approval WHERE quotation_id=:id")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_price_revision WHERE quotation_id=:id")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id=:id")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=:id)")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=:id)")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id=:id")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id=:id")
                    .setParameter("id", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id=:id")
                    .setParameter("id", quotationId).executeUpdate();
            }
            for (UUID id : derivedAttributeIds) {
                em.createNativeQuery("DELETE FROM derived_attribute WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            }
            for (UUID id : basicConfigIds) {
                em.createNativeQuery("DELETE FROM basic_data_config WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            }
            for (UUID id : productIds) {
                em.createNativeQuery("DELETE FROM product WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            }
            for (UUID id : templateIds) {
                em.createNativeQuery("DELETE FROM template WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            }
            for (UUID id : customerIds) {
                em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                    .setParameter("id", id).executeUpdate();
            }
        });
        quotationIds.clear();
        templateIds.clear();
        customerIds.clear();
        productIds.clear();
        basicConfigIds.clear();
        derivedAttributeIds.clear();
    }

    @Test
    @TestTransaction
    void approvedQuotationReadsAndExportsCauseZeroUpdatesEvenWithMissingSnapshots() {
        UUID quotationId = quotationWithLineItem();
        assertNotNull(quotationId, "test database must contain a quotation with a line item");

        em.createNativeQuery("UPDATE quotation SET status = 'APPROVED' WHERE id = :id")
                .setParameter("id", quotationId)
                .executeUpdate();
        em.createNativeQuery("""
                UPDATE quotation_line_item
                   SET quote_card_values = NULL,
                       quote_excel_values = NULL,
                       costing_card_values = NULL,
                       costing_excel_values = NULL
                 WHERE quotation_id = :id
                """)
                .setParameter("id", quotationId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :id")
                .setParameter("id", quotationId)
                .executeUpdate();
        em.flush();
        em.clear();

        Object[] before = persistenceFingerprint(quotationId);

        assertNotNull(quotationService.getById(quotationId));
        assertEquals(0, cardSnapshotService.ensureCardValues(quotationId));
        assertEquals(0, cardSnapshotService.ensureExcelValues(quotationId));
        assertTrue(exportService.exportHtml(quotationId, true, false, false).length > 0);
        assertTrue(exportService.exportExcel(quotationId, true, false).length > 0);
        assertNotNull(comparisonViewService.getMeta(quotationId));

        em.flush();
        em.clear();
        Object[] after = persistenceFingerprint(quotationId);
        assertArrayEquals(before, after,
                "APPROVED read/export path changed quotation, line item, or component data");
    }

    @Test
    void tc056_draftDetailGetIsReadOnlyWithExistingOrMissingViewStructure() {
        DraftFixture normal = createDraftStructureFixture();
        cardSnapshotService.ensureStructure(normal.quotationId());
        DraftFingerprint normalBefore = draftFingerprint(normal.quotationId());
        assertTrue(normalBefore.structureCount() > 0, "TC-056 normal fixture must have structures");

        Response normalGet = RestAssured.given()
            .get("/api/cpq/quotations/" + normal.quotationId());
        assertEquals(200, normalGet.statusCode(), normalGet.asString());
        assertEquals(normalBefore, draftFingerprint(normal.quotationId()),
            "TC-056 opening a normal DRAFT must be strictly read-only");

        DraftFixture missing = createDraftStructureFixture();
        DraftFingerprint missingBefore = draftFingerprint(missing.quotationId());
        assertEquals(0L, missingBefore.structureCount(),
            "TC-056 missing-structure fixture precondition");

        Response missingGet = RestAssured.given()
            .get("/api/cpq/quotations/" + missing.quotationId());
        assertEquals(200, missingGet.statusCode(), missingGet.asString());
        assertEquals(missingBefore, draftFingerprint(missing.quotationId()),
            "TC-056 GET must not materialize missing DRAFT view structures");
    }

    @Test
    void tc057_recalculatePreservesHistoricalNumericAndWritesOnlyCanonicalDerivedString()
            throws Exception {
        RecalculateFixture fixture = createRecalculateFixture();
        NonTargetFingerprint nonTargetBefore = nonTargetFingerprint(fixture.lineItemId());

        Response response = RestAssured.given()
            .contentType(ContentType.JSON)
            .post("/api/cpq/quotations/" + fixture.quotationId() + "/recalculate");
        assertEquals(200, response.statusCode(), response.asString());
        JsonNode responseJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.asString());
        JsonNode responseOriginal = responseJson.at("/data/originalAmount");
        JsonNode responseTotal = responseJson.at("/data/totalAmount");

        RecalculateResult result = readRecalculateResult(
            fixture.quotationId(), fixture.lineItemId(), fixture.derivedCode());
        NonTargetFingerprint nonTargetAfter = nonTargetFingerprint(fixture.lineItemId());

        assertAll(
            () -> assertTrue(responseOriginal.isTextual(), response.asString()),
            () -> assertTrue(responseTotal.isTextual(), response.asString()),
            () -> assertEquals(0, new BigDecimal(LEGACY_DECIMAL).compareTo(result.originalAmount()),
                "TC-057 header original_amount must retain all 12 decimals"),
            () -> assertEquals(0, new BigDecimal(LEGACY_DECIMAL).compareTo(result.totalAmount()),
                "TC-057 header total_amount must retain all 12 decimals"),
            () -> assertEquals(12, result.originalAmount().scale(),
                "TC-057 original_amount storage scale"),
            () -> assertEquals(12, result.totalAmount().scale(),
                "TC-057 total_amount storage scale"),
            () -> assertEquals(LEGACY_DECIMAL, result.legacyText(),
                "TC-057 historical numeric must not pass through Double"),
            () -> assertEquals("number", result.legacyType(),
                "TC-057 historical numeric token type must remain unchanged"),
            () -> assertEquals("0.123456789012", result.derivedText(),
                "TC-057 derived value must retain the 12-place node"),
            () -> assertEquals("string", result.derivedType(),
                "TC-057 only the new derived key is written as a decimal string"),
            () -> assertEquals(nonTargetBefore, nonTargetAfter,
                "TC-057 recalculate must not rewrite row_data/snapshot_rows/card/excel JSON"));
    }

    @Test
    void tc053_submittedReadAndExportRoutesRemainReadOnlyForThreeRounds() {
        SubmittedFixture fixture = createSubmittedFixture(false);
        FullFingerprint baseline = fullFingerprint(fixture.quotationId());
        assertEquals(4L, structureCount(fixture.quotationId()),
            "TC-053 fixture must contain all four frozen view structures");

        for (int round = 1; round <= 3; round++) {
            Response detail = assertReadOnly(
                fixture.quotationId(), baseline, "round " + round + " detail",
                RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId()));
            if (round == 1) assertQuotationFrozenPayload(detail);
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " snapshot",
                RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId() + "/snapshot"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " ensure-card",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/ensure-card-values", "{}"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " ensure-excel",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/ensure-excel-values", "{}"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " refresh",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/refresh-card-snapshot", "{}"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " html",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/export/html", "{}"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " pdf",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/export/pdf", "{}"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " quotation excel",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/export/excel", "{}"));
            assertReadOnly(fixture.quotationId(), baseline, "round " + round + " excel-view export",
                RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId()
                    + "/export-excel-view"));
        }
    }

    @Test
    void tc055_historicalNumericJsonRemainsExactAndByteStableAcrossReadRounds() {
        SubmittedFixture fixture = createSubmittedFixture(true);
        HistoricalNumericState original = historicalNumericState(fixture);
        assertHistoricalNumericState(original);
        FullFingerprint baseline = fullFingerprint(fixture.quotationId());

        for (int round = 1; round <= 3; round++) {
            assertReadOnly(fixture.quotationId(), baseline, "numeric round " + round + " detail",
                RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId()));
            assertReadOnly(fixture.quotationId(), baseline, "numeric round " + round + " snapshot",
                RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId() + "/snapshot"));
            assertReadOnly(fixture.quotationId(), baseline, "numeric round " + round + " costing detail",
                RestAssured.given().get("/api/cpq/costing-orders/" + fixture.costingOrderId()));
            assertReadOnly(fixture.quotationId(), baseline, "numeric round " + round + " frozen comparison",
                RestAssured.given().queryParam("frozen", true)
                    .get("/api/cpq/quotations/" + fixture.quotationId() + "/comparison-view/data"));
            assertReadOnly(fixture.quotationId(), baseline, "numeric round " + round + " html",
                jsonPost("/api/cpq/quotations/" + fixture.quotationId() + "/export/html", "{}"));
        }

        HistoricalNumericState after = historicalNumericState(fixture);
        assertEquals(original, after, "TC-055 historical JSON text/type/hash must remain byte-stable");
    }

    private SubmittedFixture createSubmittedFixture(boolean historicalNumeric) {
        SubmittedFixture fixture = QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = createCustomer();
            UUID userId = firstUserId();
            Template template = new Template();
            template.templateSeriesId = UUID.randomUUID();
            template.name = "TC-053 submitted template " + UUID.randomUUID();
            template.status = "PUBLISHED";
            template.templateKind = "QUOTATION";
            template.componentsSnapshot = "[]";
            template.sqlViewsSnapshot = "{}";
            template.templateSqlViewsSnapshot = "{}";
            template.excelViewConfig = "[{\"col_key\":\"legacy\",\"source_type\":"
                + "\"PRODUCT_ATTRIBUTE\",\"field_key\":\"legacy\"}]";
            template.persist();
            templateIds.add(template.id);

            String precisionJson = historicalNumeric
                ? "{\"legacy\":" + LEGACY_DECIMAL + "}"
                : "{\"legacy\":\"" + LEGACY_DECIMAL + "\"}";
            Quotation quotation = new Quotation();
            quotation.quotationNumber = "TC053-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.name = "TC-053 submitted read-only";
            quotation.salesRepId = userId;
            quotation.status = "DRAFT";
            quotation.customerTemplateId = template.id;
            quotation.costingCardTemplateId = template.id;
            quotation.originalAmount = new BigDecimal(LEGACY_DECIMAL);
            quotation.totalAmount = new BigDecimal(LEGACY_DECIMAL);
            quotation.submissionSnapshot = precisionJson;
            quotation.persist();
            quotationIds.add(quotation.id);

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotation.id;
            line.templateId = template.id;
            line.productNameSnapshot = "TC submitted line";
            line.productPartNoSnapshot = "TC-SUBMITTED";
            line.productAttributeValues = precisionJson;
            line.subtotal = new BigDecimal(LEGACY_DECIMAL);
            line.quoteCardValues = precisionJson;
            line.quoteExcelValues = precisionJson;
            line.costingCardValues = precisionJson;
            line.costingExcelValues = precisionJson;
            line.persist();

            QuotationLineComponentData componentData = new QuotationLineComponentData();
            componentData.lineItemId = line.id;
            componentData.tabName = "TC historical";
            componentData.rowData = "[" + precisionJson + "]";
            componentData.snapshotRows = "[" + precisionJson + "]";
            componentData.subtotal = new BigDecimal(LEGACY_DECIMAL);
            componentData.persist();

            em.createNativeQuery("INSERT INTO quotation_component_sql_snapshot "
                    + "(quotation_id,sql_view_key,sql_template,declared_columns,required_variables) "
                    + "VALUES (:id,'tc::view','SELECT 1','[]','{}')")
                .setParameter("id", quotation.id).executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_line_item_snapshot "
                    + "(line_item_id,product_part_no,product_category,product_specification) "
                    + "VALUES (:id,'TC-SKU','TEST','TC specification')")
                .setParameter("id", line.id).executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_price_revision "
                    + "(quotation_id,revision_no,sealed,upgraded_material_nos,quote_card_values,"
                    + "costing_card_values,snapshot_rows,quote_total_amount) "
                    + "VALUES (:id,:revision,true,'[]',CAST(:json AS jsonb),CAST(:json AS jsonb),"
                    + "CAST(:json AS jsonb),123.123456)")
                .setParameter("id", quotation.id)
                .setParameter("revision", "R" + UUID.randomUUID().toString().substring(0, 8))
                .setParameter("json", precisionJson).executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_approval "
                    + "(quotation_id,approver_id,action,comment,acted_at) "
                    + "VALUES (:id,:user,'APPROVED','TC read-only',now())")
                .setParameter("id", quotation.id).setParameter("user", userId).executeUpdate();

            CostingOrder costingOrder = new CostingOrder();
            costingOrder.quotationId = quotation.id;
            costingOrder.costingOrderNumber = "TC-CO-" + UUID.randomUUID();
            costingOrder.status = "PENDING";
            costingOrder.totalAmount = new BigDecimal(LEGACY_DECIMAL);
            costingOrder.costingTotalAmount = new BigDecimal(LEGACY_DECIMAL);
            costingOrder.frozenDto = historicalNumeric
                ? "{\"legacy\":" + LEGACY_DECIMAL + ",\"lineItems\":[]}"
                : "{\"legacy\":\"" + LEGACY_DECIMAL + "\",\"lineItems\":[]}";
            costingOrder.costingRender = precisionJson;
            costingOrder.persist();

            em.createNativeQuery("INSERT INTO costing_order_version_override "
                    + "(costing_order_id,component_id,part_no,view_version) "
                    + "VALUES (:id,:component,'TC-PART','2000')")
                .setParameter("id", costingOrder.id)
                .setParameter("component", UUID.randomUUID()).executeUpdate();
            return new SubmittedFixture(
                quotation.id, line.id, componentData.id, costingOrder.id);
        });

        QuarkusTransaction.requiringNew().run(() -> {
            for (String viewKind : List.of(
                    "QUOTE_CARD", "QUOTE_EXCEL", "COSTING_CARD", "COSTING_EXCEL")) {
                String structure = "{\"viewKind\":\"" + viewKind + "\","
                    + "\"tabs\":[{\"componentId\":\"frozen\","
                    + "\"subtotal\":\"" + LEGACY_DECIMAL + "\"}]}";
                em.createNativeQuery("INSERT INTO quotation_view_structure "
                        + "(quotation_id,view_kind,structure) VALUES (:id,:kind,CAST(:structure AS jsonb))")
                    .setParameter("id", fixture.quotationId())
                    .setParameter("kind", viewKind)
                    .setParameter("structure", structure).executeUpdate();
            }
            em.createNativeQuery("UPDATE quotation SET status='SUBMITTED' WHERE id=:id")
                .setParameter("id", fixture.quotationId()).executeUpdate();
        });
        return fixture;
    }

    private Response assertReadOnly(UUID quotationId, FullFingerprint baseline,
            String label, Response response) {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
            label + " failed: " + response.asString());
        assertTrue(response.asByteArray().length > 0, label + " returned an empty body");
        assertEquals(baseline, fullFingerprint(quotationId),
            "TC read path wrote persistence after " + label);
        return response;
    }

    private void assertQuotationFrozenPayload(Response response) {
        try {
            JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.asString()).path("data");
            assertTrue(data.path("lineItems").isArray() && !data.path("lineItems").isEmpty(),
                response.asString());
            assertTrue(data.path("quoteCardStructure").isObject(), response.asString());
            assertTrue(data.path("quoteExcelStructure").isObject(), response.asString());
            assertTrue(data.path("costingCardStructure").isObject(), response.asString());
            assertTrue(data.path("costingExcelStructure").isObject(), response.asString());
            JsonNode line = data.path("lineItems").path(0);
            assertTrue(line.path("quoteCardValues").isTextual(), response.asString());
            assertTrue(line.path("quoteExcelValues").isTextual(), response.asString());
            assertTrue(line.path("costingCardValues").isTextual(), response.asString());
            assertTrue(line.path("costingExcelValues").isTextual(), response.asString());
        } catch (Exception e) {
            throw new AssertionError("TC-053 detail must expose non-empty frozen structures/values", e);
        }
    }

    private long structureCount(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_view_structure WHERE quotation_id=:id")
            .setParameter("id", quotationId).getSingleResult()).longValue());
    }

    private static Response jsonPost(String path, String body) {
        return RestAssured.given().contentType(ContentType.JSON).body(body).post(path);
    }

    private FullFingerprint fullFingerprint(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> new FullFingerprint(List.of(
            tableFingerprint("quotation", "id=:id", quotationId),
            tableFingerprint("quotation_line_item", "quotation_id=:id", quotationId),
            tableFingerprint("quotation_line_component_data",
                "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)", quotationId),
            tableFingerprint("quotation_view_structure", "quotation_id=:id", quotationId),
            tableFingerprint("quotation_component_sql_snapshot", "quotation_id=:id", quotationId),
            tableFingerprint("quotation_line_item_snapshot",
                "line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)", quotationId),
            tableFingerprint("quotation_price_revision", "quotation_id=:id", quotationId),
            tableFingerprint("costing_order", "quotation_id=:id", quotationId),
            tableFingerprint("costing_order_version_override",
                "costing_order_id IN (SELECT id FROM costing_order WHERE quotation_id=:id)", quotationId),
            tableFingerprint("quotation_approval", "quotation_id=:id", quotationId))));
    }

    private String tableFingerprint(String table, String predicate, UUID quotationId) {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT count(*),COALESCE(md5(string_agg(xmin::text || ':' || "
                    + "md5(to_jsonb(t)::text),'|' ORDER BY to_jsonb(t)::text)),'none') "
                    + "FROM " + table + " t WHERE " + predicate)
            .setParameter("id", quotationId).getSingleResult();
        return row[0] + ":" + row[1];
    }

    private HistoricalNumericState historicalNumericState(SubmittedFixture fixture) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery("""
                SELECT li.product_attribute_values->>'legacy',
                       jsonb_typeof(li.product_attribute_values->'legacy'),
                       cd.row_data->0->>'legacy', jsonb_typeof(cd.row_data->0->'legacy'),
                       cd.snapshot_rows->0->>'legacy', jsonb_typeof(cd.snapshot_rows->0->'legacy'),
                       li.quote_card_values->>'legacy', jsonb_typeof(li.quote_card_values->'legacy'),
                       li.quote_excel_values->>'legacy', jsonb_typeof(li.quote_excel_values->'legacy'),
                       li.costing_card_values->>'legacy', jsonb_typeof(li.costing_card_values->'legacy'),
                       li.costing_excel_values->>'legacy', jsonb_typeof(li.costing_excel_values->'legacy'),
                       q.submission_snapshot->>'legacy', jsonb_typeof(q.submission_snapshot->'legacy'),
                       co.frozen_dto->>'legacy', jsonb_typeof(co.frozen_dto->'legacy'),
                       co.costing_render->>'legacy', jsonb_typeof(co.costing_render->'legacy')
                  FROM quotation q
                  JOIN quotation_line_item li ON li.quotation_id=q.id
                  JOIN quotation_line_component_data cd ON cd.line_item_id=li.id
                  JOIN costing_order co ON co.quotation_id=q.id
                 WHERE q.id=:id AND li.id=:lineId AND cd.id=:componentDataId AND co.id=:costingOrderId
                """)
                .setParameter("id", fixture.quotationId())
                .setParameter("lineId", fixture.lineItemId())
                .setParameter("componentDataId", fixture.componentDataId())
                .setParameter("costingOrderId", fixture.costingOrderId())
                .getSingleResult();
            List<String> values = new ArrayList<>(row.length);
            for (Object value : row) values.add(String.valueOf(value));
            return new HistoricalNumericState(values, fullFingerprint(fixture.quotationId()));
        });
    }

    private static void assertHistoricalNumericState(HistoricalNumericState state) {
        List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
        for (int i = 0; i < state.valueAndTypes().size(); i += 2) {
            int index = i;
            assertions.add(() -> assertEquals(LEGACY_DECIMAL, state.valueAndTypes().get(index),
                "TC-055 historical numeric value at pair " + index / 2));
            assertions.add(() -> assertEquals("number", state.valueAndTypes().get(index + 1),
                "TC-055 historical JSON token at pair " + index / 2));
        }
        assertAll(assertions);
    }

    private DraftFixture createDraftStructureFixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = createCustomer();
            Template template = new Template();
            template.templateSeriesId = UUID.randomUUID();
            template.name = "TC-056 published template " + UUID.randomUUID();
            template.status = "PUBLISHED";
            template.templateKind = "QUOTATION";
            template.componentsSnapshot = "[]";
            template.sqlViewsSnapshot = "{}";
            template.templateSqlViewsSnapshot = "{}";
            template.excelViewConfig = "[{\"col_key\":\"raw\",\"source_type\":"
                + "\"PRODUCT_ATTRIBUTE\",\"field_key\":\"raw\"}]";
            template.persist();
            templateIds.add(template.id);

            Quotation quotation = new Quotation();
            quotation.quotationNumber = "TC056-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.name = "TC-056 DRAFT read-only";
            quotation.salesRepId = firstUserId();
            quotation.status = "DRAFT";
            quotation.customerTemplateId = template.id;
            quotation.persist();
            quotationIds.add(quotation.id);
            return new DraftFixture(quotation.id);
        });
    }

    private RecalculateFixture createRecalculateFixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = createCustomer();
            String partNo = "TC057-" + UUID.randomUUID().toString().substring(0, 8);
            String derivedCode = "tc057_derived_" + UUID.randomUUID().toString().replace("-", "");

            Product product = new Product();
            product.name = "TC-057 product";
            product.partNo = partNo;
            product.category = "TEST";
            product.status = "ACTIVE";
            product.persist();
            productIds.add(product.id);

            UUID configId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO basic_data_config "
                    + "(id,sheet_name,description,status,created_at,updated_at) "
                    + "VALUES (:id,:name,:description,'ACTIVE',now(),now())")
                .setParameter("id", configId)
                .setParameter("name", "TC057_" + configId.toString().replace("-", ""))
                .setParameter("description", "derived fixture for " + partNo)
                .executeUpdate();
            basicConfigIds.add(configId);

            DerivedAttribute derived = new DerivedAttribute();
            derived.hostSheetId = configId;
            derived.variableCode = derivedCode;
            derived.variableLabel = "TC-057 derived";
            derived.dataType = "VALUE";
            derived.computationType = "EXPRESSION";
            derived.computation = "{\"formula\":\"0.123456789012 + 0\"}";
            derived.status = "ACTIVE";
            derived.sortOrder = 0;
            derived.persist();
            derivedAttributeIds.add(derived.id);

            Quotation quotation = new Quotation();
            quotation.quotationNumber = "TC057-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.name = "TC-057 recalculate";
            quotation.salesRepId = firstUserId();
            quotation.status = "DRAFT";
            quotation.originalAmount = BigDecimal.ZERO;
            quotation.totalAmount = BigDecimal.ZERO;
            quotation.finalDiscountRate = new BigDecimal("100.00");
            quotation.persist();
            quotationIds.add(quotation.id);

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotation.id;
            line.productId = product.id;
            line.productPartNoSnapshot = partNo;
            line.productNameSnapshot = product.name;
            line.productAttributeValues = "{\"legacy\":" + LEGACY_DECIMAL + "}";
            line.subtotal = new BigDecimal(LEGACY_DECIMAL);
            line.quoteCardValues = historicalJson("quoteCard");
            line.quoteExcelValues = historicalJson("quoteExcel");
            line.costingCardValues = historicalJson("costingCard");
            line.costingExcelValues = historicalJson("costingExcel");
            line.persist();

            QuotationLineComponentData componentData = new QuotationLineComponentData();
            componentData.lineItemId = line.id;
            componentData.tabName = "TC-057 raw";
            componentData.rowData = "[{\"legacy\":" + LEGACY_DECIMAL + "}]";
            componentData.snapshotRows = "[{\"legacy\":" + LEGACY_DECIMAL + "}]";
            componentData.subtotal = new BigDecimal(LEGACY_DECIMAL);
            componentData.persist();

            return new RecalculateFixture(quotation.id, line.id, derivedCode);
        });
    }

    private UUID createCustomer() {
        UUID customerId = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO customer (id,code,name) VALUES (:id,:code,:name)")
            .setParameter("id", customerId)
            .setParameter("code", "TC-RO-" + customerId.toString().substring(0, 8))
            .setParameter("name", "TC precision read-only " + customerId)
            .executeUpdate();
        customerIds.add(customerId);
        return customerId;
    }

    private UUID firstUserId() {
        return (UUID) em.createNativeQuery("SELECT id FROM \"user\" ORDER BY id LIMIT 1")
            .getSingleResult();
    }

    private DraftFingerprint draftFingerprint(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery("""
                SELECT q.xmin::text,
                       md5(to_jsonb(q)::text),
                       (SELECT count(*) FROM quotation_view_structure s
                         WHERE s.quotation_id=q.id),
                       COALESCE((SELECT md5(string_agg(
                           s.id::text || ':' || s.xmin::text || ':' || md5(to_jsonb(s)::text),
                           '|' ORDER BY s.id))
                         FROM quotation_view_structure s WHERE s.quotation_id=q.id), 'none')
                  FROM quotation q WHERE q.id=:id
                """).setParameter("id", quotationId).getSingleResult();
            return new DraftFingerprint(
                String.valueOf(row[0]), String.valueOf(row[1]),
                ((Number) row[2]).longValue(), String.valueOf(row[3]));
        });
    }

    private NonTargetFingerprint nonTargetFingerprint(UUID lineItemId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery("""
                SELECT md5(COALESCE(li.quote_card_values::text,'')),
                       md5(COALESCE(li.quote_excel_values::text,'')),
                       md5(COALESCE(li.costing_card_values::text,'')),
                       md5(COALESCE(li.costing_excel_values::text,'')),
                       cd.xmin::text,
                       md5(to_jsonb(cd)::text)
                  FROM quotation_line_item li
                  JOIN quotation_line_component_data cd ON cd.line_item_id=li.id
                 WHERE li.id=:id
                """).setParameter("id", lineItemId).getSingleResult();
            return new NonTargetFingerprint(
                String.valueOf(row[0]), String.valueOf(row[1]), String.valueOf(row[2]),
                String.valueOf(row[3]), String.valueOf(row[4]), String.valueOf(row[5]));
        });
    }

    private RecalculateResult readRecalculateResult(
            UUID quotationId, UUID lineItemId, String derivedCode) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery("""
                SELECT q.original_amount,
                       q.total_amount,
                       li.product_attribute_values->>'legacy',
                       jsonb_typeof(li.product_attribute_values->'legacy'),
                       li.product_attribute_values->>CAST(:derivedCode AS text),
                       jsonb_typeof(li.product_attribute_values->CAST(:derivedCode AS text))
                  FROM quotation q
                  JOIN quotation_line_item li ON li.quotation_id=q.id
                 WHERE q.id=:quotationId AND li.id=:lineItemId
                """)
                .setParameter("derivedCode", derivedCode)
                .setParameter("quotationId", quotationId)
                .setParameter("lineItemId", lineItemId)
                .getSingleResult();
            return new RecalculateResult(
                (BigDecimal) row[0], (BigDecimal) row[1], String.valueOf(row[2]),
                String.valueOf(row[3]), String.valueOf(row[4]), String.valueOf(row[5]));
        });
    }

    private static String historicalJson(String key) {
        return "{\"" + key + "\":" + LEGACY_DECIMAL + "}";
    }

    private record DraftFixture(UUID quotationId) {
    }

    private record RecalculateFixture(UUID quotationId, UUID lineItemId, String derivedCode) {
    }

    private record SubmittedFixture(
            UUID quotationId, UUID lineItemId, UUID componentDataId, UUID costingOrderId) {
    }

    private record FullFingerprint(List<String> tableFingerprints) {
    }

    private record HistoricalNumericState(
            List<String> valueAndTypes, FullFingerprint fullFingerprint) {
    }

    private record DraftFingerprint(
            String quotationXmin, String quotationHash, long structureCount, String structureHash) {
    }

    private record NonTargetFingerprint(
            String quoteCardHash, String quoteExcelHash, String costingCardHash,
            String costingExcelHash, String componentXmin, String componentHash) {
    }

    private record RecalculateResult(
            BigDecimal originalAmount, BigDecimal totalAmount,
            String legacyText, String legacyType, String derivedText, String derivedType) {
    }

    @SuppressWarnings("unchecked")
    private UUID quotationWithLineItem() {
        List<Object> rows = em.createNativeQuery("""
                SELECT q.id
                  FROM quotation q
                 WHERE EXISTS (
                       SELECT 1 FROM quotation_line_item li WHERE li.quotation_id = q.id)
                 ORDER BY q.created_at NULLS LAST, q.id
                 LIMIT 1
                """).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object value = rows.get(0);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Object[] persistenceFingerprint(UUID quotationId) {
        return (Object[]) em.createNativeQuery("""
                SELECT q.xmin::text,
                       md5(row_to_json(q)::text),
                       COALESCE((
                           SELECT md5(string_agg(
                               li.id::text || ':' || li.xmin::text || ':' || md5(row_to_json(li)::text),
                               '|' ORDER BY li.id))
                             FROM quotation_line_item li
                            WHERE li.quotation_id = q.id
                       ), 'none'),
                       COALESCE((
                           SELECT md5(string_agg(
                               cd.id::text || ':' || cd.xmin::text || ':' || md5(row_to_json(cd)::text),
                               '|' ORDER BY cd.id))
                             FROM quotation_line_component_data cd
                             JOIN quotation_line_item li ON li.id = cd.line_item_id
                            WHERE li.quotation_id = q.id
                        ), 'none')
                       ,(SELECT count(*) FROM quotation_view_structure vs WHERE vs.quotation_id = q.id)
                  FROM quotation q
                 WHERE q.id = :id
                """)
                .setParameter("id", quotationId)
                .getSingleResult();
    }
}
