package com.cpq.priceadjust.ac260920;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aDb;
import com.cpq.priceadjust.ac260920.T920Fixture.Quote;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-13 预检（主线新增，<b>不替代</b> S-2 的正式 AC-13）：私有夹具上，并发度 3 两轮全量（Va、Vb）与经「重算」入口逐个串行重算（Vs）
 * 的逐行值逐位相同。
 * <ul>
 *   <li>夹具：3 张依据单 × 3 个料号；每行的固定额各不相同（⇒ 各行「报价·调整后」互异，串行错位也能被抓到，testing.md §5.7⑥）；</li>
 *   <li>Va：生成 V1 → 等全部 READY/FAILED → 导出；Vb：生成 V2（作废 V1）→ 等全部 READY/FAILED → 导出；
 *       Vs：对 V2 每条审核行调 {@code POST …/recompute-budget}，<b>等它回到 READY/FAILED 再发下一条</b>，全部完成后导出；</li>
 *   <li>比对键 (material_no, column_id)，值 = quote_current / quote_adjusted / costing_current / costing_adjusted / diff_adjusted
 *       （numeric 按 compareTo）；先断言三份行数相等且 &gt; 0、非空「调整后」行数 &gt; 0；</li>
 *   <li>阳性对照：在 Va 的副本上改一个值，哈希必须变化。</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(T920Profiles.Conc3.class)
class Ac260920ParallelConsistencyPrecheckTest {

    @Inject
    EntityManager em;
    @InjectMock
    SessionHelper sessionHelper;

