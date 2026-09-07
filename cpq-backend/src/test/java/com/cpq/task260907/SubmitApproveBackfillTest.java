package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 序列链：<b>T1.6（AC-10 提交）→ T1.7（AC-11 核价通过）→ T1.11（AC-16 回填 no-op）</b>。
 *
 * <p>三条是一条链，必须在同一个用例里串起来跑：AC-16 要断言的是
 * 「<b>AC-11 那次核价通过</b>」前后 V6 八张表逐字不变，拆开跑就不是同一次了。
 *
 * <h3>⏱ 为什么这条要抢先跑</h3>
 * 并发的「核价回填」线一旦挂上<b>新回填路径</b>，AC-16 的 no-op 断言就不再成立。
 * 该线已承诺挂之前通知，但<b>越早跑越保险</b>。
 *
 * <h3>🚩 V6 八张表是怎么定下来的</h3>
 * 任务文档只写「V6 八张表」没有列名单。<b>不猜</b>，改用结构特征机械推导：
 * 同时具备 {@code is_current} 与 {@code pending_quotation_id} 两列的基表
 * （这是 V6 回填目标的签名，{@code f_material_element_price} 的
 * {@code candidate_materials} 也正是按这两列取数）。
 * 2026-09-07 实查得到<b>恰好 8 张</b>，与「八张表」吻合：
 * {@code annual_discount · capacity · element_bom · element_bom_item ·
 *  material_bom · material_bom_item · plating_scheme · unit_price}
 * （另有 2 个 {@code v_compat_*} 视图命中同一特征，是视图不是表，已排除）。
 */
@QuarkusTest
class SubmitApproveBackfillTest extends QuoteImportAcTestBase {

    /** AC-16② 的对照面。见类注释的推导过程。 */
    private static final List<String> V6_EIGHT = List.of(
            "annual_discount", "capacity", "element_bom", "element_bom_item",
            "material_bom", "material_bom_item", "plating_scheme", "unit_price");

    /** S-5 交付的 ds 原生模板的页签数（D-34：物料BOM 单独推迟，故是 13 不是 14）。 */
    private static final int DS_TEMPLATE_TABS = 13;

    private Response awaitFinal(String s, String rec) {
        long deadline = System.currentTimeMillis() + 180_000;
        Response last = null;
        while (System.currentTimeMillis() < deadline) {
            last = QuoteImportApi.pollImport(s, rec);
            String st = last.jsonPath().getString("data.status");
            if ("SUCCESS".equals(st) || "FAILED".equals(st)) {
                return last;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("180s 未到终态。最后响应：" + (last == null ? "null" : last.asString()));
    }

    /**
     * 机械定位「ds 原生模板」：{@code QUOTATION} + {@code PUBLISHED} +
     * <b>其组件全部 {@code builder_version} 非空</b>（= 全部由取数配置器生成）。
     * 🚩 不写死 templateId —— 写死的话模板一重建用例就红，而那不是缺陷。
     */
    private String dsNativeTemplateId() {
        List<Object> ids = col(
                "SELECT t.id::text FROM template t"
                        + " WHERE t.template_kind='QUOTATION' AND t.status='PUBLISHED'"
                        + "   AND EXISTS (SELECT 1 FROM template_component tc WHERE tc.template_id=t.id)"
                        // 🚩 builder_version 在 component_sql_view 上，不在 component 上（实查确认）
                        + "   AND NOT EXISTS ("
                        + "     SELECT 1 FROM template_component tc"
                        + "       JOIN component c ON c.id = tc.component_id"
                        + "       LEFT JOIN component_sql_view csv ON csv.component_id = c.id"
                        + "     WHERE tc.template_id = t.id AND csv.builder_version IS NULL)"
                        + " ORDER BY t.created_at DESC");
        assertFalse(ids.isEmpty(),
                "库里找不到「全部组件由取数配置器生成」的 PUBLISHED 报价模板 ⇒ S-5 未就绪，"
                        + "AC-10/11/16 的前置不成立（此时跑出来的绿是拿旧模板骗出来的）");
        String id = String.valueOf(ids.get(0));
        long tabs = countRows("template_component", "template_id = CAST('" + id + "' AS uuid)");
        assertEquals(DS_TEMPLATE_TABS, tabs,
                "ds 原生模板的页签数应为 " + DS_TEMPLATE_TABS + "（D-34：物料BOM 单独推迟），实际 " + tabs);
        return id;
    }

    /** 八张表逐表内容 md5（🚫 只比行数不够 —— 回填改的是 is_current 与列值，行数可能不变而内容已变）。 */
    private Map<String, String> v6Digest() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : V6_EIGHT) {
            assertTrue(tableExists(t), "V6 表 " + t + " 不存在 ⇒ 对照面不完整，AC-16② 会假绿");
            Object v = em.createNativeQuery(
                    "SELECT coalesce(md5(string_agg(x.t,'|' ORDER BY x.t)),'EMPTY') FROM ("
                            + " SELECT " + t + "::text AS t FROM " + t + ") x").getSingleResult();
            String d = String.valueOf(v);
            assertFalse("EMPTY".equals(d),
                    "V6 表 " + t + " 是空表 ⇒ 「内容逐字不变」在它上面是空验证。"
                            + "AC-16 的对照面必须非空。");
            m.put(t, d);
        }
        return m;
    }

