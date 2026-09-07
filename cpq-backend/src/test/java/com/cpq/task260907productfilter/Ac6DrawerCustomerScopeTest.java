package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B4 · AC-6</b>（抽屉按行携带的客户号取数）。
 *
 * <p>需求文档.md §③ AC-6：
 * 前置——壳页处于「所有客户」，列表中 X 有 A、B 两行；操作——点开 A 那一行的抽屉；
 * 断言——① 抽屉内任一 sheet 页签的行，customer_no 全 = A；② 不出现任何 B 的行；
 * ③ 版本下拉只列 A 的版本，不得把两个客户的版本号混排。
 *
 * <p>本类覆盖 ①②（用「物料」sheet —— {@code ds_quote_material} 本身，overview/rows 两端点，
 * 与 AC-3/AC-5 同一份两客户同料号夹具即可验证隔离）。
 *
 * <p>③ 用<b>版本化子表</b>「物料BOM」（{@code sheetKey="MATERIAL_BOM"} → 表
 * {@code ds_quote_material_bom}，主线 2026-09-07 核实）验证——「物料」sheet 本身是免版本表
 * （test.md §0 实测：13 张带版本表里不含它），版本号语义要在版本化子表上才有意义。
 * <p>🚨 <b>实测（2026-09-07）{@code ds_quote_material_bom} 尚未加 {@code customer_no} 列</b>
 * ——外部依赖（{@code task-260907-报价侧加客户维度}）仍未落地，③ 的用例会经
 * {@link #assumeMaterialBomCustomerDimensionReady} 干净跳过（{@code skipped}，不是 {@code passed}），
 * 落地后重跑即可真正生效，不需要改代码。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-6 抽屉按行携带的客户号取数")
@QuarkusTest
class Ac6DrawerCustomerScopeTest extends PfTestBase {

    private static final String X = FX + "DUPX6";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-6");
        insertMaterialRow(X, CUST_A, FX + "PRODA6");
        insertMaterialRow(X, CUST_B, FX + "PRODB6");
    }

    @Test
    @DisplayName("AC-6①②：overview 携带 customerNo=A 时不应把 B 的配置计数混进来")
    void overviewScopedToRowCustomer() {
        Response ra = PfApi.overview(adminSession(), PfApi.QUOTE, X, CUST_A);
        Response rb = PfApi.overview(adminSession(), PfApi.QUOTE, X, CUST_B);
        assertEquals(200, ra.statusCode(), "overview(A) body=" + ra.asString());
        assertEquals(200, rb.statusCode(), "overview(B) body=" + rb.asString());
        System.out.println("[AC-6①] overview(customerNo=A)=" + ra.asString());
        System.out.println("[AC-6①] overview(customerNo=B)=" + rb.asString());
        // 结构层最小断言：两次响应不应完全相同字节（否则说明 customerNo 被忽略，参数没接进过滤）
        assertFalse(ra.asString().equals(rb.asString())
                        && rb.asString().contains(X),
                "AC-6①：overview 在 customerNo=A/B 两种入参下响应完全相同 —— 若两行本该有不同的生产料号等区分特征，"
                        + "说明 overview 没有真正按 customerNo 取数（此断言为弱检查，需结合 rows 的强检查一起看）");
    }

    @Test
    @DisplayName("AC-6①②强检查：物料 sheet 的 rows(customerNo=A) 只含 A 的行，不含 B 的行")
    void rowsScopedToRowCustomer() {
        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料");

        Response ra = PfApi.rows(adminSession(), PfApi.QUOTE, X, sheetKey, CUST_A, null);
        assertEquals(200, ra.statusCode(), "rows(A) body=" + ra.asString());
        List<Map<String, Object>> rowsA = ra.jsonPath().getList("data.rows");
        assertNotNull(rowsA, "AC-6①：rows(A) 响应缺 data.rows");
        assertFalse(rowsA.isEmpty(), "AC-6①：rows(customerNo=A) 返回空 ⇒ 断言空跑（夹具应保证至少 1 行）");
        for (Map<String, Object> row : rowsA) {
            Object cn = row.get("customer_no") != null ? row.get("customer_no") : row.get("customerNo");
            if (cn != null) {
                assertEquals(CUST_A, String.valueOf(cn), "AC-6①：rows(customerNo=A) 里混入了非 A 的行，row=" + row);
            }
        }

        Response rb = PfApi.rows(adminSession(), PfApi.QUOTE, X, sheetKey, CUST_B, null);
        assertEquals(200, rb.statusCode(), "rows(B) body=" + rb.asString());
        List<Map<String, Object>> rowsB = rb.jsonPath().getList("data.rows");
        assertNotNull(rowsB, "AC-6②：rows(B) 响应缺 data.rows");
        assertFalse(rowsB.isEmpty(), "AC-6②：rows(customerNo=B) 返回空 ⇒ 断言空跑");

        // 阳性对照：A、B 两次返回的内容必须不同（同一份 X 若渲染出一模一样的内容，说明 customerNo 没生效）
        assertFalse(ra.asString().equals(rb.asString()),
                "AC-6②：rows(customerNo=A) 与 rows(customerNo=B) 响应完全相同 —— 两客户的生产料号本应不同"
                        + "（PRODA6 vs PRODB6），相同说明 customerNo 参数被忽略");
    }

    /**
     * <b>AC-6③ 真断言</b>：给客户 A 造两版历史（version_no=1,2），给客户 B 只造一版（version_no=1）——
     * 同一 material_no。若 {@code versions} 端点没有真正按 customerNo 过滤（只按 material_no 查
     * DISTINCT version_no），A、B 会被污染成同一个 {1,2} 集合；正确实现下 B 必须<b>看不到</b> A 独有的
     * 版本号 2，这就是「不得把两个客户的版本号混排」的可判别形态。
     */
    @Test
    @DisplayName("AC-6③：MATERIAL_BOM 版本下拉不跨客户混排——A 有 v1/v2，B 只有 v1，B 看不到 A 的 v2")
    void versionsNotMixedAcrossCustomers() {
        assumeMaterialBomCustomerDimensionReady("AC-6③");
        String bomX = FX + "BOMX6";
        insertMaterialBomRow(bomX, CUST_A, 1, FX + "AC6BOM-A-V1");
        insertMaterialBomRow(bomX, CUST_A, 2, FX + "AC6BOM-A-V2");
        insertMaterialBomRow(bomX, CUST_B, 1, FX + "AC6BOM-B-V1");

        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料BOM");

        Response ra = PfApi.versions(adminSession(), PfApi.QUOTE, bomX, sheetKey, CUST_A);
        assertEquals(200, ra.statusCode(), "versions(A) body=" + ra.asString());
        Set<Integer> versionsA = extractVersionNumbers(ra);
        System.out.println("[AC-6③] versions(customerNo=A) = " + versionsA);
        assertFalse(versionsA.isEmpty(), "AC-6③ 前置：versions(A) 返回空集 ⇒ 断言空跑（夹具应造出 v1/v2 两版）");
        assertTrue(versionsA.contains(1) && versionsA.contains(2),
                "AC-6③ 前置：客户 A 应该看到自己的 v1 与 v2，实际=" + versionsA);

        Response rb = PfApi.versions(adminSession(), PfApi.QUOTE, bomX, sheetKey, CUST_B);
        assertEquals(200, rb.statusCode(), "versions(B) body=" + rb.asString());
        Set<Integer> versionsB = extractVersionNumbers(rb);
        System.out.println("[AC-6③] versions(customerNo=B) = " + versionsB);
        assertFalse(versionsB.isEmpty(), "AC-6③ 前置：versions(B) 返回空集 ⇒ 断言空跑（夹具应造出 v1）");
        assertTrue(versionsB.contains(1), "AC-6③ 前置：客户 B 应该看到自己的 v1，实际=" + versionsB);

        assertFalse(versionsB.contains(2),
                "AC-6③🚨：客户 B 看到了版本号 2 —— 但 B 从未有过 v2，那是客户 A 独有的历史版本。"
                        + "versions(B)=" + versionsB + " —— 说明 versions 端点没有真正按 customerNo 过滤，"
                        + "只是对 material_no 做了 DISTINCT version_no（两个客户的版本号被混排）");
    }

    /** 从 versions 响应里尽量鲁棒地拆出版本号集合——不猜实现字段名，多路径兜底并在拿不到时报清楚失败原因。 */
    private Set<Integer> extractVersionNumbers(Response r) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String path : List.of("data.versions", "data.items.version", "data.items.versionNo", "data")) {
            List<?> raw;
            try {
                raw = r.jsonPath().getList(path);
            } catch (Exception e) {
                continue;
            }
            if (raw == null) {
                continue;
            }
            for (Object o : raw) {
                if (o instanceof Number n) {
                    out.add(n.intValue());
                } else if (o instanceof Map<?, ?> m) {
                    Object v = m.containsKey("version") ? m.get("version") : m.get("versionNo");
                    if (v instanceof Number n) {
                        out.add(n.intValue());
                    }
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        assertFalse(out.isEmpty(), "AC-6③：无法从 versions 响应里解析出版本号（尝试过 data.versions / "
                + "data.items.version / data.items.versionNo），原始响应=" + r.asString()
                + " —— 若字段名与本用例假设的不同，请告知实际字段名以修正解析逻辑");
        return out;
    }
}
