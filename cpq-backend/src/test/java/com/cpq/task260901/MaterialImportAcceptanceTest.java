package com.cpq.task260901;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260901 · 接口层验收：<b>材质库导入</b>（T-I-01 ~ T-I-12 / T-I-18 ~ T-I-25）。
 *
 * <h3>用例来源</h3>
 * 全部断言派生自 {@code dev-docs/task-260901-材质管理模块定义规则更新/需求文档.md §③} 的 AC 原文
 * 与 {@code api.md §2.3} 的接口契约。<b>不读实现</b>（testing.md §1）。
 *
 * <h3>为什么走 HTTP 而不是直接注入 Service</h3>
 * 注入 Service 就必须知道它的类名/方法名/DTO 字段名 —— 那已经是从实现派生。
 * 走 {@code POST /api/cpq/material-recipes/import} 只依赖 {@code api.md} 这份<b>契约</b>，
 * 顺带保证本文件在实现落地前<b>照样能编译</b>（不 import 任何新增实体/DTO 类），
 * 不会把后端工程师的整个测试套件带崩。
 *
 * <h3>夹具</h3>
 * 导入类用例统一用 {@code 夹具-材质库导入验收.xlsx}（§3.0，16 数据行，含量列为文本以保住 12 位小数）。
 * 🚫 不另造夹具。个别边界 AC（AC-23/24/25/27/28/32）需要 §3.0 之外的输入，才在内存里构造 4 列单表。
 */
@QuarkusTest
class MaterialImportAcceptanceTest extends MaterialAcTestBase {

    private static final String IMPORT = "/api/cpq/material-recipes/import";
    private static final String TEMPLATE = "/api/cpq/material-recipes/import/template";
    private static final String XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // ══════════════════════ §3.0 夹具：共同基线 ══════════════════════

    /** T-I-01 → AC-1：导入夹具，报告 totalRows=16，无 500。 */
    @Test
    void tI01_fixtureImport_totalRows16_noServerError() {
        JsonPath rep = importFixture();
        System.out.println("[AC-1] report = " + rep.prettify());
        assertEquals(16, rep.getInt("totalRows"), "AC-1：夹具 16 数据行 → totalRows=16");

        // §3.0 共同基线：一次性把六个计数全断了，任何一个偏了都能一眼看出偏在哪
        assertEquals(3, rep.getInt("recipesCreated"),
            "§3.0：只有 AC测新材 / AC测新元素 / AC测12位 三条材质该落库");
        assertEquals(4, rep.getInt("configsCreated"),
            "§3.0：00006-02 + 三条新材质各一组 = 4 组配置");
        assertEquals(1, rep.getInt("configsSkippedAsDuplicate"),
            "§3.0：AgNi10 组1 与 00006-01 逐值相同 → 跳过 1 组");
        assertEquals(1, rep.getList("createdElements").size(),
            "§3.0：只有 Xx 一个元素该自动建档");
        assertEquals(2, rep.getList("skipped").size(),
            "§3.0：AC测和不对 与 AC测集合不一致 各一条 skip，实际=" + rep.getList("skipped"));
    }

    /** T-I-02 → AC-2：已存在的材质不新建。 */
    @Test
    void tI02_existingRecipeNotDuplicated() {
        importFixture();
        long n = count("SELECT count(*) FROM material_recipe WHERE symbol = '" + REAL_RECIPE_SYMBOL + "'");
        System.out.println("[AC-2] count(material_recipe where symbol=AgNi10) = " + n);
        assertEquals(1, n, "AC-2：AgNi10 仍只有一条（code=00006），不因导入再建一条");
        assertEquals(REAL_RECIPE_CODE,
            scalar("SELECT code FROM material_recipe WHERE symbol = '" + REAL_RECIPE_SYMBOL + "'"),
            "AC-2：那一条就是 00006");
    }

