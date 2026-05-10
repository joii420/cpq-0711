package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.Quotation;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class QuotationEmailService {

    private static final Logger LOG = Logger.getLogger(QuotationEmailService.class);

    @Inject
    QuotationService quotationService;

    @Inject
    QuotationExportService exportService;

    @Inject
    Mailer mailer;

    @Transactional
    public QuotationDTO send(UUID quotationId, String to, String cc, String subject, String body, boolean attachExcel) {
        Quotation q = Quotation.findById(quotationId);
        if (q == null) {
            throw new BusinessException(404, "Quotation not found: " + quotationId);
        }
        if (!"APPROVED".equals(q.status)) {
            throw new BusinessException(400, "Only APPROVED quotations can be sent. Current status: " + q.status);
        }
        if (to == null || to.isBlank()) {
            throw new BusinessException(400, "Recipient email (to) is required");
        }

        // Build email
        Mail mail = Mail.withHtml(to, subject != null ? subject : "报价单 " + q.quotationNumber, body != null ? body : "请查收附件报价单。");

        if (cc != null && !cc.isBlank()) {
            mail.addCc(cc);
        }

        // Attach HTML export (acts as PDF substitute)
        byte[] htmlBytes = exportService.exportHtml(quotationId, true, false, false);
        mail.addAttachment(q.quotationNumber + "-报价单.html", htmlBytes, "text/html");

        if (attachExcel) {
            byte[] excelBytes = exportService.exportExcel(quotationId, true, false);
            mail.addAttachment(q.quotationNumber + "-报价单.xlsx", excelBytes,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        // Send (mock=true in dev, so this won't actually send but won't fail)
        mailer.send(mail);
        LOG.infof("Sent quotation email id=%s number=%s to=%s cc=%s", quotationId, q.quotationNumber, to, cc);

        // Update status
        q.status = "SENT";

        QuotationDTO dto = QuotationDTO.from(q);
        dto.lineItems = null; // lightweight for this response
        return dto;
    }
}
