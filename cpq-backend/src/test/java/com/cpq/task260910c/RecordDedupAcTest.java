package com.cpq.task260910c;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-16</b> —— S-6：{@code _record} 同内容重复行修复（D-11）。
 *
 * <h3>📌 D-22 复核结论：本类的断言方向<b>不受 D-14 影响，保持原样</b></h3>
 * D-14 作废的是「带版本表<b>只写 {@code _record}</b>、主表零新增」那一套（方案②）。
 * AC-16 断的是「{@code _record} <b>自身</b>不许出现同内容重复行」，
 * 与「主表写不写」正交 ⇒ 本类<b>没有一条「主表 0 行」类断言需要反转</b>，
 * 只更新了注释里指向已作废设计的措辞（原写「选配提交本身已写 _record（D-7）」）。
 * <p>🔑 D-14 之后 {@code _record} 的角色 = <b>报价单对主数据的投影 / 核价回填的数据来源</b>
 * （🚫 不再是渲染数据源），而本条 AC 的风险传导恰恰走回填那一侧：
 * {@code origin_id=NULL} 的副本会在回填时被当「新增」追加 ⇒ 主表整组翻倍（AC-17）。
 * ⇒ <b>D-14 让这条 AC 更重要，而不是失效。</b>
 *
 * <p>AC-16 原文（{@code 需求文档.md §③ S-6}）：
 * 前置「造一张单，<b>同一个销售料号建 2 个 line item</b>」；操作「触发 {@code _record} 写入（保存草稿或提交）」；断言：
 * <ol>
 *   <li>{@code ds_quote_material_bom_record} 中
 *       {@code (quotation_id, material_no, item_seq, input_material_no)} <b>无重复</b>
 *       （{@code GROUP BY … HAVING count(*)>1} 返 <b>0 行</b>）；</li>
 *   <li>{@code ds_quote_element_bom_record} 同上；</li>
 *   <li>🔑 <b>阳性对照</b>：改动前同一操作必须能复现重复（否则用例是空验证）。</li>
 * </ol>
 *
 * <h3>🔑 ③ 阳性对照 —— 已在改动前（本 worktree HEAD = 纯文档提交）实测成立</h3>
 * 2026-09-10 只读查证（{@code cpq_db_test} 与 {@code cpq_db_0724} <b>双库逐字一致</b>），
 * 需求文档点名的复现单 {@code 08c99680-1359-4b1b-be98-7b93aabc71e4} 的 {@code S0001}：
 * <pre>
 *   id=9645 origin_id=12013  material_no=S0001 item_seq=1 input_material_no=S0002  created_at=…557113+00
 *   id=9646 origin_id=(NULL) material_no=S0001 item_seq=1 input_material_no=S0002  created_at=…557113+00
 *   id=9643 origin_id=12014  material_no=S0001 item_seq=2 input_material_no=S0003  created_at=…557113+00
 *   id=9644 origin_id=(NULL) material_no=S0001 item_seq=2 input_material_no=S0003  created_at=…557113+00
 * </pre>
 * ⇒ 形态与主线给的判据<b>逐条吻合</b>：同 {@code (material_no,item_seq,input_material_no)} 2 行、
 * <b>一份认领 {@code origin_id}、一份 NULL</b>、{@code created_at} 逐字相同。
 * ⚠️ 这条只读证据证明「缺陷在改动前存在」，🚫 <b>不能</b>替代「本用例的夹具能触发它」——
 * 后者要靠本用例在改动前跑一次<b>变红</b>来证明，见 <b>FT-1</b>。
 *
 * <h3>🚫 为什么判据必须按 quotation_id 收窄</h3>
 * 同一条 SQL 全库跑，2026-09-10 实测有 <b>3763 个重复分组 / 5610 条多余行</b>（存量）。
 * 拿全库计数当判据 ⇒ 本任务修好了也永远红，而且<b>红得像本次引入的回归</b>（{@code test.md §2}）。
 */
@QuarkusTest
@DisplayName("AC-16 · _record 同内容不重复（同料号多 line item）")
class RecordDedupAcTest extends Task260910CBase {

