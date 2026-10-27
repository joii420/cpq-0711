package com.cpq.task261026;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mindrot.jbcrypt.BCrypt;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-261026 · S-BE 黑盒接口/数据库验收（AC-1~AC-6、AC-10~AC-12）。
 *
 * <p>本类只依赖任务文档中的 HTTP 契约、正式 XLSX 与数据库可观测结果；不 import
 * {@code src/main} 的 Registry、Service、DTO 或编译器类型。测试数据统一使用
 * {@code T261026_BE_} 前缀，清理语句全部带该前缀的正向 WHERE 条件。
 *
 * <p>🚨 本类会让 Quarkus 执行 Flyway。未合并 V454 前不得在共享 test/dev 库运行；
 * 由主代理明确解锁后使用一次性库或已通知的共享测试库执行。
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("task-261026 · 物料利润后端契约")
class Task261026ProfitApiTest {

    private static final String PREFIX = "T261026_BE_";
    private static final String PASSWORD = "T261026@Profit";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Map<String, String> ROLE_USERS = Map.of(
            "SYSTEM_ADMIN", PREFIX + "ADMIN",
            "PRICING_MANAGER", PREFIX + "PM",
            "SALES_MANAGER", PREFIX + "SM",
            "SALES_REP", PREFIX + "SR");

    @Inject
    EntityManager em;

    @TestHTTPResource("/")
    URI testBaseUri;

    private final Map<String, String> sessions = new LinkedHashMap<>();
    private List<String> customerNos;

    @BeforeAll
    void suiteSetUp() {
        cleanupOwnRows();
        seedRoleUsers();
        customerNos = stringColumn(
                "SELECT code FROM customer WHERE status='ACTIVE' ORDER BY code LIMIT 2");
        assertTrue(customerNos.size() >= 2,
                "AC-4/10 前置：至少需要两个 ACTIVE 客户验证复合轴隔离；实际=" + customerNos);
        for (Map.Entry<String, String> e : ROLE_USERS.entrySet()) {
            sessions.put(e.getKey(), login(e.getValue(), PASSWORD, e.getKey()));
        }
    }

    @BeforeEach
    void testSetUp() {
        cleanupOwnBusinessRows();
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no LIKE '" + PREFIX + "%'") , "上一条测试遗留了物料夹具");
    }

    @AfterEach
    void testTearDown() {
        cleanupOwnBusinessRows();
    }

