package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · 接口层验收：<b>指纹复用与铸新号的序列行为</b>。
 *
 * <p>覆盖 <b>AC-7 / AC-8 / AC-9 / AC-10 / AC-19 / AC-20 / AC-22</b>，全部是<b>序列用例</b>
 * （提交 A → 改一个维度 → 提交 B → 断言两次的关系）。
 *
 * <h3>🚨 本类的三条反假绿纪律（{@code test.md §4}）</h3>
 * <ol>
 *   <li><b>断言的是「销售料号本身」相等/不等</b>，🚫 不是「指纹字符串不同」——
 *       后者在实现顺手加了 {@code distinct()} 时照样成立（AC-20）。</li>
 *   <li>AC-19 <b>不能只验复用</b>：实现若把 {@code processNos} 也排序后落库，复用照样成立 ⇒
 *       必须同时断言 {@code unit_price.seq_no} 保留第一次的顺序。</li>
 *   <li>每次比较前先断言料号非空（{@code latestLinePartNo} 内已做），否则 null==null 会假绿。</li>
 * </ol>
 *
 * <p>📌 <b>为什么两次提交要用不同的客户产品编号</b>：AC-2 要求编号在同一客户下唯一，
 * 而 AC-7 要求配置相同 ⇒ 编号必须不同、配置必须相同。这正是 AC-12b 描述的
 * 「一料号多编号」形态，不是用例走样。
 */
@QuarkusTest
@DisplayName("task-260902 · 指纹复用序列（AC-7/8/9/10/19/20/22）")
class FingerprintReuseAcTest extends SelConfigAcTestBase {

    /** AC-7 的基准配置：AgNi10 70% + AgZnO12/Cu 30%，总重 10g。 */
    private Map<String, Object> basePart(String weight, String ratioA, String ratioB, List<String> procs) {
        return newPart("触点", "φ5", "5×3×2", weight,
                List.of(material(RECIPE_A, CONFIG_A, ratioA), material(RECIPE_B, CONFIG_B, ratioB)),
                procs);
    }