    /**
     * <b>AC-16①②</b> 主场景 —— 同一销售料号 2 个 line item。
     *
     * <h3>怎么造「同一销售料号 2 个 line item」而不改实现</h3>
     * 走<b>指纹复用</b>：两次选配提交用<b>完全相同</b>的零件输入（品名/规格/尺寸/总重/材质/工序），
     * 只把 {@code customerProductNo} 换一个（同编号会被 409 硬拦，见 AC-19）。
     * 相同输入 ⇒ 命中复用 ⇒ <b>同一个销售料号</b>；不同客户产品编号 ⇒ <b>第 2 个 line item</b>。
     * 这正是需求文档 AC-21 描述的「一料号多编号」形态，也是 {@code S0001} 那张复现单的形态。
     *
     * <p>🚨 夹具自检不可省：第二次提交若<b>没有</b>复用（铸了新料号），
     * 本用例就退化成「两个不同料号各 1 行，当然不重复」= <b>空验证</b>。
     * ⇒ 断言重复之前，先硬断言「两行的 {@code product_part_no_snapshot} 相同」。
     */
    @Test
    @DisplayName("AC-16①② · 同料号 2 个 line item ⇒ _record 无同内容重复")
    void ac16_noDuplicateRecordRows() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac16");   // D-31：含「configure 之前绑 quotation.customer_template_id」

        // ── 第 1 次提交 ──────────────────────────────────────────────
        Response r1 = configure(fx, samePartBody(C + "AC16A-" + RUN_C));
        requireImplementationPresent(r1, "AC-16");
        assertSubmitOk(r1, "AC-16 第 1 次提交");

        // ── 第 2 次提交：输入逐字相同，只换客户产品编号 ⇒ 指纹复用同一料号 ──
        Response r2 = configure(fx, samePartBody(C + "AC16B-" + RUN_C));
        assertSubmitOk(r2, "AC-16 第 2 次提交（同输入换编号 ⇒ 应命中复用）");

        // ── 夹具自检：必须真的是「同一料号 2 个 line item」──────────────
        List<Object> partNos = col("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND parent_line_item_id IS NULL "
                + "ORDER BY sort_order, created_at");
        System.out.println("[AC-16 夹具] 本单根行料号 = " + partNos);
        assertEquals(2, partNos.size(),
                "AC-16 夹具自检：本单应有 **2 个根 line item**，实际 " + partNos.size()
                        + " 个。⇒ 前置没造出来，后面的「无重复」是空验证");
        String p0 = String.valueOf(partNos.get(0));
        String p1 = String.valueOf(partNos.get(1));
        assertEquals(p0, p1,
                "AC-16 夹具自检：两个 line item 必须是**同一个销售料号**（靠指纹复用），实际 "
                        + p0 + " vs " + p1 + "。不同料号 ⇒ 各自 1 行当然不重复 = 空验证，"
                        + "本用例作废。👉 排查方向：指纹是否把 customerProductNo 也算进去了");

        // ── 触发 _record 写入（AC 原文：保存草稿或提交）────────────────
        // 选配提交在建单末尾即投影 _record（D-14 口径，与导入侧同向）；
        // 再补一次报价单提交，覆盖 submit 挂点这条路径。
        requireCardDataMaterialized(fx, "AC-16 前置");
        long recBeforeSubmit = recMbom(fx);
        System.out.println("[AC-16] 选配提交后本单 " + MBOM_REC + " = " + recBeforeSubmit + " 行");
        assertNonEmpty(recBeforeSubmit, "AC-16 前置：选配提交后本单 " + MBOM_REC + " 的行数");

