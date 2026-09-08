package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import com.cpq.priceadjust.service.MaterialVersionUpgradeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    /** AC-12 的「价格调整升版作业」入口。按**行项**调用 ⇒ 命中面收窄到本轮夹具。 */
    @Inject
    MaterialVersionUpgradeService upgradeService;

    private static final String MBOM = "ds_quote_material_bom";
    private static final String MBOM_REC = "ds_quote_material_bom_record";
    private static final String EBOM_REC = "ds_quote_element_bom_record";

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>T-22（AC-22 / D-42）🔴 P0 回归网 —— 真实 UI 形状下 {@code _record} 必须写得出来。</b>
     *
     * <h3>它拦的是什么（2026-09-08 主线亲验抓到的 P0）</h3>
     * {@code saveDraft} 处理 {@code added}/{@code modified} 时会<b>删掉整行组件数据再重建</b>，
     * 而重建发生在 {@code QuotationResource} 的 {@code snapshotQuotation}（handler 返回<b>之后</b>）。
     * 原来的 {@code syncRecords} 挂点正落在那个<b>空窗</b>里 ⇒ 查不到组件数据 ⇒
     * 日志恒打 {@code [ds-record] 命中 1 个轴值但无组件数据，跳过} ⇒
     * <b>真实 UI 路径下 {@code _record} 永远是 0 行</b>。
     *
     * <h3>🚨 为什么此前几十条用例一条都没发现它</h3>
     * 三条代理线的夹具<b>全都在 {@code saveDraft} 载荷里直接塞 {@code componentData}</b>。
     * 那种形状下组件数据在 {@code syncRecords} 那一刻<b>事务里是存在的</b> ⇒ 查得到
     * ⇒ <b>旧代码上也会绿</b>。
     * 📌 <b>所有的绿都建立在同一个夹具形状上，而那个形状不是用户的形状。</b>
     * 这正是「亲验必须走用户视角完整路径、不从 API 或单测进」的全部理由。
     *
     * <h3>本用例的形状</h3>
     * {@code added} 只发<b>行本身</b>（{@code tempId}/{@code sortOrder}/{@code productPartNo}/
     * {@code templateId}/{@code annualVolume}），<b>🚫 不带 {@code componentData}</b> ——
     * 组件数据交给服务端物化。这才是「+ 添加产品 → 保存」的真实形状。
     *
     * <p>🔑 <b>还原实验判据</b>：把挂点改回 {@code QuotationService} 内（即 revert {@code e8be83b0}），
     * 本用例<b>必须变红</b>（{@code _record} 0 行）。不变红 = 夹具仍验不到它，本用例作废。
     */
    @Test
    @DisplayName("T-22 · 真实 UI 形状（payload 不带 componentData）→ _record 必须写出来")
    void t22_recordWrittenOnRealUiShape() {
        requireRecordLayer();
        Fx fx = newFixture("AC22");
        String mat = axis("A22");

        // 🔑 主表先要有这一组的 driver 数据，页签物化出来才有行可投影。
        //    🔬 实测：不种基底时，服务端确实物化了 13 行组件数据，但日志是
        //       `绑定解析：入参组件 13，命中 12` + `写入 _record：sheets=0 axes=0 rows=0`
        //       —— 绑定是通的，只是**页签里一行数据都没有**（新料号在主表没有 driver 行）。
        //    那种 0 行**不是** D-42，是夹具没给数据；混在一起会把两件事验成一件。
        seedEbomMainGroup(fx, mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2")), 1);

        // 阳性对照：动手前本单 _record 必须是 0 行，否则「跑完有行了」可能是别人留下的
        assertEquals(0L, recCount(fx, mat), "夹具起点不干净：本单 _record 已有行");

        // 🔴 真实 UI 形状：只发行本身，🚫 不带 componentData
        Response r = saveDraftLineOnly(fx, mat);
        requireStatusBeforeDiff(r, 200, "T-22 saveDraft（真实 UI 形状，无 componentData）");

        // 前提：服务端确实把组件数据物化出来了（否则下面的 0 行是「压根没建卡片」而非 P0）
        long compData = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "'");
        assertFixtureNonEmpty(compData,
                "T-22 前提：服务端应把组件数据物化出来（真实 UI 形状下由 snapshotQuotation 建）。"
                        + "为 0 说明卡片压根没建成 —— 那是夹具问题，不是本条要验的 P0");
        System.out.println("[T-22] 服务端物化组件数据 " + compData + " 行");

        // 🔑 判据：真实 UI 形状下 _record 必须非空
        // 前提 2：页签里确实有行（否则 sheets=0 是「没数据可投影」，不是 D-42）
        long tabRows = count("SELECT coalesce(sum(jsonb_array_length("
                + "coalesce(cd.snapshot_rows, cd.row_data, '[]'::jsonb))),0) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "'");
        assertFixtureNonEmpty(tabRows,
                "T-22 前提：物化出来的页签必须**有行**。为 0 说明主表没有这一组的 driver 数据 "
                        + "⇒ 后面的 _record=0 是「没数据可投影」而不是 D-42，两件事不能混。");
        System.out.println("[T-22] 页签总行数 = " + tabRows);

        long rec = count("SELECT count(*) FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "'");
        System.out.println("[T-22] 本单 " + EBOM + "_record 行数 = " + rec);
        assertTrue(rec > 0,
                "🔴 D-42 回归：真实 UI 形状（payload 不带 componentData）下保存后，"
                        + EBOM + "_record 一行都没有（组件数据已物化 " + compData + " 行）。\n"
                        + "   这正是主线亲验抓到的 P0：syncRecords 挂在 saveDraft 事务内，"
                        + "落在「组件数据被删掉、尚未重建」的空窗里 ⇒ 恒打日志"
                        + "「[ds-record] 命中 N 个轴值但无组件数据，跳过」。\n"
                        + "   ⚠️ 🚫 不要用「往 payload 里塞 componentData」来让它变绿 —— "
                        + "那正是让这个 P0 藏了几十条用例的那个夹具形状。");
    }

    /**
     * <b>T-23（D-43）🔴 第三种夹具形状 —— 「改一格 → 直接提交」这条路上的回归网。</b>
     *
     * <h3>它拦的是什么</h3>
     * 用户在卡片上改一格值后<b>直接提交</b>（前端 {@code handleSubmit} 在
     * {@code waitForPendingEdits()} 之后<b>直接 submit，中间没有 saveDraft</b>），
     * 而 {@code quote-card-edit} 只写 {@code row_data}、<b>不触发 {@code syncRecords}</b>
     * ⇒ 这条路上根本没有挂点 ⇒ {@code _record} 停在旧值
     * ⇒ <b>核价通过回填写进主表的是「改之前的旧值」</b>。
     *
     * <h3>🚨 为什么 T-22 也验不到它（同型的第二次）</h3>
     * {@code AC-22} 系列的夹具<b>都在 {@code saveDraft} 里做完编辑</b> ⇒ D-42 的挂点会接住
     * ⇒ <b>在缺陷态代码上也会绿</b>。
     * ⇒ 必须有<b>第三种形状</b>：{@code ensure-card-values → quote-card-edit → 【直接 submit】
     * → costing-approve}，🚫 中间不许有 {@code saveDraft}。
     *
     * <h3>🔑 判据落在<b>主表</b>，不是 {@code _record}</h3>
     * {@code _record} 有没有新值只是中间态；这条缺陷的<b>实际后果</b>是「回填用旧值」。
     * ⇒ 断言写在「核价通过后主表该行是不是用户改的那个值」上。
     * <p>配对反向：<b>未编辑的那一行必须不受影响</b> —— 只验「新值写进去了」在
     * 「整组被重刷一遍」时照样成立。
     */
    @Test
    @DisplayName("T-23 · 改一格 → 直接提交（无 saveDraft）→ 回填必须写用户改的新值")
    void t23_editThenSubmitDirectlyBackfillsNewValue() {
        requireRecordLayer();
        String mat = axis("A23");
        String el1 = PREFIX + "E1";

        // 基底：主表两行（指纹由 writer 自己算）
        Fx seeder = seedMainViaCreatedOrder("23Seed", mat, List.of(
                new EbomRow(1, el1, "11.1", "1.1"),
                new EbomRow(2, PREFIX + "E2", "22.2", "2.2")));
        Fx fx = newFixtureForCustomer("AC23", seeder);

        // ① 真实 UI 形状建卡片（payload 不带 componentData）
        requireStatusBeforeDiff(saveDraftLineOnly(fx, mat), 200, "T-23 建卡片");

        // ② ensure-card-values —— 🚨 不先调它，quote-card-edit 返 400
        //    「非草稿态或数据缺失」，而那句话把两个原因并在一起，极易被误判成状态问题。
        Response ensure = RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON)
                .when().post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();
        requireStatusBeforeDiff(ensure, 200, "T-23 ensure-card-values");

        Object lineItemId = scalar("SELECT id FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' LIMIT 1");
        assertNotNull(lineItemId, "T-23 前提：找不到 line item");

        // ③ quote-card-edit 改一格：seq1 的「组成含量（%）」11.1 → 77.7
        //
        // 🔑 rowKey **从装配结果里读**，🚫 不在用例里硬编分隔符。
        //    实测分隔符是**双竖线** `||`（我首版拼成单竖线 `|`，于是 200 + 静默 no-op），
        //    但那是 FormulaCalculator.buildRawRowKeys 的**内部约定**：
        //    同族的 uniquifyRowKeys 撞键消歧还会追加 `#序号`，硬编接不住。
        //    ⇒ 写死等于把用例绑在一个可以合法变的实现细节上。
        String rowKey = authoritativeRowKey(fx, el1);
        System.out.println("[T-23] 权威 rowKey（取自 quoteCardValues.tabs[].formulaResults[].rowKey）= " + rowKey);

        String editBody = "{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"rowKey\":" + jsonStr(rowKey) + ","
                + "\"fieldName\":\"组成含量（%）\",\"value\":\"77.7\"}";
        Response edit = RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON)
                .body(editBody).when()
                .put("/api/cpq/quotations/line-items/" + lineItemId + "/quote-card-edit").thenReturn();
        requireStatusBeforeDiff(edit, 200, "T-23 quote-card-edit（rowKey=" + rowKey + "）");

        // 🚨 干预必须先被证明生效。
        //    ⚠️ 这一步不可省：quote-card-edit 对**匹配不上的 rowKey 返 200 且零效果**
        //    （CardSnapshotService.editCardValue 会新建一条 editRows 项，重算时挂不到任何 baseRow）
        //    ⇒ 不证生效的话，「主表还是旧值」可能只是这一格压根没改上，
        //       那会把**夹具错**报成 D-43 复发。
        long inRowData = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND cd.row_data::text LIKE '%77.7%'");
        long inCardValues = count("SELECT count(*) FROM quotation_line_item "
                + "WHERE quotation_id = '" + fx.quotationId() + "' "
                + "  AND quote_card_values::text LIKE '%77.7%'");
        System.out.println("[T-23] 干预落点：row_data=" + inRowData + " 行 / quote_card_values="
                + inCardValues + " 行");
        assertFixtureNonEmpty(inRowData + inCardValues,
                "🚨 干预未生效：quote-card-edit 返 200，但 77.7 哪儿都没落 ⇒ 这一格没改上"
                        + "（rowKey=" + rowKey + "）。此时「主表是旧值」是**夹具错**，"
                        + "🚫 不许报成 D-43 复发。");

        // ④ 🚫 **中间刻意没有 saveDraft** —— 有它 D-42 的挂点就把 _record 补上了，本条就验不到 D-43
        // 🔑 <b>结构性证据</b>（主线送的量具）：{@code bumpUserDataVersion} 是 saveDraft 的**唯一**自增点
        //    ⇒ 编辑与提交前后 user_data_version **不变**，就证明中间没走过**带载荷的** saveDraft。
        //    这比「我没调那个接口」强 —— 后者靠自觉，前者靠不变量。
        //    ⚠️ 精确口径（2026-09-08 主线读实现后收窄）：自增条件是
        //       `delta.hasLinePayload || requestTouchesHeader(request)`
        //       ——「请求带了明细（**哪怕三数组都是空**）或带了任何单头字段」才 bump。
        //       ⇒ 只有字面空 body `{}` 的纯探活调用不自增。
        //       所以这条证明的是「**没走过任何带载荷的 saveDraft**」，
        //       🚫 不要读成「绝对没调过 /draft」。对本用例完全够用：这条路径根本没调 /draft。
        long udvBefore = count("SELECT coalesce(user_data_version,0) FROM quotation WHERE id = '"
                + fx.quotationId() + "'");
        requireStatusBeforeDiff(submit(fx), 200, "T-23 直接提交（中间无 saveDraft）");
        long udvAfter = count("SELECT coalesce(user_data_version,0) FROM quotation WHERE id = '"
                + fx.quotationId() + "'");
        assertEquals(udvBefore, udvAfter,
                "🔑 T-23 的形状证据：user_data_version 在「编辑 → 提交」前后必须不变（都应是 "
                        + udvBefore + "）。变了 ⇒ 中间走过**带载荷的** saveDraft ⇒ D-42 的挂点会把 _record 补上，"
                        + "本用例就退化成验不到 D-43 的空壳。实测 " + udvBefore + " → " + udvAfter);
        System.out.println("[T-23] 形状证据：user_data_version 提交前后均为 " + udvAfter
                + " ⇒ 中间确实没有 saveDraft");

        // ⑤ 核价通过并确认
        approveWithPreview(fx, "AC23");

        // ══ 🔑 判据落在主表 ══
        Map<Integer, String> after = ebomBusinessRowsBySeq(mat);
        System.out.println("[T-23] 核价通过后主表 = " + after);
        assertTrue(String.valueOf(after.get(1)).contains("77.7"),
                "🔴 D-43 回归：用户改一格（11.1 → 77.7）后**直接提交**（中间无 saveDraft），"
                        + "核价通过回填进主表的应当是**用户改的新值 77.7**，实际第 1 行 = " + after.get(1)
                        + "。\n   出现旧值 11.1 = quote-card-edit 那条路上没有 syncRecords 挂点，"
                        + "_record 停在旧值 ⇒ 回填用旧值。\n"
                        + "   ⚠️ 🚫 不要靠「在中间补一次 saveDraft」让它变绿 —— 那正是让这个缺陷"
                        + "藏在 AC-22 夹具背后的那个形状。");
        assertFalse(String.valueOf(after.get(1)).contains("11.1"),
                "D-43 回归 配对：主表第 1 行不该还留着旧值 11.1。实际 " + after.get(1));
        assertTrue(String.valueOf(after.get(2)).contains("22.2"),
                "D-43 反向：未编辑的第 2 行必须不受影响（仍为 22.2）。"
                        + "🚫 只验「新值写进去了」在整组被重刷一遍时照样成立，所以本条不能省。"
                        + "实际 " + after.get(2));
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
        List<Object> aStampBefore = recStamp(fx, matA);
        assertFixtureNonEmpty(bBefore.size(), "产品乙 id|updated_at 基线");
        assertFixtureNonEmpty(aBefore.size(), "产品甲内容基线");

        // ── 操作：只把**产品甲**放进 modified（三数组协议的增量语义），改一个数值列
        List<EbomRow> rowsAChanged = List.of(
                new EbomRow(1, PREFIX + "EA1", "77.5", "1.1"),      // ← 只改这一个数值
                new EbomRow(2, PREFIX + "EA2", "20.0", "2.2"));
        requireStatusBeforeDiff(saveDraftModifiedOnly(fx, matA, rowsAChanged), 200,
                "T-02 二次 saveDraft（modified 只带产品甲）");

        // ── AC-2①：产品甲的行被更新
        //
        // 🚨 2026-09-08 补成对断言（主线 P-2）：只断言②「乙逐字未变」**是不够的** ——
        //    那在「这次保存压根什么都没写」时**同样成立**（D-42 的 P0 正是这个形态：
        //    _record 恒 0 行，于是「乙没变」恒真）。
        //    ⇒ 必须同时断言**甲确实被重写**：id 与 updated_at **双双**变。
        //    🔑 两条合起来才是「增量写」的判据：一个证明「该写的写了」，一个证明「不该写的没动」。
        List<Object> aAfter = recContent(fx, matA);
        assertFalse(aBefore.equals(aAfter),
                "AC-2①：产品甲改了值（content_pct 10.0 → 77.5），其 _record 行应被更新，实测逐字未变。"
                        + "before=" + aBefore + " after=" + aAfter);
        List<Object> aStampAfter = recStamp(fx, matA);
        assertFalse(aStampBefore.equals(aStampAfter),
                "🔑 AC-2① 成对断言：产品甲的 _record 必须**确实被重写**（id 与 updated_at 双双变）。"
                        + "实测逐字未变 ⇒ 本次保存可能一个字节都没写，"
                        + "而那种情况下断言②「乙没变」是**恒真**的 —— 只有这条配上，②才构成判据。"
                        + "before=" + aStampBefore + " after=" + aStampAfter);
        System.out.println("[T-02] AC-2① 成对断言通过：甲被重写 " + aStampBefore + " → " + aStampAfter);
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
    /**
     * <b>T-04（AC-4）</b>：{@code _record.element_price} 建单时算一次。
     *
     * <p>AC 原文两条：① 非空<b>且等于建单时刻该元素的实时价</b>；
     * ② 主表 {@code ds_quote_element_bom} <b>无</b> {@code element_price} 列。
     * 🔑 「等于实时价」必须有<b>对照源</b> —— 只断言非空不够。
     *
     * <h3>🚨 三道前置阳性对照（不加这道闸，夹具问题会被报成产品缺陷）</h3>
     * {@code element_price} 的取值链是
     * {@code driver 展开 → driverRow['元素单价'] → _record.element_price}。
     * 链上任一环没数据，结果都是 NULL，而<b>三种 NULL 长得一模一样</b>：
     * <ol>
     *   <li>{@code snapshot_rows} 为 NULL / 空 ⇒ driver 根本没物化；</li>
     *   <li>{@code driverRow} 缺 {@code 元素单价} 键 ⇒ 该列没进展开结果
     *       （此时才会回落 {@code SqlViewExecutor}，即 {@code AP-38} 那一族的 {@code #ERROR}）；</li>
     *   <li>该元素在 {@code f_material_element_price} 里<b>本来就没价</b>
     *       ⇒ 「等于实时价」退化成 {@code NULL vs NULL}，恒真。</li>
     * </ol>
     * ⇒ 三条都先断言，再谈 {@code element_price}。
     * 📌 与 AC-22 那三条闸同源：2026-09-08 主线漏传 {@code customerTemplateId} ⇒ {@code _record}=0
     * ⇒ 差点报成「挂点没生效」，正是同一个形状。
     *
     * <h3>夹具为什么挂既有客户</h3>
     * 价格来自 {@code f_customer_element_price(customer_no, date)}，实查 laneb 全库<b>只有 4 个客户</b>
     * 配了元素价格策略；新建客户恒返 0 行 ⇒ 第③条闸必然拦下。
     * ⇒ 挂既有客户（主数据），<b>轴值仍是自己的 {@code T260907R-} 前缀</b>，清理只删自己的轴值。
     * 🚫 不改该客户的任何价格策略。
     */
    @Test
    @DisplayName("T-04 · _record.element_price 非空且等于建单时刻实时价；主表无该列")
    void t04_elementPriceSnapshotAtQuoteTime() {
        requireRecordLayer();

        // ── AC-4②：主表**无** element_price 列（与①无依赖，先验，失败也便宜）
        assertEquals(0L, count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + EBOM + "' "
                        + "  AND column_name='element_price'"),
                "AC-4②：主表 " + EBOM + " **不该**有 element_price 列（它只属于 _record）");
        assertFixtureNonEmpty(count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + EBOM + "_record' "
                        + "  AND column_name='element_price'"),
                "AC-4 前置：_record 上应有 element_price 列");

        // ── 夹具：挂有价格策略的既有客户，轴值自己造
        String customerNo = PRICED_CUSTOMER;
        Object custId = scalar("SELECT id FROM customer WHERE code = '" + customerNo + "'");
        assertNotNull(custId, "T-04 前置：找不到有价格策略的客户 " + customerNo
                + "（实查 laneb 仅 4 个客户配了元素价格策略；换库时此前提要重验）");
        String element = pricedElementOf(customerNo);
        String mat = axis("P4");
        Fx owner = new Fx((java.util.UUID) custId, customerNo, null, null);
        // 🔑 先把 driver 数据种进主表：没有它，页签物化出来是 0 行，闸① 会拦下（实测过一次）。
        seedEbomMainGroup(owner, mat, List.of(new EbomRow(1, element, "50.0", "2.4")), 1);
        Fx fx = newFixtureForCustomer("AC4", owner);
        // 🔑 走**真实 UI 形状**（payload 不带 componentData，交服务端物化）——
        //    saveDraftAdded 那条路只落 row_data、不产生 snapshot_rows，闸① 必然拦下。
        // 🔑 必须用**冻结了含价格列视图**的那一版模板（实查 v1.2 才有；v1.0/v1.1 的冻结视图 13 列、无价格 JOIN）
        java.util.UUID tpl = templateWithPricedElementView();
        requireStatusBeforeDiff(saveDraftLineOnly(fx, mat, tpl), 200,
                "T-04 saveDraft（真实 UI 形状，模板 " + tpl + "）");

        // ══ 闸① driver 确实物化了 ══
        long snapLen = count("SELECT coalesce(max(jsonb_array_length(cd.snapshot_rows)),0) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND cd.component_id = '" + COMP_ELEMENT_BOM + "'");
        assertFixtureNonEmpty(snapLen,
                "🚨 闸①：snapshot_rows 为空/NULL ⇒ driver 根本没物化。"
                        + "此时 element_price 必然 NULL，那是**夹具问题**，🚫 不是 AC-4 失败。");

        // ══ 闸② driver 展开结果里确实有「元素单价」这一列 ══
        //    🔑 量的是 snapshot_rows —— **投影真正读的那一份**。
        //    🚫 不量 quote_card_values.baseRows[].driverRow：那份要先调 ensure-card-values 才有，
        //       不调时它是空的，闸② 会以「没有该键」的面目失败，而真正的原因是「那份根本没建」
        //       —— 又一个「三种 NULL 长得一样」（实测踩过一次）。
        long hasKey = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id, "
                + "  LATERAL jsonb_array_elements(coalesce(cd.snapshot_rows,'[]'::jsonb)) r "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND cd.component_id = '" + COMP_ELEMENT_BOM + "' "
                + "  AND jsonb_exists(r->'driverRow', '元素单价')");
        for (Object k : col("SELECT DISTINCT string_agg(kk, ', ' ORDER BY kk) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id, "
                + "  LATERAL jsonb_array_elements(coalesce(cd.snapshot_rows,'[]'::jsonb)) r, "
                + "  LATERAL jsonb_object_keys(r->'driverRow') kk "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND cd.component_id = '" + COMP_ELEMENT_BOM + "'")) {
            System.out.println("[T-04·诊断] snapshot_rows[].driverRow 的键 = " + k);
        }
        assertFixtureNonEmpty(hasKey,
                "🚨 闸②：driver 展开结果（snapshot_rows）里没有「元素单价」这一列 ⇒ 该列没进展开。"
                        + "此时取值会回落 SqlViewExecutor（AP-38 那一族的 #ERROR），"
                        + "结果同样是 NULL —— 那是**夹具/配置问题**，🚫 不是 AC-4 失败。");

        // ══ 闸③ 该元素**确实有价**（否则「等于实时价」是 NULL vs NULL，恒真）══
        Object livePrice = scalar("SELECT unit_price FROM f_material_element_price('"
                + customerNo + "', CURRENT_DATE) WHERE material_no = '" + mat
                + "' AND element_code = '" + element + "' LIMIT 1");
        assertNotNull(livePrice,
                "🚨 闸③：元素 " + element + " 在 f_material_element_price('" + customerNo
                        + "') 里没有价 ⇒ 「等于建单时刻实时价」退化成 NULL vs NULL，**恒真**。"
                        + "此刻的绿不构成任何证据。");
        System.out.println("[T-04] 三道闸通过：snapshot_rows 最长 " + snapLen + " 行 / driverRow 含元素单价 "
                + hasKey + " 行 / 实时价 = " + livePrice);

        // ══ AC-4①：非空 且 等于建单时刻的实时价 ══
        Object recPrice = scalar("SELECT element_price FROM " + EBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' AND material_no = '" + mat + "' "
                + "  AND element_code = '" + element + "' LIMIT 1");
        assertNotNull(recPrice,
                "AC-4①：_record.element_price 不该为空（三道闸已证明 driver 物化了、"
                        + "driverRow 有该键、该元素有价 " + livePrice + "）");
        assertEquals(0, dec(livePrice).compareTo(dec(recPrice)),
                "🔑 AC-4①：_record.element_price 应**等于建单时刻该元素的实时价**。"
                        + "实时价=" + livePrice + " _record=" + recPrice
                        + "（用 BigDecimal.compareTo 比值，🚫 不比字面量 —— 标度不同不算不等）");
        System.out.println("[T-04] AC-4① 通过：_record.element_price=" + recPrice
                + " == 实时价 " + livePrice);
    }

    /**
     * <b>T-12（AC-12）</b>：价格调整改价 → {@code _record.element_price} 与 {@code snapshot_rows} 不分叉。
     *
     * <p>前置：{@code MaterialVersionUpgradeService.ACTIVE_STATUSES}（<b>自行 grep 复核</b> =
     * {@code {DRAFT, SUBMITTED, APPROVED, REJECTED, COSTING_REJECTED}}）中的<b>每一种各一张单</b>。
     * 操作：跑一次价格调整升版作业。
     * 断言：① 每张单两处取值相同；② 被 {@code SKIPPED} 的单<b>两者同时都不变</b>。
     *
     * <h3>🚨 三条守卫（缺一条就有恒真的口子）</h3>
     * <ol>
     *   <li><b>阳性对照</b>：升版作业<b>确实改动了价格</b> —— 价格没变的话「两者相同」在任何实现下都成立。
     *       ⇒ 先断言至少一张 ACTIVE 单的 {@code element_price} <b>发生了变化</b>，再断言一致性。</li>
     *   <li><b>阴性对照的非空守卫</b>：那张状态不在集合里的单<b>必须真的存在且真的被 SKIPPED</b>
     *       （0 张 ⇒ ② 循环 0 次恒真）。</li>
     *   <li><b>比值用 {@code BigDecimal.compareTo}</b>，🚫 不用 {@code equals} —— 标度不同不算不等
     *       （T-04 已踩过同一个坑）。</li>
     * </ol>
     *
     * <p>🔑 <b>② 的措辞是不变量，不是「仍然相等」</b>：断言两个值<b>各自逐字未变</b>。
     * 「两者仍然相等」在「两个都被改成同一个新值」时照样成立 —— 而那正是它要拦的坏情况。
     *
     * <h3>命中面（🚨 共享库纪律）</h3>
     * 价格版本是<b>按客户</b>的，但 {@code material_price_version_ref} 是<b>按客户 × 料号</b>的
     * ⇒ 只把<b>我自己的轴值</b>指向新版本，别的料号一行不受影响。
     * 新建的 {@code element_price_version} 带 {@code T260907R-} 版本号，未被引用即惰性。
     */
    @Test
    @DisplayName("T-12 · 价格调整后 _record.element_price 与 snapshot_rows 不分叉；SKIPPED 单两者同时不变")
    void t12_priceAdjustKeepsRecordAndSnapshotInSync() {
        requireRecordLayer();
        String customerNo = PRICED_CUSTOMER;
        Object custId = scalar("SELECT id FROM customer WHERE code = '" + customerNo + "'");
        assertNotNull(custId, "T-12 前置：找不到有价格策略的客户 " + customerNo);
        String element = pricedElementOf(customerNo);
        java.util.UUID tpl = templateWithPricedElementView();
        Fx owner = new Fx((java.util.UUID) custId, customerNo, null, null);

        String mat = axis("P12");
        seedEbomMainGroup(owner, mat, List.of(new EbomRow(1, element, "50.0", "2.4")), 1);

        // ── 每种 ACTIVE 状态各一张单 + 一张阴性对照（状态**不在**集合里）
        List<String> activeStatuses = List.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "COSTING_REJECTED");
        String skippedStatus = "SENT";   // 不在 ACTIVE_STATUSES 里
        assertFalse(activeStatuses.contains(skippedStatus),
                "T-12 前置：阴性对照的状态必须**不在** ACTIVE_STATUSES 里");

        Map<String, Fx> orders = new java.util.LinkedHashMap<>();
        for (String st : activeStatuses) {
            orders.put(st, newPricedOrder(owner, mat, tpl, st));
        }
        Fx skipped = newPricedOrder(owner, mat, tpl, skippedStatus);
        assertFixtureNonEmpty(orders.size(), "T-12 的 ACTIVE 单数");
        // 守卫②：阴性对照单真的存在且状态真的是集合外的那个
        assertEquals(skippedStatus, String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + skipped.quotationId() + "'")),
                "🚨 守卫②：阴性对照单必须真的处于集合外状态，否则 ② 会循环 0 次恒真");

        // ── 采基线（两处各自的值，🚫 不只采「是否相等」）
        Map<String, String> recBefore = new java.util.LinkedHashMap<>();
        Map<String, String> snapBefore = new java.util.LinkedHashMap<>();
        for (var e : orders.entrySet()) {
            recBefore.put(e.getKey(), recordPrice(e.getValue(), mat));
            snapBefore.put(e.getKey(), snapshotPrice(e.getValue()));
            assertNotNull(recBefore.get(e.getKey()), e.getKey() + " 单的 _record.element_price 基线为空");
            assertNotNull(snapBefore.get(e.getKey()), e.getKey() + " 单的 snapshot_rows 价格基线为空");
        }
        String skRecBefore = recordPrice(skipped, mat);
        String skSnapBefore = snapshotPrice(skipped);
        assertNotNull(skRecBefore, "🚨 守卫②：阴性对照单的 _record 价格基线为空 ⇒「两者同时不变」恒真");
        assertNotNull(skSnapBefore, "🚨 守卫②：阴性对照单的 snapshot_rows 价格基线为空 ⇒ 同上");
        System.out.println("[T-12] 基线 _record=" + recBefore + " / snapshot=" + snapBefore
                + " ；阴性对照(" + skippedStatus + ") _record=" + skRecBefore + " snapshot=" + skSnapBefore);

        // ── 造一个**改了价**的价格版本，并只把我自己的轴值指过去
        java.math.BigDecimal oldPrice = dec(recBefore.values().iterator().next());
        java.math.BigDecimal newPrice = oldPrice.add(new java.math.BigDecimal("111.111"));
        java.util.UUID versionId = seedPriceVersion(customerNo, mat, element, oldPrice, newPrice);

        // ── 跑升版作业（按行项，命中面 = 我自己的单）
        for (var e : orders.entrySet()) runUpgrade(e.getValue(), versionId, e.getKey());
        runUpgrade(skipped, versionId, skippedStatus);

        // ══ 守卫①（阳性对照）：作业**确实改动了价格** ══
        Map<String, String> recAfter = new java.util.LinkedHashMap<>();
        Map<String, String> snapAfter = new java.util.LinkedHashMap<>();
        for (var e : orders.entrySet()) {
            recAfter.put(e.getKey(), recordPrice(e.getValue(), mat));
            snapAfter.put(e.getKey(), snapshotPrice(e.getValue()));
        }
        System.out.println("[T-12] 作业后 _record=" + recAfter + " / snapshot=" + snapAfter);
        boolean anyChanged = orders.keySet().stream()
                .anyMatch(k -> recBefore.get(k) != null && !recBefore.get(k).equals(recAfter.get(k)));
        assertTrue(anyChanged,
                "🚨 守卫①（阳性对照）：升版作业**没有改动任何 ACTIVE 单的 element_price**"
                        + "（" + oldPrice + " → 期望 " + newPrice + "）⇒ 「两者取值相同」在价格没变时"
                        + "在任何实现下都成立，此刻的绿不构成任何证据。before=" + recBefore + " after=" + recAfter);

        // ══ AC-12①：每张 ACTIVE 单两处取值相同 ══
        for (String st : activeStatuses) {
            java.math.BigDecimal r = dec(recAfter.get(st));
            java.math.BigDecimal sp = dec(snapAfter.get(st));
            assertNotNull(r, "AC-12①：状态 " + st + " 的单 _record.element_price 为空");
            assertNotNull(sp, "AC-12①：状态 " + st + " 的单 snapshot_rows 价格为空");
            assertEquals(0, r.compareTo(sp),
                    "AC-12①：状态 " + st + " 的单，_record.element_price 与 snapshot_rows 里的元素价格分叉了。"
                            + "snapshot=" + sp + " record=" + r
                            + "（用 BigDecimal.compareTo 比值，标度不同不算不等）");
        }

        // ══ AC-12②：SKIPPED 的单**两者各自逐字未变** ══
        //    🔑 不是「两者仍然相等」—— 后者在「两个都被改成同一个新值」时照样成立。
        String skRecAfter = recordPrice(skipped, mat);
        String skSnapAfter = snapshotPrice(skipped);
        System.out.println("[T-12] 阴性对照 作业后 _record=" + skRecAfter + " snapshot=" + skSnapAfter);
        assertEquals(skRecBefore, skRecAfter,
                "AC-12②：状态 " + skippedStatus + " 不在 ACTIVE_STATUSES 里、应被 SKIPPED ⇒ "
                        + "_record.element_price 必须**逐字未变**。" + skRecBefore + " → " + skRecAfter);
        assertEquals(skSnapBefore, skSnapAfter,
                "AC-12②：同上，snapshot_rows 里的元素价格必须**逐字未变**。"
                        + skSnapBefore + " → " + skSnapAfter);
        System.out.println("[T-12] AC-12 通过：5 种 ACTIVE 状态两处一致；"
                + skippedStatus + " 单两者各自逐字未变");
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

    /** 实查配了元素价格策略的客户（laneb 全库仅 4 个）。🚫 不改它的任何价格数据。 */
    private static final String PRICED_CUSTOMER = "CUST-0001";

    /** 该客户下**确实有价**的一个元素码 —— 从价格函数实取，🚫 不硬编元素名。 */
    private String pricedElementOf(String customerNo) {
        Object e = scalar("SELECT element_code FROM f_customer_element_price('"
                + customerNo + "', CURRENT_DATE) ORDER BY element_code LIMIT 1");
        assertNotNull(e, "T-04 前置：客户 " + customerNo + " 名下没有任何有价元素 ⇒ 换库后此前提需重验");
        return e.toString();
    }

    /**
     * 取「其冻结视图含 {@code 元素单价} 列」的报价模板。
     * 🚫 <b>不按 version 硬编 v1.2</b> —— 版本号是移动靶；按<b>能力</b>选：
     * 冻结快照里该组件的视图定义含 {@code 元素单价} 就是它。
     */
    private java.util.UUID templateWithPricedElementView() {
        Object id = scalar("SELECT id FROM template "
                + "WHERE template_kind='QUOTATION' AND status='PUBLISHED' "
                + "  AND (sql_views_snapshot->'" + COMP_ELEMENT_BOM + "::builder_196aadeeb89f')::text "
                + "      LIKE '%元素单价%' "
                + "ORDER BY version DESC LIMIT 1");
        assertNotNull(id, "T-04 前置：找不到任何「冻结视图含元素单价列」的已发布报价模板。\n"
                + "  探测口径：template.sql_views_snapshot 的键 '" + COMP_ELEMENT_BOM
                + "::builder_196aadeeb89f'，值里 LIKE '%元素单价%'。\n"
                + "  ⚠️ 组件 id 与视图名是**拼进 jsonb 路径**的：该组件若被换掉，这里会静默返 0 行"
                + "并走到本分支 —— 那时要查的是**组件/视图有没有换**，不是「模板都不合格」。\n"
                + "  实查（2026-09-08 laneb）：仅 v1.2 满足；v1.0/v1.1 冻结的是 13 列旧视图、无价格 JOIN。");
        return java.util.UUID.fromString(id.toString());
    }

    // ═══════════════════════ T-12 夹具 ═══════════════════════

    /** 建一张挂 {@code owner} 客户、指定状态、含有价元素的单。 */
    private Fx newPricedOrder(Fx owner, String mat, java.util.UUID tpl, String status) {
        Fx fx = newFixtureForCustomer("AC12-" + status, owner);
        requireStatusBeforeDiff(saveDraftLineOnly(fx, mat, tpl), 200, "T-12 建单(" + status + ")");
        setQuotationStatus(fx, status);
        return fx;
    }

    private String recordPrice(Fx fx, String mat) {
        Object v = scalar("SELECT element_price FROM " + EBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "' AND material_no = '" + mat + "' LIMIT 1");
        return v == null ? null : v.toString();
    }

    /** {@code snapshot_rows[].driverRow.元素单价} —— AC-12① 的对照侧。 */
    private String snapshotPrice(Fx fx) {
        Object v = scalar("SELECT r->'driverRow'->>'元素单价' "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id, "
                + "  LATERAL jsonb_array_elements(coalesce(cd.snapshot_rows,'[]'::jsonb)) r "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND cd.component_id = '" + COMP_ELEMENT_BOM + "' "
                + "  AND jsonb_exists(r->'driverRow','元素单价') LIMIT 1");
        return v == null ? null : v.toString();
    }

    /**
     * 造一个改了价的元素价格版本，并<b>只把本轮的轴值</b>指过去。
     *
     * <p>🔑 {@code status} 用 {@code SUPERSEDED} 而不是 {@code PENDING}：
     * {@code chk_epv_status} 只允许这两个值，而 {@code uq_epv_customer_pending}
     * 限定<b>每客户至多一个 PENDING</b> —— 用 PENDING 会与该客户既有的 PENDING 版本撞唯一键，
     * 那是<b>污染别人的数据</b>。价格函数按 {@code material_price_version_ref} 取版本、<b>不看 status</b>，
     * 所以 SUPERSEDED 同样生效（下面的「干预生效」断言会当场证明这一点）。
     *
     * <p>🚨 命中面：{@code element_price_version} 按客户建（带 {@code T260907R-} 版本号，
     * 未被引用即惰性），而 {@code material_price_version_ref} 是<b>客户 × 料号</b>
     * ⇒ <b>只有我自己的轴值</b>会读到新价，别的料号一行不受影响。
     */
    private java.util.UUID seedPriceVersion(String customerNo, String mat, String element,
                                            java.math.BigDecimal oldPrice,
                                            java.math.BigDecimal newPrice) {
        java.util.UUID vid = java.util.UUID.randomUUID();
        // version_no 是 varchar(20)：截到 20，🚫 不能直接 substring(0,20)（原串可能不足 20 位）
        String raw = PREFIX + vid.toString().substring(0, 6);
        String vno = raw.length() <= 20 ? raw : raw.substring(0, 20);
        inTx(() -> {
            em.createNativeQuery("INSERT INTO element_price_version "
                            + "(id,customer_no,version_no,base_date,status,trigger_type,created_at) "
                            + "VALUES (:id,:c,:v,CURRENT_DATE,'SUPERSEDED','MANUAL',now())")
                    .setParameter("id", vid).setParameter("c", customerNo).setParameter("v", vno)
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO element_price_version_item "
                            + "(id,version_id,element_code,current_price,previous_price,change_rate,"
                            + " currency,price_unit,no_price,inherited_from_previous,created_at) "
                            + "VALUES (gen_random_uuid(),:vid,:e,CAST(:p AS numeric),CAST(:pp AS numeric),"
                            + "        CAST(:cr AS numeric),'CNY','kg',false,false,now())")
                    .setParameter("vid", vid).setParameter("e", element)
                    .setParameter("p", newPrice.toPlainString())
                    // 🔑 previous_price / change_rate：作业日志打的是「版本明细 1 条（**含无价**）」，
                    //    说明它把「明细里有没有价」当成一个**分支条件** —— 只填 current_price 时
                    //    它仍判「含无价」且改写 0 行。这两个字段是本次要证伪/证实的那一项。
                    .setParameter("pp", oldPrice.toPlainString())
                    .setParameter("cr", newPrice.subtract(oldPrice)
                            .divide(oldPrice, 12, java.math.RoundingMode.HALF_UP).toPlainString())
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO material_price_version_ref (customer_no,material_no,version_id) "
                            + "VALUES (:c,:m,:vid) "
                            + "ON CONFLICT (customer_no,material_no) DO UPDATE SET version_id = EXCLUDED.version_id")
                    .setParameter("c", customerNo).setParameter("m", mat).setParameter("vid", vid)
                    .executeUpdate();
        });
        createdPriceVersions.add(vid);
        createdPriceRefMaterials.add(mat);
        // 🚨 干预生效证明：新价必须真的能从价格函数读出来，否则「作业改了价」无从谈起
        Object live = scalar("SELECT unit_price FROM f_material_element_price('" + customerNo
                + "', CURRENT_DATE) WHERE material_no = '" + mat + "' AND element_code = '"
                + element + "' LIMIT 1");
        assertNotNull(live, "T-12 干预未生效：新价格版本建好后，f_material_element_price 仍取不到价");
        assertEquals(0, newPrice.compareTo(dec(live)),
                "T-12 干预未生效：价格函数返回的仍不是新价（期望 " + newPrice + "，实际 " + live
                        + "）⇒ 后面「作业改了价」无从谈起");
        System.out.println("[T-12] 干预已生效：新价格版本 " + vno + "，f_material_element_price 返 " + live);
        return vid;
    }

    /** 跑一次价格调整升版作业（按行项，命中面 = 这张单）。 */
    private void runUpgrade(Fx fx, java.util.UUID versionId, String what) {
        for (Object li : col("SELECT id FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "'")) {
            var r = upgradeService.upgrade(java.util.UUID.fromString(li.toString()), versionId, false);
            System.out.println("[T-12] 升版(" + what + ") lineItem=" + li + " → " + r.status
                    + (r.message == null ? "" : " / " + r.message));
        }
    }

    private final List<java.util.UUID> createdPriceVersions = new java.util.ArrayList<>();
    private final List<String> createdPriceRefMaterials = new java.util.ArrayList<>();

    /** 清掉本轮造的价格版本与指针（只删自己建的 id / 自己的轴值）。 */
    @AfterEach
    void cleanupPriceFixtures() {
        if (createdPriceVersions.isEmpty() && createdPriceRefMaterials.isEmpty()) return;
        try {
            inTx(() -> {
                for (String m : createdPriceRefMaterials) {
                    em.createNativeQuery("DELETE FROM material_price_version_ref WHERE material_no = :m")
                            .setParameter("m", m).executeUpdate();
                }
                for (java.util.UUID v : createdPriceVersions) {
                    em.createNativeQuery("DELETE FROM element_price_version_item WHERE version_id = :v")
                            .setParameter("v", v).executeUpdate();
                    em.createNativeQuery("DELETE FROM element_price_version WHERE id = :v AND version_no LIKE :p")
                            .setParameter("v", v).setParameter("p", PREFIX + "%").executeUpdate();
                }
            });
        } finally {
            createdPriceVersions.clear();
            createdPriceRefMaterials.clear();
        }
    }

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

    /**
     * 🔴 <b>真实 UI 形状</b>的 {@code PUT /draft}：{@code added} 只发<b>行本身</b>，
     * <b>🚫 不带 {@code componentData}</b>，组件数据交给服务端物化。
     *
     * <p>与 {@link #saveDraftAddedTwo} 的区别就是这一点，而这一点<b>决定了能不能发现 D-42</b>：
     * 带 {@code componentData} 的形状在旧代码上也绿。
     */
    /**
     * 从<b>装配结果</b>里取权威 {@code rowKey}。
     *
     * <p>路径：{@code GET /api/cpq/quotations/{id}} → {@code data.lineItems[].quoteCardValues}
     * （<b>字符串</b>，要再 parse 一次）→ {@code .tabs[]} 按 {@code componentId} 命中 →
     * {@code .formulaResults[].rowKey} —— <b>系统自己算出来的那个值</b>。
     *
     * <p>🚫 <b>刻意不在用例里硬编分隔符</b>。实测是双竖线（如 {@code S-3110520789||00006||Ag}），
     * 但那是 {@code FormulaCalculator.buildRawRowKeys} 的内部约定，
     * 且同族的 {@code uniquifyRowKeys} 撞键消歧还会追加 {@code #序号} —— 硬编接不住。
     * ⇒ 从产物里读，实现怎么变都跟得上。
     *
     * @param elementCode 用来在多行里认出目标行（rowKey 含它）
     */
    private String authoritativeRowKey(Fx fx, String elementCode) {
        Response r = RestAssured.given().cookies(adminCookies())
                .when().get("/api/cpq/quotations/" + fx.quotationId()).thenReturn();
        requireStatusBeforeDiff(r, 200, "T-23 取装配结果");
        JsonNode lineItems = json(r).path("data").path("lineItems");
        assertTrue(lineItems.isArray() && lineItems.size() > 0,
                "T-23：装配结果里没有 lineItems。body=" + r.asString());
        List<String> all = new java.util.ArrayList<>();
        for (JsonNode li : lineItems) {
            JsonNode qcvNode = li.path("quoteCardValues");
            if (qcvNode.isMissingNode() || qcvNode.isNull()) continue;
            JsonNode qcv;
            try {
                // quoteCardValues 是**字符串**，需要再 parse 一次
                qcv = qcvNode.isTextual() ? MAPPER.readTree(qcvNode.asText()) : qcvNode;
            } catch (Exception e) {
                throw new AssertionError("T-23：quoteCardValues 不是合法 JSON：" + qcvNode, e);
            }
            for (JsonNode tab : qcv.path("tabs")) {
                if (!COMP_ELEMENT_BOM.toString().equals(tab.path("componentId").asText())) continue;
                for (JsonNode fr : tab.path("formulaResults")) {
                    String rk = fr.path("rowKey").asText(null);
                    if (rk != null && !rk.isBlank()) all.add(rk);
                }
            }
        }
        assertFixtureNonEmpty(all.size(),
                "T-23：在 quoteCardValues.tabs[componentId=" + COMP_ELEMENT_BOM
                        + "].formulaResults[].rowKey 里一个 rowKey 都没取到 ⇒ "
                        + "要么页签没装配出来，要么该结构变了。🚫 不要退回硬编分隔符。");
        List<String> hit = all.stream().distinct().filter(k -> k.contains(elementCode)).toList();
        assertEquals(1, hit.size(),
                "T-23：按元素 " + elementCode + " 应恰好命中 1 个 rowKey，实际 " + hit
                        + "（全部 = " + all.stream().distinct().toList() + "）");
        return hit.get(0);
    }

    private Response saveDraftLineOnly(Fx fx, String materialNo) {
        return saveDraftLineOnly(fx, materialNo, DS_TEMPLATE_ID);
    }

    /**
     * @param templateId 指定报价模板。
     *
     * <h3>🚨 为什么必须能指定，而不是一律用 {@code DS_TEMPLATE_ID}</h3>
     * 视图定义是<b>按模板冻结</b>的（{@code template.sql_views_snapshot}），
     * 同一个组件在不同模板版本里冻的是<b>不同的视图</b>：
     * <pre>
     *   v1.0 / v1.1 冻结的 builder_196aadeeb89f：13 列，**无** 元素单价 / 货币、**无** 价格 JOIN（1871 字符）
     *   v1.2        冻结的 同一个视图：          15 列，**有** 上述两列与价格 JOIN（2250 字符）
     * </pre>
     * ⇒ 用 v1.0 建的单，driverRow 恒 13 键，{@code element_price} 必然取不到 ——
     * <b>那是夹具选错了模板，不是取价坏了。</b>
     *
     * <p>🕰️ 我在这里栽过一次：先前查「冻结快照假设」时查的是 <b>v1.2</b>（有列），
     * 据此判「快照不是分辨点」，却<b>没查我夹具真正在用的 v1.0</b>。
     * 📌 教训与上一条同族：<b>维度选对了，对象选错了</b> —— 断言「X 解释不了差异」之前，
     * 要确认查的 X 就是这次执行真正用到的那一个。
     */
    private Response saveDraftLineOnly(Fx fx, String materialNo, java.util.UUID templateId) {
        // 🔑 真实 UI 的 Step1 会选模板 ⇒ customerTemplateId 必须透传，
        //    否则服务端不知道该物化哪些组件，snapshotQuotation 建不出页签
        //    （实测：不传时组件数据 0 行，本条的前提守卫会正确地判成「夹具问题」）。
        String body = "{\"baseVersion\":0,\"customerTemplateId\":\"" + templateId + "\",\"added\":[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "ui1\","
                + "\"templateId\":\"" + templateId + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + materialNo + "\","
                + "\"productName\":\"" + PREFIX + "真实UI形状\",\"annualVolume\":1"
                + "}],\"modified\":[],\"removed\":[]}";
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
