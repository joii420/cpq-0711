package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B1 · AC-2</b>（候选口径 = {@code customer} 表全集 ∪ 报价业务表中未建档的客户号）。
 *
 * <p>需求文档.md §③ AC-2 原文判据：
 * <pre>
 *   SELECT code FROM customer
 *   UNION
 *   SELECT DISTINCT customer_no FROM &lt;报价业务表&gt;
 *     WHERE customer_no NOT IN (SELECT code FROM customer)
 * </pre>
 * 判据 SQL 返回集合与接口返回的 {@code customerNo} 集合<b>逐项相等</b>（排序后比对）。
 *
 * <p>🔑 本类<b>不依赖</b>外部任务的 28 表 DDL——{@code ds_quote_customer_part} 早就有 {@code customer_no}，
 * 判据里「报价业务表」用 {@link PfTestBase#tablesWithCustomerNo()} 动态推导（对 information_schema 内省），
 * 而不是硬编码表名，这与 api.md §1「扫哪些表由 Registry 元数据推导」的纪律同构，且不需要读实现代码即可验证。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-2 候选客户口径 = 并集")
@QuarkusTest
class Ac2CandidatesTest extends PfTestBase {

    /** AC-2 判据 SQL 的黑盒等价实现：对 information_schema 内省出的全部 ds_quote_* 表做同一并集。 */
    private Set<String> expectedCandidateSet() {
        Set<String> expected = new LinkedHashSet<>(stringCol(
                "SELECT code FROM customer"));
        for (String table : tablesWithCustomerNo()) {
            expected.addAll(stringCol(
                    "SELECT DISTINCT customer_no FROM " + table
                            + " WHERE customer_no IS NOT NULL AND customer_no NOT IN (SELECT code FROM customer)"));
        }
        return expected;
    }

    private List<String> stringCol(String sql) {
        List<String> out = new ArrayList<>();
        for (Object o : col(sql)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    @Test
    @DisplayName("AC-2：候选集合恒等于判据 SQL 的并集（排序后逐项相等），且非空（先证明断言没有空跑）")
    void candidatesUnionMatchesJudgeSql() {
        Set<String> expected = expectedCandidateSet();
        // 断言前先断言「结果非空」——test.md §0 强制对策②
        assertFalse(expected.isEmpty(), "AC-2 前置：判据 SQL 并集为空 ⇒ 断言会空跑。customer 表或 "
                + "ds_quote_customer_part 至少一个应有数据。");

        Response r = PfApi.customers(adminSession(), PfApi.QUOTE);
        assertEquals(200, r.statusCode(), "GET /dataset/quote/customers → " + r.statusCode()
                + " body=" + r.asString());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "AC-2：响应缺 data.items");
        assertFalse(items.isEmpty(), "AC-2：接口返回候选为空 ⇒ 断言会空跑（而判据集合非空，这本身就是一处不一致）");

        Set<String> actual = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            Object no = it.get("customerNo");
            assertNotNull(no, "AC-2：某候选项缺 customerNo 字段，项=" + it);
            actual.add(String.valueOf(no));
        }
        System.out.println("[AC-2] 判据集合(" + expected.size() + ")=" + expected);
        System.out.println("[AC-2] 接口集合(" + actual.size() + ")=" + actual);

        List<String> onlyExpected = expected.stream().filter(c -> !actual.contains(c)).sorted().toList();
        List<String> onlyActual = actual.stream().filter(c -> !expected.contains(c)).sorted().toList();
        assertTrue(onlyExpected.isEmpty() && onlyActual.isEmpty(),
                "AC-2：候选集合与判据 SQL 不一致。\n  判据独有=" + onlyExpected + "\n  接口独有=" + onlyActual);
    }

    @Test
    @DisplayName("AC-2③ 阳性对照：真实存在的两个未建档客户号（C1 / Q13CUST0617）必须出现在候选里，"
            + "且 registered=false、customerName=null")
    void unregisteredCustomersAreVisible() {
        Response r = PfApi.customers(adminSession(), PfApi.QUOTE);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        assertFalse(items.isEmpty(), "AC-2③ 前置：候选为空 ⇒ 断言空跑");

        for (String unreg : List.of(CUST_UNREG_1, CUST_UNREG_2)) {
            // 阳性对照的前置：先证明这两个客户号在库里确实存在且确实未建档
            long inBusiness = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no = '"
                    + unreg + "'");
            long inCustomer = count("SELECT count(*) FROM customer WHERE code = '" + unreg + "'");
            System.out.println("[AC-2③] " + unreg + " 业务表命中=" + inBusiness + " customer表命中=" + inCustomer);
            assertTrue(inBusiness > 0, "AC-2③ 前置失效：" + unreg + " 在 ds_quote_customer_part 里已经 0 行了，"
                    + "阳性对照的地基没了（现网数据漂移，需换一个真实未建档客户号）");
            assertEquals(0, inCustomer, "AC-2③ 前置失效：" + unreg + " 现在已经建档了，不再是「未建档」场景");

            Map<String, Object> item = items.stream()
                    .filter(m -> unreg.equals(String.valueOf(m.get("customerNo"))))
                    .findFirst()
                    .orElse(null);
            assertNotNull(item, "AC-2③：未建档客户 " + unreg + " 在业务表里存在，但候选接口没有返回它 —— "
                    + "这正是 task-260903 AC-5 要防的缺陷（看得见却筛不出来），完整候选=" + items);
            assertEquals(Boolean.FALSE, item.get("registered"), "AC-2③：" + unreg + " 的 registered 应为 false，实际=" + item);
            assertTrue(item.containsKey("customerName"), "AC-2③：响应项应包含 customerName 键（值应为 null），项=" + item);
            assertTrue(item.get("customerName") == null, "AC-2③：未建档客户 customerName 应为 null（不是空字符串），实际="
                    + item.get("customerName"));
        }
    }

    @Test
    @DisplayName("AC-2 排序：registered=true 按 customerNo 升序在前，registered=false 按 customerNo 升序置尾")
    void orderingRegisteredFirstThenUnregisteredTail() {
        Response r = PfApi.customers(adminSession(), PfApi.QUOTE);
        assertEquals(200, r.statusCode());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items);
        assertFalse(items.isEmpty(), "AC-2 排序 前置：候选为空 ⇒ 断言空跑");

        // 断言前先证明两个分组都非空（否则「registered 全在前」这类断言可能在空集合上空跑地通过）
        long registeredCount = items.stream().filter(m -> Boolean.TRUE.equals(m.get("registered"))).count();
        long unregisteredCount = items.stream().filter(m -> Boolean.FALSE.equals(m.get("registered"))).count();
        System.out.println("[AC-2排序] registered=" + registeredCount + " unregistered=" + unregisteredCount);
        assertTrue(registeredCount > 0, "AC-2 排序：候选里一个 registered=true 都没有 ⇒ 排序断言空跑");
        assertTrue(unregisteredCount > 0, "AC-2 排序：候选里一个 registered=false 都没有 ⇒ 排序断言空跑"
                + "（C1/Q13CUST0617 应该在里面，若这里为 0 说明并集判据没生效）");

        int lastRegisteredIdx = -1, firstUnregisteredIdx = -1;
        List<String> registeredSeq = new ArrayList<>();
        List<String> unregisteredSeq = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            boolean reg = Boolean.TRUE.equals(items.get(i).get("registered"));
            String no = String.valueOf(items.get(i).get("customerNo"));
            if (reg) {
                lastRegisteredIdx = i;
                registeredSeq.add(no);
            } else {
                if (firstUnregisteredIdx < 0) {
                    firstUnregisteredIdx = i;
                }
                unregisteredSeq.add(no);
            }
        }
        assertTrue(firstUnregisteredIdx < 0 || lastRegisteredIdx < firstUnregisteredIdx,
                "AC-2 排序：出现 registered=false 项排在某个 registered=true 项前面 —— 未建档客户应置尾。"
                        + " lastRegisteredIdx=" + lastRegisteredIdx + " firstUnregisteredIdx=" + firstUnregisteredIdx);
        assertTrue(isAscending(registeredSeq), "AC-2 排序：registered=true 分组内部不是按 customerNo 升序：" + registeredSeq);
        assertTrue(isAscending(unregisteredSeq), "AC-2 排序：registered=false 分组内部不是按 customerNo 升序：" + unregisteredSeq);
    }

    @Test
    @DisplayName("AC-2 错误：dataset 不是 quote（含核价两套）→ 400")
    void nonQuoteDatasetReturns400() {
        for (String ds : List.of(PfApi.COST_BASIC, PfApi.COST_DETAIL, "not-a-real-dataset")) {
            Response r = PfApi.customers(adminSession(), ds);
            System.out.println("[AC-2错误] dataset=" + ds + " → " + r.statusCode());
            assertEquals(400, r.statusCode(), "AC-2：dataset=" + ds + " 应返 400（api.md §1 错误表），实际="
                    + r.statusCode() + " body=" + r.asString());
        }
    }
}
