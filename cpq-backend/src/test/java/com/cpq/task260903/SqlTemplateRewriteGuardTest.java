package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-AC-2 的结构性前置</b>：135 段 {@code component_sql_view.sql_template} 完成表名替换。
 *
 * <h3>🚨 {@code test.md §3} 第 6 号陷阱：{@code material_bom} 是 {@code material_bom_item} 的前缀</h3>
 * 朴素的字符串替换会把 {@code material_bom_item} 先匹配成 {@code material_bom}，
 * 替出 {@code v_compat_material_bom_item} 之外的畸形表名（如 {@code v_compat_material_bom_item}
 * 变成 {@code v_compat_material_bomitem}），或反过来漏替。
 * ⇒ 替换后必须<b>逐条回读断言不含裸的 V6 表名</b>。
 *
 * <h3>为什么这是「结构性」而不是「实现细节」断言</h3>
 * B-AC-2 的原文判据是「每个页签的行数与单元格值与改造前逐字相同」，那由 B-5 快照 diff 承担。
 * 但快照 diff 有一个盲区：<b>某段 SQL 压根没被替换</b>时，它当然与改造前逐字相同 —— 全绿，
 * 却意味着这一段还在读 V6，A 阶段停写 V6 后它会渲染成空。
 * ⇒ 本类补的正是这个盲区：<b>diff 绿 + 替换完整</b> 两条同时成立，B-AC-2 才算达成。
 */
@QuarkusTest
@DisplayName("B-AC-2 结构性前置：表名替换完整且无畸形")
class SqlTemplateRewriteGuardTest extends Task260903Base {

    /** 必须被替换掉的 V6 裸表名 → 期望替换成的兼容视图名。 */
    private static final List<String[]> RENAMES = List.of(
            new String[]{"material_bom_item", COMPAT_MBI},
            new String[]{"material_master",   COMPAT_MM},
            new String[]{"element_bom_item",  COMPAT_EBI});

    @Test
    @DisplayName("B-AC-2a 无裸 V6 表名残留")
    void bac2a_noBareV6TableNameLeft() {
        requireCompatViews();
        StringBuilder report = new StringBuilder();
        long total = 0;
        for (String[] r : RENAMES) {
            // \m...\M = PG 的词边界；不加边界时 material_bom 会命中 material_bom_item，
            // 而 v_compat_material_bom_item 里也含子串 material_bom_item ⇒ 必须排除已替换的形态
            String sql = "SELECT count(*) FROM component_sql_view "
                    + "WHERE sql_template ~ '(?<!v_compat_)\\m" + r[0] + "\\M'";
            long n = count(sql);
            total += n;
            List<Object> names = col("SELECT sql_view_name FROM component_sql_view "
                    + "WHERE sql_template ~ '(?<!v_compat_)\\m" + r[0] + "\\M' ORDER BY sql_view_name LIMIT 10");
            report.append("  ").append(r[0]).append(" 仍有 ").append(n).append(" 段未替换")
                  .append(n > 0 ? "，样例=" + names : "").append('\n');
        }
        System.out.println("[B-AC-2a] 裸 V6 表名残留统计：\n" + report);
        assertEquals(0, total,
                "B-AC-2a：仍有 " + total + " 段 component_sql_view 直接引用 V6 裸表名。\n" + report
                        + "🚨 这些段在 B-5 快照 diff 里会显示『逐字相同』（因为它们压根没改），"
                        + "但 A 阶段停写 V6 后它们会渲染成空 —— diff 全绿掩盖交付缺口。");
    }

    @Test
    @DisplayName("B-AC-2b 替换后的表名不畸形，且每段都能真正执行")
    void bac2b_rewrittenNamesAreWellFormed() {
        requireCompatViews();
        // 畸形形态：v_compat_ 后面跟的不是三个合法视图名之一
        long malformed = count(
                "SELECT count(*) FROM component_sql_view WHERE sql_template ~ 'v_compat_' "
                        + "AND sql_template !~ '\\mv_compat_(material_bom_item|material_master|element_bom_item)\\M'");
        List<Object> bad = col(
                "SELECT sql_view_name FROM component_sql_view WHERE sql_template ~ 'v_compat_' "
                        + "AND sql_template !~ '\\mv_compat_(material_bom_item|material_master|element_bom_item)\\M' "
                        + "ORDER BY sql_view_name LIMIT 10");
        System.out.println("[B-AC-2b] 畸形 v_compat_ 表名段数=" + malformed + (malformed > 0 ? " 样例=" + bad : ""));
        assertEquals(0, malformed,
                "B-AC-2b：出现了不在三张兼容视图之列的 v_compat_* 表名（典型根因：material_bom 前缀先于 "
                        + "material_bom_item 命中，替出畸形名）。样例=" + bad);

        long rewritten = count("SELECT count(*) FROM component_sql_view WHERE sql_template ~ 'v_compat_'");
        System.out.println("[B-AC-2b] 已替换段数=" + rewritten);
        assertTrue(rewritten > 0,
                "B-AC-2b：0 段包含 v_compat_ ⇒ 表名替换（V411/B-3）尚未落地。"
                        + "这是**环境前置缺失**，不是被测功能的结论。");
    }
}
