package com.cpq.priceadjust.ac260918;

import com.cpq.priceadjust.ac260918.Rp0918aFixture.Edge;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Quote;
import com.cpq.priceadjust.dto.UpgradeResult;
import com.cpq.priceadjust.service.MaterialVersionUpgradeService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_PREV;
import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260918 · S-BE · <b>T-BE-13 / AC-13</b>（查询次数与行数无关）：
 * 「同一模板、同一组件结构的两张单（12 行 / ≥1000 行），对同一料号各做一次试算与一次正式升版 ⇒
 * [perf] upgrade … sql= 的条数两两相等；≥1000 行单的每次 [perf] revision-write 耗时 ≤ 1 秒」。
 *
 * <p>两张单都是全新（都从未升过版），保证两边处于同一状态 —— 否则「一边要定型初版、一边不用」会让条数不等，
 * 与行数无关。再对第二个共同料号各升一次（此时两边都已有初版 + 本期记录），比较「非首次」状态下的条数。
 *
 * <p>防恒真（testing.md §5.5 形态②）：{@code sql} 是实现自报的计数，若恒为常数（如 0）则「相等」必然成立。
 * 故加两条由需求推出的非平凡约束：sql &gt; 0；且「试算不拍快照」（问题说明 ⑤-2）⇒ 正式升版的条数必须多于试算。
 * 另核对日志里的 {@code lines=} 与单据真实行数一致，证明两行日志确实分属两张单。
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
class Ac260918T13SqlCountIndependentOfLinesTest {

    @Inject
    EntityManager em;
    @Inject
    MaterialVersionUpgradeService upgradeService;

