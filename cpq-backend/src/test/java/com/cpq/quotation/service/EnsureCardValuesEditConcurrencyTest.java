package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.product.entity.Product;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineComponentData;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.entity.QuotationViewStructure;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.cpq.system.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** Regression for ensure-card-values racing quote-card-edit on the same quotation. */
@QuarkusTest
class EnsureCardValuesEditConcurrencyTest {

    private static final String TAB_NAME = "Ensure edit precision";
    private static final String EDIT_FIELD = "precisionEdit";
    private static final String EDIT_VALUE = "0.123456789123";
    private static final String INITIAL_VALUE = "0.000000000000";

    @Inject
    CardSnapshotService service;

    @Inject
    EntityManager em;

    @InjectMock
    CardSnapshotConcurrencyProbe concurrencyProbe;

    private Fixture fixture;
    private CountDownLatch releaseEnsure;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (releaseEnsure != null) releaseEnsure.countDown();
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (fixture == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            QuotationLineComponentData.delete("lineItemId", fixture.lineItemId());
            QuotationViewStructure.delete("quotationId", fixture.quotationId());
            QuotationLineItem.deleteById(fixture.lineItemId());
            Quotation.deleteById(fixture.quotationId());
            TemplateComponentSnapshot.delete("templateId", fixture.templateId());
            TemplateComponent.delete("templateId", fixture.templateId());
            Template.deleteById(fixture.templateId());
            Component.deleteById(fixture.componentId());
            Product.deleteById(fixture.productId());
            em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                .setParameter("id", fixture.customerId()).executeUpdate();
            User.deleteById(fixture.userId());
        });
    }

    @Test
    void editWaitsForEnsureThenPreservesExactValueAndCompleteSnapshot() throws Exception {
        fixture = createOwnedFixture();
        CountDownLatch ensureBuilt = new CountDownLatch(1);
        CountDownLatch editLockAttempted = new CountDownLatch(1);
        releaseEnsure = new CountDownLatch(1);

        doAnswer(invocation -> {
            ensureBuilt.countDown();
            if (!releaseEnsure.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release ensure barrier");
            }
            return null;
        }).when(concurrencyProbe).afterEnsureValuesBuilt(eq(fixture.quotationId()));
        doAnswer(invocation -> {
            editLockAttempted.countDown();
            return null;
        }).when(concurrencyProbe).beforeEditLockWait(eq(fixture.quotationId()));

        executor = Executors.newFixedThreadPool(2);
        Future<Integer> ensureFuture = executor.submit(
            () -> service.ensureCardValues(fixture.quotationId()));

        assertTrue(ensureBuilt.await(30, TimeUnit.SECONDS),
            "ensure must reach the post-build barrier while owning the advisory transaction lock");

        Future<Map<String, Object>> editFuture = executor.submit(() -> service.editCardValue(
            fixture.lineItemId(), fixture.componentId().toString(), fixture.rowKey(),
            EDIT_FIELD, EDIT_VALUE));
        assertTrue(editLockAttempted.await(5, TimeUnit.SECONDS),
            "edit must reach the blocking advisory-lock call while ensure owns the lock");
        assertThrows(TimeoutException.class, () -> editFuture.get(300, TimeUnit.MILLISECONDS),
            "edit must block instead of reading or writing ensure's in-flight snapshot");

        releaseEnsure.countDown();

        assertEquals(1, ensureFuture.get(30, TimeUnit.SECONDS), "ensure must fill the owned line");
        Map<String, Object> editResult = editFuture.get(30, TimeUnit.SECONDS);
        assertNotNull(editResult, "edit must return a complete result after ensure releases the lock");

        String responseJson = (String) editResult.get("quoteCardValues");
        assertCompleteEditedSnapshot(responseJson, "edit response");

        String storedJson = QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            QuotationLineItem stored = QuotationLineItem.findById(fixture.lineItemId());
            assertNotNull(stored, "owned line must still exist");
            return stored.quoteCardValues;
        });
        assertEquals(responseJson, storedJson,
            "committed snapshot must match the edit response; ensure must not overwrite the edit");
        assertCompleteEditedSnapshot(storedJson, "stored snapshot");
    }

    private Fixture createOwnedFixture() {
        return QuarkusTransaction.requiringNew().call(() -> {
            UUID customerId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO customer(id,code,name,level,region,industry,address) "
                    + "VALUES (:id,:code,:name,'GOLD','TEST','TEST','TEST')")
                .setParameter("id", customerId)
                .setParameter("code", "ENS-EDIT-" + customerId.toString().substring(0, 8))
                .setParameter("name", "Ensure edit concurrency customer")
                .executeUpdate();

            User user = new User();
            String userSuffix = UUID.randomUUID().toString().replace("-", "");
            user.username = "ensure_edit_" + userSuffix;
            user.fullName = "Ensure edit test user";
            user.email = "ensure_edit_" + userSuffix + "@cpq-test.internal";
            user.passwordHash = "not-used-by-direct-service-test";
            user.role = "SALES_REP";
            user.status = "ACTIVE";
            user.isFirstLogin = false;
            user.failedLoginAttempts = 0;
            user.persist();
            em.flush();

            Component component = new Component();
            component.name = "Ensure edit precision component";
            component.code = "ENS-EDIT-" + UUID.randomUUID().toString().substring(0, 8);
            component.fields = fieldsJson();
            component.formulas = "[]";
            component.rowKeyFields = "[\"rowKey\"]";
            component.persist();

            Template template = createPublishedTemplate(component);

            Product product = new Product();
            product.name = "Ensure edit precision product";
            product.partNo = "ENS-EDIT-P-" + UUID.randomUUID().toString().substring(0, 8);
            product.category = "TEST";
            product.specification = "TEST";
            product.persist();

            Quotation quotation = new Quotation();
            quotation.quotationNumber = "ENS-EDIT-" + UUID.randomUUID();
            quotation.customerId = customerId;
            quotation.salesRepId = user.id;
            quotation.name = "Ensure/edit advisory lock test";
            quotation.status = "DRAFT";
            quotation.customerTemplateId = template.id;
            quotation.finalDiscountRate = new BigDecimal("100.00");
            quotation.persist();

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotation.id;
            line.productId = product.id;
            line.templateId = template.id;
            line.productNameSnapshot = product.name;
            line.productPartNoSnapshot = product.partNo;
            line.productAttributeValues = "{}";
            line.subtotal = BigDecimal.ZERO;
            line.sortOrder = 0;
            line.compositeType = "SIMPLE";
            line.annualVolume = 1;
            line.discountBaseAmount = BigDecimal.ZERO;
            line.discountRateApplied = BigDecimal.ZERO;
            line.lineDiscountAmount = BigDecimal.ZERO;
            line.lineUnitPrice = BigDecimal.ZERO;
            line.lineFinalPrice = BigDecimal.ZERO;
            line.lineTotalAmount = BigDecimal.ZERO;
            line.quoteCardValues = null;
            line.persist();

            QuotationLineComponentData data = new QuotationLineComponentData();
            data.lineItemId = line.id;
            data.componentId = component.id;
            data.tabName = TAB_NAME;
            data.rowData = rowData();
            data.snapshotRows = snapshotRows();
            data.subtotal = BigDecimal.ZERO;
            data.sortOrder = 0;
            data.persist();

            return new Fixture(quotation.id, line.id, customerId, user.id, component.id,
                template.id, product.id, "R0");
        });
    }

    private Template createPublishedTemplate(Component component) {
        Template template = new Template();
        template.templateSeriesId = UUID.randomUUID();
        template.name = "Ensure edit quotation template";
        template.templateKind = "QUOTATION";
        template.status = "PUBLISHED";
        template.productAttributes = "[]";
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

    private static String fieldsJson() {
        return "[{\"name\":\"rowKey\",\"field_type\":\"INPUT_TEXT\",\"sort_order\":0},"
            + "{\"name\":\"amount\",\"field_type\":\"INPUT_NUMBER\",\"sort_order\":1},"
            + "{\"name\":\"" + EDIT_FIELD + "\",\"field_type\":\"INPUT_NUMBER\",\"sort_order\":2}]";
    }

    private static String componentSnapshotJson(Component component) {
        return "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + component.id
            + "\",\"componentName\":\"" + component.name + "\",\"componentCode\":\""
            + component.code + "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAB_NAME
            + "\",\"sortOrder\":0,\"fields\":" + fieldsJson()
            + ",\"formulas\":[],\"formula_assignments\":{}}]";
    }

    private static String rowData() {
        return "[{\"rowKey\":\"R0\",\"amount\":\"1.111111111111\",\"" + EDIT_FIELD
            + "\":\"" + INITIAL_VALUE + "\"},{\"rowKey\":\"R1\",\"amount\":\"2.222222222222\",\""
            + EDIT_FIELD + "\":\"" + INITIAL_VALUE + "\"}]";
    }

    private static String snapshotRows() {
        return "[{\"driverRow\":{\"rowKey\":\"R0\",\"amount\":\"1.111111111111\",\""
            + EDIT_FIELD + "\":\"" + INITIAL_VALUE + "\"},\"basicDataValues\":{}},"
            + "{\"driverRow\":{\"rowKey\":\"R1\",\"amount\":\"2.222222222222\",\""
            + EDIT_FIELD + "\":\"" + INITIAL_VALUE + "\"},\"basicDataValues\":{}}]";
    }

    private void assertCompleteEditedSnapshot(String json, String location) throws Exception {
        assertNotNull(json, location + " must contain quoteCardValues");
        JsonNode root = CardSnapshotService.MAPPER.readTree(json);
        JsonNode matchingTab = null;
        for (JsonNode tab : root.path("tabs")) {
            if (fixture.componentId().toString().equals(tab.path("componentId").asText())) {
                matchingTab = tab;
                break;
            }
        }
        assertNotNull(matchingTab, location + " must contain the edited component");
        assertEquals(2, matchingTab.path("baseRows").size(), location + " must retain both base rows");
        assertEquals(2, matchingTab.path("formulaResults").size(),
            location + " must retain both formula-result rows");
        assertFalse(matchingTab.path("editRows").isEmpty(), location + " must contain editRows");

        boolean exactEditFound = false;
        for (JsonNode editRow : matchingTab.path("editRows")) {
            if (fixture.rowKey().equals(editRow.path("rowKey").asText())
                    && EDIT_VALUE.equals(editRow.path("values").path(EDIT_FIELD).asText())) {
                exactEditFound = true;
                break;
            }
        }
        assertTrue(exactEditFound, location + " must preserve the exact decimal string edit");
    }

    private record Fixture(
            UUID quotationId,
            UUID lineItemId,
            UUID customerId,
            UUID userId,
            UUID componentId,
            UUID templateId,
            UUID productId,
            String rowKey) {
    }
}
