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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * <p>①②③ 全部用<b>版本化子表</b>「物料BOM」（{@code sheetKey="MATERIAL_BOM"} → 表
 * {@code ds_quote_material_bom}）验证——「物料」sheet 本身在 {@code GET /sheets} 里不存在
 * （2026-09-08 实跑撞出：quote 数据集的 13 个 sheet 全是「物料」的依赖子表，「物料」本身是免版本主表，
 * 直接挂在 {@code axisValue} 上，不是一个可 drill-in 的 sheet），只有版本化子表上 overview/rows/versions
 * 才有实际内容可验。
 *
 * <h3>🚨 2026-09-08 主线亲跑暴露的假绿风险，已修复</h3>
 * B-3 落地、依赖门放行后，①②两条曾经真变红：{@code ds_quote_material_bom} 里 A、B 都是 0 行 BOM 数据，
 * 导致「响应完全相同」在空集上恒真、「rows(A) 非空」直接断言失败。⇒ {@link #setUpFixture()} 现在给
 * 客户 A 造 2 行、客户 B 造 1 行 {@code ds_quote_material_bom}（{@link #insertMaterialBomRow}），
 * 且两侧「投入料号」内容各不相同——**行数**与**内容**双重区分特征，让 overview 的计数类弱检查、
 * rows 的内容类强检查都有真实的判别力（test.md §0「断言前先断言结果非空」的强制对策）。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-6 抽屉按行携带的客户号取数")
@QuarkusTest
class Ac6DrawerCustomerScopeTest extends PfTestBase {

    private static final String X = FX + "DUPX6";
    private static final String INPUT_A1 = FX + "AC6BOM-INPUT-A1";
    private static final String INPUT_A2 = FX + "AC6BOM-INPUT-A2";
    private static final String INPUT_B1 = FX + "AC6BOM-INPUT-B1";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-6");
        insertMaterialRow(X, CUST_A, FX + "PRODA6");
        insertMaterialRow(X, CUST_B, FX + "PRODB6");

        // 2026-09-08 补：overviewScopedToRowCustomer / rowsScopedToRowCustomer 需要 ds_quote_material_bom
        // 真有数据才不会在"断言前先断言非空"这一步空跑（原先两边都是 0 行，主线亲跑实测复现了这个假绿风险）。
        // 🔑 A、B 两侧刻意造成【行数不同】（2 行 vs 1 行）+【投入料号内容不同】双重区分特征：
        //   行数不同 → overview 的计数类弱检查有判别力；内容不同 → rows 的强检查有判别力。
        assumeMaterialBomCustomerDimensionReady("AC-6①②");
        insertMaterialBomRow(X, CUST_A, 1, 1, FX + "AC6BOM-A-SEQ1", INPUT_A1);
        insertMaterialBomRow(X, CUST_A, 2, 1, FX + "AC6BOM-A-SEQ2", INPUT_A2);
        insertMaterialBomRow(X, CUST_B, 1, 1, FX + "AC6BOM-B-SEQ1", INPUT_B1);
    }

    @Test
    @DisplayName("AC-6①②：overview 携带 customerNo=A 时不应把 B 的配置计数混进来（A=2 行 BOM，B=1 行，行数本身就有区分力）")
    void overviewScopedToRowCustomer() {
        // 断言前先断言前置数据非空——test.md §0 强制对策：本条上一轮正是因为 A、B 两侧都是 0 行
        // 才让"响应完全相同"这条弱检查在空集上恒真通过，主线亲跑已实测复现。
        long bomCountA = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='" + X
                + "' AND customer_no='" + CUST_A + "'");
        long bomCountB = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='" + X
                + "' AND customer_no='" + CUST_B + "'");
        System.out.println("[AC-6①前置] 库中 A 的 BOM 行数=" + bomCountA + " B 的 BOM 行数=" + bomCountB);
        assertTrue(bomCountA > 0 && bomCountB > 0, "AC-6①前置：夹具应保证 A、B 两侧都至少 1 行 BOM 数据，"
                + "否则本条弱检查会在空集上空跑。实际 A=" + bomCountA + " B=" + bomCountB);
        assertNotEquals(bomCountA, bomCountB, "AC-6①前置：A、B 的 BOM 行数应不同（2 vs 1）才有判别力，实际都是 "
                + bomCountA + " —— 判据本身失去意义");

        Response ra = PfApi.overview(adminSession(), PfApi.QUOTE, X, CUST_A);
        Response rb = PfApi.overview(adminSession(), PfApi.QUOTE, X, CUST_B);
        assertEquals(200, ra.statusCode(), "overview(A) body=" + ra.asString());
        assertEquals(200, rb.statusCode(), "overview(B) body=" + rb.asString());
        System.out.println("[AC-6①] overview(customerNo=A)=" + ra.asString());
        System.out.println("[AC-6①] overview(customerNo=B)=" + rb.asString());
        // 结构层断言：两次响应不应完全相同字节（A、B 的 BOM 行数已实证不同，若响应仍相同就是没按 customerNo 取数）
        assertFalse(ra.asString().equals(rb.asString()),
                "AC-6①🚨：overview 在 customerNo=A/B 两种入参下响应完全相同，但库中 A 有 " + bomCountA
                        + " 行 BOM、B 有 " + bomCountB + " 行 —— 说明 overview 没有真正按 customerNo 取数"
                        + "（此断言为弱检查，已结合 rows 的强检查一起看）");
    }

    @Test
    @DisplayName("AC-6①②强检查：物料BOM sheet 的 rows(customerNo=A) 只含 A 的 2 行、不含 B 的行；"
            + "rows(customerNo=B) 只含 B 的 1 行、内容与 A 不同")
    void rowsScopedToRowCustomer() {
        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料BOM");

        Response ra = PfApi.rows(adminSession(), PfApi.QUOTE, X, sheetKey, CUST_A, null);
        assertEquals(200, ra.statusCode(), "rows(A) body=" + ra.asString());
        List<Map<String, Object>> rowsA = ra.jsonPath().getList("data.rows");
        assertNotNull(rowsA, "AC-6①：rows(A) 响应缺 data.rows");
        assertFalse(rowsA.isEmpty(), "AC-6①：rows(customerNo=A) 返回空 ⇒ 断言空跑（夹具已保证 2 行，见 setUpFixture）");
        assertEquals(2, rowsA.size(), "AC-6①：客户 A 的夹具造了 2 行 BOM，rows(customerNo=A) 应恰好返回 2 行，实际="
                + rowsA.size() + "，rows=" + rowsA);
        String bodyA = ra.asString();
        assertTrue(bodyA.contains(INPUT_A1) && bodyA.contains(INPUT_A2),
                "AC-6①：rows(A) 应包含客户 A 两行各自的投入料号 " + INPUT_A1 + "/" + INPUT_A2 + "，实际=" + bodyA);
        assertFalse(bodyA.contains(INPUT_B1), "AC-6①🚨：rows(customerNo=A) 里混入了客户 B 的投入料号 " + INPUT_B1
                + "，说明按客户过滤失效。body=" + bodyA);
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
        assertFalse(rowsB.isEmpty(), "AC-6②：rows(customerNo=B) 返回空 ⇒ 断言空跑（夹具已保证 1 行）");
        assertEquals(1, rowsB.size(), "AC-6②：客户 B 的夹具只造了 1 行 BOM，rows(customerNo=B) 应恰好返回 1 行，实际="
                + rowsB.size() + "，rows=" + rowsB);
        String bodyB = rb.asString();
        assertTrue(bodyB.contains(INPUT_B1), "AC-6②：rows(B) 应包含客户 B 的投入料号 " + INPUT_B1 + "，实际=" + bodyB);
        assertFalse(bodyB.contains(INPUT_A1) || bodyB.contains(INPUT_A2),
                "AC-6②🚨：rows(customerNo=B) 里混入了客户 A 的投入料号，说明按客户过滤失效。body=" + bodyB);

        // 阳性对照：A、B 两次返回的内容必须不同（行数已经不同：2 vs 1，内容也已经不同：INPUT_A* vs INPUT_B1）
        assertFalse(bodyA.equals(bodyB),
                "AC-6②：rows(customerNo=A) 与 rows(customerNo=B) 响应完全相同 —— 两客户的行数(2 vs 1)与投入料号本应不同，"
                        + "相同说明 customerNo 参数被忽略");
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
