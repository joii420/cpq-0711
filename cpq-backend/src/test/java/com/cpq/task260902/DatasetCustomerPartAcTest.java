package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L2 集成 · 客户料号（裁决 D-18 / D-19）</b> —— 覆盖 AC-45 ~ AC-47。
 *
 * <h3>AC-47 为什么是本组最重要的一条</h3>
 * 它是<b>反串号</b>用例：两个不同客户共用同一个客户产品编号 {@code A002}，两行必须都活下来。
 * 主键若漏了客户维度，第二行会 UPSERT 覆盖第一行 ⇒ 只剩 1 行 ——
 * 那正是 {@code RECORD.md}「森萨塔投入料号跨客户串号」的复现形态（占号表全局唯一、丢了客户维度）。
 *
 * <p>⚠️ 判据特意<b>不写「count == 2」</b>而写「{@code customer_no} 集合 == {CUST-0001, CUST-0004}」：
 * 只数行数的话，两行都写成同一个客户也能凑够 2，断言会被巧合骗过。
 */
@QuarkusTest
@DisplayName("task-260902 · 客户料号 D-18/D-19（AC-45~AC-47）")
class DatasetCustomerPartAcTest extends DatasetAcTestBase {

    private static final String TABLE = "ds_quote_customer_part";
    private static final String SHEET = "客户料号";
    /** 免版本 sheet 无标记行，数据从第 2 行起（api.md §1）。AC-45/46 原文点名的就是第 2 行。 */
    private static final int FIRST_DATA_ROW = 2;
    /** AC-47 的客户产品编号锚点。 */
    private static final String SHARED_PRODUCT_NO = "A002";

    // ═════════════════════ AC-45 客户编号为空 ═════════════════════

    @Test
    @DisplayName("TC-01 / AC-45：客户编号留空 → 400 + 必填项为空 + count 不变")
    void tc01_customerNoBlank() {
        long before = countRows(TABLE, null);

        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            b.blank(SHEET, FIRST_DATA_ROW, Fixtures.CUSTOMER_NO_COLUMN);
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.QUOTE,
                    b.toBytes(), "ac45-customer-no-blank.xlsx");

            assertStatus(r, 400, "AC-45");
            assertHasError(errorsOf(r), SHEET, FIRST_DATA_ROW, Fixtures.CUSTOMER_NO_COLUMN,
                    "必填项为空", "AC-45");

            long after = countRows(TABLE, null);
            assertEquals(before, after,
                    "AC-45：整份拒收必须一行不写，" + TABLE + " 的 count 变了 " + before + " → " + after);
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-46 客户编号未登记 ═════════════════════

