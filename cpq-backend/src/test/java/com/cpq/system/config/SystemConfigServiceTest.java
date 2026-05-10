package com.cpq.system.config;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.config.dto.CreateSystemConfigRequest;
import com.cpq.system.config.dto.SystemConfigDTO;
import com.cpq.system.config.dto.UpdateSystemConfigRequest;
import com.cpq.system.config.entity.SystemConfig;
import com.cpq.system.config.service.SystemConfigService;
import io.quarkus.cache.CacheManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemConfigService unit/integration tests.
 * Covers AC-1.1, AC-1.2, AC-1.3, AC-1.4 + Caffeine cache scenario.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemConfigServiceTest {

    @Inject
    SystemConfigService configService;

    @Inject
    CacheManager cacheManager;

    @Inject
    EntityManager em;

    private static final UUID TEST_OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String TEST_KEY = "test.service_unit";

    @BeforeEach
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM system_config WHERE config_key LIKE 'test.%'").executeUpdate();
    }

    // ======== AC-1.1: list by category ========

    @Test
    @Order(1)
    void ac1_1_listByCategory_import_returnsAllImportConfigs() {
        // Given: 23 initial rows are seeded by V37; 'import' category has 6 rows
        List<SystemConfigDTO> results = configService.list("import");

        assertFalse(results.isEmpty(), "import category must have at least 1 config");
        results.forEach(r -> assertEquals("import", r.category,
                "All returned items must be in 'import' category"));
    }

    @Test
    @Order(2)
    void ac1_1_listAllConfigs_returns23OrMore() {
        // Bug-2: SystemConfig.listAll() calls itself recursively (StackOverflow).
        // PanacheEntityBase.listAll() is shadowed by SystemConfig.listAll() which recurses.
        // This test documents the bug by expecting StackOverflowError or a valid list.
        // Fix needed: remove SystemConfig.listAll() override to delegate to Panache parent.
        try {
            List<SystemConfigDTO> results = configService.list(null);
            assertTrue(results.size() >= 23,
                    "At least 23 system_config rows expected from V37 seed, got: " + results.size());
        } catch (StackOverflowError soe) {
            // Bug-2 confirmed: SystemConfig.listAll() infinite recursion
            fail("Bug-2: SystemConfig.listAll() causes StackOverflowError due to self-referential recursion. " +
                 "The method overrides Panache's listAll() but calls itself instead of super.listAll(). " +
                 "Fix: delete the SystemConfig.listAll() override (Panache provides it via inheritance).");
        }
    }

    // ======== AC-1.2: create new config ========

    @Test
    @Order(3)
    void ac1_2_create_newKey_returns201AndPersists() {
        CreateSystemConfigRequest req = new CreateSystemConfigRequest();
        req.configKey = TEST_KEY;
        req.configValue = "42";
        req.defaultValue = "42";
        req.dataType = "NUMBER";
        req.category = "import";
        req.description = "unit test config";
        req.modifiableBy = "SYSTEM_ADMIN";

        SystemConfigDTO dto = configService.create(req, TEST_OPERATOR);

        assertNotNull(dto);
        assertEquals(TEST_KEY, dto.configKey);
        assertEquals("42", dto.configValue);
        assertEquals("import", dto.category);

        // Verify DB persistence
        SystemConfig fromDb = SystemConfig.findByKey(TEST_KEY);
        assertNotNull(fromDb, "Must be persisted in DB");
        assertEquals("42", fromDb.configValue);
    }

    @Test
    @Order(4)
    void ac1_2_create_duplicateKey_throws409() {
        // First create
        CreateSystemConfigRequest req = new CreateSystemConfigRequest();
        req.configKey = TEST_KEY;
        req.configValue = "1";
        req.defaultValue = "1";
        req.dataType = "NUMBER";
        req.category = "import";
        configService.create(req, TEST_OPERATOR);

        // Second create with same key should fail
        CreateSystemConfigRequest req2 = new CreateSystemConfigRequest();
        req2.configKey = TEST_KEY;
        req2.configValue = "2";
        req2.defaultValue = "2";
        req2.dataType = "NUMBER";
        req2.category = "import";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.create(req2, TEST_OPERATOR));
        assertEquals(409, ex.getCode());
    }

    // ======== AC-1.3: update existing config ========

    @Test
    @Order(5)
    void ac1_3_update_existingKey_updatesValueAndReturns200() {
        // Given: a known seeded key
        String existingKey = "import.product_lock_timeout_seconds";

        UpdateSystemConfigRequest req = new UpdateSystemConfigRequest();
        req.configValue = "999";

        SystemConfigDTO dto = configService.update(existingKey, req, "SYSTEM_ADMIN", TEST_OPERATOR);

        assertNotNull(dto);
        assertEquals("999", dto.configValue);

        // Restore original value to not affect other tests
        UpdateSystemConfigRequest restore = new UpdateSystemConfigRequest();
        restore.configValue = "300";
        configService.update(existingKey, restore, "SYSTEM_ADMIN", TEST_OPERATOR);
    }

    @Test
    @Order(6)
    void ac1_3_update_nonExistentKey_throws404() {
        UpdateSystemConfigRequest req = new UpdateSystemConfigRequest();
        req.configValue = "999";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.update("nonexistent.key_xyz", req, "SYSTEM_ADMIN", TEST_OPERATOR));
        assertEquals(404, ex.getCode());
    }

    // ======== AC-1.4: modifiable_by permission ========

    @Test
    @Order(7)
    void ac1_4_update_salesManagerKey_bySalesManager_succeeds() {
        // Given: 'business.gross_margin_warning_min' has modifiable_by=SALES_MANAGER
        String key = "business.gross_margin_warning_min";

        UpdateSystemConfigRequest req = new UpdateSystemConfigRequest();
        req.configValue = "0.20";

        // SALES_MANAGER role can modify SALES_MANAGER config
        assertDoesNotThrow(() -> configService.update(key, req, "SALES_MANAGER", TEST_OPERATOR));

        // Restore
        UpdateSystemConfigRequest restore = new UpdateSystemConfigRequest();
        restore.configValue = "0.15";
        configService.update(key, restore, "SYSTEM_ADMIN", TEST_OPERATOR);
    }

    @Test
    @Order(8)
    void ac1_4_update_systemAdminKey_bySalesManager_throws403() {
        // Given: 'validation.composition_tolerance' has modifiable_by=SYSTEM_ADMIN
        String key = "validation.composition_tolerance";

        UpdateSystemConfigRequest req = new UpdateSystemConfigRequest();
        req.configValue = "0.99";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.update(key, req, "SALES_MANAGER", TEST_OPERATOR));
        assertEquals(403, ex.getCode(), "SALES_MANAGER cannot modify SYSTEM_ADMIN-only config");
    }

    @Test
    @Order(9)
    void ac1_4_update_systemAdminKey_bySystemAdmin_succeeds() {
        // SYSTEM_ADMIN can always modify any config
        String key = "validation.composition_tolerance";

        UpdateSystemConfigRequest req = new UpdateSystemConfigRequest();
        req.configValue = "0.05";

        assertDoesNotThrow(() -> configService.update(key, req, "SYSTEM_ADMIN", TEST_OPERATOR));

        // Restore
        UpdateSystemConfigRequest restore = new UpdateSystemConfigRequest();
        restore.configValue = "0.01";
        configService.update(key, restore, "SYSTEM_ADMIN", TEST_OPERATOR);
    }

    // ======== Caffeine cache scenario ========

    @Test
    @Order(10)
    void cache_getRaw_cachedValue_invalidatedAfterUpdate() {
        // Prime the cache
        String key = "import.product_lock_timeout_seconds";
        String original = configService.getRaw(key);
        assertEquals("300", original);

        // Update via service (should invalidate cache)
        UpdateSystemConfigRequest req = new UpdateSystemConfigRequest();
        req.configValue = "600";
        configService.update(key, req, "SYSTEM_ADMIN", TEST_OPERATOR);

        // Next call should see the updated value (cache invalidated)
        String updated = configService.getRaw(key);
        assertEquals("600", updated, "Cache should be invalidated after update, fresh DB read should return 600");

        // Restore
        UpdateSystemConfigRequest restore = new UpdateSystemConfigRequest();
        restore.configValue = "300";
        configService.update(key, restore, "SYSTEM_ADMIN", TEST_OPERATOR);
    }

    @Test
    @Order(11)
    void cache_getRaw_unknownKey_throws404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.getRaw("nonexistent.key_abc"));
        assertEquals(404, ex.getCode());
    }

    @Test
    @Order(12)
    void helper_getNumber_returnsDouble() {
        double val = configService.getNumber("import.product_lock_timeout_seconds");
        assertEquals(300.0, val, 0.001);
    }

    @Test
    @Order(13)
    void helper_getNumber_nonNumericValue_throws500() {
        // Create a config with non-numeric value for a NUMBER-typed key
        CreateSystemConfigRequest req = new CreateSystemConfigRequest();
        req.configKey = "test.bad_number";
        req.configValue = "not_a_number";
        req.defaultValue = "not_a_number";
        req.dataType = "STRING";
        req.category = "import";
        configService.create(req, TEST_OPERATOR);

        // getNumber should fail on non-parseable value
        BusinessException ex = assertThrows(BusinessException.class,
                () -> configService.getNumber("test.bad_number"));
        assertEquals(500, ex.getCode());
    }
}
