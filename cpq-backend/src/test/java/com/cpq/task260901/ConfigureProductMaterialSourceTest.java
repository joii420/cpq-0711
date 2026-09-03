package com.cpq.task260901;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260901 · 接口层验收：<b>选配侧的「含量来源」互斥与自定义含量开关</b>（T-I-17 → AC-18 / AC-19）。
 *
 * <h3>覆盖边界（诚实披露）</h3>
 * 本类只验 <b>被拒绝的四条路径</b>（它们不写库，可在共享 test 库上安全跑并完整还原）：
 * <ol>
 *   <li>{@code allowCustomContent=false} + 自定义含量 ⇒ 403 {@code CUSTOM_CONTENT_NOT_ALLOWED}（AC-18）</li>
 *   <li>{@code configNo} 与 {@code elements} 同时给 / 都不给 ⇒ 400 {@code MATERIAL_SOURCE_AMBIGUOUS}</li>
 *   <li>开关打开后 Σ=1.08 ⇒ 400，文案「含量合计必须为 1，实际 1.08」（AC-19 后半句）</li>
 *   <li>材质无 ACTIVE 配置却指定 configNo ⇒ 409 {@code RECIPE_HAS_NO_CONFIG}（AC-17 选配侧）</li>
 * </ol>
 * 每条都附带断言：<b>{@code material_recipe} / {@code material_recipe_config} 零新增</b>（AC-19「自定义不回流」）。
 *
 * <p>🚧 <b>AC-19 的正向半句（提交成功、{@code element_bom_item} 落 Ag=88 / Ni=12）不在本类</b>：
 * 走通它会在共享库里真实建料号 + 报价行 + 销售指纹，还原面远超本任务登记的三张表，
 * 属 {@code testing.md §4.3} 明令要避开的全局状态污染。该半句由 <b>T-E-07（E2E）+ 主线亲验</b> 承担。
 */
@QuarkusTest
class ConfigureProductMaterialSourceTest extends MaterialAcTestBase {

    private static final String CONFIGURE = "/api/cpq/configure-product/quotations/";

    private UUID quotationId;
    private UUID customerId;

