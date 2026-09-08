package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
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
        //    🔑 rowKey = row_key_fields 三个字段的**值**（实查该组件 = ["销售料号","材质料号","元素"]），
        //       🚫 不是「料号#项次」。
        String rowKey = mat + "|" + PREFIX + "MAT" + "|" + el1;
        String editBody = "{\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"rowKey\":" + jsonStr(rowKey) + ","
                + "\"fieldName\":\"组成含量（%）\",\"value\":\"77.7\"}";
        Response edit = RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON)
                .body(editBody).when()
                .put("/api/cpq/quotations/line-items/" + lineItemId + "/quote-card-edit").thenReturn();
        requireStatusBeforeDiff(edit, 200, "T-23 quote-card-edit（rowKey=" + rowKey + "）");

        // 🔬 诊断：把 quote_card_values 的真实行键结构打出来（rowKey 格式是本条唯一的未知数）
        for (Object d : col("SELECT jsonb_object_keys(t.tab->'baseRows'->0) "
                + "FROM quotation_line_item li, jsonb_array_elements(li.quote_card_values->'tabs') t(tab) "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND t.tab->>'componentId' = '" + COMP_ELEMENT_BOM + "' "
                + "  AND jsonb_array_length(t.tab->'baseRows') > 0")) {
            System.out.println("[T-23·诊断] baseRows[0] 的键 = " + d);
        }
        for (Object d : col("SELECT t.tab->'baseRows'->0->>'rowKey' "
                + "FROM quotation_line_item li, jsonb_array_elements(li.quote_card_values->'tabs') t(tab) "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND t.tab->>'componentId' = '" + COMP_ELEMENT_BOM + "'")) {
            System.out.println("[T-23·诊断] baseRows[0].rowKey = " + d);
        }
        for (Object[] d : rows("SELECT cd.component_id, left(coalesce(cd.row_data::text,'<NULL>'),400) "
                + "FROM quotation_line_component_data cd JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' AND cd.component_id = '"
                + COMP_ELEMENT_BOM + "'")) {
            System.out.println("[T-23·诊断] row_data(" + d[0] + ") = " + d[1]);
        }

        // 🚨 干预必须先被证明生效：row_data 里确实出现了 77.7。
        //    不证这一步，「主表还是旧值」可能只是**我这一格压根没改上**（rowKey 拼错就会这样），
        //    那会把「夹具错」报成 D-43 复发。
        //    📌 顺带回答主线的问题：后端内容键格式与本 rowKey 拼法**逐字对得上**（对不上这里就 0 命中）。
        long inRowData = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' "
                + "  AND cd.row_data::text LIKE '%77.7%'");
        long inCardValues = count("SELECT count(*) FROM quotation_line_item "
                + "WHERE quotation_id = '" + fx.quotationId() + "' "
                + "  AND quote_card_values::text LIKE '%77.7%'");
        System.out.println("[T-23·诊断] 77.7 落点：row_data=" + inRowData
                + " 行 / quote_card_values=" + inCardValues + " 行");
        long hit = inRowData + inCardValues;
        assertFixtureNonEmpty(hit,
                "🚨 干预未生效：quote-card-edit 返 200，但 row_data 里找不到 77.7 ⇒ "
                        + "这一格没改上（rowKey 拼法与后端内容键不一致？rowKey=" + rowKey + "）。"
                        + "此时「主表是旧值」是**夹具错**，🚫 不许报成 D-43 复发。");
        System.out.println("[T-23] 干预已生效：row_data 命中 77.7 的组件数据 " + hit + " 行；rowKey=" + rowKey);

        // ④ 🚫 **中间刻意没有 saveDraft** —— 有它 D-42 的挂点就把 _record 补上了，本条就验不到 D-43
        requireStatusBeforeDiff(submit(fx), 200, "T-23 直接提交（中间无 saveDraft）");

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

    /**
     * 🔴 <b>真实 UI 形状</b>的 {@code PUT /draft}：{@code added} 只发<b>行本身</b>，
     * <b>🚫 不带 {@code componentData}</b>，组件数据交给服务端物化。
     *
     * <p>与 {@link #saveDraftAddedTwo} 的区别就是这一点，而这一点<b>决定了能不能发现 D-42</b>：
     * 带 {@code componentData} 的形状在旧代码上也绿。
     */
    private Response saveDraftLineOnly(Fx fx, String materialNo) {
        // 🔑 真实 UI 的 Step1 会选模板 ⇒ customerTemplateId 必须透传，
        //    否则服务端不知道该物化哪些组件，snapshotQuotation 建不出页签
        //    （实测：不传时组件数据 0 行，本条的前提守卫会正确地判成「夹具问题」）。
        String body = "{\"baseVersion\":0,\"customerTemplateId\":\"" + DS_TEMPLATE_ID + "\",\"added\":[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "ui1\","
                + "\"templateId\":\"" + DS_TEMPLATE_ID + "\","
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
