package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-01a / T-01b / T-01c —— AC-1（{@code _record} 表建成，且与主表同构）</b>
 *
 * <p>本类是整套里<b>唯一现在就能跑出结论</b>的：它只查 {@code information_schema} 与
 * {@code flyway_schema_history}，不依赖任何业务实现。B-1/B-3 建表迁移落库前，
 * 它<b>应该是红的</b> —— 这正是「用例不是空跑」的证明。
 *
 * <p>🚫 判据一律写<b>集合关系</b>，不锁表数（{@code 需求文档.md} §③ 开头：
 * 「判据一律写不变量，不锁行数」；实证 {@code task-260819} 在同一条 AC 上因锁数字栽过三次）。
 */
@QuarkusTest
@DisplayName("AC-1 · _record 表建成且与主表同构")
class RecordSchemaAcTest extends Task260907RBase {

    /**
     * <b>T-01a（AC-1①）</b>：「有 {@code version_no} 却没有 {@code _record}」的表必须<b>返 0 行</b>。
     *
     * <p>判据 SQL 逐字取自 {@code 需求文档.md} AC-1① 代码块，🚫 不改写、不加表名白名单 ——
     * 加白名单就等于把「以后新增的带版本表也要配 _record」这条约束偷偷去掉了。
     */
    @Test
    @DisplayName("T-01a · 带 version_no 却无 _record 的表 = 0 张")
    void t01a_everyVersionedTableHasRecord() {
        List<String> missing = tablesMissingRecord();

        // 🚨 阳性对照：判据 SQL 本身必须能选出东西来，否则「返 0 行」可能只是 SQL 写错了。
        //    先证明「带 version_no 的 ds_quote_ 主表」这个集合非空，那条判据才有意义。
        long versioned = count(
                "SELECT count(*) FROM information_schema.tables t "
                        + "WHERE t.table_schema='public' AND t.table_name LIKE 'ds\\_quote\\_%' "
                        + "  AND t.table_name NOT LIKE '%\\_history' AND t.table_name NOT LIKE '%\\_record' "
                        + "  AND EXISTS(SELECT 1 FROM information_schema.columns c "
                        + "             WHERE c.table_name=t.table_name AND c.column_name='version_no')");
        assertFixtureNonEmpty(versioned,
                "带 version_no 的 ds_quote_ 主表数（阳性对照：这个集合为空 ⇒ 判据 SQL 恒返 0 行，恒真恒通过）");

        assertTrue(missing.isEmpty(),
                "AC-1①：以下 " + missing.size() + " 张带版本主表没有对应的 _record 表 —— " + missing
                        + "（判据集合中带版本的主表共 " + versioned + " 张）");
    }

