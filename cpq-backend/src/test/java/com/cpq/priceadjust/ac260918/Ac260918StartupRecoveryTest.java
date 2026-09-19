package com.cpq.priceadjust.ac260918;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Edge;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Quote;
import com.cpq.priceadjust.ac260918.Rp0918aSnapshots.Revision;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import com.cpq.priceadjust.service.PriceAdjustStartupRecovery;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_PREV;
import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * repair-260918 · S-BE · 启动收尾。
 * <ul>
 *   <li><b>T-BE-21 / AC-21</b>：批次执行中（≥1 已成功且其料号未进本期记录、≥1 执行中、≥1 等待）「服务重启」⇒
 *       调 {@code recoverJobs(只传本片批次 id)}：执行中/等待 → FAILED + EXECUTION_INTERRUPTED + 固定文案；
 *       已成功明细的料号补进本期记录；批次离开执行中且计数与明细一致。另放一个<b>不传入</b>的对照批次，必须纹丝不动。</li>
 *   <li><b>T-BE-25 / AC-25</b>：开关为 false 时 {@code runOnStartup()} 不动任何执行中批次、不启动续跑；
 *       阳性对照 = 同一现场上直接调 recoverJobs / resumeBudgets 确实会改变这些观测量。</li>
 * </ul>
 * 「现场」中的 SUCCESS / RUNNING / WAITING 状态与一天前的 updated_at 由 SQL 写在<b>本片自建明细</b>上（模拟重启前停止更新）。
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
class Ac260918StartupRecoveryTest {

    private static final String INTERRUPTED_MSG = "执行中断（服务重启），本行未更新，可重试";

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustBudgetService budget;
    @Inject
    PriceAdjustStartupRecovery recovery;
    @InjectMock
    SessionHelper sessionHelper;

    private Rp0918aDb db;
    private Rp0918aSnapshots snaps;
    private Rp0918aFixture fx;
    private Quote q;
    private UUID vPrev, vTarget;
    private UpgradeInterceptor upgrades;
    private RevisionWriterInterceptor writer;

