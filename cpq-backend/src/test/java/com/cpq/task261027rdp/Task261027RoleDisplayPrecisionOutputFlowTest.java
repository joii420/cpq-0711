package com.cpq.task261027rdp;

import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.ext.mail.MailAttachment;
import io.vertx.ext.mail.MailMessage;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.mindrot.jbcrypt.BCrypt;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-261027 · S-FLOW 服务端输出流（AC-5 / AC-10）。
 *
 * <p>通过两个真实会话调用六个既有输出端点，并解析 HTML、xlsx 与 MockMailbox 真实附件。
 * 夹具只使用 {@code T261027_RDP_}/{@code T261027_RDP_FLOW_} 前缀；本类会启动 Quarkus、
 * 连接数据库并写私有数据，未获主线提供一次性库并解锁前禁止执行。
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("T261027_RDP_FLOW_ · 六输出按真实会话角色显示精度")
class Task261027RoleDisplayPrecisionOutputFlowTest {

    private static final String USER_PREFIX = "T261027_RDP_";
    private static final String OUTPUT_USER_PREFIX = USER_PREFIX + "OUTPUT_";
    private static final String DATA_PREFIX = "T261027_RDP_FLOW_";
    private static final String PASSWORD = "T261027@Rdp";
    private static final String RAW = "1.2345678915";
    private static final String DB_RAW = "1.234567891500";
    private static final String SALES_DISPLAY = "1.2346";
    private static final String OTHER_DISPLAY = "1.234567892";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern DECIMAL_TOKEN =
            Pattern.compile("-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final int EXPECTED_TESTS = 4;

    private static final Map<String, String> ROLE_USERS = Map.of(
            "SALES_MANAGER", OUTPUT_USER_PREFIX + "SM",
            "SYSTEM_ADMIN", OUTPUT_USER_PREFIX + "ADMIN");

    @Inject EntityManager em;
    @Inject MockMailbox mailbox;
    @TestHTTPResource("/") URI testBaseUri;

    private final Map<String, String> sessions = new LinkedHashMap<>();
    private final Map<String, UUID> quotationIds = new LinkedHashMap<>();
    private final AtomicInteger successfulTests = new AtomicInteger();
    private final AtomicBoolean failedOrAborted = new AtomicBoolean();
    private UUID customerId;
    private UUID templateId;

    @RegisterExtension
    final TestWatcher preserveFailedFixture = new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
            successfulTests.incrementAndGet();
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            failedOrAborted.set(true);
        }

        @Override
        public void testAborted(ExtensionContext context, Throwable cause) {
            failedOrAborted.set(true);
        }

