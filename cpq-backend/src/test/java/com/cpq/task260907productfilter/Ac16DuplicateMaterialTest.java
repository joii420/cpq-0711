package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B9 · AC-16</b>（同料号跨客户，两行并存且各自独立）—— 后端侧证据。
 *
 * <p>需求文档.md §③ AC-16：
 * ① 列表出现两行同料号；② 两行的 {@code rowKey} 不同（前端职责，本类验其后端前提：
 * 响应里 {@code (customerNo, axisValue)} 复合键必须唯一，前端才能据此构造不同的 rowKey；
 * 仍用 axisValue 单独当 key 会让 React 认为是同一行）；③ 分别点开两行的抽屉内容互不包含对方 ——
 * 已由 {@code Ac6DrawerCustomerScopeTest} 覆盖，此处不重复。
 *
 * <p>🔑 <b>test.md X-2 证伪实验的关联点</b>：X-2 要求把前端 rowKey 改回 {@code axisValue} 后
 * {@code T-F13} 必须变红。本类在后端侧提供该证伪实验成立的<b>前提证据</b>——
 * 如果后端本身就没有把两行都返回（或返回时 customerNo 缺失/重复），
 * 前端 rowKey 改造再正确也测不出问题，因为数据源头就已经把两行合并成一行了。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-16 同料号跨客户两行并存（后端证据）")
@QuarkusTest
class Ac16DuplicateMaterialTest extends PfTestBase {

    private static final String X = FX + "DUPX16";

    @BeforeEach
    void setUpFixture() {
        assumeMaterialUniqueIndexComposite("AC-16");
        insertMaterialRow(X, CUST_A, FX + "PRODA16");
        insertMaterialRow(X, CUST_B, FX + "PRODB16");
    }

    @Test
    @DisplayName("AC-16①②：所有客户下同料号出现两行，(customerNo, axisValue) 复合键在响应里唯一")
    void twoRowsWithDistinctCompositeKey() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, null, 0, 500, X);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "AC-16：响应缺 data.items");
        assertFalse(items.isEmpty(), "AC-16：keyword=" + X + " 搜不到任何行 ⇒ 断言空跑");

        List<Map<String, Object>> matching = items.stream()
                .filter(it -> X.equals(String.valueOf(it.get("axisValue"))) || X.equals(String.valueOf(it.get("materialNo"))))
                .toList();
        System.out.println("[AC-16] 命中 " + matching.size() + " 行：" + matching);
        assertEquals(2, matching.size(), "AC-16①：同料号 " + X + " 跨两客户应恰好出现两行，实际=" + matching.size());

        Set<String> compositeKeys = new HashSet<>();
        Set<String> customerNos = new HashSet<>();
        for (Map<String, Object> it : matching) {
            Object axis = it.containsKey("axisValue") ? it.get("axisValue") : it.get("materialNo");
            Object cn = it.get("customerNo");
            assertNotNull(cn, "AC-16②：某行缺 customerNo ⇒ 前端无法据此构造不同的 rowKey，行=" + it);
            compositeKeys.add(cn + "|" + axis);
            customerNos.add(String.valueOf(cn));
        }
        assertEquals(2, compositeKeys.size(), "AC-16②：两行的 (customerNo, axisValue) 复合键应各不相同，"
                + "实际去重后只剩 " + compositeKeys.size() + " 个 —— 若为 1，说明两行被后端/序列化层合并成了一行"
                + "（前端拿到的将只有一行数据，rowKey 改造再对也没用）");
        assertEquals(Set.of(CUST_A, CUST_B), customerNos, "AC-16②：两行的 customerNo 应恰为 {"
                + CUST_A + "," + CUST_B + "}，实际=" + customerNos);
    }

    @Test
    @DisplayName("AC-16 阳性对照：单独按某一个客户过滤时应只剩一行（证明两行不是重复数据而是两个真实客户维度）")
    void filteringByOneCustomerCollapsesToOneRow() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 500, X);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        List<Map<String, Object>> matching = items.stream()
                .filter(it -> X.equals(String.valueOf(it.get("axisValue"))) || X.equals(String.valueOf(it.get("materialNo"))))
                .toList();
        assertEquals(1, matching.size(), "AC-16 对照：按 customerNo=" + CUST_A + " 过滤后应只剩 1 行，实际=" + matching.size());
        assertTrue(matching.get(0).get("customerNo").equals(CUST_A), "过滤后剩下的那一行 customerNo 应为 " + CUST_A);
    }
}
