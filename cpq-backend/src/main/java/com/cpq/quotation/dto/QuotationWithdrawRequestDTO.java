package com.cpq.quotation.dto;

import com.cpq.quotation.entity.QuotationWithdrawRequest;

import java.time.OffsetDateTime;
import java.util.UUID;

public class QuotationWithdrawRequestDTO {

    public UUID id;
    public UUID quotationId;
    public UUID requestedBy;
    public String requestedByName;
    public String reason;
    public String status;
    public UUID decidedBy;
    public String decidedByName;
    public OffsetDateTime decidedAt;
    public String decisionNote;
    public OffsetDateTime createdAt;

    public static QuotationWithdrawRequestDTO from(QuotationWithdrawRequest r) {
        QuotationWithdrawRequestDTO dto = new QuotationWithdrawRequestDTO();
        dto.id = r.id;
        dto.quotationId = r.quotationId;
        dto.requestedBy = r.requestedBy;
        dto.reason = r.reason;
        dto.status = r.status;
        dto.decidedBy = r.decidedBy;
        dto.decidedAt = r.decidedAt;
        dto.decisionNote = r.decisionNote;
        dto.createdAt = r.createdAt;
        if (r.requestedBy != null) {
            com.cpq.system.entity.User u = com.cpq.system.entity.User.findById(r.requestedBy);
            if (u != null) dto.requestedByName = u.fullName != null ? u.fullName : u.username;
        }
        if (r.decidedBy != null) {
            com.cpq.system.entity.User u = com.cpq.system.entity.User.findById(r.decidedBy);
            if (u != null) dto.decidedByName = u.fullName != null ? u.fullName : u.username;
        }
        return dto;
    }
}
