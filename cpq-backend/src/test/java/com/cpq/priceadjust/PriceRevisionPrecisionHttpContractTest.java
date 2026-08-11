package com.cpq.priceadjust;

import com.cpq.common.PrecisionHttpContractSupport;
import com.cpq.priceadjust.entity.QuotationPriceRevision;
import com.cpq.quotation.entity.Quotation;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(PriceRevisionPrecisionHttpContractTest.RbacOffProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceRevisionPrecisionHttpContractTest {

    private static final Set<String> PRECISION_FIELDS = Set.of(
            "quoteTotalAmount", "totalAmount", "subtotal", "quantity", "unitPrice", "amount");

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
    private UUID revisionId;
    private UUID snapshotLineItemId;
    private UUID snapshotComponentId;
    private String runId;

    @BeforeEach
    void createFixture() {
        createCustomerIfNeeded();
        Response created = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"customerId\":\"" + customerId + "\",\"name\":\"T0810-P4-REV-"
                        + runId + "\",\"quoteType\":\"STANDARD\"}")
                .post("/api/cpq/quotations");
        assertEquals(200, created.statusCode(), created.asString());
        quotationId = UUID.fromString(created.jsonPath().getString("data.id"));
        quotationIds.add(quotationId);
        snapshotLineItemId = UUID.randomUUID();
        snapshotComponentId = UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            Quotation quotation = em.find(Quotation.class, quotationId);
            quotation.totalAmount = new BigDecimal("98765431.123456789012");
            em.merge(quotation);

            QuotationPriceRevision revision = new QuotationPriceRevision();
            revision.quotationId = quotationId;
            revision.revisionNo = "R0810-" + runId.substring(Math.max(0, runId.length() - 6));
            revision.sealed = true;
            revision.upgradedMaterialNos = "[\"P-0810\"]";
            revision.quoteTotalAmount = new BigDecimal("98765431.123456789011");
            revision.quoteCardValues = "{\"" + snapshotLineItemId
                    + "\":{\"totalAmount\":\"98765431.123456789011\","
                    + "\"quantity\":\"0.000000000001\"}}";
            revision.costingCardValues = "{\"" + snapshotLineItemId
                    + "\":{\"subtotal\":\"12345678.123456789012\","
                    + "\"unitPrice\":\"1.000000000001\"}}";
            revision.snapshotRows = "{\"" + snapshotLineItemId + "\":{\""
                    + snapshotComponentId + "\":[{\"amount\":\"0.000000000001\"}]}}";
            revision.firstEffectiveAt = OffsetDateTime.now();
            revision.lastUpdatedAt = OffsetDateTime.now();
            revision.createdAt = OffsetDateTime.now();
            revision.persist();
            revisionId = revision.id;
        });
    }

    @AfterEach
    void cleanupQuotationFixture() {
        for (UUID id : quotationIds) {
            Response deleted = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .delete("/api/cpq/quotations/" + id);
            assertEquals(200, deleted.statusCode(), deleted.asString());
        }
        quotationIds.clear();
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
    void p4_priceRevisionListReturnsRawDecimalStringsWithoutWrites() {
        RevisionFingerprint before = revisionFingerprint();
        Response response = RestAssured.given()
                .get("/api/cpq/quotations/" + quotationId + "/price-revisions");
        assertEquals(200, response.statusCode(), response.asString());

        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        JsonNode revision = findRevision(json.path("revisions"));
        assertFalse(revision.isMissingNode(), response.asString());
        PrecisionHttpContractSupport.assertTextualOrNull(revision, "/quoteTotalAmount");
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(json, PRECISION_FIELDS);
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, Set.of());
        assertEquals(before, revisionFingerprint(), "Price revision list must be read-only");
    }

    @Test
    void p4_priceRevisionPreviewReturnsSnapshotDecimalStringsWithoutWrites() {
        RevisionFingerprint before = revisionFingerprint();
        Response response = RestAssured.given()
                .get("/api/cpq/quotations/" + quotationId
                        + "/price-revisions/" + revisionId + "/preview");
        assertEquals(200, response.statusCode(), response.asString());

        JsonNode json = PrecisionHttpContractSupport.readJson(response);
        PrecisionHttpContractSupport.assertTextualOrNull(json, "/quoteTotalAmount");
        PrecisionHttpContractSupport.assertFieldsTextualOrNull(json, PRECISION_FIELDS);
        PrecisionHttpContractSupport.assertNoUnexpectedNumericTokens(json, Set.of());
        assertTrue(json.path("readonly").asBoolean(), response.asString());
        assertEquals(before, revisionFingerprint(), "Price revision preview must be read-only");
    }

    private void createCustomerIfNeeded() {
        if (customerId != null) {
            return;
        }
        runId = Long.toUnsignedString(System.nanoTime());
        String body = "{\"name\":\"T0810 P4 Revision Customer " + runId
                + "\",\"level\":\"STANDARD\",\"contacts\":[{\"name\":\"P4 Revision\","
                + "\"phone\":\"139" + runId.substring(Math.max(0, runId.length() - 8))
                + "\",\"isPrimary\":true}]}";
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/cpq/customers");
        assertEquals(200, response.statusCode(), response.asString());
        customerId = UUID.fromString(response.jsonPath().getString("data.id"));
    }

    private JsonNode findRevision(JsonNode revisions) {
        if (!revisions.isArray()) {
            return revisions.path("missing");
        }
        for (JsonNode revision : revisions) {
            if (revisionId.toString().equals(revision.path("revisionId").asText())) {
                return revision;
            }
        }
        return revisions.path("missing");
    }

    private RevisionFingerprint revisionFingerprint() {
        PrecisionHttpContractSupport.QuotationFingerprint quotation =
                PrecisionHttpContractSupport.fingerprintQuotation(em, quotationId);
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT count(*), coalesce(min(cast(xmin as text)), ''), "
                                + "coalesce(md5(string_agg(md5(cast(to_jsonb(r) as text)), '' "
                                + "ORDER BY cast(r.id as text))), '') "
                                + "FROM quotation_price_revision r WHERE quotation_id = :id")
                .setParameter("id", quotationId)
                .getSingleResult();
        return new RevisionFingerprint(quotation, ((Number) row[0]).longValue(),
                String.valueOf(row[1]), String.valueOf(row[2]));
    }

    private record RevisionFingerprint(
            PrecisionHttpContractSupport.QuotationFingerprint quotation,
            long revisionCount,
            String revisionXmin,
            String revisionMd5) {
    }
}
