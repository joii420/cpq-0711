package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>证伪实验 R-1 / R-4</b>（{@code test.md §5}）。
 *
 * <p>自动化用例首次全绿<b>不等于它真的在验</b>。本类的每条都先破坏被测行为、
 * 确认对应断言<b>硬失败</b>；不变红 = 白测。
 *
 * <h3>🚨 前置纪律：先证明干预本身生效</h3>
 * {@code task-260825} 栽过 —— 「用例没变红」可能是<b>干预没落地</b>而不是用例无效。
 * 所以每条实验都先断言「干预确实进去了」，再看断言是否变红。
 */
@QuarkusTest
class FalsificationTest extends QuoteImportAcTestBase {

    private Response awaitFinal(String s, String rec) {
        long deadline = System.currentTimeMillis() + 180_000;
        Response last = null;
        while (System.currentTimeMillis() < deadline) {
            last = QuoteImportApi.pollImport(s, rec);
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
        throw new AssertionError("180s 未到终态。最后响应：" + (last == null ? "null" : last.asString()));
    }

    // ══════════════════ R-1 ══════════════════

    /**
     * <b>R-1 · 阳性对照</b>：证明 T1.4 里那句「16 表 count 逐表相等」<b>是活的断言</b>，
     * 不是因为「基线本就没写入」而恒真的空跑。
     *
     * <h3>为什么用阳性对照，而不是 test.md 原案的「注释掉校验器」</h3>
     * 原案要求临时改 {@code com/cpq/dataset/} 下的实现代码。两条现实约束否掉了它：
     * <ol>
     *   <li>本会话<b>不读也不改实现目录</b>（用例从 AC 派生的前提）；</li>
     *   <li>后端代理正在<b>同一个 worktree</b> 里改这些文件，
     *       我改了再改回去会与它互相覆盖 —— 那会制造一个比它要证的问题更糟的问题。</li>
     * </ol>
     * <b>等价性论证</b>：R-1 真正要排除的失败模式是「快照量具压根测不到写入，所以永远相等」。
     * 阳性对照直接打这个点：<b>拿一次一定会写库的成功导入去跑同一套快照比对，它必须红。</b>
     * 红了就证明量具能测到写入 ⇒ T1.4 里它保持相等是真结论，不是空跑。
     */
    @Test
    void r1_成功导入必须让count快照比对硬失败_证明该断言不是空跑() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);

        var before = snapshotCounts();

