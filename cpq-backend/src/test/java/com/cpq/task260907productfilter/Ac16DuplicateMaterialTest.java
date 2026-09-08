package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <b>T-B9 · AC-16②「后端层」</b>（同料号跨客户，两行并存且各自独立）—— 后端侧证据。
 *
 * <h3>🔄 2026-09-07 用户变更（D-8）：客户必选后拆两层验证</h3>
 * 客户必选后列表永远单客户，「两行同屏」在 UI 上不再可见，因此不能再靠「所有客户」模式验证。
 * 拆成两层：
 * <ul>
 *   <li><b>① UI 层</b>（E2E，见 T-F13）：壳页选 A → 看到该料号且客户列=A；切到 B → 仍看到该料号但
 *       客户列=B，且两次的行内容不同（生产料号 A/B 各不相同）——证明看到的是各自的数据。</li>
 *   <li><b>② 后端层</b>（本类）：直接调 {@code ?customerNo=A} 与 {@code ?customerNo=B}，
 *       断言<b>两个客户的行都存在于库中、互不覆盖</b>。</li>
 * </ul>
 * 🔑 两层合起来才能证伪「静默删掉别人的行」——只验 UI 单客户可见，抓不住「B 的行其实已被 A 的导入删掉了」；
 * 只验后端存在，抓不住「用户实际看不到自己的数据」。
 *
 * <p>🚨 {@code rowKey} 仍必须是 {@code `${customerNo}|${axisValue}`}——虽然单客户下不会同屏出现两行，
 * 但切换客户时 React 复用行组件，key 不含客户号会导致切换后仍显示上一个客户的单元格值（前端 E2E 验证）。
 * 本类只提供后端侧的前提证据：两行的 {@code (customerNo, axisValue)} 复合键在响应里各自可寻址、值不同。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-16②后端层：两客户同料号行各自独立存在")
@QuarkusTest
class Ac16DuplicateMaterialTest extends PfTestBase {

    private static final String X = FX + "DUPX16";
    private static final String PROD_A = FX + "PRODA16";
    private static final String PROD_B = FX + "PRODB16";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-16");
        insertMaterialRow(X, CUST_A, PROD_A);
        insertMaterialRow(X, CUST_B, PROD_B);
    }

    @Test
    @DisplayName("AC-16②：customerNo=A 与 customerNo=B 分别查询，同料号 X 在两侧都存在且各自的生产料号不同（互不覆盖）")
    void bothCustomersRowsCoexistIndependently() {
        Response ra = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 50, X);
        assertEquals(200, ra.statusCode(), "parts(A) body=" + ra.asString());
        List<Map<String, Object>> itemsA = ra.jsonPath().getList("data.items");
        assertNotNull(itemsA, "AC-16②：parts(A) 响应缺 data.items");
        assertFalse(itemsA.isEmpty(), "AC-16②：customerNo=A 搜不到 " + X + " ⇒ 断言空跑（客户 A 的行应该存在）");
        assertEquals(1, itemsA.size(), "AC-16②：customerNo=A 应恰好命中 1 行，实际=" + itemsA.size());
        Map<String, Object> rowA = itemsA.get(0);
        assertEquals(CUST_A, String.valueOf(rowA.get("customerNo")), "AC-16②：行=" + rowA);
        assertEquals(PROD_A, String.valueOf(rowA.get("productionNo")),
                "AC-16②🚨：客户 A 的生产料号被覆盖了，期望=" + PROD_A + " 实际=" + rowA.get("productionNo"));

        Response rb = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_B, 0, 50, X);
        assertEquals(200, rb.statusCode(), "parts(B) body=" + rb.asString());
        List<Map<String, Object>> itemsB = rb.jsonPath().getList("data.items");
        assertNotNull(itemsB, "AC-16②：parts(B) 响应缺 data.items");
        assertFalse(itemsB.isEmpty(), "AC-16②🚨：customerNo=B 搜不到 " + X + " ⇒ 客户 B 的行可能已被客户 A 的操作静默删除"
                + "（这正是本条 AC 要防的失败形态）");
        assertEquals(1, itemsB.size(), "AC-16②：customerNo=B 应恰好命中 1 行，实际=" + itemsB.size());
        Map<String, Object> rowB = itemsB.get(0);
        assertEquals(CUST_B, String.valueOf(rowB.get("customerNo")), "AC-16②：行=" + rowB);
        assertEquals(PROD_B, String.valueOf(rowB.get("productionNo")),
                "AC-16②🚨：客户 B 的生产料号被覆盖了，期望=" + PROD_B + " 实际=" + rowB.get("productionNo"));

        // 两行内容必须不同——证明不是"同一行换了个客户标签"，而是两条独立数据
        assertNotEquals(rowA.get("productionNo"), rowB.get("productionNo"),
                "AC-16②：客户 A、B 的生产料号应不同，实际相同 —— 说明两行数据被合并/覆盖成了一份");
        System.out.println("[AC-16②] ✅ 客户 A 行=" + rowA + "\n         客户 B 行=" + rowB);
    }
}
