package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-02 / T-04 / T-12 —— {@code _record} 的写入语义</b>
 * （T-03/AC-3 已按主线裁决三去重，统一由 {@link PartialColumnScopeAcTest#t03_extendColumnLeavesNoTraceInMainTable} 覆盖）
 * （AC-2 增量写 · AC-4 {@code element_price} · AC-12 价格同步不分叉）
 *
 * <p>⛔ <b>执行前置</b>：13 张 {@code _record} 表须已建成（B-1/B-3 迁移落库）。
 * 未落库时 {@link #requireRecordLayer} 会以「环境前置未满足」的名义<b>硬失败</b> ——
 * 🚫 刻意不用 {@code Assumptions}，因为 skip 在汇总里长得和通过太像
 * （{@code test.md} 风险点 4 的形态）。
 */
@QuarkusTest
@DisplayName("AC-2/4/12 · _record 写入语义")
class RecordWriteAcTest extends Task260907RBase {

    private static final String MBOM = "ds_quote_material_bom";
    private static final String MBOM_REC = "ds_quote_material_bom_record";
    private static final String EBOM_REC = "ds_quote_element_bom_record";

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>T-02（AC-2）</b>：保存时<b>按变更产品增量写</b>，不是整单重写。
     *
     * <p>判据形态取自 AC-2 原文：
     * ① 改了的产品（第 1 个）在 {@code _record} 里的行被更新；
     * ② <b>没改的产品（第 2 个）的 {@code updated_at} 逐字未变</b>；
     * ③ {@code base_version_no} = 拍快照时主表该轴值的 {@code version_no}。
     *
     * <p>🔑 <b>断言②才是这条 AC 的判据</b>。只断言①（「第 1 个产品写进去了」）在整单重写的实现下
     * <b>照样成立</b> —— 同型判据教训见 AC-10 的 🔑 注记与第一段 AC-22。
     *
     * <h3>🕰️ 2026-09-07 重写：从 {@code ds_quote_material_bom} 改挂 {@code ds_quote_element_bom}</h3>
     * 原版用 {@code ds_quote_material_bom} + 三个 {@code pending()} 桩。
     * 「报价模板 · ds 原生 v1.0」的 13 个组件里<b>没有物料BOM</b>（{@link Task260907RBase} 类注释实查）
     * ⇒ 那条路根本走不出 {@code _record}，桩永远接不上。
     * 本条改走已实测跑通的 {@code T260907-物料与元素BOM} → {@code ds_quote_element_bom}。
     *
     * <h3>🚨 空验证对策 + 灵敏度对照（本条不可省）</h3>
     * <ul>
     *   <li>断言前先证明<b>两个产品的 {@code _record} 行都存在且非空</b> ——
     *       否则「产品乙 updated_at 未变」在它压根没有行的时候<b>恒真</b>。</li>
     *   <li>断言②通过之后，再做一次<b>阳性对照（还原实验）</b>：把产品乙也放进 {@code modified}
     *       <b>并真的改掉它的值</b>，断言此时乙的 {@code id|updated_at} 快照<b>必须变</b>。
     *       🔑 少了这一枪，「乙没变」与「我的查询/比对压根观测不到变化」<b>在结果上分不开</b> ——
     *       后者会让本用例在任何实现下都绿。</li>
     * </ul>
     */
    @Test
    @DisplayName("T-02 · 只改产品甲 → 产品乙的 _record.updated_at 逐字未变")
    void t02_incrementalRecordWrite() {
        requireRecordLayer();
        Fx fx = newFixture("AC2");

        String matA = axis("A2A");
        String matB = axis("A2B");
        List<EbomRow> rowsA = List.of(
                new EbomRow(1, PREFIX + "EA1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "EA2", "20.0", "2.2"));
        List<EbomRow> rowsB = List.of(
                new EbomRow(1, PREFIX + "EB1", "30.0", "3.3"),
                new EbomRow(2, PREFIX + "EB2", "40.0", "4.4"));

        // ── 前置：两个产品各自的主表整组（v1）—— AC-2③ 的对照源
        seedEbomMainGroup(fx, matA, rowsA, 1);
        seedEbomMainGroup(fx, matB, rowsB, 1);

        // ── 操作 0：一次 saveDraft 建出**两个**产品卡片（AC-2 前置「含 ≥2 个产品卡片」）
        requireStatusBeforeDiff(saveDraftAddedTwo(fx, matA, rowsA, matB, rowsB), 200,
                "T-02 首次 saveDraft（两个产品卡片）");

        long nA = recCount(fx, matA);
        long nB = recCount(fx, matB);
        assertFixtureNonEmpty(nA, "产品甲 " + matA + " 的 _record 行数");
        assertFixtureNonEmpty(nB, "产品乙 " + matB + " 的 _record 行数");
        System.out.println("[T-02] 夹具就位：甲 " + matA + " _record " + nA + " 行；乙 " + matB + " _record " + nB + " 行");

        // ── AC-2③：base_version_no = 拍快照时主表该轴值的 version_no
        assertEquals(1, baseVersionOf(fx, matA),
                "AC-2③：主表 " + EBOM + " 上 " + matA + " 当前 version_no=1，"
                        + "故产品甲 _record.base_version_no 应为 1");
        assertEquals(1, baseVersionOf(fx, matB),
                "AC-2③：产品乙 _record.base_version_no 应 = 拍快照时主表该轴值的 version_no");

        // ── 采基线：逐行 id|updated_at（🚫 只取 max(updated_at) 会漏「一部分行被重写」，
        //    也漏「行被删了重插、id 换掉」这一整类）
        List<Object> bBefore = recStamp(fx, matB);
        List<Object> aBefore = recContent(fx, matA);
        assertFixtureNonEmpty(bBefore.size(), "产品乙 id|updated_at 基线");
        assertFixtureNonEmpty(aBefore.size(), "产品甲内容基线");

        // ── 操作：只把**产品甲**放进 modified（三数组协议的增量语义），改一个数值列
        List<EbomRow> rowsAChanged = List.of(
                new EbomRow(1, PREFIX + "EA1", "77.5", "1.1"),      // ← 只改这一个数值
                new EbomRow(2, PREFIX + "EA2", "20.0", "2.2"));
        requireStatusBeforeDiff(saveDraftModifiedOnly(fx, matA, rowsAChanged), 200,
                "T-02 二次 saveDraft（modified 只带产品甲）");

        // ── AC-2①：产品甲的行被更新
        List<Object> aAfter = recContent(fx, matA);
        assertFalse(aBefore.equals(aAfter),
                "AC-2①：产品甲改了值（content_pct 10.0 → 77.5），其 _record 行应被更新，实测逐字未变。"
                        + "before=" + aBefore + " after=" + aAfter);
        assertTrue(aAfter.toString().contains("77.5"),
                "AC-2① 反向：产品甲的新值 77.5 应落进 _record（防止修成「什么都不写」），实际=" + aAfter);

        // ── 🔑 AC-2②：产品乙逐字未变（这条才是「增量」的判据）
        List<Object> bAfter = recStamp(fx, matB);
        assertEquals(bBefore, bAfter,
                "🔑 AC-2②：只改了产品甲，产品乙的 _record 行（id|updated_at）必须逐字未变 ——"
                        + " 增量写，不是整单重写。实测 before=" + bBefore + " after=" + bAfter
                        + " ⇒ 若这里变了，说明保存把整单的 _record 全重写了一遍。");
        System.out.println("[T-02] AC-2② 通过：乙 " + matB + " 逐字未变 = " + bAfter);

        // ══ 🚨 阳性对照（还原实验）：证明断言②**能够**变红 ══
        //    把产品乙也放进 modified 并**真的改掉它的值**。此时乙必须变。
        //    不变 ⇒ 说明我的查询/比对根本观测不到变化 ⇒ 上面那个「相等」是空验证，本用例作废。
        List<EbomRow> rowsBChanged = List.of(
                new EbomRow(1, PREFIX + "EB1", "88.8", "3.3"),
                new EbomRow(2, PREFIX + "EB2", "40.0", "4.4"));
        requireStatusBeforeDiff(saveDraftModifiedOnly(fx, matB, rowsBChanged), 200,
                "T-02 阳性对照 saveDraft（modified 带产品乙且真的改了值）");
        List<Object> bControl = recStamp(fx, matB);
        assertFalse(bBefore.equals(bControl),
                "🚨 灵敏度对照失败：产品乙的值真的被改了（30.0 → 88.8），但它的 _record id|updated_at 快照"
                        + "仍逐字未变 ⇒ 本用例的判据**观测不到变化**，上面 AC-2② 的「相等」是空验证，"
                        + "这条用例不构成任何证据。before=" + bBefore + " control=" + bControl);
        System.out.println("[T-02] 灵敏度对照通过：乙真改值后 id|updated_at 确实变了 = " + bControl);
    }

    // ═══════════════════════ T-03（AC-3）已删除 —— 🚫 不是放弃覆盖，是去重 ═══════════════════════
    //
    // 🕰️ 2026-09-07 主线裁决三：AC-3 的覆盖统一由
    //    {@link PartialColumnScopeAcTest#t03_extendColumnLeavesNoTraceInMainTable} 承担。
    //
    // 为什么删这一份而不是那一份：
    //   · 本份挂在 **ds_quote_material_bom** 上 —— 「报价模板 · ds 原生 v1.0」的 13 个组件里
    //     没有物料BOM，这条路根本拍不出 _record ⇒ 它**恒 pending，永远接不上**；
    //   · PartialColumnScopeAcTest 那份走的是**自造部分列组件** + ds 原生链路，已实测跑通并绿。
    //
    // 🚨 为什么「留着一个永远 pending 的同名同断言用例」本身是缺陷：
    //    同一条 AC 被两个类覆盖，报告里一边写「⛔ 待接实现」、一边写「✅ 全绿」，
    //    读报告的人**无法判断哪个是真的**，而 pending 那份看起来像真实的覆盖缺口。
    //    这与 T-20a/b/c 从本包迁出时的理由是同一个（见 BackfillAnchorAndSequenceAcTest 的迁出注记）。

    /**
     * <b>T-04（AC-4）</b>：{@code element_price} 建单时算一次。
     *
     * <p>断言：{@code ds_quote_element_bom_record.element_price} <b>非空</b>，
     * 且等于建单时刻该元素的实时价；主表 {@code ds_quote_element_bom} <b>无</b> {@code element_price} 列
     * （D-6：它是销售报价时的元素实时价格快照，不是主数据）。
     */
    @Test
    @DisplayName("T-04 · _record.element_price 非空且=建单时刻实时价；主表无该列")
    void t04_elementPriceSnapshotAtQuoteTime() {
        requireRecordLayer();

        // ── 反向断言先做：主表不得有 element_price 列（不依赖任何夹具，恒可执行）
        long onMain = count("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='ds_quote_element_bom' "
                + "  AND column_name='element_price'");
        assertEquals(0L, onMain,
                "AC-4：主表 ds_quote_element_bom 不应有 element_price 列（D-6：它是报价时的价格快照，不是主数据）");

        long onRecord = count("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + EBOM_REC + "' AND column_name='element_price'");
        assertEquals(1L, onRecord,
                "AC-4 / S-3：" + EBOM_REC + " 应有 element_price 列，实测 " + onRecord + " 列");

        // ── 正向：本次夹具建出的 _record 行，element_price 必须非空且 = 建单时刻实时价
        Fx fx = newFixture("AC4");
        String mat = PREFIX + "E-" + shortId(fx);
        seedElementBomGroup(mat);
        createQuotationWithElementBom(fx, mat);

        long rowsForFx = count("SELECT count(*) FROM " + EBOM_REC
                + " WHERE quotation_id = '" + fx.quotationId() + "'");
        assertFixtureNonEmpty(rowsForFx, "本单在 " + EBOM_REC + " 的行数");

        long nullPrice = count("SELECT count(*) FROM " + EBOM_REC
                + " WHERE quotation_id = '" + fx.quotationId() + "' AND element_price IS NULL");
        assertEquals(0L, nullPrice,
                "AC-4：本单 " + EBOM_REC + " 有 " + nullPrice + " / " + rowsForFx
                        + " 行 element_price 为空。⚠️ 「非空」这条不能靠「一行都没有」来满足 —— "
                        + "上面的非空守卫已先证明本单确有 " + rowsForFx + " 行。");

        // 与建单时刻实时价一致：逐行比对 _record.element_price 与价格来源的当时取值
        List<Object[]> mismatch = rows(
                "SELECT r.id, r.element_price, p.price FROM " + EBOM_REC + " r "
                        + "JOIN " + liveElementPriceSource() + " p ON p.element_no = r.element_no "
                        + "WHERE r.quotation_id = '" + fx.quotationId() + "' "
                        + "  AND r.element_price IS DISTINCT FROM p.price");
        assertTrue(mismatch.isEmpty(),
                "AC-4：以下行的 _record.element_price 与建单时刻实时价不一致（id / record 值 / 实时价）："
                        + fmt(mismatch));
    }

    /**
     * <b>T-12（AC-12）</b>：价格调整改价后 {@code _record.element_price} 与 {@code snapshot_rows}
     * <b>不分叉</b>。
     *
     * <p>AC-12 原文：状态取 {@code MaterialVersionUpgradeService.ACTIVE_STATUSES}
     * （{@code {DRAFT, SUBMITTED, APPROVED, REJECTED, COSTING_REJECTED}}）中的<b>每一种各一张单</b>；
     * ① 每张单两处取值相同；② 被 {@code SKIPPED} 的单（状态不在 {@code ACTIVE_STATUSES}）
     * <b>两者同时都不变</b> —— 🚫 不许出现「一个变了一个没变」。
     *
     * <p>🔑 断言②的形态很关键：它不是「都变」也不是「都不变」，而是<b>两者的变/不变必须一致</b>。
     * 这正是 E-6 证伪实验的靶子（只写 {@code snapshot_rows} 不写 {@code _record} → 本条必须变红）。
     */
    @Test
    @DisplayName("T-12 · ACTIVE_STATUSES 五态各一单；_record.element_price 与 snapshot_rows 不分叉")
    void t12_priceAdjustKeepsRecordAndSnapshotInSync() {
        requireRecordLayer();

        List<String> activeStatuses = List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "COSTING_REJECTED");
        // SKIPPED 对照组：取一个明确不在 ACTIVE_STATUSES 里的状态
        String skippedStatus = "SENT";

        java.util.Map<String, Fx> fxs = new java.util.LinkedHashMap<>();
        for (String st : activeStatuses) {
            Fx fx = newFixture("AC12-" + st);
            String mat = PREFIX + "P-" + st + "-" + shortId(fx);
            seedElementBomGroup(mat);
            createQuotationWithElementBom(fx, mat);
            setQuotationStatus(fx, st);
            fxs.put(st, fx);
        }
        Fx skipped = newFixture("AC12-SKIPPED");
        String skippedMat = PREFIX + "P-SKIP-" + shortId(skipped);
        seedElementBomGroup(skippedMat);
        createQuotationWithElementBom(skipped, skippedMat);
        setQuotationStatus(skipped, skippedStatus);

        // ── 采基线（🚨 先证明每张单两处都有值，否则「相同」会在双空时恒真）
        java.util.Map<String, String> recBefore = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> snapBefore = new java.util.LinkedHashMap<>();
        for (var e : fxs.entrySet()) {
            recBefore.put(e.getKey(), recordPrices(e.getValue()));
            snapBefore.put(e.getKey(), snapshotPrices(e.getValue()));
            assertNotNull(recBefore.get(e.getKey()), e.getKey() + " 单的 _record.element_price 基线为空");
            assertNotNull(snapBefore.get(e.getKey()), e.getKey() + " 单的 snapshot_rows 价格基线为空");
        }
        String skRecBefore = recordPrices(skipped);
        String skSnapBefore = snapshotPrices(skipped);
        assertNotNull(skRecBefore, "SKIPPED 对照单的 _record 价格基线为空 ⇒ 「两者同时不变」会恒真");
        assertNotNull(skSnapBefore, "SKIPPED 对照单的 snapshot_rows 价格基线为空 ⇒ 同上");

        // ── 操作：跑一次价格调整升版作业
        runPriceAdjustJob();

        // ── AC-12①：每张 ACTIVE 单，两处取值相同
        for (var e : fxs.entrySet()) {
            String rec = recordPrices(e.getValue());
            String snap = snapshotPrices(e.getValue());
            assertEquals(snap, rec,
                    "AC-12①：状态 " + e.getKey() + " 的单，_record.element_price 与 snapshot_rows 里的元素价格分叉了。"
                            + "snapshot=" + snap + " record=" + rec);
        }

        // ── AC-12②：SKIPPED 的单，两者「变/不变」必须一致
        String skRecAfter = recordPrices(skipped);
        String skSnapAfter = snapshotPrices(skipped);
        boolean recChanged = !skRecBefore.equals(skRecAfter);
        boolean snapChanged = !skSnapBefore.equals(skSnapAfter);
        assertEquals(snapChanged, recChanged,
                "AC-12②：被 SKIPPED（状态 " + skippedStatus + "）的单出现了「一个变了一个没变」——"
                        + " snapshot_rows 变化=" + snapChanged + "（" + skSnapBefore + " → " + skSnapAfter + "）；"
                        + " _record.element_price 变化=" + recChanged + "（" + skRecBefore + " → " + skRecAfter + "）");
        assertFalse(recChanged,
                "AC-12②：被 SKIPPED 的单两者都应不变，实测 _record.element_price 变了："
                        + skRecBefore + " → " + skRecAfter);
    }

    // ═══════════════════════ 夹具与操作（⛔ 待实现落地后按 api.md 补实现体）═══════════════════════
    //
    // 🚫 这些方法**刻意不去读实现**来补全。当前缺的信息已在回报里列成「缺什么」清单交主线：
    //    ① _record 的「基版指纹」列名（api.md 暴露 baseRowFingerprint，AC-1② 的列清单里没有它）
    //    ② 主表「来源报价单 id」列名（AC-8 只说「来源报价单 id 列」，未给列名；下面用**内容发现法**规避）
    //    ③ saveDraft 里承载 _record 写入的请求体形状（api.md §3 明写「不改 saveDraft 契约」，
    //       ⇒ 需要真实模板 + 组件 + 页签夹具，不是造几行 SQL 能替代的）
    //    ④ 价格调整作业的触发入口（AC-12 只引用 MaterialVersionUpgradeService.ACTIVE_STATUSES）

    private void seedMainGroup(String customerNo, String materialNo, int rows) {
        inTx(() -> {
            for (int i = 1; i <= rows; i++) {
                insertMaterialBomRow(customerNo, materialNo, i, PREFIX + "IN-" + i, String.valueOf(i * 10), 1);
            }
        });
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + materialNo + "'"),
                "夹具主表组 " + materialNo + " 行数");
    }

    private int mainVersionOf(String table, String materialNo) {
        Object v = scalar("SELECT max(version_no) FROM " + sqlSafe(table)
                + " WHERE material_no = '" + materialNo + "'");
        assertNotNull(v, "主表 " + table + " 上找不到轴值 " + materialNo + " ⇒ 夹具没造出来");
        return ((Number) v).intValue();
    }

    private int intOf(String sql) {
        Object v = scalar(sql);
        assertNotNull(v, "查询无结果，断言会空跑：" + sql);
        return ((Number) v).intValue();
    }

    private long longOf(String sql) {
        Object v = scalar(sql);
        assertNotNull(v, "查询无结果，断言会空跑：" + sql);
        return ((Number) v).longValue();
    }

    private String shortId(Fx fx) {
        return fx.quotationId().toString().substring(0, 6);
    }

    private static String fmt(List<Object[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : rs) sb.append(java.util.Arrays.toString(r)).append(' ');
        return sb.toString();
    }

    // ⛔ 以下五个是「待接实现」的挂载点。落地前调用它们会以 UnsupportedOperationException
    //    的形式**硬失败并说清缺什么**，🚫 不返回空实现 —— 空实现会让上面的断言在「什么都没做」
    //    的情况下照样跑完，那正是最典型的假绿。

    private void seedRecordFromMain(Fx fx, String materialNo, int baseVersion) {
        throw pending("按主表整组内容为报价单 " + fx.quotationNo() + " 拍一份 _record 快照"
                + "（materialNo=" + materialNo + ", base_version_no=" + baseVersion + "）");
    }

    private void saveDraftModifyOneCell(Fx fx, String materialNo, long recordId, String column, String value) {
        throw pending("走 saveDraft 三数组协议 + baseVersion，只改 " + materialNo
                + " 的 record#" + recordId + "." + column + " = " + value);
    }

    private void seedElementBomGroup(String materialNo) {
        throw pending("造 ds_quote_element_bom 夹具组 " + materialNo + "（含可解析实时价的元素）");
    }

    private void createQuotationWithElementBom(Fx fx, String materialNo) {
        throw pending("为 " + fx.quotationNo() + " 建含物料与元素BOM 页签的产品卡片（轴值 " + materialNo + "）");
    }

    private void setQuotationStatus(Fx fx, String status) {
        inTx(() -> em.createNativeQuery("UPDATE quotation SET status = :s WHERE id = :q AND quotation_number LIKE :p")
                .setParameter("s", status).setParameter("q", fx.quotationId())
                .setParameter("p", PREFIX + "%").executeUpdate());
        assertEquals(status, String.valueOf(scalar("SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")),
                "夹具状态没设上 ⇒ AC-12 的「五态各一张」不成立");
    }

    private void submitAndApprove(Fx fx) {
        throw pending("提交 " + fx.quotationNo() + " → 预览 → 带 previewToken 确认核价通过");
    }

    private void runPriceAdjustJob() {
        throw pending("触发一次价格调整升版作业（AC-12 操作步骤）");
    }

    private String liveElementPriceSource() {
        throw pending("元素实时价的来源关系名（AC-4「建单时刻该元素的实时价」的对照源）");
    }

    private String recordPrices(Fx fx) {
        Object v = scalar("SELECT string_agg(element_price::text, ',' ORDER BY id) FROM " + EBOM_REC
                + " WHERE quotation_id = '" + fx.quotationId() + "'");
        return v == null ? null : v.toString();
    }

    private String snapshotPrices(Fx fx) {
        throw pending("从 " + fx.quotationNo() + " 的 quotation_line_component_data.snapshot_rows 里"
                + "取对应的元素价格字段（AC-12① 的对照侧）");
    }

    // ═══════════════════════ T-02 专用工具（ds 原生链路，2026-09-07 接实现）═══════════════════════

    private String axis(String tag) {
        return PREFIX + tag + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
    }

    private long recCount(Fx fx, String materialNo) {
        return count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND material_no = '" + materialNo + "'");
    }

    /** 逐行 {@code id|updated_at} —— 「行被 touch」与「行被删了重插（id 换）」两类都抓得到。 */
    private List<Object> recStamp(Fx fx, String materialNo) {
        return col("SELECT id || '|' || coalesce(updated_at::text,'<NULL>') FROM " + EBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + materialNo
                + "' ORDER BY id");
    }

    /** 逐行业务内容（不含 id / 时间戳）—— 用于「值确实写进去了」这一侧。 */
    private List<Object> recContent(Fx fx, String materialNo) {
        return col("SELECT coalesce(item_seq::text,'~') || '|' || coalesce(element_code,'~') || '|' "
                + "|| coalesce(content_pct::text,'~') || '|' || coalesce(net_usage::text,'~') "
                + "FROM " + EBOM + "_record WHERE quotation_id = '" + fx.quotationId()
                + "' AND material_no = '" + materialNo + "' ORDER BY item_seq, id");
    }

    private int baseVersionOf(Fx fx, String materialNo) {
        Object v = scalar("SELECT DISTINCT base_version_no FROM " + EBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + materialNo + "'");
        assertNotNull(v, "本单在 _record 上没有轴值 " + materialNo + " 的行 ⇒ 快照没拍成，断言会空跑");
        return ((Number) v).intValue();
    }

    /** 一次 {@code PUT /draft} 建出<b>两个</b>产品卡片（AC-2 前置「含 ≥2 个产品卡片」）。 */
    private Response saveDraftAddedTwo(Fx fx, String matA, List<EbomRow> rowsA,
                                       String matB, List<EbomRow> rowsB) {
        String body = "{\"baseVersion\":0,\"added\":["
                + addedLine("t1", 0, matA, rowsA) + "," + addedLine("t2", 1, matB, rowsB)
                + "],\"modified\":[],\"removed\":[]}";
        return putDraft(fx, body);
    }

    private String addedLine(String tempId, int sortOrder, String materialNo, List<EbomRow> rows) {
        return "{\"id\":null,\"tempId\":\"" + PREFIX + tempId + "\","
                + "\"templateId\":\"" + DS_TEMPLATE_ID + "\","
                + "\"sortOrder\":" + sortOrder + ",\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + materialNo + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                + "\"rowData\":" + jsonStr(ebomRowData(materialNo, rows)) + ",\"sortOrder\":0}]}";
    }

    /**
     * {@code modified} 数组里<b>只放一个</b>产品卡片 —— 三数组协议的增量语义。
     * 🔑 T-02 的判据完全建立在这一点上：客户端只发变更的那个产品，服务端就<b>只该</b>动那个产品的 {@code _record}。
     */
    private Response saveDraftModifiedOnly(Fx fx, String materialNo, List<EbomRow> rows) {
        Object liId = scalar("SELECT id FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' AND product_part_no_snapshot = '" + materialNo + "' LIMIT 1");
        assertNotNull(liId, "找不到 " + materialNo + " 的 line item ⇒ 夹具没建成，后面的断言会空跑");
        long ver = count("SELECT coalesce(user_data_version,0) FROM quotation WHERE id = '"
                + fx.quotationId() + "'");
        String body = "{\"baseVersion\":" + ver + ",\"added\":[],\"modified\":[{"
                + "\"id\":\"" + liId + "\",\"templateId\":\"" + DS_TEMPLATE_ID + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + materialNo + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                + "\"rowData\":" + jsonStr(ebomRowData(materialNo, rows)) + ",\"sortOrder\":0}]}],"
                + "\"removed\":[]}";
        return putDraft(fx, body);
    }

    private Response putDraft(Fx fx, String jsonBody) {
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(jsonBody)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }

    private static UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(
                "⛔ 待接实现（**不是被测功能的结论**）：" + what
                        + "。缺的契约信息已在测试回报的「缺什么」清单里列给主线；"
                        + "🚫 刻意不返回空实现 —— 空实现会让本用例在『什么都没做』的情况下跑完并报绿。");
    }
}
