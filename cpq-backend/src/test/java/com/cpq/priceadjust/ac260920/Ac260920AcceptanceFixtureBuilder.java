package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主线亲验夹具（第 3 节 §2）：<b>不是测试用例</b>，只造数、<b>不清理</b>（留给主线亲验，清理 SQL 写进证据，由主线决定何时执行）。
 * 以 {@code -Dt260920.buildAcceptanceFixture=true} 门控；平时不会运行。
 *
 * <pre>
 *  a  3 行 QUEUED                          依据单 QA（活单）
 *  b  1 行 QUEUED，依据单 QB 已 CANCELLED   点计算 ⇒ FAILED「判断依据单已不在活单范围内」
 *  c  1 行 FAILED，budget_error 同上       依据单 QC 已 CANCELLED
 *  d  2 行 READY 有金额（均标红）            依据单 QD（活单）
 *  e  d1 同时挂在 QE（SENT，非活单，建于 QD 之前 ⇒ 依据单仍是 QD）
 *  f  已作废版本 OLD 下 2 行 VOIDED + QUEUED
 * </pre>
 * a/b/c/d 由真实生成入口进池并试算（不注入延时），再把 a/b/c 的状态改回所需形态（删掉它们自己的比对列）。
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
@EnabledIfSystemProperty(named = "t260920.buildAcceptanceFixture", matches = "true")
class Ac260920AcceptanceFixtureBuilder {

