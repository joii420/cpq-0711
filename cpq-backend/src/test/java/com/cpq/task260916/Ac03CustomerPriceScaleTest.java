package com.cpq.task260916;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-3（边界 · 第 10 位舍入）</b> = T-API-01，及其还原实验 <b>RX-1</b>。
 *
 * <blockquote>AC-3 原文：前置 —— 自造一个客户策略：最新一条价 × 1 + 0，源下某元素日价 = {@code 0.1234567895}；
 * 操作 —— 对该客户调用 {@code f_customer_element_price}；
 * 断言 —— 该元素 {@code unit_price} 数值等于 {@code 0.12345679}（第 10 位 5 进位）；
 * 另造日价 {@code 0.1234567894} 时等于 {@code 0.123456789}。</blockquote>
 *
 * <p>「另造」按 test.md T-API-01 允许的「两个元素」落地：同一自造源同一日，
 * Cu = {@code 0.1234567895}、Zn = {@code 0.1234567894}，一次调用同时断言两条。
 * 日价只能 SQL 直插 —— 任何写接口都会先舍入到 9 位，造不出 10 位原值。
 */
@QuarkusTest
@DisplayName("task-260916 · S-API · AC-3 取价第 10 位舍入 + RX-1 还原实验")
class Ac03CustomerPriceScaleTest extends T916ApiBase {

    static final LocalDate D = LocalDate.of(2020, 3, 3);
    static final String CU_RAW = "0.1234567895";
    static final String ZN_RAW = "0.1234567894";
    static final String CU_EXPECT = "0.12345679";
    static final String ZN_EXPECT = "0.123456789";