    @AfterAll
    void suiteTearDown() {
        cleanupOwnRows();
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material "
                + "WHERE material_no LIKE '" + PREFIX + "%'") , "利润测试物料未清干净");
        assertEquals(0L, count("SELECT count(*) FROM import_record "
                + "WHERE original_file_name LIKE '" + PREFIX + "%'") , "利润测试导入记录未清干净");
        assertEquals(0L, count("SELECT count(*) FROM \"user\" "
                + "WHERE username LIKE '" + PREFIX + "%'") , "利润测试用户未清干净");
    }

    @Test
    @Order(1)
    @DisplayName("T261026_BE_03 / AC-1：DB 列为可空 numeric(26,12)")
    void databaseColumnIsNullableFixedPoint() {
        @SuppressWarnings("unchecked")
        List<Object[]> cols = em.createNativeQuery("""
                SELECT data_type, numeric_precision, numeric_scale, is_nullable
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name='ds_quote_material' AND column_name='profit'
                """).getResultList();
        assertEquals(1, cols.size(), "AC-1：ds_quote_material.profit 必须存在且只能有一列");
        Object[] c = cols.get(0);
        assertEquals("numeric", String.valueOf(c[0]), "AC-1：profit 必须是 PostgreSQL numeric");
        assertEquals(26, ((Number) c[1]).intValue(), "AC-1：profit precision=26");
        assertEquals(12, ((Number) c[2]).intValue(), "AC-1：profit scale=12");
        assertEquals("YES", String.valueOf(c[3]), "AC-1：profit 必须可空");

    }

    @Test
    @Order(2)
    @DisplayName("T261026_BE_04 / AC-1：旧 9 列表头返回 400，明确缺少利润且零写入")
    void legacyNineColumnHeaderIsRejected() throws Exception {
        String materialNo = PREFIX + "OLD9";
        byte[] old = quoteWorkbook(materialNo, "1.25", true);
        Response r = importQuote(old, "T261026_BE_old9.xlsx", customerNos.get(0));
        System.out.println("[T261026_BE_04] status=" + r.statusCode() + " body=" + r.asString());
        assertEquals(400, r.statusCode(), "AC-1：缺利润表头的旧模板必须返回 400");
        assertTrue(r.asString().contains("利润") && (r.asString().contains("缺") || r.asString().contains("表头")),
                "AC-1：错误必须明确指出缺少利润表头，实际=" + r.asString());
        assertEquals(0L, countMaterial(materialNo), "AC-1：结构校验失败不得写入物料行");
    }

    @Test
    @Order(3)
    @DisplayName("T261026_BE_05 / AC-2：负数、零、正 12 位小数精确导入")
    void signedNumbersImportWithoutDoubleLoss() throws Exception {
        Map<String, String> cases = Map.of(
                PREFIX + "NEG", "-12.34",
                PREFIX + "ZERO", "0",
                PREFIX + "POS12", "123.456789012345");
        for (Map.Entry<String, String> e : cases.entrySet()) {
            Response r = importQuote(quoteWorkbook(e.getKey(), e.getValue(), false),
                    e.getKey() + ".xlsx", customerNos.get(0));
            assertEquals(200, r.statusCode(), e.getKey() + " 导入失败：" + r.asString());
            String db = scalarString("SELECT profit::text FROM ds_quote_material WHERE material_no=:mn "
                    + "AND customer_no=:cn", "mn", e.getKey(), "cn", customerNos.get(0));
            assertDecimalEquals(e.getValue(), db, "AC-2：" + e.getKey() + " 精度不符");
            Map<String, Object> item = findPart(e.getKey(), customerNos.get(0));
            assertTrue(item.containsKey("profit"), "AC-6：quote item 必须恒含 profit 键：" + item);
            assertTrue(item.get("profit") instanceof String,
                    "AC-6：非空 profit 必须是 JSON string，不能是 number：" + item);
            assertDecimalEquals(e.getValue(), String.valueOf(item.get("profit")),
                    "AC-2/6：GET 返回值精度不符");
        }
    }

    @Test
    @Order(4)
    @DisplayName("T261026_BE_06 / AC-2：abc 返回定位完整的 400，整份零写入")
    void invalidNumberIsAtomicAndLocatable() throws Exception {
        String materialNo = PREFIX + "ABC";
        long before = count("SELECT count(*) FROM ds_quote_material WHERE material_no LIKE '"
                + PREFIX + "%'");
        String materialTableBefore = fingerprint("ds_quote_material", null);
        Response r = importQuote(quoteWorkbook(materialNo, "abc", false),
                "T261026_BE_abc.xlsx", customerNos.get(0));
        String body = r.asString();
        System.out.println("[T261026_BE_06] status=" + r.statusCode() + " body=" + body);
        assertEquals(400, r.statusCode(), "AC-2：非数字利润必须 400");
        assertTrue(body.contains("物料") && body.contains("利润") && body.contains("不是合法数值"),
                "AC-2：错误须包含 sheet=物料、column=利润、reason=不是合法数值，实际=" + body);
        assertTrue(body.contains("2") || body.toLowerCase().contains("row"),
                "AC-2：错误须含对应 Excel 行号，实际=" + body);
        assertEquals(before, count("SELECT count(*) FROM ds_quote_material WHERE material_no LIKE '"
                + PREFIX + "%'") , "AC-2：非法文件必须整份零写入");
        assertEquals(materialTableBefore, fingerprint("ds_quote_material", null),
                "AC-2：非法利润导入后物料表数量与内容摘要必须全部不变");
        assertEquals(0L, countMaterial(materialNo), "AC-2：非法行不得落库");
    }

    @Test
    @Order(5)
    @DisplayName("T261026_BE_07 / AC-3~AC-5：空初值、非空覆盖、页面改值后空导入保留")
    void blankInitialOverwriteAndPreserveSequence() throws Exception {
        String blankNo = PREFIX + "BLANK";
        Response blank = importQuote(quoteWorkbook(blankNo, null, false),
                "T261026_BE_blank.xlsx", customerNos.get(0));
        assertEquals(200, blank.statusCode(), "AC-3：空利润新物料应导入成功：" + blank.asString());
        assertNull(scalar("SELECT profit FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", blankNo, "cn", customerNos.get(0)), "AC-3：首次空值必须落 NULL");
        Map<String, Object> blankItem = findPart(blankNo, customerNos.get(0));
        assertTrue(blankItem.containsKey("profit") && blankItem.get("profit") == null,
                "AC-6：NULL 也必须保留 profit 键：" + blankItem);

        String seqNo = PREFIX + "SEQ";
        assertEquals(200, importQuote(quoteWorkbook(seqNo, "1.25", false),
                "T261026_BE_seq1.xlsx", customerNos.get(0)).statusCode());
        insertMaterial(seqNo, customerNos.get(1), "99");

        Response overwrite = importQuote(quoteWorkbook(seqNo, "4.5", false),
                "T261026_BE_seq2.xlsx", customerNos.get(0));
        assertEquals(200, overwrite.statusCode(), "AC-4：非空重导应成功：" + overwrite.asString());
        assertDbProfit(seqNo, customerNos.get(0), "4.5", "AC-4：目标客户应被非空值覆盖");
        assertDbProfit(seqNo, customerNos.get(1), "99", "AC-4：另一客户同料号不得被覆盖");

        Response pagePut = putProfit(adminSession(), seqNo, customerNos.get(0), "-2.5");
        assertEquals(200, pagePut.statusCode(), "AC-5 前置 PUT 失败：" + pagePut.asString());
        assertDbProfit(seqNo, customerNos.get(0), "-2.5", "AC-5：页面维护值应落库");

        Response blankAgain = importQuote(quoteWorkbook(seqNo, null, false),
                "T261026_BE_seq3-blank.xlsx", customerNos.get(0));
        assertEquals(200, blankAgain.statusCode(), "AC-5：空白重导应成功：" + blankAgain.asString());
        assertDbProfit(seqNo, customerNos.get(0), "-2.5", "AC-5：空白重导必须保留页面维护值");
        assertDecimalEquals("-2.5", String.valueOf(findPart(seqNo, customerNos.get(0)).get("profit")),
                "AC-5：GET 仍须返回页面维护后的值");
    }

    @Test
    @Order(6)
    @DisplayName("T261026_BE_08 / AC-6：quote 恒含 profit；cost 两套省略；列表固定 2 SQL")
    void listContractAndNoNPlusOne() {
        for (int i = 0; i < 6; i++) {
            insertMaterial(PREFIX + "LIST_" + i, customerNos.get(0), i % 2 == 0 ? null : String.valueOf(i));
        }

        // 先预热同一路径，避免首次类加载/懒初始化污染 SQL 统计。
        parts("quote", PREFIX + "LIST_");
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Response quote = parts("quote", PREFIX + "LIST_");
        assertEquals(200, quote.statusCode(), quote.asString());
        List<Map<String, Object>> quoteItems = quote.jsonPath().getList("data.items");
        assertNotNull(quoteItems, "AC-6：quote 响应缺 data.items");
        assertEquals(6, quoteItems.size(), "AC-6：私有前缀应精确命中 6 行，实际=" + quoteItems);
        assertTrue(quoteItems.stream().allMatch(item -> item.containsKey("profit")),
                "AC-6：quote 每个 item（含 NULL）必须恒含 profit 键：" + quoteItems);
        long sql = statistics.getPrepareStatementCount();
        System.out.println("[T261026_BE_08] quote items=" + quoteItems.size() + " SQL=" + sql);
        assertEquals(2L, sql, "AC-6：列表必须恒为 count + page 两条 SQL，不得逐行查询");

        for (String dataset : List.of("cost-basic", "cost-detail")) {
            Response cost = parts(dataset, null);
            assertEquals(200, cost.statusCode(), dataset + " 列表失败：" + cost.asString());
            List<Map<String, Object>> items = cost.jsonPath().getList("data.items");
            assertNotNull(items, dataset + " 响应缺 data.items");
            assertFalse(items.isEmpty(), dataset + " 测试库无样本，省略 profit 的断言会空跑，应补数据后再验");
            assertTrue(items.stream().noneMatch(item -> item.containsKey("profit")),
                    "AC-6：" + dataset + " item 不得出现 profit：" + items.get(0));
        }
    }

    @Test
    @Order(7)
    @DisplayName("T261026_BE_09 / AC-9~AC-10：清空、失败不改值、复合轴守卫与四角色权限")
    void putClearValidationIsolationAndRoles() {
        String materialNo = PREFIX + "PUT";
        insertMaterial(materialNo, customerNos.get(0), "8.75");
        String sourceBefore = scalarString("SELECT source FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", materialNo, "cn", customerNos.get(0));
        String updatedBefore = scalarString("SELECT updated_at::text FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", materialNo, "cn", customerNos.get(0));

        for (Map.Entry<String, String> e : sessions.entrySet()) {
            String value = switch (e.getKey()) {
                case "SYSTEM_ADMIN" -> "12.5";
                case "PRICING_MANAGER" -> "-2.5";
                case "SALES_MANAGER" -> "0";
                default -> "3.141592653589";
            };
            Response r = putProfit(e.getValue(), materialNo, customerNos.get(0), value);
            assertEquals(200, r.statusCode(), "AC-10：角色 " + e.getKey() + " 应可维护利润：" + r.asString());
            assertDbProfit(materialNo, customerNos.get(0), value,
                    "AC-10：角色 " + e.getKey() + " 的值未落库");
        }
        assertEquals(sourceBefore,
                scalarString("SELECT source FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                        "mn", materialNo, "cn", customerNos.get(0)),
                "AC-9：维护利润不得改变行级 source");
        String updatedAfter = scalarString("SELECT updated_at::text FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", materialNo, "cn", customerNos.get(0));
        assertFalse(Objects.equals(updatedBefore, updatedAfter),
                "AC-9：维护利润后 updated_at 必须推进，before=" + updatedBefore + " after=" + updatedAfter);
        assertNotNull(scalar("SELECT updated_by FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                        "mn", materialNo, "cn", customerNos.get(0)),
                "AC-9：维护利润后 updated_by 必须被记录");

        String stable = scalarString("SELECT profit::text FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", materialNo, "cn", customerNos.get(0));
        Response invalid = putRaw(adminSession(), materialNo, customerNos.get(0), Map.of("profit", "abc"));
        assertEquals(400, invalid.statusCode(), "AC-8/9：非数字 PUT 必须 400：" + invalid.asString());
        assertTrue(invalid.asString().contains("利润"), "AC-8：错误须点名利润：" + invalid.asString());
        assertEquals(stable,
                scalarString("SELECT profit::text FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                        "mn", materialNo, "cn", customerNos.get(0)),
                "AC-9：非法 PUT 后原值必须不变");

        Response clear = putRaw(adminSession(), materialNo, customerNos.get(0), singletonNullBody());
        assertEquals(200, clear.statusCode(), "AC-9：显式 null 应成功：" + clear.asString());
        assertNull(scalar("SELECT profit FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", materialNo, "cn", customerNos.get(0)), "AC-9：显式清空必须落 SQL NULL");

        String shared = PREFIX + "DUP";
        insertMaterial(shared, customerNos.get(0), "11");
        insertMaterial(shared, customerNos.get(1), "22");
        Response conflict = request(adminSession())
                .contentType(ContentType.JSON).body(Map.of("profit", "33"))
                .put("/api/cpq/dataset/quote/parts/{axis}", shared);
        assertEquals(409, conflict.statusCode(), "AC-10：漏 customerNo 命中多行必须 409：" + conflict.asString());
        assertDbProfit(shared, customerNos.get(0), "11", "AC-10：409 后客户 A 不得变化");
        assertDbProfit(shared, customerNos.get(1), "22", "AC-10：409 后客户 B 不得变化");

        Response isolated = putProfit(adminSession(), shared, customerNos.get(1), "-7.25");
        assertEquals(200, isolated.statusCode(), "AC-10：带 customerNo 的精确更新应成功：" + isolated.asString());
        assertDbProfit(shared, customerNos.get(0), "11", "AC-10：客户 A 不得串改");
        assertDbProfit(shared, customerNos.get(1), "-7.25", "AC-10：客户 B 应被精确更新");
    }

    @Test
    @Order(8)
    @DisplayName("T261026_BE_10 / AC-11~AC-12：字段树、编译、预览可用且存量配置/快照零改写")
    void fieldTreeCompilePreviewAndExistingStateRemainUntouched() {
        String valueNo = PREFIX + "PREVIEW_VALUE";
        String nullNo = PREFIX + "PREVIEW_NULL";
        insertMaterial(valueNo, customerNos.get(0), "-2.5");
        insertMaterial(nullNo, customerNos.get(0), null);

        Map<String, String> before = snapshotExistingConfigurations();
        assertTrue(existingBuilderConfigCount() > 0,
                "AC-12：测试库没有任何存量 builder_config，零改写断言会空跑");

        Response tree = request(adminSession())
                .queryParam("tabType", "主件").queryParam("variantKey", "").queryParam("dialect", "QUOTE")
                .get("/api/cpq/config/semantic-graph/field-tree");
        assertEquals(200, tree.statusCode(), "AC-11：field-tree 失败：" + tree.asString());
        List<Map<String, Object>> groups = tree.jsonPath().getList("groups");
        assertNotNull(groups, "AC-11：field-tree 缺 groups");
        assertFalse(groups.isEmpty(), "AC-11：field-tree groups 为空，后续断言会空跑");

        Map<String, Object> profit = null;
        List<Map<String, Object>> materialFields = null;
        for (Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) group.get("fields");
            if (fields == null) continue;
            for (Map<String, Object> field : fields) {
                if ("MATERIAL".equals(String.valueOf(field.get("sourceNodeKey")))
                        && "profit".equals(String.valueOf(field.get("sourceColumn")))) {
                    profit = field;
                    materialFields = fields;
                }
            }
        }
        assertNotNull(profit, "AC-11：QUOTE/物料字段树找不到 MATERIAL.profit；响应=" + tree.asString());
        assertEquals("利润", String.valueOf(profit.get("displayName")), "AC-11 displayName");
        assertEquals("NUMBER", String.valueOf(profit.get("dataType")), "AC-11 dataType");
        assertEquals("_物料_利润", String.valueOf(profit.get("viewColumn")), "AC-11 viewColumn");
        assertEquals(List.of(), profit.get("roles"), "AC-11 roles 必须为空列表");
        assertNull(profit.get("lookupLib"), "AC-11 lookupLib 必须为 null");
        assertEquals(false, profit.get("isCore"), "AC-11 isCore 必须为 false");
        assertEquals(false, profit.get("elemKey"), "AC-11 elemKey 必须为 false");
        assertEquals("profit", String.valueOf(materialFields.get(materialFields.size() - 1).get("sourceColumn")),
                "AC-11：利润必须排在物料现有字段末尾");

        UUID componentId = createBlankComponent();
        String config = """
                {
                  "dialect":"QUOTE",
                  "tabType":"主件",
                  "variantKey":"",
                  "columns":[
                    {"sourceNodeKey":"MATERIAL","sourceColumn":"material_no","fieldName":"销售料号","isRowKey":true,"isPartNo":true},
                    {"sourceNodeKey":"MATERIAL","sourceColumn":"profit","fieldName":"利润","fieldType":"INPUT_NUMBER"}
                  ]
                }
                """;
        Response compile = request(adminSession())
                .contentType(ContentType.JSON).body(config)
                .post("/api/cpq/components/{id}/builder/compile", componentId);
        assertEquals(200, compile.statusCode(), "AC-11：compile 失败：" + compile.asString());
        String sql = compile.jsonPath().getString("sql");
        assertNotNull(sql, "AC-11：compile 响应缺 sql：" + compile.asString());
        assertTrue(sql.contains("ds_quote_material") && sql.contains(".profit") && sql.contains("_物料_利润"),
                "AC-11：编译 SQL 必须投影 ds_quote_material.profit AS _物料_利润，实际=" + sql);

        assertPreviewProfit(componentId, config, valueNo, "-2.5");
        assertPreviewProfit(componentId, config, nullNo, null);

        Map<String, String> after = snapshotExistingConfigurations();
        assertEquals(before, after,
                "AC-12：新增可选字段及 compile/preview 不得改写存量组件、builder_config、模板或报价快照");
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    private Response importQuote(byte[] xlsx, String fileName, String customerNo) {
        return request(adminSession())
                .multiPart("file", fileName, xlsx, XLSX_MIME)
                .multiPart("customerNo", customerNo)
                .post("/api/cpq/dataset/quote/import");
    }

    private Response parts(String dataset, String keyword) {
        var request = request(adminSession())
                .queryParam("page", 0).queryParam("size", 200);
        if (keyword != null) request.queryParam("keyword", keyword);
        return request.get("/api/cpq/dataset/{dataset}/parts", dataset);
    }

    private Map<String, Object> findPart(String materialNo, String customerNo) {
        Response r = request(adminSession())
                .queryParam("page", 0).queryParam("size", 50)
                .queryParam("keyword", materialNo).queryParam("customerNo", customerNo)
                .get("/api/cpq/dataset/quote/parts");
        assertEquals(200, r.statusCode(), "GET parts 失败：" + r.asString());
        List<Map<String, Object>> items = r.jsonPath().getList("data.items");
        assertNotNull(items, "GET parts 缺 data.items：" + r.asString());
        List<Map<String, Object>> exact = items.stream()
                .filter(item -> materialNo.equals(String.valueOf(item.get("axisValue")))
                        && customerNo.equals(String.valueOf(item.get("customerNo"))))
                .toList();
        assertEquals(1, exact.size(), "料号+客户应精确命中一行，实际=" + exact + "；完整响应=" + r.asString());
        return exact.get(0);
    }

    private Response putProfit(String session, String materialNo, String customerNo, String profit) {
        return putRaw(session, materialNo, customerNo, Map.of("profit", profit));
    }

    private Response putRaw(String session, String materialNo, String customerNo, Map<String, Object> body) {
        var request = request(session).contentType(ContentType.JSON).body(body);
        if (customerNo != null) request.queryParam("customerNo", customerNo);
        return request.put("/api/cpq/dataset/quote/parts/{axis}", materialNo);
    }

    private static Map<String, Object> singletonNullBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profit", null);
        return body;
    }

    private String login(String username, String password, String expectedRole) {
        Response r = request().contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .post("/api/cpq/auth/login");
        assertEquals(200, r.statusCode(), "测试账号登录失败：" + username + " body=" + r.asString());
        assertEquals(expectedRole, r.jsonPath().getString("data.role"),
                username + " 的角色不符，权限断言无效");
        String cookie = r.cookie("CPQ_SESSION");
        assertNotNull(cookie, username + " 登录 200 但没有 CPQ_SESSION");
        return cookie;
    }

    private String adminSession() {
        String session = sessions.get("SYSTEM_ADMIN");
        assertNotNull(session, "SYSTEM_ADMIN 测试会话尚未建立");
        return session;
    }

    private RequestSpecification request() {
        assertNotNull(testBaseUri, "Quarkus @TestHTTPResource 未在测试生命周期前注入");
        return RestAssured.given().baseUri(testBaseUri.toString());
    }

    private RequestSpecification request(String session) {
        return request().cookie("CPQ_SESSION", session);
    }

    // ───────────────────────────── Excel ─────────────────────────────

    private byte[] quoteWorkbook(String materialNo, String profit, boolean removeProfitHeader) throws Exception {
        Path template = task260902Dir().resolve("报价 - 数据导入与表格建表.xlsx");
        assertTrue(Files.isRegularFile(template), "正式报价模板不存在：" + template);
        try (InputStream in = Files.newInputStream(template);
             Workbook workbook = new XSSFWorkbook(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            stripMarkerRows(workbook);
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (!"物料".equals(sheet.getSheetName())) clearDataRows(sheet);
            }

            // 利润用例只改物料数据；其他 Sheet 仍必须满足当前严格表头合同。
            ensureOptionalHeader(workbook.getSheet("客户料号"), "结算单位", "客户料号名称");

            Sheet material = workbook.getSheet("物料");
            assertNotNull(material, "正式模板缺「物料」Sheet");
            Row seed = firstNonBlankDataRow(material);
            assertNotNull(seed, "正式模板的物料 Sheet 没有可复制的数据行");
            int seedIndex = seed.getRowNum();
            for (int i = material.getLastRowNum(); i >= 1; i--) {
                if (i != seedIndex && material.getRow(i) != null) material.removeRow(material.getRow(i));
            }
            if (seedIndex != 1) {
                Row target = material.getRow(1);
                if (target == null) target = material.createRow(1);
                copyValues(seed, target);
                material.removeRow(seed);
                seed = target;
            }

            writeText(material, seed, "销售料号", materialNo);
            writeText(material, seed, "类型", "零件");
            writeText(material, seed, "利润", profit);
            if (removeProfitHeader) {
                int profitCol = headerIndex(material, "利润");
                Cell header = material.getRow(0).getCell(profitCol);
                if (header != null) header.setBlank();
                Cell value = seed.getCell(profitCol);
                if (value != null) value.setBlank();
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void stripMarkerRows(Workbook workbook) {
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            for (int i = sheet.getLastRowNum(); i >= 1; i--) {
                Row row = sheet.getRow(i);
                if (!isMarkerRow(row)) continue;
                sheet.removeRow(row);
                if (i < sheet.getLastRowNum()) sheet.shiftRows(i + 1, sheet.getLastRowNum(), -1);
            }
        }
    }

    private static boolean isMarkerRow(Row row) {
        if (row == null) return false;
        boolean any = false;
        for (int i = 0; i < row.getLastCellNum(); i++) {
            String value = cellText(row.getCell(i));
            if (value.isBlank()) continue;
            if (!"轴".equals(value) && !"对比项".equals(value)) return false;
            any = true;
        }
        return any;
    }

    private static void clearDataRows(Sheet sheet) {
        for (int i = sheet.getLastRowNum(); i >= 1; i--) {
            Row row = sheet.getRow(i);
            if (row != null) sheet.removeRow(row);
        }
    }

    private static Row firstNonBlankDataRow(Sheet sheet) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if (!cellText(row.getCell(c)).isBlank()) return row;
            }
        }
        return null;
    }

    private static void copyValues(Row source, Row target) {
        for (int i = 0; i < source.getLastCellNum(); i++) {
            Cell src = source.getCell(i);
            Cell dst = target.getCell(i);
            if (dst == null) dst = target.createCell(i);
            if (src == null || src.getCellType() == CellType.BLANK) {
                dst.setBlank();
            } else if (src.getCellType() == CellType.NUMERIC) {
                dst.setCellValue(src.getNumericCellValue());
            } else if (src.getCellType() == CellType.BOOLEAN) {
                dst.setCellValue(src.getBooleanCellValue());
            } else {
                dst.setCellValue(cellText(src));
            }
        }
    }

    private static void writeText(Sheet sheet, Row row, String header, String value) {
        int index = headerIndex(sheet, header);
        Cell cell = row.getCell(index);
        if (cell == null) cell = row.createCell(index);
        cell.setBlank();
        if (value != null) cell.setCellValue(value);
    }

    private static void ensureOptionalHeader(Sheet sheet, String headerName, String styleSourceHeader) {
        assertNotNull(sheet, "正式模板缺 Sheet");
        Row header = sheet.getRow(0);
        assertNotNull(header, sheet.getSheetName() + " 缺表头");
        for (int i = 0; i < header.getLastCellNum(); i++) {
            if (headerName.equals(cellText(header.getCell(i)))) return;
        }
        int styleSource = headerIndex(sheet, styleSourceHeader);
        int appendAt = Math.max(0, header.getLastCellNum());
        Cell added = header.createCell(appendAt, CellType.STRING);
        added.setCellValue(headerName);
        added.setCellStyle(header.getCell(styleSource).getCellStyle());
    }

    private static int headerIndex(Sheet sheet, String wanted) {
        Row header = sheet.getRow(0);
        assertNotNull(header, sheet.getSheetName() + " 缺表头");
        for (int i = 0; i < header.getLastCellNum(); i++) {
            if (wanted.equals(cellText(header.getCell(i)))) return i;
        }
        throw new AssertionError(sheet.getSheetName() + " 缺表头「" + wanted + "」");
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    // ───────────────────────────── DB fixtures ─────────────────────────────

    private void seedRoleUsers() {
        String hash = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(10));
        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM \"user\" WHERE username LIKE :prefix")
                        .setParameter("prefix", PREFIX + "%").executeUpdate();
                ROLE_USERS.forEach((role, username) -> em.createNativeQuery("""
                        INSERT INTO "user" (username, full_name, email, password_hash, role, status,
                                            is_first_login, failed_login_attempts)
                        VALUES (:username, :fullName, :email, :hash, :role, 'ACTIVE', false, 0)
                        """)
                        .setParameter("username", username)
                        .setParameter("fullName", "利润测试 " + role)
                        .setParameter("email", username.toLowerCase() + "@example.invalid")
                        .setParameter("hash", hash)
                        .setParameter("role", role)
                        .executeUpdate());
            }));
    }

    private void insertMaterial(String materialNo, String customerNo, String profit) {
        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
            var query = em.createNativeQuery("""
                    INSERT INTO ds_quote_material
                      (material_no, material_name, customer_no, profit, source, created_at, updated_at)
                    VALUES (:mn, :name, :cn, CAST(:profit AS numeric), 'IMPORT', now(), now() - interval '1 minute')
                    """)
                    .setParameter("mn", materialNo)
                    .setParameter("name", PREFIX + "物料")
                    .setParameter("cn", customerNo)
                    .setParameter("profit", profit);
            query.executeUpdate();
        }));
    }

    private void cleanupOwnBusinessRows() {
        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
            if (tableExists("component_sql_view") && tableExists("component")) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                                + "(SELECT id FROM component WHERE code LIKE :prefix)")
                        .setParameter("prefix", PREFIX + "%").executeUpdate();
            }
            if (tableExists("component")) {
                em.createNativeQuery("DELETE FROM component WHERE code LIKE :prefix OR name LIKE :prefix")
                        .setParameter("prefix", PREFIX + "%").executeUpdate();
            }
            if (tableExists("ds_quote_material")) {
                em.createNativeQuery("DELETE FROM ds_quote_material WHERE material_no LIKE :prefix")
                        .setParameter("prefix", PREFIX + "%").executeUpdate();
            }
            for (String table : List.of("ds_cost_basic_material", "ds_cost_detail_material")) {
                if (tableExists(table)) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE production_no LIKE :prefix")
                            .setParameter("prefix", PREFIX + "%").executeUpdate();
                }
            }
        }));
    }

    private void cleanupOwnRows() {
        cleanupOwnBusinessRows();
        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
            if (tableExists("import_record")) {
                em.createNativeQuery("DELETE FROM import_record WHERE original_file_name LIKE :prefix")
                        .setParameter("prefix", PREFIX + "%").executeUpdate();
            }
            if (tableExists("user")) {
                em.createNativeQuery("DELETE FROM \"user\" WHERE username LIKE :prefix")
                        .setParameter("prefix", PREFIX + "%").executeUpdate();
            }
        }));
    }

    private UUID createBlankComponent() {
        String name = PREFIX + "COMP_" + UUID.randomUUID();
        Response r = request(adminSession())
                .contentType(ContentType.JSON).body(Map.of("name", name))
                .post("/api/cpq/components");
        assertEquals(200, r.statusCode(), "创建私有测试组件失败：" + r.asString());
        String id = r.jsonPath().getString("data.id");
        assertNotNull(id, "创建组件响应缺 data.id：" + r.asString());
        return UUID.fromString(id);
    }

    // ───────────────────────────── assertions/snapshots ─────────────────────────────

    private void assertPreviewProfit(UUID componentId, String config, String materialNo, String expected) {
        String body = config.substring(0, config.lastIndexOf('}'))
                + ",\"partNo\":\"" + materialNo + "\",\"customerCode\":\"" + customerNos.get(0) + "\"}";
        Response preview = request(adminSession())
                .contentType(ContentType.JSON).body(body)
                .post("/api/cpq/components/{id}/builder/preview", componentId);
        assertEquals(200, preview.statusCode(), "AC-11：preview 失败：" + preview.asString());
        List<Map<String, Object>> rows = preview.jsonPath().getList("rows");
        assertNotNull(rows, "AC-11：preview 响应缺 rows：" + preview.asString());
        assertEquals(1, rows.size(), "AC-11：私有料号预览必须精确返回 1 行，实际=" + rows);
        Map<String, Object> row = rows.get(0);
        String key = row.containsKey("_物料_利润") ? "_物料_利润"
                : row.containsKey("利润") ? "利润" : null;
        assertNotNull(key, "AC-11：预览行缺利润投影（_物料_利润/利润）：" + row);
        if (expected == null) {
            assertNull(row.get(key), "AC-11：空利润预览必须返回 null：" + row);
        } else {
            assertDecimalEquals(expected, String.valueOf(row.get(key)),
                    "AC-11：预览利润不符");
        }
    }

    private Map<String, String> snapshotExistingConfigurations() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("component", fingerprint("component",
                "COALESCE(code,'') NOT LIKE '" + PREFIX + "%' AND COALESCE(name,'') NOT LIKE '" + PREFIX + "%'"));
        snapshot.put("component_sql_view", scalarString("""
                SELECT count(*)::text || ':' || COALESCE(md5(string_agg(to_jsonb(v)::text, '|' ORDER BY v.id::text)), 'empty')
                FROM component_sql_view v JOIN component c ON c.id=v.component_id
                WHERE COALESCE(c.code,'') NOT LIKE :prefix AND COALESCE(c.name,'') NOT LIKE :prefix
                """, "prefix", PREFIX + "%"));
        for (String table : List.of("template", "template_component_snapshot",
                "quotation_line_component_data", "quotation_line_item")) {
            assertTrue(tableExists(table), "AC-12：快照表不存在，无法验证零改写：" + table);
            snapshot.put(table, fingerprint(table, null));
        }
        System.out.println("[T261026_BE_10] snapshot=" + snapshot);
        return snapshot;
    }

    private long existingBuilderConfigCount() {
        return count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id "
                + "WHERE v.builder_config IS NOT NULL AND COALESCE(c.code,'') NOT LIKE '" + PREFIX + "%'");
    }

    private String fingerprint(String table, String where) {
        String sql = "SELECT count(*)::text || ':' || COALESCE(md5(string_agg(to_jsonb(t)::text, '|' "
                + "ORDER BY t.id::text)), 'empty') FROM " + table + " t"
                + (where == null ? "" : " WHERE " + where);
        return scalarString(sql);
    }

    private void assertDbProfit(String materialNo, String customerNo, String expected, String message) {
        String actual = scalarString("SELECT profit::text FROM ds_quote_material WHERE material_no=:mn AND customer_no=:cn",
                "mn", materialNo, "cn", customerNo);
        assertDecimalEquals(expected, actual, message);
    }

    private static void assertDecimalEquals(String expected, String actual, String message) {
        assertNotNull(actual, message + "（实际为 null）");
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                message + "：expected=" + expected + " actual=" + actual);
    }

    private long countMaterial(String materialNo) {
        return ((Number) scalar("SELECT count(*) FROM ds_quote_material WHERE material_no=:mn", "mn", materialNo))
                .longValue();
    }

    private long count(String sql) {
        return inRequestContext(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private Object scalar(String sql, Object... nameValuePairs) {
        return inRequestContext(() -> {
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < nameValuePairs.length; i += 2) {
                query.setParameter(String.valueOf(nameValuePairs[i]), nameValuePairs[i + 1]);
            }
            List<?> rows = query.getResultList();
            assertEquals(1, rows.size(), "查询应精确返回 1 行：" + sql + " 实际=" + rows.size());
            return rows.get(0);
        });
    }

    private String scalarString(String sql, Object... nameValuePairs) {
        Object value = scalar(sql, nameValuePairs);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringColumn(String sql) {
        return inRequestContext(() -> ((List<Object>) em.createNativeQuery(sql).getResultList()).stream()
                .filter(Objects::nonNull).map(String::valueOf).toList());
    }

    /**
     * JUnit PER_CLASS 的 BeforeAll/AfterAll 不在 Quarkus 默认请求上下文内。
     * 直接 EntityManager 辅助方法在必要时临时激活上下文；测试方法已有上下文时不重复激活。
     */
    private static <T> T inRequestContext(Supplier<T> action) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean activated = !requestContext.isActive();
        if (activated) requestContext.activate();
        try {
            return action.get();
        } finally {
            if (activated) requestContext.terminate();
        }
    }

    private static void inRequestContext(Runnable action) {
        inRequestContext(() -> {
            action.run();
            return null;
        });
    }

    private boolean tableExists(String table) {
        return count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='"
                + table.replace("'", "''") + "'") > 0;
    }

    private static Path task260902Dir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("dev-docs")
                    .resolve("task-260902-报价与核价建表与导入方案新规范");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("找不到 task-260902 规范目录，cwd=" + Path.of("").toAbsolutePath());
    }

}
