package com.cpq.quotation.snapshot;

import com.cpq.customer.entity.Customer;
import com.cpq.product.entity.Product;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SnapshotCollectorOptionalRelationTest {

    private static final String PLATING_PLAN = "\"public\".\"plating_plan\"";

    @Inject
    EntityManager em;

    @Inject
    SnapshotCollectorService snapshotCollectorService;

    @Test
    @TestTransaction
    void missingOptionalPlatingPlanLeavesTransactionWritable() {
        assertNull(regclass(PLATING_PLAN), "V361 schema must not contain the renamed legacy table");
        Fixture fixture = createProductBackedFixture("missing");

        SnapshotCollectorService.SubmissionSnapshot snapshot = snapshotCollectorService.collect(
                fixture.quotation().id, null, fixture.customer().id);

        assertFalse(snapshot.masterDataSnapshot().containsKey("plating_plan"));
        fixture.quotation().name = "collector transaction remains writable";
        em.flush();
        String persistedName = (String) em.createNativeQuery("SELECT name FROM quotation WHERE id = :id")
                .setParameter("id", fixture.quotation().id)
                .getSingleResult();
        assertEquals("collector transaction remains writable", persistedName);
    }

    @Test
    @TestTransaction
    void existingOptionalRelationQueryErrorIsNotDowngradedToEmptySnapshot() {
        assertNull(regclass(PLATING_PLAN), "test requires the V361 schema baseline");
        Fixture fixture = createProductBackedFixture("invalid");
        em.createNativeQuery("CREATE TABLE public.plating_plan (id uuid PRIMARY KEY)").executeUpdate();
        assertNotNull(regclass(PLATING_PLAN));

        assertThrows(RuntimeException.class, () -> snapshotCollectorService.collect(
                fixture.quotation().id, null, fixture.customer().id));
        // The test transaction is now intentionally aborted by PostgreSQL. @TestTransaction rolls
        // back both the fixture and the deliberately incompatible table after this method returns.
    }

    private Fixture createProductBackedFixture(String label) {
        Customer customer = new Customer();
        customer.name = "optional relation test customer";
        customer.code = "SCOR-" + UUID.randomUUID().toString().substring(0, 8);
        customer.level = "STANDARD";
        customer.persist();

        Product product = new Product();
        product.name = "optional relation test product";
        product.partNo = "SCOR-P-" + UUID.randomUUID().toString().substring(0, 8);
        product.category = "TEST";
        product.persist();

        Quotation quotation = new Quotation();
        quotation.quotationNumber = "SCOR-" + label + "-" + UUID.randomUUID();
        quotation.customerId = customer.id;
        quotation.name = "optional relation test quotation";
        quotation.salesRepId = firstUserId();
        quotation.persist();

        QuotationLineItem line = new QuotationLineItem();
        line.quotationId = quotation.id;
        line.productId = product.id;
        line.productNameSnapshot = product.name;
        line.productPartNoSnapshot = product.partNo;
        line.persist();
        em.flush();

        return new Fixture(customer, quotation);
    }

    private UUID firstUserId() {
        Object raw = em.createNativeQuery("SELECT id FROM \"user\" ORDER BY id LIMIT 1").getSingleResult();
        return raw instanceof UUID id ? id : UUID.fromString(raw.toString());
    }

    private Object regclass(String qualifiedRelationName) {
        return em.createNativeQuery("SELECT to_regclass(:relationName)")
                .setParameter("relationName", qualifiedRelationName)
                .getSingleResult();
    }

    private record Fixture(Customer customer, Quotation quotation) {}
}
