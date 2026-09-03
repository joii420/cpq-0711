package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · 接口层验收：<b>客户产品编号前置 + 编号↔料号映射</b>。
 *
 * <p>覆盖 <b>AC-1 / AC-2 / AC-12 / AC-12b / AC-24</b>。
 *
 * <h3>🚨 本类的核心守卫</h3>
 * AC-12②/AC-12b③ 都要求「{@code material_customer_map} <b>零新增行</b>」——
 * 这不是顺带断言，而是<b>确认没有回退到「改 mcm」的老方案</b>的守卫：
 * {@code uq_mcm_quote_no} 同时是 {@code upsertQuote} 的 ON CONFLICT target 与跨客户串号检测的载体
 * （森萨塔事故的防线），方案甲的全部价值就在于不动它。
 */
@QuarkusTest
@DisplayName("task-260902 · 客户产品编号（AC-1/2/12/12b/24）")
class CustomerProductNoAcTest extends SelConfigAcTestBase {

    private static final String EXISTING_PRODUCTS = "/api/cpq/quotations/%s/existing-products";

    /**
     * <b>AC-1</b>：「{@code material_customer_map} 中<b>不存在</b>客户产品编号 → 输入该编号 →
     * 可继续下一步；不出现任何阻止提示」。
     * <p>后端侧的可观测面是 api.md §2.1 的 {@code GET /check-product-no} ⇒ {@code {"taken": false}}；
     * 前端的「可继续/无提示」由 E2E {@code task260902-selconfig-flow.spec.ts} 覆盖。
     */
    @Test
    @DisplayName("AC-1 未占用的编号 → taken=false")
    void ac1_freeProductNoIsNotTaken() {
        Fx fx = newFixture("ac1");
        String productNo = PREFIX + "NEW-001";

        // 前置自检：该编号在库里确实不存在（不确认就断言 = 可能在验一个假的前置）
        assertEquals(0, count("SELECT count(*) FROM material_customer_map WHERE customer_no='"
                        + fx.customerNo() + "' AND customer_product_no='" + productNo + "'"),
                "AC-1 前置：该编号必须尚未被占用");

        Response res = RestAssured.given()
                .queryParam("customerNo", fx.customerNo())
                .queryParam("productNo", productNo)
                .get(CHECK_PRODUCT_NO).thenReturn();
        assertReachedBusinessLayer(res, "AC-1 编号占用校验");
        System.out.println("[AC-1] " + res.statusCode() + " " + res.asString());

        assertEquals(200, res.statusCode(), "AC-1：校验端点应返回 200，实际=" + res.asString());
        assertEquals(Boolean.FALSE, res.jsonPath().get("taken"),
                "AC-1：未占用的编号 taken 应为 false，实际=" + res.asString());
    }

