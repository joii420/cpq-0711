package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-11 ①（后端）与 AC-25 ①（后端）：通过 / 驳回的预算守门，同一异常类、同一信封（api.md §5：409 +
 * {@code data.code=REVIEW_BUDGET_NOT_READY} + {@code data.invalidItems}），整批不处理。
 *
 * <p>夹具：自建客户一张 5 行活单，走真实生成入口全部算成 READY；然后在<b>后台已收敛</b>后把 r2/r3/r4 分别改成
 * FAILED / QUEUED / COMPUTING（审核行状态是被测的输入条件，这三种状态靠自然路径无法稳定停住；此时没有循环在跑，不会被捡走）。
 * r1、r5 保持 READY。
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
class Ac260920ApproveRejectGuardTest {

    static final String CODE = "REVIEW_BUDGET_NOT_READY";
    static final String INJECTED_ERR = "T260920 注入的失败原因";

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    @Test
    void ac11_ac25_budgetGuardOnApproveImpactReject() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        T920Api api = T920Api.anonymous();
        try {
            fx.createCustomerAndStrategy();
            Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
            Quote q = fx.createQuote("G", 5);
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            for (String m : q.materials()) fx.setPointer(m, vPrev);
            fx.addScope(q.materials());
            fx.setElementPriceTarget(T920Fixture.P_TARGET);
            UUID v = api.generateVersion(fx.customerNo);
            assertEquals(0, fx.awaitVersionSettled(v, 180_000), "前提：全部算完");
            fx.awaitQuiet(3_000, 60_000);
            Map<String, UUID> ids = new LinkedHashMap<>();
            for (String m : q.materials()) {
                Object[] r = fx.review(v, m);
                assertTrue(r != null && "READY".equals(r[1]), "前提：" + m + " READY，实际 " + java.util.Arrays.toString(r));
                ids.put(m, (UUID) r[4]);
            }
            UUID r1 = ids.get(q.material(1)), r2 = ids.get(q.material(2)), r3 = ids.get(q.material(3)),
                r4 = ids.get(q.material(4)), r5 = ids.get(q.material(5));
            db.exec("UPDATE material_price_review SET budget_status = 'FAILED', budget_error = :e WHERE id = :id",
                "e", INJECTED_ERR, "id", r2);
            db.exec("UPDATE material_price_review SET budget_status = 'QUEUED' WHERE id = :id", "id", r3);
            db.exec("UPDATE material_price_review SET budget_status = 'COMPUTING' WHERE id = :id", "id", r4);
            String pointersBefore = pointers(db, fx);
            Map<UUID, String> label = Map.of(r2, "FAILED", r3, "QUEUED", r4, "COMPUTING");

            // ── AC-11 ①：approve 含非 READY 行 ⇒ 409 + invalidItems，整批不处理（三种状态各一次） ──
            for (UUID bad : List.of(r2, r3, r4)) {
                long jobsBefore = jobs(db, fx);
                Response r = api.approve(List.of(r1, bad));
                assertGuard(r, bad, r1, "approve[" + label.get(bad) + "]");
                assertEquals(jobsBefore, jobs(db, fx), "approve[" + label.get(bad) + "]：整批不处理 —— 不产生更新任务");
                assertEquals("PENDING", fx.review(v, q.material(1))[0], "approve：同批的 READY 行 r1 仍待处理");
                assertEquals(pointersBefore, pointers(db, fx), "approve：指针不动");
            }

            // ── AC-11 ①：impact 只传 READY 行 ⇒ materials[] 恰好是这些行 ──
            Response imp = api.impact(List.of(r1, r5));
            assertEquals(200, imp.statusCode(), "impact: " + imp.asString());
            JsonNode ij = T920Api.unwrap(T920Api.json(imp));
            Set<String> got = new TreeSet<>();
            for (JsonNode m : ij.path("materials")) got.add(m.path("materialNo").asText());
            T920Evidence.log("AC-11", "impact(r1,r5) = " + ij);
            assertEquals(new TreeSet<>(Set.of(q.material(1), q.material(5))), got, "AC-11：impact.materials[] 恰好是传入的 READY 行");
            for (String f : List.of("materialCount", "versionPaths", "quotationCount", "breachedMaterials", "excludedQuotationCount")) {
                assertTrue(ij.has(f), "api.md §3：既有字段一个不删 —— 缺 " + f);
            }

            // ── AC-25 ①：reject 含 QUEUED / COMPUTING / FAILED 任一 ⇒ 409 同一信封，整批不处理 ──
            for (UUID bad : List.of(r2, r3, r4)) {
                Response r = api.reject(List.of(r1, bad));
                JsonNode item = assertGuard(r, bad, r1, "reject[" + label.get(bad) + "]");
                String reason = item.path("reason").asText("");
                if (bad.equals(r2)) {
                    assertTrue(reason.contains("计算失败") && reason.contains(INJECTED_ERR),
                        "api.md §5：FAILED 行 reason 应为「计算失败(<budget_error>)」，实际 " + reason);
                } else {
                    assertTrue(reason.contains("预算未算完") && reason.contains(label.get(bad)),
                        "api.md §5：未算完行 reason 应为「预算未算完(<状态>)」，实际 " + reason);
                }
                assertEquals("PENDING", fx.review(v, q.material(1))[0], "reject：同批 READY 行 r1 未被驳回（整批不处理）");
                assertEquals("PENDING", db.text("SELECT status FROM material_price_review WHERE id = :id", "id", bad));
            }

            // 阳性对照：只含 READY 行的驳回照常成功 ⇒ 上面的 409 是守门、不是驳回接口本身坏了
            Response ok = api.reject(List.of(r5));
            T920Evidence.log("AC-25", "阳性对照 reject(r5 READY) = " + ok.statusCode() + " " + ok.asString());
            assertTrue(ok.statusCode() >= 200 && ok.statusCode() < 300, "阳性对照：READY 行可驳回: " + ok.asString());
            assertEquals("REJECTED", db.text("SELECT status FROM material_price_review WHERE id = :id", "id", r5));
        } finally {
            fx.awaitQuiet(3_000, 60_000);
            fx.cleanup();
        }
    }

    private static JsonNode assertGuard(Response r, UUID bad, UUID good, String what) {
        T920Evidence.log(what.startsWith("approve") ? "AC-11" : "AC-25", what + " → " + r.statusCode() + " " + r.asString());
        assertEquals(409, r.statusCode(), what + "：应 409: " + r.asString());
        assertEquals(CODE, T920Api.errorCode(r), what + "：信封 data.code（api.md 头部：409 一律走 ReviewNotReadyException 信封）");
        JsonNode items = T920Api.invalidItems(r);
        assertTrue(items.isArray() && items.size() >= 1, what + "：data.invalidItems 非空");
        JsonNode hit = null;
        for (JsonNode it : items) {
            if (bad.toString().equals(it.path("reviewId").asText())) hit = it;
            assertTrue(!good.toString().equals(it.path("reviewId").asText()), what + "：READY 行不应列入 invalidItems");
        }
        assertTrue(hit != null, what + "：invalidItems 应含非 READY 行 " + bad + "，实际 " + items);
        return hit;
    }

    private static long jobs(Rp0918aDb db, T920Fixture fx) {
        return db.count("SELECT count(*) FROM material_price_update_job WHERE customer_no = :c", "c", fx.customerNo);
    }

    private static String pointers(Rp0918aDb db, T920Fixture fx) {
        return db.text("SELECT coalesce(string_agg(material_no || '=' || version_id, ',' ORDER BY material_no), '') "
            + "FROM material_price_version_ref WHERE customer_no = :c", "c", fx.customerNo);
    }
}
