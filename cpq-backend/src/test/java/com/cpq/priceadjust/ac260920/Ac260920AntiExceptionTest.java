package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260918.UpgradeInterceptor;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-19：料号 X 无依据单、有驳回史（反例外）。生成版本 ⇒ X 进池即 READY + 0 列，<b>从不进入</b>试算队列；
 * SPECIFIED 模式小范围客户可正常生成、列表可搜到。
 * <p>观测「从不进入试算队列」：生成请求发出后以 20 ms 粒度持续采样 X 的 budget_status，采样集合必须 = {READY}；
 * 且 X 行 updated_at = created_at（建行后从未被再写）。阳性对照：同版本的普通料号确实被试算过（upgrade dryRun 次数 ≥1、
 * 且其状态轨迹里出现过非 READY 或最终 READY），证明后台循环在跑、观测手段有效。
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
class Ac260920AntiExceptionTest {

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    @Test
    void ac19_antiExceptionReadyAtEnqueue_neverQueued() throws Exception {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        UpgradeInterceptor upgrades = UpgradeInterceptor.install();
        try {
            fx.createCustomerAndStrategy();
            Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
            String x = fx.material("RJ", 1);
            fx.insertRejectHistory(x);
            Quote q = fx.createQuote("N", 3);
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            for (String m : q.materials()) fx.setPointer(m, vPrev);
            List<String> scope = new ArrayList<>(q.materials());
            scope.add(x);
            fx.addScope(scope);
            fx.setElementPriceTarget(T920Fixture.P_TARGET);

            long t0 = System.currentTimeMillis();
            CompletableFuture<UUID> gen = CompletableFuture.supplyAsync(() -> T920Api.anonymous().generateVersion(fx.customerNo));
            Set<String> xStates = new LinkedHashSet<>();
            Set<String> normalStates = new LinkedHashSet<>();
            UUID v = null;
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                if (v == null && gen.isDone()) v = gen.get();
                for (Object[] r : db.rows("SELECT r.material_no, r.budget_status FROM material_price_review r "
                    + "JOIN element_price_version ev ON ev.id = r.version_id WHERE r.customer_no = :c AND ev.status = 'PENDING'",
                    "c", fx.customerNo)) {
                    (x.equals(r[0]) ? xStates : normalStates).add((String) r[1]);
                }
                if (v != null && fx.notSettled(v) == 0 && db.count("SELECT count(*) FROM material_price_review WHERE version_id = :v",
                    "v", v) >= 1 + q.materials().size()) break;
                T920Fixture.sleep(20);
            }
            if (v == null) v = gen.get(60, TimeUnit.SECONDS);
            Object[] xr = fx.review(v, x);
            Object[] meta = db.rows("SELECT column_count, basis_quotation_id, created_at = updated_at, "
                + "(SELECT count(*) FROM material_price_review_column c WHERE c.review_id = r.id) FROM material_price_review r "
                + "WHERE version_id = :v AND material_no = :m", "v", v, "m", x).get(0);
            long dryNormal = upgrades.callsSince(t0).stream().filter(c -> Boolean.TRUE.equals(c.dryRun())).count();
            JsonNode listed = T920Api.anonymous().list(T920Api.q("customerNo", fx.customerNo, "keyword", x, "size", 20));
            boolean found = false;
            for (JsonNode row : listed.path("content")) if (x.equals(row.path("materialNo").asText())) found = true;
            T920Evidence.log("AC-19", "版本 " + v + "；X=" + x + " 采样到的状态=" + xStates + "；X 行=" + java.util.Arrays.toString(xr)
                + " column_count/basis/created=updated/比对列行数=" + java.util.Arrays.toString(meta)
                + "；普通料号采样状态=" + normalStates + "；本轮 dryRun 次数=" + dryNormal + "；列表按 X 搜索命中=" + found);

            assertNotNull(xr, "AC-19：X 应进池（有审核行）");
            assertEquals("PENDING", xr[0]);
            assertEquals(Set.of("READY"), xStates, "AC-19：X 从进池起就是 READY，采样中不得出现 QUEUED/COMPUTING/FAILED");
            assertEquals(0, ((Number) meta[0]).intValue(), "AC-19：column_count = 0");
            assertNull(meta[1], "反例外行没有依据单");
            assertEquals(Boolean.TRUE, meta[2], "AC-19：X 行建行后从未被再写（updated_at = created_at）⇒ 没有进过试算");
            assertEquals(0L, ((Number) meta[3]).longValue(), "AC-19：0 列");
            assertTrue(dryNormal >= q.materials().size(), "阳性对照：普通料号确实被后台试算（观测手段有效），dryRun=" + dryNormal);
            for (String m : q.materials()) assertEquals("READY", fx.review(v, m)[1], "阳性对照：普通料号算完");
            assertTrue(found, "AC-19：SPECIFIED 小范围客户生成后，列表按料号可搜到 X");
        } finally {
            upgrades.clearRules();
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }
}
