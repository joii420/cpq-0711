package com.cpq.quotation.resource;

import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.ext.mail.MailAttachment;
import io.vertx.ext.mail.MailMessage;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(QuotationOutputPrecisionHttpContractTest.RbacOffProfile.class)
class QuotationOutputPrecisionHttpContractTest {

    public static class RbacOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cpq.security.rbac.enabled", "false");
        }
    }

    private static final DecimalBoundary POSITIVE =
            new DecimalBoundary("1.234567891500", "1.234567892");
    private static final DecimalBoundary NEGATIVE =
            new DecimalBoundary("-1.234567891500", "-1.234567892");
    private static final DecimalBoundary LARGE =
            new DecimalBoundary("98765431.123456789012", "98765431.123456789");
    private static final DecimalBoundary SMALL =
            new DecimalBoundary("0.000000000500", "0.000000001");
    private static final DecimalBoundary ZERO =
            new DecimalBoundary("0.000000000000", "0");
    private static final List<DecimalBoundary> DECIMAL_BOUNDARIES =
            List.of(POSITIVE, NEGATIVE, LARGE, SMALL, ZERO);
    private static final Pattern MORE_THAN_NINE_DECIMALS =
            Pattern.compile("(?<![\\d.])-?\\d+\\.\\d{10,}(?!\\d)");
    private static final Pattern SCIENTIFIC_NOTATION = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_.])[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)[eE][+-]?\\d+(?![A-Za-z0-9_.])");
    private static final String RECIPIENT = "task0810-http-output@example.test";

    @Inject EntityManager em;
    @Inject MockMailbox mailbox;

    private static UUID quotationId;
    private static UUID lineItemId;

    @BeforeEach
    @Transactional
    void setUp() {
        mailbox.clear();
        if (quotationId != null) {
            return;
        }

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customerId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"Task0810 Output %s","level":"STANDARD",
                         "contacts":[{"name":"Precision","phone":"13800008100","isPrimary":true}]}
                        """.formatted(suffix))
                .post("/api/cpq/customers")
                .then().statusCode(200).extract().path("data.id");

        quotationId = UUID.fromString(RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"customerId":"%s","name":"Task0810 Output %s","quoteType":"STANDARD"}
                        """.formatted(customerId, suffix))
                .post("/api/cpq/quotations")
                .then().statusCode(200).extract().path("data.id"));

        Quotation quotation = em.find(Quotation.class, quotationId);
        quotation.status = "APPROVED";
        quotation.originalAmount = POSITIVE.decimal();
        quotation.totalAmount = LARGE.decimal();

        Template template = new Template();
        template.templateSeriesId = UUID.randomUUID();
        template.name = "Task0810 Excel View " + suffix;
        template.version = "v1.0";
        template.category = "STANDARD_PARTS";
        template.status = "PUBLISHED";
        template.excelViewConfig = """
                [
                  {"col_key":"positive","label":"Positive","source_type":"CARD_FORMULA","formula":"1.234567891500"},
                  {"col_key":"negative","label":"Negative","source_type":"CARD_FORMULA","formula":"-1.234567891500"},
                  {"col_key":"large","label":"Large","source_type":"CARD_FORMULA","formula":"98765431.123456789012"},
                  {"col_key":"small","label":"Small","source_type":"CARD_FORMULA","formula":"0.000000000500"},
                  {"col_key":"zero","label":"Zero","source_type":"CARD_FORMULA","formula":"0.000000000000"}
                ]
                """;
        em.persist(template);
        em.flush();

        QuotationLineItem line = new QuotationLineItem();
        line.quotationId = quotationId;
        line.templateId = template.id;
        line.productNameSnapshot = "Precision Product";
        line.productPartNoSnapshot = "P-0810";
        line.subtotal = NEGATIVE.decimal();
        line.lineUnitPrice = SMALL.decimal();
        line.lineDiscountAmount = ZERO.decimal();
        line.lineFinalPrice = NEGATIVE.decimal();
        line.lineTotalAmount = POSITIVE.decimal();
        line.quoteExcelValues = """
                {"rows":[{
                  "positive":"%s",
                  "negative":"%s",
                  "large":"%s",
                  "small":"%s",
                  "zero":"%s"
                }]}
                """.formatted(POSITIVE.raw(), NEGATIVE.raw(), LARGE.raw(), SMALL.raw(), ZERO.raw());
        em.persist(line);
        em.flush();
        lineItemId = line.id;
    }

    @Test
    void htmlAndPdfEndpointsExposeOnlyNineDisplayDecimals() {
        for (String path : List.of("export/html?showDiscount=true", "export/pdf")) {
            Response response = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body("{\"showDiscount\":true}")
                    .post("/api/cpq/quotations/" + quotationId + "/" + path)
                    .then().statusCode(200).extract().response();

            String html = response.asString();
            assertTrue(response.contentType().startsWith("text/html"), path + ": " + response.contentType());
            assertHtmlPrecisionContract(html, path);
        }
    }

    @Test
    void quotationAndExcelViewExportsUseStringCellsForComputedDecimals() throws Exception {
        assertQuotationWorkbook(RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"showDiscount\":true,\"includeRawData\":true}")
                .post("/api/cpq/quotations/" + quotationId + "/export/excel")
                .then().statusCode(200).extract().asByteArray());

        assertExcelViewWorkbook(RestAssured.given()
                .get("/api/cpq/quotations/" + quotationId + "/export-excel-view")
                .then().statusCode(200).extract().asByteArray());
    }

    @Test
    void excelWriteEndpointsRejectNumericTokensWithPathAndRawValue() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"columns":[{"col_key":"amount","source_type":"CARD_FORMULA","value":98765431.123456789012}]}
                        """)
                .post("/api/cpq/quotations/" + quotationId + "/excel-view/dry-run")
                .then().statusCode(400)
                .body(allOf(containsString("columns[0].value"), containsString("98765431.123456789012")));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"lineItemId":"%s","colKey":"amount","value":98765431.123456789012}
                        """.formatted(lineItemId))
                .put("/api/cpq/quotations/" + quotationId + "/excel-view")
                .then().statusCode(400)
                .body(allOf(containsString("value"), containsString("98765431.123456789012")));
    }

    @Test
    void sendEndpointPreservesPrecisionInMockMailboxAttachments() throws Exception {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"to":"%s","subject":"Task0810","body":"total=%s","attachExcel":true}
                        """.formatted(RECIPIENT, POSITIVE.display()))
                .post("/api/cpq/quotations/" + quotationId + "/send")
                .then().statusCode(200);

        List<MailMessage> messages = mailbox.getMailMessagesSentTo(RECIPIENT);
        assertEquals(1, messages.size());
        MailMessage message = messages.get(0);
        assertEquals("total=" + POSITIVE.display(), message.getHtml());

        MailAttachment html = attachment(message, "text/html");
        String htmlText = html.getData().toString(StandardCharsets.UTF_8);
        assertHtmlPrecisionContract(htmlText, "mail HTML attachment");

        MailAttachment xlsx = attachment(message,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertQuotationWorkbook(xlsx.getData().getBytes());
    }

    private static void assertHtmlPrecisionContract(String html, String source) {
        assertNotNull(html, source);
        for (DecimalBoundary boundary : DECIMAL_BOUNDARIES) {
            assertTrue(html.contains(boundary.display()),
                    () -> source + " missing display value " + boundary.display() + ": " + html);
            assertFalse(html.contains(boundary.raw()),
                    () -> source + " leaked 12-decimal value " + boundary.raw() + ": " + html);
        }
        assertFalse(MORE_THAN_NINE_DECIMALS.matcher(html).find(),
                () -> source + " contains a decimal with more than 9 fractional digits: " + html);
        assertFalse(SCIENTIFIC_NOTATION.matcher(html).find(),
                () -> source + " contains scientific notation: " + html);
    }

    private static void assertQuotationWorkbook(byte[] bytes) throws Exception {
        assertNotNull(bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("报价单");
            assertNotNull(sheet, "quotation workbook must contain 报价单 sheet");
            assertStringCellBesideLabel(sheet, "原价合计:", POSITIVE.display());
            assertStringCellBesideLabel(sheet, "报价总金额:", LARGE.display());
            assertStringCellBelowHeader(sheet, "单价(元)", SMALL.display());
            assertStringCellBelowHeader(sheet, "折扣金额(元)", ZERO.display());
            assertStringCellBelowHeader(sheet, "折后单价(元)", NEGATIVE.display());
            assertStringCellBelowHeader(sheet, "行合计(元)", POSITIVE.display());
        }
    }

    private static void assertExcelViewWorkbook(byte[] bytes) throws Exception {
        assertNotNull(bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertStringCellBelowHeader(sheet, "Positive", POSITIVE.display());
            assertStringCellBelowHeader(sheet, "Negative", NEGATIVE.display());
            assertStringCellBelowHeader(sheet, "Large", LARGE.display());
            assertStringCellBelowHeader(sheet, "Small", SMALL.display());
            assertStringCellBelowHeader(sheet, "Zero", ZERO.display());
        }
    }

    private static void assertStringCellBesideLabel(Sheet sheet, String label, String expected) {
        Cell labelCell = findStringCell(sheet, label);
        Cell valueCell = labelCell.getRow().getCell(labelCell.getColumnIndex() + 1);
        assertExactStringCell(valueCell, expected, "cell beside " + label);
    }

    private static void assertStringCellBelowHeader(Sheet sheet, String label, String expected) {
        Cell headerCell = findStringCell(sheet, label);
        Cell valueCell = null;
        for (int rowIndex = headerCell.getRowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Cell candidate = row != null ? row.getCell(headerCell.getColumnIndex()) : null;
            if (candidate != null && candidate.getCellType() != CellType.BLANK) {
                valueCell = candidate;
                break;
            }
        }
        assertExactStringCell(valueCell, expected, "first value below " + label);
    }

    private static Cell findStringCell(Sheet sheet, String expected) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && expected.equals(cell.getStringCellValue())) {
                    return cell;
                }
            }
        }
        throw new AssertionError("missing cell label: " + expected);
    }

    private static void assertExactStringCell(Cell cell, String expected, String label) {
        assertNotNull(cell, label + " must exist");
        assertEquals(CellType.STRING, cell.getCellType(), label + " must be STRING");
        assertEquals(expected, cell.getStringCellValue(), label);
    }

    private static MailAttachment attachment(MailMessage message, String contentType) {
        return message.getAttachment().stream()
                .filter(item -> contentType.equals(item.getContentType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing attachment: " + contentType));
    }

    private record DecimalBoundary(String raw, String display) {
        java.math.BigDecimal decimal() {
            return new java.math.BigDecimal(raw());
        }
    }
}
