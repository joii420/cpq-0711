package com.cpq.repair260908;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-1b</b>（阴性 · SQL 层）—— 问题说明 ⑥ 节原文：
 *
 * <blockquote>
 * 前置：{@code QT-20260908-0624} 的整单闭包（14 个料号）。<br>
 * 操作：直接执行「产品」页签视图 SQL，绑 {@code total_material_no} = 整单闭包、
 * {@code customerCode='CUST-0004'}。<br>
 * 断言：返回 <b>14 行</b>（现状 28 行）；
 * {@code count(*) filter (where customer_no<>'CUST-0004')} = <b>0</b>。
 * </blockquote>
 *
 * <h3>⚠️ 两个数字都不写死</h3>
 * AC 原文的 14 / 28 是 2026-09-08 的采样值。共享库上它<b>会漂移</b>，
 * 照抄会得到一个「今天绿、明天红、红了还看不出是数据变了」的判据。
 * ⇒ 本类<b>紧邻被测操作</b>重取三个基准（{@code closureSize} / {@code refUnderCustomer} /
 * {@code refAllCustomers}），并把实测值打印出来，与 AC 的 14/28 做<b>对照</b>而不是<b>等式</b>。
 *
 * <h3>🚨 断言从未执行 = 假绿（testing.md）</h3>
 * 三道空跑防护，任一不满足就<b>硬失败并判「未验证」</b>，🚫 不许静默通过：
 * <ol>
 *   <li>闭包必须非空（空闭包 ⇒ 产物恒 0 行 ⇒ 「跨客户行数=0」自动成立，零证据）；</li>
 *   <li>{@code refUnderCustomer} 必须 &gt; 0（本客户下真有数据）；</li>
 *   <li>{@code refAllCustomers > refUnderCustomer}（<b>真存在跨客户行</b>）——
 *       没有跨客户行时，加不加谓词结果一样，本条的鉴别力为 0。</li>
 * </ol>
 *
 * <h3>本片写入面 = 空</h3>
 * 只 SELECT。不造数、不清理、无造数前缀。
 */
@QuarkusTest
class Ac1bProductTabRowsTest extends S1CompileTestBase {

    /** 整单闭包 = 报价单的成品料号 ∪ 它们在 {@code ds_quote_material_bom} 上的直接子件（复现命令原文）。 */
    private List<String> closure() {
        return strList("""
                WITH roots AS (
                  SELECT DISTINCT product_part_no_snapshot pn FROM quotation_line_item
                   WHERE quotation_id = CAST(?1 AS uuid)),
                closure AS (
                  SELECT pn FROM roots
                  UNION
                  SELECT b.input_material_no FROM ds_quote_material_bom b
                    JOIN roots r ON r.pn = b.material_no)
                SELECT pn FROM closure WHERE pn IS NOT NULL ORDER BY 1
                """, AC1B_QUOTATION_ID);
    }

    // ═══════════════ 量具自证：行数计数器（test.md §3 强制） ═══════════════

    @Test
    @DisplayName("量具自证: 行数计数器必须能读出一个已知的 N —— 恒返 0 的计数器不许用")
    void gauge_rowCounterReadsKnownN() {
        List<String> cl = closure();
        assertFalse(cl.isEmpty(), notReady("AC-1b/量具",
                "报价单 " + AC1B_QUOTATION_ID + " 的整单闭包为空 —— 计数器无从自证", "环境数据"));

        String one = cl.get(0);
        long knownN = scalarLong(
                "SELECT count(*) FROM ds_quote_material WHERE material_no = ?1 AND customer_no = ?2",
                one, AC1B_CUSTOMER);
        System.out.println("[量具] 已知答案：料号 " + one + " 在 " + AC1B_CUSTOMER + " 下有 " + knownN + " 行");
        assertTrue(knownN > 0, notReady("AC-1b/量具",
                "料号 " + one + " 在 " + AC1B_CUSTOMER + " 下 0 行 —— 拿它当『已知 N』只能证明计数器会返 0", "环境数据"));

        ViewRow v = view();
        List<String> got = runCompiledSingleColumn("AC-1b/量具", recompile(v),
                AC1B_CUSTOMER, List.of(one), "hf_part_no");
        assertEquals(knownN, got.size(),
                "量具: 单料号 " + one + " 上，计数器应读出已知的 " + knownN + " 行，实得 " + got.size()
                        + "。计数器读不准，后面所有行数断言都不可信。实得内容=" + got);
        assertTrue(got.stream().allMatch(one::equals),
                "量具: 单料号查询返回了别的料号 " + got + " —— 说明绑参没生效");
    }

    // ═══════════════ AC-1b 主断言 ═══════════════

