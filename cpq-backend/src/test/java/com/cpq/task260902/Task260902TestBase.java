package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902「主数据导出 + 用户列表导入导出」验收测试的公共基座。
 *
 * <p>断言一律回到 {@code 需求文档.md §③} 的 AC 原文 + {@code api.md} 的接口契约。
 * <b>本类不读实现代码</b>，只读文档、库 schema 与导出文件本身。
 *
 * <h3>🚨 纪律一：不许清库（CLAUDE.md §3.2，测试也算）</h3>
 * {@code mvnw test} 走 test profile，而 {@code application-test.properties:24} 的默认值就是
 * <b>{@code 10.177.152.12:5432/cpq_db_0724}</b> —— <b>它就是共享开发库本身</b>（2026-09-02 实证）。
 * 因此本套用例：
 * <ul>
 *   <li>🚫 不出现 {@code TRUNCATE} / {@code DROP} / 无 WHERE 的 {@code DELETE} / 全局配置重置。</li>
 *   <li>造数一律带本任务专属前缀：材质 {@code T260902测试材质%}（编号 {@code T260902-%}）、
 *       工序 {@code t260902_%}、用户 {@code t260902%}。</li>
 *   <li>还原写在 {@link #restoreGlobalState()}（{@code @AfterEach}，等价于 finally），
 *       用例中途失败照样清；清完立刻 {@link #assertNoResidue()} 自检。</li>
 * </ul>
 *
 * <h3>⚠️ 材质编号刻意不用 5 位纯数字</h3>
 * task-260901 的用例基线取自 {@code max(code) WHERE code ~ '^[0-9]{5}$'}。本套若用
 * {@code 00999} 这类编号造数，会把那套用例的发号基线抬走，表现为「材质编号发号坏了」——
 * 一个长得完全像业务回归的假红。⇒ 本套材质编号统一用 {@code T260902-A} 形态，天然不落进那个正则。
 *
 * <h3>🚨 测试 profile 的 RBAC 是<b>开着</b>的（test.md §0.5）</h3>
 * 仓库里两处开关<b>值相反</b>，profile-specific 的那份优先：
 * <ul>
 *   <li>{@code src/test/resources/application.properties:5} = {@code false} ❌ 被覆盖</li>
 *   <li>{@code src/main/resources/application-test.properties:86} = <b>{@code true}</b> ✅ 生效</li>
 * </ul>
 * ⇒ <b>任何走 RestAssured 的请求，不带 session 一律 401</b>（主线实证：本任务没碰过的
 * {@code DepartmentResourceTest} 在本 worktree 里 4 条全挂在 401）。
 * ⇒ 本类提供 {@link #given()}：<b>自带管理员 session cookie</b> 的请求起点。
 * 🚫 子类不要直接用 {@code RestAssured.given()} —— 那会拿到 401，而 401 会伪装成
 * 「端点没做」或「权限判错」，是最难识破的一类误判。
 * 🚫 也不要用 {@code @TestProfile} 把 RBAC 关掉来「绕过」—— 关掉后「非管理员 403」恒红、
 * 「管理员 200」恒绿（哪怕 {@code @RoleAllowed} 一个字没标），正是 test.md §5② 要防的假绿。
 *
 * <h3>🚨 纪律二：断言不写死行数</h3>
 * 共享库有并发写入（立项当天几分钟内材质 263→259、工序 4→2）。所有「数量」断言一律写成
 * <b>「与同一时刻的基准查询相等」</b> 或 <b>「前后两次同一条 SQL 的差值」</b>。
 * 本类只提供 {@link #baseRecipeCount()} 等<b>现场取值</b>的方法，
 * 🚫 不提供任何写死的期望常量。
 */
public abstract class Task260902TestBase {

    protected static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 用户造数前缀（导入用例产出的用户名一律以此开头）。 */
    protected static final String USER_PREFIX = "t260902";
    // ⚠️ 权限用例的非管理员账号（t260902_sales）由 ExportPermissionTest 自己管理生命周期
    //    —— 它不继承本类（本类的 @BeforeEach 会按 t260902% 前缀把它删掉）。
    /** 材质造数前缀（symbol）。 */
    protected static final String RECIPE_SYMBOL_PREFIX = "T260902测试材质";
    /** 材质造数编号前缀（code）。刻意不是 5 位纯数字，见类注释。 */
    protected static final String RECIPE_CODE_PREFIX = "T260902-";
    /** 工序造数前缀（process_no）。 */
    protected static final String PROCESS_NO_PREFIX = "t260902_";

    /** AC-5 / AC-6 的实测样例材质（立项当日：Cu=84、301=16，config_no=00168-01）。 */
    protected static final String SAMPLE_RECIPE_SYMBOL = "301/Cu/301";

    @Inject
    protected EntityManager em;

    // ═════════════════════════ 生命周期 ═════════════════════════

    @BeforeEach
    void cleanBeforeAndSelfCheck() {
        // 上一轮若中途崩溃留下残渣，这里兜底 —— 否则「上轮残留」会被读成「本轮 bug」。
        restoreGlobalState();
        // 🚨 清完立刻自检：脏库必须以「残留」的名义硬失败，不许伪装成业务缺陷。
        assertNoResidue();
        System.out.printf("[task260902] baseline: ①材质(全状态)=%d ②行(全状态)=%d ②a行(仅启用)=%d ③工序=%d ④用户=%d%n",
                baseRecipeCount(), baseRecipeElementRowCount(), baseRecipeElementRowCountActiveOnly(),
                baseProcessCount(), baseUserCount());
    }

    @AfterEach
    void tearDownAndSelfCheck() {
        try {
            restoreGlobalState();
        } finally {
            assertNoResidue();
        }
    }

    // ═════════════════════ 基准查询（需求文档 §③ 开头）═════════════════════

    /**
     * 基准查询①：材质导出（<b>不筛选</b>）应含的「不同材质名」数 —— <b>全状态</b>。
     * <p>⚠️ 2026-09-02 用户裁决变更：口径与页面列表一致（列表不筛选时也显示停用材质），
     * 原先的 {@code WHERE status='ACTIVE'} 已去掉。
     */
    protected long baseRecipeCount() {
        return count("SELECT count(*) FROM material_recipe");
    }

    /** 基准查询②：材质导出（不筛选）应含的「数据行数」—— 材质不限状态，配置只取 ACTIVE。 */
    protected long baseRecipeElementRowCount() {
        return count("SELECT count(*) FROM material_recipe_element e " +
                "JOIN material_recipe_config c ON c.id = e.config_id AND c.status='ACTIVE' " +
                "JOIN material_recipe r ON r.id = c.recipe_id");
    }

    /**
     * 基准查询②a：<b>只算启用材质</b>的行数 —— <b>AC-19 回环专用</b>。
     * <p>🚨 AC-19 必须先筛 {@code status=ACTIVE} 再导出：材质导入按
     * {@code symbol AND status='ACTIVE'} 匹配既有材质（task-260901 既有语义），
     * 库里的<b>停用</b>材质（实测 {@code SnO2-del}/{@code 00263}）导出后回导会匹配不上、
     * 被当成新材质<b>新建一条同名的启用材质</b>。
     * ⇒ <b>「不筛选就回导 ≠ 零新增」是既有导入语义与导出撞出来的预期行为，用户已裁决不改导入逻辑。</b>
     * 🚫 不要把它当 bug 报，也 🚫 不要写一条「不筛选回导」的用例去验证它 ——
     * 那会真的在共享库里新建一条重复材质。
     */
    protected long baseRecipeElementRowCountActiveOnly() {
        return count("SELECT count(*) FROM material_recipe_element e " +
                "JOIN material_recipe_config c ON c.id = e.config_id AND c.status='ACTIVE' " +
                "JOIN material_recipe r ON r.id = c.recipe_id AND r.status='ACTIVE'");
    }

    /** 基准查询③：工序总数。 */
    protected long baseProcessCount() {
        return count("SELECT count(*) FROM process_master");
    }

    /** 基准查询④：用户总数。 */
    protected long baseUserCount() {
        return count("SELECT count(*) FROM \"user\"");
    }

    /** 材质配置组数（AC-19 / AC-20 的增量断言用）。 */
    protected long configCount() {
        return count("SELECT count(*) FROM material_recipe_config");
    }

    // ═════════════════════════ 还原 ═════════════════════════

    /**
     * 还原本套用例可能改动的全部全局状态。
     * <p>🚫 四条谓词全部收敛到本任务前缀，不存在「无 WHERE 的 DELETE」。
     */
    protected void restoreGlobalState() {
        QuarkusTransaction.requiringNew().run(() -> {
            // ① 本套自建的材质（symbol 前缀 + code 前缀 双限定，任一命中即删，防造数半途失败留残）
            em.createNativeQuery(
                "DELETE FROM material_recipe_element WHERE config_id IN (" +
                " SELECT c.id FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id" +
                " WHERE r.symbol LIKE :sym OR r.code LIKE :code)")
              .setParameter("sym", RECIPE_SYMBOL_PREFIX + "%")
              .setParameter("code", RECIPE_CODE_PREFIX + "%").executeUpdate();
            em.createNativeQuery(
                "DELETE FROM material_recipe_element WHERE recipe_id IN (" +
                " SELECT id FROM material_recipe WHERE symbol LIKE :sym OR code LIKE :code)")
              .setParameter("sym", RECIPE_SYMBOL_PREFIX + "%")
              .setParameter("code", RECIPE_CODE_PREFIX + "%").executeUpdate();
            em.createNativeQuery(
                "DELETE FROM material_recipe_config WHERE recipe_id IN (" +
                " SELECT id FROM material_recipe WHERE symbol LIKE :sym OR code LIKE :code)")
              .setParameter("sym", RECIPE_SYMBOL_PREFIX + "%")
              .setParameter("code", RECIPE_CODE_PREFIX + "%").executeUpdate();
            if (tableExists("material_recipe_composition")) {
                em.createNativeQuery(
                    "DELETE FROM material_recipe_composition WHERE recipe_id IN (" +
                    " SELECT id FROM material_recipe WHERE symbol LIKE :sym OR code LIKE :code)")
                  .setParameter("sym", RECIPE_SYMBOL_PREFIX + "%")
                  .setParameter("code", RECIPE_CODE_PREFIX + "%").executeUpdate();
            }
            em.createNativeQuery("DELETE FROM material_recipe WHERE symbol LIKE :sym OR code LIKE :code")
              .setParameter("sym", RECIPE_SYMBOL_PREFIX + "%")
              .setParameter("code", RECIPE_CODE_PREFIX + "%").executeUpdate();

            // ② 本套自建的工序
            em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE :p")
              .setParameter("p", PROCESS_NO_PREFIX + "%").executeUpdate();

            // ③ 本套自建 / 导入产生的用户。
            //    ⚠️ 先摘掉可能挂上来的引用行（AC-21 的 E2E 会让 t260902a 真登录并改密，
            //       那会写审计/通知行；不先摘就是 FK 违例 → 还原失败 → 下一轮恒红）。
            for (String t : new String[]{"operation_log", "notification"}) {
                if (!tableExists(t)) continue;
                String col = columnExists(t, "operator_id") ? "operator_id"
                           : columnExists(t, "recipient_id") ? "recipient_id" : null;
                if (col == null) continue;
                em.createNativeQuery("DELETE FROM " + t + " WHERE " + col + " IN (" +
                        " SELECT id FROM \"user\" WHERE username LIKE :p)")
                  .setParameter("p", USER_PREFIX + "%").executeUpdate();
            }
            em.createNativeQuery("DELETE FROM \"user\" WHERE username LIKE :p")
              .setParameter("p", USER_PREFIX + "%").executeUpdate();
        });
    }

    /**
     * 还原自检：残留即让用例失败。
     * <p>🚨 不做这一步，下一轮会变成「恒定失败且长得像业务回归」。
     */
    protected void assertNoResidue() {
        assertEquals(0, count("SELECT count(*) FROM material_recipe WHERE symbol LIKE '"
                        + RECIPE_SYMBOL_PREFIX + "%' OR code LIKE '" + RECIPE_CODE_PREFIX + "%'"),
                "还原自检失败：仍有本套自建材质残留");
        assertEquals(0, count("SELECT count(*) FROM process_master WHERE process_no LIKE '"
                        + PROCESS_NO_PREFIX + "%'"),
                "还原自检失败：仍有本套自建工序残留");
        assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE username LIKE '"
                        + USER_PREFIX + "%'"),
                "还原自检失败：仍有本套自建用户残留");

        // 🚨 真实数据不变式：样例材质 301/Cu/301 是 AC-5 / AC-6 的断言锚点，任何路径都不许被改。
        //    （导出端点按契约是只读的；这条断言就是「只读」这句话的守卫。）
        List<String> sample = strList(
            "SELECT e.element_code || '=' || e.default_pct::text FROM material_recipe_element e " +
            "JOIN material_recipe_config c ON c.id=e.config_id " +
            "JOIN material_recipe r ON r.id=c.recipe_id " +
            "WHERE r.symbol='" + SAMPLE_RECIPE_SYMBOL + "' ORDER BY e.sort_order");
        assertTrue(!sample.isEmpty(),
                "前置失败：样例材质 " + SAMPLE_RECIPE_SYMBOL + " 在库中查不到 —— "
                + "AC-5/AC-6 的锚点没了，后续断言会空跑（假绿）。请主线更换样例并同步 AC。");
    }

    // ═════════════════════════ 造数 ═════════════════════════

    /**
     * 造一条测试材质（1 组配置 + 2 个元素）。
     * <p>用于让 AC-7（状态/类型筛选）与 AC-11（外协筛选）有<b>非空正向结果</b>可断言 ——
     * 现网数据里 {@code recipe_type='editable'} 与 {@code is_outsource=true} 都是 0 条，
     * 不造数的话这两条 AC 只能验到空集，等于没验（testing.md §3 假绿第一类）。
     *
     * @return 新材质的 id
     */
    protected String seedRecipe(String codeSuffix, String symbol, String recipeType, String status,
                                String e1Code, String e1Pct, String e2Code, String e2Pct) {
        String code = RECIPE_CODE_PREFIX + codeSuffix;
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery(
                "INSERT INTO material_recipe (code, symbol, name, recipe_type, sort_order, status) " +
                "VALUES (:code, :sym, :sym, :type, 9902, :st)")
              .setParameter("code", code).setParameter("sym", symbol)
              .setParameter("type", recipeType).setParameter("st", status).executeUpdate();
            em.createNativeQuery(
                "INSERT INTO material_recipe_config (recipe_id, config_no, seq, status, sort_order) " +
                "SELECT id, :cfg, 1, 'ACTIVE', 0 FROM material_recipe WHERE code = :code")
              .setParameter("cfg", code + "-01").setParameter("code", code).executeUpdate();
            insertElement(code, e1Code, e1Pct, 1);
            insertElement(code, e2Code, e2Pct, 2);
        });
        String id = scalar("SELECT id::text FROM material_recipe WHERE code = '" + code + "'");
        assertNotNull(id, "造数失败：材质 " + code + " 没建出来");
        return id;
    }

    private void insertElement(String code, String elementCode, String pct, int sortOrder) {
        em.createNativeQuery(
            "INSERT INTO material_recipe_element " +
            "  (recipe_id, config_id, element_code, element_name, default_pct, is_locked, sort_order) " +
            "SELECT r.id, c.id, :ec, :ec, CAST(:pct AS numeric), true, :so " +
            "  FROM material_recipe r JOIN material_recipe_config c ON c.recipe_id = r.id " +
            " WHERE r.code = :code AND c.config_no = :cfg")
          .setParameter("ec", elementCode).setParameter("pct", pct).setParameter("so", sortOrder)
          .setParameter("code", code).setParameter("cfg", code + "-01").executeUpdate();
    }

    /** 造一条测试工序。 */
    protected void seedProcess(String noSuffix, String name, String category, boolean outsource,
                               String currency, String unit, String defectRate) {
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery(
                "INSERT INTO process_master " +
                "  (process_no, process_name, process_category, is_outsource, standard_currency," +
                "   standard_unit, default_defect_rate) " +
                "VALUES (:no, :nm, :cat, :out, :cur, :unit, CAST(:rate AS numeric))")
              .setParameter("no", PROCESS_NO_PREFIX + noSuffix).setParameter("nm", name)
              .setParameter("cat", category).setParameter("out", outsource)
              .setParameter("cur", currency).setParameter("unit", unit)
              .setParameter("rate", defectRate).executeUpdate());
    }

    // ═════════════════════════ 只读工具 ═════════════════════════

    protected long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? 0L : ((Number) v).longValue();
    }

    protected String scalar(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return rows.get(0).toString();
    }

    protected List<String> strList(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        List<String> out = new ArrayList<>();
        for (Object o : rows) out.add(o == null ? null : o.toString());
        return out;
    }

    protected boolean tableExists(String table) {
        return scalar("SELECT to_regclass('public.\"" + table + "\"')::text") != null;
    }

    protected boolean columnExists(String table, String column) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_name='"
                + table + "' AND column_name='" + column + "'") > 0;
    }

    /** DB 列长（AC-26「超 DB 列长」的判据来源 —— 🚫 不写死 65，见 UserImportApiTest 的注释）。 */
    protected int columnMaxLength(String table, String column) {
        String s = scalar("SELECT character_maximum_length::text FROM information_schema.columns " +
                "WHERE table_name='" + table + "' AND column_name='" + column + "'");
        assertNotNull(s, "取不到 " + table + "." + column + " 的列长");
        return Integer.parseInt(s);
    }

    /**
     * 🚨 反假绿护栏：断言「结果非空」，并把实际值打出来。
     * 数据为空 → 循环 0 次 / 分支没走到 → 断言压根没跑，测试照样报绿（testing.md §3）。
     */
    protected <T> List<T> assertNonEmpty(List<T> actual, String what) {
        System.out.println("[task260902] " + what + " = " + actual);
        assertTrue(actual != null && !actual.isEmpty(),
                "断言前置失败：" + what + " 为空 —— 后续断言会空跑（假绿）");
        return actual;
    }

    // ═════════════════════════ 管理员 session ═════════════════════════

    /**
     * 全 JVM 复用的管理员会话（🚨 登录限流 30 次/分/IP，每个用例各登一次会打满 ——
     * 打满后表现为「登录失败」，<b>看起来像鉴权坏了</b>，实际是测试基础设施问题）。
     */
    private static Map<String, String> ADMIN_COOKIES;

    /**
     * <b>子类一律用这个起手，不要用 {@code RestAssured.given()}。</b>
     * 返回自带管理员 session cookie 的请求规格（测试 profile 的 RBAC 是开着的，见类注释）。
     */
    protected RequestSpecification given() {
        return RestAssured.given().cookies(adminSession());
    }

    /** 取（必要时重建）管理员会话。每次先用 {@code /auth/me} 验明正身，过期就重登。 */
    protected Map<String, String> adminSession() {
        if (ADMIN_COOKIES != null) {
            Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
            if (me.statusCode() == 200) return ADMIN_COOKIES;
            System.out.println("[task260902] 缓存的 admin 会话已失效（/auth/me=" + me.statusCode() + "），重新登录");
            ADMIN_COOKIES = null;
        }
        // 只解锁，🚫 不改 admin 的密码 / 状态 / 角色（testing.md §4.3：不得改变共享库的全局状态）
        QuarkusTransaction.requiringNew().run(() ->
            em.createNativeQuery("UPDATE \"user\" SET failed_login_attempts = 0, locked_until = NULL " +
                    "WHERE username = 'admin'").executeUpdate());

        Response last = null;
        for (int i = 0; i < 3; i++) {
            last = RestAssured.given().contentType(ContentType.JSON)
                    .body(Map.of("username", "admin", "password", "Admin@2026"))
                    .post("/api/cpq/auth/login").thenReturn();
            if (last.statusCode() == 200) {
                ADMIN_COOKIES = new LinkedHashMap<>(last.getCookies());
                assertTrue(!ADMIN_COOKIES.isEmpty(), "登录 200 却没拿到 cookie（会话机制变了？）");
                Response me = RestAssured.given().cookies(ADMIN_COOKIES).get("/api/cpq/auth/me").thenReturn();
                assertEquals(200, me.statusCode(), "登录后 /auth/me 仍不通，body=" + me.asString());
                assertEquals("SYSTEM_ADMIN", me.jsonPath().getString("data.role"),
                        "🚨 拿到的会话不是 SYSTEM_ADMIN —— 后面所有「管理员能调通」的断言都会失去意义");
                System.out.println("[task260902] admin 登录成功，cookies=" + ADMIN_COOKIES.keySet());
                return ADMIN_COOKIES;
            }
            System.out.println("[task260902] admin 第 " + (i + 1) + " 次登录失败 status=" + last.statusCode()
                    + (last.statusCode() == 429 ? "（疑似登录限流 30/min/IP，退避重试）" : ""));
            try { Thread.sleep(3000L * (i + 1)); } catch (InterruptedException ignored) { }
        }
        throw new AssertionError("admin 登录连续 3 次失败，最后 status=" + last.statusCode()
                + " body=" + last.asString()
                + "。429 = 登录限流；423/401 = 账号被锁或密码不对。🚫 不要读成「导出功能坏了」。");
    }

    // ═════════════════════════ xlsx 读取 ═════════════════════════

    /** 读第一个 sheet 为「行 → 列 → 显示字符串」的二维表（不做任何列名解释）。 */
    protected List<List<String>> readSheet(byte[] xlsx) {
        List<List<String>> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertTrue(wb.getNumberOfSheets() > 0, "导出文件里一个 sheet 都没有");
            Sheet sh = wb.getSheetAt(0);
            for (int r = 0; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                List<String> line = new ArrayList<>();
                int last = row == null ? 0 : row.getLastCellNum();
                for (int c = 0; c < last; c++) line.add(cellText(row.getCell(c)));
                out.add(line);
            }
        } catch (Exception e) {
            throw new IllegalStateException("读 xlsx 失败（导出的可能根本不是 xlsx）", e);
        }
        return out;
    }

    /** 表头行（第 1 行）的逐列文字。 */
    protected List<String> header(byte[] xlsx) {
        List<List<String>> rows = readSheet(xlsx);
        assertTrue(!rows.isEmpty(), "导出文件连表头行都没有");
        return rows.get(0);
    }

    /** 数据行（表头以下，去掉整行全空的尾行）。 */
    protected List<List<String>> dataRows(byte[] xlsx) {
        List<List<String>> rows = readSheet(xlsx);
        List<List<String>> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.stream().allMatch(s -> s == null || s.isBlank())) continue;
            out.add(r);
        }
        return out;
    }

    /** 表头名 → 列下标。用于「按列名取值」，避免把列序写死在断言里。 */
    protected Map<String, Integer> headerIndex(byte[] xlsx) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<String> h = header(xlsx);
        for (int i = 0; i < h.size(); i++) if (h.get(i) != null && !h.get(i).isBlank()) idx.put(h.get(i), i);
        return idx;
    }

    protected String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() ? "" : (row.get(i) == null ? "" : row.get(i));
    }

    protected String cell(List<String> row, Map<String, Integer> idx, String colName) {
        Integer i = idx.get(colName);
        assertNotNull(i, "导出文件里没有列「" + colName + "」，实际列 = " + idx.keySet());
        return cell(row, i);
    }

    /**
     * 把单元格读成 BigDecimal（数字单元格取原值；文本单元格按字面解析）。
     * <p>AC-5 断言的是「值」不是「单元格类型」，所以两种写法都收；但类型会打出来供人复核。
     */
    protected BigDecimal decimal(String text, String what) {
        assertTrue(text != null && !text.isBlank(), what + " 为空 —— 断言会空跑");
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new AssertionError(what + " 不是数值：「" + text + "」（AC 要求写小数，不是带 % 的串）");
        }
    }

    /** 取表头单元格上的批注文本（AC-14 的角色列批注）。 */
    protected String headerComment(byte[] xlsx, int colIndex) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = wb.getSheetAt(0).getRow(0);
            if (row == null) return null;
            Cell c = row.getCell(colIndex);
            if (c == null) return null;
            Comment cm = c.getCellComment();
            return cm == null ? null : cm.getString().getString();
        } catch (Exception e) {
            throw new IllegalStateException("读批注失败", e);
        }
    }

    /** 单元格 → 显示字符串。数字保留原值（整数不带 .0），🚫 不做四舍五入。 */
    protected String cellText(Cell c) {
        if (c == null) return "";
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        switch (t) {
            case STRING:  return c.getStringCellValue();
            case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
            case BLANK:   return "";
            case NUMERIC: {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(c)) {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(c.getDateCellValue());
                }
                BigDecimal bd = BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros();
                return bd.scale() <= 0 ? bd.toBigInteger().toString() : bd.toPlainString();
            }
            default: return "";
        }
    }

    // ═════════════════════════ 报告体读取 ═════════════════════════

    /**
     * 取导入报告的根 Map。
     * <p>⚠️ 两条导入端点的包装不一致：材质导入返回<b>裸报告</b>（task-260901 实测），
     * 用户导入按 {@code api.md} 是 {@code ApiResponse<UserImportReportDTO>}（字段在 {@code data} 下）。
     * 这里按「有 data 且是对象就下钻」自适应，🚫 不因此放宽任何字段断言。
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> report(JsonPath jp) {
        Object data = jp.get("data");
        if (data instanceof Map && ((Map<String, Object>) data).containsKey("totalRows")) {
            return (Map<String, Object>) data;
        }
        Map<String, Object> root = jp.get("$");
        assertNotNull(root, "导入响应体为空");
        return root;
    }

    protected int intOf(Map<String, Object> rep, String key) {
        Object v = rep.get(key);
        assertNotNull(v, "报告里缺字段 " + key + "，实际字段 = " + rep.keySet());
        return ((Number) v).intValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> listOf(Map<String, Object> rep, String key) {
        Object v = rep.get(key);
        assertNotNull(v, "报告里缺字段 " + key + "，实际字段 = " + rep.keySet());
        return (List<Map<String, Object>>) v;
    }

    // ═════════════════════════ 构造 xlsx ═════════════════════════

    /** 按给定表头 + 数据行生成一份 xlsx（用户导入用例的输入夹具）。 */
    protected byte[] buildXlsx(List<String> headers, List<List<String>> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("用户");
            Row h = sh.createRow(0);
            for (int i = 0; i < headers.size(); i++) h.createCell(i).setCellValue(headers.get(i));
            for (int r = 0; r < rows.size(); r++) {
                Row row = sh.createRow(r + 1);
                List<String> data = rows.get(r);
                for (int c = 0; c < data.size(); c++) {
                    if (data.get(c) != null) row.createCell(c).setCellValue(data.get(c));
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
