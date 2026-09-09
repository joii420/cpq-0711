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
    // 🕰️ 2026-09-08 摘掉 @Disabled：B-3 的 source_quotation_id 已随 V431 落库
    //    （ds_quote_* 里带该列的表 = 26 张，0724 与 laneb 均已应用）。
    //    原 @Disabled 理由第③条写的就是「落库后本条应自动可跑：判据用内容发现法、
    //    不钉死列名，列一出现就能命中」⇒ 条件已具备，条件具备就该摘。
    //    ⚠️ 遗留：AC-8 的反向半边 assertExistingQuoteExcelStillImports() 仍是桩，
    //       需一份既有报价 Excel 夹具（不影响正向半边）。
    void t08_sourceQuotationIdRecordedButNotAColumnDef() {
        requireRecordLayer();

        // 🕰️ 2026-09-08 夹具修复：原写法 seedMainGroup → insertMaterialBomRow 往
        //    **ds_quote_material_bom** 插，却拿 MBOM(= EBOM = ds_quote_element_bom) 去数
        //    ⇒ 恒 0 行，失败在「空验证守卫」上。
        //    ⚠️ 那个症状看着像「被 B-3 前置挡住」，实际是**夹具的表对错了** ——
        //       t08 全文 grep `source_quotation_id` 0 命中，与该迁移无关。
        //    ⇒ 改走已跑通的 EBOM 路径（writer 自己算指纹），全程一张表。
        String mat = axis("G8");
        Fx seeder = seedMainViaCreatedOrder("AC8seed", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "20.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        // 同客户（复合轴 (customer_no, material_no)），改一行的值以触发升版
        Fx fx = newSubmittedOrderForCustomer("AC8", seeder, mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1"),
                new EbomRow(2, PREFIX + "E2", "42.0", "2.2"),
                new EbomRow(3, PREFIX + "E3", "30.0", "3.3")));
        int verBefore = intOf("SELECT max(version_no) FROM " + EBOM
                + " WHERE material_no = '" + mat + "'");
        approveWithPreview(fx, "AC8");
        assertEquals(verBefore + 1, intOf("SELECT max(version_no) FROM " + EBOM
                        + " WHERE material_no = '" + mat + "'"),
                "AC-8 前置：改了值应升一版，否则「升版后的新行」不存在，下面的发现法会空跑");
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + EBOM
                        + " WHERE material_no = '" + mat + "'"),
                "AC-8 前置：升版后主表该组行数");

        // ── 内容发现法：找出值等于本次报价单 id 的列（🚫 不钉死列名 —— AC-8 原文未给列名）
        List<String> hits = new java.util.ArrayList<>();
        for (String c : allColumns(EBOM)) {
            long n = count("SELECT count(*) FROM " + EBOM + " WHERE material_no = '" + mat + "' "
                    + "AND " + c + "::text = '" + fx.quotationId() + "'");
            if (n > 0) hits.add(c + "(" + n + " 行)");
        }
        System.out.println("[T-08] 内容发现法：扫描 " + allColumns(EBOM).size()
                + " 列，命中 = " + hits);
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
        // ⚠️ row_fingerprint 必须加词边界：_record 的列 `base_row_fingerprint`（存**基底行**的指纹，
        //    是拷贝不是计算）含 `row_fingerprint=` 子串，不加边界必然假阳性。
        //    🚫 version_no / versionNo **刻意不加**边界 —— `targetVersionNo + 1` 同样是「自己定版本号」，
        //       加了边界反而会漏掉它（收窄判据时要逐条问「这个前缀变体该不该放过」，不能一把全加）。
        forbidden.put("自己算 row_fingerprint",
                List.of("(?<![A-Za-z0-9_])row_fingerprint\\s*=", "sha256", "MessageDigest"));
        forbidden.put("自己写 _history", List.of("INSERT\\s+INTO\\s+\\w*_history", "_history\\s*\\("));
        forbidden.put("自己定 version_no", List.of("version_no\\s*\\+\\s*1", "versionNo\\s*\\+\\s*1"));

        Map<String, List<String>> offenders = new LinkedHashMap<>();
        for (String f : newFiles) {
            // 🚨 必须剥注释再扫：注释里出现这些词是**在描述行为**，不是在实现行为。
            //    2026-09-08 实证：DsRecordProjector 的 Javadoc 写「base_row_fingerprint=NULL」
            //    就把 AC-9 打红了一次，而该文件 sha256/MessageDigest/fingerprint( 全 0 命中。
            String code = stripComments(readFile(f));
            String[] lines = code.split("\n", -1);
            for (var e : forbidden.entrySet()) {
                for (String pat : e.getValue()) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pat).matcher(code);
                    if (m.find()) {
                        // 🔑 带上行号与该行原文 —— 只报「文件+模式」时，判断「真违规还是判据过宽」
                        //    还得人工再 grep 一次（本次就是这么浪费掉一轮的）。
                        int ln = 1 + (int) code.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                        String txt = ln - 1 < lines.length ? lines[ln - 1].trim() : "";
                        offenders.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>())
                                .add(f + ":" + ln + " 命中 /" + pat + "/ → " + txt);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "AC-9：本段新增代码自己实现了升版三件事之一（应一律走 VersionedGroupWriter）—— " + offenders
                        + "。扫描范围：本分支相对 master 新增的 " + newFiles.size() + " 个后端源文件。");
    }

    // ═══════════════════════ 工具 ═══════════════════════

    private String axis(String tag) {
        return PREFIX + tag + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
    }

    private int intOf(String sql) {
        Object v = scalar(sql);
        assertNotNull(v, "查询无结果，断言会空跑：" + sql);
        return ((Number) v).intValue();
    }

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

    /**
     * 去掉 Java 注释（{@code //}、{@code /*…*\/}、Javadoc），<b>保留字符串与字符字面量</b>。
     *
     * <h3>为什么不能用「先 split 行再判断以 // 开头」这种简写</h3>
     * 真正的违规（{@code "INSERT INTO x_history ("}）就活在<b>字符串字面量</b>里 ——
     * 任何把字面量一起抹掉的剥法都会制造<b>假阴性</b>，而假阴性比假阳性坏得多：
     * 假阳性你会去看，假阴性你以为它在守着。
     *
     * <p>🔑 注释里的换行原样保留，好让报出来的行号仍与源文件对得上。
     * <p>📌 文本块 {@code """…"""} 单列一态：不单独处理的话，三个引号会被当成
     * 「进串-出串-进串」，块内的 {@code //} 之后全被误判。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        final int CODE = 0, LINE_C = 1, BLOCK_C = 2, STR = 3, CHR = 4, TEXT_BLOCK = 5;
        int st = CODE, i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (st == CODE) {
                if (c == '/' && d == '/') { st = LINE_C; i += 2; continue; }
                if (c == '/' && d == '*') { st = BLOCK_C; i += 2; continue; }
                if (c == '"' && d == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                    st = TEXT_BLOCK; out.append("\"\"\""); i += 3; continue;
                }
                if (c == '"') { st = STR; out.append(c); i++; continue; }
                if (c == '\'') { st = CHR; out.append(c); i++; continue; }
                out.append(c); i++; continue;
            }
            if (st == LINE_C) {
                if (c == '\n') { st = CODE; out.append(c); }
                i++; continue;
            }
            if (st == BLOCK_C) {
                if (c == '*' && d == '/') { st = CODE; i += 2; continue; }
                if (c == '\n') out.append(c);      // 保住行号
                i++; continue;
            }
            if (st == TEXT_BLOCK) {
                if (c == '"' && d == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                    st = CODE; out.append("\"\"\""); i += 3; continue;
                }
                out.append(c); i++; continue;
            }
            // STR / CHR：字面量原样保留，转义整体跳过
            out.append(c);
            if (c == '\\' && i + 1 < n) { out.append(d); i += 2; continue; }
            if ((st == STR && c == '"') || (st == CHR && c == '\'')) st = CODE;
            i++;
        }
        return out.toString();
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
    /**
     * AC-9 扫描范围的<b>基线提交</b>：本段第二段代码开工前的那一点
     * （{@code fix(migration): V430 补进 master}）。
     *
     * <h3>🚫 为什么不能用 {@code master}（2026-09-08 实测踩了）</h3>
     * 原来写的是 {@code git diff --diff-filter=A master}。分支<b>合并进 master 之后</b>，
     * {@code master} 就包含了被测代码本身 ⇒ 「本分支新增」<b>塌成空集</b> ⇒ 扫描 0 次循环、
     * 判据恒真。本条的空验证守卫如实拦下了它，但**判据的寿命天然截止于合并那一刻**。
     * <p>🔑 与「阳性对照来自被测系统缺陷」是同源形态：<b>判据本身没问题，
     * 是它赖以成立的前提消失了</b>。⇒ 基线必须钉一个<b>历史里不会再动的点</b>，
     * 🚫 不许钉 {@code master}（移动靶），也🚫 不许钉分支名（会随分支推进而漂）。
     * <p>📌 实测：以本基线取到 12 个文件（{@code Ds*} 全族 11 个 + D-43 的
     * {@code DsRecordCardDeduper}）；用 {@code master} 取到 <b>0</b> 个。
     */
    private static final String AC9_BASELINE_COMMIT = "0ec3946a";

    private List<String> newlyAddedBackendSources() {
        java.io.File repoRoot = new java.io.File(System.getProperty("user.dir")).getParentFile();
        Set<String> rel = new LinkedHashSet<>();
        rel.addAll(gitLines(repoRoot, "diff", "--name-only", "--diff-filter=A", AC9_BASELINE_COMMIT, "--",
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

    // ─────────── AC-8 反向半边：既有报价 Excel 仍能导入 ───────────

    /** B-3 迁移 D-28 明令「绝不能声明成 ColumnDef」的那一列。 */
    private static final String SRC_QID = "source_quotation_id";

    /**
     * 既有报价 Excel（表头里<b>没有</b>来源报价单 id 这一列）仍能整份导入
     * ⇒ 证明 {@code source_quotation_id} <b>不是 {@code ColumnDef}</b>（AC-8 反向半边）。
     *
     * <h3>机制（判据为什么成立）</h3>
     * {@code DatasetSheetParser} 逐个 {@code spec.persistedColumns()} 去 Excel 表头里找 label，
     * 找不到就进 {@code missingHeaders}；{@code missingHeaders} 非空则<b>整张 sheet 拒收</b>
     * （{@code api.md §2}：「整份拒收，一行未写」，{@code status=FAILED}）。
     * ⇒ 该列一旦被声明成 {@code ColumnDef}，<b>所有存量报价 Excel 会当场导不进去</b>。
     *
     * <h3>🚨 三条守卫（少一条这用例就有恒真的口子）</h3>
     * <ol>
     *   <li><b>前提守卫</b>：先断言主表<b>当下确实带</b> {@code source_quotation_id} 列。
     *       V431 没落到本库时，「导入成功」什么都证不出 —— 本判据验的正是
     *       「<b>有</b>这列，但它<b>不是</b> ColumnDef」。缺列必须硬失败，🚫 不许静默通过。</li>
     *   <li>🔑 <b>机制守卫</b>：判据不只落在「导入成功」这个<b>结果</b>上，还落在
     *       「该列不在任何 sheet 的<b>已声明列</b>里」这个<b>机制</b>上。
     *       只断言「导入成功」的话，将来有人给 Excel 模板<b>补上</b>这一列表头，
     *       导入照样成功，而 ColumnDef 化<b>已经发生了</b> —— 判据会静默失效。
     *       已声明列走 {@code api.md §5} 的 {@code GET /dataset/quote/sheets}（零改动端点），
     *       它的 {@code columns[].name} 就是 sheet 规格的契约投影
     *       （实证：{@code 物料BOM} 的 13 个 label 与主文件夹具表头逐字逐序一致）。</li>
     *   <li><b>非空守卫</b>：{@code SUCCESS} 但 0 行时「没报缺表头」<b>恒真</b>。
     *       故须断言<b>本次导入</b>真的处理了行 —— 用 {@code totalRows}/{@code successRows}
     *       与 {@code summary} 里目标 sheet 的三态计数，🚫 不用「表里有多少行」
     *       （那会被<b>导入前就存在</b>的行满足）。</li>
     * </ol>
     */
    private void assertExistingQuoteExcelStillImports() {
        // ── 守卫①（前提）：主表当下确实带 source_quotation_id ─────────────
        long tablesWithCol = count("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name LIKE 'ds\\_quote\\_%' "
                + "AND column_name = '" + SRC_QID + "'");
        System.out.println("[T-08 反向] 带 " + SRC_QID + " 的 ds_quote_* 表数 = " + tablesWithCol);
        assertTrue(tablesWithCol > 0,
                "⛔ 环境前置未满足（**不是被测功能的结论**）：当前库里没有任何 ds_quote_* 表带 "
                        + SRC_QID + " 列 ⇒ B-3/V431 未落到本库。"
                        + "此时「既有 Excel 仍能导入」什么都证不出 —— 本判据验的正是"
                        + "「有这列、但它不是 ColumnDef」。");
        assertEquals(1L, count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + EBOM + "' "
                        + "AND column_name = '" + SRC_QID + "'"),
                "⛔ 环境前置：本判据下游要断言的 sheet「物料与元素BOM」落在 " + EBOM
                        + "，该表必须带 " + SRC_QID + " 列，否则证明链断在中间");

        // ── 守卫②（机制）：该列不在任何 sheet 的已声明列里 ────────────────
        io.restassured.response.Response sheetsR = io.restassured.RestAssured
                .given().cookies(adminCookies())
                .when().get("/api/cpq/dataset/quote/sheets").thenReturn();
        JsonNode sheets = ok(sheetsR, "GET /dataset/quote/sheets（api.md §5 零改动端点）").path("sheets");
        assertTrue(sheets.isArray(), "api.md §5 契约：sheets 应是数组，实际=" + sheets);
        assertFixtureNonEmpty(sheets.size(), "GET /dataset/quote/sheets 返回的 sheet 数");
        List<String> offenders = new java.util.ArrayList<>();
        int declaredCols = 0;
        for (JsonNode sh : sheets) {
            for (JsonNode c : sh.path("columns")) {
                declaredCols++;
                if (SRC_QID.equals(c.path("name").asText())) {
                    offenders.add(sh.path("sheetKey").asText() + "." + SRC_QID);
                }
            }
        }
        System.out.println("[T-08 反向] 扫 " + sheets.size() + " 张 sheet / " + declaredCols
                + " 个已声明列，" + SRC_QID + " 命中 = " + offenders);
        // 🚨 0 列 ⇒ 上面的循环压根没跑，offenders 恒空 ⇒ 断言空跑照样报绿
        assertFixtureNonEmpty(declaredCols, "各 sheet 已声明列（columns[]）总数");
        assertTrue(offenders.isEmpty(),
                "AC-8 反向半边：" + SRC_QID + " 被声明成了 ColumnDef —— 命中 " + offenders
                        + "。B-3 迁移 D-28 明令该列绝不能声明成 ColumnDef："
                        + "DatasetSheetParser 会要求每个 persistedColumns() 的 label 都出现在 Excel 表头，"
                        + "缺一个则整张 sheet 拒收 ⇒ 所有存量报价 Excel 会当场导不进去。");

        // ── 守卫④（污染前置）：导入前该轴必须是 0 行 ─────────────────
        assertLegacyAxisPristine();

        // ── 正向：既有报价 Excel 走真实导入端点 ─────────────────────────
        java.nio.file.Path xlsx = fixtureXlsx(LEGACY_QUOTE_XLSX);
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(xlsx);
        } catch (java.io.IOException e) {
            throw new AssertionError("读夹具失败（用例环境问题，不是业务结论）：" + xlsx, e);
        }
        assertFixtureNonEmpty(bytes.length, "夹具 " + LEGACY_QUOTE_XLSX + " 的字节数");
        String custId = requireCustomerId(LEGACY_QUOTE_CUSTOMER);

        io.restassured.response.Response impR = io.restassured.RestAssured
                .given().cookies(adminCookies())
                .multiPart("customerId", custId)
                .multiPart("file", LEGACY_QUOTE_XLSX, bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/api/cpq/dataset/quote/quotation-import").thenReturn();
        String recId = ok(impR, "POST /dataset/quote/quotation-import（api.md §1）")
                .path("importRecordId").asText(null);
        assertNotNull(recId, "api.md §1 契约：响应必须含 importRecordId，实际=" + impR.asString());
        System.out.println("[T-08 反向] importRecordId = " + recId);

        try {
            JsonNode fin = awaitImportFinal(recId);
            String status = fin.path("status").asText();
            // 🚩 缺表头会以「整份拒收 + errors」的形态出现 —— 失败时必须把 errors 原文带出来，
            //    否则「导入失败」三个字既可能是 AC-8 破了，也可能是夹具/主数据缺失，无从归因。
            assertEquals("SUCCESS", status,
                    "AC-8 反向半边：既有报价 Excel「" + LEGACY_QUOTE_XLSX + "」应能整份导入，实际 status="
                            + status + "。若 errors 里出现「缺少列/表头」类原因 ⇒ "
                            + SRC_QID + " 已被 ColumnDef 化，AC-8 反向半边破；"
                            + "若是主数据/客户编号类原因 ⇒ 是夹具或库环境问题，不是 AC-8 的结论。"
                            + " 完整响应=" + fin);

            // ── 守卫③（非空）：本次导入真的处理了行 ─────────────────────
            long totalRows = fin.path("totalRows").asLong(0);
            long successRows = fin.path("successRows").asLong(0);
            System.out.println("[T-08 反向] totalRows=" + totalRows + " successRows=" + successRows
                    + " failedRows=" + fin.path("failedRows").asLong(0));
            assertFixtureNonEmpty(totalRows, "本次导入的 totalRows（0 行时「没报缺表头」恒真）");
            assertFixtureNonEmpty(successRows, "本次导入的 successRows");

            // 目标 sheet 真的写了行 —— 🚫 不查「表里有多少行」（导入前就存在的行会满足它）
            JsonNode summary = fin.path("summary");
            assertTrue(summary.isArray(), "api.md §2 契约：SUCCESS 时应有 summary 数组，实际=" + fin);
            assertFixtureNonEmpty(summary.size(), "summary 条目数");
            JsonNode target = null;
            for (JsonNode s : summary) {
                if (TARGET_SHEET.equals(s.path("sheetName").asText())) {
                    target = s;
                }
            }
            assertNotNull(target, "summary 里没有目标 sheet「" + TARGET_SHEET + "」⇒ 它压根没被处理，"
                    + "「仍能导入」无从谈起。summary=" + summary);
            long touched = target.path("axisCount").asLong(0)
                    + target.path("created").asLong(0)
                    + target.path("upgraded").asLong(0)
                    + target.path("unchanged").asLong(0)
                    + target.path("inserted").asLong(0)
                    + target.path("updated").asLong(0);
            System.out.println("[T-08 反向] 目标 sheet「" + TARGET_SHEET + "」summary = " + target);
            assertFixtureNonEmpty(touched, "目标 sheet「" + TARGET_SHEET + "」本次导入的三态计数合计");

            System.out.println("[T-08 反向] ✅ AC-8 反向半边成立：" + EBOM + " 带 " + SRC_QID
                    + " 列，但它不在任何 sheet 的已声明列里，既有报价 Excel 整份导入 SUCCESS（"
                    + successRows + " 行）");
        } finally {
            // 只删本用例自己建的那一条 import_record，按 id 精确删。
            // 🚫 不按条件批量删，🚫 不碰 ds_quote_* 数据（那是共享夹具轴，见回报「残留」一节）
            dropImportRecord(recId);
        }
    }

    /** 既有报价 Excel 夹具 —— 表头里没有「来源报价单 id」，正是 AC-8 反向半边要的那种。 */
    private static final String LEGACY_QUOTE_XLSX = "T260907-主文件-CUST0004.xlsx";
    private static final String LEGACY_QUOTE_CUSTOMER = "CUST-0004";
    /** 该夹具里落到 {@link #EBOM}（唯一被前提守卫证明带 source_quotation_id 的表）的那张 sheet。 */
    private static final String TARGET_SHEET = "物料与元素BOM";

    /** 夹具里所有销售料号共用的前缀 —— 本守卫的扫描轴。 */
    private static final String LEGACY_AXIS_PREFIX = "T260907T-";

    /**
     * <b>守卫④ · 污染前置：导入前 {@link #LEGACY_AXIS_PREFIX} 轴必须是 0 行。</b>
     *
     * <h3>🔑 它和上面三条守卫方向相反</h3>
     * 守卫①②③ 是<b>让用例在功能坏了时红</b>；本条是<b>让用例在自己会造成污染时红</b>。
     * 🚫 不要把它当成「让用例更绿」的一环 —— 它只会让用例更容易红，那正是它的目的。
     *
     * <h3>为什么必须有</h3>
     * 本夹具打的是 <b>VERSIONED</b> 表（{@code 物料与元素BOM} 的 summary 就带
     * {@code created/upgraded/unchanged} 三态）。若在一个<b>已经存在该轴行</b>的库上跑：
     * <ul>
     *   <li>导入<b>不会报错</b>，而是把别人的行<b>升一版</b>；</li>
     *   <li>本用例的断言（{@code SUCCESS} + 行数 + 不在 persistedColumns）<b>照样全绿</b>；</li>
     *   <li>症状是「别人的数据莫名多了一版」，几个月后才被发现，且极难归因。</li>
     * </ul>
     * ⇒ 「导入前该轴 0 行」是这一次的<b>事实</b>，不是这个用例的<b>性质</b>；
     * 不把它写成断言，性质就只是运气。
     *
     * <h3>🚫 不清、不跳</h3>
     * 发现非 0 <b>只报不清</b>：清掉就等于删别人的行（{@code CLAUDE.md §3.2 环境销毁}），
     * 而本用例<b>没有批准权</b>。也<b>不许</b>改成 skip —— skip 在 surefire 汇总里长得和通过一样。
     */
    private void assertLegacyAxisPristine() {
        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) (List<?>) col(
                "SELECT table_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name LIKE 'ds\\_quote\\_%' "
                        + "  AND table_name NOT LIKE '%\\_history' AND table_name NOT LIKE '%\\_record' "
                        + "  AND column_name='material_no' ORDER BY 1");
        // 🚨 扫到 0 张表 ⇒ 下面的循环空跑，守卫恒真。先证明扫描面非空。
        assertFixtureNonEmpty(tables.size(),
                "带 material_no 的 ds_quote_* 主表数（0 张则本守卫恒真、等于没有）");

        List<String> dirty = new java.util.ArrayList<>();
        long total = 0;
        for (String t : tables) {
            long n = count("SELECT count(*) FROM " + sqlSafe(t)
                    + " WHERE material_no LIKE '" + LEGACY_AXIS_PREFIX + "%'");
            if (n > 0) {
                dirty.add(t + "=" + n + " 行");
                total += n;
            }
        }
        System.out.println("[T-08 反向] 守卫④ 污染前置：扫 " + tables.size() + " 张主表，轴 "
                + LEGACY_AXIS_PREFIX + " 实测 " + total + " 行" + (dirty.isEmpty() ? "（干净 ✅）" : " " + dirty));
        assertEquals(0L, total,
                "⛔ 污染前置未满足（**不是被测功能的结论**）：本库已存在 " + LEGACY_AXIS_PREFIX
                        + " 轴数据，共 " + total + " 行 —— " + dirty
                        + "。本用例的夹具打的是 VERSIONED 表，在这种库上导入**不会报错**，"
                        + "而是把这些行**升一版**；届时本用例的断言照样全绿，"
                        + "污染要几个月后才会以「别人的数据莫名多了一版」的形态暴露出来。"
                        + " ⇒ 请换一个该轴为空的库，或换一套轴值。"
                        + " 🚫 不要清掉这些行来让用例变绿 —— 那可能是别人的数据（CLAUDE.md §3.2），"
                        + "本用例没有批准权。");
    }

    private java.nio.file.Path fixtureXlsx(String name) {
        java.nio.file.Path cur = java.nio.file.Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            java.nio.file.Path p = cur.resolve("dev-docs")
                    .resolve("task-260907-报价导入建单切ds新表").resolve("测试数据").resolve(name);
            if (java.nio.file.Files.isRegularFile(p)) {
                return p;
            }
            cur = cur.getParent();
        }
        throw new AssertionError("找不到夹具 " + name + "（用例环境问题，不是业务结论），cwd="
                + java.nio.file.Path.of("").toAbsolutePath());
    }

    private String requireCustomerId(String code) {
        Object v = scalar("SELECT id::text FROM customer WHERE code = '" + code + "'");
        assertNotNull(v, "⛔ 环境前置：客户 " + code + " 不存在于本库 ⇒ 导入必然失败在客户校验上，"
                + "与 AC-8 无关。请换库或补夹具客户。");
        return v.toString();
    }

    /** 轮询到终态；🚫 超时不当通过 —— 超时就是超时。 */
    private JsonNode awaitImportFinal(String recordId) {
        long deadline = System.currentTimeMillis() + 180_000L;
        io.restassured.response.Response last = null;
        while (System.currentTimeMillis() < deadline) {
            last = io.restassured.RestAssured.given().cookies(adminCookies())
                    .when().get("/api/cpq/dataset/quote/quotation-import/{id}", recordId).thenReturn();
            assertEquals(200, last.statusCode(),
                    "api.md §2 轮询应 200，实际 " + last.statusCode() + "：" + last.asString());
            JsonNode data = json(last).path("data");
            String st = data.path("status").asText();
            if ("SUCCESS".equals(st) || "FAILED".equals(st)) {
                return data;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("180s 未到终态 —— 这是超时不是通过。最后响应："
                + (last == null ? "null" : last.asString()));
    }

    /** 按 id 精确删本用例建的 import_record（先解 quotation 外键引用）。 */
    private void dropImportRecord(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return;
        }
        try {
            inTx(() -> {
                em.createNativeQuery("UPDATE import_record SET quotation_id = NULL "
                        + "WHERE id = CAST(:id AS uuid)").setParameter("id", recordId).executeUpdate();
                em.createNativeQuery("DELETE FROM import_record WHERE id = CAST(:id AS uuid)")
                        .setParameter("id", recordId).executeUpdate();
            });
        } catch (RuntimeException e) {
            // 清理失败不许盖掉用例真正的失败原因
            System.out.println("[T-08 反向] ⚠️ 清理 import_record " + recordId + " 失败：" + e);
        }
    }

    private static UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(
                "⛔ 待接实现（**不是被测功能的结论**）：" + what
                        + "。缺的契约信息已在测试回报的「缺什么」清单里列给主线。");
    }
}
