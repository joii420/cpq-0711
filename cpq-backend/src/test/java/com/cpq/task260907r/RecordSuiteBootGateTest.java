package com.cpq.task260907r;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 🚨 <b>本套用例的「假绿闸」—— 刻意<u>不是</u> {@code @QuarkusTest}。</b>
 *
 * <h3>它在防什么</h3>
 * 本包其余用例全是 {@code @QuarkusTest}。当 Quarkus <b>启动失败</b>时，surefire 的汇总长这样：
 * <pre>
 *   Tests run: 32, Failures: 1, Errors: 1, Skipped: 25
 * </pre>
 * <b>25 个 skip、1 个 error</b> —— 一眼扫过去和「基本都过了」几乎无法区分。
 * 而实际情况是：<b>一个业务断言都没执行过</b>。
 * （同型事故：{@code RECORD.md} 2026-09-06 {@code task-260819}，{@code V417} 未在 master，44 个用例全 skip。）
 *
 * <h3>🕰️ 2026-09-07 重写：原版<b>拦不住它本该拦的东西</b></h3>
 * 原版只用裸 JDBC 查 {@code information_schema} 里 13 张 {@code _record} 表在不在。
 * 同一天<b>连续两次</b>被穿透 —— 两次都是「闸绿、Quarkus 起不来、全包 skip」：
 * <ol>
 *   <li><b>17:27</b> 13 张 {@code _record} 表<b>已建好</b>（闸绿），但 26 张主表/{@code _history}
 *       缺 {@code source_quotation_id} ⇒ {@code DatasetSchemaSelfCheck} 报「缺列」⇒ 起不来。</li>
 *   <li><b>17:55</b> 上游把 {@code V425~V429} 合入 master 并落共享库，本分支缺这 5 个迁移文件
 *       ⇒ <b>Flyway validate</b> 先一步拦下（{@code Detected applied migration not resolved locally: 425}）
 *       ⇒ 起不来。表照样在，闸照样绿。</li>
 * </ol>
 * 🔑 <b>根因</b>：判据是「<b>表建好了</b>」，而要拦的是「<b>进程起得来</b>」——<b>两件事</b>。
 *
 * <h3>重写后的判据</h3>
 * 分两段，<b>两段都是硬失败</b>：
 * <ul>
 *   <li><b>Phase A · 确定性预判</b>（纯 JDBC + 文件，不依赖任何进程）：把两类「必然让 Quarkus 起不来」
 *       的状态直接算出来 —— <b>Flyway 迁移漂移</b>、<b>{@code _record} 表缺失</b>。</li>
 *   <li><b>Phase B · 启动实证</b>（读<b>本轮</b>的 {@code target/surefire-reports/}）：
 *       真出现「启动失败 / skip 风暴」就把<b>启动异常原文</b>抬出来，并归成
 *       ① Registry↔DDL 不一致 ② Flyway 漂移 ③ 表不存在 ④ 其他 四类，各给处置。</li>
 * </ul>
 * 🚫 <b>刻意不自己起一个 {@code @QuarkusTest} 去探</b>：那样本闸会和被它守护的用例<b>一起挂掉</b>，
 * 依然拿不到结论 —— 守卫和被守卫者共享故障模式，等于没有守卫。
 *
 * <p>✅ <b>判据：本类是红的，就不许解读本包其余任何测试结果</b> —— 绿也不算数，skip 更不算数。
 */
@DisplayName("🚨 假绿闸 · 本套用例的执行前置（非 @QuarkusTest）")
class RecordSuiteBootGateTest {

