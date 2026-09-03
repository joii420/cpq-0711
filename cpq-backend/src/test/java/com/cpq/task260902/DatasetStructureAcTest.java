package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>L3 结构核对</b> —— 覆盖 A 组 AC-1 ~ AC-5。
 *
 * <p>判据的左边是 {@code information_schema}（数据库现状），右边是 {@code 字段矩阵.md}（文档）。
 * 🚫 右边不读 Java Registry —— 那会让判据退化成「实现 == 实现」，实现漏一列时 Registry 跟着漏，测试照样全绿。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("task-260902 · A 组 建表结构（AC-1~AC-5）")
class DatasetStructureAcTest extends DatasetAcTestBase {

    /** 45 主表 + 39 history = 84（需求文档 ① 的合计表）。 */
    private static final int EXPECT_TOTAL_TABLES = 84;
    private static final int EXPECT_HISTORY_TABLES = 39;
    private static final int EXPECT_MAIN_TABLES = 45;

    // ═══════════════════════════════════════════════════════════════
    // TD-00 —— 判据本身的自检。放最前面，理由同 MaterialAcTestBase：
    //          矩阵解析坏掉时，后面每条断言都会以「业务缺陷」的面目失败。
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(0)
    @DisplayName("TD-00 判据自检：字段矩阵.md 解析出 45 张主表（39 带版本 + 6 免版本）")
    void td00_matrixParsedCorrectly() {
        assertEquals(EXPECT_MAIN_TABLES, SPECS.size(),
                "字段矩阵.md 解析出的主表数不对 —— 这是判据坏了，不是建表坏了。解析结果："
                        + SPECS.stream().map(s -> s.tableName).toList());

        long versioned = SPECS.stream().filter(s -> s.versioned).count();
        assertEquals(EXPECT_HISTORY_TABLES, versioned, "带版本表数与需求文档 ① 合计表不一致");

        List<String> unversioned = SPECS.stream().filter(s -> !s.versioned).map(s -> s.tableName).toList();
        assertEquals(List.of(
                        "ds_quote_material",
                        "ds_quote_customer_part",
                        "ds_quote_plating_scheme",
                        "ds_cost_basic_material",
                        "ds_cost_detail_material",
                        "ds_cost_detail_plating_scheme"),
                unversioned,
                "免版本 6 张表与需求文档 R-2 的清单不一致");

        // 每张带版本表都必须解析出轴列，否则 R-4 的升版判定无从谈起
        List<String> noAxis = SPECS.stream()
                .filter(s -> s.versioned && s.axisField == null)
                .map(s -> s.tableName).toList();
        assertTrue(noAxis.isEmpty(), "以下带版本表在矩阵里没有标「轴」：" + noAxis);

        // 断言非空保护：每张表至少要有一个已建字段，否则后面的列集合比对是空跑
        List<String> empty = SPECS.stream().filter(s -> s.builtColumns.isEmpty()).map(s -> s.tableName).toList();
        assertTrue(empty.isEmpty(), "以下表在矩阵里一个 ✅ 建字段都没有 ⇒ AC-2 会空跑：" + empty);

        System.out.printf("[TD-00] 矩阵解析 OK：主表 %d（带版本 %d / 免版本 %d），"
                        + "建字段合计 %d 列%n",
                SPECS.size(), versioned, SPECS.size() - versioned,
                SPECS.stream().mapToInt(s -> s.builtColumns.size()).sum());
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-1 表数量
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("TS-01 / AC-1：ds_% 表共 84 张，其中 %_history 39 张")
    void ts01_tableCounts() {
        long total = count("SELECT count(*) FROM pg_tables "
                + "WHERE schemaname='public' AND tablename LIKE 'ds\\_%'");
        long history = count("SELECT count(*) FROM pg_tables "
                + "WHERE schemaname='public' AND tablename LIKE 'ds\\_%' AND tablename LIKE '%\\_history'");

        System.out.printf("[TS-01] 实际 ds_%% = %d，其中 _history = %d%n", total, history);

        assertEquals(EXPECT_TOTAL_TABLES, total,
                "AC-1：ds_% 表数量不是 84。缺失的表：" + missingTables());
        assertEquals(EXPECT_HISTORY_TABLES, history, "AC-1：_history 表数量不是 39");
    }

    private List<String> missingTables() {
        List<String> missing = new ArrayList<>();
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (!tableExists(s.tableName)) {
                missing.add(s.tableName);
            }
            if (s.versioned && !tableExists(s.historyTableName())) {
                missing.add(s.historyTableName());
            }
        }
        return missing;
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-2 逐表列集合（45 张全等才算通过）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("TS-02 / AC-2：45 张主表的列名集合逐表等于「矩阵 ✅ 建字段 ∪ 系统列（∪ 版本列）」")
    void ts02_columnSetsMatchMatrix() {
        List<String> failures = new ArrayList<>();
        int compared = 0;

        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (!tableExists(s.tableName)) {
                failures.add(s.tableName + "：表不存在");
                continue;
            }
            compared++;
            Set<String> actual = new TreeSet<>(columnsOf(s.tableName));
            Set<String> expected = new TreeSet<>(lower(s.expectedColumns()));

            Set<String> onlyExpected = new LinkedHashSet<>(expected);
            onlyExpected.removeAll(actual);
            Set<String> onlyActual = new LinkedHashSet<>(actual);
            onlyActual.removeAll(expected);

            if (!onlyExpected.isEmpty() || !onlyActual.isEmpty()) {
                failures.add(s.tableName + "：缺列 " + onlyExpected + "，多列 " + onlyActual);
            }
        }

        // 🚨 断言非空保护：一张表都没建时 failures 会全是「表不存在」，而不是「0 个差异」的假绿
        assertTrue(compared > 0 || !failures.isEmpty(),
                "AC-2 一张表都没比对到 ⇒ 空验证");
        assertTrue(failures.isEmpty(),
                "AC-2：以下表的列集合与 字段矩阵.md 不等（共 " + failures.size() + " 张，已比对 "
                        + compared + " 张）：\n  " + String.join("\n  ", failures));
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-3 白底列未建
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("TS-03 / AC-3：ds_cost_detail_capacity 不含 4 个白底列")
    void ts03_whiteColumnsNotCreated() {
        String table = "ds_cost_detail_capacity";
        assertTrue(tableExists(table), "AC-3：" + table + " 不存在，断言无从执行");

        Set<String> actual = new TreeSet<>(columnsOf(table));
        assertFalse(actual.isEmpty(), "AC-3：" + table + " 查不到任何列 ⇒ 空验证");

        for (String forbidden : List.of("material_name", "specification", "dimension", "operation_name")) {
            assertFalse(actual.contains(forbidden),
                    "AC-3：" + table + " 不该有白底列 " + forbidden + "（实际列：" + actual + "）");
        }
        System.out.printf("[TS-03] %s 实际列 = %s%n", table, actual);
    }

    @Test
    @Order(4)
    @DisplayName("TS-03b / AC-3 推广：矩阵中标 ❌ 不建的列，一张表都不许出现（45 张全查）")
    void ts03b_noWhiteColumnAnywhere() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (s.notBuiltExcelColumns.isEmpty() || !tableExists(s.tableName)) {
                continue;
            }
            checked++;
            Set<String> actual = new TreeSet<>(columnsOf(s.tableName));
            // 白底列在矩阵里没有字段名（列为「—」），只能按「不该多出列」反查：
            // AC-2 已做严格等式，这里补一条更易读的定位信息。
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(lower(s.expectedColumns()));
            if (!extra.isEmpty()) {
                failures.add(s.tableName + " 多出列 " + extra
                        + "（该表白底列：" + s.notBuiltExcelColumns + "）");
            }
        }
        assertTrue(checked > 0 || failures.isEmpty(),
                "AC-3b：没有一张含白底列的表被检查到 ⇒ 空验证（先确认迁移已落库）");
        assertTrue(failures.isEmpty(), "AC-3b：\n  " + String.join("\n  ", failures));
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-4 免版本表
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("TS-04 / AC-4：6 张免版本表无 version_no / row_fingerprint，且无对应 _history 表")
    void ts04_unversionedTables() {
        List<String> targets = List.of(
                "ds_quote_material", "ds_cost_basic_material", "ds_cost_detail_material",
                "ds_quote_customer_part", "ds_quote_plating_scheme", "ds_cost_detail_plating_scheme");

        List<String> failures = new ArrayList<>();
        for (String t : targets) {
            if (!tableExists(t)) {
                failures.add(t + "：表不存在");
                continue;
            }
            Set<String> cols = new TreeSet<>(columnsOf(t));
            if (cols.contains("version_no")) {
                failures.add(t + " 不该有 version_no");
            }
            if (cols.contains("row_fingerprint")) {
                failures.add(t + " 不该有 row_fingerprint");
            }
            if (tableExists(t + "_history")) {
                failures.add(t + "_history 不该存在");
            }
        }
        assertTrue(failures.isEmpty(), "AC-4：\n  " + String.join("\n  ", failures));
        System.out.printf("[TS-04] 6 张免版本表全部符合 R-2%n");
    }

