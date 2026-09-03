package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · A 组 · 材质导出（T-04 ~ T-08，AC-4 / AC-5 / AC-6 / AC-7 / AC-8）。
 *
 * <p>断言全部派生自 {@code 需求文档.md §③ A 组} 的 AC 原文 + {@code api.md § B-1}。<b>不读实现</b>。
 *
 * <p>🚨 <b>本类每条用例都打开 xlsx 读单元格再断言</b>（test.md §5③）。
 * 只断言 {@code status==200} 或 {@code body.length>0} 的用例视为未覆盖，本类里一条都没有。
 */
@QuarkusTest
class MaterialExportApiTest extends Task260902TestBase {

    private static final String EXPORT = "/api/cpq/material-recipes/export";

    /** 导出文件的 8 个列名，逐字取自 api.md § B-1 的响应体表。前 4 列的<b>位置</b>不可换。 */
    private static final List<String> EXPECTED_HEADER = List.of(
            "材质", "组号", "元素符号", "含量", "材质编号", "含量配置编号", "状态", "含量类型");

    // ═══════════════════════ T-04 → AC-4：可回导·表头 ═══════════════════════

    /**
     * T-04 → AC-4：第 1 行前 4 个单元格逐字为 材质/组号/元素符号/含量，<b>顺序不可换</b>。
     * <p>依据：导入端 {@code validateHeader} 按<b>位置</b>逐列比对，错一位即 400 {@code IMPORT_HEADER_INVALID}
     * （需求文档 §④ 依赖表实测结论）。
     */
    @Test
    void t04_exportHeader_first4ColumnsMatchImportTemplateByPosition() {
        Response res = given().when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "AC-4：管理员导出应 200，实际 " + res.statusCode() + " body=" + res.asString());

        // api.md 全局约定：attachment + .xlsx 文件名（ASCII）
        String cd = res.header("Content-Disposition");
        System.out.println("[AC-4] Content-Disposition = " + cd);
        assertNotNull(cd, "AC-4/api.md：导出必须带 Content-Disposition");
        assertTrue(cd.contains("attachment"), "api.md：必须是 attachment 下载，实际=" + cd);
        assertTrue(cd.contains(".xlsx"), "api.md：文件名必须以 .xlsx 结尾，实际=" + cd);

