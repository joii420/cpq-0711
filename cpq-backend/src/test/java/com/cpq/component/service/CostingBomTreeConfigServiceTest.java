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
        // task-260909 B-2：2 参重载的默认 usage 由 COSTING 改为 COST_BASIC（COSTING 已不可写）
        assertEquals(b.id, CostingBomTreeConfig.findActive("COST_BASIC").id);
    }

    @Test
    @TestTransaction
    void setActiveOnAlreadyActiveStillLeavesItActive() {
        CostingBomTreeConfig a = svc.create("A", VALID);
        svc.setActive(a.id);
        svc.setActive(a.id);   // 对已生效配置再点一次（B1 回归用例）
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(a.id)).isActive);
        assertEquals(a.id, CostingBomTreeConfig.findActive("COST_BASIC").id);
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
        CostingBomTreeConfig costingCfg = svc.create("C1", VALID, "COST_BASIC");
        svc.setActive(costingCfg.id);
        CostingBomTreeConfig quoteCfg = svc.create("Q1", VALID, "QUOTE");
        svc.setActive(quoteCfg.id);
        // 激活 QUOTE 配置不得下线 COSTING 现役配置（AC-10 零回归门禁）
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(costingCfg.id)).isActive);
        assertTrue(((CostingBomTreeConfig) CostingBomTreeConfig.findById(quoteCfg.id)).isActive);
        assertEquals(costingCfg.id, CostingBomTreeConfig.findActive("COST_BASIC").id);
        assertEquals(quoteCfg.id, CostingBomTreeConfig.findActive("QUOTE").id);
    }

    @Test
    @TestTransaction
    void createDefaultsToCostingUsage() {
        CostingBomTreeConfig c = svc.create("D1", VALID);
        // task-260909 B-2：COSTING 已停用（只读兼容别名），2 参便利重载改落 COST_BASIC
        assertEquals("COST_BASIC", c.usage);
    }

    // ── task-260909 B-2：写入值域 ────────────────────────────────────────────

    /** 🚫 写入只读兼容别名 COSTING → 400，且文案要指路（api.md §1.1）。 */
    @Test
    @TestTransaction
    void createRejectsLegacyCostingUsage() {
        com.cpq.common.exception.BusinessException ex =
                assertThrows(com.cpq.common.exception.BusinessException.class,
                        () -> svc.create("X1", VALID, "COSTING"));
        assertEquals(400, ex.getCode());
        assertEquals("COSTING 已停用，请选择 COST_BASIC 或 COST_DETAIL", ex.getMessage());
    }

    /**
     * 🚫 未传 usage → 400。原「兜底 COSTING」已取消：三套并存后静默兜底
     * 就是「用户选了详细核价、系统写成基础核价」那类故障。
     */
    @Test
    @TestTransaction
    void createRejectsMissingUsage() {
        assertEquals(400, assertThrows(com.cpq.common.exception.BusinessException.class,
                () -> svc.create("X2", VALID, null)).getCode());
        assertEquals(400, assertThrows(com.cpq.common.exception.BusinessException.class,
                () -> svc.create("X3", VALID, "  ")).getCode());
    }

    /** COST_DETAIL 可写可查 —— 本期只交付机制，库里还没有详细核价模板（AC-2 空态）。 */
    @Test
    @TestTransaction
    void createAcceptsCostDetail() {
        CostingBomTreeConfig d = svc.create("D-detail", VALID, "COST_DETAIL");
        assertEquals("COST_DETAIL", d.usage);
        svc.setActive(d.id);
        assertEquals(d.id, CostingBomTreeConfig.findActive("COST_DETAIL").id);
    }
}