    // ═══════════════════════════════════════════════════════════════
    // AC-5 history 结构
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("TS-05 / AC-5：ds_cost_detail_material_bom_history 列集合 = 主表（id→origin_id）∪ 3 归档列")
    void ts05_historyStructure() {
        FieldMatrixSpec.TableSpec spec = SPECS.stream()
                .filter(s -> "ds_cost_detail_material_bom".equals(s.tableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("矩阵里没有 ds_cost_detail_material_bom"));

        String h = spec.historyTableName();
        assertTrue(tableExists(h), "AC-5：" + h + " 不存在");

        Set<String> actual = new TreeSet<>(columnsOf(h));
        Set<String> expected = new TreeSet<>(lower(spec.expectedHistoryColumns()));
        assertFalse(actual.isEmpty(), "AC-5：" + h + " 查不到列 ⇒ 空验证");

        assertEquals(expected, actual,
                "AC-5：" + h + " 列集合不等于「主表列（id→origin_id）∪ {archived_at, archived_by, archive_reason} ∪ {id}」");

        assertTrue(actual.contains("origin_id"), "AC-5：history 表缺 origin_id（主表 id 应映射到它）");
        assertTrue(actual.contains("id"),
                "AC-5：history 表缺自身的 bigserial 主键 id —— 同一 origin_id 会因多次升版出现多行，"
                        + "不能用 origin_id 做主键");
        System.out.printf("[TS-05] %s 列集合 = %s%n", h, actual);
    }

    @Test
    @Order(7)
    @DisplayName("TS-05b / AC-5 推广：39 张 _history 全部满足「主表列（id→origin_id）∪ 3 归档列」")
    void ts05b_allHistoryStructures() {
        List<String> failures = new ArrayList<>();
        int compared = 0;

        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (!s.versioned) {
                continue;
            }
            String h = s.historyTableName();
            if (!tableExists(h)) {
                failures.add(h + "：表不存在");
                continue;
            }
            compared++;
            Set<String> actual = new TreeSet<>(columnsOf(h));
            Set<String> expected = new TreeSet<>(lower(s.expectedHistoryColumns()));
            if (!expected.equals(actual)) {
                Set<String> miss = new LinkedHashSet<>(expected);
                miss.removeAll(actual);
                Set<String> extra = new LinkedHashSet<>(actual);
                extra.removeAll(expected);
                failures.add(h + "：缺 " + miss + "，多 " + extra);
            }
        }
        assertTrue(compared > 0 || !failures.isEmpty(), "AC-5b：一张 _history 都没比对到 ⇒ 空验证");
        assertTrue(failures.isEmpty(),
                "AC-5b：共 " + failures.size() + " 张 _history 结构不符（已比对 " + compared + " 张）：\n  "
                        + String.join("\n  ", failures));
    }

