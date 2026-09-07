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
 * <b>T-B3 · AC-5</b>（「所有客户」= 展示全量 + 客户列可分辨）。
 *
 * <p>需求文档.md §③ AC-5：
 * ① 销售产品列表行数恒等于 {@code SELECT count(*) FROM ds_quote_material}（复合轴下自适应，
 *    继承 task-260903 AC-25）；
 * ② 列表含客户编号与客户名称两列；
 * ③ 夹具中同料号跨两客户的两行都出现，且客户列取值不同；
 * ④ 未建档客户所在行的客户名称列显示 {@code —}（后端层面对应 {@code customerName=null}，不是空白/报错）。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-5 所有客户=全量+客户列")
@QuarkusTest
class Ac5AllCustomersTest extends PfTestBase {

    private static final String X = FX + "DUPX5";
    private static final String UNREG_MATERIAL = FX + "UNREGMAT5";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-5");
        insertMaterialRow(X, CUST_A, FX + "PRODA5");
        insertMaterialRow(X, CUST_B, FX + "PRODB5");
        // ④ 未建档客户所在行：customer_no 不在 customer 表里
        insertMaterialRow(UNREG_MATERIAL, CUST_ABSENT_BUT_NOT_REGISTERED(), null);
    }

    /** AC-5④ 需要一个「在物料表出现但未建档」的客户号——不用 C1/Q13CUST0617（避免污染真实未建档客户的行数），
     *  自造一个仅存在于本条夹具里的、customer 表里绝对查不到的编号。 */
    private String CUST_ABSENT_BUT_NOT_REGISTERED() {
        return FX + "UNREGC5";
    }

    @Test
    @DisplayName("AC-5①：不传 customerNo（所有客户）时，列表总数恒等于 count(*) FROM ds_quote_material")
    void totalEqualsFullTableCount() {
        long dbFull = count("SELECT count(*) FROM ds_quote_material");
        assertTrue(dbFull > 0, "AC-5① 前置：ds_quote_material 空表 ⇒ 断言空跑");

        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, null, 0, 1, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        Object total = r.jsonPath().get("data.total");
        assertNotNull(total, "AC-5①：响应缺 data.total");
        long uiTotal = ((Number) total).longValue();
        System.out.println("[AC-5①] 库 count(*)=" + dbFull + " 接口 total=" + uiTotal);
        assertEquals(dbFull, uiTotal, "AC-5①：不传 customerNo 时 total 应恒等于 count(*)（复合轴下一行=客户×料号，自适应）");
    }

    @Test
    @DisplayName("AC-5②③：列表含客户编号/客户名称两列；夹具中同料号跨两客户的两行都出现且客户列不同")
    void listHasCustomerColumnsAndBothDuplicateRowsAppear() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, null, 0, 500, X);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "AC-5②：响应缺 data.items");
        assertFalse(items.isEmpty(), "AC-5②：keyword=" + X + " 搜不到任何行 ⇒ 断言空跑（前置夹具应保证 2 行）");

        List<Map<String, Object>> matching = items.stream()
                .filter(it -> X.equals(String.valueOf(it.get("axisValue"))) || X.equals(String.valueOf(it.get("materialNo"))))
                .toList();
        assertEquals(2, matching.size(), "AC-5③：同料号 " + X + " 跨两客户应出现【两行】，实际=" + matching.size()
                + "，items=" + matching);

        for (Map<String, Object> it : matching) {
            assertTrue(it.containsKey("customerNo"), "AC-5②：行内缺 customerNo 字段，项=" + it);
            assertTrue(it.containsKey("customerName"), "AC-5②：行内缺 customerName 字段，项=" + it);
        }
        String c1 = String.valueOf(matching.get(0).get("customerNo"));
        String c2 = String.valueOf(matching.get(1).get("customerNo"));
        assertFalse(c1.equals(c2), "AC-5③：两行的 customerNo 应不同，实际都是 " + c1);
        assertEquals(Set2(CUST_A, CUST_B), Set2(c1, c2), "AC-5③：两行的 customerNo 应恰为 {"
                + CUST_A + "," + CUST_B + "}，实际={" + c1 + "," + c2 + "}");
    }

    private static java.util.Set<String> Set2(String a, String b) {
        return new java.util.TreeSet<>(List.of(a, b));
    }

    @Test
    @DisplayName("AC-5④：未建档客户所在行的 customerName 为 null（前端渲染 —，不是空白/报错）")
    void unregisteredCustomerRowHasNullCustomerName() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, null, 0, 500, UNREG_MATERIAL);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        assertFalse(items.isEmpty(), "AC-5④：keyword=" + UNREG_MATERIAL + " 搜不到夹具行 ⇒ 断言空跑");
        Map<String, Object> row = items.stream()
                .filter(it -> UNREG_MATERIAL.equals(String.valueOf(it.get("axisValue")))
                        || UNREG_MATERIAL.equals(String.valueOf(it.get("materialNo"))))
                .findFirst().orElse(null);
        assertNotNull(row, "AC-5④：找不到夹具行，items=" + items);
        assertEquals(CUST_ABSENT_BUT_NOT_REGISTERED(), String.valueOf(row.get("customerNo")));
        assertTrue(row.get("customerName") == null, "AC-5④：未建档客户所在行 customerName 应为 null，实际="
                + row.get("customerName") + "（若为报错/500 或抛异常，说明未建档客户的 LEFT JOIN 分支没处理好）");
    }
}
