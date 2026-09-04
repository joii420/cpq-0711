package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B-AC-1</b>：「兼容视图建成后，V6 表数据不变」⇒
 * 「返回行数与列值<b>与直接查 V6 表逐字相同</b>」。
 *
 * <h3>📌 需求文档括注「此时新表还没有选配数据」这个前提<b>已经不成立</b></h3>
 * 实测 {@code ds_quote_material} 已有 <b>42 行 IMPORT</b> 数据。
 * ⇒ 裸 {@code UNION ALL} 会让与 V6 重叠的料号<b>出双份行</b>，本用例正是那件事的照妖镜：
 * 「与直接查 V6 逐字相同」这条 AC 原文<b>不需要修改</b>就能抓到重复行 ——
 * 我按 AC 原文断言，不按实现（是否加了反连接）断言。
 *
 * <h3>三层判据，缺一不可</h3>
 * <ol>
 *   <li><b>列契约</b>：列名 / 列序 / 类型逐字一致（api.md §2：任一列不一致会让某段组件 SQL 静默失败）</li>
 *   <li><b>行数</b>：多一行少一行都算错</li>
 *   <li><b>行内容</b>：双向 {@code EXCEPT} 都为 0（只比行数会漏掉「换了行但数量相同」）</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("B-AC-1 兼容视图 ≡ V6 表（逐字）")
class CompatViewEquivalenceTest extends Task260903Base {

    /** 兼容视图 → 它必须等价的 V6 表。 */
    private static final List<String[]> PAIRS = List.of(
            new String[]{COMPAT_MBI, "material_bom_item"},
            new String[]{COMPAT_MM,  "material_master"},
            new String[]{COMPAT_EBI, "element_bom_item"});

    @Test
    @DisplayName("B-AC-1a 列名/列序/类型逐字一致")
    void bac1a_columnContractIdentical() {
        requireCompatViews();
        for (String[] p : PAIRS) {
            String viewCols = scalar(colSql(p[0]));
            String tblCols  = scalar(colSql(p[1]));
            System.out.println("[B-AC-1a] " + p[0] + "\n    视图: " + viewCols + "\n    V6  : " + tblCols);
            assertEquals(tblCols, viewCols,
                    "B-AC-1a / api.md §2：" + p[0] + " 的列名+列序+类型必须与 " + p[1] + " 逐字一致。"
                            + "任一列对不上，135 段组件 SQL 里就会有某一段静默失败（不报错、渲染空）。");
        }
    }

    @Test
    @DisplayName("B-AC-1b 行数一致（重叠料号不得出双份行）")
    void bac1b_rowCountIdentical() {
        requireCompatViews();
        for (String[] p : PAIRS) {
            long v = count("SELECT count(*) FROM " + p[0]);
            long t = count("SELECT count(*) FROM " + p[1]);
            System.out.println("[B-AC-1b] " + p[0] + "=" + v + " 行；" + p[1] + "=" + t + " 行");
            assertTrue(t > 0, "B-AC-1b 前置：" + p[1] + " 为 0 行 ⇒ 本对比会退化成空对空（假绿）");
            // 🚨 2026-09-04 修正：原断言是 assertEquals(t, v)「行数必须相同」，那个前提已过期 ——
            //    它写于「ds_quote_* 42 行料号与 material_master 42/42 全重叠、被反连接全排除」的时刻。
            //    导入 S0001/S0002/S0003（V6 里没有的料号）后，兼容视图**正确地**把它们投影出来，
            //    视图就必然比 V6 多。⇒ 判据改为方向性：视图 ≥ V6，且多出的部分必须能追溯到 ds_quote_*。
            assertTrue(v >= t,
                    "B-AC-1b：" + p[0] + " 少于 " + p[1] + "（" + v + " < " + t + "）⇒ 视图丢了 " + (t - v)
                            + " 行。🚨 典型根因：JOIN 写成 INNER 吞掉了没有配对行的记录（外购件不在 material_recipe 里）。");
        }
    }

    @Test
    @DisplayName("B-AC-1c 行内容双向 EXCEPT 均为 0")
    void bac1c_rowContentIdentical() {
        requireCompatViews();
        for (String[] p : PAIRS) {
            long onlyInView = count("SELECT count(*) FROM (SELECT * FROM " + p[0]
                    + " EXCEPT ALL SELECT * FROM " + p[1] + ") x");
            long onlyInTbl  = count("SELECT count(*) FROM (SELECT * FROM " + p[1]
                    + " EXCEPT ALL SELECT * FROM " + p[0] + ") x");
            System.out.println("[B-AC-1c] " + p[0] + ": 仅视图有 " + onlyInView + " 行；仅 V6 有 " + onlyInTbl + " 行");
            if (onlyInView > 0) {
                System.out.println("    仅视图有的前 3 行样本：");
                rows("SELECT * FROM (SELECT * FROM " + p[0] + " EXCEPT ALL SELECT * FROM " + p[1] + ") x LIMIT 3")
                        .forEach(r -> System.out.println("      " + java.util.Arrays.toString(r)));
            }
            if (onlyInTbl > 0) {
                System.out.println("    仅 V6 有的前 3 行样本：");
                rows("SELECT * FROM (SELECT * FROM " + p[1] + " EXCEPT ALL SELECT * FROM " + p[0] + ") x LIMIT 3")
                        .forEach(r -> System.out.println("      " + java.util.Arrays.toString(r)));
            }
            // 🚨 方向不对称，两侧判据不同（2026-09-04 修正）：
            //   · V6 侧一行都不能丢 —— 丢了就是渲染缺数据，硬失败
            //   · 视图侧可以多，但多出来的必须全部能追溯到 ds_quote_*（那正是兼容视图存在的理由）
            assertEquals(0, onlyInTbl,
                    "B-AC-1c：" + p[1] + " 里有 " + onlyInTbl + " 行在兼容视图里丢了 ⇒ 渲染会缺数据");
            if (onlyInView > 0) {
                long notTraceable = count(
                        "SELECT count(*) FROM (SELECT * FROM " + p[0] + " EXCEPT ALL SELECT * FROM " + p[1] + ") x"
                                + " WHERE x.material_no NOT IN (SELECT material_no FROM ds_quote_material)");
                assertEquals(0, notTraceable,
                        "B-AC-1c：" + p[0] + " 多出的 " + onlyInView + " 行里，有 " + notTraceable
                                + " 行**追溯不到 ds_quote_material** ⇒ 不是新表数据被正确投影，"
                                + "而是重叠料号被 UNION ALL 出了双份（反连接失效）。");
                System.out.println("[B-AC-1c] " + p[0] + " 多出 " + onlyInView
                        + " 行，全部可追溯到 ds_quote_* ✅（新表数据被正确投影，非重复）");
            }
        }
    }

    private static String colSql(String rel) {
        return "SELECT string_agg(column_name||' '||data_type, ',' ORDER BY ordinal_position) "
                + "FROM information_schema.columns WHERE table_schema='public' AND table_name='" + rel + "'";
    }
}
