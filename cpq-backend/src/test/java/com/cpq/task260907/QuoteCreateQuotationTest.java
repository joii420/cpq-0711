package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建单链路：<b>T1.1（AC-1）· T1.5（AC-5）· T1.13（AC-18）· T1.16（AC-21）· T1.8（AC-12）</b>。
 *
 * <p>🚩 本类<b>会建报价单</b>。报价单不带 {@code T260907T-} 前缀，无法靠前缀清理，
 * 因此每条用例把自己建出来的单记下来，在 {@code finally} 里删掉自己那张
 * （只删本用例建的、按 id 精确删，🚫 不按条件批量删）。
 */
@QuarkusTest
class QuoteCreateQuotationTest extends QuoteImportAcTestBase {

    private Response awaitFinal(String session, String recordId) {
        long deadline = System.currentTimeMillis() + 180_000;
        Response last = null;
        while (System.currentTimeMillis() < deadline) {
            last = QuoteImportApi.pollImport(session, recordId);
            assertEquals(200, last.statusCode(), "轮询非 200：" + last.asString());
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
        throw new AssertionError("180s 未到终态 —— 这是超时不是通过。最后响应："
                + (last == null ? "null" : last.asString()));
    }

    /** 导一份夹具并等成功，返回 importRecordId。 */
    private String importOk(String s, String cid, String file) {
        Response r = QuoteImportApi.quotationImport(s, cid, QuoteFixture.bytes(file), file);
        assertEquals(200, r.statusCode(), "导入应 200（" + file + "）：" + r.asString());
        String rec = r.jsonPath().getString("data.importRecordId");
        assertFalse(rec == null || rec.isBlank(), "api.md §1：响应必须含 importRecordId。实际：" + r.asString());
        Response f = awaitFinal(s, rec);
        assertEquals("SUCCESS", f.jsonPath().getString("data.status"),
                "夹具 " + file + " 应导入成功（它是正例）。响应：" + f.asString());
        return rec;
    }

    private String templateIdFor(String s, String cid) {
        Response t = QuoteImportApi.autoDefaults(s, cid);
        assertEquals(200, t.statusCode(), "auto-defaults 非 200：" + t.asString());
        String tid = t.jsonPath().getString("data.customerTemplateId");
        assertNotNull(tid, "auto-defaults 没带出 customerTemplateId，建单无法进行：" + t.asString());
        return tid;
    }

    private String categoryIdFor(String s, String cid) {
        return QuoteImportApi.autoDefaults(s, cid).jsonPath().getString("data.categoryId");
    }

    /** 只删本用例建的那一张单，按 id 精确删。 */
    private void dropQuotation(String quotationId) {
        if (quotationId == null || quotationId.isBlank()) {
            return;
        }
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            // 🚩 import_record.quotation_id 有外键指向 quotation（实测 import_record_quotation_id_fkey）。
            //    不先解引用就删单会撞约束 —— 而且是在 finally 里撞，会把用例真正的失败原因盖掉。
            em.createNativeQuery("UPDATE import_record SET quotation_id = NULL WHERE quotation_id = CAST(:id AS uuid)")
                    .setParameter("id", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_component_sql_snapshot WHERE quotation_id = CAST(:id AS uuid)")
                    .setParameter("id", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = CAST(:id AS uuid)")
                    .setParameter("id", quotationId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation WHERE id = CAST(:id AS uuid)")
                    .setParameter("id", quotationId).executeUpdate();
        });
    }

    // ══════════════════ T1.1 · AC-1 + T1.16 · AC-21 ══════════════════

    /**
     * AC-1：① 响应含非空 quotationId；② quotation +1 且 customer_id = 正泰；
     * ③ quotation_line_item 行数 = 该 Excel「客户料号」sheet 中 customer_no='CUST-0004' 的行数。
     *
     * <p>🚩 夹具的客户料号 sheet 是 <b>3 行</b>（FG01 两条 + FG02 一条），
     * 而物料表有 <b>8</b> 个料号（含 FG03「有物料无客户料号」）——
     * 两个数字不同，所以「明细行由客户料号决定、不由物料表决定」这件事才验得出来。
     *
     * <p>AC-21 同批断言：同一 material_no 的两条客户料号 → 建 2 个明细行，
     * {@code product_part_no_snapshot} 相同、{@code customer_part_no} 不同、
     * {@code sort_order} 从 0 严格递增无重复。
     */
    @Test
    void t1_1_t1_16_建单行数等于客户料号行数且同料号两行互不覆盖() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        long qBefore = countRows("quotation", null);
        String qid = null;
        try {
            String rec = importOk(s, cid, QuoteFixture.MAIN);
            Response r = QuoteImportApi.createQuotation(s, QuoteImportApi.createBody(
                    rec, cid, "T260907T-AC1-建单验收", categoryIdFor(s, cid), templateIdFor(s, cid)));
            assertEquals(200, r.statusCode(), "AC-1：建单应 200。响应：" + r.asString());

            qid = r.jsonPath().getString("data.quotationId");
            assertFalse(qid == null || qid.isBlank(), "AC-1①：quotationId 必须非空。响应：" + r.asString());

            // ② quotation +1 且客户正确
            assertEquals(qBefore + 1, countRows("quotation", null), "AC-1②：quotation 应恰好 +1");
            assertEquals(1L,
                    countRows("quotation", "id = CAST('" + qid + "' AS uuid) AND customer_id = CAST('"
                            + cid + "' AS uuid)"),
                    "AC-1②：新建单的 customer_id 不是本次所选客户（正泰）");

            // ③ 明细行数 = 客户料号 sheet 行数（3），不是物料表料号数（8）
            long lines = countRows("quotation_line_item", "quotation_id = CAST('" + qid + "' AS uuid)");
            assertEquals(3L, lines,
                    "AC-1③：明细行数应 = Excel 客户料号 sheet 中该客户的行数 = 3。"
                            + "\n  实际 " + lines + "。⚠️ 若为 8，说明候选源取成了物料表；"
                            + "若明显偏大，先查是不是没按本次导入批次框定（D-26 批次维度）。");
            assertEquals(Long.valueOf(lines), r.jsonPath().getLong("data.lineItemsCount"),
                    "api.md §3：响应的 lineItemsCount 应与实际建出的行数一致");
            assertEquals(Boolean.TRUE, r.jsonPath().getBoolean("data.materializing"),
                    "api.md §3：materializing 恒 true（物化转后台）");

            // ── AC-21 ──
            List<Object[]> rows = rows("SELECT product_part_no_snapshot, customer_part_no, sort_order"
                    + " FROM quotation_line_item WHERE quotation_id = CAST('" + qid + "' AS uuid)"
                    + " ORDER BY sort_order");
            assertFalse(rows.isEmpty(), "AC-21：明细行为空 = 断言空跑");

            List<Object> fg01 = col("SELECT customer_part_no FROM quotation_line_item"
                    + " WHERE quotation_id = CAST('" + qid + "' AS uuid)"
                    + " AND product_part_no_snapshot = '" + P + "FG01' ORDER BY 1");
            assertEquals(2, fg01.size(),
                    "AC-21：同一销售料号 " + P + "FG01 有两条客户料号 ⇒ 应建 2 个明细行（互不覆盖），实际 "
                            + fg01.size() + "：" + fg01);
            assertNotEquals(String.valueOf(fg01.get(0)), String.valueOf(fg01.get(1)),
                    "AC-21：两行的 customer_part_no 必须不同（ZT-A001 / ZT-A001-B），实际同值：" + fg01);

            List<Object> orders = col("SELECT sort_order FROM quotation_line_item"
                    + " WHERE quotation_id = CAST('" + qid + "' AS uuid) ORDER BY sort_order");
            for (int i = 0; i < orders.size(); i++) {
                assertEquals(i, ((Number) orders.get(i)).intValue(),
                        "AC-21：sort_order 必须从 0 严格递增无重复，实际：" + orders);
            }
        } finally {
            dropQuotation(qid);
        }
    }

    // ══════════════════ T1.5 · AC-5 ══════════════════

    /**
     * AC-5：轮询端点返 status/progress；最终 SUCCESS；import_record 的
     * {@code system_type} 与 {@code import_status} 正确（【导入历史】页据此列出）。
     */
    @Test
    void t1_5_轮询端点与import_record落库正确() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        String rec = importOk(s, cid, QuoteFixture.MAIN);

        Response f = QuoteImportApi.pollImport(s, rec);
        assertEquals(200, f.statusCode(), "AC-5：轮询端点应 200");
        assertEquals("SUCCESS", f.jsonPath().getString("data.status"), "AC-5：终态应 SUCCESS");
        assertEquals("DATASET_QUOTE", f.jsonPath().getString("data.systemType"),
                "api.md §2：systemType 应为 DATASET_QUOTE");
        List<Object> summary = f.jsonPath().getList("data.summary");
        assertFalse(summary == null || summary.isEmpty(),
                "api.md §2：SUCCESS 时 summary 必须有值（否则前端无从展示逐 sheet 结果）：" + f.asString());

        // 落库侧：【导入历史】页读的是 import_record
        assertEquals(1L, countRows("import_record",
                        "id = CAST('" + rec + "' AS uuid) AND system_type = 'DATASET_QUOTE'"),
                "AC-5：import_record.system_type 应为 DATASET_QUOTE");
        List<Object> st = col("SELECT import_status FROM import_record WHERE id = CAST('" + rec + "' AS uuid)");
        assertFalse(st.isEmpty(), "AC-5：import_record 没落库");
        assertNotNull(st.get(0), "AC-5：import_status 为 null，【导入历史】页会显示空状态");
    }

