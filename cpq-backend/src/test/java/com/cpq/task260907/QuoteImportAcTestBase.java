package com.cpq.task260907;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260907「报价导入建单切 ds_ 新表体系」验收测试基座。
 *
 * <p>断言一律回到 {@code 需求文档.md §3} 的 AC 原文与 {@code api.md} 的契约。
 * 🚫 本包不 import 任何 {@code src/main} 下的类 —— 用例从 AC 派生，不从实现派生。
 *
 * <h3>🚨 共享库红线（CLAUDE.md §3.2）</h3>
 * {@code test} profile 实连共享开发库 {@code cpq_db_0724}（= dev 库本身，
 * {@code application-test.properties:24} 实证）。因此：
 * <ul>
 *   <li>🚫 全套用例不含 {@code TRUNCATE} / {@code DROP} / 无 {@code WHERE} 的 {@code DELETE}</li>
 *   <li>✅ 全部夹具料号带 {@link #P} 前缀；还原面被 {@code LIKE 'T260907-%'} 限死，且只删 {@code ds_quote_*}</li>
 *   <li>🚫 {@code customer} / {@code element} / {@code material_recipe} / {@code process_master}
 *       等共享主数据只读，一个字节都不写</li>
 * </ul>
 *
 * <h3>⚠️ 为什么 count 断言不写死字面量</h3>
 * 共享库上其他会话随时在写。凡「导入前后 count 不变」一律写成
 * <b>同一时刻基准快照 vs 事后快照逐表相等</b>，不写死 47 / 68 这类数字。
 *
 * <h3>⚠️ 恒 401 的既有环境缺陷</h3>
 * 不带 session 的 RestAssured 请求恒 401（{@code src/test/resources/application.properties}
 * 被 {@code application-test.properties} 覆盖）。所有请求都要带 session。
 * 看到 {@code Expected <200> but was <401>} 先怀疑这个，别误判成「端点没做」。
 */
public abstract class QuoteImportAcTestBase {

    /**
     * 🚨 本套用例唯一的夹具前缀。还原面靠它限死。
     *
     * <p>🚩 <b>2026-09-07 由 {@code T260907-} 改成 {@code T260907T-}</b>：实测共享库里出现了
     * {@code T260907-M1} / {@code T260907-M2}（并发会话写的，非本套夹具）。两边都按
     * {@code LIKE 'T260907-%'} 做前缀清理的话<b>会互删对方的夹具</b> ——
     * 症状是随机挂、且看起来极像业务回归（testing.md §4.3）。
     * {@code T260907T-} 与 {@code T260907-%} <b>互不匹配</b>，两个命名空间就此隔离。
     */
    protected static final String P = "T260907T-";

    /** AC-1 点名的客户：正泰。实测存在于 {@code customer.code}。 */
    protected static final String CUSTOMER_CHINT = "CUST-0004";
    /** AC-3 负例里的「另一个客户」。🚩 必须在 {@code customer.code} 命中，
     *  否则先被 D-19「客户编号不存在」拦掉，测到的是另一条规则，AC-3 空过。 */
    protected static final String CUSTOMER_ROCKWELL = "CUST-0001";

    /** AC-19 用的无权限角色账号（{@code user} 表实测 PRICING_MANAGER / ACTIVE）。 */
    protected static final String USER_PRICING = "t260903_pm";
    protected static final String PWD = "Admin@2026";

    /** 16 张业务表 + 13 张 history，逐表 count 快照用。 */
    protected static final List<String> QUOTE_TABLES = List.of(
            "ds_quote_material", "ds_quote_customer_part", "ds_quote_plating_scheme",
            "ds_quote_material_bom", "ds_quote_element_bom",
            "ds_quote_incoming_fixed_fee", "ds_quote_incoming_other_fee", "ds_quote_incoming_recovery",
            "ds_quote_self_process_fee", "ds_quote_finished_other_fee", "ds_quote_sub_component_fee",
            "ds_quote_assembly_fee", "ds_quote_plating_fee",
            "ds_quote_assembly_fee_annual", "ds_quote_incoming_annual", "ds_quote_annual_discount");

    /** 免版本 3 张（无 version_no / 无 _history）—— AC-12② 的断言面。 */
    protected static final List<String> PLAIN_TABLES = List.of(
            "ds_quote_material", "ds_quote_customer_part", "ds_quote_plating_scheme");

    @Inject
    protected EntityManager em;

    /**
     * 🚨 <b>静态</b>缓存。JUnit 每方法新建实例，实例字段会导致每条用例登录一次；
     * 登录带 Redis 限流（30 次/分/IP），一轮跑下来必然自撞，
     * 症状是「从某一条起全部 401」——<b>长得像鉴权坏了，其实是自己打的</b>。
     */
    private static final Map<String, String> SESSIONS = new LinkedHashMap<>();

    @BeforeEach
    void baseSetUp() {
        // 上轮若中途崩溃留下残渣，这里兜底 —— 避免「上轮残留」被误读成「本轮 bug」
        restoreFixtures();
        assertNoFixtureResidue();
    }

    @AfterEach
    void baseTearDown() {
        try {
            restoreFixtures();
        } finally {
            assertNoFixtureResidue();
        }
    }

    // ═══════════════════ 还原（只删 ds_quote_*，只删 T260907- 前缀） ═══════════════════

    protected void restoreFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            // 先 history 后主表：history 有指向主表的语义，反序会留孤儿
            for (String t : QUOTE_TABLES) {
                deleteByPrefix(t + "_history", prefixColumn(t));
            }
            for (String t : QUOTE_TABLES) {
                deleteByPrefix(t, prefixColumn(t));
            }
        });
    }

    /** 电镀方案的前缀列是 scheme_no，其余全是 material_no。 */
    private String prefixColumn(String table) {
        return "ds_quote_plating_scheme".equals(table) ? "scheme_no" : "material_no";
    }

    private void deleteByPrefix(String table, String column) {
        if (!tableExists(table) || !columnExists(table, column)) {
            return;
        }
        em.createNativeQuery("DELETE FROM " + table + " WHERE " + column + " LIKE :p")
                .setParameter("p", P + "%")
                .executeUpdate();
    }

    /** 还原自检：任何 ds_quote_* 表里都不许再有 T260907- 残留。 */
    protected void assertNoFixtureResidue() {
        List<String> dirty = new ArrayList<>();
        for (String t : QUOTE_TABLES) {
            for (String tt : List.of(t, t + "_history")) {
                String col = prefixColumn(t);
                if (!tableExists(tt) || !columnExists(tt, col)) {
                    continue;
                }
                long n = count("SELECT count(*) FROM " + tt + " WHERE " + col + " LIKE '" + P + "%'");
                if (n > 0) {
                    dirty.add(tt + "=" + n);
                }
            }
        }
        assertTrue(dirty.isEmpty(), "夹具残留未清干净（不是业务缺陷，是上一轮没还原）：" + dirty);
    }

    // ═══════════════════ session ═══════════════════

    protected String adminSession() {
        return sessionOf("admin", PWD);
    }

    /** AC-19 用：无导入权限的角色。 */
    protected String pricingManagerSession() {
        return sessionOf(USER_PRICING, PWD);
    }

    protected String sessionOf(String username, String password) {
        return SESSIONS.computeIfAbsent(username, u -> {
            io.restassured.response.Response r = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("{\"username\":\"" + u + "\",\"password\":\"" + password + "\"}")
                    .when().post("/api/cpq/auth/login");
            if (r.statusCode() != 200) {
                throw new AssertionError("登录失败：" + u + " → HTTP " + r.statusCode()
                        + "\n  响应体：" + r.asString()
                        + "\n  ⚠️ 依次排查：① Redis 登录限流（30 次/分/IP，本类已静态缓存以避免自撞）；"
                        + "② 账号被 E2E 置成 INACTIVE；③ 账号锁定 locked_until。");
            }
            String s = r.cookie("CPQ_SESSION");
            assertNotNull(s, u + " 登录 200 却没拿到 CPQ_SESSION");
            return s;
        });
    }

    // ═══════════════════ 只读 SQL 助手 ═══════════════════

    protected long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    protected long countRows(String table, String where) {
        if (!tableExists(table)) {
            return -1L;
        }
        return count("SELECT count(*) FROM " + table
                + (where == null || where.isBlank() ? "" : " WHERE " + where));
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected boolean tableExists(String t) {
        return count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='" + t + "'") > 0;
    }

    protected boolean columnExists(String t, String c) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name='" + t + "' AND column_name='" + c + "'") > 0;
    }

    /** 按 code 取客户 id（只读）。查不到硬失败 —— 夹具锚点漂了必须立刻暴露。 */
    protected String customerIdOf(String code) {
        List<Object> l = col("SELECT id::text FROM customer WHERE code='" + code + "'");
        assertFalse(l.isEmpty(), "customer.code 里没有「" + code + "」——"
                + " 夹具锚点漂了，此时任何断言都是假红/假绿");
        return String.valueOf(l.get(0));
    }

    // ═══════════════════ 快照（AC-2 / AC-3 / AC-4 / AC-17 用） ═══════════════════

    /** 16 主表 + 13 history 的 count 快照。缺表记 -1，快照间比对时同样有意义。 */
    protected Map<String, Long> snapshotCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : QUOTE_TABLES) {
            m.put(t, countRows(t, null));
            if (tableExists(t + "_history")) {
                m.put(t + "_history", countRows(t + "_history", null));
            }
        }
        return m;
    }

    /**
     * 逐表比对两个快照。
     * 🚨 断言前先要求「快照非空且确实有已建表」—— 表全不存在时快照会是一串 -1，
     *    两次相等会<b>假绿</b>（断言从未真正执行）。
     */
    protected void assertCountsUnchanged(Map<String, Long> before, Map<String, Long> after, String because) {
        assertFalse(before.isEmpty(), "快照为空 = 断言从未执行（假绿）。" + because);
        long built = before.values().stream().filter(v -> v >= 0).count();
        assertTrue(built >= QUOTE_TABLES.size(),
                "ds_quote_* 表没建全（已建 " + built + "）⇒ 「count 不变」是空验证。" + because);
        List<String> diff = new ArrayList<>();
        before.forEach((k, v) -> {
            if (!v.equals(after.get(k))) {
                diff.add(k + ": " + v + " → " + after.get(k));
            }
        });
        assertTrue(diff.isEmpty(), because + " —— 以下表的 count 变了：" + diff);
    }
}