        byte[] xlsx = res.asByteArray();
        List<String> h = header(xlsx);
        System.out.println("[AC-4] 表头 = " + h);
        assertTrue(h.size() >= 4, "AC-4：表头不足 4 列，实际=" + h);
        assertEquals(List.of("材质", "组号", "元素符号", "含量"), h.subList(0, 4),
                "AC-4：🚨 前 4 列必须逐字且按位置为 材质/组号/元素符号/含量 —— "
                + "错一位导入端就 400 IMPORT_HEADER_INVALID，整个「可回导」就白做了");
    }

    // ═══════════════════ T-05 → AC-5：可回导·含量口径（÷100）═══════════════════

    /**
     * T-05 → AC-5：<b>不变量</b> —— 每个「含量」单元格 × 100 == 库中该元素 {@code default_pct}，
     * 且全表任一含量值满足 {@code 0 < 值 ≤ 1}。
     *
     * <p>🚨 这条是「可回导」的命门：导入端 {@code pctInRange(v, 1)} 只接受 {@code (0,1]}，
     * 导出若照搬库值（84 / 16），回导时<b>每一行</b>都会被判「含量非法」。
     *
     * <p>🚨 <b>本用例是证伪实验（test.md §5①）的靶子之一</b>：把导出侧的「÷100」改回不除，
     * 本用例必须<b>硬失败</b>，且失败信息里会打印实际读到的 84 / 16。不变红 = 本用例是空验证。
     */
    @Test
    void t05_contentPercent_isDbValueDividedBy100_andWithin0to1() {
        byte[] xlsx = given().when().get(EXPORT).then().statusCode(200).extract().asByteArray();
        Map<String, Integer> idx = headerIndex(xlsx);
        List<List<String>> rows = assertNonEmptyRows(xlsx, "AC-5：导出的数据行");

        // ① 全表不变量：0 < 含量 ≤ 1
        BigDecimal one = BigDecimal.ONE, zero = BigDecimal.ZERO;
        int checked = 0;
        for (List<String> r : rows) {
            String txt = cell(r, idx, "含量");
            BigDecimal v = decimal(txt, "AC-5 含量单元格(" + cell(r, idx, "材质") + "/" + cell(r, idx, "元素符号") + ")");
            assertTrue(v.compareTo(zero) > 0 && v.compareTo(one) <= 0,
                    "AC-5：含量必须落在 (0,1]，实际 " + v + " —— 材质=" + cell(r, idx, "材质")
                    + " 元素=" + cell(r, idx, "元素符号")
                    + "。🚨 出现 >1 说明导出照搬了库里的百分数（default_pct），回导会被逐行判非法");
            checked++;
        }
        System.out.println("[AC-5] (0,1] 区间校验通过的含量单元格数 = " + checked);
        assertTrue(checked > 0, "AC-5：一个含量单元格都没校验到 —— 断言空跑（假绿）");

        // ② 逐行不变量：cell × 100 == default_pct（与导出同一时刻的库值比对，🚫 不写死 84/16）
        Map<String, String> dbPct = new java.util.LinkedHashMap<>();
        for (String line : strList(
                "SELECT c.config_no || '~#~' || e.element_code || '~#~' || e.default_pct::text " +
                "  FROM material_recipe_element e " +
                "  JOIN material_recipe_config c ON c.id = e.config_id " +
                "  JOIN material_recipe r ON r.id = c.recipe_id " +
                // ⚠️ 2026-09-02 口径变更：不筛选的导出是**全状态**，这里不能再限定 r.status
                " WHERE c.status='ACTIVE'")) {
            String[] p = line.split("~#~", -1);
            dbPct.put(p[0] + "~#~" + p[1], p[2]);
        }
        assertFalse(dbPct.isEmpty(), "AC-5 前置：库里一行 ACTIVE 含量都没有，断言会空跑");

        int matched = 0;
        for (List<String> r : rows) {
            String key = cell(r, idx, "含量配置编号") + "~#~" + cell(r, idx, "元素符号");
            String db = dbPct.get(key);
            if (db == null) continue;   // 并发写入可能让某行在两次取数之间消失，跳过而不误判
            BigDecimal fileVal = decimal(cell(r, idx, "含量"), "AC-5 含量");
            BigDecimal expect = new BigDecimal(db).divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP);
            assertEquals(0, fileVal.setScale(12, RoundingMode.HALF_UP).compareTo(expect),
                    "AC-5：🚨 含量口径错 —— " + key.replace("~#~", "/")
                    + " 库值 default_pct=" + db + "，导出应写 " + expect.stripTrailingZeros().toPlainString()
                    + "，实际写了 " + fileVal);
            matched++;
        }
        System.out.println("[AC-5] 与库值逐行比对成功的行数 = " + matched + " / 导出行数 " + rows.size());
        assertTrue(matched > 0,
                "AC-5：没有任何一行能与库值配上 —— 说明「含量配置编号」列或元素符号列取错了，断言空跑（假绿）");

        // ③ AC-5 的验证样例（立项当日实测 301/Cu/301：Cu=84、301=16 ⇒ 文件里必须是 0.84 / 0.16）。
        //    ⚠️ 若该材质已被改动，判据不变、样例可换 —— 所以这里不写死 0.84，而是现场取库值算期望。
        List<String> sampleRows = new ArrayList<>();
        for (List<String> r : rows) if (SAMPLE_RECIPE_SYMBOL.equals(cell(r, idx, "材质"))) {
            sampleRows.add(cell(r, idx, "元素符号") + "=" + cell(r, idx, "含量"));
        }
        assertNonEmpty(sampleRows, "AC-5 样例材质 " + SAMPLE_RECIPE_SYMBOL + " 在导出文件里的行");
        List<String> expectSample = strList(
                "SELECT e.element_code || '=' || " +
                "  trim(trailing '.' from trim(trailing '0' from (e.default_pct/100)::text)) " +
                "  FROM material_recipe_element e " +
                "  JOIN material_recipe_config c ON c.id=e.config_id " +
                "  JOIN material_recipe r ON r.id=c.recipe_id " +
                " WHERE r.symbol='" + SAMPLE_RECIPE_SYMBOL + "' AND c.status='ACTIVE' " +
                " ORDER BY c.seq, e.sort_order");
        System.out.println("[AC-5] 样例期望 = " + expectSample + " / 文件实际 = " + sampleRows);
        assertEquals(expectSample, sampleRows,
                "AC-5：样例材质 " + SAMPLE_RECIPE_SYMBOL + " 的含量必须是库值 ÷100 的小数（如 0.84 / 0.16），"
                + "不是 84 / 16");
    }

    // ═══════════════════ T-06 → AC-6：只读参考列 ═══════════════════

    /** T-06 → AC-6：第 5 列起依次为 材质编号 / 含量配置编号 / 状态 / 含量类型，且逐行与库值对得上。 */
    @Test
    void t06_readonlyReferenceColumns_startAtColumn5_andMatchDb() {
        byte[] xlsx = given().when().get(EXPORT).then().statusCode(200).extract().asByteArray();
        List<String> h = header(xlsx);
        System.out.println("[AC-6] 表头 = " + h);
        assertEquals(EXPECTED_HEADER, h.subList(0, Math.min(EXPECTED_HEADER.size(), h.size())),
                "AC-6：8 列表头必须逐字为 " + EXPECTED_HEADER
                + " —— 只读参考列只能放第 5 列及之后（放前面会撞坏导入端的按位置校验）");

        Map<String, Integer> idx = headerIndex(xlsx);
        List<List<String>> rows = assertNonEmptyRows(xlsx, "AC-6：导出的数据行");

        // 任取一行，其 材质编号 / 含量配置编号 与库中 material_recipe.code / material_recipe_config.config_no 逐字相同
        int verified = 0;
        for (List<String> r : rows) {
            String symbol = cell(r, idx, "材质");
            String code = cell(r, idx, "材质编号");
            String cfgNo = cell(r, idx, "含量配置编号");
            String status = cell(r, idx, "状态");
            String type = cell(r, idx, "含量类型");
            String db = scalar(
                "SELECT r.code || '~#~' || c.config_no || '~#~' || r.status || '~#~' || r.recipe_type " +
                "  FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id " +
                " WHERE c.config_no = '" + cfgNo.replace("'", "''") + "'");
            if (db == null) continue;
            String[] p = db.split("~#~", -1);
            assertEquals(p[0], code, "AC-6：材质编号列应 = material_recipe.code（配置 " + cfgNo + "）");
            assertEquals("ACTIVE".equals(p[2]) ? "启用" : "停用", status,
                    "AC-6：状态列写中文 启用/停用（材质 " + symbol + "，库值 " + p[2] + "）");
            assertEquals(recipeTypeLabel(p[3]), type,
                    "AC-6：含量类型列由 recipe_type 映射（库值 " + p[3] + "）");
            verified++;
            if (verified >= 20) break;   // 抽验 20 行足够，全表逐行比对已由 AC-5 覆盖
        }
        System.out.println("[AC-6] 抽验通过的行数 = " + verified);
        assertTrue(verified > 0,
                "AC-6：一行都没能与库配上 —— 含量配置编号列可能没写对，断言空跑（假绿）");
    }

    /** api.md § B-1：含量类型列 = recipe_type 映射。 */
    private String recipeTypeLabel(String recipeType) {
        switch (recipeType) {
            case "locked":   return "标准锁定";
            case "editable": return "含量可调";
            case "partial":  return "部分可调";
            default: throw new AssertionError("未知 recipe_type = " + recipeType + "（api.md 只定义了 3 种）");
        }
    }

    // ═══════════════════ T-07 → AC-7：跟随筛选 ═══════════════════

    /**
     * T-07 → AC-7：按「状态」筛选后导出，材质集合 == 同参数 SQL 的材质集合，且与 ACTIVE 集合<b>无交集</b>；
     * 叠加「类型」筛选后集合进一步收敛。
     *
     * <p>🚨 <b>本用例自己造数</b>：现网 {@code recipe_type} 258 条全是 {@code locked}、
     * {@code status<>'ACTIVE'} 只有 1 条（2026-09-02 实测）。不造数的话「叠加类型筛选」只能验到空集，
     * 等于没验（testing.md §3 假绿第一类）。造的两条材质在 {@code @AfterEach} 精确删除。
     *
     * <p><b>登记（test.md §1 全局状态）</b>：本用例向 {@code material_recipe} /
     * {@code material_recipe_config} / {@code material_recipe_element} 各写入 2/2/4 行，
     * 编号 {@code T260902-A} / {@code T260902-B}，状态 INACTIVE，跑完即删。
     */
    @Test
    void t07_exportFollowsStatusAndTypeFilter() {
        seedRecipe("A", RECIPE_SYMBOL_PREFIX + "停用锁定", "locked", "INACTIVE", "Cu", "60", "Ni", "40");
        seedRecipe("B", RECIPE_SYMBOL_PREFIX + "停用可调", "editable", "INACTIVE", "Cu", "70", "Ni", "30");

        // ① status=INACTIVE
        byte[] inactive = exportWith("status", "INACTIVE");
        Set<String> fileSet = materialNames(inactive);
        Set<String> dbSet = new LinkedHashSet<>(strList(
                "SELECT DISTINCT r.symbol FROM material_recipe r " +
                "  JOIN material_recipe_config c ON c.recipe_id=r.id AND c.status='ACTIVE' " +
                "  JOIN material_recipe_element e ON e.config_id=c.id " +
                " WHERE r.status <> 'ACTIVE'"));
        System.out.println("[AC-7] status=INACTIVE 文件材质集 = " + fileSet + " / 库集 = " + dbSet);
        assertNonEmpty(new ArrayList<>(dbSet), "AC-7 前置：库里 status<>ACTIVE 且有 ACTIVE 配置的材质");
        assertEquals(dbSet, fileSet,
                "AC-7：导出的材质集合必须 == 同一筛选条件下库里的材质集合");

        // 关键否定断言：不含任何 ACTIVE 材质
        Set<String> activeNames = new LinkedHashSet<>(strList(
                "SELECT symbol FROM material_recipe WHERE status='ACTIVE'"));
        assertNonEmpty(new ArrayList<>(activeNames), "AC-7 前置：ACTIVE 材质集");
        for (String n : fileSet) {
            assertFalse(activeNames.contains(n),
                    "AC-7：状态筛「停用」的导出里出现了 ACTIVE 材质「" + n + "」—— 筛选没跟上");
        }

        // ② 叠加 recipeType=editable（含量可调）
        byte[] both = exportWith("status", "INACTIVE", "recipeType", "editable");
        Set<String> bothSet = materialNames(both);
        Set<String> dbBoth = new LinkedHashSet<>(strList(
                "SELECT DISTINCT r.symbol FROM material_recipe r " +
                "  JOIN material_recipe_config c ON c.recipe_id=r.id AND c.status='ACTIVE' " +
                "  JOIN material_recipe_element e ON e.config_id=c.id " +
                " WHERE r.status <> 'ACTIVE' AND r.recipe_type = 'editable'"));
        System.out.println("[AC-7] +recipeType=editable 文件集 = " + bothSet + " / 库集 = " + dbBoth);
        assertNonEmpty(new ArrayList<>(dbBoth), "AC-7 前置：停用 + 含量可调 的材质（本用例已造数，应非空）");
        assertEquals(dbBoth, bothSet, "AC-7：叠加类型筛选后，导出集合 == 页面此刻显示的集合");
        assertTrue(fileSet.containsAll(bothSet) && bothSet.size() < fileSet.size(),
                "AC-7：叠加筛选必须是收敛（子集且更小），实际 " + bothSet + " ⊄ " + fileSet);
    }

    // ═══════════════════ T-08 → AC-8：不受分页限制 ═══════════════════

    /**
     * T-08 → AC-8：不传任何筛选参数导出 ⇒ 不同材质名个数 == 基准查询①、数据行数 == 基准查询②。
     * <p>⚠️ 2026-09-02 用户裁决：基准查询①② 去掉了 {@code status='ACTIVE'} ——
     * 不筛选的导出是<b>全状态</b>，与页面列表口径一致（列表不筛选时也显示停用材质）。
     * <p>关键否定断言：导出行数<b>不等于</b> 20（页面 pageSize）。
     * <p>⚠️ 共享库有并发写入，基准查询与导出取的是<b>相邻两刻</b>；本用例先取一次基线、导出、再取一次，
     * 只要导出值落在两次基线之间即视为一致 —— 🚫 这不是放宽断言，是排除并发漂移这一个已知干扰项。
     */
    @Test
    void t08_exportIsFullFilteredSet_notPageLimited() {
        long recipeBefore = baseRecipeCount(), rowsBefore = baseRecipeElementRowCount();
        byte[] xlsx = given().when().get(EXPORT).then().statusCode(200).extract().asByteArray();
        long recipeAfter = baseRecipeCount(), rowsAfter = baseRecipeElementRowCount();

        int fileMaterials = materialNames(xlsx).size();
        int fileRows = dataRows(xlsx).size();
        System.out.printf("[AC-8] 文件: 材质=%d 行=%d ｜ 基准①=%d~%d 基准②=%d~%d%n",
                fileMaterials, fileRows, recipeBefore, recipeAfter, rowsBefore, rowsAfter);

        assertTrue(fileRows > 0, "AC-8：导出 0 行 —— 断言会空跑（假绿）");
        assertTrue(fileMaterials >= Math.min(recipeBefore, recipeAfter)
                        && fileMaterials <= Math.max(recipeBefore, recipeAfter),
                "AC-8：不同材质名个数应 == 基准查询①（" + recipeBefore + "→" + recipeAfter
                + "），实际 " + fileMaterials);
        assertTrue(fileRows >= Math.min(rowsBefore, rowsAfter)
                        && fileRows <= Math.max(rowsBefore, rowsAfter),
                "AC-8：数据行数应 == 基准查询②（" + rowsBefore + "→" + rowsAfter + "），实际 " + fileRows);

        // 🚨 关键否定断言（AC-8 原文）：不等于当前页 20 条，也不等于 pageSize
        assertTrue(fileRows != 20,
                "AC-8：导出行数恰为 20 —— 高度疑似只导了当前页（pageSize=20），而不是筛选结果全量");
    }

    // ═══════════════ AC-23 的后端兜底：空结果仍 200 + 只有表头 ═══════════════

    /**
     * AC-23（后端兜底，api.md「空结果」约定）：筛选结果为 0 条时仍返回 200 + 只有表头的 xlsx。
     * <p>前端在 0 条时禁用按钮（T-23 覆盖），这里验的是绕过前端直接打接口的那条路径。
     */
    @Test
    void t08b_emptyFilterResult_returns200WithHeaderOnly() {
        byte[] xlsx = exportWith("keyword", "zzz不存在zzz");
        List<String> h = header(xlsx);
        List<List<String>> rows = dataRows(xlsx);
        System.out.println("[AC-23·后端] 表头 = " + h + " 数据行数 = " + rows.size());
        assertEquals(List.of("材质", "组号", "元素符号", "含量"), h.subList(0, 4),
                "AC-23：空结果也必须带完整表头（否则回导时表头校验直接挂）");
        assertEquals(0, rows.size(), "AC-23：搜 zzz不存在zzz 应导出 0 数据行，实际 " + rows.size());
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private byte[] exportWith(String... kv) {
        var req = given();
        for (int i = 0; i + 1 < kv.length; i += 2) req = req.queryParam(kv[i], kv[i + 1]);
        Response res = req.when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "导出应 200，实际 " + res.statusCode() + " body=" + res.asString());
        return res.asByteArray();
    }

    private Set<String> materialNames(byte[] xlsx) {
        Map<String, Integer> idx = headerIndex(xlsx);
        Set<String> s = new LinkedHashSet<>();
        for (List<String> r : dataRows(xlsx)) {
            String v = cell(r, idx, "材质");
            if (!v.isBlank()) s.add(v);
        }
        return s;
    }

    private List<List<String>> assertNonEmptyRows(byte[] xlsx, String what) {
        List<List<String>> rows = dataRows(xlsx);
        System.out.println("[task260902] " + what + " 行数 = " + rows.size()
                + (rows.isEmpty() ? "" : "，首行 = " + rows.get(0)));
        assertTrue(!rows.isEmpty(), "断言前置失败：" + what + " 为空 —— 后续断言会空跑（假绿）");
        return rows;
    }
}