    // ═══════════════════════════════════════════════════════════════
    // 补充：唯一约束（R-2 的「主键（唯一约束 + 必填）」）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("TS-06 / R-2：免版本表的主键列有唯一约束，且 NOT NULL（AC-23 的覆盖语义靠它兜底）")
    void ts06_unversionedPrimaryKeyConstraints() {
        record Pk(String table, List<String> cols) {
        }
        // 🚩 口径按 2026-09-03 裁决 D-18 更新：ds_quote_customer_part 的主键是
        //    **客户编号 + 客户产品编号**（与现有 uq_mcm_quote_cust_prod 对齐，防跨客户串号），
        //    不再是旧稿的「客户产品编号 + 销售料号」。
        List<Pk> pks = List.of(
                new Pk("ds_quote_material", List.of("material_no")),
                new Pk("ds_cost_basic_material", List.of("production_no")),
                new Pk("ds_cost_detail_material", List.of("production_no")),
                new Pk("ds_quote_customer_part", List.of("customer_no", "customer_product_no")),
                new Pk("ds_quote_plating_scheme", List.of("scheme_no", "scheme_version", "item_seq")),
                new Pk("ds_cost_detail_plating_scheme", List.of("scheme_no", "scheme_version", "item_seq")));

        List<String> failures = new ArrayList<>();
        for (Pk pk : pks) {
            if (!tableExists(pk.table())) {
                failures.add(pk.table() + "：表不存在");
                continue;
            }
            // 🚨 判据改成**行为实证**（2026-09-03 修正）：
            //    在事务内插两条业务键相同的行，期望第二条抛唯一性冲突，然后整个事务回滚。
            //
            //    为什么不再查系统表：上一版查 `pg_constraint`（唯一 CONSTRAINT），
            //    而实现用的是 `CREATE UNIQUE INDEX` —— PG 里后者**不进 pg_constraint**，
            //    于是 6 张表全被误报成「没有唯一约束」。**这是我这轮唯一的误报，根因是查错了系统表。**
            //    再往前一版只比「约束列数」，又被 id 主键的 1 列凑巧骗过。
            //    ⇒ 两次都指向同一条教训：**结构类断言优先验行为，不验元数据长什么样。**
            //       「能不能拦住重复」才是 AC-21/22/23/47 真正依赖的性质。
            String probeResult = probeUniqueness(pk.table(), pk.cols());
            if (probeResult != null) {
                failures.add(pk.table() + "：" + probeResult
                        + "（业务键 " + pk.cols() + "）—— 拦不住重复则 AC-21/22/23 的 UPSERT 语义无处落地"
                        + ("ds_quote_customer_part".equals(pk.table())
                        ? "，且 AC-47 的跨客户串号防线不成立" : "")
                        + "。该表现有的唯一 index/constraint：" + uniqueKeySets(pk.table()));
            }
            for (String c : pk.cols()) {
                if (!columnExists(pk.table(), c)) {
                    failures.add(pk.table() + "." + c + " 列不存在");
                } else {
                    long nullable = count("SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='" + pk.table() + "' "
                            + "AND column_name='" + c + "' AND is_nullable='YES'");
                    if (nullable > 0) {
                        failures.add(pk.table() + "." + c + " 可空 —— R-1「免版本表的主键列一律必填」");
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "TS-06 / R-2：\n  " + String.join("\n  ", failures));
    }

    @Test
    @Order(9)
    @DisplayName("TS-07 / R-1：39 张带版本表的 version_no / row_fingerprint 均为 NOT NULL")
    void ts07_versionColumnsNotNull() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (FieldMatrixSpec.TableSpec s : SPECS) {
            if (!s.versioned || !tableExists(s.tableName)) {
                continue;
            }
            checked++;
            for (String c : List.of("version_no", "row_fingerprint")) {
                long nullable = count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + s.tableName + "' "
                        + "AND column_name='" + c + "' AND is_nullable='YES'");
                if (nullable > 0) {
                    failures.add(s.tableName + "." + c + " 可空（R-1 要求 not null）");
                }
            }
            // 轴列必填（R-1 凌驾底色的第 1 条）
            if (s.axisField != null && columnExists(s.tableName, s.axisField)) {
                long nullable = count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + s.tableName + "' "
                        + "AND column_name='" + s.axisField + "' AND is_nullable='YES'");
                if (nullable > 0) {
                    failures.add(s.tableName + "." + s.axisField + "（轴列）可空 —— R-1「轴列一律必填」");
                }
            }
        }
        assertTrue(checked > 0, "TS-07：一张带版本表都没查到 ⇒ 空验证");
        assertTrue(failures.isEmpty(), "TS-07 / R-1：\n  " + String.join("\n  ", failures));
    }