    // 与 application-test.properties 的默认值一致（CLAUDE.md 已实证：test profile 就是共享开发库）
    /**
     * 🚨 <b>解析顺序必须与 Quarkus 一致：系统属性 → 环境变量 → 默认值。</b>
     *
     * <h3>🕰️ 2026-09-08 修一个本闸自己的缺陷</h3>
     * 原来只读 {@code System.getenv()}，而 {@code application-test.properties} 的
     * {@code ${DB_NAME:...}} 由 <b>Quarkus 配置</b>解析、<b>认系统属性</b>
     * ⇒ 用 {@code -DDB_NAME=cpq_t260907r_laneb} 跑时，
     * <b>用例连 laneb，而本闸仍在查 0724</b> —— 两者指向不同的库。
     *
     * <p>🔬 实测后果（本轮撞上）：0724 被别的会话应用了 V430、本分支只到 V429
     * ⇒ 闸报「Flyway 漂移」，而用例其实跑在 laneb 上、laneb 与分支都是 429、根本没漂移。
     * <b>那是一条假红。</b>
     *
     * <p>🚨 但真正危险的是<b>镜像情形</b>：用例跑 0724、闸查 laneb ⇒
     * <b>闸绿而用例撞漂移</b> —— 那正是本闸存在的全部意义所在的那种假绿。
     * ⇒ 守卫与被守卫者<b>必须看同一个库</b>，否则守卫本身就是个谎。
     */
    private static String cfg(String key, String dflt) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static final String URL = cfg("CPQ_TEST_JDBC_URL",
            "jdbc:postgresql://" + cfg("DB_HOST", "10.177.152.12")
                    + ":" + cfg("DB_PORT", "5432")
                    + "/" + cfg("DB_NAME", "cpq_db_0724") + "?sslmode=disable");
    private static final String USER = cfg("DB_USERNAME", "postgres");
    private static final String PASS = cfg("DB_PASSWORD", "joii5231");

    /**
     * 迁移目录。<b>默认就是真实路径</b>；可用 {@code -Dcpq.gate.migrationDir=…} 覆盖。
     *
     * <p>🔑 <b>这个覆盖点只为一件事存在：让本闸自己可被证伪。</b>
     * 首次 PASS 证明不了守卫接上了（{@code RECORD.md}：「自己写的验证脚本首次 PASS 也可能是空验证」）。
     * 有了它，还原实验是<b>零仓库风险</b>的一条命令 —— 🚫 不必去挪真实迁移文件
     * （合并在途时挪它会干扰别人）：把 {@code db/migration} 拷到临时目录、<b>少拷</b>几个已应用的版本，
     * 再 {@code ./mvnw -o test -Dtest=RecordSuiteBootGateTest -Dcpq.gate.migrationDir=<临时目录>}
     * ⇒ <b>GATE-A1 必须变红</b>。不变红 = 本闸是白写的。
     *
     * <p>⚠️ 🚫 <b>不许在常规跑测里传这个参数</b>去让闸变绿 —— 它只是实验入口，不是逃生门。
     */
    private static final String MIGRATION_DIR =
            System.getProperty("cpq.gate.migrationDir", "src/main/resources/db/migration");
    private static final String REPORT_DIR = "target/surefire-reports";
    private static final String PKG = "com.cpq.task260907r.";

    // ════════════════════ Phase A · 确定性预判 ════════════════════

