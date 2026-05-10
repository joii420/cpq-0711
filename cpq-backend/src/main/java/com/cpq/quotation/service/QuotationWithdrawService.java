package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.quotation.dto.QuotationWithdrawRequestDTO;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationApproval;
import com.cpq.quotation.entity.QuotationWithdrawRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class QuotationWithdrawService {

    private static final Logger LOG = Logger.getLogger(QuotationWithdrawService.class);

    public List<QuotationWithdrawRequestDTO> listForQuotation(UUID quotationId) {
        List<QuotationWithdrawRequest> rows = QuotationWithdrawRequest.list(
                "quotationId = ?1 ORDER BY createdAt DESC", quotationId);
        return rows.stream().map(QuotationWithdrawRequestDTO::from).collect(Collectors.toList());
    }

    public QuotationWithdrawRequestDTO findPending(UUID quotationId) {
        QuotationWithdrawRequest r = QuotationWithdrawRequest.find(
                "quotationId = ?1 AND status = 'PENDING'", quotationId).firstResult();
        return r == null ? null : QuotationWithdrawRequestDTO.from(r);
    }

    @Transactional
    public QuotationWithdrawRequestDTO requestWithdraw(UUID quotationId, String reason, UUID userId) {
        if (reason == null || reason.isBlank()) throw new BusinessException(400, "撤回原因必填");
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "Quotation not found: " + quotationId);
        if (!"APPROVED".equals(q.status))
            throw new BusinessException(400, "只有已批准状态可申请撤回");

        long pending = QuotationWithdrawRequest.count("quotationId = ?1 AND status = 'PENDING'", quotationId);
        if (pending > 0) throw new BusinessException(400, "已有待处理的撤回请求");

        QuotationWithdrawRequest r = new QuotationWithdrawRequest();
        r.quotationId = quotationId;
        r.requestedBy = userId;
        r.reason = reason.trim();
        r.status = "PENDING";
        r.persist();
        LOG.infof("Withdraw request created: quotation=%s user=%s", quotationId, userId);
        return QuotationWithdrawRequestDTO.from(r);
    }

    @Transactional
    public QuotationWithdrawRequestDTO approveWithdraw(UUID quotationId, UUID userId, String note) {
        QuotationWithdrawRequest r = QuotationWithdrawRequest.find(
                "quotationId = ?1 AND status = 'PENDING'", quotationId).firstResult();
        if (r == null) throw new BusinessException(404, "无待处理的撤回请求");

        Quotation q = Quotation.findById(quotationId);
        if (q == null || !"APPROVED".equals(q.status))
            throw new BusinessException(400, "报价单状态已变更，无法撤回");

        // 权限校验：原审批人或 SYSTEM_ADMIN
        com.cpq.system.entity.User u = com.cpq.system.entity.User.findById(userId);
        boolean isAdmin = u != null && "SYSTEM_ADMIN".equals(u.role);
        if (!isAdmin && (q.assignedApproverId == null || !q.assignedApproverId.equals(userId))) {
            throw new BusinessException(403, "无权处理此撤回请求（仅原审批人或管理员）");
        }

        r.status = "APPROVED";
        r.decidedBy = userId;
        r.decidedAt = OffsetDateTime.now();
        r.decisionNote = note;

        // 状态变更 APPROVED -> DRAFT
        q.status = "DRAFT";

        // 写入审批历史
        QuotationApproval a = new QuotationApproval();
        a.quotationId = quotationId;
        a.action = "WITHDRAWN";
        a.approverId = userId;
        a.comment = "审批人同意撤回 (撤回原因: " + r.reason + ")" + (note != null ? "; 备注: " + note : "");
        a.persist();

        LOG.infof("Withdraw approved: quotation=%s by user=%s", quotationId, userId);
        return QuotationWithdrawRequestDTO.from(r);
    }

    @Transactional
    public QuotationWithdrawRequestDTO rejectWithdraw(UUID quotationId, UUID userId, String note) {
        QuotationWithdrawRequest r = QuotationWithdrawRequest.find(
                "quotationId = ?1 AND status = 'PENDING'", quotationId).firstResult();
        if (r == null) throw new BusinessException(404, "无待处理的撤回请求");

        Quotation q = Quotation.findById(quotationId);
        com.cpq.system.entity.User u = com.cpq.system.entity.User.findById(userId);
        boolean isAdmin = u != null && "SYSTEM_ADMIN".equals(u.role);
        if (!isAdmin && (q == null || q.assignedApproverId == null || !q.assignedApproverId.equals(userId))) {
            throw new BusinessException(403, "无权处理此撤回请求");
        }

        r.status = "REJECTED";
        r.decidedBy = userId;
        r.decidedAt = OffsetDateTime.now();
        r.decisionNote = note;

        LOG.infof("Withdraw rejected: quotation=%s by user=%s", quotationId, userId);
        return QuotationWithdrawRequestDTO.from(r);
    }
}
