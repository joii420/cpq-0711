package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B8 · AC-14</b>（未建档客户可选且能筛出其产品）。
 *
 * <p>需求文档.md §③ AC-14：
 * ① 客户产品列表筛出该客户号的行（📌 采集时点：C1 1 行 / Q13CUST0617 2 行）；
 * ② 客户名称列显示 —；
 * ③ 🚫 前端不得因 customerName 为空而把这些行或这个候选项过滤掉。
 *
 * <p>本类只测<b>后端</b>①（{@code GET /dataset/quote/customer-parts?customerNo=...}，该端点
 * api.md §3 明确「不改」，本来就支持 {@code customerNo} 参数）—— ②③ 属前端渲染逻辑，见 {@code T-F11}。
 *
 * <p>🔑 <b>不依赖</b>外部 28 表 DDL：{@code ds_quote_customer_part} 本来就有 {@code customer_no}。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-14① 未建档客户能筛出客户产品")
@QuarkusTest
class Ac14UnregisteredCustomerTest extends PfTestBase {

    @Test
    @DisplayName("AC-14①：customerNo=C1 只筛出 C1 的客户产品行，且非空（阳性对照：C1 在库里确实有数据）")
    void unregisteredCustomerC1FiltersItsOwnRows() {
        long dbCount = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no = '" + CUST_UNREG_1 + "'");
        System.out.println("[AC-14①] C1 在库中的行数=" + dbCount);
        assertTrue(dbCount > 0, "AC-14① 前置失效：C1 在 ds_quote_customer_part 里已经 0 行 —— "
                + "现网数据漂移，需要换一个真实存在的未建档客户号重跑本用例（不许改用能让断言空跑的空夹具）");

        Response r = PfApi.customerParts(adminSession(), PfApi.QUOTE, CUST_UNREG_1);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "AC-14①：响应缺 data.items");
        assertFalse(items.isEmpty(), "AC-14①：customerNo=C1 返回空列表，但库里明明有 " + dbCount + " 行 ⇒ "
                + "过滤把未建档客户自己的数据也筛没了");
        assertEquals(dbCount, items.size(), "AC-14①：接口返回行数与库中 count(*) 不一致");
        for (Map<String, Object> it : items) {
            assertEquals(CUST_UNREG_1, String.valueOf(it.get("customerNo")),
                    "AC-14①：customerNo=C1 的过滤结果里混入了别的客户号，项=" + it);
        }
        System.out.println("[AC-14①] ✅ 实际返回 " + items.size() + " 行，全部 customerNo=" + CUST_UNREG_1);
    }

    @Test
    @DisplayName("AC-14① 阳性对照：customerNo=Q13CUST0617 同样能筛出（证明观察手段对第二个未建档客户同样有效）")
    void secondUnregisteredCustomerAlsoFilters() {
        long dbCount = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no = '" + CUST_UNREG_2 + "'");
        System.out.println("[AC-14①对照] Q13CUST0617 在库中的行数=" + dbCount);
        assertTrue(dbCount > 0, "AC-14① 对照前置失效：Q13CUST0617 已经 0 行");

        Response r = PfApi.customerParts(adminSession(), PfApi.QUOTE, CUST_UNREG_2);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        assertFalse(items.isEmpty(), "AC-14①对照：customerNo=Q13CUST0617 返回空列表，但库里有 " + dbCount + " 行");
        assertEquals(dbCount, items.size());
        for (Map<String, Object> it : items) {
            assertEquals(CUST_UNREG_2, String.valueOf(it.get("customerNo")), "混入了别的客户号，项=" + it);
        }
    }

    @Test
    @DisplayName("AC-14 反向对照：换一个真实存在的客户号（CUST-0001）看到的行不应包含 C1 的数据 —— "
            + "证明过滤确实按客户号生效，而不是端点从来不过滤")
    void switchingToRegisteredCustomerExcludesUnregisteredRows() {
        Response r = PfApi.customerParts(adminSession(), PfApi.QUOTE, CUST_A);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        long crossContamination = items.stream()
                .filter(it -> CUST_UNREG_1.equals(String.valueOf(it.get("customerNo")))
                        || CUST_UNREG_2.equals(String.valueOf(it.get("customerNo"))))
                .count();
        assertEquals(0, crossContamination, "AC-14 反向：customerNo=" + CUST_A
                + " 的结果里混入了 C1/Q13CUST0617 的行，过滤形同虚设");
    }
}
