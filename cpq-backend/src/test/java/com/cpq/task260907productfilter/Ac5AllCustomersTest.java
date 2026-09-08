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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B3 · AC-5</b>（列表恒等于当前客户的全集 + 客户列仍在）。
 *
 * <h3>🔄 2026-09-07 用户变更（D-7）</h3>
 * 原文「『所有客户』= 展示全量」已被推翻——客户改为<b>必选</b>，UI 上取消「所有客户」选项。
 * AC-5 的断言对象从「不传 customerNo 时看全量」改为「<b>选中某个客户后，列表恒等于该客户的全集</b>」。
 *
 * <p>需求文档.md §③ AC-5（新版）：
 * 操作——壳页选中客户 A；
 * 断言——① 列表行数恒等于 {@code SELECT count(*) FROM ds_quote_material WHERE customer_no = 'A'}
 * （继承 {@code task-260903} AC-25 的不变量形式，复合轴 + 客户必选下自适应）；
 * ② 列表仍含客户编号与客户名称两列（D-9：每行值虽相同，但用户要能确认「在看谁的数据」）；
 * ③ 列表中不出现任何 {@code customer_no != 'A'} 的行；
 * ④ 未建档客户（如 C1）被选中时，其行的客户名称列显示 {@code —}。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-5 列表恒等于当前客户的全集（D-7）")
@QuarkusTest
class Ac5AllCustomersTest extends PfTestBase {

    private static final String X = FX + "DUPX5";
    private static final String UNREG_MATERIAL = FX + "UNREGMAT5";
    private static final String UNREG_CUST = FX + "UNREGC5";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-5");
        insertMaterialRow(X, CUST_A, FX + "PRODA5");
        insertMaterialRow(X, CUST_B, FX + "PRODB5");
        // ④ 未建档客户所在行：customer_no 不在 customer 表里
        insertMaterialRow(UNREG_MATERIAL, UNREG_CUST, null);
    }

    @Test
    @DisplayName("AC-5①：选中客户 A 时，列表总数恒等于 count(*) FROM ds_quote_material WHERE customer_no = 'A'")
    void totalEqualsCurrentCustomerCount() {
        long dbForA = count("SELECT count(*) FROM ds_quote_material WHERE customer_no = '" + CUST_A + "'");
        long dbFull = count("SELECT count(*) FROM ds_quote_material");
        assertTrue(dbForA > 0, "AC-5① 前置：客户 A 在库里一行都没有 ⇒ 断言空跑（夹具应保证至少 1 行）");
        // 阳性对照：证明「按客户过滤」与「全量」在这份夹具下确实不同，否则判据没有判别力
        assertTrue(dbForA < dbFull, "AC-5① 前置：客户 A 的行数(" + dbForA + ") 等于全量(" + dbFull
                + ")，此时无法区分『按客户过滤』与『没过滤』，判据失去判别力");

        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 1, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        Object total = r.jsonPath().get("data.total");
        assertNotNull(total, "AC-5①：响应缺 data.total");
        long uiTotal = ((Number) total).longValue();
        System.out.println("[AC-5①] 库(customer_no=A)=" + dbForA + " 库(全量)=" + dbFull + " 接口 total=" + uiTotal);
        assertEquals(dbForA, uiTotal, "AC-5①：客户 A 的列表总数应恒等于 count(*) WHERE customer_no='A'"
                + "（若等于全量 " + dbFull + "，说明 customerNo 过滤没生效）");
    }

    @Test
    @DisplayName("AC-5②③：列表含客户编号/客户名称两列；选中客户 A 时不出现任何 customer_no != 'A' 的行")
    void listHasCustomerColumnsAndOnlyShowsCurrentCustomer() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 500, X);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "AC-5②：响应缺 data.items");
        assertFalse(items.isEmpty(), "AC-5②：keyword=" + X + " 且 customerNo=A 搜不到任何行 ⇒ 断言空跑"
                + "（前置夹具应保证客户 A 至少 1 行）");

        for (Map<String, Object> it : items) {
            assertTrue(it.containsKey("customerNo"), "AC-5②：行内缺 customerNo 字段，项=" + it);
            assertTrue(it.containsKey("customerName"), "AC-5②：行内缺 customerName 字段，项=" + it);
            assertEquals(CUST_A, String.valueOf(it.get("customerNo")),
                    "AC-5③🚨：customerNo=A 的过滤结果里混入了非 A 的行，项=" + it);
        }
        System.out.println("[AC-5②③] " + items.size() + " 行全部 customerNo=" + CUST_A + "，且都含客户两列");

        // 阳性对照：X 这个料号在库里确实还有客户 B 的另一行——证明"看不到 B"不是因为 B 的数据不存在
        long bRowExists = count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + X
                + "' AND customer_no = '" + CUST_B + "'");
        assertEquals(1, bRowExists, "AC-5③ 阳性对照前置：客户 B 的同料号行应该存在于库中（用于证明是过滤生效，"
                + "不是数据本来就没有），实际=" + bRowExists);
    }

    @Test
    @DisplayName("AC-5④：未建档客户被选中时，其行的 customerName 为 null（前端渲染 —，不是空白/报错）")
    void unregisteredCustomerRowHasNullCustomerName() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, UNREG_CUST, 0, 500, UNREG_MATERIAL);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        assertFalse(items.isEmpty(), "AC-5④：customerNo=" + UNREG_CUST + " + keyword=" + UNREG_MATERIAL
                + " 搜不到夹具行 ⇒ 断言空跑");
        Map<String, Object> row = items.get(0);
        assertEquals(UNREG_CUST, String.valueOf(row.get("customerNo")));
        assertTrue(row.get("customerName") == null, "AC-5④：未建档客户所在行 customerName 应为 null，实际="
                + row.get("customerName"));
    }
}
