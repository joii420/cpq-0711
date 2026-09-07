package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-AC-4 / A-AC-9</b>：指纹命中 → <b>纯引用，不写数据</b>。
 *
 * <p>A-AC-4 原文：「用<b>新编号</b>配一套<b>完全相同</b>的配置」⇒
 * 「① 复用原料号；② <b>{@code ds_quote_*} 四张表一行不写</b>（用户裁决：命中即引用）；
 * ③ {@code ds_quote_customer_part} 新增一行（一料号多编号）」。
 *
 * <h3>⚠️ A-AC-9「{@code VersionedGroupWriter} 零调用」在库外不可观测</h3>
 * 从数据库侧看，「没调用写入器」与「调用了但内容相同所以没写」<b>完全一致</b> ——
 * 两者都表现为零新增、零升版。⇒ 本类<b>不谎称验证了「零调用」</b>，
 * 只验它的可观测等价物（{@link #aac9_reuseWritesNothingObservable}）：
 * 四表零新增 + {@code version_no} 未变 + {@code _history} 零新增。
 * 🚩 真正的「零调用」需要日志/字节码层断言，那要读实现 —— 已作为口径问题报主线裁决。
 */
@QuarkusTest
@DisplayName("A-AC-4/9 指纹命中即引用，不写数据")
class FingerprintReuseNoWriteTest extends Task260903Base {

    /** AC-4 要求「完全相同的配置」，故两次提交共用同一份零件定义。 */
    private Map<String, Object> samePart() {
        return newPart("触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"),
                        material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1));
    }

    @Test
    @DisplayName("A-AC-4 新编号 + 相同配置 → 复用料号、四表零新增、customer_part +1")
    void aac4_fingerprintHitReusesPartNoAndWritesNothing() {
        Fx fx = newFixture("aac4");

        // ── 第一次：正常铸号并落库 ──
        assertSubmitOk(configure(fx, submitBody(PREFIX + "A4-1", samePart())), "A-AC-4 第一次提交");
        String partNo = latestLinePartNo(fx);
        long bom0 = assertNewTablesGotRows(partNo, "A-AC-4 第一次");
        long elem0 = dsElementBom(partNo);
        long mat0 = dsMaterial(partNo);
        long cp0 = dsCustomerPart(fx.customerNo());
        Map<String, Long> hist0 = historyCounts();
        System.out.println("[A-AC-4] 第一次后：material=" + mat0 + " bom=" + bom0 + " elem=" + elem0
                + " customer_part=" + cp0 + " history=" + hist0);
        assertTrue(cp0 > 0, "A-AC-4 前置：第一次提交后 ds_quote_customer_part 应已有行，实际 0 ⇒ 后面的 +1 断言会失真");

        // ── 第二次：换编号、配置完全相同 ──
        Response r2 = configure(fx, submitBody(PREFIX + "A4-2", samePart()));
        assertSubmitOk(r2, "A-AC-4 第二次提交（新编号 + 相同配置）");

        // ① 复用原料号
        String second = latestLinePartNo(fx);
        System.out.println("[A-AC-4①] 第一次料号=" + partNo + " 第二次=" + second
                + " fingerprintMatched=" + r2.jsonPath().get("fingerprintMatched"));
        assertEquals(partNo, second,
                "A-AC-4①：完全相同的配置必须复用原料号，实际铸了新号 " + second + " ⇒ 指纹未命中");

        // ② ds_quote_* 四张表一行不写
        long mat1 = dsMaterial(partNo), bom1 = dsMaterialBom(partNo), elem1 = dsElementBom(partNo);
        System.out.println("[A-AC-4②] 第二次后：material=" + mat1 + " bom=" + bom1 + " elem=" + elem1);
        assertEquals(mat0, mat1, "A-AC-4②：命中复用时 ds_quote_material 不得新增，" + mat0 + "→" + mat1);
        assertEquals(bom0, bom1, "A-AC-4②：命中复用时 ds_quote_material_bom 不得新增，" + bom0 + "→" + bom1);
        assertEquals(elem0, elem1, "A-AC-4②：命中复用时 ds_quote_element_bom 不得新增，" + elem0 + "→" + elem1);

        // ③ 客户产品编号表 +1（一料号多编号）
        long cp1 = dsCustomerPart(fx.customerNo());
        System.out.println("[A-AC-4③] ds_quote_customer_part " + cp0 + "→" + cp1);
        assertEquals(cp0 + 1, cp1,
                "A-AC-4③：一料号多编号 ⇒ ds_quote_customer_part 应恰好 +1 行，实际 " + cp0 + "→" + cp1);
        List<Object[]> cps = rows("SELECT customer_product_no, material_no FROM ds_quote_customer_part "
                + "WHERE customer_no='" + fx.customerNo() + "' ORDER BY customer_product_no");
        System.out.println("[A-AC-4③] 明细=" + cps.stream().map(java.util.Arrays::toString).toList());
        assertTrue(cps.stream().allMatch(r -> partNo.equals(String.valueOf(r[1]))),
                "A-AC-4③：两个编号都应指向同一料号 " + partNo + "，实际=" + cps.stream().map(java.util.Arrays::toString).toList());
    }

    /**
     * <b>A-AC-9</b> 的<b>可观测等价物</b>：复用路径上不发生任何写入与升版。
     *
     * <p>🚩 再次声明：这<b>不等于</b>断言了「{@code VersionedGroupWriter} 零调用」——
     * 库外看不出「没调用」与「调用了但没写」的区别。本条覆盖的是后者能被观测到的全部后果。
     */
    @Test
    @DisplayName("A-AC-9（可观测等价物）复用路径零写入、零升版、_history 零新增")
    void aac9_reuseWritesNothingObservable() {
        Fx fx = newFixture("aac9");

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A9-1", samePart())), "A-AC-9 第一次提交");
        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-9 第一次");

        List<Object> ver0 = col("SELECT DISTINCT version_no FROM ds_quote_material_bom "
                + "WHERE material_no='" + partNo + "' ORDER BY 1");
        Map<String, Long> hist0 = historyCounts();
        System.out.println("[A-AC-9] 复用前：version_no=" + ver0 + " history=" + hist0);

        assertSubmitOk(configure(fx, submitBody(PREFIX + "A9-2", samePart())), "A-AC-9 复用提交");

        List<Object> ver1 = col("SELECT DISTINCT version_no FROM ds_quote_material_bom "
                + "WHERE material_no='" + partNo + "' ORDER BY 1");
        Map<String, Long> hist1 = historyCounts();
        System.out.println("[A-AC-9] 复用后：version_no=" + ver1 + " history=" + hist1);

        assertEquals(ver0, ver1,
                "A-AC-9：复用不得改变 version_no（命中即早退，不进写入器），" + ver0 + "→" + ver1);
        assertEquals(hist0, hist1,
                "A-AC-9：复用不得产生 _history 行（有 _history 行 = 发生过升版 = 进了写入器），"
                        + hist0 + "→" + hist1);
    }
}
