package com.cpq.priceadjust.impl260918;

import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.service.PriceAdjustJobExecutionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260918 B-5 supplement (AC-9 ① / AC-16): while a job is RUNNING, {@code GET /jobs/{id}} and the list endpoint
 * must report per-status counts live from the items (success grows item by item), not the zeros stored until
 * finalizeJob. Private {@link Rp0918bFixture}; RBAC off for the HTTP probes.
 */
@QuarkusTest
@TestProfile(Repair260918LiveJobCountsSelfTest.RbacOff.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Repair260918LiveJobCountsSelfTest {

    public static class RbacOff implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false", "quarkus.scheduler.enabled", "false");
        }
    }

    @Inject EntityManager em;
    @Inject PriceAdjustJobExecutionService jobService;
    Rp0918bFixture fx;

    @BeforeAll
    void setUp() {
        fx = Rp0918bFixture.create(em);
        org.junit.jupiter.api.Assumptions.assumeTrue(fx != null, Rp0918bFixture.SOURCE_QUOTATION + " missing in this database");
    }

    @AfterAll
    void tearDown() {
        if (fx != null) fx.destroy(em);
    }

    @Test
    void runningJobReportsLiveCounts() throws Exception {
        List<String> mats = List.of("PERFHOT-B00031", "PERFHOT-B00032", "PERFHOT-B00033", "PERFHOT-B00034");
        UUID jobId = QuarkusTransaction.requiringNew().call(() -> {
            MaterialPriceUpdateJob job = new MaterialPriceUpdateJob();
            job.customerNo = fx.customerCode;
            job.versionId = fx.versionId;
            job.versionNo = fx.versionNo;
            job.status = MaterialPriceUpdateJob.RUNNING;
            job.totalCount = mats.size();
            job.persist();
            int n = 0;
            for (String m : mats) {
                MaterialPriceUpdateJobItem it = new MaterialPriceUpdateJobItem();
                it.jobId = job.id;
                it.quotationId = fx.qBig;
                it.materialNo = m;
                it.lineItemId = (UUID) em.createNativeQuery(
                        "SELECT id FROM quotation_line_item WHERE quotation_id = :q AND product_part_no_snapshot = :m")
                    .setParameter("q", fx.qBig).setParameter("m", m).getSingleResult();
                it.status = MaterialPriceUpdateJobItem.WAITING;
                it.createdAt = OffsetDateTime.now().plusNanos(n++ * 1000L);
                it.persist();
            }
            return job.id;
        });

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> jobService.executeJob(jobId));
        List<String> probes = new ArrayList<>();
        int lastSuccess = -1;
        String sample = null;
        boolean listChecked = false;
        while (!run.isDone()) {
            String body = RestAssured.given().get("/api/cpq/price-adjust/jobs/" + jobId).then().statusCode(200).extract().asString();
            JsonPath jp = new JsonPath(body);
            if ("RUNNING".equals(jp.getString("status"))) {
                probes.add(body);
                int success = jp.getInt("success");
                assertTrue(success >= lastSuccess, "success must not go backwards: " + body);
                lastSuccess = success;
                assertEquals(4, jp.getInt("total"));
                if (success >= 2 && jp.getInt("running") == 1 && sample == null) sample = body;
                if (success >= 1 && !listChecked) {
                    String list = RestAssured.given().queryParam("customerNo", fx.customerCode)
                        .get("/api/cpq/price-adjust/jobs").then().statusCode(200).extract().asString();
                    JsonPath lp = new JsonPath(list);
                    assertTrue(lp.getInt("content[0].success") >= 1, "list endpoint live: " + list);
                    System.out.println("[selftest] list probe: " + list);
                    listChecked = true;
                }
            }
            Thread.sleep(120);
        }
        run.get();
        probes.stream().map(p -> "[selftest] probe: " + p).distinct().forEach(System.out::println);
        assertNotNull(sample, "expected a RUNNING probe with success>=2 and running=1");
        System.out.println("[selftest] sample success>=2 running=1: " + sample);
        String done = RestAssured.given().get("/api/cpq/price-adjust/jobs/" + jobId).then().statusCode(200).extract().asString();
        System.out.println("[selftest] finished: " + done);
        JsonPath dp = new JsonPath(done);
        assertEquals("SUCCESS", dp.getString("status"));
        assertEquals(4, dp.getInt("success"));
        assertEquals(0, dp.getInt("running"));
    }
}
