package com.cpq.quotation;

import com.cpq.component.entity.Component;
import com.cpq.product.entity.Product;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.snapshot.SnapshotCollectorService;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
@TestProfile(Tc059ConcurrentSubmitHttpTest.RbacOffProfile.class)
class Tc059ConcurrentSubmitHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALUE = "98765431.123456789012";
    private static final String TAB_NAME = "TC-059 Precision";
    private static final String SQL_VIEW_NAME = "tc059_precision_view";

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @InjectSpy
    SnapshotCollectorService snapshotCollectorService;

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
                em.createNativeQuery("DELETE FROM quotation_price_revision WHERE quotation_id=:qid")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id=:qid")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item_snapshot WHERE line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=:qid)")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id=:qid")
                    .setParameter("qid", fixture.quotationId()).executeUpdate();
                em.createNativeQuery("DELETE FROM component_sql_view WHERE id=:id")
                    .setParameter("id", fixture.sqlViewId()).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id IN (:quote,:cost)")
                    .setParameter("quote", fixture.quoteTemplateId())
                    .setParameter("cost", fixture.costingTemplateId()).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id IN (:quote,:cost)")
                    .setParameter("quote", fixture.quoteTemplateId())
                    .setParameter("cost", fixture.costingTemplateId()).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id IN (:quote,:cost)")
                    .setParameter("quote", fixture.quoteTemplateId())
                    .setParameter("cost", fixture.costingTemplateId()).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id=:id")
                    .setParameter("id", fixture.componentId()).executeUpdate();
                em.createNativeQuery("DELETE FROM product WHERE id=:id")
                    .setParameter("id", fixture.productId()).executeUpdate();
                em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                    .setParameter("id", fixture.customerId()).executeUpdate();
            });
        }
        fixtures.clear();
    }

    @Test
    void tc059_sameValueSaveAndRecalculateThreeTimesRemainLogicallyStable() throws Exception {
        Fixture fixture = createFixture("save");
        DraftLogicalState first = null;

        for (int round = 1; round <= 3; round++) {
            Response saved = putSameValueDraft(fixture);
            assertEquals(200, saved.statusCode(), "save round " + round + ": " + saved.asString());
            assertDraftResponse(saved, fixture, "save round " + round);

            Response cards = postEmpty(fixture.quotationId(), "ensure-card-values");
            assertEquals(200, cards.statusCode(), "ensure cards round " + round + ": " + cards.asString());
            Response excel = postEmpty(fixture.quotationId(), "ensure-excel-values");
            assertEquals(200, excel.statusCode(), "ensure excel round " + round + ": " + excel.asString());

            Response reopened = RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId());
            assertEquals(200, reopened.statusCode(), "GET round " + round + ": " + reopened.asString());
            assertDraftResponse(reopened, fixture, "GET round " + round);

            DraftLogicalState current = draftLogicalState(fixture);
            assertDraftLogicalState(current, fixture, "save round " + round);
            if (first == null) {
                first = current;
            } else {
                assertEquals(first, current,
                    "same-value save must not drift the reopened logical state at round " + round);
            }
        }

        for (int round = 1; round <= 3; round++) {
            Response recalculated = postEmpty(fixture.quotationId(), "recalculate");
            assertEquals(200, recalculated.statusCode(),
                "recalculate round " + round + ": " + recalculated.asString());
            assertDraftResponse(recalculated, fixture, "recalculate round " + round);

            Response cards = postEmpty(fixture.quotationId(), "ensure-card-values");
            assertEquals(200, cards.statusCode(),
                "ensure cards after recalculate round " + round + ": " + cards.asString());
            Response excel = postEmpty(fixture.quotationId(), "ensure-excel-values");
            assertEquals(200, excel.statusCode(),
                "ensure excel after recalculate round " + round + ": " + excel.asString());

            Response reopened = RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId());
            assertEquals(200, reopened.statusCode(),
                "GET after recalculate round " + round + ": " + reopened.asString());
            assertDraftResponse(reopened, fixture, "GET after recalculate round " + round);

            DraftLogicalState current = draftLogicalState(fixture);
            assertDraftLogicalState(current, fixture, "recalculate round " + round);
            assertEquals(first, current,
                "same-value recalculate must match the save baseline at round " + round);
        }
    }

    @Test
    void tc059_concurrentSubmitHasSingleInternalWinnerAndMatchesSerialControl() throws Exception {
        Fixture control = createFixture("control");
        Response controlSubmit = postSubmit(control.quotationId());
        assertTrue(is2xx(controlSubmit.statusCode()), controlSubmit.asString());
        LogicalFinalState controlFinal = assertFinalState(control);

        Fixture concurrent = createFixture("parallel");
        PreSubmitState baseline = preSubmitState(concurrent.quotationId());
        assertNull(baseline.snapshotCustomerName(), "fixture must start before customer snapshot refresh");
        assertEquals(0L, baseline.productSnapshotCount());
        assertEquals(0L, baseline.sqlSnapshotCount());

        AtomicInteger downstreamCalls = new AtomicInteger();
        CountDownLatch secondDownstreamCall = new CountDownLatch(1);
        doAnswer(invocation -> {
            UUID quotationId = invocation.getArgument(0);
            if (!concurrent.quotationId().equals(quotationId)) {
                return invocation.callRealMethod();
            }
            int call = downstreamCalls.incrementAndGet();
            if (call == 1) {
                secondDownstreamCall.await(1200, TimeUnit.MILLISECONDS);
            } else {
                secondDownstreamCall.countDown();
            }
            return invocation.callRealMethod();
        }).when(snapshotCollectorService).collect(any(UUID.class), any(), any(UUID.class));

        CyclicBarrier releasePosts = new CyclicBarrier(2);
        AtomicInteger draftReads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Attempt first;
        Attempt second;
        try {
            Future<Attempt> firstFuture = executor.submit(
                () -> submitAfterConfirmedDraft(concurrent, baseline, releasePosts, draftReads));
            Future<Attempt> secondFuture = executor.submit(
                () -> submitAfterConfirmedDraft(concurrent, baseline, releasePosts, draftReads));
            first = firstFuture.get(30, TimeUnit.SECONDS);
            second = secondFuture.get(30, TimeUnit.SECONDS);
        } finally {
            secondDownstreamCall.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                "TC-059 executor must terminate; a stuck worker indicates a deadlock");
        }

        assertEquals(2, draftReads.get(), "both independent HTTP clients must confirm DRAFT before POST");
        assertNotEquals(first.threadName(), second.threadName(), "POSTs must run on two executor threads");
        List<Integer> statuses = List.of(first.httpStatus(), second.httpStatus());
        assertEquals(1L, statuses.stream().filter(Tc059ConcurrentSubmitHttpTest::is2xx).count(),
            "exactly one submit must succeed: " + first + " / " + second);
        assertEquals(1L, statuses.stream().filter(status -> status == 409).count(),
            "the losing submit must be a deterministic 409: " + first + " / " + second);
        assertTrue(statuses.stream().noneMatch(status -> status >= 500),
            "concurrent submit must not expose 500: " + first + " / " + second);

        Attempt loser = first.httpStatus() == 409 ? first : second;
        assertEquals(baseline, loser.preSubmitState(),
            "losing request must start from the captured customer/line/SQL snapshot baseline");

        LogicalFinalState concurrentFinal = assertFinalState(concurrent);
        assertEquals(controlFinal, concurrentFinal,
            "parallel final state must match the isomorphic single-thread control; sequence values are excluded");
        assertEquals(1, downstreamCalls.get(),
            "only the winning transaction may pass the internal DRAFT status gate and collect snapshots");
    }

    private Attempt submitAfterConfirmedDraft(
            Fixture fixture,
            PreSubmitState baseline,
            CyclicBarrier releasePosts,
            AtomicInteger draftReads) throws Exception {
        Response read = RestAssured.given().get("/api/cpq/quotations/" + fixture.quotationId());
        assertEquals(200, read.statusCode(), read.asString());
        String status = MAPPER.readTree(read.asString()).at("/data/status").asText();
        assertEquals("DRAFT", status, read.asString());
        draftReads.incrementAndGet();
        assertEquals(baseline, preSubmitState(fixture.quotationId()),
            "GET must not mutate customer/product/SQL submission snapshots");
        releasePosts.await(10, TimeUnit.SECONDS);
        Response submitted = postSubmit(fixture.quotationId());
        return new Attempt(Thread.currentThread().getName(), status, submitted.statusCode(),
            submitted.asString(), baseline);
    }

    private Fixture createFixture(String label) {
        Fixture fixture = QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO customer(id,code,name,level,region,industry,address) "
                    + "VALUES (:id,:code,:name,'GOLD','TC059-REGION','TC059-INDUSTRY','TC059-ADDRESS')")
                .setParameter("id", customerId)
                .setParameter("code", "TC059-" + customerId.toString().substring(0, 8))
                .setParameter("name", "TC-059 customer").executeUpdate();

            Component component = new Component();
            component.name = "TC-059 precision component";
            component.code = "TC059-C-" + UUID.randomUUID().toString().substring(0, 8);
            component.fields = fieldsJson();
            component.formulas = "[]";
            component.rowKeyFields = "[\"rowKey\"]";
            component.persist();

            UUID sqlViewId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO component_sql_view(id,component_id,sql_view_name,sql_template,"
                    + "declared_columns,required_variables,scope,status,description) VALUES "
                    + "(:id,:component,:name,'SELECT :customerId::text AS customer_id',"
                    + "'[{\"name\":\"customer_id\",\"dataType\":\"text\",\"nullable\":false}]'::jsonb,"
                    + "ARRAY['customerId']::text[],'COMPONENT','ACTIVE','TC059 deterministic closure')")
                .setParameter("id", sqlViewId)
                .setParameter("component", component.id)
                .setParameter("name", SQL_VIEW_NAME).executeUpdate();

            Template quoteTemplate = createTemplate("QUOTATION", component);
            Template costingTemplate = createTemplate("COSTING", component);

            Product product = new Product();
            product.name = "TC-059 product";
            product.partNo = "TC059-P-" + UUID.randomUUID().toString().substring(0, 8);
            product.category = "TC059-CATEGORY";
            product.specification = "TC059-SPECIFICATION";
            product.persist();

            Quotation quotation = new Quotation();
            quotation.quotationNumber = "TC059-" + label.charAt(0) + "-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.name = "TC-059 deterministic " + label;
            quotation.salesRepId = firstUserId();
            quotation.status = "DRAFT";
            quotation.customerTemplateId = quoteTemplate.id;
            quotation.costingCardTemplateId = costingTemplate.id;
            quotation.finalDiscountRate = new BigDecimal("100.00");
            quotation.persist();

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotation.id;
            line.productId = product.id;
            line.templateId = quoteTemplate.id;
            line.productNameSnapshot = "TC-059 product";
            line.productPartNoSnapshot = "TC059-LINE";
            line.productAttributeValues = productAttributes();
            line.subtotal = new BigDecimal(VALUE);
            line.sortOrder = 0;
            line.compositeType = "SIMPLE";
            line.annualVolume = 1;
            line.discountBaseAmount = new BigDecimal(VALUE);
            line.discountRateApplied = new BigDecimal("0.00");
            line.lineDiscountAmount = new BigDecimal("0.000000000000");
            line.lineUnitPrice = new BigDecimal(VALUE);
            line.lineFinalPrice = new BigDecimal(VALUE);
            line.lineTotalAmount = new BigDecimal(VALUE);
            line.persist();

            QuotationLineComponentData data = new QuotationLineComponentData();
            data.lineItemId = line.id;
            data.componentId = component.id;
            data.tabName = TAB_NAME;
            data.rowData = rowData();
            data.snapshotRows = snapshotRows();
            data.subtotal = new BigDecimal(VALUE);
            data.sortOrder = 0;
            data.persist();

            return new Fixture(quotation.id, customerId, component.id, sqlViewId,
                quoteTemplate.id, costingTemplate.id, product.id, product.partNo, line.id);
        });
        fixtures.add(fixture);
        return fixture;
    }

    private Template createTemplate(String kind, Component component) {
        Template template = new Template();
        template.templateSeriesId = UUID.randomUUID();
        template.name = "TC-059 " + kind + " template";
        template.templateKind = kind;
        template.status = "PUBLISHED";
        template.productAttributes = "[{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\"}]";
        template.componentsSnapshot = componentSnapshotJson(component);
        template.sqlViewsSnapshot = "{}";
        template.templateSqlViewsSnapshot = "{}";
        template.excelViewConfig = "[]";
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
        return template;
    }

    private PreSubmitState preSubmitState(UUID quotationId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery(
                    "SELECT snapshot_customer_name,"
                        + "(SELECT count(*) FROM quotation_line_item_snapshot s WHERE s.line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=q.id)),"
                        + "(SELECT count(*) FROM quotation_component_sql_snapshot x WHERE x.quotation_id=q.id),"
                        + "(SELECT md5(string_agg(id::text || ':' || subtotal::text || ':' "
                        + "|| product_attribute_values::text,'|' ORDER BY id)) "
                        + "FROM quotation_line_item WHERE quotation_id=q.id) "
                        + "FROM quotation q WHERE id=:id")
                .setParameter("id", quotationId).getSingleResult();
            return new PreSubmitState(string(row[0]), number(row[1]), number(row[2]), string(row[3]));
        });
    }

    @SuppressWarnings("unchecked")
    private DraftLogicalState draftLogicalState(Fixture fixture) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] header = (Object[]) em.createNativeQuery(
                    "SELECT status,original_amount::text,scale(original_amount),"
                        + "total_amount::text,scale(total_amount),"
                        + "(SELECT count(*) FROM quotation_line_item WHERE quotation_id=q.id) "
                        + "FROM quotation q WHERE id=:id")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] line = (Object[]) em.createNativeQuery(
                    "SELECT id,subtotal::text,scale(subtotal),discount_base_amount::text,"
                        + "line_discount_amount::text,line_unit_price::text,line_final_price::text,"
                        + "line_total_amount::text,product_attribute_values::text,"
                        + "jsonb_typeof(product_attribute_values->'amount'),"
                        + "quote_card_values IS NOT NULL,quote_excel_values IS NOT NULL,"
                        + "costing_card_values IS NOT NULL,costing_excel_values IS NOT NULL,"
                        + "(coalesce(quote_card_values::text,'') || coalesce(quote_excel_values::text,'') || "
                        + "coalesce(costing_card_values::text,'') || coalesce(costing_excel_values::text,'')) "
                        + "LIKE '%__cardValueFailed%' "
                        + "FROM quotation_line_item WHERE quotation_id=:id")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] component = (Object[]) em.createNativeQuery(
                    "SELECT count(*),min(subtotal::text),min(scale(subtotal)),min(row_data::text),"
                        + "min(snapshot_rows::text),min(jsonb_typeof(row_data->0->'amount')),"
                        + "min(jsonb_typeof(snapshot_rows->0->'driverRow'->'amount')),"
                        + "sum(jsonb_array_length(row_data)) "
                        + "FROM quotation_line_component_data WHERE line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=:id)")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] integrity = (Object[]) em.createNativeQuery(
                    "SELECT "
                        + "(SELECT count(*) FROM quotation_line_process p JOIN quotation_line_item li "
                        + "ON li.id=p.line_item_id WHERE li.quotation_id=:id),"
                        + "(SELECT count(*) FROM quotation_line_item_snapshot s JOIN quotation_line_item li "
                        + "ON li.id=s.line_item_id WHERE li.quotation_id=:id),"
                        + "(SELECT count(*) FROM (SELECT s.line_item_id FROM quotation_line_item_snapshot s "
                        + "JOIN quotation_line_item li ON li.id=s.line_item_id WHERE li.quotation_id=:id "
                        + "GROUP BY s.line_item_id HAVING count(*)>1) d),"
                        + "(SELECT count(*) FROM quotation_line_item_snapshot s LEFT JOIN quotation_line_item li "
                        + "ON li.id=s.line_item_id WHERE li.id IS NULL),"
                        + "(SELECT count(*) FROM (SELECT line_item_id,component_id,tab_name "
                        + "FROM quotation_line_component_data WHERE line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=:id) "
                        + "GROUP BY line_item_id,component_id,tab_name HAVING count(*)>1) d),"
                        + "(SELECT count(*) FROM quotation_line_component_data d LEFT JOIN quotation_line_item li "
                        + "ON li.id=d.line_item_id WHERE li.id IS NULL),"
                        + "(SELECT count(*) FROM quotation_view_structure WHERE quotation_id=:id),"
                        + "(SELECT count(*) FROM (SELECT view_kind FROM quotation_view_structure "
                        + "WHERE quotation_id=:id GROUP BY view_kind HAVING count(*)>1) d),"
                        + "(SELECT count(*) FROM quotation_price_revision WHERE quotation_id=:id),"
                        + "(SELECT count(*) FROM (SELECT based_version_id FROM quotation_price_revision "
                        + "WHERE quotation_id=:id GROUP BY based_version_id HAVING count(*)>1) d)")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            return new DraftLogicalState(
                string(header[0]), string(header[1]), ((Number) header[2]).intValue(),
                string(header[3]), ((Number) header[4]).intValue(), number(header[5]),
                uuid(line[0]), string(line[1]), ((Number) line[2]).intValue(),
                string(line[3]), string(line[4]), string(line[5]), string(line[6]), string(line[7]),
                string(line[8]), string(line[9]), (Boolean) line[10], (Boolean) line[11],
                (Boolean) line[12], (Boolean) line[13], (Boolean) line[14],
                number(component[0]), string(component[1]), ((Number) component[2]).intValue(),
                string(component[3]), string(component[4]), string(component[5]), string(component[6]),
                number(component[7]), number(integrity[0]), number(integrity[1]), number(integrity[2]),
                number(integrity[3]), number(integrity[4]), number(integrity[5]), number(integrity[6]),
                number(integrity[7]), number(integrity[8]), number(integrity[9]));
        });
    }

    private void assertDraftLogicalState(DraftLogicalState state, Fixture fixture, String label) {
        assertEquals("DRAFT", state.status(), label);
        assertDecimal(VALUE, state.originalAmount(), state.originalScale(), label + " header.original");
        assertDecimal(VALUE, state.totalAmount(), state.totalScale(), label + " header.total");
        assertEquals(1L, state.lineCount(), label + " line count");
        assertEquals(fixture.lineId(), state.lineId(), label + " stable line ID");
        assertDecimal(VALUE, state.lineSubtotal(), state.lineSubtotalScale(), label + " line.subtotal");
        assertEquals(VALUE, state.discountBaseAmount(), label);
        assertEquals("0.000000000000", state.lineDiscountAmount(), label);
        assertEquals(VALUE, state.lineUnitPrice(), label);
        assertEquals(VALUE, state.lineFinalPrice(), label);
        assertEquals(VALUE, state.lineTotalAmount(), label);
        assertEquals("string", state.productAmountType(), label + " product amount JSON token");
        assertJsonDecimal(state.productAttributes(), "amount", VALUE, label + " product attributes");
        assertTrue(state.quoteCardMaterialized(), label + " quote card cache");
        assertTrue(state.quoteExcelMaterialized(), label + " quote Excel cache");
        assertTrue(state.costingCardMaterialized(), label + " costing card cache");
        assertTrue(state.costingExcelMaterialized(), label + " costing Excel cache");
        assertFalse(state.hasCardFailureSentinel(), label + " cache failure sentinel");
        assertEquals(1L, state.componentCount(), label + " component count");
        assertDecimal(VALUE, state.componentSubtotal(), state.componentSubtotalScale(),
            label + " component subtotal");
        assertEquals("string", state.rowAmountType(), label + " row_data amount JSON token");
        assertEquals("string", state.snapshotAmountType(), label + " snapshot_rows amount JSON token");
        assertJsonDecimal(state.rowData(), "amount", VALUE, label + " row_data");
        assertJsonDecimal(state.snapshotRows(), "amount", VALUE, label + " snapshot_rows");
        assertEquals(1L, state.driverRowCount(), label + " driver row count");
        assertEquals(0L, state.processCount(), label + " process count");
        assertEquals(0L, state.productSnapshotCount(), label + " pre-submit product snapshot count");
        assertEquals(0L, state.productSnapshotDuplicateGroups(), label + " product snapshot duplicates");
        assertEquals(0L, state.orphanProductSnapshots(), label + " orphan product snapshots");
        assertEquals(0L, state.componentDuplicateGroups(), label + " component duplicates");
        assertEquals(0L, state.orphanComponents(), label + " orphan components");
        assertTrue(state.viewStructureCount() <= 4L, label + " view structure count");
        assertEquals(0L, state.viewKindDuplicateGroups(), label + " duplicate view kinds");
        assertEquals(1L, state.revisionCount(), label + " initial revision placeholder count");
        assertEquals(0L, state.revisionDuplicateGroups(), label + " duplicate revisions");
    }

    private Response putSameValueDraft(Fixture fixture) throws Exception {
        String body = "{\"name\":\"TC-059 deterministic save\","
            + "\"customerTemplateId\":\"" + fixture.quoteTemplateId() + "\","
            + "\"costingCardTemplateId\":\"" + fixture.costingTemplateId() + "\","
            + "\"finalDiscountRate\":\"100.00\",\"lineItems\":[{"
            + "\"id\":\"" + fixture.lineId() + "\","
            + "\"productId\":\"" + fixture.productId() + "\","
            + "\"templateId\":\"" + fixture.quoteTemplateId() + "\","
            + "\"productPartNo\":\"" + fixture.productPartNo() + "\","
            + "\"productName\":\"TC-059 product\","
            + "\"productAttributeValues\":" + MAPPER.writeValueAsString(productAttributes()) + ","
            + "\"subtotal\":\"" + VALUE + "\",\"sortOrder\":0,"
            + "\"compositeType\":\"SIMPLE\",\"annualVolume\":1,"
            + "\"discountBaseAmount\":\"" + VALUE + "\","
            + "\"discountRateApplied\":\"0.00\","
            + "\"lineDiscountAmount\":\"0.000000000000\","
            + "\"lineUnitPrice\":\"" + VALUE + "\","
            + "\"lineFinalPrice\":\"" + VALUE + "\","
            + "\"lineTotalAmount\":\"" + VALUE + "\","
            + "\"componentData\":[{\"componentId\":\"" + fixture.componentId() + "\","
            + "\"tabName\":\"" + TAB_NAME + "\",\"rowData\":"
            + MAPPER.writeValueAsString(rowData()) + ",\"subtotal\":\"" + VALUE
            + "\",\"sortOrder\":0}]}]}";
        return RestAssured.given().contentType(ContentType.JSON).body(body)
            .put("/api/cpq/quotations/" + fixture.quotationId() + "/draft");
    }

    private static Response postEmpty(UUID quotationId, String action) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/quotations/" + quotationId + "/" + action);
    }

    private static void assertDraftResponse(Response response, Fixture fixture, String label) throws Exception {
        JsonNode data = MAPPER.readTree(response.asString()).path("data");
        assertEquals("DRAFT", data.path("status").asText(), label + ": " + response.asString());
        assertEquals(VALUE, data.path("originalAmount").asText(), label + " originalAmount");
        assertEquals(VALUE, data.path("totalAmount").asText(), label + " totalAmount");
        assertTrue(data.path("originalAmount").isTextual(), label + " originalAmount token");
        assertTrue(data.path("totalAmount").isTextual(), label + " totalAmount token");
        assertEquals(1, data.path("lineItems").size(), label + " line count");
        JsonNode line = data.path("lineItems").get(0);
        assertEquals(fixture.lineId().toString(), line.path("id").asText(), label + " line ID");
        Map<String, String> expected = Map.of(
            "subtotal", VALUE,
            "discountBaseAmount", VALUE,
            "lineDiscountAmount", "0.000000000000",
            "lineUnitPrice", VALUE,
            "lineFinalPrice", VALUE,
            "lineTotalAmount", VALUE);
        expected.forEach((field, value) -> {
            assertTrue(line.path(field).isTextual(), label + " " + field + " token");
            assertEquals(0, new BigDecimal(value).compareTo(new BigDecimal(line.path(field).asText())),
                label + " " + field + " canonical decimal value");
        });
    }

    @SuppressWarnings("unchecked")
    private LogicalFinalState assertFinalState(Fixture fixture) throws Exception {
        FinalRows rows = QuarkusTransaction.requiringNew().call(() -> {
            Object[] header = (Object[]) em.createNativeQuery(
                    "SELECT status,snapshot_customer_name,snapshot_customer_level,snapshot_customer_region,"
                        + "snapshot_customer_industry,snapshot_customer_address,original_amount::text,"
                        + "scale(original_amount),total_amount::text,scale(total_amount),submission_snapshot::text "
                        + "FROM quotation WHERE id=:id")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] line = (Object[]) em.createNativeQuery(
                    "SELECT id,subtotal::text,scale(subtotal),discount_base_amount::text,"
                        + "line_discount_amount::text,line_unit_price::text,line_final_price::text,"
                        + "line_total_amount::text,product_attribute_values::text,quote_card_values::text,"
                        + "quote_excel_values::text,costing_card_values::text,costing_excel_values::text "
                        + "FROM quotation_line_item WHERE quotation_id=:id")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] component = (Object[]) em.createNativeQuery(
                    "SELECT count(*),min(subtotal::text),min(scale(subtotal)),"
                        + "min(row_data::text),min(snapshot_rows::text),"
                        + "sum(jsonb_array_length(row_data)) FROM quotation_line_component_data "
                        + "WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id)")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] costing = (Object[]) em.createNativeQuery(
                    "SELECT count(*),min(total_amount::text),min(scale(total_amount)),"
                        + "min(costing_total_amount::text),min(scale(costing_total_amount)),min(frozen_dto::text) "
                        + "FROM costing_order WHERE quotation_id=:id AND status IN ('PENDING','APPROVED')")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            Object[] integrity = (Object[]) em.createNativeQuery(
                    "SELECT "
                        + "(SELECT count(*) FROM quotation_line_item WHERE quotation_id=:id),"
                        + "(SELECT count(*) FROM quotation_line_item_snapshot s WHERE s.line_item_id IN "
                        + "(SELECT id FROM quotation_line_item WHERE quotation_id=:id)),"
                        + "(SELECT count(*) FROM (SELECT line_item_id FROM quotation_line_item_snapshot s "
                        + "WHERE s.line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id=:id) "
                        + "GROUP BY line_item_id HAVING count(*)>1) d),"
                        + "(SELECT count(*) FROM quotation_line_item_snapshot s LEFT JOIN quotation_line_item li "
                        + "ON li.id=s.line_item_id WHERE li.id IS NULL),"
                        + "(SELECT count(*) FROM (SELECT view_kind FROM quotation_view_structure "
                        + "WHERE quotation_id=:id GROUP BY view_kind HAVING count(*)>1) d),"
                        + "(SELECT coalesce(max(c),0) FROM (SELECT count(*) c FROM quotation_view_structure "
                        + "WHERE quotation_id=:id GROUP BY view_kind) v),"
                        + "(SELECT count(*) FROM quotation_component_sql_snapshot WHERE quotation_id=:id),"
                        + "(SELECT count(*) FROM (SELECT sql_view_key FROM quotation_component_sql_snapshot "
                        + "WHERE quotation_id=:id GROUP BY sql_view_key HAVING count(*)>1) d),"
                        + "(SELECT count(*) FROM (SELECT based_version_id FROM quotation_price_revision "
                        + "WHERE quotation_id=:id GROUP BY based_version_id HAVING count(*)>1) d)")
                .setParameter("id", fixture.quotationId()).getSingleResult();
            List<Object[]> productSnapshots = em.createNativeQuery(
                    "SELECT s.line_item_id,s.product_part_no,s.product_category,s.product_specification "
                        + "FROM quotation_line_item_snapshot s JOIN quotation_line_item li ON li.id=s.line_item_id "
                        + "WHERE li.quotation_id=:id")
                .setParameter("id", fixture.quotationId()).getResultList();
            List<String> sqlKeys = ((List<Object>) em.createNativeQuery(
                    "SELECT sql_view_key FROM quotation_component_sql_snapshot "
                        + "WHERE quotation_id=:id ORDER BY sql_view_key")
                .setParameter("id", fixture.quotationId()).getResultList()).stream()
                .map(Tc059ConcurrentSubmitHttpTest::string).toList();
            return new FinalRows(header, line, component, costing, integrity, productSnapshots, sqlKeys);
        });

        assertEquals("SUBMITTED", string(rows.header()[0]));
        assertEquals("TC-059 customer", string(rows.header()[1]));
        assertEquals("GOLD", string(rows.header()[2]));
        assertEquals("TC059-REGION", string(rows.header()[3]));
        assertEquals("TC059-INDUSTRY", string(rows.header()[4]));
        assertEquals("TC059-ADDRESS", string(rows.header()[5]));
        assertDecimal(VALUE, rows.header()[6], rows.header()[7], "quotation.original_amount");
        assertDecimal(VALUE, rows.header()[8], rows.header()[9], "quotation.total_amount");
        assertNotNull(rows.header()[10], "submission_snapshot must be frozen");

        assertEquals(fixture.lineId(), uuid(rows.line()[0]), "quotation line ID must remain stable");
        assertDecimal(VALUE, rows.line()[1], rows.line()[2], "line.subtotal");
        for (int index : List.of(3, 5, 6, 7)) {
            assertEquals(VALUE, string(rows.line()[index]), "line decimal index=" + index);
        }
        assertEquals("0.000000000000", string(rows.line()[4]));
        assertJsonDecimal(rows.line()[8], "amount", VALUE, "product_attribute_values");
        for (int index : List.of(9, 10, 11, 12)) {
            String json = string(rows.line()[index]);
            assertNotNull(json, "card value index=" + index + " must be materialized");
            assertFalse(json.contains("__cardValueFailed"), "failure sentinel must not survive: " + json);
        }

        assertEquals(1L, number(rows.component()[0]), "component count");
        assertEquals(VALUE, string(rows.component()[1]));
        assertEquals(12, ((Number) rows.component()[2]).intValue());
        assertJsonDecimal(rows.component()[3], "amount", VALUE, "component.row_data");
        assertJsonDecimal(rows.component()[4], "amount", VALUE, "component.snapshot_rows");
        assertEquals(1L, number(rows.component()[5]), "process/driver row count");

        assertEquals(1L, number(rows.costing()[0]), "exactly one active costing order");
        assertDecimal(VALUE, rows.costing()[1], rows.costing()[2], "costing.total_amount");
        assertDecimal("0.000000000000", rows.costing()[3], rows.costing()[4],
            "costing.costing_total_amount without a SUBTOTAL component");
        JsonNode frozen = MAPPER.readTree(string(rows.costing()[5]));
        assertEquals(fixture.quotationId().toString(), frozen.path("id").asText());
        assertEquals(VALUE, frozen.path("totalAmount").asText());
        assertEquals(1, frozen.path("lineItems").size());
        assertEquals(fixture.lineId().toString(), frozen.path("lineItems").get(0).path("id").asText());
        assertEquals(VALUE, frozen.path("lineItems").get(0).path("subtotal").asText());
        assertEquals(VALUE, frozen.path("lineItems").get(0).path("lineTotalAmount").asText());

        assertEquals(1L, number(rows.integrity()[0]), "line count");
        assertEquals(1L, number(rows.integrity()[1]), "product-backed line snapshot must be non-empty");
        assertEquals(0L, number(rows.integrity()[2]), "line snapshot duplicate groups");
        assertEquals(0L, number(rows.integrity()[3]), "orphan line snapshots");
        assertEquals(0L, number(rows.integrity()[4]), "view-kind duplicate groups");
        assertTrue(number(rows.integrity()[5]) <= 1L, "each view kind must occur at most once");
        assertEquals(1L, number(rows.integrity()[6]), "SQL snapshot closure size");
        assertEquals(0L, number(rows.integrity()[7]), "SQL snapshot key duplicates");
        assertEquals(0L, number(rows.integrity()[8]), "revision duplicate groups");
        assertEquals(1, rows.productSnapshots().size());
        Object[] productSnapshot = rows.productSnapshots().get(0);
        assertEquals(fixture.lineId(), uuid(productSnapshot[0]));
        assertEquals(fixture.productPartNo(), string(productSnapshot[1]));
        assertEquals("TC059-CATEGORY", string(productSnapshot[2]));
        assertEquals("TC059-SPECIFICATION", string(productSnapshot[3]));
        assertEquals(List.of(fixture.componentId() + "::" + SQL_VIEW_NAME), rows.sqlKeys(),
            "SQL snapshot closure must contain the fixture component view exactly once");

        return new LogicalFinalState(
            string(rows.header()[0]), string(rows.header()[1]), string(rows.header()[2]),
            string(rows.header()[3]), string(rows.header()[4]), string(rows.header()[5]),
            string(rows.header()[6]), ((Number) rows.header()[7]).intValue(),
            string(rows.header()[8]), ((Number) rows.header()[9]).intValue(),
            string(rows.line()[1]), string(rows.line()[3]), string(rows.line()[4]),
            string(rows.line()[5]), string(rows.line()[6]), string(rows.line()[7]),
            number(rows.component()[0]), number(rows.component()[5]), number(rows.costing()[0]),
            string(rows.costing()[1]), string(rows.costing()[3]),
            number(rows.integrity()[1]), number(rows.integrity()[2]), number(rows.integrity()[3]),
            number(rows.integrity()[4]), number(rows.integrity()[5]), number(rows.integrity()[6]),
            number(rows.integrity()[7]), number(rows.integrity()[8]),
            rows.sqlKeys().stream().map(key -> key.substring(key.indexOf("::"))).toList());
    }

    private UUID firstUserId() {
        return uuid(em.createNativeQuery("SELECT id FROM \"user\" ORDER BY id LIMIT 1").getSingleResult());
    }

    private static Response postSubmit(UUID quotationId) {
        return RestAssured.given().contentType(ContentType.JSON).body("{}")
            .post("/api/cpq/quotations/" + quotationId + "/submit");
    }

    private static boolean is2xx(int status) {
        return status >= 200 && status < 300;
    }

    private static String fieldsJson() {
        return "[{\"name\":\"rowKey\",\"field_type\":\"INPUT_TEXT\",\"sort_order\":0},"
            + "{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\",\"sort_order\":1,"
            + "\"is_amount\":true,\"is_subtotal\":true}]";
    }

    private static String componentSnapshotJson(Component component) {
        return "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + component.id
            + "\",\"componentName\":\"" + component.name + "\",\"componentCode\":\""
            + component.code + "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAB_NAME
            + "\",\"sortOrder\":0,\"fields\":" + fieldsJson()
            + ",\"formulas\":[],\"formula_assignments\":{}}]";
    }

    private static String productAttributes() {
        return "{\"amount\":\"" + VALUE + "\",\"rowKey\":\"R0\"}";
    }

    private static String rowData() {
        return "[{\"rowKey\":\"R0\",\"amount\":\"" + VALUE + "\"}]";
    }

    private static String snapshotRows() {
        return "[{\"driverRow\":{\"rowKey\":\"R0\",\"amount\":\"" + VALUE
            + "\"},\"basicDataValues\":{}}]";
    }

    private static void assertJsonDecimal(Object raw, String field, String expected, String label) {
        try {
            JsonNode root = MAPPER.readTree(string(raw));
            List<JsonNode> matches = root.findValues(field);
            assertTrue(matches.stream().anyMatch(node -> node.isTextual() && expected.equals(node.asText())),
                label + " must contain exact decimal string " + field + "=" + expected + ": " + raw);
            assertTrue(matches.stream().noneMatch(JsonNode::isNumber),
                label + " must not contain a numeric token for " + field + ": " + raw);
        } catch (Exception e) {
            throw new AssertionError(label + " must be valid JSON: " + raw, e);
        }
    }

    private static void assertDecimal(String expected, Object raw, Object scale, String label) {
        assertEquals(expected, string(raw), label);
        assertEquals(12, ((Number) scale).intValue(), label + " scale");
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record Fixture(
            UUID quotationId,
            UUID customerId,
            UUID componentId,
            UUID sqlViewId,
            UUID quoteTemplateId,
            UUID costingTemplateId,
            UUID productId,
            String productPartNo,
            UUID lineId) {
    }

    private record PreSubmitState(
            String snapshotCustomerName,
            long productSnapshotCount,
            long sqlSnapshotCount,
            String lineFingerprint) {
    }

    private record DraftLogicalState(
            String status,
            String originalAmount,
            int originalScale,
            String totalAmount,
            int totalScale,
            long lineCount,
            UUID lineId,
            String lineSubtotal,
            int lineSubtotalScale,
            String discountBaseAmount,
            String lineDiscountAmount,
            String lineUnitPrice,
            String lineFinalPrice,
            String lineTotalAmount,
            String productAttributes,
            String productAmountType,
            boolean quoteCardMaterialized,
            boolean quoteExcelMaterialized,
            boolean costingCardMaterialized,
            boolean costingExcelMaterialized,
            boolean hasCardFailureSentinel,
            long componentCount,
            String componentSubtotal,
            int componentSubtotalScale,
            String rowData,
            String snapshotRows,
            String rowAmountType,
            String snapshotAmountType,
            long driverRowCount,
            long processCount,
            long productSnapshotCount,
            long productSnapshotDuplicateGroups,
            long orphanProductSnapshots,
            long componentDuplicateGroups,
            long orphanComponents,
            long viewStructureCount,
            long viewKindDuplicateGroups,
            long revisionCount,
            long revisionDuplicateGroups) {
    }

    private record Attempt(
            String threadName,
            String observedStatus,
            int httpStatus,
            String body,
            PreSubmitState preSubmitState) {
    }

    private record FinalRows(
            Object[] header,
            Object[] line,
            Object[] component,
            Object[] costing,
            Object[] integrity,
            List<Object[]> productSnapshots,
            List<String> sqlKeys) {
    }

    private record LogicalFinalState(
            String status,
            String customerName,
            String customerLevel,
            String customerRegion,
            String customerIndustry,
            String customerAddress,
            String originalAmount,
            int originalScale,
            String totalAmount,
            int totalScale,
            String lineSubtotal,
            String discountBaseAmount,
            String lineDiscountAmount,
            String lineUnitPrice,
            String lineFinalPrice,
            String lineTotalAmount,
            long componentCount,
            long processRowCount,
            long activeCostingCount,
            String frozenTotal,
            String costingTotal,
            long productSnapshotCount,
            long productSnapshotDuplicateGroups,
            long orphanProductSnapshots,
            long viewKindDuplicateGroups,
            long maxRowsPerViewKind,
            long sqlSnapshotCount,
            long sqlSnapshotDuplicateGroups,
            long revisionDuplicateGroups,
            List<String> normalizedSqlKeys) {
    }
}
