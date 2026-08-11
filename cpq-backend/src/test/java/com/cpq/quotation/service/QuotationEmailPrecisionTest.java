package com.cpq.quotation.service;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QuotationEmailPrecisionTest {

    private static final String RECIPIENT = "task0810-precision@example.test";
    private static final String DISPLAY_VALUE = "1.234567892";

    @Inject
    QuotationEmailService emailService;

    @Inject
    MockMailbox mailbox;

    @Inject
    EntityManager em;

    @BeforeEach
    void clearMailbox() {
        mailbox.clear();
    }

    @Test
    @TestTransaction
    void mockMailboxPreservesNineDigitHtmlBodyAndStringExcelAttachment() throws Exception {
        UUID quotationId = firstQuotationId();
        assertNotNull(quotationId, "test database must contain one quotation fixture");

        em.createNativeQuery("""
                UPDATE quotation
                   SET status = 'APPROVED',
                       total_amount = CAST('1.234567891500' AS numeric(26,12)),
                       original_amount = CAST('1.234567891500' AS numeric(26,12))
                 WHERE id = :id
                """)
                .setParameter("id", quotationId)
                .executeUpdate();
        em.flush();
        em.clear();

        emailService.send(quotationId, RECIPIENT, null, "task0810 precision",
                "total=" + DISPLAY_VALUE, true);

        assertEquals(1, mailbox.getTotalMessagesSent());
        List<MailMessage> messages = mailbox.getMailMessagesSentTo(RECIPIENT);
        assertEquals(1, messages.size());
        MailMessage mail = messages.get(0);
        assertEquals("total=" + DISPLAY_VALUE, mail.getHtml());
        assertEquals(2, mail.getAttachment().size());

        MailAttachment html = attachmentByType(mail, "text/html");
        String htmlText = html.getData().toString(StandardCharsets.UTF_8);
        assertTrue(htmlText.contains(DISPLAY_VALUE), htmlText);
        assertFalse(htmlText.contains("1.2345678915"), htmlText);

        MailAttachment xlsx = attachmentByType(mail,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        boolean foundDisplayString = false;
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(xlsx.getData().getBytes()))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING
                                && DISPLAY_VALUE.equals(cell.getStringCellValue())) {
                            assertEquals(CellType.STRING, cell.getCellType());
                            foundDisplayString = true;
                        }
                    }
                }
            }
        }
        assertTrue(foundDisplayString, "Excel attachment must contain the 9-digit value as STRING");
    }

    @SuppressWarnings("unchecked")
    private UUID firstQuotationId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation ORDER BY created_at NULLS LAST, id LIMIT 1").getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object value = rows.get(0);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static MailAttachment attachmentByType(MailMessage mail, String contentType) {
        return mail.getAttachment().stream()
                .filter(attachment -> contentType.equals(attachment.getContentType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing attachment: " + contentType));
    }
}
