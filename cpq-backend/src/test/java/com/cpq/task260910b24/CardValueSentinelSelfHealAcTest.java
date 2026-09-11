package com.cpq.task260910b24;

import com.cpq.task260910c.Task260910CBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 <b>B-24 / AC-25</b> —— 失败哨兵不再粘死（{@code CardSnapshotService} 选行谓词）。
 *
 * <h3>被测的那一行</h3>
 * {@code ensureCardValuesDetailed} 的 missing 谓词原来只认 {@code quote_card_values IS NULL}。
 * 失败哨兵 {@code {"tabs":[],"__cardValueFailed":true}} 是<b>非 NULL</b> 的 ⇒ 一旦落库就永不再被选中，
 * 用户事后补绑报价模板也不自愈。B-24 把谓词改成
 * {@code IS NULL OR position('__cardValueFailed' in <col>::text) > 0}（{@code sqlNeedsRecompute}）。
 *
 * <h3>为什么只在最后一步调 {@code ensure-card-values}（而不是照搬诊断的三连击）</h3>
 * 诊断的 {@code materialize()} 是 GET 详情 + PUT draft + POST ensure-card-values 三连。
 * 本用例把 <b>PUT draft</b>（负责物化 {@code quotation_line_component_data}，AC-25②）与
 * <b>POST ensure-card-values</b>（负责重算卡片值，AC-25①③）分成两个独立阶段并各自读数，
 * 这样「①变成真值」只可能来自被测的那一行谓词 —— 🚫 不给「其实是 saveDraft 顺手置 NULL 才好的」
 * 这种混淆项留口子（阶段 3 会把 draft 之后的卡片值原样打出来自证）。
 *
 * <h3>🧪 阳性对照（人工，见回报 §4）</h3>
 * 把 {@code sqlNeedsRecompute} 改回只认 {@code IS NULL} 重跑本用例 ⇒ 阶段 4 的 ① 必须<b>仍是哨兵</b>
 * （bug 复现）。不复现 = 本用例没触发粘死场景，读数作废。
 *
 * <p>🚦 命中面：只读/只写本用例自造的那一个报价单（{@code WHERE ... = 本单}）；
 * 🚫 不碰现网存量的 {@code QT-20260908-0612} / {@code QT-20260907-0580}（那两张单只做只读判据）。
 */
@QuarkusTest
@DisplayName("AC-25 · 失败哨兵不再粘死（B-24）")
class CardValueSentinelSelfHealAcTest extends Task260910CBase {

    /** 与生产常量同值；🚫 不 import 生产类，避免「被测方改了常量、断言跟着改、于是永远绿」。 */
    private static final String MARK = "__cardValueFailed";