        // ── ① material_bom_record 无重复 ──────────────────────────────
        assertNoDup(MBOM_REC, fx, p0, "AC-16①");
        // ── ② element_bom_record 无重复 ──────────────────────────────
        assertNoDup(EBOM_REC, fx, p0, "AC-16②");
    }

    /**
     * <b>AC-16 的第二条路径</b> —— 经<b>报价单提交</b>（{@code POST /{id}/submit}，
     * {@code api.md §1} 明列「不变更」）触发 {@code _record} 重写后仍不重复。
     *
     * <p>📌 为什么单独一条：AC 原文写「保存草稿<b>或</b>提交」，两条挂点是不同代码路径
     * （既有测试 {@code RecordWriteAcTest} 的类注释已记过 saveDraft 挂点空窗的 P0）。
     * 覆盖式重写若把「旧行不删 + 新行追加」写成累加，重复只会在<b>第二次</b>写入时出现 ——
     * 只跑一次触发的用例看不见它。
     */
    @Test
    @DisplayName("AC-16 · 二次触发（报价单提交）后仍无重复 —— 覆盖式重写不许累加")
    void ac16_noDuplicateAfterSecondTrigger() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac16b");  // D-31：含「configure 之前绑 quotation.customer_template_id」

        Response r1 = configure(fx, samePartBody(C + "AC16C-" + RUN_C));
        requireImplementationPresent(r1, "AC-16 二次触发");
        assertSubmitOk(r1, "AC-16 二次触发 · 选配提交");
        String partNo = latestLinePartNo(fx);

        requireCardDataMaterialized(fx, "AC-16 二次触发前置");
        long after1 = recRows(MBOM_REC, fx, partNo);
        System.out.println("[AC-16 二次触发] 第 1 次触发后 " + MBOM_REC + " = " + after1 + " 行");
        assertNonEmpty(after1, "AC-16 二次触发前置：第 1 次触发后本单 " + MBOM_REC + " 的行数");

        // 物化卡片值（既有测试的既定做法：提交前确保卡片值已算）
        RestAssured.given().cookies(adminSession()).contentType(ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();

        Response sub = RestAssured.given().cookies(adminSession()).contentType(ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/submit").thenReturn();
        System.out.println("[AC-16 二次触发] 报价单提交 status=" + sub.statusCode());
        // 🚨 提交没通过时，「行数没翻倍」是因为压根没重写 ⇒ 必须硬失败，不许当通过
        assertEquals(200, sub.statusCode(),
                "AC-16 二次触发前置：报价单提交应 200（api.md §1 明列 submit 不变更），实际 "
                        + sub.statusCode() + "。非 200 ⇒ 第二次 _record 写入压根没发生，"
                        + "「无重复」是空验证，不是结论。响应=" + sub.asString());

        long after2 = recRows(MBOM_REC, fx, partNo);
        System.out.println("[AC-16 二次触发] 第 2 次触发后 " + MBOM_REC + " = " + after2 + " 行");
        assertNoDup(MBOM_REC, fx, partNo, "AC-16 二次触发①");
        assertNoDup(EBOM_REC, fx, partNo, "AC-16 二次触发②");
        assertEquals(after1, after2,
                "AC-16 二次触发：覆盖式重写后本单行数应与第一次**相同**（" + after1 + "），实际 " + after2
                        + " ⇒ 第二次写入是「追加」而不是「覆盖」，重复正在累加");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 完全相同的零件输入 —— 只有 {@code customerProductNo} 不同，用来触发指纹复用。 */
    private java.util.Map<String, Object> samePartBody(String customerProductNo) {
        return submitBody(customerProductNo,
                newPart(freshPartName("AC16复用零件"), "spec-2343", "234", "11",
                        List.of(material(RECIPE_AGCU, CONFIG_AGCU, "100")),
                        List.of(PROC_CLEAN)));
    }

    private void assertNoDup(String table, Fx fx, String partNo, String label) {
        long groups = dupGroups(table, fx);
        long rowsInScope = recRows(table, fx, partNo);
        System.out.println("[" + label + "] " + table + " 本单行数=" + rowsInScope
                + " 重复分组数=" + groups);
        // 🚨 先证明有行，再断「无重复」—— 0 行时「无重复」恒成立（假绿）
        assertNonEmpty(rowsInScope, label + "：" + table + " 在本单该料号下的行数");
        assertEquals(0L, groups,
                label + "：" + table + " 在本单（quotation_id=" + fx.quotationId()
                        + "）内 (quotation_id, material_no, item_seq, input_material_no) **不许重复**，"
                        + "实际有 " + groups + " 个重复分组。明细（料号/item_seq/投入料号/行数/其中认领 origin_id 的行数）="
                        + fmt(dupDetail(table, fx))
                        + "\n  📌 改动前的已知形态：同一分组 2 行，一份认领 origin_id、一份 NULL，"
                        + "created_at 逐字相同（复现单 08c99680… 的 S0001）。"
                        + "\n  ⚠️ 风险传导：origin_id=NULL 的副本在核价回填时会被当「新增」追加 ⇒ 主表整组翻倍（AC-17）。");
    }

    private static String fmt(List<Object[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : rs) {
            sb.append("\n    ");
            for (Object c : r) sb.append('[').append(c).append(']');
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }
}
