package com.cpq.configure;

import jakarta.persistence.EntityManager;

/**
 * ConfigureProductServiceTest / ConfigureProductServiceSalesFingerprintTest 依赖的 demo 材质夹具。
 *
 * <p><b>task-260901</b>：材质模型改三层（材质 → 含量配置 → 元素行）后，本夹具同步补
 * {@code material_recipe_config}（每条材质一条 {@code <code>-01}）与
 * {@code material_recipe_composition}（元素组成），元素行改挂 {@code config_id}。
 * 仍然全程幂等（{@code ON CONFLICT DO NOTHING}），可在共享 test 库上反复跑。
 *
 * <p><b>背景（task-0708）</b>：决策#8 用 V318 一次性删除了 V171 注入的 12 条 demo seed 材质
 * （UI 清爽），但上述两个测试类把 {@code AgCu85/AgCu90/AgNi90/AgNi95} 当"事务外持久、只读"
 * 的 DB 夹具。为让它们自给自足、不再依赖已被删的迁移 seed，这里按 V171 原配置在测试前
 * <b>幂等</b>补种（{@code ON CONFLICT DO NOTHING}）。生产库不受影响（迁移已删、无测试运行）。
 */
public final class DemoMaterialRecipeFixture {

    private DemoMaterialRecipeFixture() {}

    /** 幂等补种 4 条 demo 材质 + 配置 + 元素组成 + 元素行（须在 @Transactional 上下文调用以提交）。 */
    public static void ensureSeeded(EntityManager em) {
        em.createNativeQuery(
            "INSERT INTO material_recipe (code, symbol, name, spec_label, recipe_type, sort_order, status) VALUES " +
            "('AgCu85','AgCu','银铜合金','85/15','locked',10,'ACTIVE'), " +
            "('AgCu90','AgCu','银铜合金','90/10','locked',20,'ACTIVE'), " +
            "('AgNi90','AgNi','银镍合金','90/10','editable',30,'ACTIVE'), " +
            "('AgNi95','AgNi','银镍合金','95/5','editable',40,'ACTIVE') " +
            "ON CONFLICT (code) DO NOTHING").executeUpdate();

        // task-260901（M-5）：这 4 条 demo 材质是<b>选配「自定义含量」用例的专用夹具</b>，
        // 新模型下自定义含量由材质级开关 allow_custom_content 裁决（false 时直接 403、
        // 不进元素级 is_locked 判断）。因此夹具必须显式把开关打开，否则那些用例验的
        // 不再是「自定义含量算得对不对」而是「开关关着」。
        // ⚠️ 只影响这 4 个 demo code（V318 已把它们从业务数据里删掉，现在纯属测试夹具）。
        em.createNativeQuery(
            "UPDATE material_recipe SET allow_custom_content = true " +
            "WHERE code IN ('AgCu85','AgCu90','AgNi90','AgNi95')").executeUpdate();

        // task-260901：每条材质一条 <code>-01 配置（seq=1）
        for (String code : new String[]{"AgCu85", "AgCu90", "AgNi90", "AgNi95"}) {
            em.createNativeQuery(
                "INSERT INTO material_recipe_config (recipe_id, config_no, seq, status, sort_order) " +
                "SELECT id, :code || '-01', 1, 'ACTIVE', 1 FROM material_recipe WHERE code = :code " +
                "ON CONFLICT (config_no) DO NOTHING")
                .setParameter("code", code).executeUpdate();
        }

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
            "(config_id, element_no, element_code, element_name, default_pct, is_locked, sort_order) " +
            "SELECT c.id, (SELECT element_no FROM element WHERE element_code = :ec LIMIT 1), " +
            "       :ec, :en, CAST(:pct AS numeric), true, :so " +
            "FROM material_recipe_config c JOIN material_recipe r ON r.id = c.recipe_id " +
            "WHERE r.code = :code AND c.seq = 1 " +
            "ON CONFLICT (config_id, element_code) DO NOTHING")
            .setParameter("ec", ec).setParameter("en", en).setParameter("pct", pct)
            .setParameter("so", so).setParameter("code", code).executeUpdate();
        seedComposition(em, code, ec, en, so);
    }

    private static void seedEditable(EntityManager em, String code, String ec, String en,
                                     String pct, String min, String max, int so) {
        em.createNativeQuery(
            "INSERT INTO material_recipe_element " +
            "(config_id, element_no, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order) " +
            "SELECT c.id, (SELECT element_no FROM element WHERE element_code = :ec LIMIT 1), " +
            "       :ec, :en, CAST(:pct AS numeric), CAST(:min AS numeric), CAST(:max AS numeric), false, :so " +
            "FROM material_recipe_config c JOIN material_recipe r ON r.id = c.recipe_id " +
            "WHERE r.code = :code AND c.seq = 1 " +
            "ON CONFLICT (config_id, element_code) DO NOTHING")
            .setParameter("ec", ec).setParameter("en", en).setParameter("pct", pct)
            .setParameter("min", min).setParameter("max", max).setParameter("so", so)
            .setParameter("code", code).executeUpdate();
        seedComposition(em, code, ec, en, so);
    }

    /** 元素组成（task-260901 · M-0）：element_no 按符号反查主表，查不到退化用符号本身（列 NOT NULL）。 */
    private static void seedComposition(EntityManager em, String code, String ec, String en, int so) {
        em.createNativeQuery(
            "INSERT INTO material_recipe_composition (recipe_id, element_no, element_code, element_name, sort_order) " +
            "SELECT r.id, COALESCE((SELECT element_no FROM element WHERE element_code = :ec LIMIT 1), :ec), " +
            "       :ec, :en, :so " +
            "FROM material_recipe r WHERE r.code = :code " +
            "ON CONFLICT (recipe_id, element_no) DO NOTHING")
            .setParameter("ec", ec).setParameter("en", en).setParameter("so", so)
            .setParameter("code", code).executeUpdate();
    }
}