    @Test
    @DisplayName("AC-1b: 「产品」页签绑整单闭包 + customerCode=CUST-0004 ⇒ 行数 = 本客户参考行数；跨客户行 = 0")
    void ac1b_productTabNarrowedToCustomer() {
        List<String> cl = closure();
        assertFalse(cl.isEmpty(), notReady("AC-1b",
                "报价单 " + AC1B_QUOTATION_ID + " 查不到闭包（单据被删？）—— 断言会空跑", "环境数据"));

        // 🔑 三个基准全部紧邻重取，🚫 不照抄 AC 原文的 14 / 28
        long refUnderCustomer = scalarLong(
                "SELECT count(*) FROM ds_quote_material WHERE material_no = ANY(CAST(?1 AS text[])) "
                        + "AND customer_no = ?2", pgArray(cl), AC1B_CUSTOMER);
        long refAllCustomers = scalarLong(
                "SELECT count(*) FROM ds_quote_material WHERE material_no = ANY(CAST(?1 AS text[]))",
                pgArray(cl));
        System.out.println("[AC-1b] 紧邻取基准（库 cpq_db_0724）：闭包=" + cl.size()
                + " 料号，全客户 " + refAllCustomers + " 行，" + AC1B_CUSTOMER + " 下 " + refUnderCustomer
                + " 行，跨客户 " + (refAllCustomers - refUnderCustomer) + " 行"
                + "\n         AC 原文当日采样 = 14 / 28 / 14（仅作对照，不作断言）");

        // 空跑防护②③
        assertTrue(refUnderCustomer > 0, notReady("AC-1b",
                AC1B_CUSTOMER + " 在该闭包下 0 行 —— 断言退化成『0 == 0』，零证据", "环境数据"));
        assertTrue(refAllCustomers > refUnderCustomer, notReady("AC-1b",
                "该闭包下不存在跨客户行（全客户 " + refAllCustomers + " = 本客户 " + refUnderCustomer + "）"
                        + "\n  ⇒ 加不加客户谓词结果一样，本条的鉴别力为 0，属于恒绿判据。"
                        + "\n  🚫 不许当成通过 —— 请换一张有跨客户数据的单，或说明数据已被清理。", "环境数据"));

        ViewRow v = view();
        String sql = recompile(v);

        // ① 结构前置：产物里确实带上了客户谓词（否则下面的行数相等只可能是数据巧合）
        SqlShape.Block outer = SqlShape.outerBlocks(sql).get(0);
        assertTrue(SqlShape.hasCustomerCodePredicate(outer.whereTop(), outer.from().alias()),
                "AC-1b①: 重编译后「产品」页签外层 WHERE 应含 `" + outer.from().alias()
                        + ".customer_no = :customerCode`（B-1）。实得 WHERE(本层)=" + outer.whereTop()
                        + "\n  SQL=\n" + sql);

        // ② 行数：等于本客户参考行数，且严格小于全客户行数
        List<String> got = runCompiledSingleColumn("AC-1b", sql, AC1B_CUSTOMER, cl, "hf_part_no");
        assertEquals(refUnderCustomer, got.size(),
                "AC-1b②: 产物行数应等于 `ds_quote_material` 在 " + AC1B_CUSTOMER
                        + " 下的参考行数 " + refUnderCustomer + "，实得 " + got.size()
                        + "\n  （AC 原文当日为 14；若两者都变了，先确认闭包数据是否被并发线改动）");
        assertTrue(got.size() < refAllCustomers,
                "AC-1b②: 产物行数 " + got.size() + " 未小于全客户行数 " + refAllCustomers
                        + " ⇒ 客户谓词没有真正收窄，缺陷① 仍在");

        // ③ 跨客户行数 = 0：产物不输出 customer_no，改用「多重集与本客户参考集逐一致」证明
        //    —— 若混进别家的行，同一料号会出现 2 次，多重集必然对不上，比 count 强。
        List<String> ref = strList(
                "SELECT material_no FROM ds_quote_material WHERE material_no = ANY(CAST(?1 AS text[])) "
                        + "AND customer_no = ?2 ORDER BY 1", pgArray(cl), AC1B_CUSTOMER);
        List<String> gotSorted = new ArrayList<>(got);
        gotSorted.sort(String::compareTo);
        assertEquals(ref, gotSorted,
                "AC-1b③: 产物返回的料号多重集应与「" + AC1B_CUSTOMER + " 下的行」逐一相等 ⇒ 跨客户行数 = 0。"
                        + "\n  参考集(" + ref.size() + ")=" + ref
                        + "\n  实得集(" + gotSorted.size() + ")=" + gotSorted
                        + "\n  🔑 若某料号在实得集里出现 2 次而参考集只有 1 次，就是别的客户的行串进来了。");
    }

    // ═══════════════ 证伪设计（test.md §4）═══════════════
    //  把 B-1 在 applyFullScope 里新增的客户谓词注释掉后：
    //   · ac1b_productTabNarrowedToCustomer ①  ⇒ 红（产物 WHERE 里找不到谓词）
    //   · 同上 ②                              ⇒ 红（行数 = refAllCustomers 而非 refUnderCustomer）
    //   · 同上 ③                              ⇒ 红（每个料号出现 2 次，多重集对不上）
    //  三处独立报红 ⇒ 不是靠某一条侥幸。

    private ViewRow view() {
        ViewRow v = builderViews().stream().filter(x -> AC1B_VIEW.equals(x.viewName())).findFirst().orElse(null);
        assertNotNull(v, notReady("AC-1b",
                "库里找不到视图 " + AC1B_VIEW + "（AC 原文点名的「产品」页签视图）", "取数配置器"));
        assertEquals("ds_quote_material", v.anchorTable(),
                "AC-1b: " + AC1B_VIEW + " 的锚点应是 ds_quote_material（语义图口径），实得=" + v.anchorTable()
                        + "\n  ⚠️ 锚点变了说明模板被改过，本条 AC 的前提不再成立，请报主线。");
        return v;
    }

    /** {@code List<String>} → PG 文本数组字面量，供 {@code CAST(?1 AS text[])} 绑定。 */
    private static String pgArray(List<String> xs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < xs.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(xs.get(i).replace("\\", "\\\\")
                    .replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