    /**
     * <b>T-01b（AC-1②）</b>：每张 {@code _record} 的列 ⊇ 主表全部业务列 + 5 个附加列，
     * 且<b>共有列的类型与可空性与主表一致</b>。
     *
     * <p>🔑 列集用<b>动态派生</b>（从 {@code information_schema} 读主表列），
     * 🚫 不硬编码列名清单 —— 上游 {@code 报价侧加客户维度} 随时会给主表加 {@code customer_no}，
     * 硬编码的清单那天就会变成假红（报「多了一列」而其实是上游正常演进）。
     *
     * <p>⚠️ {@code origin_id} 可空是 AC 原文明写的（「报价单里手工新增的行在主表没有对应行」），
     * 因此对它单独断言「可空」，不套用「与主表一致」那条。
     */
    @Test
    @DisplayName("T-01b · _record 列集 = 主表业务列 + 附加列，共有列类型/可空性一致")
    void t01b_recordColumnsMirrorMainTable() {
        requireRecordLayer();

        List<String> mains = versionedMainTables();
        assertFixtureNonEmpty(mains.size(), "带版本主表清单");

        List<String> problems = new java.util.ArrayList<>();
        for (String main : mains) {
            String rec = main + "_record";
            Map<String, String[]> mainCols = columnMeta(main);   // name -> {type, isNullable}
            Map<String, String[]> recCols = columnMeta(rec);

            // ① 主表的每一列都必须在 _record 里出现 —— 但**版本列除外**。
            //
            // 🕰️ 2026-09-07 修正（本条一开始写宽了，13 张表全红）：
            //   首版把「主表全部业务列」实现成了「主表所有列」，于是把 version_no / row_fingerprint
            //   也要求进来，13 张 _record 全被判缺列。复核 AC-1② 原文后确认**是我的判据太严**：
            //     AC-1② = 主表全部**业务列** + id + origin_id + quotation_id + base_version_no
            //              + extend_column + **系统列** —— 通篇没有要求版本列。
            //   而 _record 是「钉在某个基版上的快照」，它自己没有版本：
            //     version_no       → 由 base_version_no      承载（钉住拍快照时主表的版本）
            //     row_fingerprint  → 由 base_row_fingerprint 承载（A0-1 双锚的指纹兜底那一锚）
            //   ⇒ 属 testing.md §4.1.5「必现 ≠ 缺陷」：**改 AC 的实现口径，不改产品**。
            //   🚫 但不是简单放宽 —— 见下面 ①' ，把「被替换掉」这件事正面断言出来，比原来更严。
            Set<String> missingInRecord = new LinkedHashSet<>(mainCols.keySet());
            missingInRecord.removeAll(recCols.keySet());
            // 🚫 只放过「按设计不镜像」的系统列（版本列 + source_quotation_id），
            //    业务列缺失仍然必须红 —— 权威口径见 RECORD_NOT_MIRRORED 的注释。
            missingInRecord.removeAll(RECORD_NOT_MIRRORED);
            if (!missingInRecord.isEmpty()) {
                problems.add(rec + " 缺主表列 " + missingInRecord);
            }

            // ①' 版本列必须**被替换**，而不是「碰巧没有」。两侧都要断言，否则放宽就变成漏检：
            //     - _record 不该有主表的 version_no / row_fingerprint（它没有自己的版本）
            //     - 且必须有对应的 base_* 承载列，类型与主表被替换掉的那一列一致
            for (String v : VERSION_COLUMNS) {
                if (recCols.containsKey(v)) {
                    problems.add(rec + " 不该有主表版本列 " + v
                            + "（_record 是钉在 base_version_no 上的快照，没有自己的版本）");
                }
            }
            for (Map.Entry<String, String> e : VERSION_TO_BASE.entrySet()) {
                String mainMeta = mainCols.containsKey(e.getKey()) ? mainCols.get(e.getKey())[0] : null;
                String[] baseMeta = recCols.get(e.getValue());
                if (baseMeta == null) {
                    problems.add(rec + " 缺承载列 " + e.getValue() + "（用于替代主表的 " + e.getKey() + "）");
                } else if (mainMeta != null && !mainMeta.equals(baseMeta[0])) {
                    problems.add(rec + "." + e.getValue() + " 类型应与主表 " + e.getKey() + " 一致："
                            + "主表=" + mainMeta + " / _record=" + baseMeta[0]);
                }
            }

            // ② AC-1② 点名的附加列
            for (String extra : List.of("origin_id", "quotation_id", "base_version_no", "extend_column")) {
                if (!recCols.containsKey(extra)) problems.add(rec + " 缺附加列 " + extra);
            }
            if (!recCols.containsKey("id")) problems.add(rec + " 缺主键列 id");

            // ③ origin_id 必须可空（AC-1② 原文：报价单里手工新增的行在主表没有对应行）
            String[] originMeta = recCols.get("origin_id");
            if (originMeta != null && !"YES".equalsIgnoreCase(originMeta[1])) {
                problems.add(rec + ".origin_id 应可空（AC-1② 原文），实际 is_nullable=" + originMeta[1]);
            }

            // ④ 共有列逐列比类型与可空性
            for (Map.Entry<String, String[]> e : mainCols.entrySet()) {
                String[] r = recCols.get(e.getKey());
                if (r == null) continue;                       // 已在 ① 报过
                if ("id".equals(e.getKey())) continue;          // _record 有自己的主键序列
                if (RECORD_NOT_MIRRORED.contains(e.getKey())) continue;  // 版本列已在 ①' 单独断言
                if (!e.getValue()[0].equals(r[0])) {
                    problems.add(rec + "." + e.getKey() + " 类型不一致：主表=" + e.getValue()[0] + " / _record=" + r[0]);
                }
                if (!e.getValue()[1].equals(r[1])) {
                    problems.add(rec + "." + e.getKey() + " 可空性不一致：主表=" + e.getValue()[1] + " / _record=" + r[1]);
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "AC-1②：_record 与主表不同构，共 " + problems.size() + " 处 —— " + problems);
    }

    /**
     * <b>T-01b'（AC-1③）</b>：免版本三张表<b>没有</b> {@code _record}。
     *
     * <p>反向断言。🚨 配阳性对照：先证明「免版本表」这个集合非空，
     * 否则「都没有 _record」在集合为空时恒真（{@code test.md} §一风险点 1 的同型）。
     */
    @Test
    @DisplayName("T-01b' · 免版本三表没有 _record（反向）")
    void t01b_unversionedTablesHaveNoRecord() {
        @SuppressWarnings("unchecked")
        List<String> unversioned = (List<String>) (List<?>) col(
                "SELECT t.table_name FROM information_schema.tables t "
                        + "WHERE t.table_schema='public' AND t.table_name LIKE 'ds\\_quote\\_%' "
                        + "  AND t.table_name NOT LIKE '%\\_history' AND t.table_name NOT LIKE '%\\_record' "
                        + "  AND NOT EXISTS(SELECT 1 FROM information_schema.columns c "
                        + "                 WHERE c.table_name=t.table_name AND c.column_name='version_no') "
                        + "ORDER BY 1");
        assertFixtureNonEmpty(unversioned.size(),
                "免版本 ds_quote_ 主表数（阳性对照：集合为空 ⇒ 下面的『都没有 _record』恒真）");

        List<String> offenders = new java.util.ArrayList<>();
        for (String t : unversioned) {
            if (relationExists(t + "_record")) offenders.add(t + "_record");
        }
        assertTrue(offenders.isEmpty(),
                "AC-1③：免版本表不该有 _record（用户裁决 D-8：这三张是基础资料，不回填 ⇒ 不需要与主表对照），"
                        + "实际多出 " + offenders + "。当前免版本表清单=" + unversioned);
    }

    /**
     * <b>T-01c（AC-1④⑤）</b>：建表迁移 {@code success = true}；且<b>后端起得来</b>。
     *
     * <p>AC-1⑤ 原文：「启动期 {@code DatasetSchemaSelfCheck} 通过（后端能起来 = 通过，起不来即失败）」。
     * 本用例跑在 {@code @QuarkusTest} 里 —— <b>它能执行到这一行本身就是 AC-1⑤ 的证据</b>：
     * 自检不过则应用启动失败，整个类 error 而不是这行断言失败。
     * 但「能执行到」这件事必须被<b>显式打印出来</b>，否则读报告的人无从区分「跑过了」与「压根没跑」
     * （{@code test.md} 风险点 4：44 个用例全 skip 被读成没问题）。
     */
    @Test
    @DisplayName("T-01c · 迁移 success=t + 后端启动自检通过")
    void t01c_migrationSucceededAndAppBooted() {
        // ── AC-1⑤：能走到这里 = Quarkus 起来了。再补一枪 HTTP 阳性对照。
        assertProcessAlive();

        // 🚨 但「起来了」只有在自检**开着**的时候才等价于「自检通过」。
        //    2026-09-07：为绕开 B-3（source_quotation_id 的 26 处 DDL 未落）导致的启动失败，
        //    跑测试时加了 -Dcpq.dataset.record-check.enabled=false。
        //    ⇒ 此时应用能起来，只是因为**那项自检被关掉了**，不是因为它通过了。
        //    若这里照旧打印「AC-1⑤ 通过」，就是一句**由我自己制造的假绿** —— 读报告的人会以为验过了。
        boolean recordCheckOn = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("cpq.dataset.record-check.enabled", Boolean.class)
                .orElse(true);
        if (recordCheckOn) {
            System.out.println("[T-01c] ✅ AC-1⑤ 证据：自检**开着**且应用已启动、业务端点返 401 "
                    + "⇒ 启动期 DatasetSchemaSelfCheck 通过（它不通过应用会启动失败，本行根本执行不到）");
        } else {
            System.out.println("[T-01c] ⛔ AC-1⑤ **本轮未验证**（不是通过，也不是失败）："
                    + "cpq.dataset.record-check.enabled=false ⇒ 该项启动自检被关掉了，"
                    + "「应用起得来」此刻**不构成** AC-1⑤ 的证据。"
                    + "待 B-3（source_quotation_id 的 26 处 DDL）落库、自检重新打开后才能验。"
                    + "🚫 test-report.md 的 AC-1⑤ 行须写「⛔ 待 B-3 落库后验」，不许写「通过」。");
        }

        // ── AC-1④：迁移 success = true。
        // 🚫 不锁死具体版本号 —— 迁移号是移动靶（需求文档 §4.1 实测：同日 3 小时内变过两次，
        //    至少四条线在抢 V420）。判据写成「_record 建表迁移必须存在且 success」的集合关系。
        long failed = count("SELECT count(*) FROM flyway_schema_history WHERE success = false");
        assertEquals(0L, failed,
                "AC-1④：flyway_schema_history 里存在 success=false 的迁移 " + failed + " 条 ⇒ 迁移链已断，"
                        + "此时 schema 处于未定义状态，本类其余断言均不可信。"
                        + "失败明细=" + col("SELECT version || ' / ' || script FROM flyway_schema_history "
                        + "WHERE success = false ORDER BY installed_rank"));

        // 建表迁移确实跑过：_record 表存在 ⇒ 必有一条迁移建了它。两者互为佐证，缺一说明有人手工建表。
        List<String> missing = tablesMissingRecord();
        assertTrue(missing.isEmpty(),
                "AC-1④：_record 建表迁移尚未落库（缺 " + missing.size() + " 张：" + missing + "）。"
                        + "⚠️ 若此时 flyway 无失败记录，说明迁移文件还没写/还没被应用，属**环境前置**。");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 主表的<b>版本列</b> —— 与「业务列」「系统列」并列的第三类
     * （{@code 需求文档.md} §4.2 提到 {@code SYSTEM_COLUMNS} / {@code VERSION_COLUMNS} 是同类概念）。
     * {@code _record} <b>不镜像</b>它们：快照没有自己的版本，只有「基于哪一版」。
     */
    private static final Set<String> VERSION_COLUMNS =
            new LinkedHashSet<>(List.of("version_no", "row_fingerprint"));

    /**
     * {@code _record} <b>刻意不镜像</b>的主表列 = 版本列 + {@code source_quotation_id}。
     *
     * <h3>🕰️ 2026-09-08 补进 source_quotation_id（V431 落库后本条 13 张全红）</h3>
     * 权威出处是 {@link SheetDef#expectedRecordColumns} 的 Javadoc 原文：
     * <blockquote>🚫 <b>没有</b> {@code version_no} / {@code row_fingerprint} /
     * {@code SOURCE_QUOTATION_COLUMN}：前两者属主表的版本化语义（AC-9 要求一律由
     * {@code VersionedGroupWriter} 负责），<b>后者是「主表这一版由哪张单写的」——
     * 而 {@code _record} 自己就有 {@code quotation_id}</b>。</blockquote>
     *
     * <p>🔑 <b>本条红过是因为对照面取错了</b>：它拿 {@code information_schema} 里主表的
     * <b>物理列</b>当期望集，而 V431 给物理主表加了一列 ⇒ 差集凭空多一项。
     * 物理 schema 不是「_record 该有哪些列」的权威，{@link SheetDef} 才是。
     * ⚠️ 这与上面 {@code VERSION_COLUMNS} 是同一个坑的第二次发作 ——
     * <b>每新增一个「主表有、_record 按设计没有」的系统列，这里都要同步。</b>
     *
     * <p>🚫 排除口径只放过<b>系统列</b>：任何<b>业务列</b>在 {@code _record} 里缺失
     * 仍然必须红（见 t01b 的证伪实验）。
     */
    private static final Set<String> RECORD_NOT_MIRRORED =
            new LinkedHashSet<>(List.of("version_no", "row_fingerprint",
                    com.cpq.dataset.registry.SheetDef.SOURCE_QUOTATION_COLUMN));

    /** 版本列在 {@code _record} 里的承载列（AC-1② 点名 {@code base_version_no}；指纹侧为 {@code base_row_fingerprint}）。 */
    private static final Map<String, String> VERSION_TO_BASE = Map.of(
            "version_no", "base_version_no",
            "row_fingerprint", "base_row_fingerprint");

    @SuppressWarnings("unchecked")
    private List<String> versionedMainTables() {
        return (List<String>) (List<?>) col(
                "SELECT t.table_name FROM information_schema.tables t "
                        + "WHERE t.table_schema='public' AND t.table_name LIKE 'ds\\_quote\\_%' "
                        + "  AND t.table_name NOT LIKE '%\\_history' AND t.table_name NOT LIKE '%\\_record' "
                        + "  AND EXISTS(SELECT 1 FROM information_schema.columns c "
                        + "             WHERE c.table_name=t.table_name AND c.column_name='version_no') "
                        + "ORDER BY 1");
    }

    /** {@code column_name -> {data_type(含长度/精度), is_nullable}}。 */
    private Map<String, String[]> columnMeta(String table) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (Object[] r : rows(
                "SELECT column_name, "
                        + "  data_type || coalesce('(' || character_maximum_length || ')','') "
                        + "            || coalesce('(' || numeric_precision || ',' || numeric_scale || ')','') AS t, "
                        + "  is_nullable "
                        + "FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                        + sqlSafe(table) + "' ORDER BY ordinal_position")) {
            out.put((String) r[0], new String[]{String.valueOf(r[1]), (String) r[2]});
        }
        assertFalse(out.isEmpty(), "表 " + table + " 查不到任何列 ⇒ 表不存在，断言会空跑");
        return out;
    }
}