    /**
     * <b>A1 · Flyway 迁移漂移</b> —— 共享库里已应用、而本工作区<b>没有对应迁移文件</b>的版本号。
     *
     * <p>这正是 Flyway 启动期 {@code validate} 的判据
     * （{@code Detected applied migration not resolved locally: N}），
     * 在<b>任何</b> Quarkus 启动之前就会抛，因此可以纯离线算出来。
     *
     * <p>🔑 <b>为什么它必须是独立一条</b>：漂移的处置与其余两类<b>完全不同</b>
     * —— 它不是我们的实现有问题，而是分支落后于共享库，
     * 且处置里有两个明确的<b>禁止动作</b>（见失败文案）。归成一类会让读者按错的方向去修。
     */
    @Test
    @DisplayName("GATE-A1 · Flyway 未漂移（共享库已应用的迁移，本工作区都有文件）")
    void gateA1_noFlywayDrift() throws Exception {
        List<String> applied = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version, description FROM flyway_schema_history "
                             + "WHERE version IS NOT NULL AND success ORDER BY installed_rank")) {
            while (rs.next()) applied.add(rs.getString(1));
        }
        // 🚨 阳性对照：applied 为空时下面「都有文件」恒真恒通过 —— 那才是最彻底的假绿。
        if (applied.isEmpty()) {
            fail("🚨 假绿闸自身失效：flyway_schema_history 里一条成功记录都没有。"
                    + "要么连错了库（当前 URL=" + URL + "），要么判据 SQL 写坏了。"
                    + "此时「迁移都在本地」会恒真 —— 必须先修好本条，再谈其余结论。");
        }

        Set<String> local = localMigrationVersions();
        if (local.isEmpty()) {
            fail("🚨 假绿闸自身失效：在 " + new File(MIGRATION_DIR).getAbsolutePath()
                    + " 下一个迁移文件都没扫到（user.dir=" + System.getProperty("user.dir") + "）。"
                    + "此时「每个已应用迁移都缺文件」会全量误报 —— 先修路径。");
        }

        List<String> missing = applied.stream().filter(v -> !local.contains(v)).toList();
        if (!missing.isEmpty()) {
            fail("⛔ 执行前置未满足 · <b>Flyway 迁移漂移</b>（**不是被测功能的结论，也不是回归**）：\n"
                    + "   共享库已应用但本工作区没有文件的迁移：" + missing + "\n"
                    + "\n"
                    + "🚨 后果：Quarkus 启动期 Flyway validate 直接抛\n"
                    + "   `Detected applied migration not resolved locally: " + missing.get(0) + "`\n"
                    + "   ⇒ **应用起不来** ⇒ 本包所有 @QuarkusTest 以 `Errors: 1, Skipped: N-1` 的形态收场。\n"
                    + "   那批 skip 长得和「基本都过了」几乎一样，但一个业务断言都没执行过。\n"
                    + "\n"
                    + "✅ 处置：**让主线把 master 合进本分支**（迁移文件会随之进来）。\n"
                    + "🚫 不要 `flyway repair`（会把别人的迁移标成已删除）。\n"
                    + "🚫 不要把别人的迁移文件抄进本分支（checksum 与归属都会错）。\n"
                    + "   —— 依据 `db/migration-pending-260907/README.md` 写死的处置。\n"
                    + "\n"
                    + "📌 合并完成前跑出来的红**一律不作数**，🚫 不许据此下「实现坏了 / 有回归」的结论。");
        }
        System.out.println("[GATE-A1] ✅ Flyway 未漂移（库=" + URL.replaceAll(".*/([^?]+).*", "$1") + "）：已应用 " + applied.size()
                + " 个迁移，本工作区文件 " + local.size() + " 个，缺口 0");
    }

    /**
     * <b>A2 · {@code _record} 层已落库</b>（原版唯一的判据，保留，但降级为<b>三条之一</b>）。
     */
    @Test
    @DisplayName("GATE-A2 · _record 层已落库（每张带版本主表都有同名 _record）")
    void gateA2_recordLayerApplied() throws Exception {
        List<String> missing = new ArrayList<>();
        int versioned = 0;
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
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
        if (versioned == 0) {
            fail("🚨 假绿闸自身失效：查不到任何带 version_no 的 ds_quote_ 主表。"
                    + "要么连错了库（当前 URL=" + URL + "），要么判据 SQL 写坏了。"
                    + "此时「_record 都建好了」会恒真 —— 必须先修好本条，再谈其余结论。");
        }
        if (!missing.isEmpty()) {
            fail("⛔ 执行前置未满足 · <b>_record 表未建</b>（**不是被测功能的结论**）："
                    + missing.size() + " / " + versioned + " 张带版本主表尚无 _record —— " + missing + "\n"
                    + "🚨 后果：DatasetSchemaSelfCheck 报「表不存在」⇒ Quarkus 起不来 ⇒ 全包 skip。\n"
                    + "✅ 处置：把 db/migration-pending-260907/ 下的建表迁移**实取号**并应用"
                    + "（取号 = max(db/migration 目录最大号, 共享库 flyway_schema_history 最大号) + 1，"
                    + "两个都不能单独信），再重跑。");
        }
        System.out.println("[GATE-A2] ✅ _record 层已落库：" + versioned + " / " + versioned + " 张带版本主表齐备");
    }

    // ════════════════════ Phase B · 启动实证 ════════════════════

    /**
     * <b>B · 本轮确实有一次成功的 Quarkus 启动</b> —— 判据不是「表建好了」，是「<b>进程起来了</b>」。
     *
     * <p>做法：读<b>本轮</b>（报告文件 mtime ≥ 本 JVM 启动时刻）的
     * {@code target/surefire-reports/com.cpq.task260907r.*.txt}：
     * <ul>
     *   <li>出现 {@code Failed to start quarkus} ⇒ 把 <b>{@code Caused by:} 原文</b>抬出来，
     *       归成四类之一并给处置；</li>
     *   <li>出现 <b>skip 风暴</b>（某类 {@code Skipped} &gt; 0）⇒ 同样硬失败 ——
     *       🔑 「本包 @QuarkusTest 有 skip 而闸绿」这个组合<b>永远不该出现</b>；</li>
     *   <li>本轮还没有任何本包报告（本闸先于其余类执行）⇒ 打印说明，<b>不失败</b>：
     *       此时判据由 Phase A 承担。🚫 不拿<b>上一轮</b>的报告下结论 —— 那会用陈旧证据制造假红。</li>
     * </ul>
     */
    @Test
    @DisplayName("GATE-B · 本轮 @QuarkusTest 确实启动成功（读本轮 surefire 报告，非 @QuarkusTest）")
    void gateB_quarkusActuallyBooted() throws IOException {
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        File dir = new File(REPORT_DIR);
        List<File> current = new ArrayList<>();
        File[] all = dir.listFiles((d, n) -> n.startsWith(PKG) && n.endsWith(".txt"));
        if (all != null) {
            for (File f : all) if (f.lastModified() >= jvmStart) current.add(f);
        }
        if (current.isEmpty()) {
            System.out.println("[GATE-B] ⓘ 本轮尚无本包 surefire 报告（本闸先于其余类执行，属正常）"
                    + " ⇒ 本轮启动结论由 Phase A（GATE-A1/A2）承担。"
                    + "🚫 刻意不读上一轮的报告 —— 陈旧证据会制造假红。");
            return;
        }

        List<String> bootFailures = new ArrayList<>();
        List<String> skipStorms = new ArrayList<>();
        String firstCause = null;
        for (File f : current) {
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            if (text.contains("Failed to start quarkus")) {
                bootFailures.add(f.getName());
                if (firstCause == null) firstCause = extractRootCause(text);
            }
            // 🕰️ 2026-09-08 收窄：原来「只要有 skip 就算风暴」，被自己的 @Disabled 误伤。
            //    T-15② 按裁决显式禁用后，本闸当场把它读成「Quarkus 没起来」——
            //    🔑 **守卫误报的代价和漏报一样贵**：它会让每一轮基线都带一条假红，
            //       而假红看多了就没人看真红了。
            //    ⇒ 判据改成读 XML：@Disabled 的 skip 带**非空 message**（就是 reason），
            //       而 Quarkus 启动失败导致的 skip 没有 reason。二者由此可区分。
            int skipped = countSkipped(f);
            if (skipped > 0) {
                int explained = countExplainedSkips(f);
                if (explained < skipped) {
                    skipStorms.add(f.getName() + "(skipped=" + skipped
                            + "，其中仅 " + explained + " 条有 @Disabled reason)");
                }
            }
        }

        if (!bootFailures.isEmpty() || !skipStorms.isEmpty()) {
            fail("⛔ 执行前置未满足 · <b>本轮 Quarkus 没有成功启动</b>（**不是被测功能的结论**）：\n"
                    + "   启动失败的类：" + bootFailures + "\n"
                    + "   出现 skip 的类：" + skipStorms + "\n"
                    + "   🔑 「本包 @QuarkusTest 有 skip 而闸绿」这个组合永远不该出现 —— 那正是本闸要拦的形态。\n"
                    + "\n"
                    + "🔬 启动异常原文（本轮实取，不是推测）：\n     " + firstCause + "\n"
                    + "\n"
                    + classify(firstCause)
                    + "\n📌 判据：本条红着，就不许解读本包其余任何测试结果 —— 绿也不算数，skip 更不算数。");
        }
        System.out.println("[GATE-B] ✅ 本轮 " + current.size()
                + " 个本包报告：无「Failed to start quarkus」、无 skip ⇒ 启动实证通过");
    }

    // ─────────────────────────── 归类与处置 ───────────────────────────

    /** 该类报告里的 skip 数（{@code Tests run: … Skipped: N}）。 */
    private static int countSkipped(File txt) throws IOException {
        Matcher m = Pattern.compile("Skipped:\\s*(\\d+)")
                .matcher(Files.readString(txt.toPath(), StandardCharsets.UTF_8));
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return max;
    }

    /**
     * <b>有解释的</b> skip 数 —— 即 XML 里 {@code <skipped message="…"/>} 且 message 非空。
     *
     * <p>🔑 {@code @Disabled("reason")} 会把 reason 写进 message；
     * 而 Quarkus 启动失败导致的 skip <b>没有</b> reason。
     * ⇒ 「skip 数 > 有解释的 skip 数」才是真风暴。
     */
    private static int countExplainedSkips(File txt) throws IOException {
        String name = txt.getName().replaceFirst("\\.txt$", "");
        File xml = new File(txt.getParentFile(), "TEST-" + name + ".xml");
        if (!xml.isFile()) return 0;
        Matcher m = Pattern.compile("<skipped\\s+message=\"([^\"]{1,})\"")
                .matcher(Files.readString(xml.toPath(), StandardCharsets.UTF_8));
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** 把启动异常原文归成四类，各给<b>可直接执行</b>的处置 —— 🚫 不写「请检查配置」这种无动作文案。 */
    private static String classify(String cause) {
        String c = cause == null ? "" : cause;
        if (c.contains("not resolved locally") || c.contains("FlywayValidateException")) {
            return "🏷️ 归类 ② <b>Flyway 迁移漂移</b>（分支落后于共享库）。\n"
                    + "✅ 处置：让主线把 master 合进本分支。\n"
                    + "🚫 不要 `flyway repair`；🚫 不要把别人的迁移文件抄进本分支。\n"
                    + "🚫 合并完成前跑出的红一律不作数，不许据此下「实现坏了 / 有回归」的结论。";
        }
        if (c.contains("多出未声明的列") || c.contains("未声明")) {
            return "🏷️ 归类 ① <b>Registry↔DDL 不一致 · 库里多出 Registry 没声明的列</b>。\n"
                    + "   典型成因：上游 DDL 已落共享库（如 V425 的 customer_no），而本分支 Registry 还没声明它。\n"
                    + "✅ 处置：等主线合完 master + 后端把 Registry 对齐；这属实现工作，不是测试能修的。\n"
                    + "🚫 不许自行改 Registry 去凑绿。";
        }
        if (c.contains("缺列") || c.contains("Registry 与数据库 schema 不一致")) {
            return "🏷️ 归类 ① <b>Registry↔DDL 不一致 · Registry 声明了库里没有的列</b>。\n"
                    + "   典型成因：本段 B-3 的 `source_quotation_id` 迁移尚未落库。\n"
                    + "✅ 处置：把 db/migration-pending-260907/ 下的对应迁移取号并应用；\n"
                    + "   排障期可临时 `-Dcpq.dataset.record-check.enabled=false` 绕过\n"
                    + "   —— ⚠️ 但绕过之后 **AC-1⑤（启动期自检通过）就没被验证**，报告里必须写明「未验证」。";
        }
        if (c.contains("does not exist") || c.contains("不存在")) {
            return "🏷️ 归类 ③ <b>表不存在</b>。\n"
                    + "✅ 处置：见 GATE-A2 的取号与应用步骤。";
        }
        return "🏷️ 归类 ④ <b>其他启动失败</b>（不属已知三类）。\n"
                + "✅ 处置：把上面的异常原文原样报主线 —— 🚫 不要自行猜测归因，"
                + "本闸的职责是「让原因可见」，不是替人下结论。";
    }

    /** 取最后一个 {@code Caused by:} 及其后一行（根因通常在最内层）。 */
    private static String extractRootCause(String text) {
        String[] lines = text.split("\n");
        String cause = null;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("Caused by:")) {
                StringBuilder sb = new StringBuilder(lines[i].trim());
                for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                    String nxt = lines[j].trim();
                    if (nxt.startsWith("at ") || nxt.startsWith("...")) break;
                    sb.append("\n     ").append(nxt);
                }
                cause = sb.toString();
            }
        }
        return cause == null ? "<报告里没抓到 Caused by 行，请直接看 " + REPORT_DIR + ">" : cause;
    }

    /** 本工作区 {@code db/migration} 下的版本号集合（{@code V123__xxx.sql} → {@code 123}）。 */
    private static Set<String> localMigrationVersions() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Path dir = Path.of(MIGRATION_DIR);
        if (!Files.isDirectory(dir)) return out;
        Pattern p = Pattern.compile("^V(\\d+(?:\\.\\d+)*)__.*\\.sql$");
        try (var s = Files.list(dir)) {
            s.forEach(f -> {
                Matcher m = p.matcher(f.getFileName().toString());
                if (m.matches()) out.add(m.group(1));
            });
        }
        return out;
    }
}
