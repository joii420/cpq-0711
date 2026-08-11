package com.cpq.priceadjust.resource;

import com.cpq.priceadjust.dto.JobDTO;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
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
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(PriceAdjustJobPrecisionHttpContractTest.RbacOffProfile.class)
class PriceAdjustJobPrecisionHttpContractTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    @Inject
    EntityManager em;

    private UUID customerId;
    private UUID quotationId;
    private UUID jobId;
    private String customerCode;

    @BeforeEach
    @Transactional
    void seedFixture() {
        String customerBody = """
                {
                  "name":"P4 Job Precision %s",
                  "level":"STANDARD",
                  "contacts":[{"name":"P4","phone":"13800000110","isPrimary":true}]
                }
                """.formatted(UUID.randomUUID());
        String customer = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(customerBody)
                .post("/api/cpq/customers")
                .then().statusCode(200)
                .extract().path("data.id");
        customerId = UUID.fromString(customer);
        customerCode = (String) em.createNativeQuery("SELECT code FROM customer WHERE id=:id")
                .setParameter("id", customerId).getSingleResult();

        String quotation = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"customerId\":\"" + customerId
                        + "\",\"name\":\"P4 Job Precision\",\"quoteType\":\"STANDARD\"}")
                .post("/api/cpq/quotations")
                .then().statusCode(200)
                .extract().path("data.id");
        quotationId = UUID.fromString(quotation);

        MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
        job.customerNo = customerCode;
        job.versionNo = "P4-JOB-V1";
        job.status = MaterialPriceUpdateJob.SUCCESS;
        job.totalCount = 1;
        job.successCount = 1;
        job.persist();
        em.flush();
        jobId = job.id;

        MaterialPriceUpdateJobItem item = new MaterialPriceUpdateJobItem();
        item.jobId = jobId;
        item.quotationId = quotationId;
        item.materialNo = "P4-MAT-001";
        item.status = MaterialPriceUpdateJobItem.SUCCESS;
        item.diffValue = new BigDecimal("98765431.123456789012");
        item.persist();
    }

    @AfterEach
    @Transactional
    void cleanupFixture() {
        if (jobId != null) {
            em.createNativeQuery("DELETE FROM material_price_update_job_item WHERE job_id=:id")
                    .setParameter("id", jobId).executeUpdate();
            em.createNativeQuery("DELETE FROM material_price_update_job WHERE id=:id")
                    .setParameter("id", jobId).executeUpdate();
        }
        if (quotationId != null) {
            em.createNativeQuery("DELETE FROM quotation WHERE id=:id")
                    .setParameter("id", quotationId).executeUpdate();
        }
        if (customerId != null) {
            em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id=:id")
                    .setParameter("id", customerId).executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE id=:id")
                    .setParameter("id", customerId).executeUpdate();
        }
    }

    @Test
    void jobListAndDetailKeepStructuralCountsAsNumbers() {
        assertFalse(Arrays.stream(JobDTO.class.getDeclaredFields())
                        .anyMatch(field -> field.getType() == BigDecimal.class),
                "JobDTO has no precision field; TC-083 does not apply to job list/detail requests");

        RestAssured.given()
                .queryParam("customerNo", customerCode)
                .get("/api/cpq/price-adjust/jobs")
                .then().statusCode(200)
                .body("content.find { it.jobId == '" + jobId + "' }.total", equalTo(1))
                .body("content.find { it.jobId == '" + jobId + "' }.success", equalTo(1))
                .body("totalElements", instanceOf(Number.class));

        RestAssured.given()
                .get("/api/cpq/price-adjust/jobs/" + jobId)
                .then().statusCode(200)
                .body("jobId", equalTo(jobId.toString()))
                .body("total", equalTo(1))
                .body("success", equalTo(1));
    }

    @Test
    void jobItemsReturnTwelveDigitWorkingValueAsDecimalString() {
        RestAssured.given()
                .get("/api/cpq/price-adjust/jobs/" + jobId + "/items")
                .then().statusCode(200)
                .body("content[0].diffValue", equalTo("98765431.123456789012"))
                .body("content[0].retryCount", equalTo(0))
                .body("totalElements", equalTo(1));
    }
}
