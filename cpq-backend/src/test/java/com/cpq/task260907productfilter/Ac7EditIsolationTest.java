package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B5 · AC-7</b>（生产料号编辑只改该客户的行）—— 本任务<b>唯一的写端点</b>，
 * 也是「静默改错数据」这个失败形态的核心测试对象。
 *
 * <p>需求文档.md §③ AC-7：
 * 前置——A、B 两行的 {@code production_no} 初值不同且均非空；
 * 操作——把 A 那一行的生产料号改为新值，失焦保存；
 * 断言——① A 行落库为新值；② 🚨 B 行的 {@code production_no} 逐字未变；③ 两行的 {@code source}
 * 均保持 {@code IMPORT}。
 *
 * <p>🔑 本类同时承担 <b>test.md §3 X-3 证伪实验</b>的宿主：
 * {@link #missingCustomerNoOnDuplicateMaterialMustError()} 就是 X-3 本身——
 * 若后端的「多行守卫」被去掉（不传 customerNo 时命中多行仍然放行更新），
 * 本条用例必须<b>由绿变红</b>；若它对任何实现都返回 200，说明这条用例从未真正验过守卫。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-7 生产料号编辑只改该客户的行（写端点隔离 + 多行守卫）")
@QuarkusTest
class Ac7EditIsolationTest extends PfTestBase {

    private static final String X = FX + "DUPX7";
    private static final String INIT_A = FX + "PRODA7-INIT";
    private static final String INIT_B = FX + "PRODB7-INIT";
    private static final String NEW_A = FX + "PRODA7-NEW";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-7");
        insertMaterialRow(X, CUST_A, INIT_A);
        insertMaterialRow(X, CUST_B, INIT_B);
    }

    @Test
    @DisplayName("AC-7①②③：改 A 的生产料号 → A 落库为新值，B 逐字未变，两行 source 仍为 IMPORT")
    void editingCustomerARowDoesNotTouchCustomerBRow() {
        // 前置自检：两行初值确实不同且非空（AC-7 前置条件）
        String beforeA = readProductionNo(X, CUST_A);
        String beforeB = readProductionNo(X, CUST_B);
        System.out.println("[AC-7前置] A.production_no=" + beforeA + " B.production_no=" + beforeB);
        assertEquals(INIT_A, beforeA, "AC-7 前置：A 行初值与夹具写入值不符（夹具本身有问题）");
        assertEquals(INIT_B, beforeB, "AC-7 前置：B 行初值与夹具写入值不符");
        assertNotEquals(beforeA, beforeB, "AC-7 前置：两行初值应不同，实际相同 —— 判别力为零");

        Response r = PfApi.updatePart(adminSession(), PfApi.QUOTE, X, CUST_A,
                Map.of("productionNo", NEW_A));
        assertEquals(200, r.statusCode(), "PUT /parts/" + X + "?customerNo=" + CUST_A + " → " + r.statusCode()
                + " body=" + r.asString());

        String afterA = readProductionNo(X, CUST_A);
        String afterB = readProductionNo(X, CUST_B);
        System.out.println("[AC-7结果] A.production_no=" + afterA + " B.production_no=" + afterB);

        assertEquals(NEW_A, afterA, "AC-7①：A 行应落库为新值 " + NEW_A + "，实际=" + afterA);
        assertEquals(beforeB, afterB, "AC-7②🚨：B 行的 production_no 被改动了！改前=" + beforeB + " 改后=" + afterB
                + " —— 这正是复合轴下『不带客户号编辑会误伤其他客户』的静默数据损坏");

        String sourceA = readSource(X, CUST_A);
        String sourceB = readSource(X, CUST_B);
        assertEquals("IMPORT", sourceA, "AC-7③：A 行 source 应保持 IMPORT，实际=" + sourceA);
        assertEquals("IMPORT", sourceB, "AC-7③：B 行 source 应保持 IMPORT，实际=" + sourceB);
    }

    /**
     * 🚨 <b>test.md X-3 证伪实验的宿主用例</b>。
     * <p>api.md §2/A-6：「命中行数 > 1 时必须报错，🚫 不许静默更新多行」。
     * 本用例<b>不传</b> {@code customerNo}，此时 {@code WHERE material_no = X} 命中 A、B 两行 ——
     * 按契约必须报错（4xx/5xx 均可，只要不是 200 且不是静默改多行），且两行数据都不应被改动。
     * <p>🔑 <b>证伪指引（供主线/开发跑用）</b>：去掉后端的多行守卫后重跑本用例，期望结果从 PASS → FAIL
     * （现象应为：断言"两行都未被改成同一个新值"失败，能观察到两行被误改成同一值）。
     * 若去掉守卫后本用例仍然 PASS，说明这条用例从未真正触达守卫代码路径，必须重新设计。
     */
    @Test
    @DisplayName("AC-7 多行守卫（X-3 证伪目标）：不传 customerNo 时命中 A、B 两行，必须报错且两行均不被改动")
    void missingCustomerNoOnDuplicateMaterialMustError() {
        String beforeA = readProductionNo(X, CUST_A);
        String beforeB = readProductionNo(X, CUST_B);

        Response r = PfApi.updatePart(adminSession(), PfApi.QUOTE, X, null,
                Map.of("productionNo", FX + "SHOULD-NOT-APPLY"));
        System.out.println("[AC-7多行守卫] 不传 customerNo → HTTP " + r.statusCode() + " body=" + r.asString());

        String afterA = readProductionNo(X, CUST_A);
        String afterB = readProductionNo(X, CUST_B);
        System.out.println("[AC-7多行守卫] A: " + beforeA + " → " + afterA + "   B: " + beforeB + " → " + afterB);

        assertTrue(r.statusCode() >= 400, "AC-7 多行守卫：不传 customerNo 命中多行时应报错（4xx/5xx），"
                + "实际返回 " + r.statusCode() + " —— 若为 200，说明后端静默更新了多行，"
                + "这是本任务唯一写端点里最危险的失败形态（页面上看不出来）");
        assertEquals(beforeA, afterA, "AC-7 多行守卫：A 行本不该被这次（应报错的）请求改动，改前=" + beforeA + " 改后=" + afterA);
        assertEquals(beforeB, afterB, "AC-7 多行守卫：B 行本不该被这次（应报错的）请求改动，改前=" + beforeB + " 改后=" + afterB);
    }

    private String readProductionNo(String materialNo, String customerNo) {
        Object v = em.createNativeQuery("SELECT production_no FROM ds_quote_material "
                + "WHERE material_no = :mn AND customer_no = :cn")
                .setParameter("mn", materialNo).setParameter("cn", customerNo)
                .getSingleResult();
        return v == null ? null : String.valueOf(v);
    }

    private String readSource(String materialNo, String customerNo) {
        Object v = em.createNativeQuery("SELECT source FROM ds_quote_material "
                + "WHERE material_no = :mn AND customer_no = :cn")
                .setParameter("mn", materialNo).setParameter("cn", customerNo)
                .getSingleResult();
        return v == null ? null : String.valueOf(v);
    }
}