    /**
     * T-I-03 → AC-3：材质编号按<b>文件内首次出现顺序</b>发号，且<b>只对校验通过的材质发号</b>。
     * <p>⚠️ AC 原文的字面值是 00263/00264/00265（dev 库 max=00262）。test 库基线不同，
     * 这里断言等价语义「基线+1 / +2 / +3」，字面值由主线在 dev 库亲验。
     */
    @Test
    void tI03_recipeCodeAllocation_inFileOrder_onlyForValidRecipes() {
        String base = baselineMaxCode5;
        assertNotNull(base, "前置：test 库须已有 5 位补零材质编号，否则 AC-3 的自增语义无从验起");
        importFixture();

        String c1 = scalar("SELECT code FROM material_recipe WHERE symbol = 'AC测新材'");
        String c2 = scalar("SELECT code FROM material_recipe WHERE symbol = 'AC测新元素'");
        String c3 = scalar("SELECT code FROM material_recipe WHERE symbol = 'AC测12位'");
        System.out.printf("[AC-3] base=%s → AC测新材=%s AC测新元素=%s AC测12位=%s%n", base, c1, c2, c3);

        assertEquals(nextCode(base, 1), c1, "AC-3：AC测新材 = 基线+1（文件内第 1 个通过校验的新材质）");
        assertEquals(nextCode(base, 2), c2, "AC-3：AC测新元素 = 基线+2");
        assertEquals(nextCode(base, 3), c3,
            "AC-3：AC测12位 = 基线+3 —— 被跳过的两条材质不占号，所以这里不是 基线+5");
    }

    /** T-I-04 → AC-4：被跳过的材质不落库、不消耗编号（三条连续编号、无空号）。 */
    @Test
    void tI04_skippedRecipes_notPersisted_andConsumeNoCode() {
        String base = baselineMaxCode5;
        importFixture();

        long bad = count("SELECT count(*) FROM material_recipe "
                + "WHERE symbol IN ('AC测和不对','AC测集合不一致')");
        assertEquals(0, bad, "AC-4：AC测和不对 / AC测集合不一致 都不落库，实际 " + bad + " 条");

        List<String> codes = strList("SELECT code FROM material_recipe WHERE code ~ '^[0-9]{5}$' AND code > '"
                + base + "' ORDER BY code");
        assertNonEmpty(codes, "AC-4 本次新发的材质编号");
        assertEquals(List.of(nextCode(base, 1), nextCode(base, 2), nextCode(base, 3)), codes,
            "AC-4：恰三条连续编号、无空号（有空号 = 被跳过的材质偷偷消耗了编号）");
    }

    /** T-I-05 → AC-5：已建档的元素符号自动匹配到 element_no，配置元素行的 element_no 不为 NULL。 */
    @Test
    void tI05_knownElementSymbols_resolveToElementNo() {
        importFixture();
        List<String> rows = strList(
            "SELECT e.element_code || '=' || COALESCE(e.element_no,'<NULL>') " +
            "FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id = e.config_id " +
            "JOIN material_recipe r ON r.id = c.recipe_id " +
            "WHERE r.symbol LIKE '" + AC_PREFIX + "%' OR c.config_no = '00006-02' " +
            "ORDER BY 1");
        assertNonEmpty(rows, "AC-5 本次新增的配置元素行");

        long nullNo = count(
            "SELECT count(*) FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id = e.config_id " +
            "JOIN material_recipe r ON r.id = c.recipe_id " +
            "WHERE e.element_no IS NULL AND (r.symbol LIKE '" + AC_PREFIX + "%' OR c.config_no = '00006-02')");
        assertEquals(0, nullNo, "AC-5：本次新增行 element_no 一个都不许为 NULL");

        // Ag/Ni/Cu 必须匹配到 element 主表现有编号（值取自主表，不写死 10001/10005/10002）
        for (String sym : List.of("Ag", "Ni", "Cu")) {
            String expect = scalar("SELECT element_no FROM element WHERE element_code = '" + sym + "' LIMIT 1");
            assertNotNull(expect, "前置：element 主表须已有 " + sym);
            List<String> got = strList(
                "SELECT DISTINCT e.element_no FROM material_recipe_element e " +
                "JOIN material_recipe_config c ON c.id = e.config_id " +
                "JOIN material_recipe r ON r.id = c.recipe_id " +
                "WHERE e.element_code = '" + sym + "' " +
                "  AND (r.symbol LIKE '" + AC_PREFIX + "%' OR c.config_no = '00006-02')");
            assertNonEmpty(got, "AC-5 " + sym + " 本次落库的 element_no");
            assertEquals(List.of(expect), got, "AC-5：" + sym + " 应匹配到主表既有编号 " + expect);
        }
    }

