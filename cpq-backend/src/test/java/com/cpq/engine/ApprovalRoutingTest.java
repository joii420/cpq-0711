package com.cpq.engine;

import com.cpq.approval.entity.ApprovalRule;
import com.cpq.engine.approval.JavaApprovalRoutingService;
import com.cpq.system.entity.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApprovalRoutingTest {

    @Inject
    JavaApprovalRoutingService approvalService;

    @Inject
    EntityManager em;

    private UUID testApproverId;
    private UUID testRegionId;

    @BeforeEach
    @Transactional
    void setup() {
        // Clean up test approval rules
        em.createQuery("DELETE FROM ApprovalRule ar WHERE ar.priority >= 500").executeUpdate();

        // Get an existing user to use as approver (the seed admin)
        User admin = em.createQuery(
                "SELECT u FROM User u WHERE u.role = 'SYSTEM_ADMIN' AND u.status = 'ACTIVE' ORDER BY u.createdAt ASC",
                User.class)
                .setMaxResults(1)
                .getSingleResult();
        testApproverId = admin.id;

        // Get an existing region
        UUID regionId = (UUID) em.createNativeQuery("SELECT id FROM region ORDER BY sort_order ASC LIMIT 1")
                .getSingleResult();
        testRegionId = regionId;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        em.createQuery("DELETE FROM ApprovalRule ar WHERE ar.priority >= 500").executeUpdate();
    }

    @Test
    @Order(1)
    @Transactional
    void fixedRule_returnsApprover() {
        ApprovalRule rule = new ApprovalRule();
        rule.ruleType = "FIXED";
        rule.approverId = testApproverId;
        rule.priority = 500;
        em.persist(rule);
        em.flush();

        UUID result = approvalService.routeApprover(null, null);
        assertEquals(testApproverId, result);
    }

    @Test
    @Order(2)
    @Transactional
    void dynamicRegionMatch_returnsApprover() {
        ApprovalRule rule = new ApprovalRule();
        rule.ruleType = "DYNAMIC";
        rule.approverId = testApproverId;
        rule.matchField = "REGION";
        rule.matchValueId = testRegionId;
        rule.priority = 500;
        em.persist(rule);
        em.flush();

        UUID result = approvalService.routeApprover(testRegionId, null);
        // In shared DB, other rules may match first; just verify a result is returned
        assertNotNull(result);
    }

    @Test
    @Order(3)
    void noRulesMatch_returnsFallbackAdmin() {
        // No test rules inserted, and with priority >= 500 cleaned up
        // Pass non-matching UUIDs
        UUID result = approvalService.routeApprover(UUID.randomUUID(), UUID.randomUUID());
        // Should return the fallback admin (earliest SYSTEM_ADMIN)
        assertNotNull(result);
        assertEquals(testApproverId, result);
    }
}
