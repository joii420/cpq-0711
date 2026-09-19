package com.cpq.priceadjust.ac260918;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Edge;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Quote;
import com.cpq.priceadjust.ac260918.Rp0918aSnapshots.Revision;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.RollbackException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_PREV;
import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260918 · S-BE · 问题 2+3（大单升版）主序列 —— 与主线亲验计划（test.md §6 第 1~6 步）同构，
 * 数据换成本片私有合成单：
 *
 * <pre>
 *   QW   12 行   预热（不计时）
 *   Q12  12 行   T₀：「从点通过到批次完成」的速度基准
 *   QL   1200 行 对应开发库 QT-20260911-0842（含 3 条口径边界行：卡片值为空 / 页签 component_id 为空 / 无页签数据）
 *   QA   1845 行 对应开发库 QT-20260909-0629；第 221 / 239 / 291 行与 QL 同料号（对应 PERFHOT-*）
 * </pre>
 *
 * 顺序：T₀ → AC-6/7（抽屉，单据从未升版）→ AC-8/20（失败明细单条重试 = 首次升版）→ AC-9/11/12（同单 7 行合并）
 * → AC-10（跨两张大单）→ AC-7 加强（单据已有版本记录时再开抽屉）。
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac260918LargeQuoteSequenceTest {

    private static final BigDecimal EXPECTED = Rp0918aFixture.expectedSubtotal(P_TARGET);
    private static final String TIMEOUT_MSG = "执行超时（超过 60 秒）被中止，本行未更新，可重试";

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustBudgetService budget;
    @InjectMock
    SessionHelper sessionHelper;

    private Rp0918aDb db;
    private Rp0918aFixture fx;
    private Rp0918aSnapshots snaps;
    private Quote qWarm, q12, qL, qA;
    private UUID vPrev, vTarget;
    private final Map<String, UUID> reviews = new LinkedHashMap<>();
    private String mWarm, mT0, mFail, mDetailLater;
    private List<String> batch7;
    private List<String> hot3;

    private UpgradeInterceptor upgrades;
    private RevisionWriterInterceptor writer;
    private Rp0918aLogCapture logs;

    /** T₀（毫秒）。t00 测得；后续用例缺它时直接失败而不是跳过。 */
    private Long t0Millis;
    /** t06 时 QL 的全行指纹，供 t07b 做阳性对照。 */
    private String qlDigestAtT06;

    @BeforeAll
    void buildFixture() {
        db = new Rp0918aDb(em);
        snaps = new Rp0918aSnapshots(db);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        fx.createTemplate();
        long s = System.currentTimeMillis();
        qWarm = fx.createQuote("QW", 12, null, Edge.NONE);
        q12 = fx.createQuote("Q12", 12, null, Edge.NONE);
        qL = fx.createQuote("QL", 1200, null, new Edge(1198, 1199, 1200));
        Map<Integer, String> shared = new LinkedHashMap<>();
        for (int o : new int[]{221, 239, 291}) shared.put(o, qL.material(o));
        qA = fx.createQuote("QA", 1845, shared, Edge.NONE);
        List<UUID> all = List.of(qWarm.id(), q12.id(), qL.id(), qA.id());
        fx.addScopeFromQuotes(all);

        vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 7_200);
        vTarget = fx.insertVersion("TGT", "PENDING", P_TARGET, P_PREV,
            P_TARGET.subtract(P_PREV).divide(P_PREV, 12, RoundingMode.HALF_UP), 3_600);
        fx.setPointersFromQuotes(vPrev, all);

        mWarm = qWarm.material(1);
        mT0 = q12.material(1);
        mFail = qL.material(432);
        batch7 = List.of(qL.material(7), qL.material(217), qL.material(259), qL.material(368), qL.material(485),
            qL.material(598), qL.material(150));
        hot3 = List.of(qL.material(221), qL.material(239), qL.material(291));
        mDetailLater = qL.material(999);

        List<String> need = new ArrayList<>();
        need.add(mWarm);
        need.add(mT0);
        need.add(mFail);
        need.addAll(batch7);
        need.addAll(hot3);
        need.add(mDetailLater);
        long b = System.currentTimeMillis();
        reviews.putAll(fx.createReviewsViaBudget(budget, vTarget, need));
        Rp0918aEvidence.log("fixture", "客户 " + fx.customerNo + "；QW/Q12/QL(1200)/QA(1845) 造数 "
            + (b - s) + "ms；预算建审核 " + need.size() + " 个料号 " + (System.currentTimeMillis() - b) + "ms；"
            + "QL=" + qL.id() + " QA=" + qA.id() + " vTarget=" + vTarget);
    }

    @AfterAll
    void cleanup() {
        if (fx != null) {
            fx.awaitQuiet(3_000, 120_000);
            fx.cleanup();
        }
    }

    @BeforeEach
    void installSeams() {
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        upgrades = UpgradeInterceptor.install();
        writer = RevisionWriterInterceptor.install();
        logs = Rp0918aLogCapture.start();
    }

    @AfterEach
    void closeLogs() {
        if (upgrades != null) {
            Rp0918aEvidence.log("upgrade-paths", getClass().getSimpleName() + " 拦到的 upgrade 调用：" + upgrades.signatureSummary());
            upgrades.clearRules();
        }
        if (writer != null) writer.disarm();
        if (logs != null) logs.close();
    }

    // ═════════════════════════════════════════════════════════════════════
    // T₀
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    void t00_warmupThenMeasureT0OnTwelveLineQuote() {
        UUID jw = Rp0918aApi.approveOk(List.of(reviews.get(mWarm)));
        assertEquals("SUCCESS", Rp0918aApi.awaitJobTerminal(db, jw, 120_000), "预热批次未成功");

        long start = System.currentTimeMillis();
        UUID j0 = Rp0918aApi.approveOk(List.of(reviews.get(mT0)));
        String st = Rp0918aApi.awaitJobTerminal(db, j0, 120_000);
        long t0 = System.currentTimeMillis() - start;
        assertEquals("SUCCESS", st, "T₀ 批次未成功");
        // T₀ 必须测的是一次真实升版（不是被跳过的空操作），否则后续比值没有意义
        UUID line = q12.line(mT0);
        assertEquals(0, P_TARGET.compareTo(fx.lineElementPrice(line)), "T₀ 那一行的银价应已改为本期价");
        assertEquals(0, EXPECTED.compareTo(fx.lineSubtotal(line)), "T₀ 那一行的小计应已按本期价重算");
        t0Millis = t0;
        Rp0918aEvidence.log("T0", "T₀ = " + t0 + "ms（Q12 12 行，料号 " + mT0 + "，批次 " + j0 + "，从调 approve 到批次离开 RUNNING）");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-6 / T-BE-7 · AC-6 + AC-7：1200 行依据单，抽屉 5 秒内、调整后小计为数值、零写库
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    void t06_t07_reviewDetailOnLargeBasisQuote_fastComputedAndSideEffectFree() {
        String material = batch7.get(0);
        UUID reviewId = reviews.get(material);
        UUID basisLine = qL.line(material);

        List<String> stampsBefore = snaps.revisionStamps(qL.id());
        String linesBefore = snaps.allLinesSubtotalAndQuoteCardDigest(qL.id());
        String compBefore = snaps.allComponentDataDigest(qL.id());
        long structBefore = db.count("SELECT count(*) FROM quotation_view_structure WHERE quotation_id = :q", "q", qL.id());
        assertTrue(stampsBefore.isEmpty(), "场景前提（同开发库）：依据单此时从未升过版，版本记录 0 行；实际 " + stampsBefore);
        assertTrue(linesBefore.startsWith("1200:"), "场景前提：1200 行，实际 " + linesBefore);
        qlDigestAtT06 = linesBefore;

        long s = System.currentTimeMillis();
        Response r = Rp0918aApi.reviewDetail(reviewId);
        long ms = System.currentTimeMillis() - s;
        long s2 = System.currentTimeMillis();
        int secondStatus = Rp0918aApi.reviewDetail(reviewId).statusCode();
        long ms2 = System.currentTimeMillis() - s2;

        Rp0918aEvidence.log("AC-06", "GET /reviews/" + reviewId + " 首次 " + ms + "ms HTTP " + r.statusCode()
            + "；再次 " + ms2 + "ms HTTP " + secondStatus + "；响应=" + abbreviate(r.asString()));
        assertEquals(200, r.statusCode(), "AC-6：抽屉接口应 200，实际 " + r.statusCode() + " " + r.asString());
        JsonNode d = Rp0918aApi.json(r);
        JsonNode basis = Rp0918aApi.basisRow(d);
        assertNotNull(basis, "AC-6：「三、逐单明细」里找不到依据单行（isBasis=true）；quotations=" + d.path("quotations"));
        assertEquals(qL.id().toString(), basis.path("quotationId").asText(), "依据单应为 1200 行的 QL");
        assertTrue(basis.path("adjustedComputed").asBoolean(false),
            "AC-6：依据单行 adjustedComputed 应为 true（不是「未试算」），实际 " + basis);
        BigDecimal adjusted = Rp0918aApi.decimal(basis.path("quoteSubtotalAdjusted"));
        assertNotNull(adjusted, "AC-6：调整后小计应为数值，实际 " + basis.path("quoteSubtotalAdjusted"));
        BigDecimal impact = Rp0918aApi.decimal(d.path("elementImpactTotal"));
        assertNotNull(impact, "AC-6：合计对单价影响应为数值，实际 " + d.path("elementImpactTotal"));
        Rp0918aEvidence.log("AC-06", "依据单行 quoteSubtotalAdjusted=" + adjusted.toPlainString() + "（独立期望 "
            + EXPECTED.toPlainString() + "）adjustedComputed=true；elementImpactTotal=" + impact.toPlainString()
            + "（独立期望 " + EXPECTED.subtract(Rp0918aFixture.expectedSubtotal(P_PREV)).toPlainString() + "）");
        assertEquals(0, EXPECTED.compareTo(adjusted), "调整后小计应 = 本期价 + 固定额（9 位结果边界）= " + EXPECTED.toPlainString());
        assertTrue(ms <= 5_000, "AC-6：抽屉应 5 秒内加载完成，实际首次 " + ms + "ms");

        // AC-7：前后逐项不变
        List<String> stampsAfter = snaps.revisionStamps(qL.id());
        String linesAfter = snaps.allLinesSubtotalAndQuoteCardDigest(qL.id());
        String compAfter = snaps.allComponentDataDigest(qL.id());
        long structAfter = db.count("SELECT count(*) FROM quotation_view_structure WHERE quotation_id = :q", "q", qL.id());
        long dryCalls = upgrades.calls().stream()
            .filter(c -> Boolean.TRUE.equals(c.dryRun()) && c.touches(Set.of(basisLine))).count();
        Rp0918aEvidence.log("AC-07", "版本记录 前=" + stampsBefore + " 后=" + stampsAfter + "；行指纹(subtotal+quote_card_values) 前="
            + linesBefore + " 后=" + linesAfter + "；页签数据指纹 前=" + compBefore + " 后=" + compAfter
            + "；冻结结构行数 前=" + structBefore + " 后=" + structAfter + "；两次打开期间对依据行的试算调用 " + dryCalls + " 次");
        assertEquals(stampsBefore, stampsAfter, "AC-7：quotation_price_revision 行数与各行 last_updated_at 应不变");
        assertEquals(linesBefore, linesAfter, "AC-7：全部产品行 subtotal 与 quote_card_values 的 md5 应不变");
        assertEquals(compBefore, compAfter, "AC-7 附加：页签数据（snapshot_rows/row_data/row_version）应不变");
        assertEquals(structBefore, structAfter, "AC-7 附加：冻结结构行数应不变");
        // 阳性对照：抽屉确实做了实时试算（问题说明 ⑤ 已否决「改读预算结果」），「不变」不是因为什么都没跑
        assertTrue(dryCalls >= 1, "阳性对照：打开抽屉应对依据行做过试算，实际 0 次（观测不成立）");
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-8 / T-BE-20（+ T-BE-15 超时形态）· AC-8 + AC-20：失败明细单条重试 = 该大单首次升版
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @Order(3)
    void t08_t20_failedItemOnLargeQuote_retrySucceeds_matchesDrawer_andCreatesTwoRevisions() {
        long t0 = requireT0();
        UUID lineFail = qL.line(mFail);

        // 造「失败明细」：真实执行时抛事务超时形态的异常（问题 3 的真实失败形态）
        UpgradeInterceptor.Rule timeout = upgrades.addRule("AC-8 造失败：事务超时",
            UpgradeInterceptor.onLines(Set.of(lineFail), false),
            UpgradeInterceptor.throwing(() -> new RuntimeException("Error invoking subclass method",
                new RollbackException("ARJUNA016102: The transaction is not active! Uid is 0:ffff:rp0918a:8"))));
        UUID job = Rp0918aApi.approveOk(List.of(reviews.get(mFail)));
        String jobStatus = Rp0918aApi.awaitJobTerminal(db, job, 120_000);
        timeout.disarm();
        assertTrue(timeout.fired() >= 1, "注入未生效（没有拦到对该行的真实升版调用）");
        JsonNode failed = Rp0918aApi.itemsByMaterial(job).get(mFail).get(0);
        Rp0918aEvidence.log("AC-08", "造失败：批次 " + job + " 状态 " + jobStatus + "；明细=" + failed);
        Rp0918aEvidence.log("AC-15", "(a) 超时形态（RollbackException ARJUNA016102）→ " + failed.path("errorCode").asText()
            + " / " + failed.path("errorMessage").asText());
        assertEquals("FAILED", failed.path("status").asText(), "失败明细应为 FAILED");
        assertEquals("EXECUTION_TIMEOUT", failed.path("errorCode").asText(), "AC-15(a)：错误码");
        assertEquals(TIMEOUT_MSG, failed.path("errorMessage").asText(), "AC-15(a)：错误信息");
        assertEquals(0, P_PREV.compareTo(fx.lineElementPrice(lineFail)), "失败那次不应改动该行（本行未更新）");
        assertTrue(snaps.revisions(qL.id()).isEmpty(), "AC-20 前提：该单此时从未升过版（版本记录 0 行）");
        UUID itemId = UUID.fromString(failed.path("itemId").asText());
        int retryBefore = failed.path("retryCount").asInt();
        String cardBefore = fx.lineQuoteCardText(lineFail);
        assertNotNull(cardBefore, "前提：该行升版前卡片值非空");

        // ① 先打开抽屉记 X
        Response dr = Rp0918aApi.reviewDetail(reviews.get(mFail));
        assertEquals(200, dr.statusCode(), "抽屉 HTTP " + dr.statusCode());
        JsonNode basis = Rp0918aApi.basisRow(Rp0918aApi.json(dr));
        assertNotNull(basis, "抽屉里找不到依据单行");
        assertTrue(basis.path("adjustedComputed").asBoolean(false), "抽屉依据单行应已试算: " + basis);
        BigDecimal x = Rp0918aApi.decimal(basis.path("quoteSubtotalAdjusted"));
        assertNotNull(x, "X（调整后小计）为空");
        Rp0918aEvidence.log("AC-08", "① 抽屉依据单行调整后小计 X=" + x.toPlainString());

        // ② 重试 → ③ 成功且耗时 ≤ 1.5 T₀
        long s = System.currentTimeMillis();
        Response rr = Rp0918aApi.retryItem(itemId);
        assertEquals(202, rr.statusCode(), "单条重试应 202: " + rr.asString());
        Object[] fin = Rp0918aApi.awaitItem(db, itemId,
            row -> "SUCCESS".equals(row[0]) || (Rp0918aApi.isTerminal((String) row[0]) && ((Number) row[3]).intValue() > retryBefore),
            120_000);
        long ms = System.currentTimeMillis() - s;
        Rp0918aEvidence.log("AC-08", "③ 重试 " + ms + "ms（1.5×T₀=" + (1.5 * t0) + "ms）→ " + fin[0] + "/" + fin[1] + "/" + fin[2]);
        assertEquals("SUCCESS", fin[0], "AC-8：重试后应成功，实际 " + fin[0] + " " + fin[1] + " " + fin[2]);
        assertTrue(ms <= 1.5 * t0, "AC-8：重试耗时应 ≤ 1.5×T₀（" + (1.5 * t0) + "ms），实际 " + ms + "ms");

        // ④ 行上的元素价与小计
        BigDecimal price = fx.lineElementPrice(lineFail);
        BigDecimal sub = fx.lineSubtotal(lineFail);
        Rp0918aEvidence.log("AC-08", "④ 行 " + lineFail + " 银价=" + price + " 小计=" + sub + " X=" + x.toPlainString());
        assertNotNull(price);
        assertNotNull(sub);
        assertEquals(0, P_TARGET.compareTo(price), "AC-8④：元素银单价应 = 本期价 " + P_TARGET.toPlainString());
        assertEquals(0, x.compareTo(sub), "AC-8④：行小计应与抽屉 X 逐位相等");
        assertEquals(x.stripTrailingZeros().toPlainString(), sub.stripTrailingZeros().toPlainString(), "AC-8④：逐位");

        // ⑤ 刷新后再看
        Rp0918aFixture.sleep(1_000);
        JsonNode again = Rp0918aApi.itemsById(job).get(itemId.toString());
        assertEquals("SUCCESS", again.path("status").asText(), "AC-8⑤：刷新后状态应不变");
        assertEquals(0, sub.compareTo(fx.lineSubtotal(lineFail)), "AC-8⑤：刷新后小计应不变");
        assertEquals(0, P_TARGET.compareTo(fx.lineElementPrice(lineFail)), "AC-8⑤：刷新后单价应不变");
        JsonNode jobAfter = Rp0918aApi.job(job);
        Rp0918aEvidence.log("AC-08", "⑤ 刷新后明细=" + again + "；批次=" + jobAfter);

        // AC-20：恰好两行版本记录
        List<Revision> revs = snaps.revisions(qL.id());
        StringBuilder rs = new StringBuilder();
        for (Revision rv : revs) {
            rs.append("[").append(rv.revisionNo()).append(" based=").append(rv.basedVersionId()).append(" sealed=")
                .append(rv.sealed()).append(" mats=").append(rv.upgradedMaterialNos()).append("] ");
        }
        Rp0918aEvidence.log("AC-20", "QL 版本记录 " + revs.size() + " 行：" + rs);
        assertEquals(2, revs.size(), "AC-20：单条路径首次升版后应恰好 2 行版本记录，实际 " + rs);
        Revision init = snaps.initial(qL.id());
        Revision cur = snaps.current(qL.id(), vTarget);
        assertNotNull(init, "AC-20：缺初版（based_version_id 为空）");
        assertNotNull(cur, "AC-20：缺本期（based_version_id = 目标版本）");
        assertTrue(init.sealed(), "AC-20：初版应已定型");
        assertNotEquals(init.revisionNo(), cur.revisionNo(), "AC-20：两行 revision_no 必须不同");
        Set<String> actualNos = Set.of(init.revisionNo(), cur.revisionNo());
        boolean matched = false;
        for (ZoneId z : List.of(ZoneId.systemDefault(), ZoneId.of("Asia/Shanghai"))) {
            String dd = LocalDate.now(z).format(DateTimeFormatter.ofPattern("yyMMdd"));
            if (Set.of("R" + dd + "01", "R" + dd + "02").equals(actualNos)) matched = true;
        }
        assertTrue(matched, "AC-20：revision_no 应为 R<当天>01 / R<当天>02，实际 " + actualNos);
        JsonNode curLine = cur.quoteCardValues() == null ? null : cur.quoteCardValues().get(lineFail.toString());
        JsonNode initLine = init.quoteCardValues() == null ? null : init.quoteCardValues().get(lineFail.toString());
        JsonNode lineAfter = Rp0918aSnapshots.parse(fx.lineQuoteCardText(lineFail));
        JsonNode lineBefore = Rp0918aSnapshots.parse(cardBefore);
        assertNotEquals(lineBefore, lineAfter, "前提：升版前后该行卡片值应不同（否则下面两条比较无区分力）");
        assertEquals(lineAfter, curLine, "AC-20：本期快照里该行报价卡片值应 = 升版后的 quote_card_values；差异 "
            + Rp0918aSnapshots.firstDiff(lineAfter, curLine, "$"));
        assertEquals(lineBefore, initLine, "AC-20：初版快照里该行报价卡片值应 = 升版前的值；差异 "
            + Rp0918aSnapshots.firstDiff(lineBefore, initLine, "$"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-9 / T-BE-11 / T-BE-12 · AC-9 + AC-11 + AC-12：同一 1200 行单 7 行合并执行
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @Order(4)
    void t09_t11_t12_sevenLinesOnSameLargeQuote() {
        long t0 = requireT0();
        Map<String, UUID> lineOf = new LinkedHashMap<>();
        for (String m : batch7) lineOf.put(m, qL.line(m));
        Set<UUID> lines7 = new HashSet<>(lineOf.values());

        // AC-11 前置：未通过行任取 3 个（固定种子，打印）
        Set<Integer> exclude = new HashSet<>(List.of(7, 217, 259, 368, 485, 598, 150, 432, 221, 239, 291, 999,
            1198, 1199, 1200));
        long seed = System.nanoTime();
        Random rnd = new Random(seed);
        List<UUID> sample = new ArrayList<>();
        while (sample.size() < 3) {
            int o = 1 + rnd.nextInt(1197);
            if (exclude.add(o)) sample.add(qL.lineByOrdinal().get(o));
        }
        Map<UUID, Map<String, String>> sampleBefore = new LinkedHashMap<>();
        for (UUID l : sample) sampleBefore.put(l, snaps.lineFourDigests(l));
        String untouchedBefore = snaps.untouchedLinesDigest(qL.id(), lines7);
        Revision curBefore = snaps.current(qL.id(), vTarget);
        assertNotNull(curBefore, "前提：t08（AC-8）已为 QL 写出本期记录；缺失则本用例的「共 8 个」无从成立");
        List<String> prevUpgraded = curBefore.upgradedMaterialNos();
        assertEquals(List.of(mFail), prevUpgraded, "前提：执行前本期记录只含 AC-8 的料号");

        // 通过前在抽屉看到的调整后小计
        Map<String, BigDecimal> xs = new LinkedHashMap<>();
        StringBuilder xsLog = new StringBuilder();
        for (String m : batch7) {
            long s = System.currentTimeMillis();
            Response r = Rp0918aApi.reviewDetail(reviews.get(m));
            long ms = System.currentTimeMillis() - s;
            assertEquals(200, r.statusCode(), "抽屉 " + m + " HTTP " + r.statusCode());
            JsonNode basis = Rp0918aApi.basisRow(Rp0918aApi.json(r));
            assertNotNull(basis, "抽屉 " + m + " 没有依据单行");
            BigDecimal x = Rp0918aApi.decimal(basis.path("quoteSubtotalAdjusted"));
            assertNotNull(x, "抽屉 " + m + " 调整后小计为空");
            xs.put(m, x);
            xsLog.append(m).append('=').append(x.toPlainString()).append('(').append(ms).append("ms) ");
        }
        Rp0918aEvidence.log("AC-11", "抽样种子=" + seed + " 抽样行=" + sample + "；通过前抽屉 X：" + xsLog);

        // 执行中探针：每条明细真正开始升版的那一刻，读一次批次与明细
        List<Map<String, Object>> probes = Collections.synchronizedList(new ArrayList<>());
        long[] probeCost = {0};
        upgrades.addRule("AC-9 执行中探针", UpgradeInterceptor.onLines(lines7, false), c -> {
            long ps = System.currentTimeMillis();
            UUID line = null;
            for (UUID u : c.uuids()) if (lines7.contains(u)) line = u;
            UUID jobId = (UUID) db.scalar("SELECT job_id FROM material_price_update_job_item WHERE line_item_id = :l "
                + "ORDER BY created_at DESC LIMIT 1", "l", line);
            Response jr = Rp0918aApi.jobRaw(jobId);
            JsonNode items = Rp0918aApi.items(jobId);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("line", line);
            p.put("jobRaw", jr.asString());
            p.put("job", Rp0918aApi.json(jr));
            p.put("items", items);
            probes.add(p);
            synchronized (probeCost) {
                probeCost[0] += System.currentTimeMillis() - ps;
            }
        });

        long start = System.currentTimeMillis();
        List<UUID> ids = new ArrayList<>();
        for (String m : batch7) ids.add(reviews.get(m));
        UUID job = Rp0918aApi.approveOk(ids);
        String st = Rp0918aApi.awaitJobTerminal(db, job, 300_000);
        long raw = System.currentTimeMillis() - start;
        long adjusted = raw - probeCost[0];
        Rp0918aFixture.sleep(500); // 让批次结束后的日志尾巴落进捕获
        assertEquals("SUCCESS", st, "AC-9②：批次应成功，实际 " + st + " 明细 " + Rp0918aApi.items(job));

        // ① 执行中可见 —— 违例先收集、方法末尾统一断言（让 ②③④ / AC-11 / AC-12 同轮也能取证）
        List<String> step1 = new ArrayList<>();
        StringBuilder pl = new StringBuilder();
        for (int k = 0; k < probes.size(); k++) {
            Map<String, Object> p = probes.get(k);
            JsonNode j = (JsonNode) p.get("job");
            Map<String, Integer> dist = new LinkedHashMap<>();
            String probedStatus = null;
            for (JsonNode it : (JsonNode) p.get("items")) {
                dist.merge(it.path("status").asText(), 1, Integer::sum);
                if (p.get("line").toString().equals(it.path("lineItemId").asText())) probedStatus = it.path("status").asText();
            }
            pl.append("\n  #").append(k).append(" running=").append(j.path("running")).append(" success=")
                .append(j.path("success")).append(" total=").append(j.path("total")).append(" 明细分布=").append(dist)
                .append(" 正在执行那条=").append(probedStatus);
            if (!j.has("running")) step1.add("#" + k + " GET /jobs 缺 running 字段");
            if (j.path("running").asInt(-1) != 1) step1.add("#" + k + " running=" + j.path("running") + "（应为 1）");
            if (j.path("success").asInt(-1) != k) step1.add("#" + k + " success=" + j.path("success") + "（逐条变成功，应为 " + k + "）");
            int done = j.path("success").asInt() + j.path("failed").asInt() + j.path("conflict").asInt()
                + j.path("stale").asInt() + j.path("skipped").asInt();
            int waiting = j.path("total").asInt() - done - j.path("running").asInt();
            if (waiting != 7 - k - 1) step1.add("#" + k + " 等待=" + waiting + "（应为 " + (7 - k - 1) + "）");
            if (waiting != dist.getOrDefault("WAITING", 0)) step1.add("#" + k + " 等待数 " + waiting + " ≠ WAITING 明细数 " + dist.getOrDefault("WAITING", 0));
            if (!"RUNNING".equals(probedStatus)) step1.add("#" + k + " 正在执行的那条状态=" + probedStatus + "（应为 RUNNING）");
        }
        Rp0918aEvidence.log("AC-09", "① 探针 " + probes.size() + " 次：" + pl);
        if (!probes.isEmpty()) Rp0918aEvidence.log("AC-09", "① 执行中 GET /jobs/" + job + " 原始响应（第 1 次探针）：" + probes.get(0).get("jobRaw"));
        if (probes.size() > 1) Rp0918aEvidence.log("AC-09", "① 执行中 GET /jobs/" + job + " 原始响应（第 2 次探针）：" + probes.get(1).get("jobRaw"));
        if (probes.size() != 7) step1.add("探针次数=" + probes.size() + "（7 条明细各应探到一次）");
        Rp0918aEvidence.log("AC-09", "① 违例：" + (step1.isEmpty() ? "无" : step1));

        // ② 全部成功 + 速度
        Map<String, List<JsonNode>> byMat = Rp0918aApi.itemsByMaterial(job);
        for (String m : batch7) {
            assertEquals(1, byMat.getOrDefault(m, List.of()).size(), "料号 " + m + " 应有 1 条明细");
            assertEquals("SUCCESS", byMat.get(m).get(0).path("status").asText(), "AC-9②：" + m + " 应成功");
        }
        double perItem = adjusted / 7.0;
        Rp0918aEvidence.log("AC-09", "② 批次 " + job + " 从通过到完成 " + raw + "ms（扣探针 " + probeCost[0] + "ms 后 "
            + adjusted + "ms），每条 " + perItem + "ms；1.5×T₀=" + (1.5 * t0) + "ms");
        assertTrue(perItem <= 1.5 * t0, "AC-9②：(耗时÷7) 应 ≤ 1.5×T₀（" + (1.5 * t0) + "ms），实际 " + perItem + "ms");

        // ③ 本期记录只有 1 行，已升版料号 = 7 + AC-8 的 1 个
        long curRows = db.count("SELECT count(*) FROM quotation_price_revision WHERE quotation_id = :q AND based_version_id = :v",
            "q", qL.id(), "v", vTarget);
        Revision cur = snaps.current(qL.id(), vTarget);
        assertNotNull(cur, "本期记录缺失");
        Rp0918aEvidence.log("AC-09", "③ 本期记录行数=" + curRows + " 已升版料号(" + cur.upgradedMaterialNos().size() + ")="
            + cur.upgradedMaterialNos() + "（执行前=" + prevUpgraded + "）");
        assertEquals(1, curRows, "AC-9③：该单本期版本记录应只有 1 行");
        Set<String> expectMats = new LinkedHashSet<>(batch7);
        expectMats.add(mFail);
        assertEquals(expectMats, new HashSet<>(cur.upgradedMaterialNos()), "AC-9③：已升版料号应为这 7 个 + " + mFail);
        assertEquals(8, cur.upgradedMaterialNos().size(), "AC-9③：共 8 个且不重复");
        assertEquals(prevUpgraded, cur.upgradedMaterialNos().subList(0, prevUpgraded.size()),
            "问题说明 ⑤-1：已升版料号追加去重、保持原有顺序");

        // ④ 恰好 1 行 kind=CURRENT
        List<Rp0918aLogCapture.RevisionWrite> writes = new ArrayList<>();
        for (Rp0918aLogCapture.RevisionWrite w : logs.revisionWrites(start)) {
            if (qL.id().toString().equals(w.quotationId())) writes.add(w);
        }
        long currentWrites = writes.stream().filter(w -> "CURRENT".equals(w.kind())).count();
        int writerCalls = writer.callsFor(qL.id(), start).size();
        Rp0918aEvidence.log("AC-09", "④ 本批对 QL 的 revision-write 日志：" + writes + "；CurrentPeriodRevisionWriter#write 调用 "
            + writerCalls + " 次");
        assertEquals(1, currentWrites, "AC-9④：本批次对该单的 [perf] revision-write … kind=CURRENT 日志应恰好 1 行");
        assertEquals(1, writerCalls, "AC-9④ 旁证：本批次对该单写本期快照的调用应恰好 1 次");
        for (Rp0918aLogCapture.RevisionWrite w : writes) {
            assertTrue(w.ms() <= 1_000, "AC-13 旁证：≥1000 行单的 revision-write 应 ≤ 1 秒: " + w.raw());
        }

        // AC-11：未通过行逐字节不变；通过行小计 = 通过前抽屉 X
        StringBuilder a11 = new StringBuilder();
        for (UUID l : sample) {
            Map<String, String> after = snaps.lineFourDigests(l);
            a11.append(l).append(" 前=").append(sampleBefore.get(l)).append(" 后=").append(after).append("; ");
            assertEquals(sampleBefore.get(l), after, "AC-11：未通过行 " + l + " 的 4 列 md5 应逐字节不变");
        }
        String untouchedAfter = snaps.untouchedLinesDigest(qL.id(), lines7);
        a11.append("其余全部行聚合 前=").append(untouchedBefore).append(" 后=").append(untouchedAfter).append("; ");
        for (String m : batch7) {
            BigDecimal sub = fx.lineSubtotal(lineOf.get(m));
            a11.append(m).append(" 小计=").append(sub).append(" X=").append(xs.get(m).toPlainString()).append("; ");
            assertNotNull(sub);
            assertEquals(0, xs.get(m).compareTo(sub), "AC-11：通过行 " + m + " 小计应 = 通过前抽屉里的调整后小计");
            assertEquals(0, EXPECTED.compareTo(sub), "通过行 " + m + " 小计应 = 本期价 + 固定额（9 位结果边界）");
        }
        Rp0918aEvidence.log("AC-11", a11.toString());
        assertEquals(untouchedBefore, untouchedAfter, "AC-11 加强：除这 7 行外全部行的 4 列应逐字节不变");

        // AC-12：本期快照三列 = 按口径从原表独立计算
        ObjectNode[] exp = snaps.expectedWholeQuoteSnapshot(qL.id());
        assertEquals(1200, exp[0].size(), "期望快照应覆盖 1200 行（非空样本）");
        String dq = Rp0918aSnapshots.firstDiff(exp[0], cur.quoteCardValues(), "quote_card_values");
        String dc = Rp0918aSnapshots.firstDiff(exp[1], cur.costingCardValues(), "costing_card_values");
        String dr = Rp0918aSnapshots.firstDiff(exp[2], cur.snapshotRows(), "snapshot_rows");
        UUID nullCardLine = qL.lineByOrdinal().get(1198);
        UUID nullCompLine = qL.lineByOrdinal().get(1199);
        UUID noCompLine = qL.lineByOrdinal().get(1200);
        Rp0918aEvidence.log("AC-12", "期望 vs 实际 第一处差异：quote=" + dq + " costing=" + dc + " rows=" + dr
            + "；边界行：卡片值为空行=" + Rp0918aSnapshots.abbreviate(cur.quoteCardValues() == null ? null : cur.quoteCardValues().get(nullCardLine.toString()))
            + " 无页签行=" + Rp0918aSnapshots.abbreviate(cur.snapshotRows() == null ? null : cur.snapshotRows().get(noCompLine.toString()))
            + " component_id 为空行的键=" + (cur.snapshotRows() == null ? null : iterKeys(cur.snapshotRows().get(nullCompLine.toString()))));
        assertNull(dq, "AC-12：报价卡片值快照与独立计算不一致：" + dq);
        assertNull(dc, "AC-12：核价卡片值快照与独立计算不一致：" + dc);
        assertNull(dr, "AC-12：页签行数据快照与独立计算不一致：" + dr);
        assertTrue(cur.quoteCardValues().get(nullCardLine.toString()).isNull(), "AC-12 口径：行卡片值为空 ⇒ JSON null");
        assertEquals(0, cur.snapshotRows().get(noCompLine.toString()).size(), "AC-12 口径：没有组件数据的行 ⇒ {}");
        assertTrue(cur.snapshotRows().get(nullCompLine.toString()).has("null"), "AC-12 口径：component_id 为空 ⇒ 键 \"null\"");
        for (String m : batch7) {
            UUID l = lineOf.get(m);
            assertEquals(Rp0918aSnapshots.parse(fx.lineQuoteCardText(l)), cur.quoteCardValues().get(l.toString()),
                "AC-12：被升版行 " + m + " 的报价卡片值应 = 升版后的 quote_card_values");
        }
        assertTrue(step1.isEmpty(), "AC-9①（执行中可见 / 明细逐条从执行中变成功）违例：" + step1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-10 · AC-10：3 个料号横跨两张大单（1845 行新单 + 1200 行已有版本记录的单）
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @Order(5)
    void t10_threeMaterialsAcrossTwoLargeQuotes() {
        long t0 = requireT0();
        Revision qlCurBefore = snaps.current(qL.id(), vTarget);
        assertNotNull(qlCurBefore, "前提：QL 已有本期记录（前序用例）");
        List<String> qlPrev = qlCurBefore.upgradedMaterialNos();
        assertTrue(snaps.revisions(qA.id()).isEmpty(), "前提：QA 从未升过版");

        long start = System.currentTimeMillis();
        List<UUID> ids = new ArrayList<>();
        for (String m : hot3) ids.add(reviews.get(m));
        Response ar = Rp0918aApi.approve(ids);
        assertEquals(202, ar.statusCode(), ar.asString());
        UUID job = UUID.fromString(Rp0918aApi.json(ar).path("jobId").asText());
        String st = Rp0918aApi.awaitJobTerminal(db, job, 300_000);
        long ms = System.currentTimeMillis() - start;
        Rp0918aFixture.sleep(500);

        JsonNode items = Rp0918aApi.items(job);
        Map<String, Integer> perQuote = new LinkedHashMap<>();
        for (JsonNode it : items) {
            perQuote.merge(it.path("quotationId").asText(), 1, Integer::sum);
            assertEquals("SUCCESS", it.path("status").asText(), "AC-10：明细应全部成功: " + it);
        }
        double perItem = ms / 6.0;
        Rp0918aEvidence.log("AC-10", "approve 响应=" + ar.asString() + "；批次 " + job + " 状态 " + st + " 耗时 " + ms
            + "ms，每条 " + perItem + "ms，1.5×T₀=" + (1.5 * t0) + "；每单明细数=" + perQuote);
        assertEquals(6, items.size(), "AC-10：应有 6 条明细（每单 3 条）");
        assertEquals(3, perQuote.getOrDefault(qA.id().toString(), 0), "QA 3 条");
        assertEquals(3, perQuote.getOrDefault(qL.id().toString(), 0), "QL 3 条");
        assertEquals("SUCCESS", st);
        assertTrue(perItem <= 1.5 * t0, "AC-10：(总耗时÷6) 应 ≤ 1.5×T₀（" + (1.5 * t0) + "ms），实际 " + perItem + "ms");

        long curA = 0;
        long curL = 0;
        for (Rp0918aLogCapture.RevisionWrite w : logs.revisionWrites(start)) {
            if (!"CURRENT".equals(w.kind())) continue;
            if (qA.id().toString().equals(w.quotationId())) curA++;
            if (qL.id().toString().equals(w.quotationId())) curL++;
        }
        Rp0918aEvidence.log("AC-10", "kind=CURRENT 日志：QA " + curA + " 行，QL " + curL + " 行；全部 revision-write="
            + logs.revisionWrites(start));
        assertEquals(1, curA, "AC-10：QA 只应有 1 行 kind=CURRENT");
        assertEquals(1, curL, "AC-10：QL 只应有 1 行 kind=CURRENT");

        List<Revision> revA = snaps.revisions(qA.id());
        Revision initA = snaps.initial(qA.id());
        Revision curAR = snaps.current(qA.id(), vTarget);
        Rp0918aEvidence.log("AC-10", "QA 版本记录 " + revA.size() + " 行：初版 " + (initA == null ? null : initA.revisionNo() + " sealed=" + initA.sealed())
            + "；本期 " + (curAR == null ? null : curAR.revisionNo() + " mats=" + curAR.upgradedMaterialNos()));
        assertEquals(2, revA.size(), "AC-10：QA 应有初版 + 本期各 1 行");
        assertNotNull(initA);
        assertNotNull(curAR);
        assertTrue(initA.sealed(), "AC-10：QA 初版应已定型");
        assertNotEquals(initA.revisionNo(), curAR.revisionNo(), "AC-10：两行 revision_no 不同");
        assertEquals(new HashSet<>(hot3), new HashSet<>(curAR.upgradedMaterialNos()), "QA 本期已升版料号 = 这 3 个");

        long qlCurRows = db.count("SELECT count(*) FROM quotation_price_revision WHERE quotation_id = :q AND based_version_id = :v",
            "q", qL.id(), "v", vTarget);
        Revision qlCur = snaps.current(qL.id(), vTarget);
        Rp0918aEvidence.log("AC-10", "QL 本期行数=" + qlCurRows + " 已升版料号=" + qlCur.upgradedMaterialNos());
        assertEquals(1, qlCurRows, "QL 本期记录仍只有 1 行");
        assertEquals(qlPrev, qlCur.upgradedMaterialNos().subList(0, qlPrev.size()), "追加且保持原有顺序");
        assertEquals(qlPrev.size() + 3, qlCur.upgradedMaterialNos().size(), "追加 3 个且不重复");
        assertTrue(qlCur.upgradedMaterialNos().containsAll(hot3));
    }

    // ═════════════════════════════════════════════════════════════════════
    // T-BE-7 加强 · AC-7：依据单已有版本记录时再开抽屉，版本记录与全部行照样纹丝不动
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @Order(6)
    void t07b_reviewDetailSideEffectFree_whenBasisAlreadyHasRevisions() {
        UUID reviewId = reviews.get(mDetailLater);
        List<String> stampsBefore = snaps.revisionStamps(qL.id());
        String linesBefore = snaps.allLinesSubtotalAndQuoteCardDigest(qL.id());
        String compBefore = snaps.allComponentDataDigest(qL.id());
        assertFalse(stampsBefore.isEmpty(), "前提：QL 此时已有初版 + 本期记录");
        assertNotNull(qlDigestAtT06, "前提：t06 已运行");
        assertNotEquals(qlDigestAtT06, linesBefore, "阳性对照：前序升版后行指纹应已变化 —— 证明该指纹能捕获改动");

        Response r = Rp0918aApi.reviewDetail(reviewId);
        assertEquals(200, r.statusCode(), r.asString());
        JsonNode basis = Rp0918aApi.basisRow(Rp0918aApi.json(r));
        assertNotNull(basis, "依据单行缺失");
        assertTrue(basis.path("adjustedComputed").asBoolean(false), "依据单行应已试算: " + basis);

        List<String> stampsAfter = snaps.revisionStamps(qL.id());
        String linesAfter = snaps.allLinesSubtotalAndQuoteCardDigest(qL.id());
        String compAfter = snaps.allComponentDataDigest(qL.id());
        Rp0918aEvidence.log("AC-07", "（已有版本记录时）版本记录 前=" + stampsBefore + " 后=" + stampsAfter + "；行指纹 前="
            + linesBefore + " 后=" + linesAfter + "；页签数据 前=" + compBefore + " 后=" + compAfter
            + "；阳性对照 t06 行指纹=" + qlDigestAtT06);
        assertEquals(stampsBefore, stampsAfter, "AC-7：版本记录行数与 last_updated_at 应不变");
        assertEquals(linesBefore, linesAfter, "AC-7：全部行 subtotal/quote_card_values 应不变");
        assertEquals(compBefore, compAfter, "AC-7 附加：页签数据应不变");
    }

    // ─────────────────────────────────────────────────────────────────────

    private long requireT0() {
        assertNotNull(t0Millis, "T₀ 未测得（t00 失败）—— 依赖 T₀ 的断言无法成立");
        return t0Millis;
    }

    private static String iterKeys(JsonNode n) {
        if (n == null) return "null";
        List<String> keys = new ArrayList<>();
        n.fieldNames().forEachRemaining(keys::add);
        return keys.toString();
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() > 1500 ? s.substring(0, 1500) + "…(" + s.length() + " chars)" : s);
    }
}
