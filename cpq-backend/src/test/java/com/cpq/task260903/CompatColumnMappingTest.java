package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-AC-4 / B-AC-5 / B-AC-6</b>：兼容视图<b>新表侧</b>的列映射正确性。
 *
 * <h3>🚨 为什么必须有这个类（{@code test.md §3} 第 1 号假绿陷阱）</h3>
 * B 阶段的主验收是 {@code B-5 快照 diff}，但采基线时新表<b>没有选配数据</b> ——
 * {@code UNION ALL} 的新表侧是空集，此时 diff 全绿<b>只证明 V6 侧没被改坏，
 * 完全没有证明新表侧映射正确</b>。⇒ 本类<b>自己造新表数据</b>再验，
 * 🚫 B-AC-4/5/6 不许靠 B-AC-2 的 diff 顺带认定。
 *
 * <h3>零残留</h3>
 * 全部走 {@link Task260903Base#inRollback}，<b>永不提交</b>。
 */
@QuarkusTest
@DisplayName("B-AC-4/5/6 兼容视图新表侧列映射")
class CompatColumnMappingTest extends Task260903Base {

    /** 本次运行唯一的测试料号 —— 即使回滚失效也不会撞任何存量数据。 */
    private String mark() { return DS_MARK + UUID.randomUUID().toString().substring(0, 8); }

    /**
     * <b>B-AC-4</b>「在新表插一条 {@code output_material_type='ASSEMBLY'} 的测试行」⇒
     * 「兼容视图的 {@code characteristic} 列返回 {@code ASSEMBLY}」（api.md §2.1：
     * {@code characteristic} ← {@code output_material_type}，取值逐字相同）。
     *
     * <p>顺带覆盖 <b>B-AC-6</b>：新表行没有 {@code is_current} 列 ⇒ 兼容视图恒返 {@code true}。
     */
    @Test
    @DisplayName("B-AC-4/6 characteristic ← output_material_type；is_current 恒 true")
    void bac4_characteristicMapsFromOutputMaterialType() {
        requireCompatViews();
        String mn = mark();
        String cust = "T2609CP" + UUID.randomUUID().toString().substring(0, 6);

        inRollback(() -> {
            insertDsMaterial(mn, "T260903 测试料号");
            insertDsCustomerPart(cust, mn + "-PN", mn);
            insertDsMaterialBom(mn, 1, RECIPE_A, "ASSEMBLY", "0.700000000000");
            insertDsMaterialBom(mn, 2, RECIPE_B, "RECIPE",   "0.300000000000");

            List<Object[]> rows = rows(
                    "SELECT characteristic, is_current, seq_no, component_no, material_ratio, system_type, customer_no "
                            + "FROM " + COMPAT_MBI + " WHERE material_no='" + mn + "' ORDER BY seq_no");

            // 🚨 断言从未执行 = 假绿：先证明结果非空，再谈列值对不对
            System.out.println("[B-AC-4] 兼容视图返回 " + rows.size() + " 行：");
            rows.forEach(r -> System.out.println("    " + java.util.Arrays.toString(r)));
            assertEquals(2, rows.size(),
                    "B-AC-4 前置：造了 2 行新表数据，兼容视图必须都读得到。实际 " + rows.size()
                            + " 行 ⇒ 新表侧根本没进 UNION，后面的列断言会空跑（假绿）");

            assertEquals("ASSEMBLY", String.valueOf(rows.get(0)[0]),
                    "B-AC-4：characteristic 必须逐字返回 output_material_type 的值 'ASSEMBLY'（api.md §2.1）");
            assertEquals("RECIPE", String.valueOf(rows.get(1)[0]),
                    "B-AC-4：第二行 characteristic 应为 'RECIPE'");

            // B-AC-6
            assertEquals(Boolean.TRUE, rows.get(0)[1],
                    "B-AC-6：新表行无 is_current 列 ⇒ 兼容视图必须恒返 true（api.md §2.1 常量 true）");
            assertEquals(Boolean.TRUE, rows.get(1)[1],
                    "B-AC-6：第二行 is_current 同样应恒为 true");

            // 顺带核对 api.md §2.1 的其余新表侧映射
            assertEquals(RECIPE_A, String.valueOf(rows.get(0)[3]),
                    "api.md §2.1：component_no ← input_material_no");
            assertEquals(0, new java.math.BigDecimal("0.700000000000")
                            .compareTo(new java.math.BigDecimal(String.valueOf(rows.get(0)[4]))),
                    "api.md §2.1：material_ratio 直取，且 12 位小数不得被截断。实际=" + rows.get(0)[4]);
            assertEquals("QUOTE", String.valueOf(rows.get(0)[5]),
                    "api.md §2.1：system_type 新表侧为常量 'QUOTE'");
            assertEquals(cust, String.valueOf(rows.get(0)[6]),
                    "api.md §2.1：customer_no 由 JOIN ds_quote_customer_part 取得");
        });
    }

    /**
     * <b>B-AC-5</b>「{@code input_material_no='00006'}」⇒
     * 「{@code component_usage_type} 返回 {@code AgNi10}（由 JOIN {@code material_recipe.symbol} 补出）」。
     *
     * <h3>🚨 同时覆盖 {@code test.md §3} 第 5 号陷阱：INNER JOIN 吞掉外购件行</h3>
     * 外购件的 {@code input_material_no} <b>不在 {@code material_recipe} 里</b>。
     * api.md §2.1 明写「必须 LEFT —— INNER 会吞掉整行」。
     * ⇒ 本用例<b>必须同时造一个外购件行</b>，只造材质行的话 INNER/LEFT 表现完全一样，漏检。
     */
    @Test
    @DisplayName("B-AC-5 component_usage_type ← material_recipe.symbol；外购件行不得被 INNER JOIN 吞掉")
    void bac5_componentUsageTypeFromRecipeSymbol_andOutsourcedRowSurvives() {
        requireCompatViews();

        // 前置对账：00006 → AgNi10 必须真的成立，否则本用例断的是一个不存在的事实
        String symbol = scalar("SELECT symbol FROM material_recipe WHERE code='" + RECIPE_A + "'");
        assertEquals(RECIPE_A_SYMBOL, symbol,
                "B-AC-5 前置：需求文档举例 00006→AgNi10，但库里 material_recipe.code='" + RECIPE_A
                        + "' 的 symbol 实际是 " + symbol + " ⇒ 前置漂移，请先确认基线数据");

        // 外购件料号：取一个确实不在 material_recipe 里的
        String outsourced = scalar(
                "SELECT material_no FROM material_master WHERE material_type='外购件' "
                        + "AND material_no NOT IN (SELECT code FROM material_recipe) ORDER BY material_no LIMIT 1");
        assertNotNull(outsourced,
                "B-AC-5 前置：需要一个『不在 material_recipe 里』的外购件料号来验 LEFT JOIN。"
                        + "库里一条都没有 ⇒ 本用例会退化成只验材质行（INNER/LEFT 无差别），漏检 ⇒ 硬失败，请先补数据");
        System.out.println("[B-AC-5] 外购件料号=" + outsourced + "（不在 material_recipe 中）");

        String mn = mark();
        String cust = "T2609CP" + UUID.randomUUID().toString().substring(0, 6);

        inRollback(() -> {
            insertDsMaterial(mn, "T260903 测试料号");
            insertDsCustomerPart(cust, mn + "-PN", mn);
            insertDsMaterialBom(mn, 1, RECIPE_A,   "RECIPE",   "0.700000000000");  // 材质行
            insertDsMaterialBom(mn, 2, outsourced, "ASSEMBLY", "1.000000000000");  // 外购件行

            List<Object[]> rows = rows(
                    "SELECT seq_no, component_no, component_usage_type "
                            + "FROM " + COMPAT_MBI + " WHERE material_no='" + mn + "' ORDER BY seq_no");
            System.out.println("[B-AC-5] 兼容视图返回 " + rows.size() + " 行：");
            rows.forEach(r -> System.out.println("    " + java.util.Arrays.toString(r)));

            // 🚨 这一条就是 INNER JOIN 的照妖镜：INNER 时外购件行消失 → 只剩 1 行
            assertEquals(2, rows.size(),
                    "B-AC-5：造了 2 行（1 材质 + 1 外购件），兼容视图必须都返回。实际 " + rows.size()
                            + " 行 ⇒ 若为 1 行，就是 material_recipe 用了 INNER JOIN 把外购件整行吞了"
                            + "（api.md §2.1 明写必须 LEFT）");

            assertEquals(RECIPE_A_SYMBOL, String.valueOf(rows.get(0)[2]),
                    "B-AC-5：材质行的 component_usage_type 应由 material_recipe.symbol 补出 = " + RECIPE_A_SYMBOL);
            assertNull(rows.get(1)[2],
                    "B-AC-5：外购件不在 material_recipe 里 ⇒ component_usage_type 应为 NULL（LEFT JOIN 的自然结果），"
                            + "实际=" + rows.get(1)[2]);
        });
    }

    /**
     * <b>B-AC-6</b> 的独立断言：<b>存量 V6 侧</b>的 {@code is_current} 必须仍是原值，
     * 不能被新表侧的「常量 true」污染成全 true。
     *
     * <p>📌 需求文档只写了新表侧恒 true。但兼容视图是 UNION，
     * 「新表侧常量 true」写错位置（写成整个视图的 SELECT 常量）会让 V6 侧的 false 行也变 true，
     * 而 B-AC-2 的 diff <b>抓得到</b>（值变了）—— 这里再加一道，是为了让失败定位更快。
     */
    @Test
    @DisplayName("B-AC-6 存量 V6 侧的 is_current=false 行不得被污染成 true")
    void bac6_v6SideIsCurrentNotOverwritten() {
        requireCompatViews();
        long v6False = count("SELECT count(*) FROM material_bom_item WHERE is_current = false");
        assertTrue(v6False > 0,
                "B-AC-6 前置：需要 V6 侧存在 is_current=false 的行才能验『没被污染』。"
                        + "实际 0 条 ⇒ 本断言会空跑（假绿），请确认基线数据");
        long viaCompat = count("SELECT count(*) FROM " + COMPAT_MBI + " WHERE is_current = false");
        System.out.println("[B-AC-6] V6 直查 is_current=false " + v6False + " 行；兼容视图 " + viaCompat + " 行");
        assertEquals(v6False, viaCompat,
                "B-AC-6：兼容视图必须原样透传 V6 侧的 is_current。"
                        + "若兼容视图这里变成 0，说明『新表侧常量 true』写到了整个视图上，把 V6 侧也刷成了 true");
    }
}
