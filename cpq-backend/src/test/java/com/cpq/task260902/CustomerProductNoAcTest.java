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
 * <h3>🚨 本类的核心守卫：mcm 的<b>双重角色</b>必须分开断言</h3>
 * {@code material_customer_map} 在选配链路里承担<b>两件不同的事</b>，2026-09-03 由本类的断言顶出来
 * （初版断言把两者混成一件，误报成后端 bug，特此留痕）：
 * <ol>
 *   <li><b>占号表</b>：铸报价料号时必然写一行，{@code customer_product_no} 留 <b>NULL</b>，
 *       防止料号被重复分配。<b>这行是必需的，禁掉就没法铸号。</b>
 *       实测现网 QUOTE 域：NULL 占号行 16 / 非空真映射行 1846。</li>
 *   <li><b>客户产品编号映射</b>：{@code customer_product_no} <b>非空</b>的行。
 *       🚫 方案甲要求这类行<b>一条都不许新增</b> —— 客户产品编号只落 {@code sel_product_no}。</li>
 * </ol>
 * ⇒ 判据是「{@code WHERE customer_product_no IS NOT NULL} 的行数」，<b>不是整表行数</b>。
 * 守卫意图不变：确认没有回退到把编号写进 mcm 的老方案 ——
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

        Response res = given()
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

        Response check = given()
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
     * ②「SQL 验 {@code sel_product_no} 有对应行，🚫 {@code material_customer_map} 中<b>不得出现
     * {@code customer_product_no} 非空的新增行</b>」；
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
        assertMcmHoldsNoProductNo(fx, "AC-12②", 1);

        // ①③ 「从产品库添加」列表
        Response list = given()
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
     * ③「{@code material_customer_map} 中 <b>{@code customer_product_no} 非空的行数不变</b>」
     *   （🚨 守卫：确认没有回退到把编号写进 mcm 的老方案；铸号占位行是必需的，见类注释）；
     * ⑤-a（<b>搜索面</b>）「{@code GET existing-products?customerProductNo=<任一编号>} 都必须返回料号 X」；
     * ⑤-b（<b>浏览面</b>）「不带过滤时料号 X <b>只出现 1 次</b>；该行 {@code customerProductNos}
     *   含全部编号 {@code [T260902-A, T260902-B]}；旧字段 {@code customerProductNo} 仍是代表编号
     *   {@code T260902-A}（🚫 不得改成数组，会破坏既有消费方）」。
     *
     * <p>📌 原 ④「能按任一编号找到」可观测面不唯一，已按主线裁决拆成 ⑤-a / ⑤-b。
     * ⑤-b 的理由：{@code DISTINCT ON} 取最早编号作代表 ⇒ 用编号 B 的销售不带过滤看列表会看到编号 A，
     * <b>以为这不是自己的产品</b> —— 「找不到」变成「认不出」，同一个病的另一面。
     *
     * <p>⚠️ <b>一条留给后来者的教训</b>：本用例初版断言「该端点没有按编号过滤的能力」，依据是
     * {@code ExistingProductResourceTest:98-106} 只测了 {@code productName} —— <b>那是改造前的旧测试，
     * 不是当前契约</b>。测试文件同样会过期，契约以 {@code api.md} + 当前实现为准。
     */
    @Test
    @DisplayName("AC-12b 一料号多编号：两行映射共存、mcm 无编号行、按任一编号可搜、列表不重复且带全部编号")
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
        // 🚨 占号行应恰好 1 条：两次提交复用同一个料号 ⇒ 只铸过一次号（是 ① 的推论）
        assertMcmHoldsNoProductNo(fx, "AC-12b③", 1);

        // ⑤-a 搜索面：按**任一编号**过滤都必须能查回料号 X
        for (String no : List.of(PREFIX + "A", PREFIX + "B")) {
            Response hit = given().queryParam("customerProductNo", no)
                    .get(String.format(EXISTING_PRODUCTS, fx.quotationId())).thenReturn();
            assertReachedBusinessLayer(hit, "AC-12b⑤-a 按编号 " + no + " 过滤");
            assertEquals(200, hit.statusCode(), "AC-12b⑤-a：按编号过滤应返回 200，实际=" + hit.asString());
            List<String> found = hit.jsonPath().getList("data.content.materialNo", String.class);
            System.out.println("[AC-12b⑤-a] customerProductNo=" + no + " → " + found);
            assertTrue(found != null && found.contains(x),
                    "AC-12b⑤-a：按编号 " + no + " 过滤必须能查回料号 " + x
                            + "（一料号多编号 ⇒ 每个编号都得找得回自己的产品），实际=" + hit.asString());
        }

        // ⑤-b 浏览面：不带过滤时，产品只出现 1 次，且该行把两个编号都带出来
        Response list = given()
                .get(String.format(EXISTING_PRODUCTS, fx.quotationId())).thenReturn();
        assertReachedBusinessLayer(list, "AC-12b⑤-b 不带过滤浏览");
        assertEquals(200, list.statusCode(), "AC-12b⑤-b：列表端点应返回 200");
        System.out.println("[AC-12b⑤-b] existing-products=" + list.asString());

        List<String> rows = list.jsonPath().getList("data.content.materialNo", String.class);
        assertTrue(rows != null && !rows.isEmpty(),
                "AC-12b⑤-b：列表不得为空（空 ⇒ 下面的断言全部空跑）");
        long occurrences = rows.stream().filter(x::equals).count();
        System.out.println("[AC-12b⑤-b] 料号 " + x + " 在列表里出现 " + occurrences + " 次");
        assertEquals(1, occurrences,
                "AC-12b⑤-b：该产品在列表里应只出现一次，不因两行编号映射而重复（AP-22 重复渲染族）");

        // 代表编号：旧字段语义不变（DISTINCT ON 取最早的那个），🚫 不许把它改成数组
        String representative = list.jsonPath()
                .get("data.content.find { it.materialNo == '" + x + "' }.customerProductNo");
        System.out.println("[AC-12b⑤-b] 代表编号 customerProductNo=" + representative);
        assertEquals(PREFIX + "A", representative,
                "AC-12b⑤-b：旧字段 customerProductNo 应保留「代表编号」语义（最早的 T260902-A），"
                        + "🚫 不得改成数组 —— 那会破坏既有消费方");

        // 🚨 新字段：该行必须带出该料号名下的**全部**客户产品编号
        List<String> allNos = list.jsonPath()
                .getList("data.content.find { it.materialNo == '" + x + "' }.customerProductNos", String.class);
        System.out.println("[AC-12b⑤-b] customerProductNos=" + allNos);
        assertNotNull(allNos,
                "AC-12b⑤-b：列表行须新增 customerProductNos 字段（该料号名下的全部客户产品编号）—— "
                        + "缺了它，用编号 B 的人浏览列表只看到编号 A，会以为这不是自己的产品。实际=" + list.asString());
        // 📌 断言集合相等而非顺序相等：谁是代表已由上面 representative 单独锁死，这里只管「一个都不能少」
        assertEquals(List.of(PREFIX + "A", PREFIX + "B"), allNos.stream().sorted().toList(),
                "AC-12b⑤-b：customerProductNos 应含该料号名下的全部编号，实际=" + allNos);
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

        // 🚨 会话必须**在并发之前**解析好：两个线程各自去 adminSession() 可能同时触发登录，
        //    打满 30/min/IP 的登录限流，然后以「登录失败」的面目掩盖掉本条真正要验的竞态。
        Map<String, String> session = adminSession();

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Response> task = () -> {
                startLine.await(10, TimeUnit.SECONDS);   // 🚨 两个请求在同一瞬间发出
                return RestAssured.given().cookies(session).contentType(ContentType.JSON).body(body)
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

    /**
     * 方案甲的核心守卫：<b>mcm 里不得出现「客户产品编号」</b>（那该落 {@code sel_product_no}），
     * 但<b>铸号占位行必须允许</b>（{@code customer_product_no} 为 NULL）。
     *
     * <p>🚨 <b>为什么要带阳性对照</b>（{@code testing.md §4.4}）：本方法断言的是「某事<b>没</b>发生」。
     * 若哪天 mcm 名下一行都没有，「非空行 = 0」会<b>无条件成立</b> —— 守卫悄悄变成空断言，
     * 而它看起来和真通过一模一样。因此同时断言占号行确实存在：
     * 观察手段能抓到 mcm 的写入，「没抓到编号行」才是有意义的结论。
     *
     * @param expectedHolderRows 期望的占号行数 = 本用例铸出的<b>不同</b>销售料号个数
     */
    private void assertMcmHoldsNoProductNo(Fx fx, String ac, int expectedHolderRows) {
        long total = count("SELECT count(*) FROM material_customer_map WHERE customer_no='" + fx.customerNo() + "'");
        long holder = count("SELECT count(*) FROM material_customer_map WHERE customer_no='"
                + fx.customerNo() + "' AND customer_product_no IS NULL");
        long mapped = count("SELECT count(*) FROM material_customer_map WHERE customer_no='"
                + fx.customerNo() + "' AND customer_product_no IS NOT NULL");
        System.out.println("[" + ac + "] mcm 本客户：总 " + total + " 行 = 占号行(编号 NULL) " + holder
                + " + 编号映射行(编号非空) " + mapped);

        assertEquals(0, mapped,
                ac + "：🚨 material_customer_map 里出现了 customer_product_no 非空的行 —— "
                        + "说明回退到了把客户产品编号写进 mcm 的老方案。客户产品编号只能落 sel_product_no");
        assertEquals(expectedHolderRows, holder,
                ac + " 阳性对照：铸号占位行应有 " + expectedHolderRows + " 条（customer_product_no 为 NULL）。"
                        + "为 0 说明观察手段根本没抓到 mcm 的写入 ⇒ 上面那条『没有编号行』是空断言；"
                        + "多于预期说明铸了多个料号（与复用语义矛盾）");
    }
}