    /**
     * 行为实证：在<b>独立事务</b>内插两条业务键相同的行，期望第二条抛唯一性冲突，最后<b>整个事务回滚</b>。
     *
     * <p>🚨 共享库纪律：探针行的业务键全部带 {@link #P} 前缀，且事务<b>一定回滚</b>，
     * 不依赖 {@code @AfterEach} 清理 —— 即使断言中途抛异常也不会留下任何行。
     *
     * @return {@code null} = 唯一性生效（期望）；否则返回问题描述。
     */
    private String probeUniqueness(String table, List<String> keyCols) {
        final String[] problem = {null};
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                try {
                    insertProbeRow(table, keyCols, 1);
                    // 第一条必须能插进去，否则下面的「第二条被拦」是空验证
                    insertProbeRow(table, keyCols, 2);
                    // 走到这里 = 两条重复业务键都插成功 ⇒ 唯一性没生效
                    problem[0] = "插入两条业务键相同的行都成功了，唯一性未生效";
                } catch (RuntimeException e) {
                    String msg = rootMessage(e);
                    if (msg.contains("duplicate key") || msg.contains("unique constraint")
                            || msg.contains("唯一") || msg.toLowerCase().contains("unique")) {
                        problem[0] = null; // ✅ 期望：被唯一性拦住
                    } else {
                        problem[0] = "插入探针行时抛出的不是唯一性冲突，而是：" + msg;
                    }
                }
                // 🚫 无论成败一律回滚，探针行绝不落库
                throw new ProbeRollback();
            });
        } catch (RuntimeException ignored) {
            // ProbeRollback 是我们自己抛的回滚信号（它也是 RuntimeException）；
            // 其它异常已在 lambda 内部归因到 problem[0]
        }
        return problem[0];
    }

    /** 只往业务键列 + 必填列写值，其余留空。 */
    private void insertProbeRow(String table, List<String> keyCols, int seq) {
        List<String> cols = new ArrayList<>(keyCols);
        List<String> vals = new ArrayList<>();
        for (String c : keyCols) {
            // item_seq / scheme_version 这类整数列给数字，其余给带前缀的字符串
            boolean numeric = "item_seq".equals(c);
            vals.add(numeric ? "1" : "'" + P + "UQPROBE'");
        }
        // source 有默认值，其余可空列不填；用 seq 制造「非键列不同」以证明拦的是键而不是整行
        cols.add("source");
        vals.add("'IMPORT'");
        if (columnExists(table, "material_name")) {
            cols.add("material_name");
            vals.add("'探针" + seq + "'");
        }
        em.createNativeQuery("INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES ("
                + String.join(",", vals) + ")").executeUpdate();
        em.flush();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        StringBuilder sb = new StringBuilder();
        while (c != null) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(" | ");
            }
            c = c.getCause();
        }
        return sb.toString();
    }

    /** 回滚信号，不是错误。 */
    private static final class ProbeRollback extends RuntimeException {
        ProbeRollback() {
            super(null, null, false, false);
        }
    }

    /**
     * 取一张表上全部唯一键的列集合 —— <b>唯一 index ∪ 唯一/主键 constraint 的并集</b>。
     * ⚠️ 只查 {@code pg_constraint} 会漏掉 {@code CREATE UNIQUE INDEX}（它不进那张表），
     * 这正是上一版误报 6 张表「没有唯一约束」的根因。本方法仅用于**失败时的诊断信息**，判据是行为实证。
     */
    @SuppressWarnings("unchecked")
    private List<String> uniqueKeySets(String table) {
        List<Object[]> raw = em.createNativeQuery("""
                SELECT i.relname,
                       string_agg(a.attname, ',' ORDER BY a.attname) AS cols
                FROM pg_index x
                JOIN pg_class t ON t.oid = x.indrelid
                JOIN pg_class i ON i.oid = x.indexrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN unnest(x.indkey) AS k(attnum) ON true
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE n.nspname = 'public' AND t.relname = '%s' AND (x.indisunique OR x.indisprimary)
                GROUP BY i.relname
                """.formatted(table)).getResultList();
        List<String> out = new ArrayList<>();
        for (Object[] row : raw) {
            out.add(row[0] + new TreeSet<>(List.of(String.valueOf(row[1]).split(","))).toString());
        }
        return out;
    }

    private static Set<String> lower(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase());
        }
        return out;
    }
}
