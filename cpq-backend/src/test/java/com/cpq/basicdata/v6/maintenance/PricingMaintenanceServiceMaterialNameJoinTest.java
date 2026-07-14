package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.RowsDTO;
import com.cpq.configure.entity.MaterialRecipe;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ELEMENT_BOM sheet 「材质名」两跳 join 单测（task-0712 · childtask-1 · B2）。
 * 路径：material_part_no → material_master.material_recipe_id → material_recipe.name。
 * 用真实 DB（@QuarkusTest），料号/材质料号/材质配方一律加 ZTPMI-*-B2 前缀避免撞现网数据。
 */
@QuarkusTest
class PricingMaintenanceServiceMaterialNameJoinTest {

    @Inject PricingMaintenanceService service;
    @Inject EntityManager em;

    static final String MAT = "ZTPMI-MAT-B2";
    static final String MP_BOUND = "ZTPMI-MPB-B2";
    static final String MP_UNBOUND = "ZTPMI-MPU-B2";
    static final String RECIPE_CODE = "ZTPMI-RCP-B2";

    @Transactional
    void seed() {
        MaterialRecipe r = new MaterialRecipe();
        r.code = RECIPE_CODE;
        r.symbol = "测试符号B2";
        r.name = "测试材质配方B2";
        r.recipeType = "locked";
        r.status = "ACTIVE";
        r.sortOrder = 999999;
        r.createdAt = OffsetDateTime.now();
        r.updatedAt = OffsetDateTime.now();
        r.persist();
        em.flush();
        UUID recipeId = r.id;

        // 销售料号主档（materialExists 依赖）
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, '测试销售料号B2', NOW(), NOW())")
            .setParameter("no", MAT).executeUpdate();
        // 已绑定材质料号
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, material_recipe_id, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, '绑定材质料号B2', :rid, NOW(), NOW())")
            .setParameter("no", MP_BOUND).setParameter("rid", recipeId).executeUpdate();
        // 未绑定材质料号
        em.createNativeQuery("INSERT INTO material_master (id, material_no, material_name, created_at, updated_at) " +
            "VALUES (gen_random_uuid(), :no, '未绑定材质料号B2', NOW(), NOW())")
            .setParameter("no", MP_UNBOUND).executeUpdate();

        em.createNativeQuery("INSERT INTO element_bom_item " +
            "(id, system_type, customer_no, material_no, characteristic, material_part_no, " +
            " seq_no, component_no, content, scrap_rate, is_current, created_at, updated_at) VALUES " +
            "(gen_random_uuid(), 'PRICING', '_GLOBAL_', :m, '1', :bound,   10, 'AG', 0.5, 0.01, TRUE, NOW(), NOW()), " +
            "(gen_random_uuid(), 'PRICING', '_GLOBAL_', :m, '1', :unbound, 10, 'CU', 0.5, 0.01, TRUE, NOW(), NOW())")
            .setParameter("m", MAT).setParameter("bound", MP_BOUND).setParameter("unbound", MP_UNBOUND)
            .executeUpdate();
    }

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM element_bom_item WHERE material_no = :m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM element_bom WHERE material_no = :m").setParameter("m", MAT).executeUpdate();
        em.createNativeQuery("DELETE FROM material_master WHERE material_no IN (:m, :b, :u)")
            .setParameter("m", MAT).setParameter("b", MP_BOUND).setParameter("u", MP_UNBOUND).executeUpdate();
        em.createNativeQuery("DELETE FROM material_recipe WHERE code = :c").setParameter("c", RECIPE_CODE).executeUpdate();
    }

    @BeforeEach void before() { cleanup(); seed(); }
    @AfterEach  void after()  { cleanup(); }

    @Test
    void readRows_elementBom_materialRecipeName_boundVsUnbound() {
        RowsDTO dto = service.readRows(MAT, "ELEMENT_BOM", null);
        assertNotNull(dto.rows);
        assertEquals(2, dto.rows.size());

        Map<String, Object> boundRow = dto.rows.stream()
            .filter(r -> MP_BOUND.equals(r.get("material_part_no"))).findFirst().orElseThrow();
        Map<String, Object> unboundRow = dto.rows.stream()
            .filter(r -> MP_UNBOUND.equals(r.get("material_part_no"))).findFirst().orElseThrow();

        assertEquals("测试材质配方B2", boundRow.get("material_recipe_name"), "已绑定材质料号应带出材质名（两跳 join）");
        assertNull(unboundRow.get("material_recipe_name"), "未绑定材质料号应为 null（前端渲染'未绑定'）");
    }
}
