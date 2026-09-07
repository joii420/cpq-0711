package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-02 / T-03 / T-04 / T-12 —— {@code _record} 的写入语义</b>
 * （AC-2 增量写 · AC-3 {@code extend_column} 只留痕 · AC-4 {@code element_price} · AC-12 价格同步不分叉）
 *
 * <p>⛔ <b>执行前置</b>：13 张 {@code _record} 表须已建成（B-1/B-3 迁移落库）。
 * 未落库时 {@link #requireRecordLayer} 会以「环境前置未满足」的名义<b>硬失败</b> ——
 * 🚫 刻意不用 {@code Assumptions}，因为 skip 在汇总里长得和通过太像
 * （{@code test.md} 风险点 4 的形态）。
 */
@QuarkusTest
@DisplayName("AC-2/3/4/12 · _record 写入语义")
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
     * <p>🚨 空验证对策：断言前先证明<b>两个产品的 {@code _record} 行都存在且非空</b>，
     * 否则「第 2 个产品 updated_at 未变」在它压根没有行的时候恒真。
     */
    @Test
    @DisplayName("T-02 · 只改产品甲 → 产品乙的 _record.updated_at 逐字未变")
    void t02_incrementalRecordWrite() {
        requireRecordLayer();
        Fx fx = newFixture("AC2");

        String matA = PREFIX + "A-" + fx.quotationNo().substring(fx.quotationNo().length() - 6);
        String matB = PREFIX + "B-" + fx.quotationNo().substring(fx.quotationNo().length() - 6);

        // ── 前置：两个产品卡片各自的主表整组 + _record 快照
        seedMainGroup(matA, 3);
        seedMainGroup(matB, 3);
        int verA = mainVersionOf(MBOM, matA);
        int verB = mainVersionOf(MBOM, matB);
        seedRecordFromMain(fx, matA, verA);
        seedRecordFromMain(fx, matB, verB);

        long nA = count("SELECT count(*) FROM " + MBOM_REC + " WHERE material_no = '" + matA + "'");
        long nB = count("SELECT count(*) FROM " + MBOM_REC + " WHERE material_no = '" + matB + "'");
        assertFixtureNonEmpty(nA, "产品甲 " + matA + " 的 _record 行数");
        assertFixtureNonEmpty(nB, "产品乙 " + matB + " 的 _record 行数");

        // ── AC-2③：base_version_no = 拍快照时主表该轴值的 version_no
        assertEquals(verA, intOf("SELECT DISTINCT base_version_no FROM " + MBOM_REC
                        + " WHERE material_no = '" + matA + "'"),
                "AC-2③：产品甲 _record.base_version_no 应 = 拍快照时主表 " + MBOM + " 该轴值的 version_no");

        // ── 采基线：产品乙的 updated_at 逐字快照（🚫 只取 max 会漏「一部分行被重写」）
        List<Object> bBefore = col("SELECT id || '|' || coalesce(updated_at::text,'<NULL>') FROM "
                + MBOM_REC + " WHERE material_no = '" + matB + "' ORDER BY id");
        List<Object> aBefore = col("SELECT id || '|' || coalesce(component_qty::text,'<NULL>') FROM "
                + MBOM_REC + " WHERE material_no = '" + matA + "' ORDER BY id");
        assertFixtureNonEmpty(bBefore.size(), "产品乙 updated_at 基线");

        // ── 操作：只改产品甲的一行的一个数值列，走 saveDraft 的三数组协议 + baseVersion
        //    ⚠️ 本步依赖 saveDraft 把 _record 写入挂上（api.md §3：_record 由 saveDraft 内部写，
        //       前端无感知 ⇒ AC-2 由 DB 断言验证，不由接口验证）。
        long recIdA = longOf("SELECT min(id) FROM " + MBOM_REC + " WHERE material_no = '" + matA + "'");
        saveDraftModifyOneCell(fx, matA, recIdA, "component_qty", "77.5");

        // ── AC-2①：产品甲的行被更新
        List<Object> aAfter = col("SELECT id || '|' || coalesce(component_qty::text,'<NULL>') FROM "
                + MBOM_REC + " WHERE material_no = '" + matA + "' ORDER BY id");
        assertFalse(aBefore.equals(aAfter),
                "AC-2①：产品甲改了值，其 _record 行应被更新，实测逐字未变。before=" + aBefore + " after=" + aAfter);

        // ── AC-2②：产品乙逐字未变（🔑 这条才是「增量」的判据）
        List<Object> bAfter = col("SELECT id || '|' || coalesce(updated_at::text,'<NULL>') FROM "
                + MBOM_REC + " WHERE material_no = '" + matB + "' ORDER BY id");
        assertEquals(bBefore, bAfter,
                "AC-2②：只改了产品甲，产品乙的 _record.updated_at 必须逐字未变（增量写，不是整单重写）。"
                        + "实测 before=" + bBefore + " after=" + bAfter
                        + " ⇒ 若这里变了，说明保存把整单的 _record 全重写了一遍。");
    }

    /**
     * <b>T-03（AC-3）</b>：{@code extend_column} 只留痕，<b>不参与任何写主表的动作</b>。
     *
     * <p>三条断言逐条对应 AC-3 原文：
     * ① 自定义列 / 公式列的值出现在 {@code _record.extend_column} 的 jsonb 里；
     * ② 主表没有新增列，也没有任何一列被这些值写入；
     * ③ 这些值不进 {@code row_fingerprint} —— <b>只改自定义列再保存再通过 → 该组判 {@code UNCHANGED}、
     * {@code version_no} 不变</b>。
     *
     * <p>🔑 断言③是 E-5 证伪实验的靶子（把 {@code extend_column} 塞进指纹 → 本条必须变红）。
     */
    @Test
    @DisplayName("T-03 · 自定义/公式列进 extend_column；只改它们 → UNCHANGED、version_no 不变")
    void t03_extendColumnLeavesNoTraceInMainTable() {
        requireRecordLayer();
        Fx fx = newFixture("AC3");
        String mat = PREFIX + "C-" + shortId(fx);

        seedMainGroup(mat, 3);
        int ver0 = mainVersionOf(MBOM, mat);
        seedRecordFromMain(fx, mat, ver0);

        // 主表列集基线 —— AC-3② 的「主表没有新增列」
        List<Object> mainColsBefore = col("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + MBOM + "' ORDER BY column_name");
        assertFixtureNonEmpty(mainColsBefore.size(), "主表列集基线");

        // ── 在自定义列 / 公式列上填值并保存
        String customValue = PREFIX + "自定义值-" + shortId(fx);
        String formulaValue = "123.456";
        saveDraftFillExtendColumns(fx, mat, java.util.Map.of(
                "自定义列A", customValue,
                "毛利率(公式)", formulaValue));

        // ── AC-3①：值出现在 extend_column jsonb 里
        long hit = count("SELECT count(*) FROM " + MBOM_REC + " WHERE material_no = '" + mat + "' "
                + "AND extend_column::text LIKE '%" + customValue + "%'");
        assertFixtureNonEmpty(hit,
                "AC-3①：自定义列的值 '" + customValue + "' 应出现在 " + MBOM_REC + ".extend_column 里，命中行数");

        // ── AC-3②：主表列集没变，且主表没有任何一列被这些值写入
        List<Object> mainColsAfter = col("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + MBOM + "' ORDER BY column_name");
        assertEquals(mainColsBefore, mainColsAfter,
                "AC-3②：主表 " + MBOM + " 不应因自定义列而新增列");
        long leaked = count("SELECT count(*) FROM " + MBOM + " x WHERE x.material_no = '" + mat + "' "
                + "AND to_jsonb(x)::text LIKE '%" + customValue + "%'");
        assertEquals(0L, leaked,
                "AC-3②：自定义列的值渗进了主表 " + MBOM + " 的某一列（命中 " + leaked + " 行）"
                        + " —— extend_column 应当只留痕，不写主表");

        // ── AC-3③：只改自定义列 → 核价通过后判 UNCHANGED、version_no 不变
        int verBefore = mainVersionOf(MBOM, mat);
        long histBefore = count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat + "'");

        submitAndApprove(fx);

        assertEquals(verBefore, mainVersionOf(MBOM, mat),
                "AC-3③：只改了自定义列（不进 row_fingerprint），该组应判 UNCHANGED、version_no 不变。"
                        + "实测由 " + verBefore + " 变成 " + mainVersionOf(MBOM, mat)
                        + " ⇒ extend_column 被算进指纹了（E-5 靶子）");
        assertEquals(histBefore, count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat + "'"),
                "AC-3③：UNCHANGED 的组不应往 _history 写任何行");
    }

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

    private void seedMainGroup(String materialNo, int rows) {
        inTx(() -> {
            for (int i = 1; i <= rows; i++) {
                insertMaterialBomRow(materialNo, i, PREFIX + "IN-" + i, String.valueOf(i * 10), 1);
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

    private void saveDraftFillExtendColumns(Fx fx, String materialNo, java.util.Map<String, String> values) {
        throw pending("在 " + materialNo + " 的自定义列/公式列上填值并保存：" + values);
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

    private static UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(
                "⛔ 待接实现（**不是被测功能的结论**）：" + what
                        + "。缺的契约信息已在测试回报的「缺什么」清单里列给主线；"
                        + "🚫 刻意不返回空实现 —— 空实现会让本用例在『什么都没做』的情况下跑完并报绿。");
    }
}