        @Override
        public void testDisabled(ExtensionContext context, java.util.Optional<String> reason) {
            failedOrAborted.set(true);
        }
    };

    @BeforeAll
    void setUpSuite() {
        assertEquals(0L, count("SELECT count(*) FROM \"user\" WHERE username LIKE '" + OUTPUT_USER_PREFIX + "%'"),
                "一次性库已有同前缀用户；为保留失败现场，本测试拒绝自动扩大清理");
        assertEquals(0L, count("SELECT count(*) FROM quotation WHERE name LIKE '" + DATA_PREFIX + "OUTPUT_%'"),
                "一次性库已有同前缀报价；为保留失败现场，本测试拒绝自动扩大清理");

        seedRoleUsers();
        ROLE_USERS.forEach((role, username) -> sessions.put(role, login(username, role)));
        customerId = createCustomer();
        templateId = createTemplate();
        quotationIds.put("SALES_MANAGER", createOutputQuotation("SALES_MANAGER"));
        quotationIds.put("SYSTEM_ADMIN", createOutputQuotation("SYSTEM_ADMIN"));
        mailbox.clear();
    }

    @AfterAll
    void tearDownSuite() {
        if (failedOrAborted.get() || successfulTests.get() != EXPECTED_TESTS) {
            System.err.println("[T261027_RDP_FLOW_] 测试未 4/4 成功，保留一次性库中的本前缀现场："
                    + "successful=" + successfulTests.get() + " failedOrAborted=" + failedOrAborted.get());
            return;
        }
        List<String> failures = new ArrayList<>();
        String admin = sessions.get("SYSTEM_ADMIN");
        for (UUID quotationId : quotationIds.values()) {
            inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
                Quotation quotation = em.find(Quotation.class, quotationId);
                if (quotation != null) {
                    quotation.status = "DRAFT";
                    em.merge(quotation);
                }
            }));
            Response deleted = request(admin).contentType(ContentType.JSON).body("{}")
                    .delete("/api/cpq/quotations/{id}", quotationId);
            if (deleted.statusCode() != 200) {
                failures.add("quotation " + quotationId + " status=" + deleted.statusCode()
                        + " body=" + deleted.asString());
            }
        }

        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            DELETE FROM operation_log
                            WHERE operator_id IN (
                                SELECT id FROM "user" WHERE username LIKE :userPrefix
                            )
                            """)
                    .setParameter("userPrefix", OUTPUT_USER_PREFIX + "%")
                    .executeUpdate();
            if (customerId != null) {
                em.createNativeQuery("DELETE FROM customer_contact WHERE customer_id=:id")
                        .setParameter("id", customerId).executeUpdate();
                em.createNativeQuery("DELETE FROM customer WHERE id=:id AND name LIKE :prefix")
                        .setParameter("id", customerId)
                        .setParameter("prefix", DATA_PREFIX + "%")
                        .executeUpdate();
            }
            if (templateId != null) {
                em.createNativeQuery("DELETE FROM template WHERE id=:id AND name LIKE :prefix")
                        .setParameter("id", templateId)
                        .setParameter("prefix", DATA_PREFIX + "%")
                        .executeUpdate();
            }
            em.createNativeQuery("DELETE FROM \"user\" WHERE username LIKE :prefix")
                    .setParameter("prefix", OUTPUT_USER_PREFIX + "%")
                    .executeUpdate();
        }));
        mailbox.clear();

        assertTrue(failures.isEmpty(), "私有报价清理失败：" + failures);
        assertEquals(0L, count("SELECT count(*) FROM quotation WHERE name LIKE '" + DATA_PREFIX + "OUTPUT_%'"));
        assertEquals(0L, count("SELECT count(*) FROM \"user\" WHERE username LIKE '" + OUTPUT_USER_PREFIX + "%'"));
    }

    @Test
    @Order(1)
    @DisplayName("T-07a / AC-5：销售经理六输出与邮件附件最多 4 位，保存值不变")
    void salesManagerSixOutputsUseFourDecimals() throws Exception {
        assertOutputs("SALES_MANAGER", SALES_DISPLAY, OTHER_DISPLAY);
    }

    @Test
    @Order(2)
    @DisplayName("T-07b / AC-5：系统管理员六输出与邮件附件最多 9 位，保存值不变")
    void systemAdminSixOutputsUseNineDecimals() throws Exception {
        assertOutputs("SYSTEM_ADMIN", OTHER_DISPLAY, SALES_DISPLAY);
    }

    @Test
    @Order(3)
    @DisplayName("T-10a / AC-10：未登录查询与六输出端点逐一返回 401")
    void anonymousRequestsRemainUnauthorized() {
        UUID quotationId = quotationIds.get("SYSTEM_ADMIN");
        List<Integer> statuses = List.of(
                request().get("/api/cpq/quotations/{id}", quotationId).statusCode(),
                request().contentType(ContentType.JSON).body("{}")
                        .post("/api/cpq/quotations/{id}/export/html", quotationId).statusCode(),
                request().contentType(ContentType.JSON).body("{}")
                        .post("/api/cpq/quotations/{id}/export/pdf", quotationId).statusCode(),
                request().contentType(ContentType.JSON).body("{}")
                        .post("/api/cpq/quotations/{id}/export/excel", quotationId).statusCode(),
                request().get("/api/cpq/quotations/{id}/export-excel-view", quotationId).statusCode(),
                request().contentType(ContentType.JSON).body(comparisonBody(Map.of()))
                        .post("/api/cpq/quotations/{id}/comparison/export", quotationId).statusCode(),
                request().contentType(ContentType.JSON).body(Map.of(
                                "to", "anonymous@example.invalid", "subject", DATA_PREFIX,
                                "body", "precision", "attachExcel", false))
                        .post("/api/cpq/quotations/{id}/send", quotationId).statusCode());
        assertEquals(List.of(401, 401, 401, 401, 401, 401, 401), statuses,
                "GET + 六输出端点必须逐一保留 401");
    }

    @Test
    @Order(4)
    @DisplayName("T-10b / AC-10：query/body/header 伪造管理员与 9 位不覆盖销售经理会话")
    void clientCannotForgeRoleOrDisplayLimit() throws Exception {
        UUID quotationId = quotationIds.get("SALES_MANAGER");
        String session = sessions.get("SALES_MANAGER");

        Response html = request(session)
                .header("X-Role", "SYSTEM_ADMIN")
                .header("X-Display-Decimals", "9")
                .queryParam("role", "SYSTEM_ADMIN")
                .queryParam("displayDecimals", 9)
                .contentType(ContentType.JSON)
                .body(Map.of("role", "SYSTEM_ADMIN", "displayDecimals", 9))
                .post("/api/cpq/quotations/{id}/export/html", quotationId);
        assertEquals(200, html.statusCode(), html.asString());
        assertTextPrecision(html.asString(), SALES_DISPLAY, OTHER_DISPLAY, "forged HTML");

        Response comparison = request(session)
                .header("X-Role", "SYSTEM_ADMIN")
                .header("X-Display-Decimals", "9")
                .queryParam("role", "SYSTEM_ADMIN")
                .queryParam("displayDecimals", 9)
                .contentType(ContentType.JSON)
                .body(comparisonBody(Map.of("role", "SYSTEM_ADMIN", "displayDecimals", 9)))
                .post("/api/cpq/quotations/{id}/comparison/export", quotationId);
        assertEquals(200, comparison.statusCode(), comparison.asString());
        assertWorkbookContains(comparison.asByteArray(), SALES_DISPLAY, OTHER_DISPLAY, "forged comparison");
    }

    private void assertOutputs(String role, String expected, String forbidden) throws Exception {
        UUID quotationId = quotationIds.get(role);
        String session = sessions.get(role);
        String beforeDb = persistedValues(quotationId);

        Response detail = request(session).get("/api/cpq/quotations/{id}", quotationId);
        assertEquals(200, detail.statusCode(), detail.asString());
        assertTrue(detail.asString().contains(RAW), role + " GET 必须保留 canonical decimal string " + RAW);

        for (String endpoint : List.of("export/html", "export/pdf")) {
            Response response = request(session).contentType(ContentType.JSON)
                    .body("{\"showDiscount\":true,\"showProcesses\":true,\"showTabDetails\":true}")
                    .post("/api/cpq/quotations/{id}/" + endpoint, quotationId);
            assertEquals(200, response.statusCode(), endpoint + ": " + response.asString());
            assertTrue(response.contentType().startsWith("text/html"), endpoint + ": " + response.contentType());
            assertTextPrecision(response.asString(), expected, forbidden, role + " " + endpoint);
        }

        Response quoteExcel = request(session).contentType(ContentType.JSON)
                .body("{\"showDiscount\":true,\"includeRawData\":true}")
                .post("/api/cpq/quotations/{id}/export/excel", quotationId);
        assertEquals(200, quoteExcel.statusCode(), quoteExcel.asString());
        assertWorkbookContains(quoteExcel.asByteArray(), expected, forbidden, role + " quotation Excel");

        Response excelView = request(session).get(
                "/api/cpq/quotations/{id}/export-excel-view", quotationId);
        assertEquals(200, excelView.statusCode(), excelView.asString());
        assertWorkbookContains(excelView.asByteArray(), expected, forbidden, role + " Excel view");

        Response comparison = request(session).contentType(ContentType.JSON)
                .body(comparisonBody(Map.of()))
                .post("/api/cpq/quotations/{id}/comparison/export", quotationId);
        assertEquals(200, comparison.statusCode(), comparison.asString());
        assertWorkbookContains(comparison.asByteArray(), expected, forbidden, role + " comparison Excel");

        String recipient = role.toLowerCase() + ".t261027@example.invalid";
        Response send = request(session).contentType(ContentType.JSON).body(Map.of(
                        "to", recipient,
                        "subject", DATA_PREFIX + role,
                        "body", "display=" + expected,
                        "attachExcel", true))
                .post("/api/cpq/quotations/{id}/send", quotationId);
        assertEquals(200, send.statusCode(), send.asString());

        List<MailMessage> messages = mailbox.getMailMessagesSentTo(recipient);
        assertEquals(1, messages.size(), role + " 必须精确发送一封测试邮件");
        MailMessage message = messages.get(0);
        MailAttachment htmlAttachment = attachment(message, "text/html");
        assertTextPrecision(htmlAttachment.getData().toString(StandardCharsets.UTF_8),
                expected, forbidden, role + " mail HTML attachment");
        MailAttachment excelAttachment = attachment(message, XLSX_MIME);
        assertWorkbookContains(excelAttachment.getData().getBytes(),
                expected, forbidden, role + " mail Excel attachment");

        assertEquals(beforeDb, persistedValues(quotationId),
                role + " 六输出与发送不得改写未舍入金额或产品行快照");
    }

    private UUID createCustomer() {
        Response response = request(sessions.get("SYSTEM_ADMIN"))
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", DATA_PREFIX + "OUTPUT_CUSTOMER",
                        "level", "STANDARD",
                        "contacts", List.of(Map.of(
                                "name", "RDP", "phone", "13800261027", "isPrimary", true))))
                .post("/api/cpq/customers");
        assertEquals(200, response.statusCode(), response.asString());
        return UUID.fromString(response.jsonPath().getString("data.id"));
    }

    private UUID createTemplate() {
        return inRequestContext(() -> QuarkusTransaction.requiringNew().call(() -> {
            Template template = new Template();
            template.templateSeriesId = UUID.randomUUID();
            template.name = DATA_PREFIX + "OUTPUT_TEMPLATE";
            template.version = "v1.0";
            template.category = "STANDARD_PARTS";
            template.status = "PUBLISHED";
            template.excelViewConfig = """
                    [
                      {"col_key":"role_precision","label":"Role Precision",
                       "source_type":"CARD_FORMULA","formula":"1.2345678915"}
                    ]
                    """;
            em.persist(template);
            em.flush();
            return template.id;
        }));
    }

    private UUID createOutputQuotation(String role) {
        Response response = request(sessions.get("SYSTEM_ADMIN"))
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "customerId", customerId.toString(),
                        "name", DATA_PREFIX + "OUTPUT_" + role,
                        "quoteType", "STANDARD"))
                .post("/api/cpq/quotations");
        assertEquals(200, response.statusCode(), response.asString());
        UUID quotationId = UUID.fromString(response.jsonPath().getString("data.id"));

        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> {
            Quotation quotation = em.find(Quotation.class, quotationId);
            assertNotNull(quotation, "创建报价后必须可按 id 回读");
            quotation.status = "APPROVED";
            quotation.originalAmount = new BigDecimal(DB_RAW);
            quotation.totalAmount = new BigDecimal(DB_RAW);

            QuotationLineItem line = new QuotationLineItem();
            line.quotationId = quotationId;
            line.templateId = templateId;
            line.productNameSnapshot = DATA_PREFIX + "PRODUCT";
            line.productPartNoSnapshot = DATA_PREFIX + "PART";
            line.subtotal = new BigDecimal(DB_RAW);
            line.lineUnitPrice = new BigDecimal(DB_RAW);
            line.lineDiscountAmount = BigDecimal.ZERO.setScale(12);
            line.lineFinalPrice = new BigDecimal(DB_RAW);
            line.lineTotalAmount = new BigDecimal(DB_RAW);
            line.quoteExcelValues = """
                    {"rows":[{"role_precision":"1.2345678915"}]}
                    """;
            line.quoteCardValues = """
                    {"tabs":[{"componentId":"T261027_RDP_FLOW_COMPONENT",
                    "tabName":"精度验证","baseRows":[],"editRows":[],
                    "formulaResults":[{"rowKey":"01","values":{"精度结果":"1.2345678915"}}]}]}
                    """;
            em.persist(line);
            em.flush();
        }));
        return quotationId;
    }

    private void seedRoleUsers() {
        String hash = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(10));
        inRequestContext(() -> QuarkusTransaction.requiringNew().run(() -> ROLE_USERS.forEach((role, username) ->
                em.createNativeQuery("""
                        INSERT INTO "user" (username, full_name, email, password_hash, role, status,
                                            is_first_login, failed_login_attempts)
                        VALUES (:username, :fullName, :email, :hash, :role, 'ACTIVE', false, 0)
                        """)
                        .setParameter("username", username)
                        .setParameter("fullName", "角色显示精度 " + role)
                        .setParameter("email", username.toLowerCase() + "@example.invalid")
                        .setParameter("hash", hash)
                        .setParameter("role", role)
                        .executeUpdate())));
    }

    private String login(String username, String expectedRole) {
        Response response = request().contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", PASSWORD))
                .post("/api/cpq/auth/login");
        assertEquals(200, response.statusCode(), username + ": " + response.asString());
        assertEquals(expectedRole, response.jsonPath().getString("data.role"));
        String session = response.cookie("CPQ_SESSION");
        assertNotNull(session, username + " 登录 200 但缺 CPQ_SESSION");
        return session;
    }

    private Map<String, Object> comparisonBody(Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", List.of(Map.of("tag", "TOTAL", "label", "总价")));
        body.put("rows", List.of(Map.of(
                "partNo", DATA_PREFIX + "PART",
                "presence", "BOTH",
                "cells", Map.of("TOTAL", Map.of(
                        "quote", RAW, "costing", RAW, "highlighted", false)))));
        body.putAll(extras);
        return body;
    }

    private String persistedValues(UUID quotationId) {
        return inRequestContext(() -> QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> rows = em.createNativeQuery("""
                    SELECT q.original_amount::text || '|' || q.total_amount::text || '|' ||
                           COALESCE(string_agg(
                             li.id::text || ':' || COALESCE(li.subtotal::text,'') || ':' ||
                             COALESCE(li.line_unit_price::text,'') || ':' ||
                             COALESCE(li.line_discount_amount::text,'') || ':' ||
                             COALESCE(li.line_final_price::text,'') || ':' ||
                             COALESCE(li.line_total_amount::text,'') || ':' ||
                             COALESCE(li.quote_card_values::text,'') || ':' ||
                             COALESCE(li.quote_excel_values::text,''),
                             '||' ORDER BY li.id::text), '')
                    FROM quotation q
                    LEFT JOIN quotation_line_item li ON li.quotation_id=q.id
                    WHERE q.id=:id
                    GROUP BY q.id, q.original_amount, q.total_amount
                    """).setParameter("id", quotationId).getResultList();
            assertEquals(1, rows.size(), "私有报价必须精确回读一行");
            String value = String.valueOf(rows.get(0));
            assertTrue(value.contains(RAW), "持久化快照必须非空包含 " + RAW + ": " + value);
            return value;
        }));
    }

    private static void assertTextPrecision(
            String text,
            String expected,
            String forbidden,
            String label) {
        assertNotNull(text, label);
        List<String> tokens = decimalTokens(text);
        assertTrue(tokens.contains(expected), label + " 缺显示值 " + expected + ": " + text);
        assertFalse(tokens.contains(forbidden), label + " 出现错误角色显示值 " + forbidden + ": " + text);
        assertFalse(tokens.contains(RAW), label + " 泄漏未舍入原值 " + RAW + ": " + text);
    }

    private static void assertWorkbookContains(
            byte[] bytes,
            String expected,
            String forbidden,
            String label) throws Exception {
        assertNotNull(bytes, label);
        List<String> strings = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            strings.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        List<String> tokens = strings.stream().flatMap(value -> decimalTokens(value).stream()).toList();
        assertTrue(tokens.contains(expected), label + " 缺显示值 " + expected + ": " + strings);
        assertFalse(tokens.contains(forbidden), label + " 出现错误角色显示值 " + forbidden + ": " + strings);
        assertFalse(tokens.contains(RAW), label + " 泄漏未舍入原值 " + RAW + ": " + strings);
    }

    private static List<String> decimalTokens(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = DECIMAL_TOKEN.matcher(value);
        while (matcher.find()) tokens.add(matcher.group().replace(",", ""));
        return tokens;
    }

    private static MailAttachment attachment(MailMessage message, String contentType) {
        return message.getAttachment().stream()
                .filter(item -> contentType.equals(item.getContentType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少附件 " + contentType));
    }

    private RequestSpecification request() {
        assertNotNull(testBaseUri, "@TestHTTPResource 尚未注入");
        return RestAssured.given().baseUri(testBaseUri.toString());
    }

    private RequestSpecification request(String session) {
        assertNotNull(session, "测试会话未建立");
        return request().cookie("CPQ_SESSION", session);
    }

    private long count(String sql) {
        return inRequestContext(() -> QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery(sql).getSingleResult()).longValue()));
    }

    private static void inRequestContext(Runnable action) {
        inRequestContext(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T inRequestContext(java.util.function.Supplier<T> action) {
        ManagedContext context = Arc.container().requestContext();
        boolean activate = !context.isActive();
        if (activate) context.activate();
        try {
            return action.get();
        } finally {
            if (activate) context.terminate();
        }
    }
}
