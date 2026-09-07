package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧入口下线与权限：<b>T1.9（AC-14）· T1.14（AC-19）</b>。
 */
@QuarkusTest
class QuoteLegacyAndPermissionTest extends QuoteImportAcTestBase {

    // ══════════════════ T1.9 · AC-14 ══════════════════

    /**
     * AC-14：直接 POST 旧端点 → 返 <b>410</b>，不建单，{@code quotation} 行数不变。
     *
     * <p>🚩 api.md §4 只停<b>报价侧两个</b>。{@code GET /basic-data-import/v6/{recordId}}
     * <b>必须保留</b>（报价与核价共用的轮询端点，且【导入历史】页要读历史记录）——
     * 两个并发任务都以为对方会留着它，那是最容易两边都删掉的形态，故本用例一并守住。
     */
    @Test
    void t1_9_旧报价导入端点返410且不建单() {
        String s = adminSession();
        long qBefore = countRows("quotation", null);

        Response r1 = QuoteImportApi.legacyQuoteImport(
                s, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN);
        assertEquals(410, r1.statusCode(),
                "AC-14：POST /basic-data-import/v6/quote 应返 410 Gone，实际 "
                        + r1.statusCode() + "：" + r1.asString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("importRecordId", "00000000-0000-0000-0000-000000000000");
        Response r2 = QuoteImportApi.legacyCreateQuotation(s, body);
        assertEquals(410, r2.statusCode(),
                "AC-14：POST /basic-data-import/v6/quote/create-quotation 应返 410，实际 "
                        + r2.statusCode() + "：" + r2.asString());

        assertEquals(qBefore, countRows("quotation", null), "AC-14：410 之后 quotation 行数不许变");
    }

    /**
     * api.md §4 的守卫：共用轮询端点 {@code GET /basic-data-import/v6/{recordId}}
     * <b>不许一起被停</b>。用一个不存在的 id 调用，期望 404（记录不存在）而<b>不是 410</b>；
     * 410 就说明端点被误停了。
     */
    @Test
    void t1_9b_共用轮询端点必须保留() {
        String s = adminSession();
        Response r = io.restassured.RestAssured.given().cookie("CPQ_SESSION", s)
                .when().get("/api/cpq/basic-data-import/{id}", "00000000-0000-0000-0000-000000000000");
        assertTrue(r.statusCode() != 410,
                "api.md §4：GET /basic-data-import/{recordId} 是报价与核价共用的轮询端点，"
                        + "本任务只停报价侧两个 POST，🚫 不许把它一起停掉（【导入历史】页要读它）。"
                        + "实际返回 410。");
    }

    // ══════════════════ T1.14 · AC-19 ══════════════════

    /**
     * AC-19①③：以无权限角色（{@code PRICING_MANAGER}）调三个新端点 → <b>403</b>；
     * {@code quotation} 行数不变。
     *
     * <p>🚩 AC-19② 的「按钮禁用但可见」<b>在 UI 上不可达</b>：
     * {@code router/index.tsx:70} 的 {@code QUOTATION_MGMT_ROLES} 与新导入白名单完全相同 ⇒
     * 能打开报价单列表页的人必然有导入权限，按钮永远不会是禁用态；
     * {@code PRICING_MANAGER} 打开 {@code /quotations} 直接是「无权访问」页。
     * ⇒ 该子项标「路由层不可达，不验」，<b>不为凑证据去造一个产品里不存在的状态</b>。
     */
    @Test
    void t1_14_无权限角色调三个新端点全403() {
        String pm = pricingManagerSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        long qBefore = countRows("quotation", null);

        Response r1 = QuoteImportApi.quotationImport(
                pm, cid, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN);
        assertEquals(403, r1.statusCode(),
                "AC-19①：PRICING_MANAGER 调 quotation-import 应 403，实际 "
                        + r1.statusCode() + "：" + r1.asString());

        Response r2 = QuoteImportApi.pollImport(pm, "00000000-0000-0000-0000-000000000000");
        assertEquals(403, r2.statusCode(),
                "AC-19①：PRICING_MANAGER 调轮询端点应 403（而不是 404）——"
                        + " 先鉴权后查存在性，否则会泄漏记录是否存在。实际 " + r2.statusCode());

        Response r3 = QuoteImportApi.createQuotation(pm, QuoteImportApi.createBody(
                "00000000-0000-0000-0000-000000000000", cid, "T260907T-AC19-不该建出来", null,
                "00000000-0000-0000-0000-000000000000"));
        assertEquals(403, r3.statusCode(),
                "AC-19①：PRICING_MANAGER 调 create-quotation 应 403，实际 "
                        + r3.statusCode() + "：" + r3.asString());

        assertEquals(qBefore, countRows("quotation", null),
                "AC-19③：403 之后 quotation 行数不许变");
    }
}