    @Test
    @DisplayName("模板未绑时落的失败哨兵，事后补绑模板 + 触发 ensureCardValues 后被重算成真值")
    void ac25() {
        // ══════════ 阶段 1：前置 —— 模板未绑 + 加产品 ⇒ 哨兵落库、compData 0 ══════════
        UUID catId = referenceCategoryId();
        Fx fx = newFixture("B24", catId);       // 🚫 刻意不调 newBoundFixture：本用例要的就是「模板未绑」
        assertEquals("(NULL)", scalar(
                        "SELECT coalesce(customer_template_id::text,'(NULL)') FROM quotation WHERE id='"
                                + fx.quotationId() + "'"),
                "AC-25 前置：本单 customer_template_id 必须为 NULL（这是哨兵落库的唯一成因）。"
                        + "已经非 NULL ⇒ 后面测的不是粘死场景，读数作废");

        Response res = configure(fx, submitBody(C + "B24-" + RUN_C,
                newPart(freshPartName("B24零件"), "spec-234", "234", "11",
                        List.of(material(RECIPE_AGCU, CONFIG_AGCU, "100")),
                        List.of(PROC_CLEAN))));
        assertSubmitOk(res, "AC-25 阶段1 加产品");
        assertFreshlyMinted(res, "AC-25");      // 🚨 指纹复用假绿守卫（本任务已发生过）
        String partNo = latestLinePartNo(fx);
        System.out.println("[AC-25 阶段1] 新铸销售料号=" + partNo);

        System.out.println("[AC-25 阶段1] configure 直后 quote_card_values = " + trunc(cardValues(fx))
                + "（🔬 实测：此刻还是 NULL —— 加产品本身不落哨兵，哨兵由下一步的 ensure 落）");

        // ── 阶段 1b：模板仍为 NULL 时触发一次 ensure ⇒ build 守卫返 null ⇒ **失败哨兵落库** ──
        // 这一步就是 AC-25 原文的前置「失败哨兵已落下」；🚫 少了它就不是粘死场景（实测 cv=NULL 时
        // IS NULL 谓词本来就选得中，改动前后都能算 ⇒ 会得出「看起来也好了」的假绿读数）。
        Response seed = given().contentType(ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();
        assertReachedBusinessLayer(seed, "AC-25 阶段1b 落哨兵");
        assertEquals(200, seed.statusCode(), "AC-25 阶段1b：模板为 NULL 时 ensure 应仍返 200（内部落哨兵，"
                + "🚫 不许 500），实际=" + seed.statusCode() + " " + seed.asString());

        String cv1 = cardValues(fx);
        long cd1 = compData(fx);
        System.out.println("[AC-25 阶段1b] quote_card_values = " + cv1);
        System.out.println("[AC-25 阶段1b] quotation_line_component_data = " + cd1 + " 条");
        assertTrue(cv1 != null && cv1.contains(MARK),
                "AC-25 前置：模板未绑 + 加产品之后 quote_card_values 应是失败哨兵（含 " + MARK + "），"
                        + "实际=" + cv1 + " ⇒ 前置不成立，本用例测不到粘死");
        assertEquals(0L, cd1,
                "AC-25 ② 的对照读数：前置阶段 compData 应为 0 条（driver 组件解析不到 ⇒ 早退），实际=" + cd1);

        // ── 阶段 1c：模板<b>仍</b>为 NULL 时再 ensure 一次 —— 这正是现网那 2 张存量单修复后的处境 ──
        // 🔑 B-24 之后哨兵行会被<b>重选</b>，于是必须回答两件事：
        //    ① 会不会因此炸（500 / 异常冒泡）？② 会不会「自愈成假的真值」（空壳当成功）？
        // 期望：仍 200、仍是哨兵（根因没消失 ⇒ 重算再失败一次 ⇒ 哨兵原样重写）。
        // ⇒ 这就是「存量单不自愈」结论的体内证据：光改谓词不够，得先把模板补上。
        Response again = given().contentType(ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();
        assertEquals(200, again.statusCode(), "AC-25 阶段1c：哨兵行被重选后再算一次仍应 200（🚫 不许 500），实际="
                + again.statusCode() + " " + again.asString());
        String cv1c = cardValues(fx);
        System.out.println("[AC-25 阶段1c] 模板仍 NULL 时第二次 ensure ⇒ quote_card_values = " + trunc(cv1c)
                + " | compData = " + compData(fx) + " 条");
        assertTrue(cv1c != null && cv1c.contains(MARK),
                "AC-25 阶段1c：根因（模板未绑）没消失时，重算只应把哨兵原样写回；"
                        + "变成非哨兵反而说明它把空壳当成功落库了（那是更坏的假绿）。实际=" + trunc(cv1c));

        // ══════════ 阶段 2：干预 —— 事后补绑 quotation.customer_template_id ══════════
        // （= 模拟用户在报价单 Step2 手工选模板；CreateQuotationRequest.customerTemplateId 是可选字段）
        String tpl = bindQuotationTemplate(fx);   // 基座内含 UPDATE 后回读自检
        System.out.println("[AC-25 阶段2] 事后补绑 customer_template_id=" + tpl);

        // ══════════ 阶段 3：PUT draft —— 只负责物化 compData（AC-25②），不负责治哨兵 ══════════
        Object bv = scalar("SELECT coalesce(user_data_version,0) FROM quotation WHERE id='" + fx.quotationId() + "'");
        Response draft = given().contentType(ContentType.JSON)
                .body("{\"baseVersion\":" + bv + ",\"added\":[],\"modified\":[],\"removed\":[]}")
                .put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
        assertReachedBusinessLayer(draft, "AC-25 阶段3 PUT draft");
        assertEquals(200, draft.statusCode(), "AC-25 阶段3：PUT draft 应 200，实际=" + draft.statusCode()
                + " " + draft.asString());
        long cd2 = compData(fx);
        String cv2 = cardValues(fx);
        System.out.println("[AC-25 阶段3] PUT draft 后 compData = " + cd2 + " 条");
        System.out.println("[AC-25 阶段3] PUT draft 后 quote_card_values 含哨兵？= " + (cv2 != null && cv2.contains(MARK))
                + " | " + trunc(cv2));
        // 🚫 这里刻意**不断言**「仍是哨兵」：saveDraft 的 D-1 条件失效若判定内容变了会把该列置 NULL，
        //    那属于另一条合法自愈路径。本行只做**归因披露** —— 它决定阶段 4 的 ① 该怎么解读：
        //    · 阶段 3 仍是哨兵（诊断实测的情形）⇒ 阶段 4 的 ① 变真值 = 被测谓词的功劳
        //    · 阶段 3 已被置 NULL ⇒ 阶段 4 就算绿也**不构成** B-24 的证据（须看阳性对照那一枪）

        // ══════════ 阶段 4：POST ensure-card-values —— 唯一的重算入口 ══════════
        Response ensure = given().contentType(ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();
        assertReachedBusinessLayer(ensure, "AC-25 阶段4 ensure-card-values");
        assertEquals(200, ensure.statusCode(), "AC-25 阶段4：ensure-card-values 应 200（🚫 不许 500），实际="
                + ensure.statusCode() + " " + ensure.asString());

        String cv3 = cardValues(fx);
        long cd3 = compData(fx);
        long tabsWithRows = count("SELECT count(*) FROM quotation_line_item li, "
                + "jsonb_array_elements(li.quote_card_values->'tabs') t "
                + "WHERE li.quotation_id='" + fx.quotationId() + "' "
                + "  AND jsonb_array_length(coalesce(t->'baseRows','[]'::jsonb)) > 0");
        long tabsTotal = count("SELECT count(*) FROM quotation_line_item li, "
                + "jsonb_array_elements(li.quote_card_values->'tabs') t "
                + "WHERE li.quotation_id='" + fx.quotationId() + "'");

        System.out.println("[AC-25 ①] quote_card_values 含哨兵？= " + (cv3 != null && cv3.contains(MARK)));
        System.out.println("[AC-25 ①] quote_card_values 前 400 字 = " + trunc(cv3));
        System.out.println("[AC-25 ②] quotation_line_component_data = " + cd3 + " 条（阶段1 基线 " + cd1 + " 条）");
        System.out.println("[AC-25 ③] 页签总数 = " + tabsTotal + "，其中 baseRows 非空的 = " + tabsWithRows);
        for (Object[] r : rows("SELECT c.name, "
                + "coalesce(jsonb_array_length(coalesce(cd.snapshot_rows, cd.row_data,'[]'::jsonb)),0) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id=cd.line_item_id "
                + "JOIN component c ON c.id=cd.component_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "' "
                + "  AND coalesce(jsonb_array_length(coalesce(cd.snapshot_rows, cd.row_data,'[]'::jsonb)),0) > 0 "
                + "ORDER BY c.name")) {
            System.out.println("   · 页签 " + r[0] + " → " + r[1] + " 行");
        }

        // ── ① 变成真值：tabs 非空 且 不含失败标记 ──
        assertNotNull(cv3, "AC-25 ①：quote_card_values 不应为 NULL");
        assertFalse(cv3.contains(MARK),
                "AC-25 ①：ensureCardValues 之后 quote_card_values 仍带失败标记 " + MARK
                        + " ⇒ 哨兵仍然粘死（B-24 未生效）。实际=" + trunc(cv3));
        assertTrue(tabsTotal > 0,
                "AC-25 ①：tabs 必须非空，实际页签数=" + tabsTotal + "（空 tabs 即使不带哨兵也不算通过）");

        // ── ② compData 非 0（前置阶段为 0，作为对照）──
        assertTrue(cd3 > 0, "AC-25 ②：quotation_line_component_data 应非 0 条，实际=" + cd3);

        // ── ③ 渲染出非空行：至少一个页签 baseRows 非空（🚫 空列表 / 0 行 / 「—」不算通过）──
        assertTrue(tabsWithRows > 0,
                "AC-25 ③：全部 " + tabsTotal + " 个页签的 baseRows 都是空的 ⇒ 卡片渲染不出任何数据行，"
                        + "按 AC 原文「空列表 / 0 行 / 「—」一律不算通过」判不通过。实际 cardValues=" + trunc(cv3));

        // ── ③' 再从前端真正消费的那个出口确认一次（DTO，不只是 DB 列）──
        Response detail = given().get("/api/cpq/quotations/" + fx.quotationId()).thenReturn();
        assertEquals(200, detail.statusCode(), "AC-25 ③'：GET 报价单详情应 200，实际=" + detail.statusCode());
        String dtoCv = String.valueOf(jsonAny(detail, "data.lineItems[0].quoteCardValues",
                "lineItems[0].quoteCardValues"));
        System.out.println("[AC-25 ③'] DTO.lineItems[0].quoteCardValues 前 400 字 = " + trunc(dtoCv));
        assertFalse(dtoCv.contains(MARK),
                "AC-25 ③'：DTO 里回给前端的 quoteCardValues 仍带 " + MARK
                        + " ⇒ 页面照旧显示「渲染失败」。实际=" + trunc(dtoCv));
        assertTrue(dtoCv.contains("baseRows"),
                "AC-25 ③'：DTO 的 quoteCardValues 里应有 baseRows 结构，实际=" + trunc(dtoCv));
    }

    // ─────────────────────────── 读数helper（全部按本单收窄）───────────────────────────

    private String cardValues(Fx fx) {
        return scalar("SELECT quote_card_values::text FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' "
                + "ORDER BY created_at DESC, sort_order DESC LIMIT 1");
    }

    private long compData(Fx fx) {
        return count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id=cd.line_item_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "'");
    }

    private static String trunc(String s) {
        if (s == null) return "(NULL)";
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
