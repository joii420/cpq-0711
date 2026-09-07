package com.cpq.task260907r;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 🚨 <b>本套用例的「假绿闸」—— 刻意<u>不是</u> {@code @QuarkusTest}</b>。
 *
 * <h3>它在防什么（2026-09-07 实测复现，不是推测）</h3>
 * 本包其余用例全是 {@code @QuarkusTest}。当 Quarkus <b>启动失败</b>时，surefire 的汇总长这样：
 * <pre>
 *   Tests run: 6, Failures: 0, Errors: 1, Skipped: 5
 * </pre>
 * <b>5 个 skip、0 个 failure</b> —— 一眼扫过去和「基本都过了」几乎无法区分。
 * 而实际情况是：<b>一个业务断言都没执行过</b>。
 *
 * <p>🔬 <b>本任务当场撞上了这一形态</b>（2026-09-07 04:12，worktree {@code feat/task-260907-record-backfill}）：
 * 后端已在 Registry 里声明了 {@code source_quotation_id} 与 13 张 {@code _record} 表，
 * 但建表迁移放在 {@code db/migration-pending-260907/}（被上游 {@code customer_no} DDL 阻塞，刻意未应用）
 * ⇒ 启动期 {@code DatasetSchemaSelfCheck} 报 <b>39 处 Registry↔DDL 不一致</b>
 * （13 主表缺列 + 13 {@code _history} 缺列 + 13 {@code _record} 表不存在）⇒ 应用起不来。
 *
 * <p>⚠️ 这与 {@code RECORD.md} 2026-09-06 记的 {@code task-260819} 事故<b>完全同型</b>：
 * 因 {@code V417} 未在 master，Quarkus 根本没起来，<b>44 个用例全 skip</b>，主线差点据此下反向结论。
 *
 * <h3>它怎么防</h3>
 * 本类只用<b>纯 JDBC</b>，不依赖 CDI、不依赖应用启动 ⇒ Quarkus 起不来时它<b>照样执行</b>，
 * 并以<b>红</b>（不是 skip）的形态把原因说清楚。
 * ⇒ 判据：<b>本类是红的，就不许解读本包其余任何测试结果</b>（绿也不算数，skip 更不算数）。
 */
@DisplayName("🚨 假绿闸 · 本套用例的执行前置（非 @QuarkusTest）")
class RecordSuiteBootGateTest {

    // 与 application-test.properties 的默认值一致（CLAUDE.md 已实证：test profile 就是共享开发库）
    private static final String URL = System.getenv().getOrDefault("CPQ_TEST_JDBC_URL",
            "jdbc:postgresql://" + System.getenv().getOrDefault("DB_HOST", "10.177.152.12")
                    + ":" + System.getenv().getOrDefault("DB_PORT", "5432")
                    + "/" + System.getenv().getOrDefault("DB_NAME", "cpq_db_0724") + "?sslmode=disable");
    private static final String USER = System.getenv().getOrDefault("DB_USERNAME", "postgres");
    private static final String PASS = System.getenv().getOrDefault("DB_PASSWORD", "joii5231");

    /**
     * 闸门：{@code _record} 层是否已落库。
     *
     * <p>没落库 ⇒ 本包所有 {@code @QuarkusTest} 都会因启动自检失败而 <b>skip</b>，
     * 那批 skip <b>不构成任何结论</b>。本条以红的形态把这件事说出来。
     */
    @Test
    @DisplayName("GATE · _record 层已落库（否则本包所有 @QuarkusTest 的 skip 都不构成结论）")
    void gate_recordLayerApplied() throws Exception {
        List<String> missing = new ArrayList<>();
        int versioned = 0;
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT t.table_name, "
                            + "  (SELECT count(*) FROM information_schema.tables r "
                            + "   WHERE r.table_name = t.table_name || '_record') AS has_rec "
                            + "FROM information_schema.tables t "
                            + "WHERE t.table_schema='public' AND t.table_name LIKE 'ds\\_quote\\_%' "
                            + "  AND t.table_name NOT LIKE '%\\_history' AND t.table_name NOT LIKE '%\\_record' "
                            + "  AND EXISTS(SELECT 1 FROM information_schema.columns col "
                            + "             WHERE col.table_name=t.table_name AND col.column_name='version_no') "
                            + "ORDER BY 1")) {
                while (rs.next()) {
                    versioned++;
                    if (rs.getInt(2) == 0) missing.add(rs.getString(1));
                }
            }
        }

        // 🚨 阳性对照：连得上库、且「带版本主表」这个集合非空。
        //    集合为空时下面的「都建好了」恒真恒通过 —— 那才是最彻底的假绿。
        if (versioned == 0) {
            fail("🚨 假绿闸自身失效：查不到任何带 version_no 的 ds_quote_ 主表。"
                    + "要么连错了库（当前 URL=" + URL + "），要么判据 SQL 写坏了。"
                    + "此时「_record 都建好了」会恒真 —— 必须先修好本条，再谈其余结论。");
        }

        if (!missing.isEmpty()) {
            fail("⛔ 执行前置未满足（**不是被测功能的结论**）：" + missing.size() + " / " + versioned
                    + " 张带版本主表尚无 _record 表 —— " + missing + "\n"
                    + "\n"
                    + "🚨 后果（本任务 2026-09-07 04:12 实测撞上）：\n"
                    + "   后端已在 Registry 里声明了 source_quotation_id 与 13 张 _record 表，\n"
                    + "   而建表迁移放在 db/migration-pending-260907/ 未应用 ⇒ 启动期 DatasetSchemaSelfCheck\n"
                    + "   报 39 处 Registry↔DDL 不一致 ⇒ **Quarkus 起不来** ⇒ 本包所有 @QuarkusTest\n"
                    + "   以 `Tests run: N, Failures: 0, Skipped: N-1` 的形态收场。\n"
                    + "   那批 skip 长得和「基本都过了」几乎一样，但**一个业务断言都没执行过**。\n"
                    + "   （同型事故：RECORD.md 2026-09-06 task-260819，V417 未在 master，44 个用例全 skip。）\n"
                    + "\n"
                    + "✅ 判据：**本条红着，就不许解读本包其余任何测试结果** —— 绿也不算数，skip 更不算数。\n"
                    + "✅ 解除方式：把 db/migration-pending-260907/ 下的建表迁移取号并应用（须先解上游\n"
                    + "   `task-260907-报价侧加客户维度` 的 customer_no DDL 阻塞），再重跑。");
        }
    }
}
