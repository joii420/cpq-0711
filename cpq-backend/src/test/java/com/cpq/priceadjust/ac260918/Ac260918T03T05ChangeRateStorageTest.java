package com.cpq.priceadjust.ac260918;

import com.cpq.priceadjust.service.PriceAdjustVersionGenerationService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * repair-260918 · S-BE · 问题 1（涨跌率）的存储侧。
 *
 * <ul>
 *   <li><b>T-BE-3 / AC-3</b>（单点 · 存储）：「新生成的版本，涨跌率按 12 位小数存：上期价 28892.5、本期价 28892.501
 *       ⇒ 库中 change_rate = 0.000000034611」。另加一个<b>跌</b>方向的边界（AC-4 显示侧用到的
 *       -0.000000034611 在存储侧也能产生）。</li>
 *   <li><b>T-BE-5 / AC-5</b>（无副作用）：「已生成版本不回算 —— 改动上线后 V26091802 银的 change_rate 仍为
 *       0.000035000000」。测试库没有 V26091802（克隆早于它），用同形态的自建历史版本（6 位精度时代生成的
 *       0.000035000000）代替；开发库那条由主线亲验查库。</li>
 * </ul>
 *
 * <p>价差怎么来：只给本片客户配一条元素取价策略（{@link Rp0918aFixture#setElementPriceTarget}），
 * 🚫 不碰全局元素日价。上一版价格用直接插入的历史版本提供。
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
class Ac260918T03T05ChangeRateStorageTest {

    @Inject
    PriceAdjustVersionGenerationService generation;
    @Inject
    EntityManager em;

    private Rp0918aDb db;
    private Rp0918aFixture fx;

    @BeforeEach
    void setUp() {
        db = new Rp0918aDb(em);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        // 范围里放一个不在任何活单里的料号：生成不会因「范围为空」走异常分支；预算对它只会自动推进指针（D5）。
        fx.addScope(List.of(fx.material("DUMMY", 1)));
    }

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.awaitQuiet(3_000, 60_000);
            fx.cleanup();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // T-BE-3 · AC-3：涨 —— 28892.5 → 28892.501 ⇒ 0.000000034611
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void tBe3_ac3_newVersionStoresChangeRateWithTwelveDecimals_rise() {
        BigDecimal prev = new BigDecimal("28892.5");
        BigDecimal cur = new BigDecimal("28892.501");
        BigDecimal expected = new BigDecimal("0.000000034611");
        // AC 原文数值自洽性（防止把错的期望值写死）：(cur-prev)/prev 四舍五入到 12 位
        assertEquals(0, cur.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP).compareTo(expected),
            "AC-3 原文数值自检");

        fx.insertVersion("PREV", "SUPERSEDED", prev, null, null, 3_600);
        fx.setElementPriceTarget(cur);

        PriceAdjustVersionGenerationService.GenerateResult r =
            generation.generateVersion(fx.customerNo, true, "MANUAL", null);
        assertNotNull(r, "generateVersion 返回 null");
        assertNotNull(r.versionId, "未生成版本");
        assertFalse(r.alreadyExisted, "应是新生成的版本");

        Object[] item = readAg(r.versionId);
        Rp0918aEvidence.log("AC-03", "涨: version=" + r.versionId + " current=" + item[0] + " previous=" + item[1]
            + " change_rate(库原值)=" + item[2]);
        assertEquals(0, new BigDecimal((String) item[0]).compareTo(cur), "夹具前提：本期价应为 28892.501，实际 " + item[0]);
        assertEquals(0, new BigDecimal((String) item[1]).compareTo(prev), "夹具前提：上期价应为 28892.5，实际 " + item[1]);
        assertNotNull(item[2], "change_rate 为空");
        assertEquals(0, new BigDecimal((String) item[2]).compareTo(expected),
            "AC-3：change_rate 应为 0.000000034611（12 位），实际库原值 " + item[2]
                + "（若为 0 = 仍按 6 位舍入）");
    }

    // ─────────────────────────────────────────────────────────────────────
    // T-BE-3 边界：跌 —— 28892.501 → 28892.5 ⇒ -0.000000034611（非零且为负，不得被舍成 0）
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void tBe3_ac3_boundary_fall_tinyNegativeRateNotRoundedToZero() {
        BigDecimal prev = new BigDecimal("28892.501");
        BigDecimal cur = new BigDecimal("28892.5");
        BigDecimal expected = cur.subtract(prev).divide(prev, 12, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(new BigDecimal("-0.000000034611")), "期望值自检");

        fx.insertVersion("PREV", "SUPERSEDED", prev, null, null, 3_600);
        fx.setElementPriceTarget(cur);
        PriceAdjustVersionGenerationService.GenerateResult r =
            generation.generateVersion(fx.customerNo, true, "MANUAL", null);
        assertNotNull(r.versionId);

        Object[] item = readAg(r.versionId);
        Rp0918aEvidence.log("AC-03", "跌: version=" + r.versionId + " current=" + item[0] + " previous=" + item[1]
            + " change_rate(库原值)=" + item[2]);
        assertEquals(0, new BigDecimal((String) item[0]).compareTo(cur), "夹具前提：本期价");
        assertNotNull(item[2], "change_rate 为空");
        assertEquals(0, new BigDecimal((String) item[2]).compareTo(expected),
            "AC-3 边界：跌 0.001 时 change_rate 应为 -0.000000034611，实际 " + item[2]);
    }

    // ─────────────────────────────────────────────────────────────────────
    // T-BE-5 · AC-5：生成新版本不回算已生成版本的 change_rate
    // ─────────────────────────────────────────────────────────────────────
    @Test
    void tBe5_ac5_existingVersionsChangeRateNotRecomputed() {
        // 历史：OLD1（更早）与 OLD2（最近一版，形态同开发库 V26091802：28893.5 / 28892.5 / 0.000035000000）
        UUID old1 = fx.insertVersion("OLD1", "SUPERSEDED", new BigDecimal("28892.5"), new BigDecimal("28890"),
            new BigDecimal("0.000086540000"), 7_200);
        UUID old2 = fx.insertVersion("OLD2", "PENDING", new BigDecimal("28893.5"), new BigDecimal("28892.5"),
            new BigDecimal("0.000035000000"), 3_600);
        String itemsBefore = itemsDigest(old1, old2);
        assertEquals("0.000035000000", rateText(old2), "夹具前提：历史版本库原值");

        BigDecimal cur = new BigDecimal("28892.501");
        fx.setElementPriceTarget(cur);
        PriceAdjustVersionGenerationService.GenerateResult r =
            generation.generateVersion(fx.customerNo, true, "MANUAL", null);
        assertNotNull(r.versionId);
        fx.awaitQuiet(3_000, 60_000);

        String itemsAfter = itemsDigest(old1, old2);
        String old2Rate = rateText(old2);
        String old2Status = db.text("SELECT status FROM element_price_version WHERE id = :id", "id", old2);
        Object[] newItem = readAg(r.versionId);
        BigDecimal newExpected = cur.subtract(new BigDecimal("28893.5")).divide(new BigDecimal("28893.5"), 12,
            RoundingMode.HALF_UP);
        Rp0918aEvidence.log("AC-05", "OLD2 change_rate 前=0.000035000000 后=" + old2Rate + "；OLD2 状态=" + old2Status
            + "；历史明细指纹 前=" + itemsBefore + " 后=" + itemsAfter + "；新版 change_rate=" + newItem[2]
            + "（12 位期望 " + newExpected.toPlainString() + "）");

        assertEquals("0.000035000000", old2Rate, "AC-5：已生成版本的 change_rate 不得被回算");
        assertEquals(itemsBefore, itemsAfter, "AC-5：已生成版本的明细行（全部列）不得被改动");

        // 阳性对照：生成确实发生、且确实以 12 位精度计算了（否则「没回算」可能只是「什么都没算」）
        assertEquals("SUPERSEDED", old2Status, "阳性对照：新版生成应作废上一待处理版本 —— 证明生成流程确实经过了历史版本");
        assertNotNull(newItem[2], "新版 change_rate 为空");
        assertEquals(0, new BigDecimal((String) newItem[2]).compareTo(newExpected),
            "阳性对照：新版 change_rate 应按 12 位算出 " + newExpected.toPlainString() + "，实际 " + newItem[2]);
        assertNotEquals(0, newExpected.compareTo(newExpected.setScale(6, RoundingMode.HALF_UP)),
            "阳性对照自检：该取值在 6 位与 12 位下必须可区分");
    }

    private Object[] readAg(UUID versionId) {
        List<Object[]> r = db.rows("SELECT current_price::text, previous_price::text, change_rate::text "
            + "FROM element_price_version_item WHERE version_id = :v AND element_code = 'Ag'", "v", versionId);
        assertEquals(1, r.size(), "版本 " + versionId + " 应恰有 1 条银明细，实际 " + r.size());
        return r.get(0);
    }

    private String rateText(UUID versionId) {
        return db.text("SELECT change_rate::text FROM element_price_version_item WHERE version_id = :v AND element_code = 'Ag'",
            "v", versionId);
    }

    private String itemsDigest(UUID... versionIds) {
        return db.text("SELECT count(*) || ':' || md5(string_agg(row_to_json(i)::text, ',' ORDER BY i.id)) "
            + "FROM element_price_version_item i WHERE i.version_id IN (:v)", "v", List.of(versionIds));
    }
}
