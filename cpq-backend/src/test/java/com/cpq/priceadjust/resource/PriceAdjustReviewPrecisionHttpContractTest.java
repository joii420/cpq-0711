package com.cpq.priceadjust.resource;

import com.cpq.priceadjust.dto.ApproveRejectRequest;
import com.cpq.priceadjust.dto.ImpactResultDTO;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.service.PriceAdjustJobExecutionService;
import com.cpq.priceadjust.service.PriceAdjustReviewService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(PriceAdjustReviewPrecisionHttpContractTest.RbacOffProfile.class)
class PriceAdjustReviewPrecisionHttpContractTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    @InjectMock
    SessionHelper sessionHelper;

    @InjectMock
    PriceAdjustJobExecutionService jobExecutionService;

    private UUID previousVersionId;
    private UUID targetVersionId;
    private UUID reviewId;
    private String customerNo;

    @BeforeEach
    @Transactional
    void seedReview() {
        org.mockito.Mockito.when(sessionHelper.getCurrentUserId(org.mockito.ArgumentMatchers.any()))
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        previousVersionId = UUID.randomUUID();
        targetVersionId = UUID.randomUUID();
        reviewId = UUID.randomUUID();
        customerNo = "P4-REV-" + UUID.randomUUID().toString().substring(0, 8);

        insertVersion(previousVersionId, "P4-PREV", "SUPERSEDED");
        insertVersion(targetVersionId, "P4-TARGET", "PENDING");
        em.createNativeQuery("""
                INSERT INTO material_price_review
                  (id, version_id, previous_version_id, customer_no, material_no,
                   status, budget_status, breached_count, amber_count, missing_count,
                   stale_count, column_count, created_at, updated_at)
                VALUES
                  (:id, :versionId, :previousVersionId, :customerNo, 'P4-MAT-REV',
                   'PENDING', 'READY', 1, 0, 0, 0, 1, now(), now())
                """)
                .setParameter("id", reviewId)
                .setParameter("versionId", targetVersionId)
                .setParameter("previousVersionId", previousVersionId)
                .setParameter("customerNo", customerNo)
                .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO material_price_review_column
                  (id, review_id, column_id, column_label, threshold, sort_order,
                   quote_current, quote_adjusted, costing_current, costing_adjusted,
                   diff_current, diff_adjusted, status, created_at)
                VALUES
                  (:id, :reviewId, 'col-default', 'Product total', 0.123456, 0,
                   98765431.123456789012, 98765431.123456789013,
                   0.000000000001, 0.000000000002,
                   98765431.123456789011, 98765431.123456789011,
                   'NORMAL', now())
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("reviewId", reviewId)
                .executeUpdate();
    }

    private void insertVersion(UUID id, String versionNo, String status) {
        em.createNativeQuery("""
                INSERT INTO element_price_version
                  (id, customer_no, version_no, base_date, status, trigger_type, created_at)
                VALUES (:id, :customerNo, :versionNo, :baseDate, :status, 'MANUAL', now())
                """)
                .setParameter("id", id)
                .setParameter("customerNo", customerNo)
                .setParameter("versionNo", versionNo + "-" + customerNo.substring(7))
                .setParameter("baseDate", LocalDate.now())
                .setParameter("status", status)
                .executeUpdate();
    }

    @AfterEach
    @Transactional
    void cleanupReview() {
        em.createNativeQuery("DELETE FROM material_price_update_job_item WHERE job_id IN "
                        + "(SELECT id FROM material_price_update_job WHERE customer_no=:customerNo)")
                .setParameter("customerNo", customerNo).executeUpdate();
        em.createNativeQuery("DELETE FROM material_price_update_job WHERE customer_no=:customerNo")
                .setParameter("customerNo", customerNo).executeUpdate();
        em.createNativeQuery("DELETE FROM material_price_version_ref WHERE customer_no=:customerNo")
                .setParameter("customerNo", customerNo).executeUpdate();
        em.createNativeQuery("DELETE FROM material_price_review_column WHERE review_id=:reviewId")
                .setParameter("reviewId", reviewId).executeUpdate();
        em.createNativeQuery("DELETE FROM material_price_review WHERE id=:reviewId")
                .setParameter("reviewId", reviewId).executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_version_item WHERE version_id IN (:previousId, :targetId)")
                .setParameter("previousId", previousVersionId)
                .setParameter("targetId", targetVersionId).executeUpdate();
        em.createNativeQuery("DELETE FROM element_price_version WHERE id IN (:previousId, :targetId)")
                .setParameter("previousId", previousVersionId)
                .setParameter("targetId", targetVersionId).executeUpdate();
    }

    @Test
    void reviewListAndDetailReturnTwelveDigitDecimalStrings() {
        RestAssured.given()
                .queryParam("customerNo", customerNo)
                .get("/api/cpq/price-adjust/reviews")
                .then().statusCode(200)
                .body("content[0].quoteCostCurrent", equalTo("98765431.123456789012"))
                .body("content[0].quoteCostAdjusted", equalTo("98765431.123456789013"))
                .body("content[0].costingCost", equalTo("0.000000000001"))
                .body("content[0].diffCurrent", equalTo("98765431.123456789011"))
                .body("content[0].columnCount", equalTo(1))
                .body("totalElements", instanceOf(Number.class));

        RestAssured.given()
                .get("/api/cpq/price-adjust/reviews/" + reviewId)
                .then().statusCode(200)
                .body("comparisonColumns[0].quoteCurrent", equalTo("98765431.123456789012"))
                .body("comparisonColumns[0].quoteAdjusted", equalTo("98765431.123456789013"))
                .body("comparisonColumns[0].costingCurrent", equalTo("0.000000000001"))
                .body("comparisonColumns[0].diffCurrent", equalTo("98765431.123456789011"))
                .body("elementImpactTotal", equalTo("0"));
    }

    @Test
    void impactHasNoPrecisionRequestOrResponseFields() {
        assertNoBigDecimalFields(ApproveRejectRequest.class);
        assertNoBigDecimalFields(ImpactResultDTO.class);
        assertNoBigDecimalFields(ImpactResultDTO.VersionPath.class);
        assertNoBigDecimalFields(ImpactResultDTO.BreachedMaterial.class);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"reviewIds\":[\"" + reviewId + "\"]}")
                .post("/api/cpq/price-adjust/reviews/impact")
                .then().statusCode(200)
                .body("materialCount", equalTo(1))
                .body("quotationCount", equalTo(0))
                .body("versionPaths[0].from", notNullValue())
                .body("versionPaths[0].to", notNullValue());
    }

    @Test
    void approveReturnsOnlyIdsAndStructuralCounts() {
        assertNoBigDecimalFields(PriceAdjustReviewService.ApproveResult.class);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"reviewIds\":[\"" + reviewId + "\"],\"comment\":\"P4 precision\"}")
                .post("/api/cpq/price-adjust/reviews/approve")
                .then().statusCode(202)
                .body("jobId", notNullValue())
                .body("materialCount", equalTo(1))
                .body("quotationCount", equalTo(0))
                .body("itemCount", equalTo(0));
    }

    @Test
    void recomputeBudgetHasNoPrecisionRequestOrResponseBody() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/api/cpq/price-adjust/reviews/" + UUID.randomUUID() + "/recompute-budget")
                .then().statusCode(404);
    }

    private void assertNoBigDecimalFields(Class<?> type) {
        assertFalse(Arrays.stream(type.getDeclaredFields())
                        .anyMatch(field -> field.getType() == BigDecimal.class),
                type.getSimpleName() + " has no precision input/output field under TC-083");
    }
}
