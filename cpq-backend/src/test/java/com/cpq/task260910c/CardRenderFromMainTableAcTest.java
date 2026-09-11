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
 * <b>AC-11</b> —— 两个页签渲染非空，达成路径 = <b>主表有边 ⇒ 树骨架能递归</b>。
 *
 * <h3>🔴 本类原名 {@code CardRenderFromRecordAcTest}，为<b>方案②</b>而写；D-14 作废后按 D-22 改写</h3>
 * 原设计下 AC-11 是方案②的<b>命门</b>：「带版本表只写 {@code _record} 之后，卡片<b>仍能渲染</b>」，
 * 靠 {@code ComponentDriverService} 的 <b>{@code _record} patch 合并</b>（D-8/D-9）达成，
 * 所以旧版有一条前提断言 {@code assertEquals(0L, mainM)}（<b>主表必须 0 行</b>）。
 * <p><b>那条前提与现行设计正相反</b>：D-14 后带版本表直写主表 ⇒ 主表 1 行。
 * D-8/D-9 的 patch 合并<b>整条作废</b>，渲染侧不再读 {@code _record}。
 * <p>📌 <b>AC-11 正是 D-14 要解决的问题</b>：方案② 下实测「物料BOM」页签只有 {@code BOM|1}
 * （只有合成根行、业务列全 {@code null}），根因是「树骨架只递归主表、spine 看不见 {@code _record}」。
 * ⇒ D-23：树骨架递归主表就能出边，🚫 <b>不动 {@code costing_bom_tree_config}</b>。
 *
 * <h3>AC-11 原文（{@code 需求文档.md §③ S-4}，达成路径已改）</h3>
 * 前置同 AC-10；操作「提交后打开报价单编辑页，点开「物料BOM」与「物料与元素BOM」两个页签」；断言：
 * <ol>
 *   <li>两个页签<b>各渲染出非空行</b>（🚫 空列表 / 0 行 / 「—」/「加载中…」一律不算通过）；</li>
 *   <li>「物料BOM」页签渲染出<b>树形两层</b>（根 {@code <新料号>} + 子 {@code AgCu90}），
 *       且<b>「材料占比（%）」列显示 {@code 100}</b>；</li>
 *   <li>「物料与元素BOM」页签渲染出 {@code Ag 90} / {@code Cu 10} 两行。</li>
 * </ol>
 *
 * <h3>本用例断在哪一层，以及为什么不止这一层</h3>
 * AC-11 的字面观测点是<b>浏览器里的页签</b>。本用例断的是
 * {@code quotation_line_component_data}（{@code snapshot_rows} / {@code row_data}）——
 * <b>那正是编辑页卡片读的那份数据</b>，是同一事实的后端可观测面，且能给出可复核的原始值。
 *
 * <p>🚫 <b>它不能替代 UI 验证</b>：「数据在库里对」与「页面上看得见」之间还隔着渲染层
 * （本项目 AP-31/AP-50 全族缺陷就长在这一段：数据齐全但 cell 走 fallback 渲染成「—」/「加载中…」）。
 * ⇒ 真实 UI 由 {@code cpq-frontend/e2e/t260910c-sc.spec.ts} 覆盖（AC-11 + AC-20）。
 * <b>两层都绿才算 AC-11 达成</b>；本用例单独绿只证明「后端把数据备齐了」。
 *
 * <h3>夹具的一处刻意选择</h3>
 * 客户挂上<b>与 {@code CUST-0004} 相同的产品分类</b>（只读取它的 {@code product_category_id}，
 * 🚫 不改 {@code CUST-0004} 任何字段）。理由：卡片页签集由模板决定，而模板经产品分类解析；
 * 不挂分类时「页签 0 行」会是<b>夹具问题</b>，却长得和 AC-11 的缺陷一模一样。
 */
@QuarkusTest
@DisplayName("AC-11 · 主表有边 ⇒ 两页签渲染非空（树形两层 + 占比 100 + 元素两行）")
class CardRenderFromMainTableAcTest extends Task260910CBase {

    private static final String TAB_MBOM = TAB_MBOM_COMPONENT;
    private static final String TAB_EBOM = TAB_EBOM_COMPONENT;