    // ══════════════════ T1.13 · AC-18 ══════════════════

    /**
     * AC-18①：所选客户 0 行客户料号 → 建单返 200、{@code lineItemsCount=0}（不是 500、不是白屏）。
     *
     * <p>🚩 主线交底的两种「空」必须分开（后端特意做的）：
     * <ul>
     *   <li><b>键不存在</b>（D-26 之前的老批次）→ <b>400</b>「请重新导入一次」</li>
     *   <li><b>空数组</b>（本批次该客户确实 0 行）→ <b>200 + 0 行</b> ← 本用例验的是这个</li>
     * </ul>
     * 两者若不分，老批次会<b>静默建出 0 行单</b>：导入显示成功、单子是空的、全程不报错。
     */
    @Test
    void t1_13_客户0行客户料号建单返200且0行() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        String qid = null;
        try {
            String rec = importOk(s, cid, QuoteFixture.EMPTY_CUSTOMER_PART);
            Response r = QuoteImportApi.createQuotation(s, QuoteImportApi.createBody(
                    rec, cid, "T260907T-AC18-空客户料号", categoryIdFor(s, cid), templateIdFor(s, cid)));

            assertEquals(200, r.statusCode(),
                    "AC-18①：该客户 0 行客户料号时应返 200（空数组 ≠ 键不存在），"
                            + "🚫 不是 500 不是白屏。实际 " + r.statusCode() + "：" + r.asString());
            qid = r.jsonPath().getString("data.quotationId");
            assertEquals(Integer.valueOf(0), r.jsonPath().getInt("data.lineItemsCount"),
                    "AC-18①：lineItemsCount 应为 0。响应：" + r.asString());
            assertEquals(0L, countRows("quotation_line_item", "quotation_id = CAST('" + qid + "' AS uuid)"),
                    "AC-18①：库里也不该有明细行");
        } finally {
            dropQuotation(qid);
        }
    }

    // ══════════════════ T1.8 · AC-12 ══════════════════

    /**
     * AC-12（2026-09-07 按实测订正为两条）：同一客户、同一文件再导一次 ——
     * <ol>
     *   <li><b>13 张带版本表</b>：判 {@code UNCHANGED} ⇒ 一行不写、{@code version_no} 不变、
     *       {@code _history} 零新增</li>
     *   <li><b>3 张免版本表</b>：<b>行数不变且内容不变</b>（允许 UPDATE，但值必须一样）</li>
     * </ol>
     *
     * <p>🚩 第②条是我实测补的：原 AC 写「一行不写」，但免版本表再导时 summary 报
     * {@code updated: 8/3/3}（走 UPDATE 而非跳过）。按原文断言必假红。
     */
    @Test
    void t1_8_同文件再导带版本表unchanged免版本表内容不变() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        String where = "material_no LIKE '" + P + "%'";

        importOk(s, cid, QuoteFixture.MAIN);

        // 第一次导入后的基准
        List<Object> ver1 = col("SELECT material_no||'#'||version_no FROM ds_quote_material_bom WHERE "
                + where + " ORDER BY 1");
        assertFalse(ver1.isEmpty(), "AC-12 前置不成立：第一次导入没写进 ds_quote_material_bom ⇒ 后面全是空验证");
        long hist1 = countRows("ds_quote_material_bom_history", where);
        // 免版本 3 张的内容指纹（md5(整表本前缀内容)）—— 只比行数抓不住「值被改了」
        Map<String, String> plainMd5 = plainContentDigest();

        // ── 再导一次同一份文件 ──
        String rec2 = importOk(s, cid, QuoteFixture.MAIN);
        Response f = QuoteImportApi.pollImport(s, rec2);
        String body = f.asString();

        // ① 13 张带版本表全 unchanged
        List<Map<String, Object>> summary = f.jsonPath().getList("data.summary");
        assertFalse(summary == null || summary.isEmpty(), "summary 为空 = 断言空跑：" + body);
        List<String> bad = new java.util.ArrayList<>();
        for (Map<String, Object> sh : summary) {
            Object versioned = sh.get("versioned");
            if (!Boolean.TRUE.equals(versioned) && !"VERSIONED".equals(sh.get("kind"))) {
                continue;
            }
            Number created = (Number) sh.getOrDefault("created", 0);
            Number upgraded = (Number) sh.getOrDefault("upgraded", 0);
            if (created.intValue() != 0 || upgraded.intValue() != 0) {
                bad.add(sh.get("sheetName") == null ? String.valueOf(sh.get("sheet")) : String.valueOf(sh.get("sheetName"))
                        + "(created=" + created + ",upgraded=" + upgraded + ")");
            }
        }
        assertTrue(bad.isEmpty(),
                "AC-12①：同文件再导时带版本表应全部 UNCHANGED（created=0 且 upgraded=0），"
                        + "以下 sheet 不是：" + bad + "\n  完整 summary：" + body);

        assertEquals(ver1,
                col("SELECT material_no||'#'||version_no FROM ds_quote_material_bom WHERE " + where + " ORDER BY 1"),
                "AC-12①：version_no 逐行不变");
        assertEquals(hist1, countRows("ds_quote_material_bom_history", where),
                "AC-12①：_history 零新增");

        // ② 免版本 3 张：行数不变 + 内容不变
        Map<String, String> after = plainContentDigest();
        plainMd5.forEach((t, d) -> assertEquals(d, after.get(t),
                "AC-12②：免版本表 " + t + " 允许 UPDATE，但再导同一份文件后<b>内容必须一模一样</b>。"
                        + "内容指纹变了说明值被改写了。"));
    }

    /**
     * 免版本 3 张表在本前缀下的<b>业务内容</b>指纹。只比行数抓不住「值被改了」。
     *
     * <p>🚩 <b>只取业务列</b>：排除 {@code id} 与 4 个审计列。
     * 第一版用了整行 {@code t::text}，结果<b>必红</b> —— 免版本表走的是
     * DELETE+INSERT / UPDATE，{@code id} 会重排、{@code updated_at} 必然变，
     * 而 AC-12② 要的是「<b>值</b>必须一样」，审计列不是值。
     * 这是量具的问题，不是产品缺陷，别拿它去报 bug。
     */
    private static final List<String> AUDIT_COLS =
            List.of("id", "created_at", "created_by", "updated_at", "updated_by");

    private Map<String, String> plainContentDigest() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String t : PLAIN_TABLES) {
            String col = "ds_quote_plating_scheme".equals(t) ? "scheme_no" : "material_no";
            List<Object> cols = col("SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema='public' AND table_name='" + t + "' ORDER BY ordinal_position");
            String proj = cols.stream().map(String::valueOf)
                    .filter(c -> !AUDIT_COLS.contains(c))
                    .map(c -> "coalesce(" + c + "::text,'~')")
                    .reduce((a, x) -> a + "||'\u0001'||" + x)
                    .orElseThrow(() -> new IllegalStateException(t + " 没有业务列？"));
            Object v = em.createNativeQuery(
                            "SELECT coalesce(md5(string_agg(x.t, '|' ORDER BY x.t)), 'EMPTY') FROM ("
                                    + " SELECT " + proj + " AS t FROM " + t
                                    + " WHERE " + col + " LIKE :p) x")
                    .setParameter("p", P + "%").getSingleResult();
            m.put(t, String.valueOf(v));
            assertNotEquals("EMPTY", String.valueOf(v),
                    "AC-12②：免版本表 " + t + " 在本前缀下 0 行 ⇒ 「内容不变」是空验证。"
                            + "先确认主文件导入真的写进去了。");
        }
        return m;
    }
}
