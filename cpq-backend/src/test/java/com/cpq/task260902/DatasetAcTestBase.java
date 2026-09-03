package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902「报价与核价建表与导入方案新规范」验收测试的公共基座。
 *
 * <p>本类只做四件事：<b>登录取 session · 只读 SQL 助手 · TEST-DS- 前缀还原 · 还原自检</b>。
 * 断言一律回到 {@code 需求文档.md ④} 的 AC 原文，本类不含任何业务判据，也不 import 任何 {@code src/main} 下的类。
 *
 * <h3>🚨 共享库红线（{@code CLAUDE.md} §3.2 + {@code test.md} §0.1）</h3>
 * {@code mvnw test} 走的 {@code test} profile <b>实连共享开发库 {@code cpq_db_0724}</b>（就是 dev 库本身）。
 * <ul>
 *   <li>🚫 全套用例<b>不含</b> {@code TRUNCATE} / {@code DROP} / 无 {@code WHERE} 的 {@code DELETE}。</li>
 *   <li>✅ 所有夹具轴值一律带 {@link #P} 前缀；还原面被 {@code LIKE 'TEST-DS-%'} 限死，且<b>只删 {@code ds_*} 表</b>。</li>
 *   <li>🚫 {@code element} / {@code process} / {@code process_master} / {@code material_recipe} / {@code customer}
 *       等共享主数据<b>只读，一个字节都不写</b>。</li>
 * </ul>
 *
 * <h3>⚠️ 为什么断言不写死行数</h3>
 * {@code test.md} §0.3：实测同一条 {@code count(*)} 几分钟内会变（其他会话在写共享库）。
 * 因此「导入前后 count 不变」一律写成<b>同一时刻基准快照 vs 事后快照逐表相等</b>，不写死 128 这类字面量。
 *
 * <h3>⚠️ RBAC 恒 401 的既有环境缺陷（{@code test.md} §0.2）</h3>
 * {@code src/test/resources/application.properties:5}={@code false} 被
 * {@code application-test.properties:86}={@code true} 覆盖 ⇒ 不带 session 的 RestAssured 请求恒 401。
 * <b>本任务不修它</b>，所有请求都走 {@link #adminSession()}。看到 {@code Expected <200> but was <401>}
 * 先怀疑这个坑，不要误判成「端点没做」。
 */
public abstract class DatasetAcTestBase {

    /** 🚨 本套用例唯一的夹具轴值前缀。还原面靠它限死，别改。 */
    protected static final String P = "TEST-DS-";

    /** 基础核价（核价2）夹具轴值。AC 原文的字面值是 {@code 3120014539}，共享库上必须加前缀。 */
    protected static final String AXIS_BASIC = P + "3120014539";
    /** 基础核价物料表（免版本）的主键值。AC-21 原文字面值 {@code S-3120014539}。 */
    protected static final String AXIS_BASIC_MATERIAL = P + "S-3120014539";
    /** AC-19 的「另一个不该被动的料号」。原文字面值 {@code 9999999999}。 */
    protected static final String AXIS_BASIC_OTHER = P + "9999999999";
    /** 报价夹具轴值。AC-36 原文字面值 {@code 202601011226}。 */
    protected static final String AXIS_QUOTE = P + "202601011226";

    // ── 主数据锚点：全部经 2026-09-03 实查确认存在且 ACTIVE，见 test-report「夹具真实性核查」 ──
    /** 元素代码，{@code element} 表实测存在 ACTIVE。 */
    protected static final String ELEMENT_CU = "Cu";
    protected static final String ELEMENT_AG = "Ag";
    protected static final String ELEMENT_NI = "Ni";
    /** AC-8 要求的「不存在的元素代码」，{@code element} 表实测 0 行。 */
    protected static final String ELEMENT_ABSENT = "ZZZZ";
    /** 材质料号，{@code material_recipe.code} 实测存在 ACTIVE。 */
    protected static final String RECIPE_00168 = "00168";
    protected static final String RECIPE_00006 = "00006";
    protected static final String RECIPE_992 = "992";
    /**
     * 工序编号。
     * 🚩 <b>模板里的 Z053/Z008/Z490/Z002/Z611 在 {@code process}（0 行）与 {@code process_master}（4 行）里都不存在</b>，
     * 见 test-report 的夹具核查。这里改用 {@code process_master} 中实测存在的 Z100/Z101。
     */
    protected static final String PROCESS_Z100 = "Z100";
    protected static final String PROCESS_Z101 = "Z101";

    /**
     * 客户编号（裁决 D-18 / D-19）。
     * 🚩 {@code customer.code} 里只有 3 个编号与现网 {@code material_customer_map} 对得上，夹具只许从这 3 个里取。
     * 🚫 不许用 {@code 8000142}~{@code 8000155} / {@code Q13CUST0617} / {@code C1} —— 它们未登记，会造成「假红」。
     */
    protected static final String CUSTOMER_ROCKWELL = "CUST-0001";
    protected static final String CUSTOMER_TEST = "CUST-0002";
    protected static final String CUSTOMER_CHINT = "CUST-0004";
    /** AC-46 的反例：明确不存在于 {@code customer.code}。 */
    protected static final String CUSTOMER_ABSENT = "NOTEXIST-999";

    @Inject
    protected EntityManager em;

    /**
     * 🚨 <b>静态</b>缓存：JUnit 每个测试方法新建一个实例，实例字段会导致<b>每条用例登录一次</b>。
     * 本项目的登录带 Redis 限流（30 次/分/IP），一轮跑下来必然撞限流，
     * 症状是「从某一条起全部 401」——<b>长得像鉴权坏了，其实是自己打的</b>。
     */
    private static String cachedSession;

    /** 45 张主表的规格（解析自 {@code 字段矩阵.md}）。 */
    protected static final List<FieldMatrixSpec.TableSpec> SPECS = FieldMatrixSpec.parseAll();

    // ═══════════════════════ 生命周期 ═══════════════════════

    @BeforeEach
    void baseSetUp() {
        // 上一轮若中途崩溃留下残渣，这里兜底 —— 避免「上轮残留」被误读成「本轮 bug」。
        // 清完立刻自检：脏库以「残留」的名义硬失败，不会伪装成业务缺陷。
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

    // ═══════════════════════ 还原（只删 ds_*，只删 TEST-DS- 前缀） ═══════════════════════

    /**
     * 按前缀精确还原全部 45 主表 + 39 history 表。
     * 🚫 无 {@code TRUNCATE}、无无条件 {@code DELETE}；表未建时静默跳过（迁移尚未落库的阶段）。
     */
    protected void restoreFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (FieldMatrixSpec.TableSpec s : SPECS) {
                if (s.versioned) {
                    deleteByAxisPrefix(s.historyTableName(), s.axisField);
                }
                deleteByAxisPrefix(s.tableName, primaryPrefixColumn(s));
            }
        });
    }

    /**
     * 免版本表没有「轴」，用它的第一个 varchar 主键列做前缀删除面。
     * 三张免版本表的主键首列分别是 material_no / customer_product_no / scheme_no / production_no（见 R-2）。
     */
    private String primaryPrefixColumn(FieldMatrixSpec.TableSpec s) {
        if (s.axisField != null) {
            return s.axisField;
        }
        // 免版本表：矩阵里第一个已建字段就是主键首列或包含它，这里取「表里实际存在的第一个候选」
        for (String cand : List.of("material_no", "production_no", "customer_product_no", "scheme_no")) {
            if (s.builtColumns.contains(cand)) {
                return cand;
            }
        }
        return s.builtColumns.isEmpty() ? null : s.builtColumns.get(0);
    }

    private void deleteByAxisPrefix(String table, String column) {
        if (column == null || !tableExists(table) || !columnExists(table, column)) {
            return;
        }
        em.createNativeQuery("DELETE FROM " + table + " WHERE " + column + " LIKE :p")
                .setParameter("p", P + "%")
                .executeUpdate();
    }

    /** 还原自检：任何 {@code ds_*} 表里都不许再有 {@code TEST-DS-} 前缀的残留。 */
    protected void assertNoFixtureResidue() {
        List<String> dirty = new ArrayList<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            String col = primaryPrefixColumn(s);
            if (col == null) {
                continue;
            }
            for (String t : s.versioned ? List.of(s.tableName, s.historyTableName()) : List.of(s.tableName)) {
                if (!tableExists(t) || !columnExists(t, col)) {
                    continue;
                }
                long n = count("SELECT count(*) FROM " + t + " WHERE " + col + " LIKE '" + P + "%'");
                if (n > 0) {
                    dirty.add(t + "=" + n);
                }
            }
        }
        assertTrue(dirty.isEmpty(), "夹具残留未清干净（不是业务缺陷，是上一轮没还原）：" + dirty);
    }

    // ═══════════════════════ session ═══════════════════════

    /**
     * 取 admin 的 {@code CPQ_SESSION}。
     * ⚠️ 这是绕开 {@code test.md} §0.2 那个恒 401 环境缺陷的唯一手段，不是业务逻辑。
     */
    protected String adminSession() {
        if (cachedSession == null) {
            cachedSession = login("admin", "Admin@2026");
            assertNotNull(cachedSession, "admin 登录未拿到 CPQ_SESSION —— 先查 admin 是否被 E2E 置成 INACTIVE");
        }
        return cachedSession;
    }

    protected String login(String username, String password) {
        io.restassured.response.Response r = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .when().post("/api/cpq/auth/login");
        if (r.statusCode() != 200) {
            // 🚩 登录失败必须报出原始响应 —— 否则后面每条用例都以 401 收场，
            //    读报告的人会去查权限实现，而真因可能是限流 / 账号被 E2E 置成 INACTIVE。
            throw new AssertionError("登录失败：" + username + " → HTTP " + r.statusCode()
                    + "\n  响应体：" + r.asString()
                    + "\n  ⚠️ 依次排查：① Redis 登录限流（30 次/分/IP，本类已改静态缓存以避免自撞）；"
                    + "② admin 被 E2E 置成 INACTIVE；③ 账号锁定 locked_until。");
        }
        return r.cookie("CPQ_SESSION");
    }

    // ═══════════════════════ 只读 SQL 助手 ═══════════════════════

    protected long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return ((Number) v).longValue();
    }

    protected long countRows(String table, String where) {
        if (!tableExists(table)) {
            return -1L;
        }
        return count("SELECT count(*) FROM " + table + (where == null || where.isBlank() ? "" : " WHERE " + where));
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected boolean tableExists(String table) {
        return count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='" + table + "'") > 0;
    }

    protected boolean columnExists(String table, String column) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                + table + "' AND column_name='" + column + "'") > 0;
    }

    /** 取一张表的列名集合（小写）。 */
    @SuppressWarnings("unchecked")
    protected List<String> columnsOf(String table) {
        return em.createNativeQuery(
                        "SELECT lower(column_name) FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='" + table + "' ORDER BY 1")
                .getResultList();
    }

    // ═══════════════════════ 基准快照（AC-10 / AC-19 / AC-39 / AC-43 用） ═══════════════════════

    /** 全部 45 主表 + 39 history 的 {@code count(*)} 快照。缺表记为 -1，快照间比对时同样有意义。 */
    protected Map<String, Long> snapshotAllDatasetCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            m.put(s.tableName, countRows(s.tableName, null));
            if (s.versioned) {
                m.put(s.historyTableName(), countRows(s.historyTableName(), null));
            }
        }
        return m;
    }

    /**
     * 逐表比对两个快照。
     * 🚨 断言前先要求「快照非空」—— 45 张表一张都没建时快照会是 45 个 -1，两次相等会<b>假绿</b>。
     */
    protected void assertCountsUnchanged(Map<String, Long> before, Map<String, Long> after, String because) {
        assertFalse(before.isEmpty(), "快照为空 = 断言从未执行（假绿）。" + because);
        long built = before.values().stream().filter(v -> v >= 0).count();
        assertTrue(built > 0,
                "全部 45 张 ds_* 表都不存在 ⇒ 「count 不变」是空验证。先确认迁移已落库。" + because);
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long a = after.get(e.getKey());
            if (!e.getValue().equals(a)) {
                diff.add(e.getKey() + ": " + e.getValue() + " → " + a);
            }
        }
        assertTrue(diff.isEmpty(), because + " —— 以下表的 count 变了：" + diff);
    }

    // ═══════════════════════ 主数据探针（夹具动态替换用） ═══════════════════════

    /**
     * 供 {@link Fixtures} 判断「这个主数据值库里到底有没有」。
     * 🚫 只读，不写任何主数据表。
     */
    protected Fixtures.MasterDataProbe probe() {
        return (table, column, value) -> {
            if (!tableExists(table) || !columnExists(table, column)) {
                return false;
            }
            return count("SELECT count(*) FROM " + table + " WHERE " + column + " = '"
                    + value.replace("'", "''") + "'") > 0;
        };
    }

    // ═══════════════════════ 数值比较（不写死 scale） ═══════════════════════

    /** 按数值相等比较，忽略 scale：{@code 5.5} 与 {@code 5.500000} 视为相等（R-3 规范化口径）。 */
    protected void assertNumericEquals(String expected, Object actual, String message) {
        assertNotNull(actual, message + "（实际为 null —— 断言若跳过 null 就是假绿）");
        BigDecimal a = new BigDecimal(expected);
        BigDecimal b = new BigDecimal(String.valueOf(actual));
        assertEquals(0, a.compareTo(b), message + "：期望 " + expected + "，实际 " + actual);
    }
}