    @Test
    @DisplayName("AC-11①②③ · 两页签非空 + 树形两层（根料号 + 子 AgCu90）+ 占比 100 + Ag 90 / Cu 10")
    void ac11_cardRendersFromMainTableRows() {
        requireRecordLayer();

        // D-31：newBoundFixture = 挂参照客户的产品分类（只读它）+ **configure 之前**绑
        //       quotation.customer_template_id。后者是 compData 0 条 vs 14 条的**单一变量**。
        Fx fx = newBoundFixture("C-ac11");

        Response res = configure(fx, submitBody(C + "AC11-" + RUN_C,
                newPart(freshPartName("AC11零件"), "spec-234", "234", "11",
                        List.of(material(RECIPE_AGCU, CONFIG_AGCU, "100")),
                        List.of(PROC_CLEAN))));
        requireImplementationPresent(res, "AC-11");
        assertSubmitOk(res, "AC-11 选配提交");
        assertFreshlyMinted(res, "AC-11");
        String partNo = latestLinePartNo(fx);

        // ── 前提：达成路径真的具备了 —— **主表有边**（D-14），而不是旧版的「主表必须 0 行」──
        long mainM = count("SELECT count(*) FROM " + MBOM + " WHERE material_no='" + partNo + "'");
        long recM = recRows(MBOM_REC, fx, partNo);
        System.out.println("[AC-11 前提] 主表 " + MBOM + "=" + mainM + " 行 / " + MBOM_REC + "=" + recM + " 行");
        assertEquals(1L, mainM,
                "AC-11 前提：主表 " + MBOM + " 应有 **1 行**（D-14 直写），实际 " + mainM + " 行。"
                        + "\n  🔑 AC-11 的达成路径 = 「主表有数据 ⇒ 树骨架（costing_bom_tree_config，"
                        + "usage='QUOTE'）能把边递归出来」⇒ 主表 0 行时页签必然只剩合成根行"
                        + "（方案② 下实测的 BOM|1 形态），那时页签 0 行是**前提不成立**而不是渲染缺陷。"
                        + "\n  🚫 旧版这里断的是 0 行（方案② 的 patch 合并前提），已随 D-14 作废。");
        // 🔎 诊断（🚫 不是 AC-11 的判据）：D-14 之后 AC-11 的达成路径是「主表有边 ⇒ 树能递归」，
        //    原文**一个字都没提 _record** ⇒ _record 的行数归 AC-10④ 判，这里只打印供归因用。
        System.out.println("[AC-11 诊断] " + MBOM_REC + " 本单该料号 = " + recM
                + " 行（🚫 不是本条 AC 的判据；D-8/D-9 的 patch 合并路径随 D-14 整条作废）");

        // ── 把 BL-0202 这个混淆项排除掉，再物化卡片 ──────────────────
        String tplId = ensureLineTemplate(fx);
        long cdRows = materializeCards(fx);
        System.out.println("[AC-11 夹具] template_id=" + tplId + " 卡片组件数据=" + cdRows + " 条");
        assertTrue(cdRows > 0,
                "⛔ AC-11 【未验证】（**不是「通过」，也不是被测功能的结论**）：三条服务端物化入口"
                        + "（GET 详情 / PUT draft / POST ensure-card-values）跑完，本单 "
                        + "quotation_line_component_data 仍是 0 条 ⇒ 「页签 0 行」还没走到"
                        + "「渲染不出来」这一层，判不出 AC-11 的红绿。template_id=" + tplId
                        + "\n  🔑 AC-11 的字面观测点是**编辑页的实时渲染**，那条路走的是"
                        + "前端 expand-driver 而不是这张持久化表 ⇒ 权威判据是 Playwright 用例"
                        + " cpq-frontend/e2e/t260910c-sc.spec.ts，本用例只是辅助证据。");

        // ── ① 两页签各非空 ──────────────────────────────────────────
        long mbomRows = tabRowCount(fx, TAB_MBOM);
        long ebomRows = tabRowCount(fx, TAB_EBOM);
        System.out.println("[AC-11①] 「" + TAB_MBOM + "」页签行数=" + mbomRows
                + " / 「" + TAB_EBOM + "」页签行数=" + ebomRows);
        assertTrue(mbomRows > 0,
                "AC-11①：「物料BOM」页签应渲染出非空行，实际 " + mbomRows + " 行。"
                        + "\n  🚫 0 行 / 空列表 / 「—」/ 「加载中…」一律不算通过（AP-31 族）。"
                        + "\n  📌 本单主表 " + MBOM + " 有 " + mainM + " 行边 ⇒ 0 行意味着"
                        + "树骨架没把主表的边递归出来（D-23 说「递归主表就能出边，不动 costing_bom_tree_config」）。"
                        + "\n  ⚠️ 归因排除项：报价行 template_id=" + tplId + "，为 NULL 时先按 BL-0202 排查夹具。");
        assertTrue(ebomRows > 0,
                "AC-11①：「物料与元素BOM」页签应渲染出非空行，实际 " + ebomRows + " 行。同上归因。");

        // ── ② 树形两层（根 = 新料号，子 = AgCu90）+ 材料占比 100 ────────
        String mbomText = tabText(fx, TAB_MBOM);
        System.out.println("[AC-11②] 「" + TAB_MBOM + "」页签原始数据 = " + preview(mbomText));
        assertTrue(mbomText.contains(partNo),
                "AC-11②：「物料BOM」页签应渲染出**树的根层** = 新料号 " + partNo + "，页签数据里找不到它。"
                        + "\n  页签原始数据 = " + preview(mbomText));
        assertTrue(mbomText.contains(RECIPE_AGCU),
                "AC-11②：「物料BOM」页签应渲染出**树的子层** = 材质 " + RECIPE_AGCU + "，页签数据里找不到它。"
                        + "\n  🔑 只有根层没有子层 = 方案② 下实测的 BOM|1「合成根行、业务列全 null」形态 ——"
                        + "那正是 D-14 要解决的问题：树骨架看不见数据，递归不出边。"
                        + "\n  页签原始数据 = " + preview(mbomText));
        System.out.println("[AC-11②] 「材料占比（%）」列在页签数据里的全部实际取值 = "
                + columnValues(mbomText, "材料占比（%）"));
        assertTrue(columnHasValue(mbomText, "材料占比（%）", "100"),
                "AC-11②：「物料BOM」页签的「材料占比（%）」列应显示 **100**，"
                        + "该列的实际取值 = " + columnValues(mbomText, "材料占比（%）")
                        + "\n  🚨 本判据已按列名定位（🚫 不再是「整段 JSON 里能找到 100」那种宽匹配 ——"
                        + "同一行还有组成数量/不良率/损耗率等十几个数值列，任一为 100 都会让旧判据假绿）。"
                        + "\n  📌 D-14 后这一列由**主表直写天然带上**（AC-10① 断言 material_ratio="
                        + "100.000000000000）⇒ 页签有行但这格空，说明渲染层没把主表该列取出来"
                        + "（🚫 不再是「_record 投影没补 material_ratio」—— D-10 已降级、D-19 接受现状）。"
                        + "\n  页签原始数据 = " + preview(mbomText));

        // ── ③ 元素两行 Ag 90 / Cu 10 ────────────────────────────────
        String ebomText = tabText(fx, TAB_EBOM);
        System.out.println("[AC-11③] 「" + TAB_EBOM + "」页签原始数据 = " + preview(ebomText));
        assertTrue(ebomText.contains("Ag"),
                "AC-11③：「物料与元素BOM」页签应出现元素 **Ag**（" + CONFIG_AGCU + " 配置：Ag=90 / Cu=10），"
                        + "实际找不到。页签原始数据 = " + preview(ebomText));
        assertTrue(ebomText.contains("Cu"),
                "AC-11③：「物料与元素BOM」页签应出现元素 **Cu**，实际找不到。页签原始数据 = "
                        + preview(ebomText));
        System.out.println("[AC-11③] 「组成含量（%）」列在页签数据里的全部实际取值 = "
                + columnValues(ebomText, "组成含量（%）"));
        assertTrue(columnHasValue(ebomText, "组成含量（%）", "90"),
                "AC-11③：Ag 的含量应是 **90**，「组成含量（%）」列的实际取值 = "
                        + columnValues(ebomText, "组成含量（%）")
                        + "\n  🚨 按列名定位（🚫 不是在整段 JSON 里找 90）。原始数据 = " + preview(ebomText));
        assertTrue(columnHasValue(ebomText, "组成含量（%）", "10"),
                "AC-11③：Cu 的含量应是 **10**，「组成含量（%）」列的实际取值 = "
                        + columnValues(ebomText, "组成含量（%）")
                        + "\n  🚨 按列名定位。原始数据 = " + preview(ebomText));

        System.out.println("[AC-11] ⚠️ 本用例断的是编辑页卡片读的那份持久化数据，"
                + "🚫 不替代 UI 验证 —— 真实页签的可见行由 Playwright 用例覆盖。");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /**
     * 依次尝试三条<b>服务端</b>物化入口，并打印每一条之后的行数 —— 让「哪条入口有效」这件事
     * 变成报告里的事实，而不是下一个人再猜一遍。
     *
     * <p>📌 既有测试已登记的事实（{@code CardValuesRecomputeStableTest} 类注释）：
     * {@code snapshotQuotation(id,false)} 在隔离夹具下<b>未触发 expand-driver、也未创建
     * {@code quotation_line_component_data} 行</b>，根因未查明。⇒ 这里不赌任何单一入口。
     */
    private long materializeCards(Fx fx) {
        long n = cardDataRows(fx);
        System.out.println("[AC-11 物化] 起点 = " + n + " 条"
                + (n > 0 ? "（🔑 D-31 前置补上后 **configure 一次请求内就物化了**，"
                         + "下面三条入口一个都不调 —— 这是「不靠额外入口」的构造性证据）" : ""));
        if (n > 0) return n;

        RestAssured.given().cookies(adminSession()).get("/api/cpq/quotations/" + fx.quotationId()).thenReturn();
        n = cardDataRows(fx);
        System.out.println("[AC-11 物化] GET 详情后 = " + n + " 条");
        if (n > 0) return n;

        Object bv = scalar("SELECT coalesce(user_data_version,0) FROM quotation WHERE id='"
                + fx.quotationId() + "'");
        Response d = RestAssured.given().cookies(adminSession()).contentType(ContentType.JSON)
                .body("{\"baseVersion\":" + bv + ",\"added\":[],\"modified\":[],\"removed\":[]}")
                .put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
        n = cardDataRows(fx);
        System.out.println("[AC-11 物化] PUT draft（status=" + d.statusCode() + "）后 = " + n + " 条"
                + (d.statusCode() == 200 ? "" : " body=" + d.asString()));
        if (n > 0) return n;

        Response e = RestAssured.given().cookies(adminSession()).contentType(ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();
        n = cardDataRows(fx);
        System.out.println("[AC-11 物化] POST ensure-card-values（status=" + e.statusCode() + "）后 = "
                + n + " 条" + (e.statusCode() == 200 ? "" : " body=" + e.asString()));
        return n;
    }

    private long cardDataRows(Fx fx) {
        return count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "'");
    }

    /** 本单某个页签（按组件名匹配）的行数 = {@code snapshot_rows} / {@code row_data} 的数组长度之和。 */
    private long tabRowCount(Fx fx, String componentName) {
        return count("SELECT coalesce(sum(jsonb_array_length("
                + "coalesce(cd.snapshot_rows, cd.row_data, '[]'::jsonb))),0) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "JOIN component c ON c.id = cd.component_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "' AND c.name='" + componentName + "'");
    }

    /** 本单某个页签的原始 JSON 文本（断内容用；打印出来供人复核）。 */
    private String tabText(Fx fx, String componentName) {
        List<Object> t = col("SELECT coalesce(cd.snapshot_rows, cd.row_data, '[]'::jsonb)::text "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "JOIN component c ON c.id = cd.component_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "' AND c.name='" + componentName + "'");
        return String.join(" ", t.stream().map(String::valueOf).toList());
    }

    /**
     * 「<b>某一列</b>的值 = 期望数字」的判据 —— 🚨 <b>按列名定位，不是在整段 JSON 里找这个数字</b>。
     *
     * <h3>🔴 2026-09-11 收紧（原判据是潜在假绿）</h3>
     * 原判据是 {@code json.matches(".*[":\s]100(\.0+)?[",\s}].*")} —— 在<b>整段</b>页签 JSON 里
     * 找「100」这个数字。而「物料BOM」页签同一行里还有<b>组成数量 / 不良率 / 损耗率 / 单重</b>等
     * 十几个数值列 ⇒ <b>任何一列恰好是 100，这条断言就绿</b>，而「材料占比（%）」那格可能是空的。
     * ⇒ 现在改成：先按<b>列名后缀</b>（AC 原文点名的列名「材料占比（%）」/「组成含量（%）」）定位到那个 key，
     * 再断它紧跟的值。列名来自 <b>AC 原文</b>，🚫 不是从实现反推的。
     *
     * <p>📌 值的文本形态不写死：落库列 {@code numeric(26,12)}，经渲染可能是 {@code 100} /
     * {@code 100.0} / {@code "100.000000000000"} / {@code "100.000000000"}（9 位显示口径）
     * ⇒ 按「以期望数字起头、后面只跟小数点和 0」匹配，避免「值对了但小数口径变了」误红。
     *
     * @param columnNameSuffix AC 原文里的列名（如 {@code 材料占比（%）}）；JSON key 以它结尾即命中
     */
    private static boolean columnHasValue(String json, String columnNameSuffix, String expected) {
        // 形如  "...材料占比（%）": "100.000000000000"   或  "...材料占比（%）": 100
        String re = "(?s).*" + java.util.regex.Pattern.quote(columnNameSuffix)
                + "\"\\s*:\\s*\"?" + java.util.regex.Pattern.quote(expected) + "(\\.0+)?\"?\\s*[,}].*";
        return json.matches(re);
    }

    /** 该列在页签 JSON 里的<b>全部实际取值</b>（失败信息里要打出来，testing.md §3）。 */
    private static String columnValues(String json, String columnNameSuffix) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(columnNameSuffix) + "\"\\s*:\\s*(\"[^\"]*\"|[^,}\\s]+)")
                .matcher(json);
        List<String> vs = new java.util.ArrayList<>();
        while (m.find()) vs.add(m.group(1));
        return vs.isEmpty() ? "(该列名在页签数据里一次都没出现)" : vs.toString();
    }

    private static String preview(String s) {
        if (s == null) return "(null)";
        return s.length() <= 1200 ? s : s.substring(0, 1200) + "…（共 " + s.length() + " 字符）";
    }
}