    /** T-I-06 → AC-6：未建档符号 Xx 自动建档，编号 = 纯数字最大值+1；报告列出「本次自动新建元素」。 */
    @Test
    void tI06_unknownSymbol_autoRegistered_withNextNumericNo() {
        long base = baselineMaxElementNo;
        assertTrue(base > 0, "前置：element 主表须有纯数字编号");
        JsonPath rep = importFixture();

        String no = scalar("SELECT element_no FROM element WHERE element_code = 'Xx'");
        String name = scalar("SELECT element_name FROM element WHERE element_code = 'Xx'");
        System.out.printf("[AC-6] base=%d → Xx element_no=%s name=%s%n", base, no, name);
        assertNotNull(no, "AC-6：Xx 应被自动建档");
        assertEquals(String.valueOf(base + 1), no,
            "AC-6：编号 = ^[0-9]+$ 最大值 + 1（脏行 '白银' 天然被过滤掉）");

        List<Map<String, Object>> created = rep.getList("createdElements");
        assertNonEmpty(created, "AC-6 报告的 createdElements");
        assertTrue(created.stream().anyMatch(m -> "Xx".equals(m.get("elementCode"))
                        && String.valueOf(base + 1).equals(String.valueOf(m.get("elementNo")))),
            "AC-6：导入报告的「本次自动新建元素」清单须列出 Xx / " + (base + 1) + "，实际=" + created);
    }

    /** T-I-07 → AC-7：内容相同的组跳过，不同的组新增一条配置 00006-02。 */
    @Test
    void tI07_sameContentSkipped_newContentAddsConfig() {
        long before = count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'");
        assertEquals(1, before, "前置：00006 存量应恰有 1 条 ACTIVE 配置（00006-01，S-6 迁移产出）");

        importFixture();

        List<String> configs = strList(
            "SELECT c.config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id " +
            "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE' ORDER BY c.seq");
        assertNonEmpty(configs, "AC-7 00006 的 ACTIVE 配置");
        assertEquals(List.of("00006-01", "00006-02"), configs,
            "AC-7：组1 内容相同 → 不新增；组2 → 新增 00006-02");

        List<String> g2 = strList(
            "SELECT e.element_code || '=' || e.default_pct FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id " +
            "WHERE c.config_no='00006-02' ORDER BY e.element_code");
        assertNonEmpty(g2, "AC-7 00006-02 的元素含量");
        assertEquals(List.of("Ag=85.000000000000", "Ni=15.000000000000"), g2,
            "AC-7：00006-02 = Ag 85 / Ni 15（100 制）");
    }

    /** T-I-08 → AC-8：12 位小数无损，两值相加 = 100.000000000000。 */
    @Test
    void tI08_twelveDecimals_lossless() {
        importFixture();
        List<String> pcts = strList(
            "SELECT e.default_pct::text FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id " +
            "JOIN material_recipe r ON r.id=c.recipe_id " +
            "WHERE r.symbol='AC测12位' ORDER BY e.element_code");
        assertNonEmpty(pcts, "AC-8 AC测12位 的含量原始值");
        assertEquals(List.of("12.345678901200", "87.654321098800"), pcts,
            "AC-8：0.123456789012 / 0.876543210988 ×100 后 12 位小数无损");

        String sum = scalar(
            "SELECT sum(e.default_pct)::text FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id " +
            "JOIN material_recipe r ON r.id=c.recipe_id WHERE r.symbol='AC测12位'");
        System.out.println("[AC-8] Σ = " + sum);
        assertEquals("100.000000000000", sum, "AC-8：两值相加恰 = 100.000000000000");
    }

    /** T-I-09 → AC-9：Σ=1.20 超容差 → 整组跳过，报告出现「含量合计≠1(实际1.20)」。 */
    @Test
    void tI09_sumNotOne_groupSkipped_withReason() {
        JsonPath rep = importFixture();
        List<Map<String, Object>> skipped = rep.getList("skipped");
        assertNonEmpty(skipped, "AC-9 报告的 skipped 条目");

        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> s : skipped) reasons.add(String.valueOf(s.get("reason")));
        System.out.println("[AC-9] skipped reasons = " + reasons);