    static final String REASON = "判断依据单已不在活单范围内";
    static final String PM_PASSWORD = "T260920@Accept1";

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    @Test
    void buildAndKeep() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        fx.createCustomerAndStrategy();
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);

        Quote qe = fx.createQuote("E", 1);                 // 非活单，先建（依据单按创建时间取最新活单）
        Quote qa = fx.createQuote("A", 3);
        Quote qb = fx.createQuote("B", 1);
        Quote qc = fx.createQuote("C", 1);
        Quote qd = fx.createQuote("D", 2);
        String d1 = qd.material(1);
        db.exec("UPDATE quotation_line_item SET product_part_no_snapshot = :m WHERE id = :l", "m", d1, "l", qe.line(qe.material(1)));
        db.exec("UPDATE quotation SET status = 'SENT' WHERE id = :q", "q", qe.id());

        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
        List<String> scope = new ArrayList<>();
        for (Quote q : List.of(qa, qb, qc, qd)) scope.addAll(q.materials());
        for (String m : scope) fx.setPointer(m, vPrev);
        fx.addScope(scope);
        fx.setElementPriceTarget(T920Fixture.P_TARGET);

        UUID v = T920Api.anonymous().generateVersion(fx.customerNo);
        assertEquals(0, fx.awaitVersionSettled(v, 240_000), "全部先真实算完");
        fx.awaitQuiet(3_000, 60_000);

        Map<String, List<String>> kind = new LinkedHashMap<>();
        kind.put("a", qa.materials());
        kind.put("b", qb.materials());
        kind.put("c", qc.materials());
        kind.put("d", qd.materials());
        for (String m : scope) assertEquals("READY", fx.review(v, m)[1], "前提：" + m + " 首轮 READY");

        List<UUID> reset = new ArrayList<>();
        for (String m : kind.get("a")) reset.add(fx.reviewId(v, m));
        UUID rb = fx.reviewId(v, qb.material(1));
        UUID rc = fx.reviewId(v, qc.material(1));
        reset.add(rb);
        reset.add(rc);
        db.exec("DELETE FROM material_price_review_column WHERE review_id IN (:ids) AND review_id IN "
            + "(SELECT id FROM material_price_review WHERE customer_no = :c)", "ids", reset, "c", fx.customerNo);
        db.exec("UPDATE material_price_review SET budget_status = 'QUEUED', budget_error = NULL, column_count = 0, breached_count = 0, "
            + "amber_count = 0, missing_count = 0, stale_count = 0, updated_at = now() WHERE id IN (:ids) AND customer_no = :c",
            "ids", reset.subList(0, 4), "c", fx.customerNo);
        db.exec("UPDATE material_price_review SET budget_status = 'FAILED', budget_error = :e, column_count = 0, breached_count = 0, "
            + "amber_count = 0, missing_count = 0, stale_count = 0, updated_at = now() WHERE id = :id AND customer_no = :c",
            "e", REASON, "id", rc, "c", fx.customerNo);
        fx.cancelQuote(qb.id());
        fx.cancelQuote(qc.id());

        UUID vOld = fx.insertVersion("OLD", "SUPERSEDED", T920Fixture.P_PREV, 86_400);
        List<UUID> voided = List.of(
            fx.insertReview(vOld, fx.material("F", 1), "VOIDED", "QUEUED", null, null),
            fx.insertReview(vOld, fx.material("F", 2), "VOIDED", "QUEUED", null, null));

        UUID pmId = UUID.randomUUID();
        String pm = "t260920-accept-" + fx.run;
        db.exec("INSERT INTO \"user\"(id, username, full_name, email, password_hash, role, status, is_first_login, created_at, updated_at) "
                + "VALUES (:id, :u, :u, :e, :h, 'PRICING_MANAGER', 'ACTIVE', false, now(), now())",
            "id", pmId, "u", pm, "e", pm + "@t260920.local", "h", BCrypt.hashpw(PM_PASSWORD, BCrypt.gensalt(12)));

        // 自检
        StringBuilder rows = new StringBuilder();
        for (Object[] r : db.rows("SELECT r.id, r.material_no, r.status, r.budget_status, coalesce(r.budget_error, ''), r.breached_count, "
            + "q.quotation_number, q.status FROM material_price_review r LEFT JOIN quotation q ON q.id = r.basis_quotation_id "
            + "WHERE r.customer_no = :c ORDER BY r.status, r.material_no", "c", fx.customerNo)) {
            rows.append("\n  ").append(java.util.Arrays.toString(r));
        }
        long dBreached = db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v AND material_no IN (:m) "
            + "AND budget_status = 'READY' AND breached_count > 0", "v", v, "m", kind.get("d"));
        assertTrue(dBreached >= 1, "d 至少 1 行标红");

        List<UUID> qids = fx.quotationIds();
        String cleanup = String.join("\n",
            "-- T260920 亲验夹具清理（按私有 id 精确限定；只写不跑，主线验完后执行）",
            "BEGIN;",
            "DELETE FROM material_price_update_job_item WHERE quotation_id IN (" + in(qids) + ") OR job_id IN (SELECT id FROM material_price_update_job WHERE customer_no = '" + fx.customerNo + "');",
            "DELETE FROM material_price_update_job WHERE customer_no = '" + fx.customerNo + "';",
            "DELETE FROM material_price_review_column WHERE review_id IN (SELECT id FROM material_price_review WHERE customer_no = '" + fx.customerNo + "');",
            "DELETE FROM material_price_review WHERE customer_no = '" + fx.customerNo + "';",
            "DELETE FROM material_price_version_ref WHERE customer_no = '" + fx.customerNo + "';",
            "DELETE FROM quotation_price_revision WHERE quotation_id IN (" + in(qids) + ");",
            "DELETE FROM costing_order_version_override WHERE costing_order_id IN (SELECT id FROM costing_order WHERE quotation_id IN (" + in(qids) + "));",
            "DELETE FROM costing_order WHERE quotation_id IN (" + in(qids) + ");",
            "DELETE FROM quotation WHERE id IN (" + in(qids) + ");",
            "DELETE FROM element_price_version WHERE customer_no = '" + fx.customerNo + "';",
            "DELETE FROM comparison_column_config WHERE customer_no = '" + fx.customerNo + "';",
            "DELETE FROM customer_price_adjust_strategy WHERE id = '" + fx.strategyId + "';",
            "DELETE FROM element_price_strategy WHERE customer_no = '" + fx.customerNo + "';",
            "DELETE FROM template WHERE id = '" + fx.templateId + "';",
            "DELETE FROM component WHERE code IN ('T260920-ELEM-" + fx.run + "', 'T260920-SUB-" + fx.run + "');",
            "DELETE FROM customer WHERE id = '" + fx.customerId + "';",
            "DELETE FROM \"user\" WHERE id = '" + pmId + "' AND username = '" + pm + "';",
            "-- 残留自检（应全为 0）：",
            "SELECT (SELECT count(*) FROM material_price_review WHERE customer_no = '" + fx.customerNo + "') + (SELECT count(*) FROM quotation WHERE quotation_number LIKE 'T260920-" + fx.run + "-%') + (SELECT count(*) FROM customer WHERE code = '" + fx.customerNo + "') + (SELECT count(*) FROM \"user\" WHERE username = '" + pm + "') AS residue;",
            "COMMIT;");
        T920Evidence.log("ACCEPT-FIXTURE", "客户=" + fx.customerNo + " 搜索词=T260920-" + fx.run + " 目标版本=" + v + " 已作废版本=" + vOld
            + "\na=" + ids(fx, v, kind.get("a")) + " 依据单=" + qa.number()
            + "\nb=" + ids(fx, v, kind.get("b")) + " 依据单=" + qb.number() + "（CANCELLED）"
            + "\nc=" + ids(fx, v, kind.get("c")) + " 依据单=" + qc.number() + "（CANCELLED）"
            + "\nd=" + ids(fx, v, kind.get("d")) + " 依据单=" + qd.number() + "；标红行=" + dBreached
            + "\ne=" + d1 + " 另挂在 " + qe.number() + "（SENT）"
            + "\nf=" + voided + "（" + fx.material("F", 1) + "," + fx.material("F", 2) + "）"
            + "\nPM 账号=" + pm + " / " + PM_PASSWORD + "（PRICING_MANAGER）"
            + "\n全部审核行：" + rows
            + "\n清理 SQL：\n" + cleanup);
    }

    private static String ids(T920Fixture fx, UUID v, List<String> mats) {
        StringBuilder sb = new StringBuilder();
        for (String m : mats) sb.append(m).append("→").append(fx.reviewId(v, m)).append(' ');
        return sb.toString().trim();
    }

    private static String in(List<UUID> ids) {
        StringBuilder sb = new StringBuilder();
        for (UUID u : ids) sb.append(sb.length() == 0 ? "" : ", ").append('\'').append(u).append('\'');
        return sb.toString();
    }
}