    private void dropQuotation(String qid) {
        if (qid == null || qid.isBlank()) {
            return;
        }
        try {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("UPDATE import_record SET quotation_id=NULL WHERE quotation_id=CAST(:i AS uuid)")
                        .setParameter("i", qid).executeUpdate();
                // 🚩 实查：9 张表有指向 quotation 的外键。提交/核价过的单还会挂 costing_order，
                //    只删 line_item + sql_snapshot 会在 finally 里静默失败，留下 SUBMITTED 残留
                //    （2026-09-07 实际发生过一次，留下 036b25d7）。
                for (String t : List.of("quotation_component_sql_snapshot", "quotation_line_item",
                        "quotation_approval", "quotation_price_revision", "quotation_view_structure",
                        "quotation_withdraw_request", "costing_order")) {
                    em.createNativeQuery("DELETE FROM " + t + " WHERE quotation_id=CAST(:i AS uuid)")
                            .setParameter("i", qid).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM quotation WHERE id=CAST(:i AS uuid)")
                        .setParameter("i", qid).executeUpdate();
            });
        } catch (RuntimeException e) {
            // 🚩 清理失败必须单独报告，🚫 不许让它顶替用例本身的成败 ——
            //    上一轮 dropQuotation 撞外键，看起来像 T1.1/T1.13 失败，其实两条断言都通过了。
            System.err.println("⚠️ [T1.6链] 清理报价单 " + qid + " 失败（用例结论不受影响）：" + e);
        }
    }

    @Test
    void t1_6_t1_7_t1_11_提交_核价通过_且回填静默降级为noop() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        String tpl = dsNativeTemplateId();
        String qid = null;

        // 捕获后端日志（AC-16①③ 要看 QuoteBackfillService 摘要 + 无 ERROR）
        List<LogRecord> logs = new ArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) { synchronized (logs) { logs.add(r); } }
            @Override public void flush() { }
            @Override public void close() { }
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.addHandler(h);

        try {
            // ── 前置：导入 + 建单 ──
            String rec = QuoteImportApi.quotationImport(
                    s, cid, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN)
                    .jsonPath().getString("data.importRecordId");
            assertEquals("SUCCESS", awaitFinal(s, rec).jsonPath().getString("data.status"), "前置导入应成功");

            Response cr = QuoteImportApi.createQuotation(s, QuoteImportApi.createBody(
                    rec, cid, "T260907T-AC10-提交核价链", null, tpl));
            assertEquals(200, cr.statusCode(), "前置建单失败：" + cr.asString());
            qid = cr.jsonPath().getString("data.quotationId");
            assertNotNull(qid, "前置建单没给 quotationId");

            // 物化（提交前要确保卡片值已算）
            RestAssured.given().cookie("CPQ_SESSION", s).contentType(ContentType.JSON)
                    .when().post("/api/cpq/quotations/{id}/ensure-card-values", qid);

            // ══════ T1.6 · AC-10 ══════
            // 🚩 不带 Content-Type 会返 415（实测），不是权限问题也不是端点没做
            Response sub = RestAssured.given().cookie("CPQ_SESSION", s).contentType(ContentType.JSON)
                    .when().post("/api/cpq/quotations/{id}/submit", qid);
            assertEquals(200, sub.statusCode(), "AC-10：提交应 200。响应：" + sub.asString());

            List<Object> st = col("SELECT status FROM quotation WHERE id=CAST('" + qid + "' AS uuid)");
            assertEquals("SUBMITTED", String.valueOf(st.get(0)), "AC-10①：状态应变 SUBMITTED");

            long snap = count("SELECT count(*) FROM quotation WHERE id=CAST('" + qid + "' AS uuid)"
                    + " AND submission_snapshot IS NOT NULL");
            assertEquals(1L, snap, "AC-10②：submission_snapshot 必须非空");

            long sqlSnap = countRows("quotation_component_sql_snapshot",
                    "quotation_id = CAST('" + qid + "' AS uuid)");
            assertEquals(DS_TEMPLATE_TABS, sqlSnap,
                    "AC-10③：quotation_component_sql_snapshot 应落该单的 " + DS_TEMPLATE_TABS
                            + " 段 SQL（D-34：13 不是 14），实际 " + sqlSnap);

            // ══════ AC-16 的跑前基线（必须在核价通过之前取） ══════
            Map<String, String> before = v6Digest();
            Map<String, Long> beforeCounts = new LinkedHashMap<>();
            V6_EIGHT.forEach(t -> beforeCounts.put(t, countRows(t, null)));
            synchronized (logs) { logs.clear(); }   // 只看核价通过这一段的日志

            // ══════ T1.7 · AC-11 ══════
            // 🚩 实测：直接 approve 会返 400「previewToken 缺失，请先调用回填影响预览接口」。
            //    ⇒ 核价通过是<b>两步</b>：先 GET 预览拿 token，再带 token approve。
            //    这一步同时是 AC-16 的正向旁证：预览就是「回填影响」的官方视图。
            String pmSession = pricingManagerSession();
            Response pv = RestAssured.given().cookie("CPQ_SESSION", pmSession)
                    .when().get("/api/cpq/quotations/{id}/costing-approve/preview", qid);
            assertEquals(200, pv.statusCode(), "回填影响预览应 200。响应：" + pv.asString());
            String previewToken = pv.jsonPath().getString("data.previewToken");
            assertNotNull(previewToken,
                    "预览未返回 previewToken，核价通过无法进行。响应：" + pv.asString());
            System.out.println("📎 AC-16 回填影响预览（新链路单，期望零影响）：" + pv.asString());

            // ══════ AC-16① 的结构化证据（比 grep 日志更硬） ══════
            // 🚩 预览返回的 summary 四个计数器的<b>字段名与 AC-16① 原文逐字对应</b>，
            //    且它是 backfill planner 的产物 —— summary 存在且 groups 为空，
            //    正好证明「<b>跑了但没东西可回填</b>」而不是「压根没跑」（B-13 要的正是这个区分）。
            assertNotNull(pv.jsonPath().get("data.summary"),
                    "预览没有 summary ⇒ 无法区分「跑了但降级」与「压根没跑」，AC-16① 不成立。" + pv.asString());
            for (String k : List.of("versionedGroups", "addedRows", "deletedRows", "changedRows")) {
                assertEquals(0, pv.jsonPath().getInt("data.summary." + k),
                        "AC-16①：新链路单的回填影响 " + k + " 应为 0，实际 "
                                + pv.jsonPath().getInt("data.summary." + k) + "。响应：" + pv.asString());
            }

            Map<String, Object> approveBody = new LinkedHashMap<>();
            approveBody.put("comment", "T260907T 自动化验收");
            approveBody.put("previewToken", previewToken);
            Response ap = RestAssured.given()
                    .cookie("CPQ_SESSION", pmSession)
                    .contentType(ContentType.JSON).body(approveBody)
                    .when().post("/api/cpq/quotations/{id}/costing-approve", qid);
            assertEquals(200, ap.statusCode(),
                    "AC-11①：核价通过应返 200 且不抛异常。响应：" + ap.asString());
            List<Object> st2 = col("SELECT status FROM quotation WHERE id=CAST('" + qid + "' AS uuid)");
            assertEquals("APPROVED", String.valueOf(st2.get(0)), "AC-11①：状态应变 APPROVED");

            // ══════ T1.11 · AC-16 ══════
            List<LogRecord> snapshotLogs;
            synchronized (logs) { snapshotLogs = new ArrayList<>(logs); }

            // ③ 无 ERROR
            List<String> errors = snapshotLogs.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                    .map(r -> r.getLoggerName() + ": " + r.getMessage())
                    .toList();
            assertTrue(errors.isEmpty(), "AC-16③：核价通过过程中出现 ERROR 日志：" + errors);

            // ① QuoteBackfillService 摘要 0/0/0/0
            List<String> backfill = snapshotLogs.stream()
                    .map(r -> String.valueOf(r.getMessage()))
                    .filter(m -> m != null && m.contains("QuoteBackfill"))
                    .toList();
            if (backfill.isEmpty()) {
                // 🚩 关键区分：AC-16 要的是「跑了但降级」，不是「压根没跑」。
                //    日志抓不到时不许直接判过 —— 那正是「没跑」与「跑了 no-op」不可分的情形。
                System.err.println("⚠️ AC-16①：未捕获到 QuoteBackfillService 摘要日志。"
                        + "可能是日志级别未开或摘要不含该关键字 ——"
                        + "本条以「未验证」结案，不许当通过。捕获到的日志条数=" + snapshotLogs.size());
            } else {
                assertTrue(backfill.stream().anyMatch(m ->
                                m.contains("0") && !m.matches(".*(addedRows|added)=[1-9].*")),
                        "AC-16①：QuoteBackfillService 摘要应为 0/0/0/0，实际：" + backfill);
            }

            // ② V6 八张表行数与内容逐字不变
            Map<String, String> after = v6Digest();
            List<String> changed = new ArrayList<>();
            before.forEach((t, d) -> {
                if (!d.equals(after.get(t))) {
                    changed.add(t + "（内容 md5 变了：" + d + " → " + after.get(t) + "）");
                }
                long b = beforeCounts.get(t);
                long a = countRows(t, null);
                if (b != a) {
                    changed.add(t + "（行数 " + b + " → " + a + "）");
                }
            });
            assertTrue(changed.isEmpty(),
                    "AC-16②：新单核价通过时 QuoteBackfillService 应静默降级为 no-op，"
                            + "V6 八张表内容与行数必须逐字不变。以下变了：" + changed
                            + "\n  ⚠️ 归因前先排除并发会话：共享库上别人也在写这八张表。"
                            + "\n  ⚠️ 若「核价回填」线已挂上新回填路径，本条断言按设计就不再成立 —— 那不是缺陷。");
        } finally {
            root.removeHandler(h);
            dropQuotation(qid);
        }
    }
}
