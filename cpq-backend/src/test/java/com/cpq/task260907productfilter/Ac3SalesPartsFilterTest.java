package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B2 · AC-3</b>（销售产品列表按客户过滤 —— 后端 SQL 过滤，不是内存过滤）。
 *
 * <p>需求文档.md §③ AC-3：
 * 前置——同一销售料号 {@code T260907M-X} 在客户 A 与客户 B 下各有一行；
 * 操作——壳页选客户 A；
 * 断言——① 列表出现 X 且 customerNo=A；② 不出现 B 的那一行；③ {@code total} 等于「客户 A 的料号行数」
 * 而不是全量行数（专门证伪「前端内存过滤」这种失败形态：那种实现下 total 会保持全量）。
 *
 * <p>🚨 <b>依赖门</b>：本类全部用例依赖 {@code ds_quote_material.customer_no} 与复合唯一索引
 * （{@code task-260907-报价侧加客户维度} 的外部 DDL）。未落地时整批 {@code @BeforeEach} 会
 * {@code assumeTrue} 失败，JUnit 报 skipped（不是 passed，也不是 failed）。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-3 销售产品列表按客户过滤（后端 SQL 过滤）")
@QuarkusTest
class Ac3SalesPartsFilterTest extends PfTestBase {

    private static final String X = FX + "DUPX3";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-3");
        insertMaterialRow(X, CUST_A, FX + "PRODA3");
        insertMaterialRow(X, CUST_B, FX + "PRODB3");
    }

    @AfterEach
    void cleanup() {
        // 由 PfTestBase 的 tearDownMaterialFixtures 精确清理（按 material_no+customer_no）
    }

    @Test
    @DisplayName("AC-3①②：壳页选客户 A → 列表出现 X 且 customerNo=A，不出现 B 的那一行")
    void filterByCustomerAShowsOnlyItsRow() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 200, X);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "AC-3：响应缺 data.items");
        assertFalse(items.isEmpty(), "AC-3：customerNo=" + CUST_A + " + keyword=" + X + " 返回空列表 ⇒ 断言空跑"
                + "（前置夹具应该保证至少 1 行）");

        List<Map<String, Object>> matchingX = items.stream()
                .filter(it -> X.equals(String.valueOf(it.get("axisValue"))) || X.equals(String.valueOf(it.get("materialNo"))))
                .toList();
        assertFalse(matchingX.isEmpty(), "AC-3①：过滤后的列表里找不到夹具料号 " + X + "，实际 items=" + items);
        for (Map<String, Object> it : matchingX) {
            assertEquals(CUST_A, String.valueOf(it.get("customerNo")), "AC-3①：该行 customerNo 应为 " + CUST_A + "，实际=" + it);
        }
        boolean containsB = items.stream().anyMatch(it -> CUST_B.equals(String.valueOf(it.get("customerNo")))
                && (X.equals(String.valueOf(it.get("axisValue"))) || X.equals(String.valueOf(it.get("materialNo")))));
        assertFalse(containsB, "AC-3②：customerNo=" + CUST_A + " 的结果里混入了客户 " + CUST_B + " 的同料号行");
    }

    @Test
    @DisplayName("AC-3③：total 等于「客户 A 的料号行数」而不是全量行数 —— 专门证伪『前端内存过滤』这种失败形态")
    void totalReflectsFilteredCountNotFullCount() {
        long dbFilteredCount = count("SELECT count(*) FROM ds_quote_material WHERE customer_no = '" + CUST_A + "'");
        long dbFullCount = count("SELECT count(*) FROM ds_quote_material");
        System.out.println("[AC-3③] 库中客户 A 行数=" + dbFilteredCount + " 全量行数=" + dbFullCount);
        assertTrue(dbFilteredCount > 0, "AC-3③ 前置：客户 A 在库里一行都没有 ⇒ 断言空跑（夹具应保证至少 1 行）");
        // 阳性对照：证明过滤前后数字确实不同，否则「total==filtered」和「total==full」在这份夹具下会碰巧相等
        assertTrue(dbFilteredCount < dbFullCount,
                "AC-3③ 前置：客户 A 的行数(" + dbFilteredCount + ") 等于全量(" + dbFullCount + ")，"
                        + "此时『total 等于过滤后计数』与『total 等于全量』无法区分，判据本身失去判别力。"
                        + "（正常情况下全库应有其它客户的数据，这里只是保护性前置检查）");

        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 5, null);
        assertEquals(200, r.statusCode());
        Object total = r.jsonPath().get("data.total");
        assertNotNull(total, "AC-3③：响应缺 data.total");
        long uiTotal = ((Number) total).longValue();
        System.out.println("[AC-3③] 接口 total=" + uiTotal);
        assertEquals(dbFilteredCount, uiTotal, "AC-3③：total(" + uiTotal + ") 应等于客户 A 的过滤后行数("
                + dbFilteredCount + ")；若 total 等于全量(" + dbFullCount + ")，说明过滤是在内存里做的，"
                + "翻页会整体错位（api.md 硬约束 2）");
    }
}
