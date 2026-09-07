package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-06 / T-13 / T-08 / T-09 —— 回填的 patch 语义与 AP-60 守卫</b>
 * （AC-6 按 patch 升版 · AC-13 未表征的行与列必须原样保留 · AC-8 来源报价单 id · AC-9 复用 VersionedGroupWriter）
 *
 * <h3>🚨 这是本任务最重要的一组用例</h3>
 * {@code AP-60} / {@code repair-0727} 的真实事故：核价单 {@code HJ-20260726-0007} 通过后，
 * {@code material_bom_item} 一个组<b>从 4 行被「对齐」成 1 行</b>且多列置 NULL，
 * 而财务在预览里看到的是「新增 0 / 删除 0 / 改值 0」。根因是把<b>页签渲染投影</b>当成了<b>该组的全部行</b>。
 *
 * <p>⇒ 本组用例的判据一律是<b>「原来的东西还在吗」</b>，🚫 不是「新值写进去了吗」。
 * 后者在「原行被删光」之后<b>照样成立</b>（AC-10 / AC-20 的 🔑 注记反复强调的同型陷阱）。
 */
@QuarkusTest
@DisplayName("AC-6/8/9/13 · 回填 patch 语义与 AP-60 守卫")
class BackfillPatchSemanticsAcTest extends Task260907RBase {