    /** 匹配 ROUND( … , 9 )，允许一层内嵌括号；命名组 pre=「ROUND(…,」 post=「)」。 */
    static final Pattern ROUND_9 = Pattern.compile(
            "(?<pre>ROUND\\s*\\((?:[^()]|\\([^()]*\\))*?,\\s*)9(?<post>\\s*\\))", Pattern.CASE_INSENSITIVE);
    static final Pattern ROUND_4 = Pattern.compile(
            "ROUND\\s*\\((?:[^()]|\\([^()]*\\))*?,\\s*4\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** AC-3 的断言本体 —— RX-1 复用同一段，证明「同一个判据」在干预下会变红。 */
    static void assertAc3(Map<String, Object> prices) {
        assertFalse(prices.isEmpty(),
                "AC-3：f_customer_element_price 对自造客户返回 0 行 ⇒ 断言无法执行（空跑）。"
                        + "排查：策略是否落库 / 源是否 ACTIVE / Cu、Zn 是否 ACTIVE / 日期是否 ≤ 基准日");
        assertTrue(prices.containsKey("Cu"), "AC-3：结果里没有 Cu 行。实际=" + prices);
        assertTrue(prices.containsKey("Zn"), "AC-3：结果里没有 Zn 行。实际=" + prices);
        assertNumEq(CU_EXPECT, prices.get("Cu"), "AC-3 Cu（日价 " + CU_RAW + "，第 10 位 5 进位）");
        assertNumEq(ZN_EXPECT, prices.get("Zn"), "AC-3 Zn（日价 " + ZN_RAW + "，第 10 位 4 舍去）");
    }

    // ═══════════════════ T-API-01 ═══════════════════

    @Test
    @DisplayName("AC-3：日价 0.1234567895 → 0.12345679；0.1234567894 → 0.123456789")
    void ac3_tenthDigitRounding() throws Exception {
        Fixture fx = newCustomer("A3");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "A3");
            assertStatus(putDefaultStrategy(fx, "LATEST", "1", "0"), 200, "AC-3 前置：保存默认策略");
            insertDailyPrice(fx, "Cu", D, CU_RAW);
            insertDailyPrice(fx, "Zn", D, ZN_RAW);
            assertAc3(customerElementPrice(fx, D));
        });
    }

    // ═══════════════════ RX-1 ═══════════════════

    /**
     * RX-1（test.md §3）：在<b>同一个 JDBC 事务</b>里把取价函数 CREATE OR REPLACE 回 {@code ROUND(…, 4)}，
     * 跑 AC-3 同一段断言 ⇒ 必须变红（得 {@code 0.1235}）；然后 ROLLBACK，🚫 绝不提交。
     *
     * <p>干预方式：取 {@code pg_get_functiondef} 原文，把所有 {@code ROUND(…, 9)} 机械替换为 {@code ROUND(…, 4)}。
     * 本类只对函数定义做正则计数与替换、打印命中片段，不据此推导任何断言。
     * <ol>
     *   <li>干预前：定义里 {@code ROUND(…, 9)} 命中数必须 ≥ 1，否则「干预对象不存在」⇒ 中止，不改任何东西；</li>
     *   <li>干预后、跑断言前：同事务内重读定义，必须 {@code ROUND(…, 9)} = 0 且 {@code ROUND(…, 4)} ≥ 1（先证明生效，§5.7②）；</li>
     *   <li>ROLLBACK 后：用<b>新连接</b>重读定义，{@code ROUND(…, 9)} 命中数必须回到干预前的值并打印（还原后复验，§5.7①）；</li>
     *   <li>还原后再跑一次 AC-3 断言，必须重新变绿（证明现场已复原，没有把别片打红）。</li>
     * </ol>
     */
    @Test
    @DisplayName("RX-1：同事务把取价函数改回 4 位 → AC-3 断言变红 → ROLLBACK → 复验仍为 9 位")
    void rx1_revertToScale4_turnsAc3Red_thenRollback() throws Exception {
        Fixture fx = newCustomer("RX1");
        withCleanup(List.of(fx), () -> {
            newSource(fx, "RX1");
            assertStatus(putDefaultStrategy(fx, "LATEST", "1", "0"), 200, "RX-1 前置：保存默认策略");
            insertDailyPrice(fx, "Cu", D, CU_RAW);
            insertDailyPrice(fx, "Zn", D, ZN_RAW);

            // 阳性基线：干预前 AC-3 是绿的（否则「变红」没有意义）
            assertAc3(customerElementPrice(fx, D));

            String url = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class);
            String user = ConfigProvider.getConfig().getValue("quarkus.datasource.username", String.class);
            String pwd = ConfigProvider.getConfig().getValue("quarkus.datasource.password", String.class);
            assertTrue(url.contains("/cpq_db_test"),
                    "RX-1 只允许在 cpq_db_test 上做，当前 url=" + url + " —— 中止");

            int before9;
            try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
                conn.setAutoCommit(false);
                try {
                    try (Statement st = conn.createStatement()) {
                        st.execute("SET LOCAL lock_timeout = '5s'");
                        st.execute("SET LOCAL statement_timeout = '30s'");
                    }
                    List<String> defs = functionDefs(conn);
                    assertEquals(1, defs.size(), "RX-1：f_customer_element_price 重载数应为 1，实际 " + defs.size());
                    String original = defs.get(0);
                    before9 = printMatches("RX-1 干预前", original, ROUND_9);
                    assertTrue(before9 >= 1, "RX-1 前置不成立：函数定义里找不到 ROUND(…, 9) ⇒ 干预对象不存在，"
                            + "中止实验（未做任何修改），报主线");

                    Matcher m = ROUND_9.matcher(original);
                    String reverted = m.replaceAll("${pre}4${post}");
                    try (Statement st = conn.createStatement()) {
                        st.execute(reverted);   // 仅在本事务内生效
                    }

                    // 先证明干预生效
                    String inTx = functionDefs(conn).get(0);
                    int in9 = printMatches("RX-1 干预中", inTx, ROUND_9);
                    int in4 = printMatches("RX-1 干预中", inTx, ROUND_4);
                    assertEquals(0, in9, "RX-1：干预后仍有 ROUND(…, 9) ⇒ 干预未生效，中止");
                    assertTrue(in4 >= 1, "RX-1：干预后找不到 ROUND(…, 4) ⇒ 干预未生效，中止");

                    Map<String, Object> redValues = queryInTx(conn, fx.customerNo, D);
                    System.out.println("[RX-1] 干预中取价 = " + redValues);
                    AssertionFailedError red = assertThrows(AssertionFailedError.class, () -> assertAc3(redValues),
                            "RX-1：取价函数改回 4 位后 AC-3 断言仍然通过 ⇒ AC-3 判据恒绿，不可作为证据");
                    System.out.println("[RX-1] ✅ AC-3 断言在干预下变红：" + red.getMessage());
                    assertNumEq("0.1235", redValues.get("Cu"), "RX-1 干预中 Cu（应为 4 位舍入值）");
                } finally {
                    conn.rollback();   // 🚫 绝不提交
                    System.out.println("[RX-1] ROLLBACK 已执行");
                }
            }

            // 还原后复验：新连接读定义
            try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
                String after = functionDefs(conn).get(0);
                int after9 = printMatches("RX-1 还原后", after, ROUND_9);
                System.out.println("[RX-1 还原后复验] ROUND(…, 9) 命中=" + after9 + "（应 " + before9 + "）");
                assertEquals(before9, after9, "RX-1：ROLLBACK 后函数定义未复原！立即报主线");
            }
            assertAc3(customerElementPrice(fx, D));
            System.out.println("[RX-1 还原后复验] AC-3 断言重新为绿");
        });
    }

    // ═══════════════════ helpers ═══════════════════

    private static List<String> functionDefs(Connection conn) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_get_functiondef(p.oid) FROM pg_proc p "
                     + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                     + "WHERE n.nspname = 'public' AND p.proname = 'f_customer_element_price'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static int printMatches(String tag, String def, Pattern p) {
        Matcher m = p.matcher(def);
        int n = 0;
        while (m.find()) {
            n++;
            System.out.println("[" + tag + "] 命中片段#" + n + "：" + m.group().replaceAll("\\s+", " "));
        }
        System.out.println("[" + tag + "] " + p.pattern().substring(0, Math.min(20, p.pattern().length()))
                + "… 命中数=" + n);
        return n;
    }

    private static Map<String, Object> queryInTx(Connection conn, String customerNo, LocalDate d) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT element_code, unit_price FROM f_customer_element_price(?, ?) ORDER BY element_code")) {
            ps.setString(1, customerNo);
            ps.setObject(2, d);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal v = rs.getBigDecimal(2);
                    m.put(rs.getString(1), v);
                }
            }
        }
        return m;
    }
}
