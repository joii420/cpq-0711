package com.cpq.component.service;

import com.cpq.component.entity.CostingBomTreeConfig;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CostingBomTreeConfigServiceTest {

    @Inject
    CostingBomTreeConfigService svc;

    private static final String VALID =
            "SELECT p AS root_no, p AS material_no, CAST(NULL AS text) AS bom_version, CAST(NULL AS text) AS parent_no, p::text AS node_path FROM unnest(:production_part_nos) p";

    @Test
    @TestTransaction
    void setActiveIsGloballyUnique() {
        CostingBomTreeConfig a = svc.create("A", VALID);
        CostingBomTreeConfig b = svc.create("B", VALID);
        svc.setActive(a.id);
        svc.setActive(b.id);
        assertFalse(((CostingBomTreeConfig) CostingBomTreeConfig.findById(a.id)).isActive);
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(b.id)).isActive);
        assertEquals(b.id, CostingBomTreeConfig.findActive("COSTING").id);
    }

    @Test
    @TestTransaction
    void setActiveOnAlreadyActiveStillLeavesItActive() {
        CostingBomTreeConfig a = svc.create("A", VALID);
        svc.setActive(a.id);
        svc.setActive(a.id);   // 对已生效配置再点一次（B1 回归用例）
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(a.id)).isActive);
        assertEquals(a.id, CostingBomTreeConfig.findActive("COSTING").id);
    }

    @Test
    @TestTransaction
    void createRejectsInvalidSql() {
        assertThrows(RuntimeException.class, () -> svc.create("bad", "SELECT 1"));
    }

    // ── task-0721 B2: usage 维度隔离 ──────────────────────────────────────

    @Test
    @TestTransaction
    void setActiveOnlyAffectsSameUsage() {
        CostingBomTreeConfig costingCfg = svc.create("C1", VALID, "COSTING");
        svc.setActive(costingCfg.id);
        CostingBomTreeConfig quoteCfg = svc.create("Q1", VALID, "QUOTE");
        svc.setActive(quoteCfg.id);
        // 激活 QUOTE 配置不得下线 COSTING 现役配置（AC-10 零回归门禁）
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(costingCfg.id)).isActive);
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(quoteCfg.id)).isActive);
        assertEquals(costingCfg.id, CostingBomTreeConfig.findActive("COSTING").id);
        assertEquals(quoteCfg.id, CostingBomTreeConfig.findActive("QUOTE").id);
    }

    @Test
    @TestTransaction
    void createDefaultsToCostingUsage() {
        CostingBomTreeConfig c = svc.create("D1", VALID);
        assertEquals("COSTING", c.usage);
    }
}
