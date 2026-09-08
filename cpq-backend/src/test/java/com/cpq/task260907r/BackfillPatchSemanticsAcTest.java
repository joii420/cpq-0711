package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** 🔑 被测主表改为 ds_quote_element_bom：本模板 13 组件里没有物料BOM，那张表造不出 _record。 */
    private static final String MBOM = EBOM;

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    /**
     * <b>T-06（AC-6）+ T-13（AC-13）🚨 AP-60 行维度守卫</b>
     *
     * <p>造 5 行组 → 第二张单只表征其中 2 行（其中 1 行改值）→ 核价通过确认。
     *
     * <p>断言（AC-6① / AC-13①③）：
     * <ol>
     *   <li><b>未被页签表征的 3 行仍在主表，逐列未变</b> —— 🚨 这是 {@code repair-0727} 的事故形状
     *       （4 行被「对齐」成 1 行）；</li>
     *   <li>组的总行数不减 —— 防「静默吞行」；也不翻倍 —— 防上一轮抓到的「锚不上就追加」；</li>
     *   <li><b>反向</b>：确实表征并改了值的那一行，确实变了（防修成「什么都不写」）；</li>
     *   <li>AC-6③ 新版号 = {@code max(主表当前, _history 最大) + 1}；AC-6② 旧版整组进 {@code _history}。</li>
     * </ol>
     *
     * <h3>⛔ 列维度（AC-13②）在现有夹具下<b>不可阳性验证</b>，本条<b>不覆盖</b>它</h3>
     * 实查：组件「T260907-物料与元素BOM」有 <b>12</b> 个字段，主表 {@code ds_quote_element_bom} 有
     * <b>12</b> 个业务列 ⇒ <b>「页签未暴露的列」集合为空</b>，
     * 断言它「逐字未变」会 0 次循环恒真 —— 那是假绿，不是覆盖。
     * 佐证：{@code UPGRADED} 组的 {@code columnScope.preserved} 实测恒为 {@code []}。
     * ⇒ 要验 AC-13② 需要<b>一个只暴露主表部分列的 ds 组件</b>，现网没有。
     * {@code test-report.md} 的 AC-13② 须标 <b>⛔ 缺阳性夹具</b>，🚫 不许写「通过」。
     *
     * <h3>⚠️ 覆盖边界（同上一轮）：本条用平铺页签替代树页签</h3>
     * {@code B-7}（BOM 树递归换表）未落地 ⇒ 验不到 {@code AP-60} 的<b>闭包放大</b>那一层。
     */
    @Test
    @DisplayName("T-06+T-13 · [行维度·平铺页签] 5 行组只表征 2 行 → 未表征的 3 行逐列保留，改的那行确实变")
    void t06t13_ap60RowDimensionGuard() {
        requireRecordLayer();

        String mat = PREFIX + "G6-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        List<EbomRow> fullGroup = List.of(
                new EbomRow(1, PREFIX + "EL1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "EL2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "EL3", "30.0", "3.3"),
                new EbomRow(4, PREFIX + "EL4", "40.0", "4.4"),
                new EbomRow(5, PREFIX + "EL5", "50.0", "5.5"));

        // ── 前置：让 writer 自己把 5 行 v1 造出来（🚫 不手工 INSERT，见 seedMainViaCreatedOrder 注释）
        seedMainViaCreatedOrder("AC6seed", mat, fullGroup);
        Map<Integer, String> before = ebomBusinessRowsBySeq(mat);
        assertEquals(5, before.size(), "前置：主表该组应为 5 行，实际 " + before.size() + " → " + before);
        int mainVerBefore = maxVersion(mat);
        int histVerBefore = maxHistoryVersion(mat);

        // ── 第二张单：只表征 seq=2 与 seq=4 两行，且把 seq=2 的 content_pct 改掉
        List<EbomRow> represented = List.of(
                new EbomRow(2, PREFIX + "EL2", "99.9", "2.2"),   // 改值
                new EbomRow(4, PREFIX + "EL4", "40.0", "4.4"));  // 原样
        Fx fx = newSubmittedOrder("AC6", mat, represented);

        JsonNode g = findGroup(dsBackfill(ok(getPreview(fx.quotationId()), "T-06 预览")), MBOM, mat);
        assertNotNull(g, "预览里应出现轴值 " + mat + " 的组");
        System.out.println("[T-06+13] 预览组形状 = " + g);

        approveWithPreview(fx, "AC6");

        // ── 🚨 AC-13① / AP-60：未表征的 3 行必须仍在且逐列未变
        Map<Integer, String> after = ebomBusinessRowsBySeq(mat);
        List<String> vanished = new java.util.ArrayList<>();
        List<String> drifted = new java.util.ArrayList<>();
        for (Map.Entry<Integer, String> e : before.entrySet()) {
            if (e.getKey() == 2 || e.getKey() == 4) continue;      // 被表征的两行另行断言
            String now = after.get(e.getKey());
            if (now == null) vanished.add("seq=" + e.getKey());
            else if (!e.getValue().equals(now)) drifted.add("seq=" + e.getKey() + ": " + e.getValue() + " → " + now);
        }
        assertTrue(vanished.isEmpty(),
                "🚨 AC-13① / AP-60：页签没表征的行被删掉了 —— 消失的 " + vanished
                        + "。这正是 repair-0727 的事故形状（4 行被对齐成 1 行）。"
                        + "回填前 " + before.size() + " 行 " + before + "；回填后 " + after.size() + " 行 " + after);
        assertTrue(drifted.isEmpty(),
                "AC-13①：未表征的行必须逐列未变，实测漂移 " + drifted);

        // ── 行数既不减也不增（防静默吞行 / 防锚不上就追加）
        assertEquals(before.size(), after.size(),
                "AC-6①：整组行数应保持 " + before.size() + "，实际 " + after.size()
                        + " ⇒ 变少=静默吞行；变多=锚定失效被当成新增。after=" + after);

        // ── 反向（AC-13③）：表征并改了的那一行确实变了；表征但没改的那行不变
        assertFalse(before.get(2).equals(after.get(2)),
                "AC-13③ 反向：seq=2 表征并把 content_pct 改成 99.9，应当变化，实测逐字未变 —— "
                        + "回填被修成了「什么都不写」同样不满足 AC。值=" + after.get(2));
        assertTrue(String.valueOf(after.get(2)).contains("99.9"),
                "AC-13③ 反向：seq=2 应写入 99.9，实际 " + after.get(2));
        assertEquals(before.get(4), after.get(4),
                "AC-6①：seq=4 被表征但值没改，应逐字未变。before=" + before.get(4) + " after=" + after.get(4));

        // ── AC-6③ 版本号 = max(主表当前, _history 最大) + 1
        int expectedVer = Math.max(mainVerBefore, histVerBefore) + 1;
        assertEquals(expectedVer, maxVersion(mat),
                "AC-6③：新版号应 = max(主表当前 " + mainVerBefore + ", _history 最大 " + histVerBefore
                        + ") + 1 = " + expectedVer + "（🚫 不是「当前 + 1」）");

        // ── AC-6② 旧版**整组** 5 行进 _history + archive_reason 唯一且在 varchar(32) 内
        long archived = count("SELECT count(*) FROM " + MBOM + "_history WHERE material_no = '" + mat
                + "' AND version_no = " + mainVerBefore);
        assertEquals(5L, archived,
                "AC-6②：旧版 v" + mainVerBefore + " 应<b>整组</b> 5 行进 _history（只归档变过的行 = 旧版查不回来），实际 "
                        + archived);
        List<Object> reasons = col("SELECT DISTINCT archive_reason FROM " + MBOM + "_history "
                + "WHERE material_no = '" + mat + "' AND version_no = " + mainVerBefore);
        assertEquals(1, reasons.size(), "AC-6②：同一次归档的 archive_reason 应唯一，实际 " + reasons);
        assertNotNull(reasons.get(0), "AC-6②：archive_reason 不应为 NULL");
        assertTrue(reasons.get(0).toString().length() <= 32,
                "AC-6②：archive_reason 超出 varchar(32)：'" + reasons.get(0) + "'");
        System.out.println("[T-06+13] archive_reason = " + reasons.get(0)
                + "；版本 " + mainVerBefore + " → " + maxVersion(mat));

        assertEquals("APPROVED", String.valueOf(scalar(
                        "SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")),
                "AC-6④：确认后报价单状态应为 APPROVED");
    }

    private int maxVersion(String materialNo) {
        Object v = scalar("SELECT max(version_no) FROM " + MBOM + " WHERE material_no = '" + materialNo + "'");
        assertNotNull(v, "主表上找不到轴值 " + materialNo);
        return ((Number) v).intValue();
    }

    private int maxHistoryVersion(String materialNo) {
        Object v = scalar("SELECT coalesce(max(version_no),0) FROM " + MBOM + "_history "
                + "WHERE material_no = '" + materialNo + "'");
        return ((Number) v).intValue();
    }

    private com.fasterxml.jackson.databind.JsonNode findGroup(
            com.fasterxml.jackson.databind.JsonNode ds, String tableName, String axisValue) {
        for (com.fasterxml.jackson.databind.JsonNode t : ds.path("tables")) {
            if (!tableName.equals(t.path("tableName").asText())) continue;
            for (com.fasterxml.jackson.databind.JsonNode g : t.path("groups")) {
                if (axisValue.equals(g.path("axisValue").asText())) return g;
            }
        }
        return null;
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
        seedMainGroup(fx.customerNo(), mat, 3);
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

    private void seedMainGroup(String customerNo, String materialNo, int rows) {
        inTx(() -> {
            for (int i = 1; i <= rows; i++) {
                insertMaterialBomRow(customerNo, materialNo, i, PREFIX + "IN-" + i, String.valueOf(i * 10), 1);
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
