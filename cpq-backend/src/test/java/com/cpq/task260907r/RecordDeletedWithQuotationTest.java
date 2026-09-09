package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>D-48 · 删报价单不清 {@code _record}</b>（2026-09-08 主线亲验抓到，用户裁决本期修）。
 *
 * <h3>缺陷形状</h3>
 * {@code QuotationService.delete} 清了 {@code QuotationApproval} / {@code QuotationWithdrawRequest} /
 * {@code import_record} / pending V6 / 调价 job item，<b>一个 {@code _record} 表都没碰</b>；
 * 而 {@code ds_quote_*_record} 的<b>外键数 = 0</b>（设计如此）⇒ 数据库不会级联；
 * 唯一的 {@code _record} DELETE（{@code DsQuoteRecordService.deleteGroups}）按
 * {@code (quotation_id, customer_no, axis IN ...)} 收窄且只在重算时跑 ⇒ 够不到「单已经没了」。
 * <p>后果是<b>孤儿行按删单次数累积</b>（实测 {@code cpq_db_0724}：
 * {@code ds_quote_element_bom_record} 孤儿 27 / 总 206 = 13%）。
 *
 * <h3>🚨 三条防假绿的设计（每一条都对应一种「改坏了也全绿」的实现）</h3>
 * <ol>
 *   <li><b>非空守卫</b>：删之前先断言被删单的 {@code _record} 行数 {@code > 0}。
 *       没有这一条，「删后为 0」在<b>本来就没写进任何 {@code _record} 行</b>时<b>恒真</b> ——
 *       用例照样绿，但它验的是「什么都没有」，不是「删干净了」。</li>
 *   <li><b>反向证据（最要紧的一条）</b>：<b>另一张单</b>的 {@code _record} 行必须<b>逐字未变</b>
 *       （按行 {@code ::text} 排序后取 md5，全列参与）。
 *       🔑 {@code WHERE quotation_id = :qid} 写漏就是<b>清空全表</b>，
 *       而「我这单的行没了」在那种实现下<b>照样成立</b> ⇒ 只验正向 = 验不出误删。</li>
 *   <li><b>表清单取自 {@code information_schema}，不取自 Registry</b>。
 *       被测实现是<b>从 Registry 派生</b>表清单的；用例若也从 Registry 取，
 *       两侧共享同一个「哪些表算数」的理解 ⇒ Registry 少声明一张表时<b>两边一起漏，全绿</b>。
 *       ⇒ 用例问的是<b>物理库里实际存在哪些 {@code ds_quote_%_record}</b>，
 *       并先断言这个清单非空（清单查空 = 查错了模式，不是「很干净」）。</li>
 * </ol>
 *
 * <h3>双侧还原（把修复改回去必须变红）</h3>
 * 把 {@code QuotationService.delete} 里那两行 {@code deleteByQuotation} 注掉重跑：
 * {@link #t01_deleteQuotation_purgesAllRecordTables} 必须<b>红</b>在「删后仍有行」这一断言上；
 * {@link #t02_otherQuotationRecordsUntouched} 仍应<b>绿</b>（它验的是不误删，与修复方向无关）。
 */
@QuarkusTest
@DisplayName("🔴 D-48 · 删报价单必须清掉本单全部 ds_quote_*_record")
class RecordDeletedWithQuotationTest extends Task260907RBase {

    private static final String STALE = "ds_quote_record_stale";

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    // ══════════════════════════════════════════════════════════════════
    //  工具：表清单 / 计数 / 指纹 —— 一律走 information_schema，不问 Registry
    // ══════════════════════════════════════════════════════════════════

    /** 物理库里实际存在的 {@code ds_quote_*_record} 表名。🚫 不从 Registry 取（见类注释③）。 */
    private List<String> physicalRecordTables() {
        List<Object> raw = col("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name LIKE 'ds\\_quote\\_%\\_record' "
                + "ORDER BY table_name");
        List<String> out = new ArrayList<>();
        for (Object o : raw) out.add(String.valueOf(o));
        assertTrue(out.size() >= 13,
                "前置：物理库里的 ds_quote_*_record 表应 ≥ 13 张，实测 " + out.size()
                        + " 张 —— 查空 = 查错了模式/命名，不是「很干净」。清单=" + out);
        return out;
    }

    /** 某张单在<b>每一张</b> {@code _record} 表上的行数（0 的也记，便于报告里逐表列出）。 */
    private Map<String, Long> rowCounts(List<String> tables, UUID quotationId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : tables) {
            m.put(t, count("SELECT count(*) FROM " + sqlSafe(t)
                    + " WHERE quotation_id = '" + quotationId + "'"));
        }
        return m;
    }

    private static long total(Map<String, Long> counts) {
        long n = 0;
        for (Long v : counts.values()) n += v;
        return n;
    }

    /** 该单在该表上<b>全部列</b>的内容指纹（行 {@code ::text} 排序后 md5）。空 → {@code EMPTY}。 */
    private String digest(String table, UUID quotationId) {
        Object v = scalar("SELECT coalesce(md5(string_agg(x, '|')), 'EMPTY') FROM ("
                + "SELECT t::text AS x FROM " + sqlSafe(table) + " t"
                + " WHERE quotation_id = '" + quotationId + "' ORDER BY 1) s");
        return String.valueOf(v);
    }

    private Map<String, String> digests(List<String> tables, UUID quotationId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : tables) m.put(t, digest(t, quotationId));
        return m;
    }

    private Response deleteQuotation(UUID id) {
        return RestAssured.given().cookies(adminCookies())
                .when().delete("/api/cpq/quotations/" + id).thenReturn();
    }

    /** 造一张<b>真有 {@code _record} 行</b>的 DRAFT 单。 */
    private Fx seedOrderWithRecords(String label) {
        Fx fx = newFixture(label);
        String materialNo = PREFIX + "D48" + label + "-" + UUID.randomUUID().toString().substring(0, 6);
        List<EbomRow> rows = List.of(
                new EbomRow(1, "E1", "11.1", "1.1"),
                new EbomRow(2, "E2", "22.2", "2.2"));
        seedEbomMainGroup(fx, materialNo, rows, 1);
        Response r = saveDraftAdded(fx, materialNo, rows);
        requireStatusBeforeDiff(r, 200, label + " 保存草稿");
        return fx;
    }

    // ══════════════════════════════════════════════════════════════════
    //  t01：删单 ⇒ 全部 _record 表上该单 0 行
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("t01 · 删报价单后，该单在全部 ds_quote_*_record 上均为 0 行")
    void t01_deleteQuotation_purgesAllRecordTables() {
        assertProcessAlive();
        requireRecordLayer();

        List<String> tables = physicalRecordTables();
        Fx victim = seedOrderWithRecords("V");

        // 🔒 非空守卫：没有这一条，「删后为 0」恒真（见类注释①）
        Map<String, Long> before = rowCounts(tables, victim.quotationId());
        assertFixtureNonEmpty(total(before),
                "删之前 " + victim.quotationNo() + " 的 _record 行数（逐表：" + nonZero(before) + "）");

        Response del = deleteQuotation(victim.quotationId());
        assertTrue(del.statusCode() == 200 || del.statusCode() == 204,
                "DELETE /api/cpq/quotations/{id} 应成功，实际 " + del.statusCode()
                        + " body=" + del.getBody().asString());
        assertEquals(0L, count("SELECT count(*) FROM quotation WHERE id = '" + victim.quotationId() + "'"),
                "前置：单本身应已删除");

        Map<String, Long> after = rowCounts(tables, victim.quotationId());
        assertEquals(0L, total(after),
                "D-48：删单后该单在 ds_quote_*_record 上应 0 行，实际残留 " + nonZero(after)
                        + "（删前 " + nonZero(before) + "）");
    }

    // ══════════════════════════════════════════════════════════════════
    //  t02：反向证据 —— 别的单逐字未变（防「WHERE 漏写 ⇒ 清空全表」）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("t02 · 删甲单不动乙单：乙单的 _record 行逐字未变")
    void t02_otherQuotationRecordsUntouched() {
        assertProcessAlive();
        requireRecordLayer();

        List<String> tables = physicalRecordTables();
        Fx victim = seedOrderWithRecords("V2");
        Fx bystander = seedOrderWithRecords("B2");

        Map<String, Long> victimBefore = rowCounts(tables, victim.quotationId());
        Map<String, Long> byBefore = rowCounts(tables, bystander.quotationId());
        assertFixtureNonEmpty(total(victimBefore), "被删单的 _record 行数");
        // 🔒 旁观单也必须非空，否则「未变」= 空对空，验不出误删
        assertFixtureNonEmpty(total(byBefore), "旁观单的 _record 行数");
        Map<String, String> byDigestBefore = digests(tables, bystander.quotationId());
        // 全表规模也留一手：WHERE 漏写会把全表清空，那时旁观单的行同样会没
        long globalBefore = count("SELECT count(*) FROM ds_quote_element_bom_record");

        Response del = deleteQuotation(victim.quotationId());
        assertTrue(del.statusCode() == 200 || del.statusCode() == 204,
                "DELETE 应成功，实际 " + del.statusCode() + " body=" + del.getBody().asString());

        assertEquals(0L, total(rowCounts(tables, victim.quotationId())), "被删单应 0 行");
        assertEquals(byBefore, rowCounts(tables, bystander.quotationId()),
                "🔑 反向证据：旁观单 " + bystander.quotationNo() + " 的 _record 行数不许变");
        assertEquals(byDigestBefore, digests(tables, bystander.quotationId()),
                "🔑 反向证据：旁观单的 _record 内容必须逐字未变（全列 md5）");
        long globalAfter = count("SELECT count(*) FROM ds_quote_element_bom_record");
        assertEquals(globalBefore - total(victimBefore), globalAfter,
                "ds_quote_element_bom_record 全表只该少掉被删单那几行（删前 " + globalBefore
                        + "，被删单 " + total(victimBefore) + "，删后 " + globalAfter + "）");
    }

    // ══════════════════════════════════════════════════════════════════
    //  t03：D-35 的过期标记同样只挂 quotation_id、无外键 ⇒ 一并清
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("t03 · 删单一并清掉本单的 _record 过期标记，且不动别人的")
    void t03_staleMarkerPurgedForDeletedOrderOnly() {
        assertProcessAlive();
        if (!relationExists(STALE)) {
            // 表未落库 ⇒ 实现按约定 no-op。此处不判绿也不判红，直接跳过并说明。
            org.junit.jupiter.api.Assumptions.abort(
                    "标记表 " + STALE + " 未落库（V432 未应用）⇒ 本条无从验证");
        }
        Fx victim = newFixture("V3");
        Fx bystander = newFixture("B3");
        inTx(() -> {
            for (Fx f : List.of(victim, bystander)) {
                em.createNativeQuery("INSERT INTO " + STALE
                                + " (quotation_id, reason, detail, detected_by) "
                                + "VALUES (:qid, 'WRITE_FAILED', :d, 'D48-TEST')")
                        .setParameter("qid", f.quotationId())
                        .setParameter("d", PREFIX + "D48 自测标记")
                        .executeUpdate();
            }
        });
        assertEquals(1L, staleCount(victim.quotationId()), "前置：被删单应有 1 条标记");
        assertEquals(1L, staleCount(bystander.quotationId()), "前置：旁观单应有 1 条标记");

        Response del = deleteQuotation(victim.quotationId());
        assertTrue(del.statusCode() == 200 || del.statusCode() == 204,
                "DELETE 应成功，实际 " + del.statusCode() + " body=" + del.getBody().asString());

        assertEquals(0L, staleCount(victim.quotationId()),
                "D-48：单已不存在 ⇒ 它的过期标记没有消费者（find 按 quotation_id 收窄），应一并删掉");
        assertEquals(1L, staleCount(bystander.quotationId()),
                "🔑 反向证据：旁观单的标记不许被误删");

        // 自清：旁观单的标记随 cleanupOwnFixtures 之外单独清（该表不在 base 的清理面里）
        inTx(() -> em.createNativeQuery("DELETE FROM " + STALE + " WHERE detected_by = 'D48-TEST'")
                .executeUpdate());
    }

    private long staleCount(UUID quotationId) {
        return count("SELECT count(*) FROM " + STALE + " WHERE quotation_id = '" + quotationId + "'");
    }

    private static String nonZero(Map<String, Long> counts) {
        Map<String, Long> hit = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) if (e.getValue() > 0) hit.put(e.getKey(), e.getValue());
        return hit.isEmpty() ? "{全部 0}" : hit.toString();
    }
}
