package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入段的校验类 AC：<b>T1.2（AC-2）· T1.3（AC-3）· T1.4（AC-4）· T1.12（AC-17）</b>。
 *
 * <p>四条的共同判据都是<b>「一行未写」</b>（{@code task-260902} Phase 1 零写库语义），
 * 所以统一用「同一时刻基准快照 vs 事后快照逐表相等」断言，
 * 🚫 不写死 47 / 68 这类字面量 —— 共享库上别的会话随时在写。
 */
@QuarkusTest
class QuoteImportValidationTest extends QuoteImportAcTestBase {

    /** 轮询到终态；超时即失败（不许因为「还在跑」就当通过）。 */
    private Response awaitFinal(String session, String recordId) {
        long deadline = System.currentTimeMillis() + 120_000;
        Response last = null;
        while (System.currentTimeMillis() < deadline) {
            last = QuoteImportApi.pollImport(session, recordId);
            assertEquals(200, last.statusCode(),
                    "轮询端点非 200（HTTP " + last.statusCode() + "）：" + last.asString());
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
        throw new AssertionError("120s 内没走到终态（仍是 PROCESSING）——"
                + " 这不是「通过」，是超时。最后一次响应：" + (last == null ? "null" : last.asString()));
    }

    // ══════════════════ T1.2 · AC-2 ══════════════════

    /**
     * AC-2②③：绕过前端直接调端点、不传 customerId → 400「customerId 不能为空」；
     * 且 {@code ds_quote_*} 各表 count 逐表不变。
     */
    @Test
    void t1_2_不传customerId返400且一行未写() {
        String s = adminSession();
        var before = snapshotCounts();

        Response r = QuoteImportApi.quotationImportNoCustomer(
                s, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN);

        assertEquals(400, r.statusCode(),
                "AC-2②：缺 customerId 应返 400，实际 " + r.statusCode() + "。响应：" + r.asString());
        assertTrue(r.asString().contains("customerId"),
                "AC-2②：错误消息应点名 customerId（api.md §1「customerId 不能为空」）。实际：" + r.asString());

        assertCountsUnchanged(before, snapshotCounts(), "AC-2③：缺 customerId 被拒后不许写入任何数据");
    }

    // ══════════════════ T1.3 · AC-3 ══════════════════

    /**
     * AC-3：Excel「客户料号」sheet 含 CUST-0001 与 CUST-0004 两个客户的行，
     * 导入时选 CUST-0004 → <b>整份拒收</b>，并指出另一个客户编号；各表 count 逐表不变。
     *
     * <p>🚩 负例文件的第二个客户编号是 {@code CUST-0001}，<b>实测在 customer.code 命中</b> ——
     * 若用一个不存在的编号，会先被 D-19「客户编号不存在」拦掉，
     * <b>测到的是另一条规则，AC-3 空过</b>。
     */
    @Test
    void t1_3_跨客户文件整份拒收且一行未写() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        var before = snapshotCounts();

        Response r = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.CROSS_CUSTOMER), QuoteFixture.CROSS_CUSTOMER);

        // ① 同步段 200 + PROCESSING（D-33：内容类错误不是 400）
        assertEquals(200, r.statusCode(),
                "AC-3①（D-33）：内容类错误的同步段应返 200，🚫 不是 400 —— "
                        + "只有入参类错误（customerId 缺失）才是真 400。实际 " + r.statusCode() + "：" + r.asString());
        String rec = r.jsonPath().getString("data.importRecordId");
        assertFalse(rec == null || rec.isBlank(), "api.md §1：同步段必须给 importRecordId 供轮询。" + r.asString());

        // ② 轮询取回 FAILED
        Response f = awaitFinal(s, rec);
        String body = f.asString();
        assertEquals("FAILED", f.jsonPath().getString("data.status"),
                "AC-3②：跨客户文件必须整份拒收（轮询终态 FAILED）。响应：" + body);

        // 错误必须点名「本次客户」「闯入的客户编号」「行号」三要素 —— 少任何一个用户都不知道改哪
        assertTrue(body.contains(CUSTOMER_ROCKWELL),
                "AC-3②：错误信息必须点名文件里出现的其它客户编号 " + CUSTOMER_ROCKWELL + "。实际：" + body);
        assertTrue(body.contains(CUSTOMER_CHINT),
                "AC-3②：错误信息应点名本次导入客户 " + CUSTOMER_CHINT + "（否则用户不知道以谁为准）。实际：" + body);
        List<Map<String, Object>> errs = f.jsonPath().getList("data.errors");
        assertFalse(errs == null || errs.isEmpty(), "AC-3②：errors[] 为空 = 断言空跑。响应：" + body);
        assertTrue(errs.stream().anyMatch(e -> e.get("rowNum") != null),
                "AC-3②：错误条目必须带 rowNum（AC 原文要求「第 N 行」）。实际：" + body);

        // ③ 各表 count 逐表不变
        assertCountsUnchanged(before, snapshotCounts(), "AC-3③：跨客户文件整份拒收，不许写入任何数据");
    }

    /**
     * <b>D-19 与 B-15 必须同批报出</b>，且同一行同时命中两条规则时<b>各报一条</b>。
     *
     * <p>组合负例的「客户料号」sheet：
     * <pre>
     *   行2  CUST-0004          ← 合法，本次客户
     *   行3  CUST-0001          ← 合法客户但不是本次客户  ⇒ 只命中 B-15
     *   行4  T260907T-NOCUST1   ← 编号不存在且不是本次客户 ⇒ D-19 与 B-15 同时命中
     * </pre>
     *
     * <p>🚩 <b>为什么这条值得单写</b>：若实现「命中 D-19 就 return，不再跑 B-15」，
     * 用户会陷入<b>改一个看见下一个</b>的循环 —— 而 AC-4「逐条列出」的本意正是一次看全。
     * 只测单一负例（本包另两条）抓不住这个形态，因为那时两条规则不会在同一份文件里相遇。
     */
    @Test
    void t1_3b_D19与B15同批报出且同一行各报一条() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        var before = snapshotCounts();

        Response r = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.COMBINED_NEGATIVE), QuoteFixture.COMBINED_NEGATIVE);
        assertEquals(200, r.statusCode(), "同步段应 200（D-33）：" + r.asString());
        Response f = awaitFinal(s, r.jsonPath().getString("data.importRecordId"));
        String body = f.asString();
        assertEquals("FAILED", f.jsonPath().getString("data.status"), "组合负例应整份拒收：" + body);

        List<Map<String, Object>> errs = f.jsonPath().getList("data.errors");
        assertFalse(errs == null || errs.isEmpty(), "errors[] 为空 = 断言空跑：" + body);

        long d19 = errs.stream().filter(e -> String.valueOf(e.get("reason")).contains("未在客户档案中登记")).count();
        long b15 = errs.stream().filter(e -> String.valueOf(e.get("reason")).contains("其它客户编号")).count();

        assertTrue(d19 >= 1,
                "D-19 的错误没报出来（编号不存在）—— 实际 D-19 条数 " + d19 + "。响应：" + body);
        assertTrue(b15 >= 2,
                "B-15 的错误应有 2 条（行3 的 CUST-0001 与 行4 的不存在编号，两者都不是本次客户），"
                        + "实际 " + b15 + " 条 ⇒ 疑似「命中 D-19 就 return，不再跑 B-15」，"
                        + "用户会陷入改一个看见下一个的循环。响应：" + body);

        assertCountsUnchanged(before, snapshotCounts(), "组合负例同样是 Phase 1 零写库");
    }

    // ══════════════════ T1.4 · AC-4 ══════════════════

    /**
     * AC-4：含 {@code customer.code} 中不存在的客户编号 → 整份拒收并
     * <b>逐条</b>列出问题行；16 张表导入前后 count 逐表相等。
     *
     * <p>🚩 夹具里放了<b>两条</b>非法编号，就是为了验「逐条」而不是「只有第一条」——
     * 只放一条的话，返回 1 条和返回全部是同一个结果，断言等于没写。
     */
    @Test
    void t1_4_非法客户编号逐条列出且一行未写() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        var before = snapshotCounts();

        Response r = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.BAD_CUSTOMER), QuoteFixture.BAD_CUSTOMER);

        String body;
        if (r.statusCode() == 200) {
            String rec = r.jsonPath().getString("data.importRecordId");
            Response f = awaitFinal(s, rec);
            body = f.asString();
            assertEquals("FAILED", f.jsonPath().getString("data.status"),
                    "AC-4：非法客户编号必须整份拒收。响应：" + body);
            List<Object> errs = f.jsonPath().getList("data.errors");
            assertFalse(errs == null || errs.isEmpty(),
                    "AC-4：errors[] 为空 = 断言空跑。必须逐条列出问题行。响应：" + body);
        } else {
            assertEquals(400, r.statusCode(), "AC-4：应 400 拒收，实际 " + r.statusCode() + "：" + r.asString());
            body = r.asString();
        }

        // ── 判据 A（AC-4 本体）：「逐条」= 两条非法行都要各出一条错误，不是只报第一条 ──
        // 🚩 用「客户料号 sheet 的错误条数」判，而不是用值文本判 ——
        //    值是否回显属于 api.md 的契约（判据 B），两者混在一起会让红灯指向错误的责任方。
        long custErrs = body.split("\"sheetName\":\"客户料号\"", -1).length - 1;
        assertTrue(custErrs >= 2,
                "AC-4：夹具在「客户料号」sheet 放了 2 行非法编号，错误清单应<b>逐条</b>各出一条，"
                        + "实际只有 " + custErrs + " 条 ⇒ 是「只报第一条」。响应：" + body);

        // ── 判据 B（api.md §2 契约）：errors[] 条目应带 value，否则用户看不出是哪个值错了 ──
        assertTrue(body.contains(P + "NOCUST1") && body.contains(P + "NOCUST2"),
                "api.md §2 声明 errors[] 条目形如 {sheetName,rowNum,columnLabel,<b>value</b>,reason}，"
                        + "但实际响应<b>不含 value 字段</b> ⇒ 前端错误表格只能显示行号与原因，"
                        + "显示不出「到底填了什么」。这是契约不符，不是 AC-4 的「逐条」不成立"
                        + "（逐条那条已单独断言并通过）。响应：" + body);

        assertCountsUnchanged(before, snapshotCounts(),
                "AC-4：Phase 1 零写库 —— 校验失败时 16 张表 count 必须逐表相等");
    }

    // ══════════════════ T1.12 · AC-17 ══════════════════

    /**
     * AC-17：空 sheet（只有表头）导入 → 该 sheet 对应的表
     * <b>一行不动</b>（不升版、不归档、不清空）。
     *
     * <p>🚩 前提：该表得<b>先有行</b>，否则「不变」是 0→0 的空验证。
     * 所以本用例分两步：先用主文件把「年降系数」写进去，再导空 sheet 版本比对。
     */
    @Test
    void t1_12_空sheet不升版不归档不清空() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);

        // ── 第一步：先把年降系数写进去（否则「不变」是 0→0 的空验证） ──
        Response seed = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN);
        assertEquals(200, seed.statusCode(), "前置导入失败，AC-17 无从谈起：" + seed.asString());
        awaitFinal(s, seed.jsonPath().getString("data.importRecordId"));

        String where = "material_no LIKE '" + P + "%'";
        long rows0 = countRows("ds_quote_annual_discount", where);
        assertTrue(rows0 > 0,
                "AC-17 前置不成立：ds_quote_annual_discount 里没有本套夹具的行（实际 " + rows0
                        + "）⇒「不变」会是 0→0 的空验证。先确认主文件导入真的写进去了。");
        long hist0 = countRows("ds_quote_annual_discount_history", where);
        List<Object> ver0 = col("SELECT material_no||'#'||version_no FROM ds_quote_annual_discount WHERE "
                + where + " ORDER BY 1");

        // ── 第二步：导「年降系数」只剩表头的版本 ──
        Response r = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.EMPTY_SHEET_ANNUAL), QuoteFixture.EMPTY_SHEET_ANNUAL);
        assertEquals(200, r.statusCode(), "空 sheet 版本导入应成功（空 sheet 不是错误）：" + r.asString());
        Response f = awaitFinal(s, r.jsonPath().getString("data.importRecordId"));
        assertEquals("SUCCESS", f.jsonPath().getString("data.status"),
                "AC-17：空 sheet 不该让整份导入失败。响应：" + f.asString());

        assertEquals(rows0, countRows("ds_quote_annual_discount", where),
                "AC-17①：空 sheet 导入后该表行数变了（不许清空）");
        assertEquals(hist0, countRows("ds_quote_annual_discount_history", where),
                "AC-17③：空 sheet 导入后 _history 变了（不许归档）");
        assertEquals(ver0,
                col("SELECT material_no||'#'||version_no FROM ds_quote_annual_discount WHERE "
                        + where + " ORDER BY 1"),
                "AC-17②：空 sheet 导入后 version_no 变了（不许升版）");
    }
}