    /**
     * <b>AC-7</b>（序列）：「配一个零件（AgNi10 70% + AgZnO12/Cu 30%，总重 10g）→ 提交生成料号 X →
     * 再配一个<b>完全相同的</b> → 提交」⇒「第二次<b>复用料号 X</b>，不铸新号；
     * {@code sel_part_signature} 只有 1 条记录」。
     */
    @Test
    @DisplayName("AC-7 完全相同的配置 → 复用同一料号，签名表只 1 条")
    void ac7_identicalConfigReusesPartNo() {
        Fx fx = newFixture("ac7");

        Response r1 = configure(fx, submitBody(PREFIX + "A7-1", basePart("10", "70", "30", List.of(PROC_1))));
        assertSubmitOk(r1, "AC-7 第一次提交");
        String x = latestLinePartNo(fx);

        Response r2 = configure(fx, submitBody(PREFIX + "A7-2", basePart("10", "70", "30", List.of(PROC_1))));
        assertSubmitOk(r2, "AC-7 第二次提交（完全相同）");
        String second = latestLinePartNo(fx);

        System.out.println("[AC-7] X=" + x + " 第二次=" + second + " fingerprintMatched=" + r2.jsonPath().get("fingerprintMatched"));
        assertEquals(x, second, "AC-7：完全相同的配置必须复用同一销售料号 X，实际第二次=" + second);
        assertEquals(1, count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + fx.customerNo() + "'"),
                "AC-7：sel_part_signature 应只有 1 条记录");
        assertEquals(Boolean.TRUE, r2.jsonPath().get("fingerprintMatched"),
                "AC-7：第二次响应的 fingerprintMatched 应为 true（api.md §1.3）");
        // api.md §1.3：命中复用时带出销售产品信息，供前端展示（AC-7 状态 C / F-9）
        assertNotNull(r2.jsonPath().get("reusedProductInfo"),
                "api.md §1.3：命中复用时响应应带 reusedProductInfo，实际=" + r2.asString());
    }

    /**
     * <b>AC-8</b>（序列）：「同 AC-7，但第二次把<b>总重改成 20g</b>，其余不变」⇒
     * 「第二次<b>铸新料号 Y ≠ X</b>（重量进指纹）」。
     */
    @Test
    @DisplayName("AC-8 只改总重 10g→20g → 铸新料号")
    void ac8_differentWeightMintsNewPartNo() {
        Fx fx = newFixture("ac8");

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A8-1", basePart("10", "70", "30", List.of(PROC_1)))),
                "AC-8 第一次（10g）");
        String x = latestLinePartNo(fx);

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A8-2", basePart("20", "70", "30", List.of(PROC_1)))),
                "AC-8 第二次（20g）");
        String y = latestLinePartNo(fx);

        System.out.println("[AC-8] X=" + x + " Y=" + y);
        assertNotEquals(x, y, "AC-8：零件总重进指纹 ⇒ 10g 与 20g 必须是两个料号（否则重量不同却共用料号 ⇒ 成本错算）");
        assertEquals(2, count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + fx.customerNo() + "'"),
                "AC-8：sel_part_signature 应有 2 条");
    }

    /**
     * <b>AC-9</b>（序列）：「同 AC-7，但第二次把<b>占比改成 50/50</b>，其余不变」⇒
     * 「第二次<b>铸新料号</b>（占比进指纹）」。
     */
    @Test
    @DisplayName("AC-9 只改占比 70/30→50/50 → 铸新料号")
    void ac9_differentRatioMintsNewPartNo() {
        Fx fx = newFixture("ac9");

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A9-1", basePart("10", "70", "30", List.of(PROC_1)))),
                "AC-9 第一次（70/30）");
        String x = latestLinePartNo(fx);

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A9-2", basePart("10", "50", "50", List.of(PROC_1)))),
                "AC-9 第二次（50/50）");
        String y = latestLinePartNo(fx);

        System.out.println("[AC-9] X=" + x + " Y=" + y);
        assertNotEquals(x, y, "AC-9：材质占比进指纹 ⇒ 70/30 与 50/50 必须是两个料号");
    }

    /**
     * <b>AC-10</b>（序列）：「同 AC-7，但第二次材质改选<b>含量逐字相同的另一条配方</b>」⇒
     * 「第二次<b>复用原料号</b>（配方编号不进指纹，按含量判同）」。
     *
     * <p>🚨 <b>这是裁决 D-5 的预期行为，不是 bug。</b> 与 AC-8/AC-9 形成对照：
     * 换了配方编号但含量一个字都没变 ⇒ 就是同一种材料 ⇒ 同一个产品。
     * <b>后人若把它当缺陷「修掉」，等于把 D-5 推翻</b> —— 要改先回主线走裁决。
     *
     * <p>📌 fixture：现网 258 条 ACTIVE 材质里没有「同材质含量逐字相同的两条配置」
     * （唯一有 2 组的 {@code 00262/SnO2} 两组含量不同）⇒ 用<b>本任务自建材质的两条同容配置</b>，
     * @AfterEach 精确删除，不碰存量。
     */
    @Test
    @Disabled("""
            🚫 阻塞（2026-09-03 实跑确认）：AC-10 在当前 schema 下**造不出前置**，不是实现有 bug。

            根因：material_recipe_element 上的唯一索引是
                uq_recipe_element = UNIQUE (recipe_id, element_code)     ← 实查 pg_indexes
            即**同一材质下两条配置不能出现同名元素**。而 AC-10 要的恰恰是「同材质、两条含量
            *逐字相同* 的配置」——逐字相同 ⇒ 元素码必然相同 ⇒ 必撞该唯一索引。
            实跑报错：duplicate key "uq_recipe_element" Key (recipe_id, element_code)=(…, Ag)。
            旁证：现网唯一有 2 组配置的 00262/SnO2，两组的元素码恰恰**不同**（10004 vs Sn）——
            正是被这个约束逼出来的形状。

            为什么不绕（放弃「甲：换个构造方式」）：
              · 换成两条**不同元素码**的配置 ⇒ 含量不再逐字相同 ⇒ 指纹本就该不同 ⇒
                用例会断言复用并失败，而那个失败是**正确行为**，等于把 AC-10 改写成了另一条 AC；
              · 换成**另一个材质** ⇒ MAT= token 含材质码 ⇒ 指纹必不同 ⇒ 同上；
              · 钻 recipe_id 可空的空子（现网 631 行里有 10 行为 NULL）能绕过约束，但那是
                应用自己**不会产生**的行形状，测出来的绿说明不了任何事（典型假绿）。

            解除条件：task-260901 的 **B-3 迁移（DROP CONSTRAINT uq_recipe_element）获批并落地**后，
            删掉本注解即可，用例正文无需改动。

            覆盖缺口有多大：D-5「配方编号不进指纹、按含量内容判同」这条规则**并未失去验证** ——
            AC-22③（自定义含量 90/10 与标准配方逐字相同 → 复用 X）走的是同一条判同路径且可构造，
            已在 ac22_customVsStandardContentDiagonal 覆盖。本条缺的是「配方 ↔ 配方」这一种形态。
            """)
    @DisplayName("AC-10 换成含量逐字相同的另一条配方 → 仍复用（D-5 有意行为）【被 uq_recipe_element 阻塞】")
    void ac10_sameContentDifferentConfigNoStillReuses() {
        String recipe = createRecipe("10", false, List.of(new String[]{"Ag", "90"}, new String[]{"Ni", "10"}));
        addConfig(recipe, recipe + "-02", 2, List.of(new String[]{"Ag", "90"}, new String[]{"Ni", "10"}));
        // 构造自检：两条配置的含量必须逐字相同，否则本用例验的根本不是 AC-10 的场景
        List<Object> pcts = col("SELECT c.config_no||':'||e.element_code||'='||e.default_pct::text "
                + "FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "JOIN material_recipe_element e ON e.config_id=c.id "
                + "WHERE r.code='" + recipe + "' ORDER BY c.config_no, e.sort_order");
        System.out.println("[AC-10] 两条配置的含量=" + pcts);
        assertEquals(4, pcts.size(), "构造自检：应有 2 组 × 2 元素 = 4 行");

        Fx fx = newFixture("ac10");
        Map<String, Object> p1 = newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(recipe, recipe + "-01", "100")), List.of(PROC_1));
        Map<String, Object> p2 = newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(recipe, recipe + "-02", "100")), List.of(PROC_1));

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A10-1", p1)), "AC-10 第一次（配置 -01）");
        String x = latestLinePartNo(fx);
        Response r2 = configure(fx, submitBody(PREFIX + "A10-2", p2));
        assertSubmitOk(r2, "AC-10 第二次（配置 -02，含量逐字相同）");
        String second = latestLinePartNo(fx);

        System.out.println("[AC-10] X=" + x + " 第二次=" + second);
        assertEquals(x, second,
                "AC-10：配方编号不进指纹、按含量内容判同（裁决 D-5）⇒ 换成含量逐字相同的另一条配置必须复用原料号");
        assertEquals(1, count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + fx.customerNo() + "'"),
                "AC-10：只应有 1 条签名");
    }

    /**
     * <b>AC-19</b>（序列）：「工序列表 {@code Z100 焊接 → Z101 铆接} → 料号 X；
     * 再配一个完全相同但工序列表为 {@code Z101 → Z100} 的零件」⇒
     * ①「第二次<b>复用料号 X</b>」；②「{@code sel_part_signature} 仍只有 1 条」；
     * ③「{@code unit_price} 的 {@code seq_no} <b>仍是第一次的顺序</b>（1=Z100 / 2=Z101），
     * 不因第二次的排列而改变」。
     *
     * <p>🚨 {@code test.md §4}：<b>只验①②会漏</b> —— 实现若把 {@code processNos} 也排序后落库，
     * 复用照样成立。③ 才是能抓住它的那条断言。
     */
    @Test
    @DisplayName("AC-19 工序换序 → 复用同一料号，且 unit_price.seq_no 保持第一次的顺序")
    void ac19_processReorderReusesAndKeepsFirstSeqNo() {
        Fx fx = newFixture("ac19");

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A19-1",
                basePart("10", "70", "30", List.of(PROC_1, PROC_2)))), "AC-19 第一次（Z100→Z101）");
        String x = latestLinePartNo(fx);
        Map<String, Integer> seqAfterFirst = processSeqNos(fx, x);
        System.out.println("[AC-19] 第一次 unit_price.seq_no=" + seqAfterFirst);

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A19-2",
                basePart("10", "70", "30", List.of(PROC_2, PROC_1)))), "AC-19 第二次（Z101→Z100）");
        String second = latestLinePartNo(fx);

        // ① 复用
        assertEquals(x, second, "AC-19①：工序顺序不进指纹 ⇒ 换序后必须复用料号 X，实际=" + second);
        // ② 签名 1 条
        assertEquals(1, count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + fx.customerNo() + "'"),
                "AC-19②：sel_part_signature 应仍只有 1 条");
        // ③ seq_no 仍是第一次的顺序
        Map<String, Integer> seqAfterSecond = processSeqNos(fx, x);
        System.out.println("[AC-19③] 第二次后 unit_price.seq_no=" + seqAfterSecond);
        assertTrue(seqAfterSecond.containsKey(PROC_1) && seqAfterSecond.containsKey(PROC_2),
                "AC-19③：unit_price 应能查到 Z100/Z101 两条工序行（查不到 ⇒ 本条断言空跑），实际=" + seqAfterSecond);
        assertEquals(1, seqAfterSecond.get(PROC_1),
                "AC-19③：Z100 的 seq_no 应仍是第一次的 1，实际=" + seqAfterSecond);
        assertEquals(2, seqAfterSecond.get(PROC_2),
                "AC-19③：Z101 的 seq_no 应仍是第一次的 2（🚨 第二次是 Z101→Z100，若这里变成 1 说明被第二次改写了）");
        assertEquals(seqAfterFirst, seqAfterSecond,
                "AC-19③：第二次提交不得改写 unit_price 的 seq_no");
    }

    /**
     * <b>AC-20</b>（序列）：「同 AC-19 的料号 X（工序 {@code Z100, Z101}），再配一个工序为
     * {@code Z100 → Z101 → Z100}（焊接两次）的零件」⇒「<b>铸新料号 Y ≠ X</b>」。
     *
     * <p>🚨 <b>本条是防「顺手 distinct()」的守卫</b>：排序不去重
     * （{@code ["Z100","Z101","Z100"].sort()} → {@code Z100,Z100,Z101} ≠ {@code Z100,Z101}），
     * 所以重复次数仍是指纹维度。实现者若在 {@code sorted()} 旁加 {@code distinct()}，本条立刻红。
     * <p>🚫 <b>只验「指纹字符串不同」拦不住</b> ⇒ 本条断言的是<b>料号本身</b>，
     * 且两次之间<b>只有工序重复次数这一个变量</b>（品名/规格/尺寸/总重/材质/占比全部逐字相同）。
     */
    @Test
    @DisplayName("AC-20 工序重复次数不同（焊两次）→ 必须铸新料号")
    void ac20_repeatedProcessCountMintsNewPartNo() {
        Fx fx = newFixture("ac20");

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A20-1",
                basePart("10", "70", "30", List.of(PROC_1, PROC_2)))), "AC-20 第一次（Z100,Z101）");
        String x = latestLinePartNo(fx);

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A20-2",
                basePart("10", "70", "30", List.of(PROC_1, PROC_2, PROC_1)))), "AC-20 第二次（Z100,Z101,Z100）");
        String y = latestLinePartNo(fx);

        System.out.println("[AC-20] X=" + x + " Y=" + y);
        assertNotEquals(x, y,
                "AC-20：工序重复次数仍进指纹（sort 不去重）⇒『焊两次』与『焊一次』必须是两个料号。"
                        + "相等 ⇒ 实现多半在 sorted() 旁加了 distinct()");
        assertEquals(2, count("SELECT count(*) FROM sel_part_signature WHERE customer_no='" + fx.customerNo() + "'"),
                "AC-20：sel_part_signature 应有 2 条");
    }

    /**
     * <b>AC-22</b>（序列）：「① 用<b>标准配方</b>配一个零件 → 料号 X；
     * ② 换成<b>自定义含量 Ag=88/Ni=12</b> → 提交；③ 再换成<b>自定义含量 Ag=90/Ni=10</b>
     * （与标准配方逐字相同）→ 提交」⇒ ②「铸新料号 Y ≠ X」；③「<b>复用料号 X</b>」。
     *
     * <p>🚨 ③ 才是裁决 D-5「按含量内容判同」的完整验证 —— AC-10 只验了「配方 ↔ 配方」，
     * 本条补上「配方 ↔ 自定义」这条对角线。
     */
    @Test
    @DisplayName("AC-22 自定义 ↔ 标准配方的判同对角线")
    void ac22_customVsStandardContentDiagonal() {
        String recipe = createRecipe("22", true, List.of(new String[]{"Ag", "90"}, new String[]{"Ni", "10"}));
        assertEquals("true", scalar("SELECT allow_custom_content::text FROM material_recipe WHERE code='" + recipe + "'"),
                "构造自检：AC-22 的材质必须 allow_custom_content=true，否则 ②③ 会以 403 结束（验的不是 AC-22）");

        Fx fx = newFixture("ac22");
        Map<String, Object> standard = newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(recipe, recipe + "-01", "100")), List.of(PROC_1));
        Map<String, Object> custom88 = newPart("触点", "φ5", "5×3×2", "10",
                List.of(materialCustom(recipe, "100", List.of(el("Ag", "0.88"), el("Ni", "0.12")))), List.of(PROC_1));
        Map<String, Object> custom90 = newPart("触点", "φ5", "5×3×2", "10",
                List.of(materialCustom(recipe, "100", List.of(el("Ag", "0.90"), el("Ni", "0.10")))), List.of(PROC_1));

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A22-1", standard)), "AC-22① 标准配方");
        String x = latestLinePartNo(fx);

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A22-2", custom88)), "AC-22② 自定义 88/12");
        String y = latestLinePartNo(fx);
        System.out.println("[AC-22] X=" + x + " Y=" + y);
        assertNotEquals(x, y, "AC-22②：含量不同（88/12 ≠ 90/10）⇒ 必须铸新料号");

        Response r3 = configure(fx, submitBody(PREFIX + "A22-3", custom90));
        assertSubmitOk(r3, "AC-22③ 自定义 90/10（与标准逐字相同）");
        String third = latestLinePartNo(fx);
        System.out.println("[AC-22③] 第三次=" + third + " fingerprintMatched=" + r3.jsonPath().get("fingerprintMatched"));
        assertEquals(x, third,
                "AC-22③：自定义含量与标准配方逐字相同 ⇒ 同一个产品，必须复用 X（D-5「按含量内容判同」的对角线验证）");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /**
     * 读回该销售料号在 {@code unit_price} 上的「工序码 → seq_no」。
     * <p>📌 工序码落在 {@code code} 还是 {@code operation_no} 属实现细节，AC 只约束
     * 「Z100 的 seq_no 是 1」这一可观测事实 ⇒ 两列都认，避免把断言绑死在实现选择上。
     */
    private Map<String, Integer> processSeqNos(Fx fx, String partNo) {
        List<Object[]> rows = rows("SELECT code, operation_no, seq_no FROM unit_price "
                + "WHERE customer_no='" + fx.customerNo() + "' AND finished_material_no='" + partNo + "' "
                + "AND is_current=true ORDER BY seq_no");
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> raw = new ArrayList<>();
        for (Object[] r : rows) {
            raw.add(java.util.Arrays.toString(r));
            Integer seq = r[2] == null ? null : ((Number) r[2]).intValue();
            for (int i = 0; i < 2; i++) {
                String v = r[i] == null ? null : r[i].toString();
                if (v != null && (v.equals(PROC_1) || v.equals(PROC_2))) out.put(v, seq);
            }
        }
        System.out.println("[unit_price 原始行] " + raw);
        return out;
    }
}