    private Rp0918aFixture fx;

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.awaitQuiet(2_000, 60_000);
            fx.cleanup();
        }
    }

    @Test
    void tBe13_ac13_sqlCountEqualForSmallAndLargeQuote_andRevisionWriteUnderOneSecond() {
        Rp0918aDb db = new Rp0918aDb(em);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        fx.createTemplate();
        String same1 = fx.material("SAME", 1);
        String same2 = fx.material("SAME", 2);
        Quote small = fx.createQuote("S12", 12, Map.of(5, same1, 6, same2), Edge.NONE);
        Quote large = fx.createQuote("B1000", 1000, Map.of(5, same1, 6, same2), Edge.NONE);
        fx.addScopeFromQuotes(List.of(small.id(), large.id()));
        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 7_200);
        UUID vTarget = fx.insertVersion("TGT", "PENDING", P_TARGET, P_PREV,
            P_TARGET.subtract(P_PREV).divide(P_PREV, 12, RoundingMode.HALF_UP), 3_600);
        fx.setPointersFromQuotes(vPrev, List.of(small.id(), large.id()));

        try (Rp0918aLogCapture logs = Rp0918aLogCapture.start()) {
            long start = System.currentTimeMillis();
            Map<String, UpgradeResult> results = new LinkedHashMap<>();
            results.put("dry-small", upgradeService.upgrade(small.line(same1), vTarget, true));
            results.put("dry-large", upgradeService.upgrade(large.line(same1), vTarget, true));
            results.put("real1-small", upgradeService.upgrade(small.line(same1), vTarget, false));
            results.put("real1-large", upgradeService.upgrade(large.line(same1), vTarget, false));
            results.put("real2-small", upgradeService.upgrade(small.line(same2), vTarget, false));
            results.put("real2-large", upgradeService.upgrade(large.line(same2), vTarget, false));
            Rp0918aFixture.sleep(300);

            StringBuilder rs = new StringBuilder();
            for (Map.Entry<String, UpgradeResult> e : results.entrySet()) {
                assertNotNull(e.getValue(), e.getKey() + " 返回 null");
                rs.append(e.getKey()).append('=').append(e.getValue().status).append(' ');
                assertFalse(Set.of("FAILED", "CONFLICT").contains(e.getValue().status.name()),
                    e.getKey() + " 升版失败: " + e.getValue().status + " " + e.getValue().message);
            }
            for (String k : List.of("real1-small", "real1-large", "real2-small", "real2-large")) {
                assertEquals(UpgradeResult.Status.SUCCESS, results.get(k).status, k + " 应 SUCCESS: " + results.get(k).message);
            }

            List<Rp0918aLogCapture.PerfUpgrade> perf = logs.perfUpgrades(start);
            Rp0918aLogCapture.PerfUpgrade dS = pick(perf, small.line(same1), true, 0);
            Rp0918aLogCapture.PerfUpgrade dL = pick(perf, large.line(same1), true, 0);
            Rp0918aLogCapture.PerfUpgrade r1S = pick(perf, small.line(same1), false, 0);
            Rp0918aLogCapture.PerfUpgrade r1L = pick(perf, large.line(same1), false, 0);
            Rp0918aLogCapture.PerfUpgrade r2S = pick(perf, small.line(same2), false, 0);
            Rp0918aLogCapture.PerfUpgrade r2L = pick(perf, large.line(same2), false, 0);
            List<Rp0918aLogCapture.RevisionWrite> largeWrites = new ArrayList<>();
            for (Rp0918aLogCapture.RevisionWrite w : logs.revisionWrites(start)) {
                if (large.id().toString().equals(w.quotationId())) largeWrites.add(w);
            }
            Rp0918aEvidence.log("AC-13", "结果：" + rs);
            for (Rp0918aLogCapture.PerfUpgrade p : List.of(dS, dL, r1S, r1L, r2S, r2L)) Rp0918aEvidence.log("AC-13", p.raw());
            for (Rp0918aLogCapture.RevisionWrite w : largeWrites) Rp0918aEvidence.log("AC-13", w.raw());

            assertEquals(12, dS.lines(), "日志 lines= 应为小单行数");
            assertEquals(1000, dL.lines(), "日志 lines= 应为大单行数");
            assertTrue(dS.sql() > 0, "防恒真：sql 计数应 > 0");
            assertEquals(dS.sql(), dL.sql(), "AC-13：试算的 SQL 条数应与行数无关（12 行 vs 1000 行）");
            assertEquals(r1S.sql(), r1L.sql(), "AC-13：首次正式升版的 SQL 条数应与行数无关");
            assertEquals(r2S.sql(), r2L.sql(), "AC-13 加强：已有版本记录时正式升版的 SQL 条数也应与行数无关");
            assertTrue(r1S.sql() > dS.sql(), "防恒真：试算不拍快照 ⇒ 正式升版条数应多于试算（"
                + r1S.sql() + " vs " + dS.sql() + "）");

            assertTrue(largeWrites.size() >= 2, "应至少有 INITIAL + CURRENT 两行 revision-write 日志，实际 " + largeWrites);
            assertTrue(largeWrites.stream().anyMatch(w -> "INITIAL".equals(w.kind())), "缺 kind=INITIAL");
            assertTrue(largeWrites.stream().anyMatch(w -> "CURRENT".equals(w.kind())), "缺 kind=CURRENT");
            for (Rp0918aLogCapture.RevisionWrite w : largeWrites) {
                assertTrue(w.ms() <= 1_000, "AC-13：≥1000 行单每次 revision-write 应 ≤ 1 秒: " + w.raw());
            }
        }
    }

    private static Rp0918aLogCapture.PerfUpgrade pick(List<Rp0918aLogCapture.PerfUpgrade> perf, UUID line,
                                                       boolean dryRun, int index) {
        List<Rp0918aLogCapture.PerfUpgrade> hits = new ArrayList<>();
        for (Rp0918aLogCapture.PerfUpgrade p : perf) {
            if (line.toString().equals(p.lineId()) && p.dryRun() == dryRun) hits.add(p);
        }
        assertTrue(hits.size() > index, "找不到 li=" + line + " dryRun=" + dryRun + " 的 [perf] upgrade 日志；全部 perf 日志=" + perf);
        return hits.get(index);
    }
}
