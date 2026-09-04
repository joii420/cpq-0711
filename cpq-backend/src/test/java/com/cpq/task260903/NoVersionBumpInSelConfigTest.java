package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-AC-5</b>：「连续三次配置<b>不同</b>产品」⇒
 * 「三个料号的 {@code version_no} <b>全部 = 1</b>，{@code _history} 表零新增」。
 *
 * <p>📌 用户裁决：<b>选配阶段不发生版本升级</b>。
 *
 * <h3>🚨 {@code test.md §3} 第 3 号假绿陷阱</h3>
 * 「{@code version_no} 全 1」<b>在一行都没写时同样成立</b>（空集合上的全称命题恒真）。
 * ⇒ 每个料号都先过 {@code assertNewTablesGotRows}，再谈版本号。
 */
@QuarkusTest
@DisplayName("A-AC-5 选配阶段不升版")
class NoVersionBumpInSelConfigTest extends Task260903Base {

    @Test
    @DisplayName("A-AC-5 连配三个不同产品 → version_no 全 1、_history 零新增")
    void aac5_threeDistinctProductsNeverBumpVersion() {
        Fx fx = newFixture("aac5");
        Map<String, Long> hist0 = historyCounts();
        System.out.println("[A-AC-5] 起始 history=" + hist0);

        // 三次「不同」的配置：靠总重区分（task-260902 AC-8 已证总重进指纹 ⇒ 必铸不同料号）
        String[] weights = {"10", "20", "30"};
        List<String> partNos = new ArrayList<>();

        for (int i = 0; i < weights.length; i++) {
            assertSubmitOk(configure(fx, submitBody(PREFIX + "A5-" + i,
                    newPart("触点", "φ5", "5×3×2", weights[i],
                            List.of(material(RECIPE_A, CONFIG_A, "70"),
                                    material(RECIPE_B, CONFIG_B, "30")),
                            List.of(PROC_1)))), "A-AC-5 第 " + (i + 1) + " 次提交（总重 " + weights[i] + "g）");
            String pn = latestLinePartNo(fx);
            // 🚨 先证明真写了行，否则「version_no 全 1」是空集恒真
            assertNewTablesGotRows(pn, "A-AC-5 第 " + (i + 1) + " 次");
            partNos.add(pn);
        }

        System.out.println("[A-AC-5] 三个料号=" + partNos);
        assertEquals(3, partNos.stream().distinct().count(),
                "A-AC-5 前置：三次配置的是『不同』产品，应铸出 3 个不同料号，实际=" + partNos
                        + " ⇒ 若有重复，本用例实际只验了 1~2 个料号，覆盖不足");

        // ① 三个料号在两张版本化子表里的 version_no 必须全是 1
        for (String pn : partNos) {
            for (String table : List.of("ds_quote_material_bom", "ds_quote_element_bom")) {
                long n = count("SELECT count(*) FROM " + table + " WHERE material_no='" + pn + "'");
                if (n == 0 && table.equals("ds_quote_element_bom")) {
                    System.out.println("[A-AC-5] " + pn + " 在 " + table + " 无行，跳过（该料号无元素行）");
                    continue;
                }
                assertTrue(n > 0, "A-AC-5：" + pn + " 在 " + table + " 应有行，实际 0 ⇒ 版本断言会空跑");
                List<Object> vs = col("SELECT DISTINCT version_no FROM " + table
                        + " WHERE material_no='" + pn + "' ORDER BY 1");
                System.out.println("[A-AC-5] " + pn + " @ " + table + " version_no=" + vs + "（" + n + " 行）");
                assertEquals(List.of(1), vs.stream().map(v -> ((Number) v).intValue()).toList(),
                        "A-AC-5：" + table + " 中料号 " + pn + " 的 version_no 必须全部 = 1"
                                + "（用户裁决：选配阶段不升版），实际=" + vs);
            }
        }

        // ② _history 零新增
        Map<String, Long> hist1 = historyCounts();
        System.out.println("[A-AC-5] 结束 history=" + hist1);
        assertEquals(hist0, hist1,
                "A-AC-5：选配阶段不升版 ⇒ _history 表必须零新增。"
                        + "出现新增行说明写入器发生了版本升级，" + hist0 + "→" + hist1);
    }
}