        // 干预：换成一份<b>一定会写库</b>的正例文件（T1.4 用的是被拒收的负例）
        Response r = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN);
        assertEquals(200, r.statusCode(), "干预未落地：正例文件都没导进去，后面的结论不成立");
        Response f = awaitFinal(s, r.jsonPath().getString("data.importRecordId"));
        assertEquals("SUCCESS", f.jsonPath().getString("data.status"),
                "干预未落地：正例文件没导成功 ⇒ 没有发生写入 ⇒ 本实验证明不了任何事");

        var after = snapshotCounts();

        // 期望：同一套断言必须硬失败
        AssertionError caught = null;
        try {
            assertCountsUnchanged(before, after, "（R-1 阳性对照，预期在此失败）");
        } catch (AssertionError e) {
            caught = e;
        }
        assertFalse(caught == null,
                "🚨 R-1 不通过：发生了一次确凿的成功写入，「16 表 count 逐表相等」却<b>没有变红</b>。"
                        + "⇒ 该断言是空跑的，T1.2 / T1.3 / T1.4 里所有依赖它的『一行未写』结论全部不可信。");

        // 并且要真的看到行数增加，而不是靠别的原因红
        long matBefore = before.get("ds_quote_material");
        long matAfter = after.get("ds_quote_material");
        assertTrue(matAfter > matBefore,
                "R-1：ds_quote_material 应因本次导入而增加（" + matBefore + " → " + matAfter + "）");
    }

    // ══════════════════ R-4 ══════════════════

    /**
     * <b>R-4</b>：证明 AC-12 的 {@code UNCHANGED} 判定<b>真的走到了指纹比对</b>，
     * 而不是「压根没比就报 unchanged」。
     *
     * <p>干预：{@code T260907-R4-改一格.xlsx} 相对主文件<b>只差一格</b> ——
     * 物料BOM / FG01 第 1 行的「组成数量」2 → 99（该列是物料BOM 的<b>对比项</b>）。
     * 期望：该轴从 {@code unchanged} 变 {@code upgraded}，{@code version_no} +1，
     * {@code _history} 增加，而<b>其它轴仍是 unchanged</b>。
     *
     * <p>🚩 最后那半句是关键：只断言「变了」抓不住「所有轴都被无脑升版」这种反向缺陷。
     */
    @Test
    void r4_改一个对比项列的值必须让该轴从unchanged变upgraded() {
        String s = adminSession();
        String cid = customerIdOf(CUSTOMER_CHINT);
        String where = "material_no LIKE '" + P + "%'";

        // 基线：导主文件两次，确认第二次是 unchanged（否则下面的「变化」无从对比）
        String rec1 = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN)
                .jsonPath().getString("data.importRecordId");
        awaitFinal(s, rec1);
        String rec2 = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.MAIN), QuoteFixture.MAIN)
                .jsonPath().getString("data.importRecordId");
        Map<String, Object> bomBase = sheetSummary(awaitFinal(s, rec2), "物料BOM");
        assertEquals(0, ((Number) bomBase.getOrDefault("upgraded", 0)).intValue(),
                "干预未落地的前置不成立：同文件再导时物料BOM 就已经在升版了，"
                        + "R-4 无法区分「指纹比对生效」与「无脑升版」。summary：" + bomBase);

        long fg01VerBefore = versionOf(P + "FG01");
        long histBefore = countRows("ds_quote_material_bom_history", where);

        // ── 干预：只差一格的文件 ──
        String rec3 = QuoteImportApi.quotationImport(
                s, cid, QuoteFixture.bytes(QuoteFixture.R4_ONE_CELL), QuoteFixture.R4_ONE_CELL)
                .jsonPath().getString("data.importRecordId");
        Map<String, Object> bom = sheetSummary(awaitFinal(s, rec3), "物料BOM");

        int upgraded = ((Number) bom.getOrDefault("upgraded", 0)).intValue();
        int unchanged = ((Number) bom.getOrDefault("unchanged", 0)).intValue();

        assertEquals(1, upgraded,
                "🚨 R-4 不通过：改了「组成数量」（物料BOM 的对比项列）之后，upgraded 应为 1，实际 "
                        + upgraded + "。\n  为 0 ⇒ 指纹比对没生效，或该列不在对比项里 ⇒ "
                        + "AC-12 的 unchanged 是「压根没比」而不是「比过且相同」，T1.8 全部不可信。"
                        + "\n  summary：" + bom);
        assertTrue(unchanged >= 2,
                "R-4 反向守卫：只改了 FG01 一个轴，其余轴（FG02 / RM01）应仍是 unchanged，实际 unchanged="
                        + unchanged + " ⇒ 疑似所有轴被无脑升版。summary：" + bom);

        assertEquals(fg01VerBefore + 1, versionOf(P + "FG01"),
                "R-4：FG01 的 version_no 应 +1");
        assertTrue(countRows("ds_quote_material_bom_history", where) > histBefore,
                "R-4：升版必须把旧行整组归档进 _history");
        assertNotEquals(fg01VerBefore, versionOf(P + "FG01"), "R-4：version_no 没动");
    }

    private long versionOf(String materialNo) {
        List<Object> v = col("SELECT DISTINCT version_no FROM ds_quote_material_bom"
                + " WHERE material_no = '" + materialNo + "'");
        assertFalse(v.isEmpty(), "轴 " + materialNo + " 在 ds_quote_material_bom 里没有行 ⇒ 断言空跑");
        return ((Number) v.get(0)).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sheetSummary(Response f, String sheetName) {
        List<Map<String, Object>> summary = f.jsonPath().getList("data.summary");
        assertFalse(summary == null || summary.isEmpty(), "summary 为空 = 断言空跑：" + f.asString());
        return summary.stream()
                .filter(m -> sheetName.equals(m.get("sheetName")) || sheetName.equals(m.get("sheet")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("summary 里没有 sheet「" + sheetName + "」：" + f.asString()));
    }
}
