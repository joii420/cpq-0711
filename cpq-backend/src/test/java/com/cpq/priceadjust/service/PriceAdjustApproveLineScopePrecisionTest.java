package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ApproveRejectRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** TC-046: approval jobs must not expand a material upgrade to PART rows. */
@QuarkusTest
class PriceAdjustApproveLineScopePrecisionTest {

    private static final String MATERIAL_NO = "TC046-MATERIAL";

    @Inject
    PriceAdjustReviewService reviewService;

    @Inject
    EntityManager em;

    private final String customerNo = "TC046-" + UUID.randomUUID().toString().substring(0, 8);
    private UUID customerId;
    private UUID quotationId;
    private UUID simpleLineId;
    private UUID compositeLineId;
    private UUID partLineId;
    private UUID versionId;
    private UUID reviewId;
    private UUID jobId;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (jobId != null) {
                exec("DELETE FROM material_price_update_job_item WHERE job_id=:id", "id", jobId);
                exec("DELETE FROM material_price_update_job WHERE id=:id", "id", jobId);
            }
            exec("DELETE FROM material_price_version_ref WHERE customer_no=:c", "c", customerNo);
            if (reviewId != null) {
                exec("DELETE FROM material_price_review WHERE id=:id", "id", reviewId);
            }
            if (versionId != null) {
                exec("DELETE FROM element_price_version_item WHERE version_id=:id", "id", versionId);
                exec("DELETE FROM element_price_version WHERE id=:id", "id", versionId);
            }
            if (quotationId != null) {
                exec("DELETE FROM quotation_line_item WHERE quotation_id=:id", "id", quotationId);
                exec("DELETE FROM quotation WHERE id=:id", "id", quotationId);
            }
            if (customerId != null) {
                exec("DELETE FROM customer WHERE id=:id", "id", customerId);
            }
        });
    }

    @Test
    void approve_sameMaterial_selectsAllTopLevelTypesAndExcludesOnlyPart() {
        assertCompositeTypeDatabaseAndPredicateContract();
        seedFixture();

        ApproveRejectRequest request = new ApproveRejectRequest();
        request.reviewIds = List.of(reviewId);
        PriceAdjustReviewService.ApproveResult result = reviewService.doApprove(request, null);
        jobId = result.jobId;

        List<?> selectedLineIds = QuarkusTransaction.requiringNew().call(() ->
            em.createNativeQuery(
                    "SELECT line_item_id FROM material_price_update_job_item "
                        + "WHERE job_id=:jobId ORDER BY line_item_id")
                .setParameter("jobId", jobId)
                .getResultList());

        assertEquals(2, result.itemCount,
            "TC-046: 同料号 PART 行不得被 approve 扩大为独立升版任务");
        assertEquals(Set.of(simpleLineId, compositeLineId), Set.copyOf(selectedLineIds),
            "TC-046: job item 只能指向既有指定 SIMPLE 行，PART 行必须保持范围外");
    }

    private void assertCompositeTypeDatabaseAndPredicateContract() {
        QuarkusTransaction.requiringNew().run(() -> {
            Object nullable = em.createNativeQuery(
                    "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='quotation_line_item' "
                        + "AND column_name='composite_type'")
                .getSingleResult();
            assertEquals("NO", nullable,
                "TC-046: current schema contract must keep quotation_line_item.composite_type NOT NULL");

            List<?> predicateTruth = em.createNativeQuery(
                    "SELECT composite_type IS NULL OR composite_type <> 'PART' "
                        + "FROM (VALUES (CAST(NULL AS text)),('SIMPLE'),('COMPOSITE'),('PART')) "
                        + "AS types(composite_type)")
                .getResultList();
            assertEquals(List.of(true, true, true, false), predicateTruth,
                "TC-046: approval predicate includes historical NULL and all non-PART types only");
        });
    }

    private void seedFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            customerId = UUID.randomUUID();
            exec("INSERT INTO customer (id, code, name) VALUES (:id,:code,'TC-046 precision customer')",
                "id", customerId, "code", customerNo);

            UUID salesRepId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1")
                .getSingleResult();
            quotationId = UUID.randomUUID();
            exec("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, "
                    + "created_at, updated_at) VALUES (:id,:no,:customerId,'TC-046 scope',:salesRepId,"
                    + "'DRAFT',now(),now())",
                "id", quotationId, "no", "TC046-" + quotationId,
                "customerId", customerId, "salesRepId", salesRepId);

            simpleLineId = UUID.randomUUID();
            exec("INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, composite_type, "
                    + "subtotal, line_unit_price, discount_base_amount, line_final_price, "
                    + "line_discount_amount, line_total_amount, created_at) "
                    + "VALUES (:id,:quotationId,:materialNo,'SIMPLE',1.123456789012,2.123456789012,"
                    + "3.123456789012,4.123456789012,5.123456789012,6.123456789012,now())",
                "id", simpleLineId, "quotationId", quotationId, "materialNo", MATERIAL_NO);

            compositeLineId = insertTopLevelLine("COMPOSITE", 21);

            partLineId = UUID.randomUUID();
            exec("INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, composite_type, "
                    + "subtotal, line_unit_price, discount_base_amount, line_final_price, "
                    + "line_discount_amount, line_total_amount, created_at) "
                    + "VALUES (:id,:quotationId,:materialNo,'PART',11.123456789012,12.123456789012,"
                    + "13.123456789012,14.123456789012,15.123456789012,16.123456789012,now())",
                "id", partLineId, "quotationId", quotationId, "materialNo", MATERIAL_NO);

            versionId = UUID.randomUUID();
            exec("INSERT INTO element_price_version (id, customer_no, version_no, base_date, status, "
                    + "trigger_type, created_at) VALUES (:id,:customerNo,'TC046V1',CURRENT_DATE,'PENDING',"
                    + "'MANUAL',now())",
                "id", versionId, "customerNo", customerNo);

            reviewId = UUID.randomUUID();
            exec("INSERT INTO material_price_review (id, version_id, customer_no, material_no, status, "
                    + "budget_status, created_at, updated_at) VALUES (:id,:versionId,:customerNo,:materialNo,"
                    + "'PENDING','READY',now(),now())",
                "id", reviewId, "versionId", versionId,
                "customerNo", customerNo, "materialNo", MATERIAL_NO);
        });
    }

    private UUID insertTopLevelLine(String compositeType, int base) {
        UUID lineId = UUID.randomUUID();
        exec("INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, composite_type, "
                + "subtotal, line_unit_price, discount_base_amount, line_final_price, "
                + "line_discount_amount, line_total_amount, created_at) "
                + "VALUES (:id,:quotationId,:materialNo,:compositeType,CAST(:subtotal AS numeric),"
                + "CAST(:unitPrice AS numeric),CAST(:discountBase AS numeric),CAST(:finalPrice AS numeric),"
                + "CAST(:discountAmount AS numeric),CAST(:totalAmount AS numeric),now())",
            "id", lineId, "quotationId", quotationId, "materialNo", MATERIAL_NO,
            "compositeType", compositeType,
            "subtotal", base + ".123456789012",
            "unitPrice", (base + 1) + ".123456789012",
            "discountBase", (base + 2) + ".123456789012",
            "finalPrice", (base + 3) + ".123456789012",
            "discountAmount", (base + 4) + ".123456789012",
            "totalAmount", (base + 5) + ".123456789012");
        return lineId;
    }

    private void exec(String sql, Object... nameValuePairs) {
        var query = em.createNativeQuery(sql);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
        }
        query.executeUpdate();
    }
}
