package com.cpq.engine;

import com.cpq.customer.entity.Customer;
import com.cpq.engine.discount.DiscountResult;
import com.cpq.engine.discount.JavaDiscountCalculationService;
import com.cpq.pricing.entity.PricingRule;
import com.cpq.pricing.entity.PricingStrategy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiscountCalculationTest {

    @Inject
    JavaDiscountCalculationService discountService;

    @Inject
    EntityManager em;

    private UUID testCustomerId;

    @BeforeEach
    @Transactional
    void setup() {
        // Clean up test data
        em.createQuery("DELETE FROM PricingRule pr WHERE pr.strategy.id IN " +
                "(SELECT ps.id FROM PricingStrategy ps WHERE ps.name LIKE 'Test%')").executeUpdate();
        em.createQuery("DELETE FROM PricingStrategy ps WHERE ps.name LIKE 'Test%'").executeUpdate();
        em.createQuery("DELETE FROM Customer c WHERE c.code LIKE 'TEST_DISC_%'").executeUpdate();

        // Create a test customer
        Customer customer = new Customer();
        customer.name = "Test Discount Customer";
        customer.code = "TEST_DISC_" + UUID.randomUUID().toString().substring(0, 8);
        customer.level = "STANDARD";
        customer.status = "ACTIVE";
        em.persist(customer);
        em.flush();
        testCustomerId = customer.id;
    }

    @AfterEach
    @Transactional
    void cleanup() {
        em.createQuery("DELETE FROM PricingRule pr WHERE pr.strategy.id IN " +
                "(SELECT ps.id FROM PricingStrategy ps WHERE ps.name LIKE 'Test%')").executeUpdate();
        em.createQuery("DELETE FROM PricingStrategy ps WHERE ps.name LIKE 'Test%'").executeUpdate();
        em.createQuery("DELETE FROM Customer c WHERE c.code LIKE 'TEST_DISC_%'").executeUpdate();
    }

    @Test
    @Order(1)
    void noStrategies_returns100() {
        DiscountResult result = discountService.calculate(testCustomerId, new BigDecimal("50000"));
        assertEquals(0, new BigDecimal("100").compareTo(result.discountRate));
        assertEquals("NO_STRATEGY", result.ruleType);
    }

    @Test
    @Order(2)
    @Transactional
    void singleStrategy_baseDiscountOnly_returnsBaseDiscount() {
        PricingStrategy ps = new PricingStrategy();
        ps.customerId = testCustomerId;
        ps.name = "Test Base Only";
        ps.baseDiscount = new BigDecimal("85.00");
        ps.minOrderAmount = new BigDecimal("1000");
        ps.priority = 1;
        ps.status = "ACTIVE";
        em.persist(ps);
        em.flush();

        DiscountResult result = discountService.calculate(testCustomerId, new BigDecimal("5000"));
        assertEquals(0, new BigDecimal("85.00").compareTo(result.discountRate));
        assertEquals("BASE_DISCOUNT", result.ruleType);
        assertEquals("Test Base Only", result.matchedRuleName);
    }

    @Test
    @Order(3)
    @Transactional
    void singleBulkRule_matches_returnsBulkRate() {
        PricingStrategy ps = new PricingStrategy();
        ps.customerId = testCustomerId;
        ps.name = "Test Bulk Strategy";
        ps.baseDiscount = new BigDecimal("90.00");
        ps.minOrderAmount = new BigDecimal("1000");
        ps.priority = 1;
        ps.status = "ACTIVE";
        em.persist(ps);
        em.flush();

        PricingRule rule = new PricingRule();
        rule.strategy = ps;
        rule.ruleType = "BULK_DISCOUNT";
        rule.thresholdAmount = new BigDecimal("5000");
        rule.discountRate = new BigDecimal("80.00");
        rule.sortOrder = 1;
        em.persist(rule);
        em.flush();

        DiscountResult result = discountService.calculate(testCustomerId, new BigDecimal("10000"));
        assertEquals(0, new BigDecimal("80.00").compareTo(result.discountRate));
        assertEquals("BULK_DISCOUNT", result.ruleType);
    }

    @Test
    @Order(4)
    @Transactional
    void multipleBulkRules_returnsLowestRate() {
        PricingStrategy ps = new PricingStrategy();
        ps.customerId = testCustomerId;
        ps.name = "Test Multi Bulk";
        ps.baseDiscount = new BigDecimal("95.00");
        ps.minOrderAmount = new BigDecimal("1000");
        ps.priority = 1;
        ps.status = "ACTIVE";
        em.persist(ps);
        em.flush();

        PricingRule rule1 = new PricingRule();
        rule1.strategy = ps;
        rule1.ruleType = "BULK_DISCOUNT";
        rule1.thresholdAmount = new BigDecimal("5000");
        rule1.discountRate = new BigDecimal("85.00");
        rule1.sortOrder = 1;
        em.persist(rule1);

        PricingRule rule2 = new PricingRule();
        rule2.strategy = ps;
        rule2.ruleType = "BULK_DISCOUNT";
        rule2.thresholdAmount = new BigDecimal("10000");
        rule2.discountRate = new BigDecimal("75.00");
        rule2.sortOrder = 2;
        em.persist(rule2);
        em.flush();

        // Amount is 20000, both rules have threshold below it, pick lowest discount_rate
        DiscountResult result = discountService.calculate(testCustomerId, new BigDecimal("20000"));
        assertEquals(0, new BigDecimal("75.00").compareTo(result.discountRate));
        assertEquals("BULK_DISCOUNT", result.ruleType);
    }

    @Test
    @Order(5)
    @Transactional
    void amountBelowMinOrderAmount_returns100() {
        PricingStrategy ps = new PricingStrategy();
        ps.customerId = testCustomerId;
        ps.name = "Test High Min";
        ps.baseDiscount = new BigDecimal("80.00");
        ps.minOrderAmount = new BigDecimal("100000");
        ps.priority = 1;
        ps.status = "ACTIVE";
        em.persist(ps);
        em.flush();

        DiscountResult result = discountService.calculate(testCustomerId, new BigDecimal("50000"));
        assertEquals(0, new BigDecimal("100").compareTo(result.discountRate));
        assertEquals("NO_STRATEGY", result.ruleType);
    }

    @Test
    @Order(6)
    @Transactional
    void multipleStrategies_picksFirstByPriority() {
        PricingStrategy ps1 = new PricingStrategy();
        ps1.customerId = testCustomerId;
        ps1.name = "Test Priority Low";
        ps1.baseDiscount = new BigDecimal("90.00");
        ps1.minOrderAmount = new BigDecimal("1000");
        ps1.priority = 1;
        ps1.status = "ACTIVE";
        em.persist(ps1);

        PricingStrategy ps2 = new PricingStrategy();
        ps2.customerId = testCustomerId;
        ps2.name = "Test Priority High";
        ps2.baseDiscount = new BigDecimal("70.00");
        ps2.minOrderAmount = new BigDecimal("1000");
        ps2.priority = 2;
        ps2.status = "ACTIVE";
        em.persist(ps2);
        em.flush();

        // Both meet threshold, should pick ps1 (priority=1)
        DiscountResult result = discountService.calculate(testCustomerId, new BigDecimal("5000"));
        assertEquals(0, new BigDecimal("90.00").compareTo(result.discountRate));
        assertEquals("Test Priority Low", result.matchedRuleName);
    }
}
