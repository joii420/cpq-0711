package com.cpq.priceadjust.ac260920;

import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-27（后端部分）与 AC-28。夹具直接插审核行（这两条验的是列表的计数与排序，行的来路不是被测对象）；
 * 所有查询都按本轮自建客户号过滤（共库片禁全局计数）。
 */
@QuarkusTest
@TestProfile(T920Profiles.Base.class)
class Ac260920ListTest {

    @Inject
    EntityManager em;

    /**
     * AC-27 ①：「已作废 + QUEUED」的行不计入 notComputedTotal。
     * 夹具：旧版本 V1（已作废）下 3 行 VOIDED+QUEUED；当前版本 V2 下 2 行 PENDING+QUEUED、1 行 PENDING+COMPUTING、1 行 PENDING+READY。
     * 阳性对照：同一请求里 PENDING 的 3 行未计算确实被计入（证明计数不是恒 0）。
     */
    @Test
    void ac27_voidedQueuedNotCounted() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        try {
            fx.createCustomerAndStrategy();
            UUID v1 = fx.insertVersion("V1", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            UUID v2 = fx.insertVersion("V2", "PENDING", T920Fixture.P_TARGET, 60);
            for (int i = 1; i <= 3; i++) fx.insertReview(v1, fx.material("VQ", i), "VOIDED", "QUEUED", null, null);
            fx.insertReview(v2, fx.material("PQ", 1), "PENDING", "QUEUED", null, null);
            fx.insertReview(v2, fx.material("PQ", 2), "PENDING", "QUEUED", null, null);
            fx.insertReview(v2, fx.material("PC", 1), "PENDING", "COMPUTING", null, null);
            fx.insertReview(v2, fx.material("PR", 1), "PENDING", "READY", null, null);
            T920Api api = T920Api.anonymous();

            JsonNode all = T920Api.unwrap(api.list(T920Api.q("customerNo", fx.customerNo, "page", 1, "size", 50)));
            JsonNode voided = T920Api.unwrap(api.list(T920Api.q("customerNo", fx.customerNo, "status", "VOIDED", "page", 1, "size", 50)));
            T920Evidence.log("AC-27", "全部: totalElements=" + all.path("totalElements") + " notComputedTotal=" + all.path("notComputedTotal")
                + "；status=VOIDED: totalElements=" + voided.path("totalElements") + " notComputedTotal=" + voided.path("notComputedTotal")
                + "；VOIDED 行=" + voided.path("content"));
            assertEquals(7, all.path("totalElements").asInt(-1), "前提：本客户 7 行都能列出");
            assertTrue(all.has("notComputedTotal"), "api.md §1.1：返回体应有 notComputedTotal");
            assertEquals(3, all.path("notComputedTotal").asInt(-1),
                "AC-27①：只数待处理行里的 QUEUED/COMPUTING（3），不计已作废的 3 行 QUEUED");
            assertEquals(3, voided.path("totalElements").asInt(-1), "前提：按「已作废」筛选能取到这 3 行（非空样本）");
            assertEquals(0, voided.path("notComputedTotal").asInt(-1), "AC-27①：筛选已作废时 notComputedTotal = 0");
            for (JsonNode r : voided.path("content")) {
                assertEquals("VOIDED", r.path("reviewStatus").asText());
                assertEquals("QUEUED", r.path("budgetStatus").asText(), "夹具：这些行 budgetStatus 仍是 QUEUED（组件测试据此验不渲染）");
            }
        } finally {
            fx.cleanup();
        }
    }

    /**
     * AC-28：100 行 created_at 完全相同，每页 20 取第 1~5 页，重复两次 ⇒ 五页合起来恰好 100 个不同的行、两次顺序一致。
     * 🔒 还原实验（S1-5）两层：
     * <ol>
     *   <li>本用例内置的<b>夹具鉴别力预检</b>：同一批行用旧排序（只按 created_at 倒序 + LIMIT/OFFSET）直接查库翻页，
     *       记录是否出现重复/遗漏/两次不一致。预检结果写进证据；它不是对旧实现的证明，只说明夹具能不能把旧排序打红；</li>
     *   <li>正式还原实验在执行阶段做：把本类原样放到 master 代码（旧排序）上跑，必须变红（见回报）。</li>
     * </ol>
     */
    @Test
    void ac28_sameCreatedAtPagingStable() {
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        try {
            fx.createCustomerAndStrategy();
            UUID v = fx.insertVersion("SAME", "SUPERSEDED", T920Fixture.P_PREV, 3_600);
            List<Integer> order = new ArrayList<>();
            for (int i = 1; i <= 100; i++) order.add(i);
            Collections.shuffle(order);                       // 物理插入次序打乱，不与料号序 / id 序一致
            Set<String> inserted = new LinkedHashSet<>();
            for (int i : order) {
                inserted.add(fx.insertReview(v, fx.material("S", i), "VOIDED", "READY", null, "2026-09-20 12:00:00+08").toString());
            }
            assertEquals(1, db.count("SELECT count(DISTINCT created_at) FROM material_price_review WHERE customer_no = :c",
                "c", fx.customerNo), "前提：100 行 created_at 完全相同");

            List<String> a = pages(fx.customerNo);
            List<String> b = pages(fx.customerNo);
            List<String> oldA = oldSortPages(db, fx.customerNo);
            List<String> oldB = oldSortPages(db, fx.customerNo);
            T920Evidence.log("AC-28", "接口两次：distinct=" + new LinkedHashSet<>(a).size() + "/" + a.size()
                + " 一致=" + a.equals(b) + "；旧排序预检两次：distinct=" + new LinkedHashSet<>(oldA).size() + "/" + oldA.size()
                + " 覆盖全集=" + new LinkedHashSet<>(oldA).equals(inserted) + " 一致=" + oldA.equals(oldB)
                + " ⇒ 夹具对旧排序" + (new LinkedHashSet<>(oldA).size() < 100 || !oldA.equals(oldB) ? "有鉴别力" : "无鉴别力（报主线改夹具）"));
            assertEquals(100, a.size(), "五页共 100 行（每页 20，页码 1 起：api.md §1.1 page=1）");
            assertEquals(inserted, new LinkedHashSet<>(a), "AC-28：五页合起来恰好是这 100 个不同的行，无重复无遗漏");
            assertEquals(a, b, "AC-28：两次顺序完全一致");
        } finally {
            fx.cleanup();
        }
    }

    private static List<String> pages(String customerNo) {
        List<String> out = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            JsonNode j = T920Api.unwrap(T920Api.anonymous().list(T920Api.q("customerNo", customerNo, "status", "VOIDED", "page", p, "size", 20)));
            assertEquals(20, j.path("content").size(), "第 " + p + " 页应有 20 行（totalElements=" + j.path("totalElements") + "）");
            for (JsonNode r : j.path("content")) out.add(r.path("reviewId").asText());
        }
        return out;
    }

    private static List<String> oldSortPages(Rp0918aDb db, String customerNo) {
        List<String> out = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            for (Object o : db.column("SELECT id::text FROM material_price_review WHERE customer_no = :c "
                + "ORDER BY created_at DESC LIMIT 20 OFFSET " + (p * 20), "c", customerNo)) out.add((String) o);
        }
        return out;
    }
}