    @BeforeEach
    void setUp() {
        db = new Rp0918aDb(em);
        snaps = new Rp0918aSnapshots(db);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        fx.createTemplate();
        q = fx.createQuote("R", 12, null, Edge.NONE, 8);
        fx.addScopeFromQuotes(List.of(q.id()));
        vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 7_200);
        vTarget = fx.insertVersion("TGT", "PENDING", P_TARGET, P_PREV,
            P_TARGET.subtract(P_PREV).divide(P_PREV, 12, RoundingMode.HALF_UP), 3_600);
        fx.setPointersFromQuotes(vPrev, List.of(q.id()));
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        upgrades = UpgradeInterceptor.install();
        writer = RevisionWriterInterceptor.install();
    }

    @AfterEach
    void tearDown() {
        if (upgrades != null) {
            Rp0918aEvidence.log("upgrade-paths", getClass().getSimpleName() + " 拦到的 upgrade 调用：" + upgrades.signatureSummary());
            upgrades.clearRules();
        }
        if (writer != null) writer.disarm();
        if (fx != null) {
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-21 · AC-21
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe21_ac21_recoverInterruptedJob() {
        String m1 = q.material(2);
        String m2 = q.material(5);
        String m3 = q.material(8);
        String m4 = q.material(11);
        Map<String, UUID> rv = fx.createReviewsViaBudget(budget, vTarget, List.of(m1));

        // 造「行已成功、本期记录没写到」：分组写快照注入失败
        writer.armFailure(q.id());
        UUID job = Rp0918aApi.approveOk(rv.values());
        Rp0918aApi.awaitJobTerminal(db, job, 120_000);
        writer.disarm();
        assertTrue(writer.injectedFailures() >= 1, "注入未生效");
        JsonNode i1 = Rp0918aApi.itemsByMaterial(job).get(m1).get(0);
        assertEquals("REVISION_WRITE_FAILED", i1.path("errorCode").asText(), "前提：行成功但写快照失败: " + i1);
        assertEquals(0, P_TARGET.compareTo(fx.lineElementPrice(q.line(m1))), "前提：m1 行已按本期价更新");
        Revision curPre = snaps.current(q.id(), vTarget);
        assertTrue(curPre == null || !curPre.upgradedMaterialNos().contains(m1), "前提：本期记录里没有 m1");

        // 现场：m1 SUCCESS、m2 RUNNING、m3 WAITING，全部在「本实例启动之前」就停止更新；批次 RUNNING
        UUID item1 = UUID.fromString(i1.path("itemId").asText());
        db.exec("UPDATE material_price_update_job_item SET status = 'SUCCESS', error_code = NULL, error_message = NULL, "
            + "created_at = now() - interval '1 day', updated_at = now() - interval '1 day' WHERE id = :id", "id", item1);
        UUID item2 = fx.insertJobItem(job, q.id(), m2, q.line(m2), "RUNNING", "1 day");
        UUID item3 = fx.insertJobItem(job, q.id(), m3, q.line(m3), "WAITING", "1 day");
        db.exec("UPDATE material_price_update_job SET status = 'RUNNING', finished_at = NULL, total_count = 3, "
            + "success_count = 1, failed_count = 0, conflict_count = 0, stale_count = 0, "
            + "triggered_at = now() - interval '1 day', created_at = now() - interval '1 day' WHERE id = :id", "id", job);
        // 对照批次：同样是中断现场，但不传给 recoverJobs
        UUID control = fx.insertJob(vTarget, "RUNNING", "1 day");
        fx.insertJobItem(control, q.id(), m4, q.line(m4), "WAITING", "1 day");
        db.exec("UPDATE material_price_update_job SET total_count = 1 WHERE id = :id", "id", control);
        String controlBefore = jobDigest(control);
        JsonNode jobBefore = Rp0918aApi.job(job);
        assertEquals("RUNNING", jobBefore.path("status").asText(), "前提：批次执行中");

        recovery.recoverJobs(List.of(job));
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline
            && "RUNNING".equals(db.text("SELECT status FROM material_price_update_job WHERE id = :id", "id", job))) {
            Rp0918aFixture.sleep(200);
        }
        Map<String, JsonNode> items = Rp0918aApi.itemsById(job);
        JsonNode jobAfter = Rp0918aApi.job(job);
        Revision cur = snaps.current(q.id(), vTarget);
        String controlAfter = jobDigest(control);
        Rp0918aEvidence.log("AC-21", "批次 前=" + jobBefore + "\n后=" + jobAfter + "\n明细=" + items.values()
            + "\n本期记录=" + (cur == null ? null : cur.revisionNo() + " " + cur.upgradedMaterialNos())
            + "\n对照批次指纹 前=" + controlBefore + " 后=" + controlAfter);

        for (UUID it : List.of(item2, item3)) {
            JsonNode n = items.get(it.toString());
            assertEquals("FAILED", n.path("status").asText(), "AC-21：执行中/等待的明细应变「失败」: " + n);
            assertEquals("EXECUTION_INTERRUPTED", n.path("errorCode").asText(), "AC-21：错误码: " + n);
            assertEquals(INTERRUPTED_MSG, n.path("errorMessage").asText(), "AC-21：错误信息: " + n);
        }
        assertEquals("SUCCESS", items.get(item1.toString()).path("status").asText(), "已成功明细保持成功");
        assertNotNull(cur, "AC-21：已成功明细所在单应补写本期版本记录");
        assertTrue(cur.upgradedMaterialNos().contains(m1), "AC-21：本期记录「已升版料号」应包含已成功的 " + m1);
        assertFalse(cur.upgradedMaterialNos().contains(m2) || cur.upgradedMaterialNos().contains(m3),
            "中断的明细（未更新）不得记为已升版: " + cur.upgradedMaterialNos());
        assertEquals(Rp0918aSnapshots.parse(fx.lineQuoteCardText(q.line(m1))), cur.quoteCardValues().get(q.line(m1).toString()),
            "补写的本期快照里 m1 行应为升版后的卡片值");
        assertNotEquals("RUNNING", jobAfter.path("status").asText(), "AC-21：批次状态应离开「执行中」");
        assertEquals(3, jobAfter.path("total").asInt());
        assertEquals(1, jobAfter.path("success").asInt(), "AC-21：表头计数与明细一致（成功 1）");
        assertEquals(2, jobAfter.path("failed").asInt(), "AC-21：表头计数与明细一致（失败 2）");
        assertEquals(0, jobAfter.path("running").asInt(-1), "AC-21：执行中 0");
        assertEquals(0, P_PREV.compareTo(fx.lineElementPrice(q.line(m2))), "中断明细所在行未被更新");
        assertEquals(controlBefore, controlAfter, "未传入 recoverJobs 的对照批次必须纹丝不动（只处理传入的 id）");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-25 · AC-25
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void tBe25_ac25_switchOff_runOnStartupTouchesNothing() {
        if (!Boolean.getBoolean("rp0918a.allowRunOnStartup")) {
            fail("T-BE-25 未执行：runOnStartup() 没有 id 参数，若实现未守住开关，会处理 cpq_db_test 里所有人的 RUNNING 批次"
                + "与待处理版本。需主线批准后以 -Drp0918a.allowRunOnStartup=true 运行（或改在一次性库上跑）。");
        }
        Optional<Boolean> sw = ConfigProvider.getConfig()
            .getOptionalValue("cpq.price-adjust.startup-recovery.enabled", Boolean.class);
        assertEquals(Optional.of(false), sw, "前提：本 profile 下开关必须为 false");

        String m1 = q.material(2);
        String m2 = q.material(5);
        UUID job = fx.insertJob(vTarget, "RUNNING", "1 day");
        fx.insertJobItem(job, q.id(), m1, q.line(m1), "RUNNING", "1 day");
        fx.insertJobItem(job, q.id(), m2, q.line(m2), "WAITING", "1 day");
        db.exec("UPDATE material_price_update_job SET total_count = 2 WHERE id = :id", "id", job);
        long unprocessed = db.count("SELECT count(*) FROM customer_price_adjust_material cm WHERE cm.strategy_id = :s "
            + "AND NOT EXISTS (SELECT 1 FROM material_price_review r WHERE r.version_id = :v AND r.material_no = cm.material_no) "
            + "AND NOT EXISTS (SELECT 1 FROM material_price_version_ref p WHERE p.customer_no = :c AND p.material_no = cm.material_no "
            + "AND p.version_id = :v)", "s", fx.strategyId, "v", vTarget, "c", fx.customerNo);
        assertEquals(12, unprocessed, "前提：待处理版本有 12 个未处理料号（续跑若被启动一定可见）");

        Map<String, String> before = observe(job);
        recovery.runOnStartup();
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            assertEquals(before, observe(job), "AC-25：开关关闭时启动收尾不得改动执行中批次 / 审核行 / 指针");
            Rp0918aFixture.sleep(500);
        }
        Map<String, String> afterOff = observe(job);
        Rp0918aEvidence.log("AC-25", "开关=" + sw + "；runOnStartup 前=" + before + "\n10 秒后=" + afterOff);

        // 阳性对照：同一现场直接调收尾 / 续跑，观测量确实会变
        recovery.recoverJobs(List.of(job));
        recovery.resumeBudgets(List.of(vTarget));
        long dl = System.currentTimeMillis() + 120_000;
        Map<String, String> ctrl = observe(job);
        while (System.currentTimeMillis() < dl
            && (ctrl.get("job").equals(before.get("job")) || ctrl.get("reviews").equals(before.get("reviews")))) {
            Rp0918aFixture.sleep(500);
            ctrl = observe(job);
        }
        Rp0918aEvidence.log("AC-25", "阳性对照（直接调 recoverJobs + resumeBudgets）后=" + ctrl);
        assertNotEquals(before.get("job"), ctrl.get("job"), "阳性对照：直接收尾应改变批次/明细 —— 否则上面的「不变」无证明力");
        assertNotEquals(before.get("reviews"), ctrl.get("reviews"), "阳性对照：直接续跑应产生审核行/推进指针");
    }

    private Map<String, String> observe(UUID job) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("job", jobDigest(job));
        m.put("reviews", db.text("SELECT count(*) || ':' || coalesce(md5(string_agg(row_to_json(r)::text, ',' ORDER BY r.id)), '') "
            + "FROM material_price_review r WHERE r.version_id = :v", "v", vTarget) + "|"
            + db.text("SELECT count(*) || ':' || md5(string_agg(material_no || '=' || version_id || '@' || updated_at, ',' "
            + "ORDER BY material_no)) FROM material_price_version_ref WHERE customer_no = :c", "c", fx.customerNo));
        return m;
    }

    private String jobDigest(UUID job) {
        return db.text("SELECT md5((SELECT row_to_json(j)::text FROM material_price_update_job j WHERE j.id = :id) || '|' || "
            + "coalesce((SELECT string_agg(row_to_json(i)::text, ',' ORDER BY i.id) FROM material_price_update_job_item i "
            + "WHERE i.job_id = :id), ''))", "id", job);
    }
}