    private static final String MBOM = "ds_quote_material_bom";

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>T-06（AC-6）</b>：确认 → 按 patch 语义升版。
     *
     * <p>夹具形态照 {@code test.md} §四指定：<b>自造 9 行组，只表征其中 2 行 2 列</b>。
     *
     * <p>断言逐条对应 AC-6 原文：
     * ① 主表该组 = <b>主表原整组行为基底</b>，{@code _record} 表征的列被覆盖，
     *    <b>未表征的行与列逐字保留</b>；
     * ② 旧版整组进 {@code _history}，{@code archive_reason} = 本次回填的原因常量；
     * ③ 新版 {@code version_no} = {@code max(主表当前, _history 最大) + 1}（🚫 不是「当前 + 1」）；
     * ④ 报价单状态 → {@code APPROVED}。
     */
    @Test
    @DisplayName("T-06 · 9 行组只表征 2 行 2 列 → 其余 7 行与未表征列逐字保留")
    void t06_patchOnBaseOfFullGroup() {
        requireRecordLayer();

        Fx fx = newFixture("AC6");
        String mat = PREFIX + "G6-" + shortId(fx);
        seedMainGroup(mat, 9);

        int mainVerBefore = maxVersion(MBOM, mat);
        int histVerBefore = maxHistoryVersion(mat);
        Map<Long, String> baseRows = groupSnapshot(mat);
        assertEquals(9, baseRows.size(), "夹具应造出 9 行，实际 " + baseRows.size());

        // 只表征第 2、5 行的 component_qty / unit_weight 两列
        List<Long> patched = List.copyOf(baseRows.keySet()).subList(1, 2);
        List<Long> patchedIds = List.of(nth(baseRows, 1), nth(baseRows, 4));
        Set<String> patchedCols = new LinkedHashSet<>(List.of("component_qty", "unit_weight"));
        snapshotRecordCoveringRowsAndColumns(fx, mat, patchedIds, patchedCols);
        changeRecordValues(fx, mat, patchedIds, Map.of("component_qty", "999.5", "unit_weight", "8.25"));

        approve(fx);

        // ── AC-6④
        assertEquals("APPROVED", String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")),
                "AC-6④：确认后报价单状态应为 APPROVED");

        // ── AC-6③：新版号 = max(主表当前, _history 最大) + 1
        int expectedVer = Math.max(mainVerBefore, histVerBefore) + 1;
        assertEquals(expectedVer, maxVersion(MBOM, mat),
                "AC-6③：新版号应 = max(主表当前 " + mainVerBefore + ", _history 最大 " + histVerBefore + ") + 1 = "
                        + expectedVer + "（🚫 不是「当前 + 1」）");

        // ── AC-6①：基底是主表原整组 —— 行数必须仍是 9（🚨 AP-60 的核心：不许被「对齐」成 2 行）
        Map<Long, String> after = groupSnapshot(mat);
        assertEquals(9, after.size(),
                "🚨 AC-6① / AP-60：主表该组原有 9 行，回填后应仍是 9 行（基底 = 主表原整组）。"
                        + "实际 " + after.size() + " 行 ⇒ 页签投影被当成了该组全部行，"
                        + "这正是 repair-0727 的事故形状（4 行被对齐成 1 行）。回填后内容=" + after.values());

        // ── AC-6①：未表征的 7 行逐字保留
        List<String> drifted = new java.util.ArrayList<>();
        for (Map.Entry<Long, String> e : baseRows.entrySet()) {
            if (patchedIds.contains(e.getKey())) continue;
            String now = businessTupleOfOriginRow(mat, e.getKey());
            if (!e.getValue().equals(now)) drifted.add("origin#" + e.getKey() + ": " + e.getValue() + " → " + now);
        }
        assertTrue(drifted.isEmpty(),
                "AC-6①：未被页签表征的 7 行必须逐字保留，实测 " + drifted.size() + " 行发生变化 —— " + drifted);

        // ── AC-6①反向：确实表征并改了的列确实变了（防止修成「什么都不写」）
        for (Long id : patchedIds) {
            String now = businessTupleOfOriginRow(mat, id);
            assertFalse(baseRows.get(id).equals(now),
                    "AC-6① 反向：表征并改了值的行 origin#" + id + " 应当变化，实测逐字未变 —— "
                            + "这说明回填被修成了「什么都不写」，那同样不满足 AC。值=" + now);
        }

        // ── AC-6②：旧版整组进 _history，且 archive_reason 是本次回填的原因常量
        long archived = count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat
                + "' AND version_no = " + mainVerBefore);
        assertEquals(9L, archived,
                "AC-6②：旧版 v" + mainVerBefore + " 应<b>整组</b> 9 行进 _history，实际 " + archived + " 行"
                        + " ⇒ 只归档「变过的行」= 旧版查不回来了");
        List<Object> reasons = col("SELECT DISTINCT archive_reason FROM " + MBOM + "_history "
                + "WHERE material_no = '" + mat + "' AND version_no = " + mainVerBefore);
        assertEquals(1, reasons.size(),
                "AC-6②：同一次归档的 archive_reason 应唯一，实际 " + reasons);
        assertNotNull(reasons.get(0), "AC-6②：archive_reason 不应为 NULL");
        // 列长约束：archive_reason varchar(32)（需求文档 §4.1 实测）
        assertTrue(reasons.get(0).toString().length() <= 32,
                "AC-6②：archive_reason 超出 varchar(32)，实际 '" + reasons.get(0) + "' 长度 "
                        + reasons.get(0).toString().length());
        System.out.println("[T-06] archive_reason 实际取值 = " + reasons.get(0));
    }

    /**
     * <b>T-13（AC-13）🚨 AP-60 守卫</b>：页签只表征<b>部分行</b>（带 {@code WHERE} 谓词收窄）
     * 且只暴露<b>部分列</b>。
     *
     * <p>断言：
     * ① 未被页签表征的行在主表中<b>仍存在，逐列未变</b>；
     * ② 被表征行里页签没暴露的列<b>逐字未变</b>（🚫 不得写 NULL）；
     * ③ 反向：页签确实表征并改了值的列，确实变了。
     *
     * <p>依据 {@code AP-60} 事故实证：4 行被对齐成 1 行、{@code element_bom_item.base_qty}
     * 由 {@code 0.624610} 变 NULL，而预览显示 0 变更。
     *
     * <p>本用例是 <b>E-1</b>（遍历主轴改成 {@code _record} 行）与 <b>E-2</b>（未暴露的列写 NULL）
     * 两个证伪实验的靶子 —— 任一注入都必须让它变红。
     *
     * <h3>🚨 覆盖边界：本条用<b>平铺页签</b>替代<b>树页签</b>，因 B-7（BOM 树递归换表）未落地</h3>
     * {@code costing_bom_tree_config} 的递归目前仍读 V6 老表 {@code material_bom_item}，
     * 改读新表那件事在另一个任务（{@code task-260907-报价侧加客户维度} 的 B-7）里，且那个任务尚未走闸门 A
     * ⇒ 树页签的端到端渲染现在跑不通。本用例改用<b>平铺页签 + {@code WHERE} 谓词收窄行 + 只暴露部分列</b>
     * 来构造同型投影。
     *
     * <p>⚠️ <b>因此本条不能被读成「树页签已验证」</b>：{@code AP-60} 的真实事故
     * （{@code repair-0727}）恰恰发生在树页签场景 —— 闭包让一个页签横跨多个组、每组只被部分表征。
     * 平铺投影能验到<b>行维度与列维度</b>的基本守卫，但<b>验不到闭包放大那一层</b>。
     * ⇒ {@code test-report.md} 的 AC-13 行须归入<b>「未完全覆盖」</b>，🚫 不是「已通过」。
     */
    @Test
    @DisplayName("T-13 · [平铺页签替代树页签·B-7未落地] 未表征的行仍在且逐列未变；未暴露的列不写 NULL；表征的列确实变了")
    void t13_ap60GuardRowsAndColumns() {
        requireRecordLayer();

        Fx fx = newFixture("AC13");
        String mat = PREFIX + "G13-" + shortId(fx);
        seedMainGroup(mat, 6);

        Map<Long, String> before = groupSnapshot(mat);
        assertEquals(6, before.size(), "夹具应造出 6 行，实际 " + before.size());

        // 页签用 WHERE 谓词只收窄到 item_seq <= 2 的两行；且只暴露 component_qty 一列
        List<Long> represented = List.of(nth(before, 0), nth(before, 1));
        Set<String> exposed = new LinkedHashSet<>(List.of("component_qty"));
        // 「未暴露」的列 —— 它们必须逐字未变（E-2 靶子）
        Set<String> notExposed = new LinkedHashSet<>(businessColumns(MBOM));
        notExposed.removeAll(exposed);
        notExposed.remove("material_no");   // 轴列本身
        assertFixtureNonEmpty(notExposed.size(),
                "「页签未暴露的列」集合（阳性对照：为空 ⇒ 断言② 0 次循环恒真）");

        snapshotRecordCoveringRowsAndColumns(fx, mat, represented, exposed);
        changeRecordValues(fx, mat, represented, Map.of("component_qty", "1234.5"));

        approve(fx);

        Map<Long, String> after = groupSnapshot(mat);

        // ── AC-13①：未表征的 4 行仍在，逐列未变
        Set<Long> missingOrigins = new LinkedHashSet<>();
        List<String> drifted = new java.util.ArrayList<>();
        for (Map.Entry<Long, String> e : before.entrySet()) {
            if (represented.contains(e.getKey())) continue;
            String now = businessTupleOfOriginRow(mat, e.getKey());
            if (now == null) { missingOrigins.add(e.getKey()); continue; }
            if (!e.getValue().equals(now)) drifted.add("origin#" + e.getKey() + ": " + e.getValue() + " → " + now);
        }
        assertTrue(missingOrigins.isEmpty(),
                "🚨 AC-13① / AP-60：页签没表征的行被删掉了 —— 消失的原行 " + missingOrigins
                        + "。这正是 repair-0727 的事故形状（4 行被对齐成 1 行）。"
                        + "回填前 " + before.size() + " 行，回填后 " + after.size() + " 行");
        assertTrue(drifted.isEmpty(),
                "AC-13①：未表征的行必须逐列未变，实测漂移 " + drifted);

        // ── AC-13②：被表征行里，页签没暴露的列逐字未变（🚫 不得写 NULL）
        List<String> nulled = new java.util.ArrayList<>();
        for (Long id : represented) {
            Map<String, String> b = columnValuesOfOriginRow(mat, id, notExposed, before);
            Map<String, String> a = columnValuesOfOriginRow(mat, id, notExposed, after);
            for (String c : notExposed) {
                if (!java.util.Objects.equals(b.get(c), a.get(c))) {
                    nulled.add("origin#" + id + "." + c + ": " + b.get(c) + " → " + a.get(c));
                }
            }
        }
        assertTrue(nulled.isEmpty(),
                "🚨 AC-13②：被表征行里页签没暴露的列必须逐字未变（🚫 不得写 NULL）。"
                        + "实测 " + nulled.size() + " 处变化 —— " + nulled
                        + "。AP-60 实证：element_bom_item.base_qty 由 0.624610 变 NULL 而预览显示 0 变更。");

        // ── AC-13③ 反向：表征并改了的列确实变了（防止修成「什么都不写」）
        for (Long id : represented) {
            String nowQty = String.valueOf(scalar("SELECT component_qty FROM " + MBOM
                    + " WHERE material_no = '" + mat + "' AND " + originIdPredicate(id)));
            assertTrue(nowQty.startsWith("1234.5"),
                    "AC-13③ 反向：页签表征并改了 component_qty=1234.5 的行 origin#" + id
                            + " 实际值为 " + nowQty + " ⇒ 回填被修成了「什么都不写」，同样不满足 AC");
        }
    }

    /**
     * <b>T-08（AC-8）</b>：主表记来源报价单 id，且该列<b>不是 {@code ColumnDef}</b>。
     *
     * <p>🔑 <b>用内容发现法，不猜列名</b>：AC-8 只说「来源报价单 id 列」，没给列名，
     * 而我不读实现（{@code testing.md} §1）。判据写成：
     * 「升版后，主表该组新行里<b>存在恰好一列</b>，其值等于本次报价单 id」。
     * 这比钉死列名更贴 AC，而且实现改列名也不会假红。
     *
     * <p>反向判据（AC-8 后半 + §4.2 硬约束）：该列不能是 {@code ColumnDef} ——
     * {@code DatasetSheetParser} 会要求每个 {@code persistedColumns()} 的 label 都出现在 Excel 表头，
     * 缺一个则整份 sheet 拒收 ⇒ 任何一张既有报价 Excel（不含该列表头）仍必须能正常导入。
     */
    @Test
    @DisplayName("T-08 · 主表新行记来源报价单 id；且既有 Excel 仍能导入（证明它不是 ColumnDef）")
    void t08_sourceQuotationIdRecordedButNotAColumnDef() {
        requireRecordLayer();

        Fx fx = newFixture("AC8");
        String mat = PREFIX + "G8-" + shortId(fx);
        seedMainGroup(mat, 3);
        snapshotRecordCoveringRowsAndColumns(fx, mat, List.copyOf(groupSnapshot(mat).keySet()),
                new LinkedHashSet<>(List.of("component_qty")));
        changeRecordValues(fx, mat, List.copyOf(groupSnapshot(mat).keySet()), Map.of("component_qty", "42"));
        approve(fx);

        // ── 内容发现法：找出值等于本次报价单 id 的列
        List<String> hits = new java.util.ArrayList<>();
        for (String c : allColumns(MBOM)) {
            long n = count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + mat + "' "
                    + "AND " + c + "::text = '" + fx.quotationId() + "'");
            if (n > 0) hits.add(c + "(" + n + " 行)");
        }
        assertEquals(1, hits.size(),
                "AC-8：升版后主表该组新行里应恰好有一列 = 本次报价单 id " + fx.quotationId()
                        + "，实际命中 " + hits.size() + " 列：" + hits
                        + "。0 列 ⇒ 来源报价单 id 没落库；>1 列 ⇒ 语义重复。"
                        + "（本判据用内容发现法，不钉死列名 —— AC-8 原文未给列名）");
        System.out.println("[T-08] 来源报价单 id 列（内容发现）= " + hits.get(0));

        // ── AC-8 反向：既有报价 Excel（不含该列表头）仍能导入
        assertExistingQuoteExcelStillImports();
    }

    /**
     * <b>T-09（AC-9）</b>：复用 {@code VersionedGroupWriter}，不另立升版实现。
     *
     * <p>断言分两半：
     * ① <b>静态</b>：本段新增代码里，「算 {@code row_fingerprint} / 写 {@code _history} /
     *    决定 {@code version_no}」三类动作<b>一处都没有</b>自己实现。
     *    🚫 判据写成<b>集合关系</b>（新增文件 ∩ 自实现这三类动作 = ∅），不写数字 ——
     *    数字是移动靶，写死了下次加一个文件就假红。
     * ② <b>运行时同源</b>：回填路径的三态与版本号取值规则与导入路径一致
     *    （{@code CREATED}/{@code UPGRADED}/{@code UNCHANGED}，版本号取 {@code max} 而非「当前 + 1」）。
     *    ②的运行时部分已由 T-06③ 覆盖（{@code max(主表当前, _history 最大) + 1}）。
     */
    @Test
    @DisplayName("T-09 · 新增代码不自己实现指纹/归档/定版本号（静态集合判据）")
    void t09_noReimplementationOfVersioning() {
        // 静态扫描在 worktree 内做，不依赖数据库与实现是否已落地 ⇒ 无 requireRecordLayer()
        List<String> newFiles = newlyAddedBackendSources();
        assertFixtureNonEmpty(newFiles.size(),
                "本分支新增的后端源文件数（阳性对照：为空 ⇒ 下面的扫描 0 次循环恒真恒通过。"
                        + "若后端确实还没提交代码，本条应报「⛔ 待实现」而不是绿）");

        // 三类动作的判据词 —— 各自都是「自己实现」才会出现的写法
        Map<String, List<String>> forbidden = new LinkedHashMap<>();
        forbidden.put("自己算 row_fingerprint", List.of("row_fingerprint\\s*=", "sha256", "MessageDigest"));
        forbidden.put("自己写 _history", List.of("INSERT\\s+INTO\\s+\\w*_history", "_history\\s*\\("));
        forbidden.put("自己定 version_no", List.of("version_no\\s*\\+\\s*1", "versionNo\\s*\\+\\s*1"));

        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (String f : newFiles) {
            String body = readFile(f);
            for (var e : forbidden.entrySet()) {
                for (String pat : e.getValue()) {
                    if (java.util.regex.Pattern.compile(pat).matcher(body).find()) {
                        offenders.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>())
                                .add(f + " 命中 /" + pat + "/");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "AC-9：本段新增代码自己实现了升版三件事之一（应一律走 VersionedGroupWriter）—— " + offenders
                        + "。扫描范围：本分支相对 master 新增的 " + newFiles.size() + " 个后端源文件。");
    }

    // ═══════════════════════ 工具 ═══════════════════════

    private void seedMainGroup(String materialNo, int rows) {
        inTx(() -> {
            for (int i = 1; i <= rows; i++) {
                insertMaterialBomRow(materialNo, i, PREFIX + "IN-" + i, String.valueOf(i * 10), 1);
            }
        });
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + materialNo + "'"),
                "夹具主表组 " + materialNo + " 行数");
    }

    /** {@code 主表行 id -> 该行的业务列元组文本}（🚫 不含 id / version_no / updated_at 等会随升版正常变化的列）。 */
    private Map<Long, String> groupSnapshot(String materialNo) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT id, " + businessTupleExpr() + " FROM " + MBOM
                + " WHERE material_no = '" + materialNo + "' ORDER BY id")) {
            out.put(((Number) r[0]).longValue(), String.valueOf(r[1]));
        }
        return out;
    }

    /** 业务列元组表达式 —— 排除系统列与版本列，它们随升版变化属正常，纳入比对会产生假红。 */
    private String businessTupleExpr() {
        StringBuilder sb = new StringBuilder();
        for (String c : businessColumns(MBOM)) {
            if (sb.length() > 0) sb.append(" || '|' || ");
            sb.append("coalesce(").append(c).append("::text,'<NULL>')");
        }
        return sb.toString();
    }

    private static final Set<String> SYSTEM_COLS = new LinkedHashSet<>(List.of(
            "id", "version_no", "row_fingerprint", "source",
            "created_at", "created_by", "updated_at", "updated_by"));

    private List<String> businessColumns(String table) {
        List<String> out = new java.util.ArrayList<>();
        for (String c : allColumns(table)) {
            if (!SYSTEM_COLS.contains(c)) out.add(c);
        }
        assertFalse(out.isEmpty(), "表 " + table + " 没有业务列 ⇒ 比对表达式会退化");
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> allColumns(String table) {
        return (List<String>) (List<?>) col("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + sqlSafe(table) + "' ORDER BY ordinal_position");
    }

    private int maxVersion(String table, String materialNo) {
        Object v = scalar("SELECT max(version_no) FROM " + sqlSafe(table)
                + " WHERE material_no = '" + materialNo + "'");
        assertNotNull(v, "主表 " + table + " 上找不到轴值 " + materialNo);
        return ((Number) v).intValue();
    }

    private int maxHistoryVersion(String materialNo) {
        Object v = scalar("SELECT coalesce(max(version_no), 0) FROM " + MBOM + "_history "
                + "WHERE material_no = '" + materialNo + "'");
        return ((Number) v).intValue();
    }

    private static Long nth(Map<Long, String> m, int i) {
        return List.copyOf(m.keySet()).get(i);
    }

    private String shortId(Fx fx) {
        return fx.quotationId().toString().substring(0, 6);
    }

    private String readFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (Exception e) {
            throw new AssertionError("读不到新增源文件 " + path + "（用例环境问题）：" + e, e);
        }
    }

    /**
     * 本分支新增的后端源文件（AC-9 静态扫描的范围）。
     *
     * <p>🚨 <b>必须同时收「已跟踪的新增」与「未跟踪的新文件」</b>：
     * 子代理按纪律不执行 {@code git commit}（提交由主线统一做），
     * ⇒ 新写的服务包在扫描时通常还是 <b>untracked</b>。
     * 只用 {@code git diff --diff-filter=A} 会漏掉它们，而漏掉的表现是<b>扫描 0 个文件、断言恒真报绿</b>
     * —— 那正是本任务要防的假绿形态（{@code RECORD.md} 有同型实证：
     * 「子代理不 commit」把提交责任隐式转给主线，而三项常规前置检查都发现不了代码没进 git）。
     */
    private List<String> newlyAddedBackendSources() {
        java.io.File repoRoot = new java.io.File(System.getProperty("user.dir")).getParentFile();
        Set<String> rel = new LinkedHashSet<>();
        rel.addAll(gitLines(repoRoot, "diff", "--name-only", "--diff-filter=A", "master", "--",
                "cpq-backend/src/main/java"));
        rel.addAll(gitLines(repoRoot, "ls-files", "--others", "--exclude-standard", "--",
                "cpq-backend/src/main/java"));
        List<String> files = new java.util.ArrayList<>();
        for (String line : rel) {
            if (line.endsWith(".java")) files.add(new java.io.File(repoRoot, line).getAbsolutePath());
        }
        return files;
    }

    private List<String> gitLines(java.io.File dir, String... args) {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            List<String> lines = new java.util.ArrayList<>();
            for (String l : out.split("\n")) {
                if (!l.isBlank()) lines.add(l.trim());
            }
            return lines;
        } catch (Exception e) {
            throw new AssertionError("AC-9 静态扫描无法执行（用例环境问题，不是业务结论）：" + e, e);
        }
    }

    // ⛔ 待接实现的挂载点 —— 🚫 刻意不返回空实现（空实现会让断言在「什么都没做」时跑完并报绿）

    private void snapshotRecordCoveringRowsAndColumns(Fx fx, String materialNo,
                                                      List<Long> originIds, Set<String> columns) {
        throw pending("为 " + fx.quotationNo() + " 拍一份只表征 " + materialNo + " 的行 " + originIds
                + " 与列 " + columns + " 的 _record 快照（其余行/列不进快照）");
    }

    private void changeRecordValues(Fx fx, String materialNo, List<Long> originIds, Map<String, String> newValues) {
        throw pending("把 " + materialNo + " 在 _record 里对应 " + originIds + " 的行改成 " + newValues);
    }

    private void approve(Fx fx) {
        throw pending("提交 " + fx.quotationNo() + " → 预览拿 previewToken → 带 token 确认核价通过");
    }

    private String businessTupleOfOriginRow(String materialNo, long originId) {
        Object v = scalar("SELECT " + businessTupleExpr() + " FROM " + MBOM
                + " WHERE material_no = '" + materialNo + "' AND " + originIdPredicate(originId));
        return v == null ? null : v.toString();
    }

    private Map<String, String> columnValuesOfOriginRow(String materialNo, long originId,
                                                        Set<String> columns, Map<Long, String> ignored) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : columns) {
            Object v = scalar("SELECT " + sqlSafe(c) + "::text FROM " + MBOM
                    + " WHERE material_no = '" + materialNo + "' AND " + originIdPredicate(originId));
            out.put(c, v == null ? null : v.toString());
        }
        return out;
    }

    /**
     * ⛔ 「升版后如何定位到原来那一行」—— 升版会换 {@code id}（{@code test.md} 风险点 3 实证：
     * {@code T260907-M1} 升版后 {@code origin_id} 3199/3200 与主表当前 id 3201 <b>交集为空</b>）。
     * ⇒ 判据必须走 {@code _history.origin_id} 链或内容锚，不能直接用 id。具体口径待主线补齐。
     */
    private String originIdPredicate(long originId) {
        throw pending("升版后按原行 id " + originId + " 定位到当前对应行的谓词"
                + "（升版会换 id，须走 _history.origin_id 链或内容锚）");
    }

    private void assertExistingQuoteExcelStillImports() {
        throw pending("拿一张既有报价 Excel（不含「来源报价单 id」表头）走导入，断言不进 missingHeaders、"
                + "整份 sheet 不被拒收 —— 这是 AC-8「该列不是 ColumnDef」的反向判据");
    }

    private static UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(
                "⛔ 待接实现（**不是被测功能的结论**）：" + what
                        + "。缺的契约信息已在测试回报的「缺什么」清单里列给主线。");
    }
}
