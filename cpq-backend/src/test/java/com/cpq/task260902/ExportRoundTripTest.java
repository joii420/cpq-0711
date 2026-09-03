package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · E 组 · <b>回环测试</b>（T-19 / T-20，AC-19 / AC-20）。
 *
 * <p>「可回导」是本任务的价值所在 —— 只有真跑一遍导出 → 回导才算验过（test.md §1）。
 * 本类是全套用例的<b>核心</b>，也是 test.md §5① 证伪实验的主靶子。
 *
 * <h3>🚨 证伪实验（实现落地后、闸门 B 之前必做）</h3>
 * <pre>
 * FT-A · 含量口径（AC-5 的命门，AC-19 的前提）
 *   破坏：把材质导出侧「default_pct ÷ 100」改回不除（直接写库值 84 / 16）。
 *   跑  ：./mvnw test -Dtest=ExportRoundTripTest#t19_exportThenReimport_isFullyIdempotent
 *   期望：❌ 硬失败，且失败信息是「回导报告出现大量含量非法的 skip」而不是别的。
 *   🚫 不变红 = 本用例根本没在验回导，是空验证，必须重写。
 *
 * FT-B · 只读参考列的位置（AC-4 / AC-6）
 *   破坏：把「材质编号」列挪到第 1 列（即只读列插到前 4 列之前）。
 *   跑  ：同上 + MaterialExportApiTest#t04_exportHeader_first4ColumnsMatchImportTemplateByPosition
 *   期望：❌ 两个都变红 —— t04 报表头位置不对，t19 报回导 400 IMPORT_HEADER_INVALID。
 *   只红一个 ⇒ 说明另一条用例没真正走到那条路径，报主线。
 * </pre>
 *
 * <h3>⚠️ 本类是唯一「可能改动真实数据」的用例，处置见 {@link #t19_exportThenReimport_isFullyIdempotent}</h3>
 */
@QuarkusTest
class ExportRoundTripTest extends Task260902TestBase {

    private static final String EXPORT = "/api/cpq/material-recipes/export";
    private static final String IMPORT = "/api/cpq/material-recipes/import";

    /** T-20 专用的自建材质（🚫 刻意不拿真实的 301/Cu/301 当写入靶子，理由见 t20 的注释）。 */
    private static final String RT_SYMBOL = RECIPE_SYMBOL_PREFIX + "回环";
    private static final String RT_CODE_SUFFIX = "RT";

    // ═══════════════════ T-19 → AC-19：导出 → 原样回导 = 幂等 ═══════════════════

    /**
     * T-19 → AC-19（序列 AC，2026-09-02 用户裁决后的口径）：
     * <b>先把「状态」筛为「启用」再导出</b> → <b>一个字节不改</b>直接回导 ⇒
     * 报告显示<b>新增材质 0、新增含量配置 0</b>（跳过数 == 文件里的配置组数）；
     * 回导前后 <b>基准查询②a</b> 与 {@code material_recipe_config} 的 count <b>差值均为 0</b>。
     *
     * <p>🚨 <b>为什么必须先筛「启用」</b>：材质导入按 {@code symbol AND status='ACTIVE'} 匹配既有材质
     * （task-260901 既有语义）。库里的<b>停用</b>材质（实测 {@code SnO2-del}/{@code 00263}，带 1 组启用配置）
     * 导出后回导时匹配不上 ⇒ 会被当成新材质<b>新建一条同名的启用材质</b>。
     * ⇒ <b>「不筛选就回导 ≠ 零新增」是预期行为</b>（既有导入语义 × 导出功能的组合），
     * 用户已裁决<b>不改导入逻辑</b>，靠导入抽屉的提醒 + 报告里的「新增材质」计数暴露。
     * 🚫 不要把它当 bug 报；也 🚫 不要写一条「不筛选回导」的用例去证实它 ——
     * 那会真的在共享库里新建一条重复材质，属不可逆污染。
     *
     * <p>本条同时验证 AC-6 的只读参考列（第 5~8 列）<b>被导入端忽略</b> ——
     * 如果导入端把它们当数据列吃了，回导必然产生新材质或新配置，差值就不是 0。
     *
     * <p>🚨 <b>风险登记（唯一一条可能改动真实数据的用例）</b>：
     * 若导出实现有 bug（例如含量没 ÷100 之外的形态错误），回导<b>可能真的往共享库写入新材质/新配置</b>。
     * 因此本用例在回导前后各拍一次 <b>id 集合快照</b>，一旦出现新增就把<b>具体 id 与 config_no 打印出来</b>
     * 并硬失败 —— 让主线拿着精确的 WHERE 清理，而不是面对一句「数字对不上」。
     * 🚫 本用例不自行删除这些行（它们落在真实材质上，删除面不明 = CLAUDE.md §3.2 红线）。
     */
    @Test
    void t19_exportThenReimport_isFullyIdempotent() {
        // 🚨 AC-19 口径：先筛「启用」再导出（理由见方法注释）
        byte[] exported = export("status", "ACTIVE");
        List<List<String>> rows = dataRows(exported);
        assertTrue(!rows.isEmpty(),
                "AC-19 前置：导出 0 行 —— 回导等于导了个空文件，整条用例会空跑（假绿）");
        // 反假绿：筛「启用」的导出里不许混进停用材质，否则回导必然新建同名材质，
        // 而那个失败会被读成「导入坏了」，实际是导出的 status 参数没生效。
        Map<String, Integer> hidx = headerIndex(exported);
        for (List<String> r : rows) {
            assertEquals("启用", cell(r, hidx, "状态"),
                    "AC-19 前置：status=ACTIVE 的导出里混进了停用材质「" + cell(r, hidx, "材质")
                    + "」—— 导出的 status 参数没生效，回导必然新建同名材质");
        }
        Map<String, Integer> idx = headerIndex(exported);

        // 文件里的「配置组数」= (材质, 组号) 去重后的个数 —— AC-19 的「跳过数」期望值
        Set<String> groups = new LinkedHashSet<>();
        for (List<String> r : rows) groups.add(cell(r, idx, "材质") + "~#~" + cell(r, idx, "组号"));
        System.out.println("[AC-19] 导出行数 = " + rows.size() + "，配置组数 = " + groups.size());

        // ── 基线快照（🚨 与回导之间不做别的操作，共享库有并发写入，间隔越长干扰越大）──
        long recipeBefore = count("SELECT count(*) FROM material_recipe");
        long configBefore = configCount();
        long rowsActiveBefore = baseRecipeElementRowCountActiveOnly();   // 基准查询②a
        Set<String> recipeIdsBefore = new LinkedHashSet<>(strList("SELECT id::text FROM material_recipe"));
        Set<String> configIdsBefore = new LinkedHashSet<>(strList("SELECT id::text FROM material_recipe_config"));

        Response res = given()
                .multiPart("file", "材质库回导.xlsx", exported, XLSX_MIME)
                .post(IMPORT).thenReturn();
        System.out.println("[AC-19] 回导 status = " + res.statusCode());
        assertEquals(200, res.statusCode(),
                "AC-19：🚨 导出的文件必须能被导入端接受 —— 实际 " + res.statusCode()
                + "。400 IMPORT_HEADER_INVALID ⇒ 只读参考列插错了位置（AC-4/AC-6）；"
                + "body=" + res.asString());

        long recipeAfter = count("SELECT count(*) FROM material_recipe");
        long configAfter = configCount();
        long rowsActiveAfter = baseRecipeElementRowCountActiveOnly();    // 基准查询②a

        Map<String, Object> rep = report(res.jsonPath());
        System.out.println("[AC-19] 回导报告 = " + rep);

        // ── ① 报告口径：零新增（AC-19 的核心） ──
        assertEquals(0, intOf(rep, "recipesCreated"),
                "AC-19：🚨 原样回导必须新增材质 0 条，实际 " + rep.get("recipesCreated")
                + " —— 说明导出的「材质」列与导入端的查重键（symbol）对不上，"
                + "或导出里混进了停用材质（导入按 symbol AND status='ACTIVE' 匹配）");
        assertEquals(0, intOf(rep, "configsCreated"),
                "AC-19：🚨 原样回导必须新增含量配置 0 组，实际 " + rep.get("configsCreated")
                + " —— 说明回导后的组被判成了「与现有组不同」，最可能是含量口径（÷100）没对上");

        // ── ② 🚨 含量口径的证伪探针（test.md §5① FT-A 的靶心）──
        //    把导出侧的「÷100」改回不除，回导会**逐行**报「含量非法」（pctInRange 只收 (0,1]）。
        //    这条断言就是那个实验的判据：它必须是**红的**，而不是被别的断言先拦下。
        List<Map<String, Object>> skippedRows = listOf(rep, "skipped");
        List<String> pctReasons = new ArrayList<>();
        for (Map<String, Object> sk : skippedRows) {
            String reason = String.valueOf(sk.get("reason"));
            if (reason.contains("含量")) pctReasons.add(reason + " @ " + sk.get("raw"));
        }
        assertTrue(pctReasons.isEmpty(),
                "AC-19/AC-5：🚨 回导出现了「含量」类拒收 " + pctReasons.size() + " 条 —— "
                + "导出的含量口径没有 ÷100（库里存百分数 84，导入端 pctInRange 只收 (0,1]），"
                + "「可回导」这个功能等于白做。前 3 条 = "
                + pctReasons.subList(0, Math.min(3, pctReasons.size())));

        // ── ③ 库内增量（AC-19 原文的硬判据）──
        //    🚨 刻意排在「跳过数记账」之前：记账断言若先红，就看不到库到底有没有被改坏了。
        System.out.printf("[AC-19] material_recipe: %d → %d ｜ material_recipe_config: %d → %d "
                        + "｜ 基准查询②a: %d → %d%n",
                recipeBefore, recipeAfter, configBefore, configAfter, rowsActiveBefore, rowsActiveAfter);
        assertEquals(0, rowsActiveAfter - rowsActiveBefore,
                "AC-19：🚨 基准查询②a（只算启用材质的行数）的增量必须为 0（"
                + rowsActiveBefore + "→" + rowsActiveAfter + "）");
        if (recipeAfter != recipeBefore || configAfter != configBefore) {
            List<String> newRecipes = strList(
                    "SELECT id::text || ' / ' || code || ' / ' || symbol FROM material_recipe " +
                    "WHERE id::text NOT IN (" + quoteIn(recipeIdsBefore) + ")");
            List<String> newConfigs = strList(
                    "SELECT id::text || ' / ' || config_no FROM material_recipe_config " +
                    "WHERE id::text NOT IN (" + quoteIn(configIdsBefore) + ")");
            throw new AssertionError(
                "AC-19：🚨 原样回导改动了共享库！material_recipe " + recipeBefore + "→" + recipeAfter
                + "，material_recipe_config " + configBefore + "→" + configAfter + "。\n"
                + "本用例刻意不自行删除（落在真实材质上，删除面不明 = CLAUDE.md §3.2 红线）。\n"
                + "请主线按下列精确 id 清理：\n  新增材质 = " + newRecipes + "\n  新增配置 = " + newConfigs);
        }

        // ── ④ 记账：文件里的每一组都必须被「认领」，不许静默消失 ──
        //    AC-19 原文写的是「跳过数 == 文件里的配置组数」。实测有一类组走的是**另一个出口**：
        //    元素集合与该材质的 `material_recipe_composition` 对不上的组，会被导入端按 task-260901
        //    既有规则「元素组合与该材质的元素组成不一致」拒收，而不是记成「重复」。
        //    2026-09-02 首次实测命中 1 组（材质 `SnO2` / 配置 `00262-02`），但**同类脏行不止一条**
        //    （主线另查到 `WZHF20-11`/`00244` 的 `element_code='223'`；它当前恰好两边一致所以不触发）。
        //    🚫 因此**绝不能写死「只有 1 条」** —— 那会在别的脏行浮上来时变成假红。
        //    🚨 <b>根因是既有脏数据，不是本任务的缺陷</b>：那条 composition 行三列错位 ——
        //       `element_code='10004'`（本该是 `Sn`）、`element_name='Sn'`（本该是 `锡`），
        //       `10004` 正是 Sn 的 element_no。导出如实导出、导入如实拒收，两边都没错。
        //    ⇒ 这里不写死 1，而是**现场用 SQL 数出这类组有几个**，断言「重复数 + 这类数 == 总组数」。
        //       这与 AC 原文等强（没有任何一组会静默消失），且一旦出现**新的**拒收原因就会变红。
        long inconsistentGroups = count(
                "SELECT count(*) FROM material_recipe r JOIN material_recipe_config c ON c.recipe_id = r.id " +
                " WHERE r.status='ACTIVE' AND c.status='ACTIVE' " +
                "   AND coalesce((SELECT string_agg(x.element_code, ',' ORDER BY x.element_code) " +
                "                   FROM material_recipe_composition x WHERE x.recipe_id = r.id), '') " +
                "    IS DISTINCT FROM (SELECT string_agg(e.element_code, ',' ORDER BY e.element_code) " +
                "                        FROM material_recipe_element e WHERE e.config_id = c.id)");
        int dup = intOf(rep, "configsSkippedAsDuplicate");
        System.out.printf("[AC-19] 记账：重复 %d + 元素组成不一致 %d = %d ｜ 文件里的配置组数 %d%n",
                dup, inconsistentGroups, dup + inconsistentGroups, groups.size());
        assertEquals(groups.size(), dup + inconsistentGroups,
                "AC-19：文件里的每一组都必须被认领（重复 " + dup + " + 元素组成不一致 "
                + inconsistentGroups + " 应 == 总组数 " + groups.size() + "）。"
                + "对不上 = 有组被静默丢弃，或出现了新的拒收原因；完整报告 = " + rep);
        assertEquals(inconsistentGroups, skippedRows.size(),
                "AC-19：被跳过的行数应恰好等于库里「元素组成不一致」的组数 " + inconsistentGroups
                + "，实际 " + skippedRows.size() + " —— 多出来的就是本次引入的新问题，明细 = " + skippedRows);

        // ── ⑤ 再导出一次，与首次导出逐单元格相同（AC-19「刷新页面后逐字相同」的接口层等价物）──
        //    ⚠️ 比的是**数据**不是字节：POI 每次会把生成时间戳写进 docProps/core.xml，
        //       两份内容相同的 xlsx 其 sha256 必然不同 —— 断言字节相等在 POI 下不可能成立。
        List<List<String>> again = dataRows(export("status", "ACTIVE"));
        System.out.println("[AC-19] 二次导出行数 = " + again.size() + "（首次 " + rows.size() + "）");
        assertEquals(rows.size(), again.size(),
                "AC-19：回导后再导出的行数应与回导前逐字相同");
        assertEquals(rows, again,
                "AC-19：回导后再导出的内容应与回导前【逐单元格】相同（真正的幂等）");
    }

    // ═══════════════════ T-20 → AC-20：导出 → 改动 → 回导 ═══════════════════

    /**
     * T-20 → AC-20（序列 AC）：在导出文件里给某材质追加 2 行（组号 2、两个元素、含量 0.8 / 0.2）
     * ⇒ 回导报告新增含量配置 <b>1</b> 组；该材质的含量配置数由 1 变 <b>2</b>；
     * 再次导出该材质有 <b>4</b> 行、组号分别为 1、1、2、2。
     *
     * <p>🚨 <b>刻意不拿真实的 {@code 301/Cu/301} 当写入靶子</b>（AC-20 原文点了它，但那是「举例」）：
     * 往真实材质上追加一组配置 = 改动共享库里的<b>公共基础数据</b>（{@code material_recipe.code/name}
     * 被 107 个组件 SQL 视图 JOIN 引用），且新配置 {@code 00168-02} 的 seq 水位<b>不回收</b>
     * （task-260901 M-2），删掉也复原不了编号。testing.md §4.3 要求「必须改的，用例自己还原」——
     * 这一条在真实材质上做不到。
     * ⇒ 本用例改用<b>自建的 ACTIVE 材质</b>（元素组成与元素行都造齐，走的是完全相同的
     * 「已存在材质 + 新增一组」代码路径），跑完精确删除。
     * <b>AC-20 的「301/Cu/301 上 1→2 组、再导出 4 行」由主线在亲验时手工走一遍。</b>
     *
     * <p><b>登记（test.md §1 全局状态）</b>：{@code material_recipe} / {@code _composition} /
     * {@code _config} / {@code _element} 各写入 1/2/1/2 行，编号 {@code T260902-RT}，
     * 回导后再多 1 组配置 + 2 行元素，全部在 {@code @AfterEach} 按前缀删除。
     */
    @Test
    void t20_appendGroupInExportedFile_thenReimport_createsExactlyOneConfig() {
        seedRecipeWithComposition();
        String code = RECIPE_CODE_PREFIX + RT_CODE_SUFFIX;

        // 前置：自建材质此刻恰有 1 组配置、2 行元素
        assertEquals(1, count("SELECT count(*) FROM material_recipe_config c " +
                        "JOIN material_recipe r ON r.id=c.recipe_id " +
                        "WHERE r.code='" + code + "' AND c.status='ACTIVE'"),
                "AC-20 前置：自建材质应恰有 1 组 ACTIVE 配置");

        // 🚨 与 AC-19 同一口径：必须筛「启用」再导出。
        //    本用例会把**整份文件**回导，若含停用材质（实测 SnO2-del），
        //    导入端按 symbol AND status='ACTIVE' 匹配不上 ⇒ 会在共享库里新建一条同名启用材质
        //    ⇒ 不可逆污染，且 configsCreated 会变成 2 让 AC-20 的断言红得莫名其妙。
        byte[] exported = export("status", "ACTIVE");
        Map<String, Integer> idx = headerIndex(exported);
        for (List<String> r : dataRows(exported)) {
            assertEquals("启用", cell(r, idx, "状态"),
                    "AC-20 前置：待回导的文件里混进了停用材质「" + cell(r, idx, "材质")
                    + "」—— 回导会新建同名材质，污染共享库");
        }
        List<List<String>> mine = new ArrayList<>();
        for (List<String> r : dataRows(exported)) {
            if (RT_SYMBOL.equals(cell(r, idx, "材质"))) mine.add(r);
        }
        assertNonEmpty(mine, "AC-20 前置：导出文件里自建材质 " + RT_SYMBOL + " 的行");
        assertEquals(2, mine.size(),
                "AC-20 前置：自建材质应在导出文件里恰有 2 行（1 组 × 2 元素），实际 " + mine.size()
                + " —— 不是 2 就说明导出的行集合口径不对，后面的 4 行断言会失去意义");

        // 在导出文件末尾追加 2 行：组号 2、Cu 0.8 / Ni 0.2（AC-20 的「追加一组」）
        byte[] modified = appendRows(exported, List.of(
                List.of(RT_SYMBOL, "2", "Cu", "0.8"),
                List.of(RT_SYMBOL, "2", "Ni", "0.2")));

        long configBefore = configCount();
        Response res = given()
                .multiPart("file", "材质库改动回导.xlsx", modified, XLSX_MIME)
                .post(IMPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "AC-20：改动后的文件仍应被接受，实际 " + res.statusCode() + " body=" + res.asString());
        long configAfter = configCount();

        Map<String, Object> rep = report(res.jsonPath());
        System.out.println("[AC-20] 回导报告 = " + rep);
        assertEquals(1, intOf(rep, "configsCreated"),
                "AC-20：🚨 只追加了 1 组 ⇒ 新增含量配置必须恰为 1，实际 " + rep.get("configsCreated"));
        assertEquals(0, intOf(rep, "recipesCreated"),
                "AC-20：材质本来就存在 ⇒ 不许新建材质，实际 " + rep.get("recipesCreated"));
        assertEquals(1, configAfter - configBefore,
                "AC-20：material_recipe_config 的增量必须恰为 1（" + configBefore + "→" + configAfter + "）");

        // 该材质的含量配置数 1 → 2
        long cfgOfMine = count("SELECT count(*) FROM material_recipe_config c " +
                "JOIN material_recipe r ON r.id=c.recipe_id " +
                "WHERE r.code='" + code + "' AND c.status='ACTIVE'");
        System.out.println("[AC-20] 自建材质的 ACTIVE 配置组数 = " + cfgOfMine);
        assertEquals(2, cfgOfMine, "AC-20：该材质的「含量配置数」应由 1 变 2");

        // 再次导出：该材质 4 行，组号 1,1,2,2
        byte[] reExported = export("status", "ACTIVE");
        Map<String, Integer> idx2 = headerIndex(reExported);
        List<String> seqs = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        for (List<String> r : dataRows(reExported)) {
            if (!RT_SYMBOL.equals(cell(r, idx2, "材质"))) continue;
            seqs.add(cell(r, idx2, "组号"));
            pairs.add(cell(r, idx2, "组号") + ":" + cell(r, idx2, "元素符号") + "=" + cell(r, idx2, "含量"));
        }
        System.out.println("[AC-20] 再次导出该材质 = " + pairs);
        assertEquals(4, seqs.size(),
                "AC-20：再次导出时该材质必须有 4 行，实际 " + seqs.size() + " 行：" + pairs);
        assertEquals(List.of("1", "1", "2", "2"), seqs,
                "AC-20：4 行的组号应依次为 1、1、2、2（按 symbol, seq, sort_order 排序），实际 " + seqs);

        // 新组的含量回到文件里仍是 0.8 / 0.2（÷100 口径闭环）
        assertTrue(pairs.contains("2:Cu=0.8"),
                "AC-20：新组的 Cu 含量再导出应仍为 0.8（库里存 80，导出 ÷100），实际 " + pairs);
        assertTrue(pairs.contains("2:Ni=0.2"),
                "AC-20：新组的 Ni 含量再导出应仍为 0.2，实际 " + pairs);
        assertEquals("80.000000000000", scalar(
                "SELECT e.default_pct::text FROM material_recipe_element e " +
                "  JOIN material_recipe_config c ON c.id=e.config_id " +
                "  JOIN material_recipe r ON r.id=c.recipe_id " +
                " WHERE r.code='" + code + "' AND c.seq=2 AND e.element_code='Cu'"),
                "AC-20：文件写 0.8 ⇒ 库里应落 default_pct=80（导入端 ×100）");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private byte[] export(String... kv) {
        var req = given();
        for (int i = 0; i + 1 < kv.length; i += 2) req = req.queryParam(kv[i], kv[i + 1]);
        Response res = req.when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "导出应 200，实际 " + res.statusCode() + " body=" + res.asString());
        return res.asByteArray();
    }

    /** 造一条 ACTIVE 自建材质：元素组成（composition）+ 1 组配置 + 2 行元素，元素编号取真实 element 主表。 */
    private void seedRecipeWithComposition() {
        String code = RECIPE_CODE_PREFIX + RT_CODE_SUFFIX;
        seedRecipe(RT_CODE_SUFFIX, RT_SYMBOL, "locked", "ACTIVE", "Cu", "60", "Ni", "40");
        QuarkusTransaction.requiringNew().run(() -> {
            // 元素组成必须与元素行一致 —— 导入端会拿「组的元素集合」与它比对（task-260901 M-0）
            em.createNativeQuery(
                "INSERT INTO material_recipe_composition (recipe_id, element_no, element_code, element_name, sort_order) " +
                "SELECT r.id, e.element_no, e.element_code, e.element_name, " +
                "       (CASE e.element_code WHEN 'Cu' THEN 1 ELSE 2 END) " +
                "  FROM material_recipe r, element e " +
                " WHERE r.code = :code AND e.element_code IN ('Cu','Ni')")
              .setParameter("code", code).executeUpdate();
            // 元素行也补上 element_no，与主表对齐
            em.createNativeQuery(
                "UPDATE material_recipe_element me SET element_no = e.element_no " +
                "  FROM element e, material_recipe_config c, material_recipe r " +
                " WHERE me.element_code = e.element_code AND me.config_id = c.id " +
                "   AND c.recipe_id = r.id AND r.code = :code")
              .setParameter("code", code).executeUpdate();
        });
        List<String> comp = strList(
                "SELECT c.element_code FROM material_recipe_composition c " +
                "  JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code='" + code + "' ORDER BY c.sort_order");
        assertEquals(List.of("Cu", "Ni"), comp,
                "AC-20 前置：自建材质的元素组成应恰为 [Cu, Ni]，实际 " + comp
                + " —— 造数没成功，后续断言会失去意义");
    }

    /** 在 xlsx 末尾追加数据行（只填前 4 列 —— 只读参考列回导时被忽略，AC-6）。 */
    private byte[] appendRows(byte[] xlsx, List<List<String>> extra) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.getSheetAt(0);
            int next = sh.getLastRowNum() + 1;
            for (List<String> line : extra) {
                Row r = sh.createRow(next++);
                r.createCell(0).setCellValue(line.get(0));
                r.createCell(1).setCellValue(Double.parseDouble(line.get(1)));
                r.createCell(2).setCellValue(line.get(2));
                r.createCell(3).setCellValue(Double.parseDouble(line.get(3)));
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("追加行失败", e);
        }
    }

    private String quoteIn(Set<String> ids) {
        if (ids.isEmpty()) return "''";
        List<String> q = new ArrayList<>();
        for (String i : ids) q.add("'" + i + "'");
        return String.join(",", q);
    }
}