    @Test
    void ac13Precheck_parallelTwiceEqualsSerialRecompute() throws Exception {
        assertEquals(3, ConfigProvider.getConfig().getValue("cpq.price-adjust.budget.concurrency", Integer.class),
            "前提：并发度 3");
        Rp0918aDb db = new Rp0918aDb(em);
        T920Fixture fx = new T920Fixture(db);
        T920Api api = T920Api.anonymous();
        try {
            fx.createCustomerAndStrategy();
            Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
            List<Quote> quotes = List.of(fx.createQuote("A", 3), fx.createQuote("B", 3), fx.createQuote("C", 3));
            UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", T920Fixture.P_PREV, 7_200);
            int i = 0;
            List<String> mats = new ArrayList<>();
            for (Quote q : quotes) {
                for (String m : q.materials()) {
                    i++;
                    varyFixedAmount(db, q.line(m), T920Fixture.FIXED.add(BigDecimal.valueOf(i)));
                    fx.setPointer(m, vPrev);
                    mats.add(m);
                }
            }
            fx.addScope(mats);
            fx.setElementPriceTarget(T920Fixture.P_TARGET);

            UUID v1 = api.generateVersion(fx.customerNo);
            assertEquals(0, fx.awaitVersionSettled(v1, 240_000), "V1 全部 READY/FAILED");
            fx.awaitQuiet(3_000, 60_000);
            TreeMap<String, String> va = export(db, v1);

            UUID v2 = api.generateVersion(fx.customerNo);
            assertEquals(0, fx.awaitVersionSettled(v2, 240_000), "V2 全部 READY/FAILED");
            fx.awaitQuiet(3_000, 60_000);
            TreeMap<String, String> vb = export(db, v2);

            long s0 = System.currentTimeMillis();
            List<String> serialLog = new ArrayList<>();
            for (Object[] r : db.rows("SELECT id, material_no FROM material_price_review WHERE version_id = :v AND status = 'PENDING' "
                + "ORDER BY material_no", "v", v2)) {
                UUID id = (UUID) r[0];
                String stamp = db.text("SELECT updated_at::text FROM material_price_review WHERE id = :id", "id", id);
                Response rr = api.recomputeBudget(id);
                assertEquals(202, rr.statusCode(), "重算入口 202: " + rr.asString());
                long deadline = System.currentTimeMillis() + 90_000;
                String st;
                String now;
                do {
                    T920Fixture.sleep(200);
                    st = db.text("SELECT budget_status FROM material_price_review WHERE id = :id", "id", id);
                    now = db.text("SELECT updated_at::text FROM material_price_review WHERE id = :id", "id", id);
                } while (System.currentTimeMillis() < deadline && (now.equals(stamp) || !("READY".equals(st) || "FAILED".equals(st))));
                serialLog.add(r[1] + "=" + st + (now.equals(stamp) ? "(未见重算)" : ""));
            }
            TreeMap<String, String> vs = export(db, v2);

            long nonNull = va.values().stream().filter(x -> !x.split("\\|", -1)[1].isEmpty()).count();
            TreeMap<String, String> tampered = new TreeMap<>(va);
            String firstKey = tampered.firstKey();
            tampered.put(firstKey, tampered.get(firstKey) + "9");
            Set<String> distinctAdjusted = new HashSet<>();
            for (String x : va.values()) distinctAdjusted.add(x.split("\\|", -1)[1]);
            T920Evidence.log("AC-13预检", "V1=" + v1 + " V2=" + v2 + "；行数 Va/Vb/Vs=" + va.size() + "/" + vb.size() + "/" + vs.size()
                + "；非空「报价·调整后」行=" + nonNull + "；不同调整后值=" + distinctAdjusted.size()
                + "\n哈希 Va=" + hash(va) + " Vb=" + hash(vb) + " Vs=" + hash(vs) + " 篡改副本=" + hash(tampered)
                + "\n串行重算用时=" + (System.currentTimeMillis() - s0) + "ms 轨迹=" + serialLog + "\nVa=" + va);

            assertTrue(va.size() > 0 && va.size() == vb.size() && vb.size() == vs.size(), "三份导出行数相等且 > 0");
            assertTrue(nonNull > 0, "非空「调整后」行 > 0（否则比对无鉴别力，报主线）");
            assertTrue(serialLog.stream().noneMatch(x -> x.contains("未见重算")), "串行重算每条都确实重算过: " + serialLog);
            assertEquals(va, vb, "Va = Vb（两轮并行逐行逐位相同）");
            assertEquals(vb, vs, "Vb = Vs（并行 = 串行重算）");
            assertNotEquals(hash(va), hash(tampered), "阳性对照：副本改一个值，哈希必须变化");
        } finally {
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    /** 让每行固定额各不相同：同步改该行元素页签 driverRow.fixedAmount、页签小计、行小计（口径 = 上期价 + 固定额，9 位）。 */
    private static void varyFixedAmount(Rp0918aDb db, UUID lineId, BigDecimal fixed) {
        String sub = T920Fixture.P_PREV.add(fixed).setScale(9, RoundingMode.HALF_UP).toPlainString();
        db.exec("UPDATE quotation_line_component_data SET snapshot_rows = jsonb_set(snapshot_rows, '{0,driverRow,fixedAmount}', "
            + "to_jsonb(CAST(:f AS text))), subtotal = CAST(:s AS numeric) WHERE line_item_id = :l", "f", fixed.toPlainString(),
            "s", sub, "l", lineId);
        db.exec("UPDATE quotation_line_item SET subtotal = CAST(:s AS numeric), line_total_amount = CAST(:s AS numeric) WHERE id = :l",
            "s", sub, "l", lineId);
    }

    /** (material_no|column_id) → quote_current|quote_adjusted|costing_current|costing_adjusted|diff_adjusted（stripTrailingZeros）。 */
    private static TreeMap<String, String> export(Rp0918aDb db, UUID v) {
        TreeMap<String, String> out = new TreeMap<>();
        for (Object[] r : db.rows("SELECT r.material_no, c.column_id, c.quote_current, c.quote_adjusted, c.costing_current, "
            + "c.costing_adjusted, c.diff_adjusted FROM material_price_review r JOIN material_price_review_column c ON c.review_id = r.id "
            + "WHERE r.version_id = :v AND r.status = 'PENDING'", "v", v)) {
            StringBuilder sb = new StringBuilder();
            for (int k = 2; k <= 6; k++) {
                if (k > 2) sb.append('|');
                sb.append(r[k] == null ? "" : new BigDecimal(r[k].toString()).stripTrailingZeros().toPlainString());
            }
            out.put(r[0] + "|" + r[1], sb.toString());
        }
        return out;
    }

    private static String hash(TreeMap<String, String> m) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (var e : m.entrySet()) md.update((e.getKey() + "=" + e.getValue() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.substring(0, 16);
    }
}