    @BeforeEach
    void seedQuotation() {
        customerId = UUID.randomUUID();
        quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Object admin = em.createNativeQuery("SELECT id FROM \"user\" WHERE username='admin' LIMIT 1")
                .getResultList().stream().findFirst().orElse(null);
            assertNotNull(admin, "前置：admin 用户应存在（V1 迁移）");
            em.createNativeQuery(
                "INSERT INTO customer (id, name, code, level, status, created_at, updated_at) " +
                "VALUES (:id, 'AC260901 Test Customer', :code, 'STANDARD', 'ACTIVE', NOW(), NOW())")
              .setParameter("id", customerId)
              .setParameter("code", "AC9-" + customerId.toString().substring(0, 8))
              .executeUpdate();
            em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :qno, :cid, 'AC260901 Test Quotation', CAST(:uid AS uuid), 'DRAFT', NOW(), NOW())")
              .setParameter("id", quotationId)
              .setParameter("qno", "QT-AC9-" + quotationId.toString().substring(0, 8))
              .setParameter("cid", customerId)
              .setParameter("uid", admin.toString())
              .executeUpdate();
        });
    }

    /** 🚨 还原：本类自己 seed 的 quotation / customer 必须自己清（不落在基类的三张表口径里）。 */
    @AfterEach
    void dropSeededQuotation() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :qid")
              .setParameter("qid", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id = :qid")
              .setParameter("qid", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM customer WHERE id = :cid")
              .setParameter("cid", customerId).executeUpdate();
        });
    }

    // ─────────────────────── 用例 ───────────────────────

    /** AC-18：材质「支持自定义含量」= 关 ⇒ 自定义含量必须被拒（403 CUSTOM_CONTENT_NOT_ALLOWED）。 */
    @Test
    void customContentRejectedWhenSwitchOff() {
        setAllowCustom(false);
        Snapshot before = snapshot();

        Response res = configure(partWithCustom("0.88", "0.12"));
        System.out.println("[AC-18] " + res.statusCode() + " " + res.asString());
        assertEquals(403, res.statusCode(),
            "AC-18/M-5：allowCustomContent=false ⇒ 直接拒绝任何自定义含量，不进元素级校验");
        assertTrue(res.asString().contains("CUSTOM_CONTENT_NOT_ALLOWED")
                || res.asString().contains("该材质不支持自定义含量"),
            "AC-18：错误码/文案应为 CUSTOM_CONTENT_NOT_ALLOWED，实际=" + res.asString());
        before.assertUnchanged("AC-18 被拒后");
    }

    /** api.md §2.4 互斥规则：configNo 与 elements 必须恰好给一个。 */
    @Test
    void materialSourceMustBeExactlyOne() {
        setAllowCustom(true);
        Snapshot before = snapshot();

        Map<String, Object> both = part();
        both.put("configNo", "00006-01");
        both.put("elements", List.of(el("Ag", "0.88"), el("Ni", "0.12")));
        Response r1 = configure(both);
        System.out.println("[AMBIGUOUS·both] " + r1.statusCode() + " " + r1.asString());
        assertEquals(400, r1.statusCode(), "两个都给 ⇒ 400 MATERIAL_SOURCE_AMBIGUOUS");
        assertTrue(r1.asString().contains("MATERIAL_SOURCE_AMBIGUOUS")
                || r1.asString().contains("请选择标准配置或自定义含量之一"), "实际=" + r1.asString());

        Response r2 = configure(part());   // 都不给
        System.out.println("[AMBIGUOUS·neither] " + r2.statusCode() + " " + r2.asString());
        assertEquals(400, r2.statusCode(), "都不给 ⇒ 400 MATERIAL_SOURCE_AMBIGUOUS");
        before.assertUnchanged("互斥规则被拒后");
    }

    /** AC-19 后半句：开关打开后 Σ=1.08 ⇒ 提交被拦，报「含量合计必须为 1，实际 1.08」。 */
    @Test
    void customContentSumNotOneRejected_withExactMessage() {
        setAllowCustom(true);
        Snapshot before = snapshot();

        Response res = configure(partWithCustom("0.88", "0.20"));   // Σ = 1.08
        System.out.println("[AC-19·Σ] " + res.statusCode() + " " + res.asString());
        assertEquals(400, res.statusCode(), "AC-19：Σ=1.08 ⇒ 提交被拦");
        String body = res.asString();
        assertTrue(body.contains("含量合计必须为 1"), "AC-19：文案须为「含量合计必须为 1，实际 1.08」，实际=" + body);
        assertTrue(body.contains("1.08"), "AC-19：文案须带上实际值 1.08，实际=" + body);
        before.assertUnchanged("AC-19 Σ 校验被拒后");
    }

    /** AC-17 选配侧 + api.md：材质无 ACTIVE 配置却指定 configNo ⇒ 409 RECIPE_HAS_NO_CONFIG。 */
    @Test
    void recipeWithoutActiveConfigRejected() {
        // 造一条 0 配置的测试材质（AC测% 前缀，随基类还原）
        String code = createZeroConfigRecipe();
        Snapshot before = snapshot();

        Map<String, Object> p = part();
        p.put("recipeCode", code);
        p.put("configNo", code + "-01");
        Response res = configure(p);
        System.out.println("[AC-17·选配] " + res.statusCode() + " " + res.asString());
        assertTrue(res.statusCode() == 409 || res.statusCode() == 400,
            "AC-17：0 配置的材质在选配侧不可用，实际 " + res.statusCode() + " " + res.asString());
        assertTrue(res.asString().contains("RECIPE_HAS_NO_CONFIG")
                || res.asString().contains("该材质尚未配置含量"),
            "AC-17：错误码/文案应为 RECIPE_HAS_NO_CONFIG，实际=" + res.asString());
        before.assertUnchanged("AC-17 被拒后");
    }

    // ─────────────────────── 辅助 ───────────────────────

    private Response configure(Map<String, Object> part) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productType", "SIMPLE");
        body.put("parts", List.of(part));
        return RestAssured.given().contentType(ContentType.JSON).body(body)
            .post(CONFIGURE + quotationId).thenReturn();
    }

    private Map<String, Object> part() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "AC260901 test part");
        p.put("partMode", "custom");
        p.put("recipeCode", REAL_RECIPE_CODE);
        p.put("unitWeightGrams", "1");
        return p;
    }

    private Map<String, Object> partWithCustom(String p1, String p2) {
        Map<String, Object> p = part();
        p.put("elements", List.of(el("Ag", p1), el("Ni", p2)));
        return p;
    }

    /** api.md §2.4：pct 由 number 改为 string。 */
    private Map<String, Object> el(String code, String pct) {
        return Map.of("elementCode", code, "pct", pct);
    }

    private void setAllowCustom(boolean v) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE material_recipe SET allow_custom_content = :v WHERE code = :c")
            .setParameter("v", v).setParameter("c", REAL_RECIPE_CODE).executeUpdate());
        assertEquals(String.valueOf(v),
            scalar("SELECT allow_custom_content::text FROM material_recipe WHERE code='" + REAL_RECIPE_CODE + "'"),
            "前置：开关须真的被置成 " + v + "（不确认就断言 = 可能在验一个没生效的前置）");
    }

    private String createZeroConfigRecipe() {
        String symbol = AC_PREFIX + "无配置";
        String code = nextCode(maxRecipeCode5(), 1);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                "INSERT INTO material_recipe (code, symbol, name, recipe_type, sort_order, status) " +
                "VALUES (:code, :sym, :sym, 'locked', 9991, 'ACTIVE')")
              .setParameter("code", code).setParameter("sym", symbol).executeUpdate();
            em.createNativeQuery(
                "INSERT INTO material_recipe_composition (recipe_id, element_no, element_code, element_name, sort_order) " +
                "SELECT r.id, e.element_no, e.element_code, e.element_name, 1 " +
                "FROM material_recipe r, element e WHERE r.code=:code AND e.element_code='Ag' LIMIT 1")
              .setParameter("code", code).executeUpdate();
        });
        assertEquals(0, count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + code + "'"), "构造：该材质应 0 条配置");
        return code;
    }

    /** AC-19「自定义含量不回流材质库」的可观测判据：材质表与配置表零新增。 */
    private Snapshot snapshot() {
        return new Snapshot(
            count("SELECT count(*) FROM material_recipe"),
            count("SELECT count(*) FROM material_recipe_config"),
            count("SELECT count(*) FROM material_recipe_element"));
    }

    private class Snapshot {
        final long recipes, configs, elements;
        Snapshot(long r, long c, long e) { recipes = r; configs = c; elements = e; }
        void assertUnchanged(String when) {
            System.out.printf("[不回流] %s: recipe=%d→%d config=%d→%d element=%d→%d%n", when,
                recipes, count("SELECT count(*) FROM material_recipe"),
                configs, count("SELECT count(*) FROM material_recipe_config"),
                elements, count("SELECT count(*) FROM material_recipe_element"));
            assertEquals(recipes, count("SELECT count(*) FROM material_recipe"),
                "AC-19：" + when + " material_recipe 不得新增");
            assertEquals(configs, count("SELECT count(*) FROM material_recipe_config"),
                "AC-19：" + when + " material_recipe_config 不得新增（自定义不回流）");
            assertEquals(elements, count("SELECT count(*) FROM material_recipe_element"),
                "AC-19：" + when + " material_recipe_element 不得新增");
        }
    }
}
