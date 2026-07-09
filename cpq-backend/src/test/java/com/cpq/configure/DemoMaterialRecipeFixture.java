package com.cpq.configure;

import jakarta.persistence.EntityManager;

/**
 * ConfigureProductServiceTest / ConfigureProductServiceSalesFingerprintTest 依赖的 demo 材质夹具。
 *
 * <p><b>背景（task-0708）</b>：决策#8 用 V318 一次性删除了 V171 注入的 12 条 demo seed 材质
 * （UI 清爽），但上述两个测试类把 {@code AgCu85/AgCu90/AgNi90/AgNi95} 当"事务外持久、只读"
 * 的 DB 夹具。为让它们自给自足、不再依赖已被删的迁移 seed，这里按 V171 原配置在测试前
 * <b>幂等</b>补种（{@code ON CONFLICT DO NOTHING}）。生产库不受影响（迁移已删、无测试运行）。
 */
public final class DemoMaterialRecipeFixture {

    private DemoMaterialRecipeFixture() {}

    /** 幂等补种 4 条 demo 材质 + 元素（须在 @Transactional 上下文调用以提交）。 */
    public static void ensureSeeded(EntityManager em) {
        em.createNativeQuery(
            "INSERT INTO material_recipe (code, symbol, name, spec_label, recipe_type, sort_order, status) VALUES " +
            "('AgCu85','AgCu','银铜合金','85/15','locked',10,'ACTIVE'), " +
            "('AgCu90','AgCu','银铜合金','90/10','locked',20,'ACTIVE'), " +
            "('AgNi90','AgNi','银镍合金','90/10','editable',30,'ACTIVE'), " +
            "('AgNi95','AgNi','银镍合金','95/5','editable',40,'ACTIVE') " +
            "ON CONFLICT (code) DO NOTHING").executeUpdate();

        // locked：min/max NULL, is_locked=true
        seedLocked(em, "AgCu85", "Ag", "银", "85.0", 1);
        seedLocked(em, "AgCu85", "Cu", "铜", "15.0", 2);
        seedLocked(em, "AgCu90", "Ag", "银", "90.0", 1);
        seedLocked(em, "AgCu90", "Cu", "铜", "10.0", 2);
        // editable：min/max 有值, is_locked=false
        seedEditable(em, "AgNi90", "Ag", "银", "90.0", "85.0", "95.0", 1);
        seedEditable(em, "AgNi90", "Ni", "镍", "10.0", "5.0", "15.0", 2);
        seedEditable(em, "AgNi95", "Ag", "银", "95.0", "90.0", "98.0", 1);
        seedEditable(em, "AgNi95", "Ni", "镍", "5.0", "2.0", "10.0", 2);
    }

    private static void seedLocked(EntityManager em, String code, String ec, String en, String pct, int so) {
        em.createNativeQuery(
            "INSERT INTO material_recipe_element " +
            "(recipe_id, element_code, element_name, default_pct, is_locked, sort_order) " +
            "SELECT id, :ec, :en, CAST(:pct AS numeric), true, :so FROM material_recipe WHERE code = :code " +
            "ON CONFLICT (recipe_id, element_code) DO NOTHING")
            .setParameter("ec", ec).setParameter("en", en).setParameter("pct", pct)
            .setParameter("so", so).setParameter("code", code).executeUpdate();
    }

    private static void seedEditable(EntityManager em, String code, String ec, String en,
                                     String pct, String min, String max, int so) {
        em.createNativeQuery(
            "INSERT INTO material_recipe_element " +
            "(recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order) " +
            "SELECT id, :ec, :en, CAST(:pct AS numeric), CAST(:min AS numeric), CAST(:max AS numeric), false, :so " +
            "FROM material_recipe WHERE code = :code " +
            "ON CONFLICT (recipe_id, element_code) DO NOTHING")
            .setParameter("ec", ec).setParameter("en", en).setParameter("pct", pct)
            .setParameter("min", min).setParameter("max", max).setParameter("so", so)
            .setParameter("code", code).executeUpdate();
    }
}