    @Test
    @DisplayName("TC-02 / AC-46：客户编号 NOTEXIST-999 不在 customer.code → 400 + 整份拒收（16 张 ds_quote_* 全不变）")
    void tc02_customerNoNotRegistered() {
        // 只取报价侧的 16 张表做快照（AC-46 原文口径）
        Map<String, Long> before = quoteCountsSnapshot();

        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            b.setText(SHEET, FIRST_DATA_ROW, Fixtures.CUSTOMER_NO_COLUMN, CUSTOMER_ABSENT);
            Response r = DatasetApi.importFile(adminSession(), DatasetApi.QUOTE,
                    b.toBytes(), "ac46-customer-not-registered.xlsx");

            assertStatus(r, 400, "AC-46");
            assertHasError(errorsOf(r), SHEET, FIRST_DATA_ROW, Fixtures.CUSTOMER_NO_COLUMN,
                    "客户编号未在客户档案中登记", "AC-46");

            Map<String, Long> after = quoteCountsSnapshot();
            assertCountsUnchanged(before, after,
                    "AC-46：客户编号未登记必须整份拒收，全部 16 张 ds_quote_* 表 count 逐表相等");
        } finally {
            b.close();
        }
    }

    // ═════════════════════ AC-47 反串号 ═════════════════════

    @Test
    @DisplayName("TC-03 / AC-47 🚩反串号：两客户共用客户产品编号 A002，两行都要留下（森萨塔串号的复现形态）")
    void tc03_crossCustomerNoCollision() {
        String materialA = P + "202601011226";
        String materialB = P + "5550C1649001";

        DatasetFixtureBuilder b = Fixtures.quote(probe());
        try {
            // 第一行：CUST-0001 / A002 / TEST-DS-202601011226
            b.setText(SHEET, FIRST_DATA_ROW, Fixtures.CUSTOMER_NO_COLUMN, CUSTOMER_ROCKWELL);
            b.setText(SHEET, FIRST_DATA_ROW, "客户产品编号", SHARED_PRODUCT_NO);
            b.setText(SHEET, FIRST_DATA_ROW, "销售料号", materialA);

            // 第二行：CUST-0004 / 同一个 A002 / 另一个销售料号
            b.appendCopyOf(SHEET, FIRST_DATA_ROW, null);
            int secondRow = b.dataRowNumbers(SHEET, false).get(1);
            b.setText(SHEET, secondRow, Fixtures.CUSTOMER_NO_COLUMN, CUSTOMER_CHINT);
            b.setText(SHEET, secondRow, "客户产品编号", SHARED_PRODUCT_NO);
            b.setText(SHEET, secondRow, "销售料号", materialB);

            Response r = DatasetApi.importFile(adminSession(), DatasetApi.QUOTE,
                    b.toBytes(), "ac47-cross-customer.xlsx");
            assertStatus(r, 200, "AC-47");

            assertTrue(tableExists(TABLE), "AC-47：" + TABLE + " 不存在，断言无从执行");

            // 🚨 判据是「客户编号的集合」，不是「行数 == 2」——
            //    只数行数的话，两行都落成同一个客户也能凑够 2，断言会被巧合骗过。
            @SuppressWarnings("unchecked")
            List<Object> customerNos = (List<Object>) em.createNativeQuery(
                            "SELECT customer_no FROM " + TABLE
                                    + " WHERE customer_product_no = '" + SHARED_PRODUCT_NO + "'"
                                    + " AND material_no LIKE '" + P + "%' ORDER BY 1")
                    .getResultList();

            assertFalse(customerNos.isEmpty(),
                    "AC-47：导入返回 200 但 " + TABLE + " 里查不到 " + SHARED_PRODUCT_NO
                            + " 的任何行 ⇒ 空验证（testing.md §3.3）");

            TreeSet<String> actual = new TreeSet<>(customerNos.stream().map(String::valueOf).toList());
            assertEquals(new TreeSet<>(List.of(CUSTOMER_ROCKWELL, CUSTOMER_CHINT)), actual,
                    "AC-47 🚩 跨客户串号：期望 " + SHARED_PRODUCT_NO + " 下同时存在 "
                            + CUSTOMER_ROCKWELL + " 与 " + CUSTOMER_CHINT + " 两行，实际 " + actual
                            + "。\n  若只剩 1 行 ⇒ ds_quote_customer_part 的唯一约束漏了客户维度，"
                            + "与 RECORD.md 的森萨塔串号同型（CLAUDE.md §4.3：同一症状第 2 次出现）。");

            assertEquals(2, customerNos.size(),
                    "AC-47：期望正好 2 行，实际 " + customerNos.size() + " 行：" + customerNos);

            // 两行的销售料号也必须各自独立，不能被对方覆盖
            long a = countRows(TABLE, "customer_no='" + CUSTOMER_ROCKWELL + "' AND material_no='" + materialA + "'");
            long bb = countRows(TABLE, "customer_no='" + CUSTOMER_CHINT + "' AND material_no='" + materialB + "'");
            assertEquals(1L, a, "AC-47：" + CUSTOMER_ROCKWELL + " → " + materialA + " 这条不见了");
            assertEquals(1L, bb, "AC-47：" + CUSTOMER_CHINT + " → " + materialB + " 这条不见了");

            System.out.printf("[TC-03] %s 下的客户编号 = %s%n", SHARED_PRODUCT_NO, actual);
        } finally {
            b.close();
        }
    }

    // ═════════════════════ 助手 ═════════════════════

    /** 报价侧 16 张表的 count 快照（AC-46 的口径）。 */
    private Map<String, Long> quoteCountsSnapshot() {
        java.util.LinkedHashMap<String, Long> m = new java.util.LinkedHashMap<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if ("quote".equals(s.dataset)) {
                m.put(s.tableName, countRows(s.tableName, null));
            }
        }
        assertEquals(16, m.size(), "报价数据集应有 16 张主表，矩阵解析出 " + m.size() + " 张 ⇒ 判据坏了");
        return m;
    }

    private void assertStatus(Response r, int expected, String ac) {
        int actual = r.statusCode();
        if (actual == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401。⚠️ 先怀疑 test.md §0.2 的既有环境缺陷，"
                    + "或 admin 被 E2E 置成 INACTIVE。响应：" + r.asString());
        }
        assertEquals(expected, actual, ac + "：HTTP 状态码不符。响应体：" + r.asString());
    }

    private List<Map<String, Object>> errorsOf(Response r) {
        List<Map<String, Object>> errors = r.jsonPath().getList("data.errors");
        assertNotNull(errors, "api.md §1：400 响应必须带 data.errors。实际：" + r.asString());
        assertFalse(errors.isEmpty(), "data.errors 为空 ⇒ 逐条断言空跑（假绿）。实际：" + r.asString());
        return errors;
    }

    private void assertHasError(List<Map<String, Object>> errors,
                                String sheet, int row, String column, String reason, String ac) {
        boolean hit = errors.stream().anyMatch(e ->
                sheet.equals(String.valueOf(e.get("sheet")))
                        && String.valueOf(row).equals(String.valueOf(e.get("row")))
                        && column.equals(String.valueOf(e.get("column")))
                        && reason.equals(String.valueOf(e.get("reason"))));
        assertTrue(hit, ac + "：报告里找不到条目 {sheet=" + sheet + ", row=" + row
                + ", column=" + column + ", reason=" + reason + "}。实际报告：" + errors);
    }
}