    /**
     * <b>AC-2</b>：「编号<b>已存在</b>于 {@code material_customer_map} → 输入该编号」⇒
     * ①「阻止继续」；②「提示文案含『该编号已存在，请从产品库添加』」；③「给出跳转入口」。
     *
     * <p>本用例验<b>后端半句</b>（{@code test.md} T-02：前端拦是体验，后端拦是正确性，两处都要验）：
     * {@code GET /check-product-no} ⇒ {@code taken=true} 并带出已对应的销售料号；
     * 直接 POST 提交该编号 ⇒ <b>409 {@code CUSTOMER_PRODUCT_NO_TAKEN}</b>。
     * ②③ 的文案与跳转按钮由 E2E 覆盖。
     */
    @Test
    @DisplayName("AC-2 已占用的编号 → taken=true，且直接提交被 409 挡住")
    void ac2_takenProductNoBlocksSubmit() {
        Fx fx = newFixture("ac2");
        String productNo = PREFIX + "TAKEN-001";
        String occupiedPartNo = seedOccupiedProductNo(fx, productNo);

        Response check = RestAssured.given()
                .queryParam("customerNo", fx.customerNo())
                .queryParam("productNo", productNo)
                .get(CHECK_PRODUCT_NO).thenReturn();
        assertReachedBusinessLayer(check, "AC-2 编号占用校验");
        System.out.println("[AC-2·check] " + check.statusCode() + " " + check.asString());
        assertEquals(200, check.statusCode(), "AC-2：校验端点应返回 200");
        assertEquals(Boolean.TRUE, check.jsonPath().get("taken"),
                "AC-2：已占用的编号 taken 应为 true，实际=" + check.asString());
        assertEquals(occupiedPartNo, check.jsonPath().get("hfPartNo"),
                "AC-2③：响应须带出该编号已对应的销售料号（前端要显示它并给跳转入口），实际=" + check.asString());

        // 后端硬拦（绕过前端直接提交）
        Response submit = configure(fx, submitBody(productNo, newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "100")), List.of(PROC_1))));
        assertReachedBusinessLayer(submit, "AC-2 直接提交已占用编号");
        System.out.println("[AC-2·submit] " + submit.statusCode() + " " + submit.asString());
        assertEquals(409, submit.statusCode(),
                "AC-2：已占用的编号直接提交应返回 409（前端拦是体验、后端拦是正确性），实际="
                        + submit.statusCode() + " " + submit.asString());
        assertTrue(submit.asString().contains("CUSTOMER_PRODUCT_NO_TAKEN"),
                "AC-2：错误码应为 CUSTOMER_PRODUCT_NO_TAKEN，实际=" + submit.asString());
    }

    /**
     * <b>AC-12</b>：「选配生成料号后，进入『从产品库添加』入口」⇒
     * ①「该产品<b>出现在列表中</b>，『客户产品编号』列显示 AC-1 输入的编号」；
     * ②「SQL 验 {@code sel_product_no} 有对应行，🚫 {@code material_customer_map} <b>无新增行</b>」；
     * ③「该行的 {@code source} 标记为 {@code CONFIGURED}（按<b>来源表</b>判定）」。
     */
    @Test
    @DisplayName("AC-12 选配产品能在产品库列表找回，且不写 mcm")
    void ac12_configuredProductFoundInExistingProducts() {
        Fx fx = newFixture("ac12");
        String productNo = PREFIX + "FIND-001";

        assertSubmitOk(configure(fx, submitBody(productNo, newPart(
                        "触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)))),
                "AC-12 提交");
        String partNo = latestLinePartNo(fx);

        // ② sel_product_no 有行 + mcm 零新增
        assertTrue(tableExists("sel_product_no"),
                "AC-12②：新表 sel_product_no 应已由迁移建出（backtask B-16）");
        List<Object[]> spn = rows("SELECT customer_product_no, quote_part_no, customer_product_name "
                + "FROM sel_product_no WHERE customer_no='" + fx.customerNo() + "'");
        System.out.println("[AC-12②] sel_product_no=" + spn.stream().map(java.util.Arrays::toString).toList());
        assertEquals(1, spn.size(), "AC-12②：sel_product_no 应恰好 1 行，实际 " + spn.size());
        assertEquals(productNo, spn.get(0)[0], "AC-12②：customer_product_no 应是输入的编号");
        assertEquals(partNo, spn.get(0)[1], "AC-12②：quote_part_no 应是本次的销售料号");
        assertEquals(0, count("SELECT count(*) FROM material_customer_map WHERE customer_no='"
                        + fx.customerNo() + "'"),
                "AC-12②：🚨 material_customer_map 不得新增任何行（方案甲的核心：不动 mcm）");

        // ①③ 「从产品库添加」列表
        Response list = RestAssured.given()
                .get(String.format(EXISTING_PRODUCTS, fx.quotationId())).thenReturn();
        assertReachedBusinessLayer(list, "AC-12 从产品库添加列表");
        assertEquals(200, list.statusCode(), "AC-12①：列表端点应返回 200，实际=" + list.asString());
        String body = list.asString();
        System.out.println("[AC-12①] existing-products=" + body);
        assertTrue(body.contains(partNo),
                "AC-12①：列表应含本次选配生成的料号 " + partNo + "（历史问题：mcm 编号为空导致找不回），实际=" + body);
        assertTrue(body.contains(productNo),
                "AC-12①：列表的『客户产品编号』应显示输入的编号 " + productNo + "，实际=" + body);
        assertTrue(body.contains("CONFIGURED"),
                "AC-12③：选配来的产品 source 应标记为 CONFIGURED（按来源表判定），实际=" + body);
    }

    /**
     * <b>AC-12b</b>（序列）：「编号 {@code T260902-A} 配出料号 X → 再用<b>新编号</b> {@code T260902-B}
     * 配一套<b>完全相同</b>的配置」⇒
     * ①「第二次<b>复用料号 X</b>」；②「{@code sel_product_no} 出现<b>两行</b>，均落库成功、无 500」；
     * ③「{@code material_customer_map} <b>不新增任何行</b>」；
     * ④「列表能按<b>任一编号</b>找到该产品，且该产品<b>只出现一次</b>（不因两行映射而重复）」。
     */
    @Test
    @DisplayName("AC-12b 一料号多编号：两行映射共存、mcm 零新增、列表不重复")
    void ac12b_onePartNoManyProductNos() {
        Fx fx = newFixture("ac12b");
        Map<String, Object> part = newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1));

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A", part)), "AC-12b 第一次（编号 A）");
        String x = latestLinePartNo(fx);

        Response r2 = configure(fx, submitBody(PREFIX + "B", part));
        assertSubmitOk(r2, "AC-12b 第二次（编号 B，配置相同）");
        String second = latestLinePartNo(fx);

        // ① 复用
        assertEquals(x, second, "AC-12b①：配置完全相同 ⇒ 第二次必须复用料号 X（AC-7 语义不变）");
        // ② 两行映射共存
        List<Object[]> spn = rows("SELECT customer_product_no, quote_part_no FROM sel_product_no "
                + "WHERE customer_no='" + fx.customerNo() + "' ORDER BY customer_product_no");
        System.out.println("[AC-12b②] sel_product_no=" + spn.stream().map(java.util.Arrays::toString).toList());
        assertEquals(2, spn.size(), "AC-12b②：sel_product_no 应有 2 行（一料号多编号），实际 " + spn.size());
        assertEquals(PREFIX + "A", spn.get(0)[0]);
        assertEquals(PREFIX + "B", spn.get(1)[0]);
        assertEquals(x, spn.get(0)[1], "AC-12b②：两行的 quote_part_no 应同为 X");
        assertEquals(x, spn.get(1)[1], "AC-12b②：两行的 quote_part_no 应同为 X");
        // ③ mcm 零新增（守卫）
        assertEquals(0, count("SELECT count(*) FROM material_customer_map WHERE customer_no='"
                        + fx.customerNo() + "'"),
                "AC-12b③：🚨 material_customer_map 不得新增任何行 —— 新增即说明回退到了改 mcm 的老方案");

        // ④ 列表能按任一编号找到，且产品只出现一次
        Response list = RestAssured.given()
                .get(String.format(EXISTING_PRODUCTS, fx.quotationId())).thenReturn();
        assertEquals(200, list.statusCode(), "AC-12b④：列表端点应返回 200");
        String body = list.asString();
        System.out.println("[AC-12b④] existing-products=" + body);
        assertTrue(body.contains(PREFIX + "A") && body.contains(PREFIX + "B"),
                "AC-12b④：两个编号都应能在列表里找到，实际=" + body);
        int occurrences = body.split(java.util.regex.Pattern.quote(x), -1).length - 1;
        System.out.println("[AC-12b④] 料号 " + x + " 在列表响应里出现 " + occurrences + " 次");
        assertEquals(1, occurrences,
                "AC-12b④：该产品在列表里应<b>只出现一次</b>，不因两行编号映射而重复（AP-22 重复渲染族）");
    }

    /**
     * <b>AC-24</b>（边界·并发）：「两个会话<b>同时</b>用同一个新客户产品编号提交选配」⇒
     * 「<b>恰好一个成功、另一个返 409 {@code CUSTOMER_PRODUCT_NO_TAKEN}</b>，🚫 <b>不得出现 500</b>」。
     *
     * <p>🚨 <b>必须真并发</b>：用 {@link CyclicBarrier} 把两个请求卡在同一起跑线上，
     * 串行跑等于没验竞态（SELECT-then-INSERT 的检查串行下永远能挡住）。
     * <p>📌 本条正是 {@code backtask.md} B-23 要防的：后者撞 {@code uq_mcm_quote_cust_prod} /
     * {@code uq_spn_cust_prod} 抛 {@code ConstraintViolationException} 会漏成 500。
     */
    @Test
    @DisplayName("AC-24 并发同编号提交 → 恰好 1×200 + 1×409，无 500")
    void ac24_concurrentSameProductNoYieldsExactlyOne409() throws Exception {
        Fx fx = newFixture("ac24");
        String productNo = PREFIX + "C";
        Map<String, Object> part = newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "100")), List.of(PROC_1));
        Map<String, Object> body = submitBody(productNo, part);

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Response> task = () -> {
                startLine.await(10, TimeUnit.SECONDS);   // 🚨 两个请求在同一瞬间发出
                return RestAssured.given().contentType(ContentType.JSON).body(body)
                        .post(CONFIGURE + fx.quotationId()).thenReturn();
            };
            Future<Response> f1 = pool.submit(task);
            Future<Response> f2 = pool.submit(task);
            Response a = f1.get(60, TimeUnit.SECONDS);
            Response b = f2.get(60, TimeUnit.SECONDS);

            System.out.println("[AC-24] A=" + a.statusCode() + " " + a.asString());
            System.out.println("[AC-24] B=" + b.statusCode() + " " + b.asString());
            assertReachedBusinessLayer(a, "AC-24 并发请求 A");
            assertReachedBusinessLayer(b, "AC-24 并发请求 B");

            int ok = (a.statusCode() == 200 ? 1 : 0) + (b.statusCode() == 200 ? 1 : 0);
            int conflict = (a.statusCode() == 409 ? 1 : 0) + (b.statusCode() == 409 ? 1 : 0);
            assertEquals(0, (a.statusCode() >= 500 ? 1 : 0) + (b.statusCode() >= 500 ? 1 : 0),
                    "AC-24：🚫 不得出现 500 —— 撞唯一约束必须被捕获并映射成 409（B-23），实际 A="
                            + a.statusCode() + " B=" + b.statusCode());
            assertEquals(1, ok, "AC-24：应恰好一个成功，实际成功 " + ok + " 个");
            assertEquals(1, conflict, "AC-24：另一个应返 409，实际 409 " + conflict + " 个");
            String conflictBody = a.statusCode() == 409 ? a.asString() : b.asString();
            assertTrue(conflictBody.contains("CUSTOMER_PRODUCT_NO_TAKEN"),
                    "AC-24：冲突方的错误码应为 CUSTOMER_PRODUCT_NO_TAKEN，实际=" + conflictBody);

            // 落库侧同样只能有一份编号映射
            assertEquals(1, count("SELECT count(*) FROM sel_product_no WHERE customer_no='"
                            + fx.customerNo() + "' AND customer_product_no='" + productNo + "'"),
                    "AC-24：并发后该编号在 sel_product_no 里只应有 1 行");
        } finally {
            pool.shutdownNow();
        }
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /**
     * 构造 AC-2 的前置：让某个客户产品编号「已被占用」。
     * <p>📌 走 {@code material_customer_map}（AC-2 原文点名的就是这张表），
     * 数据带 {@code T260902-} 前缀且绑定本用例自建的 customer_no，@AfterEach 精确清除。
     *
     * @return 该编号已对应的销售料号（AC-2③ 要求响应带出它）
     */
    private String seedOccupiedProductNo(Fx fx, String productNo) {
        String partNo = "T2609" + fx.quotationId().toString().replace("-", "").substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO material_master (id,material_no,material_name,material_type,created_at,updated_at) "
                            + "VALUES (gen_random_uuid(),:p,:n,'零件',NOW(),NOW())")
                    .setParameter("p", partNo).setParameter("n", PREFIX + "既有产品").executeUpdate();
            em.createNativeQuery("INSERT INTO material_customer_map (id,system_type,material_no,customer_no,customer_product_no,created_at,updated_at) "
                            + "VALUES (gen_random_uuid(),'QUOTE',:p,:c,:pn,NOW(),NOW())")
                    .setParameter("p", partNo).setParameter("c", fx.customerNo())
                    .setParameter("pn", productNo).executeUpdate();
        });
        assertEquals(1, count("SELECT count(*) FROM material_customer_map WHERE customer_no='"
                        + fx.customerNo() + "' AND customer_product_no='" + productNo + "'"),
                "AC-2 前置自检：该编号必须真的已被占用，否则本用例验的不是 AC-2 的场景");
        assertNotNull(partNo);
        return partNo;
    }
}