        assertTrue(reasons.stream().anyMatch(r -> r.contains("含量合计≠1") && r.contains("1.20")),
            "AC-9：须有一条 `含量合计≠1(实际1.20)`，实际 reasons=" + reasons);
        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE symbol='AC测和不对'"),
            "AC-9：该材质无有效组 ⇒ 不建材质");
    }

    /**
     * T-I-10 → AC-10：<b>已存在材质</b>的某一组元素集合与该材质的元素组成不相等 → 该组跳过。
     * <p>用另一份只含 {@code AgNi10 / 组号9 / Ag 0.5 / Cu 0.5} 的文件（AC-10 原文指定）。
     */
    @Test
    void tI10_groupElementSetMismatchExistingRecipe_groupSkipped_compositionUntouched() {
        List<String> compBefore = strList(
            "SELECT c.element_code FROM material_recipe_composition c JOIN material_recipe r ON r.id=c.recipe_id "
            + "WHERE r.code='" + REAL_RECIPE_CODE + "' ORDER BY c.sort_order");
        assertEquals(List.of("Ag", "Ni"), compBefore, "前置：00006 元素组成 = Ag + Ni");
        long activeBefore = count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'");

        JsonPath rep = doImport(buildSheet(new String[][]{
            {"AgNi10", "9", "Ag", "0.5"},
            {"AgNi10", "9", "Cu", "0.5"},
        }));
        System.out.println("[AC-10] report = " + rep.prettify());

        List<Map<String, Object>> skipped = rep.getList("skipped");
        assertNonEmpty(skipped, "AC-10 报告的 skipped 条目");
        assertTrue(skipped.stream().anyMatch(
                s -> String.valueOf(s.get("reason")).contains("元素组合与该材质的元素组成不一致")),
            "AC-10：须有一条 `元素组合与该材质的元素组成不一致`，实际=" + skipped);

        assertEquals(activeBefore,
            count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'"),
            "AC-10：00006 下 ACTIVE 配置数不变");

        List<String> compAfter = strList(
            "SELECT c.element_code FROM material_recipe_composition c JOIN material_recipe r ON r.id=c.recipe_id "
            + "WHERE r.code='" + REAL_RECIPE_CODE + "' ORDER BY c.sort_order");
        assertEquals(compBefore, compAfter,
            "AC-10：🚨 不得因此改动材质的元素组成，实际=" + compAfter);
    }

    /** T-I-11 → AC-11：旧两 sheet 模板整体拒收（400），库内材质条数不变。 */
    @Test
    void tI11_legacyTwoSheetTemplate_rejected() {
        long before = count("SELECT count(*) FROM material_recipe");
        byte[] legacy = buildLegacyTwoSheetWorkbook();

        Response res = RestAssured.given()
            .multiPart("file", "材质库.xlsx", legacy, XLSX_MIME)
            .post(IMPORT).thenReturn();
        System.out.println("[AC-11] status=" + res.statusCode() + " body=" + res.asString());

        assertEquals(400, res.statusCode(), "AC-11：旧模板必须整体拒收，不按旧语义静默执行");
        String body = res.asString();
        assertTrue(body.contains("导入模板格式已更新"),
            "AC-11：错误文案须为「导入模板格式已更新，请下载新模板…」，实际=" + body);
        assertTrue(body.contains("材质") && body.contains("组号")
                && body.contains("元素符号") && body.contains("含量"),
            "AC-11：文案须点名新模板的 4 列，实际=" + body);
        assertEquals(before, count("SELECT count(*) FROM material_recipe"),
            "AC-11：库内 material_recipe 条数不变");
    }

    /** T-I-12 → AC-12：模板下载 = 单 sheet、恰 4 列表头、含 2 行示例、不含旧编号列。 */
    @Test
    void tI12_templateIsSingleSheetFourColumns() throws Exception {
        byte[] bytes = RestAssured.given().get(TEMPLATE).then().statusCode(200)
            .extract().asByteArray();
        assertTrue(bytes.length > 0, "AC-12：模板文件非空");

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(1, wb.getNumberOfSheets(),
                "AC-12：单 sheet，不含第二个工作表（实际 " + wb.getNumberOfSheets() + " 个）");
            Sheet s = wb.getSheetAt(0);
            Row h = s.getRow(0);
            List<String> header = new ArrayList<>();
            for (int i = 0; i < h.getLastCellNum(); i++) {
                Cell c = h.getCell(i);
                if (c != null && !c.toString().isBlank()) header.add(c.toString().trim());
            }
            System.out.println("[AC-12] header = " + header);
            assertEquals(List.of("材质", "组号", "元素符号", "含量"), header,
                "AC-12：表头恰为 材质/组号/元素符号/含量，不含「材质编号」「元素编号」");
            assertEquals(2, s.getLastRowNum(),
                "AC-12：含 2 行示例数据（表头 + 2 行 ⇒ lastRowNum=2）");
        }
    }

    // ══════════════════════ 序列 AC ══════════════════════

    /** T-I-18 → AC-20（序列）：原样重导 ⇒ 材质/配置/元素三个新增数都为 0，总数完全一致。 */
    @Test
    void tI18_reimportSameFixture_isIdempotent() {
        JsonPath first = importFixture();
        long recipesAfterFirst = count("SELECT count(*) FROM material_recipe");
        long configsOn00006 = count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'");
        System.out.printf("[AC-20] 第一次后：material_recipe=%d，00006 ACTIVE 配置=%d%n",
                recipesAfterFirst, configsOn00006);
        assertEquals(3, first.getInt("recipesCreated"), "AC-20 中间态：第一次导入建 3 条材质");
        assertEquals(2, configsOn00006, "AC-20 中间态：00006 下 2 条配置");

        JsonPath second = importFixture();
        System.out.println("[AC-20] 第二次 report = " + second.prettify());
        assertEquals(0, second.getInt("recipesCreated"), "AC-20：第二次 新增材质=0");
        assertEquals(0, second.getInt("configsCreated"), "AC-20：第二次 新增配置=0");
        assertEquals(0, second.getList("createdElements").size(), "AC-20：第二次 新增元素=0");

        assertEquals(recipesAfterFirst, count("SELECT count(*) FROM material_recipe"),
            "AC-20：material_recipe 总数与第一次导入后完全一致");
        assertEquals(2, count("SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
                + "WHERE r.code='" + REAL_RECIPE_CODE + "' AND c.status='ACTIVE'"),
            "AC-20 最终态：00006 下仍是 2 条配置");
    }

    // ══════════════════════ 边界 AC ══════════════════════

    /** T-I-19 → AC-23：只有表头无数据行 ⇒ 200 + totalRows=0，不抛 500，库内零变化。 */
    @Test
    void tI19_headerOnlyFile_returns200AndZeroReport() {
        long before = count("SELECT count(*) FROM material_recipe");
        JsonPath rep = doImport(buildSheet(new String[0][]));
        System.out.println("[AC-23] report = " + rep.prettify());
        assertEquals(0, rep.getInt("totalRows"), "AC-23：totalRows=0");
        assertEquals(0, rep.getInt("recipesCreated"), "AC-23：零新增");
        assertEquals(before, count("SELECT count(*) FROM material_recipe"), "AC-23：库内零变化");
    }

    /** T-I-20 → AC-24：材质名 33 字符 ⇒ 该材质整体跳过 + 报告超长，不出现 DB `value too long`，其余正常。 */
    @Test
    void tI20_recipeNameTooLong_skipped_withoutDbException() {
        String longName = AC_PREFIX + "X".repeat(33 - AC_PREFIX.length()); // 恰 33 字符
        assertEquals(33, longName.length(), "构造：材质名恰 33 字符（symbol 为 varchar(32)）");

        JsonPath rep = doImport(buildSheet(new String[][]{
            {longName, "1", "Ag", "0.5"},
            {longName, "1", "Ni", "0.5"},
            {AC_PREFIX + "正常", "1", "Ag", "0.6"},
            {AC_PREFIX + "正常", "1", "Ni", "0.4"},
        }));
        System.out.println("[AC-24] report = " + rep.prettify());

        List<Map<String, Object>> skipped = rep.getList("skipped");
        assertNonEmpty(skipped, "AC-24 报告的 skipped 条目");
        assertTrue(skipped.stream().anyMatch(
                s -> String.valueOf(s.get("reason")).contains("材质名超长")),
            "AC-24：须有一条「材质名超长（最多 32 字符）」，实际=" + skipped);
        assertFalse(rep.prettify().contains("value too long"),
            "AC-24：🚫 不得把 PG 的 `value too long` 直接冒到报告里");

        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE symbol = '" + longName + "'"),
            "AC-24：超长材质整体跳过");
        assertEquals(1, count("SELECT count(*) FROM material_recipe WHERE symbol = '" + AC_PREFIX + "正常'"),
            "AC-24：其余材质正常导入");
    }

    /** T-I-21 → AC-25：含量 0 / 1.5 / abc / 空 各自跳过该行并记「含量非法」；同组其余行不受影响。 */
    @Test
    void tI21_illegalContent_rowSkipped() {
        // 四个非法值各自成组（组内另有一个合法行），验「跳过的是行不是整表」
        JsonPath rep = doImport(buildSheet(new String[][]{
            {AC_PREFIX + "零",   "1", "Ag", "0"},    {AC_PREFIX + "零",   "1", "Ni", "1"},
            {AC_PREFIX + "超一", "1", "Ag", "1.5"},  {AC_PREFIX + "超一", "1", "Ni", "1"},
            {AC_PREFIX + "非数", "1", "Ag", "abc"},  {AC_PREFIX + "非数", "1", "Ni", "1"},
            {AC_PREFIX + "空值", "1", "Ag", ""},     {AC_PREFIX + "空值", "1", "Ni", "1"},
        }));
        System.out.println("[AC-25] report = " + rep.prettify());

        List<Map<String, Object>> skipped = rep.getList("skipped");
        assertNonEmpty(skipped, "AC-25 报告的 skipped 条目");
        long illegal = skipped.stream()
            .filter(s -> String.valueOf(s.get("reason")).contains("含量非法")).count();
        assertEquals(4, illegal,
            "AC-25：0 / 1.5 / abc / 空 各记一条「含量非法」，实际 " + illegal + " 条，全量=" + skipped);

        // 跳过后各组只剩一行 Ni=1 ⇒ Σ=1 恰好成立 ⇒ 四条材质应正常建（这条同时证明「同组其余行不受影响」）
        for (String s : List.of("零", "超一", "非数", "空值")) {
            List<String> els = strList(
                "SELECT e.element_code FROM material_recipe_element e " +
                "JOIN material_recipe_config c ON c.id=e.config_id " +
                "JOIN material_recipe r ON r.id=c.recipe_id " +
                "WHERE r.symbol='" + AC_PREFIX + s + "' ORDER BY e.element_code");
            assertEquals(List.of("Ni"), els,
                "AC-25：" + AC_PREFIX + s + " 的合法行 Ni 不受影响，实际=" + els);
        }
    }

    /** T-I-22 → AC-27：9 个纯数字合金牌号正常匹配到 element 主表既有编号，不得被判非法。 */
    @Test
    void tI22_numericGradeSymbols_resolveNormally() {
        List<String> grades = List.of("301", "304", "316", "430", "191", "206", "223", "258", "721");
        // 前置：9 个牌号必须都已建档，否则本用例会退化成「验自动建档」而不是「验匹配」
        for (String g : grades) {
            assertNotNull(scalar("SELECT element_no FROM element WHERE element_code = '" + g + "'"),
                "前置：合金牌号 " + g + " 应已在 element 主表建档（AC-27 原文：均已建档）");
        }
        long elementCountBefore = count("SELECT count(*) FROM element");

        String[][] rows = new String[9][];
        for (int i = 0; i < 9; i++) {
            // 9 个牌号各 1/9，Σ 不是 1 —— 改成 8 个 0.1 + 1 个 0.2，凑 Σ=1
            rows[i] = new String[]{AC_PREFIX + "牌号", "1", grades.get(i), i == 0 ? "0.2" : "0.1"};
        }
        JsonPath rep = doImport(buildSheet(rows));
        System.out.println("[AC-27] report = " + rep.prettify());

        assertEquals(1, rep.getInt("recipesCreated"), "AC-27：9 个纯数字牌号的材质应正常落库");
        assertEquals(elementCountBefore, count("SELECT count(*) FROM element"),
            "AC-27：牌号全部匹配到既有编号 ⇒ element 主表不该新增任何行");

        List<String> got = strList(
            "SELECT e.element_code || '->' || e.element_no FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id " +
            "JOIN material_recipe r ON r.id=c.recipe_id " +
            "WHERE r.symbol='" + AC_PREFIX + "牌号' ORDER BY e.element_code");
        assertNonEmpty(got, "AC-27 落库的牌号元素行");
        assertEquals(9, got.size(), "AC-27：9 个牌号全部入库，实际=" + got);
        for (String g : grades) {
            String expect = g + "->" + scalar("SELECT element_no FROM element WHERE element_code='" + g + "'");
            assertTrue(got.contains(expect), "AC-27：" + expect + " 应在落库结果里，实际=" + got);
        }
        assertTrue(rep.getList("skipped").stream().noneMatch(
                s -> grades.contains(String.valueOf(((Map<?, ?>) s).get("raw")))),
            "AC-27：🚫 不得因「纯数字」把牌号判为非法");
    }

    /**
     * T-I-25 → AC-32（边界）：新建材质的各组元素集合不一致 ⇒ 整个材质跳过、不发号；
     * <b>且把两组的行顺序对调后重导，报告逐字相同</b>（入库结果不依赖行序）。
     */
    @Test
    void tI25_inconsistentGroups_wholeRecipeSkipped_andOrderIndependent() {
        String base = baselineMaxCode5;

        String[][] order1 = {
            {AC_PREFIX + "集合不一致", "1", "Ag", "0.5"},
            {AC_PREFIX + "集合不一致", "1", "Ni", "0.5"},
            {AC_PREFIX + "集合不一致", "2", "Ag", "0.5"},
            {AC_PREFIX + "集合不一致", "2", "Cu", "0.5"},
        };
        String[][] order2 = {   // 两组对调
            {AC_PREFIX + "集合不一致", "2", "Ag", "0.5"},
            {AC_PREFIX + "集合不一致", "2", "Cu", "0.5"},
            {AC_PREFIX + "集合不一致", "1", "Ag", "0.5"},
            {AC_PREFIX + "集合不一致", "1", "Ni", "0.5"},
        };

        JsonPath r1 = doImport(buildSheet(order1));
        System.out.println("[AC-32] 原序 report = " + r1.prettify());
        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE symbol='" + AC_PREFIX + "集合不一致'"),
            "AC-32：整个材质不入库");
        assertEquals(base, maxRecipeCode5(), "AC-32：未消耗材质编号（max 应仍是基线 " + base + "）");
        assertTrue(r1.getList("skipped").stream().anyMatch(
                s -> String.valueOf(((Map<?, ?>) s).get("reason")).contains("同一材质内各组元素组成不一致")),
            "AC-32：须有一条 `同一材质内各组元素组成不一致(...)`，实际=" + r1.getList("skipped"));

        JsonPath r2 = doImport(buildSheet(order2));
        System.out.println("[AC-32] 对调 report = " + r2.prettify());

        // 逐字对比：剔除 durationMs（耗时天然不同）与行号（行序变了行号必然变）后必须完全相同
        assertEquals(normalizeReport(r1), normalizeReport(r2),
            "AC-32：行序对调后入库结果与报告须逐字相同（顺序无关，M-5b）");
        assertEquals(base, maxRecipeCode5(), "AC-32：对调重导后仍未消耗编号");
    }

    /**
     * T-I-23 → AC-28（边界）：同一 symbol 有 ≥2 条 ACTIVE 材质 ⇒ 该材质整体跳过（M-6 防御分支），
     * 不任选一条落库、不新建第三条；其余材质正常导入。
     * <p>⚠️ 构造数据用 {@code AC测重名} 前缀，随 {@code @AfterEach} 一并清除。
     */
    @Test
    void tI23_duplicateSymbol_defensiveSkip() {
        String dupSymbol = AC_PREFIX + "重名";
        seedDuplicateActiveRecipes(dupSymbol);
        assertEquals(2, count("SELECT count(*) FROM material_recipe WHERE symbol='" + dupSymbol
                + "' AND status='ACTIVE'"), "构造：应恰有 2 条同名 ACTIVE 材质");

        JsonPath rep = doImport(buildSheet(new String[][]{
            {dupSymbol, "1", "Ag", "0.5"},
            {dupSymbol, "1", "Ni", "0.5"},
            {AC_PREFIX + "陪跑", "1", "Ag", "0.6"},
            {AC_PREFIX + "陪跑", "1", "Ni", "0.4"},
        }));
        System.out.println("[AC-28] report = " + rep.prettify());

        assertTrue(rep.getList("skipped").stream().anyMatch(
                s -> String.valueOf(((Map<?, ?>) s).get("reason"))
                        .contains("材质名对应多条材质记录，请先在材质管理页处理")),
            "AC-28：须有 M-6 防御分支的报告条目，实际=" + rep.getList("skipped"));
        assertEquals(2, count("SELECT count(*) FROM material_recipe WHERE symbol='" + dupSymbol + "'"),
            "AC-28：🚫 不得新建第三条同名材质");
        assertEquals(0, count(
            "SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id "
            + "WHERE r.symbol='" + dupSymbol + "'"),
            "AC-28：🚫 不得任选一条落配置");
        assertEquals(1, count("SELECT count(*) FROM material_recipe WHERE symbol='" + AC_PREFIX + "陪跑'"),
            "AC-28：其余材质正常导入");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private JsonPath importFixture() {
        return doImport(readFixture());
    }

    private JsonPath doImport(byte[] xlsx) {
        Response res = RestAssured.given()
            .multiPart("file", "夹具-材质库导入验收.xlsx", xlsx, XLSX_MIME)
            .post(IMPORT).thenReturn();
        assertEquals(200, res.statusCode(),
            "导入应返 200（AC-1：无 500），实际 " + res.statusCode() + " body=" + res.asString());
        return res.jsonPath();
    }

    /** §3.0 的固定夹具。缺文件直接失败 —— 🚫 不许悄悄降级成「跳过」，那是假绿。 */
    private byte[] readFixture() {
        for (String p : new String[]{
                "../dev-docs/task-260901-材质管理模块定义规则更新/夹具-材质库导入验收.xlsx",
                "dev-docs/task-260901-材质管理模块定义规则更新/夹具-材质库导入验收.xlsx"}) {
            Path path = Paths.get(p);
            if (Files.exists(path)) {
                try {
                    byte[] b = Files.readAllBytes(path);
                    assertTrue(b.length > 0, "夹具文件为空：" + path.toAbsolutePath());
                    return b;
                } catch (Exception e) {
                    throw new IllegalStateException("读夹具失败：" + path.toAbsolutePath(), e);
                }
            }
        }
        throw new IllegalStateException(
            "🚨 找不到夹具 夹具-材质库导入验收.xlsx（cwd=" + Paths.get("").toAbsolutePath() + "）。"
            + "夹具是 AC 的共同基线，缺了不能跳过，必须让用例硬失败。");
    }

    /** 内存构造新格式：单 sheet、表头 材质|组号|元素符号|含量，全部按字符串写（保 12 位小数）。 */
    private byte[] buildSheet(String[][] rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("材质含量");
            Row h = s.createRow(0);
            String[] hdr = {"材质", "组号", "元素符号", "含量"};
            for (int i = 0; i < hdr.length; i++) h.createCell(i).setCellValue(hdr[i]);
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int j = 0; j < 4; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 旧两 sheet 模板（AC-11 的反例）。 */
    private byte[] buildLegacyTwoSheetWorkbook() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet cs = wb.createSheet("材质编号");
            Row ch = cs.createRow(0);
            ch.createCell(0).setCellValue("材质");
            ch.createCell(1).setCellValue("材质编号");
            Row c1 = cs.createRow(1);
            c1.createCell(0).setCellValue(AC_PREFIX + "旧模板");
            c1.createCell(1).setCellValue("ZZ001");

            Sheet es = wb.createSheet("材质对应元素");
            Row eh = es.createRow(0);
            String[] hdr = {"材质", "材质编号", "元素名称", "含量", "元素编号"};
            for (int i = 0; i < hdr.length; i++) eh.createCell(i).setCellValue(hdr[i]);
            Row e1 = es.createRow(1);
            e1.createCell(0).setCellValue(AC_PREFIX + "旧模板");
            e1.createCell(1).setCellValue("ZZ001");
            e1.createCell(2).setCellValue("Ag");
            e1.createCell(3).setCellValue("1");
            e1.createCell(4).setCellValue("10001");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** AC-28 构造：两条同 symbol 的 ACTIVE 材质（编号带 AC 前缀无关，靠 symbol 前缀被清理）。 */
    private void seedDuplicateActiveRecipes(String symbol) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 1; i <= 2; i++) {
                em.createNativeQuery(
                    "INSERT INTO material_recipe (code, symbol, name, recipe_type, sort_order, status) " +
                    "VALUES (:code, :sym, :sym, 'locked', 9990, 'ACTIVE') ON CONFLICT (code) DO NOTHING")
                  .setParameter("code", "ACDUP" + i)
                  .setParameter("sym", symbol)
                  .executeUpdate();
            }
        });
    }

    /** 剔除耗时与行号后的报告规范化形式（AC-32 逐字对比用）。 */
    private String normalizeReport(JsonPath rep) {
        StringBuilder sb = new StringBuilder();
        for (String k : List.of("totalRows", "recipesCreated", "configsCreated",
                "configsSkippedAsDuplicate", "elementRowsInserted", "skippedRowCount")) {
            sb.append(k).append('=').append(String.valueOf((Object) rep.get(k))).append('\n');
        }
        List<Map<String, Object>> skipped = rep.getList("skipped");
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> s : skipped) reasons.add(String.valueOf(s.get("reason")));
        reasons.sort(String::compareTo);
        sb.append("skippedReasons=").append(reasons).append('\n');
        sb.append("createdConfigs=").append(rep.getList("createdConfigs")).append('\n');
        sb.append("createdElements=").append(rep.getList("createdElements")).append('\n');
        return sb.toString();
    }
}
