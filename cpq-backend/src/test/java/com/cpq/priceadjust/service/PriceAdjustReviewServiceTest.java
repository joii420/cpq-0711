package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ApproveRejectRequest;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.exception.ReviewNotReadyException;
import com.cpq.common.exception.BusinessException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B5 · 审核服务校验逻辑单测（reject reason 必填 / approve 三项前置校验）。
 *
 * <p>纯校验路径用自建最小 fixture（不含真实报价单/line item，因为这些分支在校验失败时根本
 * 不会走到 job 创建那一步）。真实的「同步推进指针 + 异步建 job + 执行 + SUCCESS 汇总」全链路
 * 已在真实单 HTTP 联调中验证（见 RECORD.md B5 条目：CUST-0001 隔离料号测试，逐字节恢复）。
 */
@QuarkusTest
class PriceAdjustReviewServiceTest {

    @Inject PriceAdjustReviewService reviewService;

    private UUID versionId;
    private UUID reviewId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (reviewId != null) MaterialPriceReview.deleteById(reviewId);
        if (versionId != null) ElementPriceVersion.deleteById(versionId);
    }

    @Transactional
    void seedReview(String reviewStatus, String budgetStatus, String versionStatus) {
        ElementPriceVersion v = new ElementPriceVersion();
        v.customerNo = "TEST-B5-" + UUID.randomUUID().toString().substring(0, 8);
        v.versionNo = "V00000099";
        v.baseDate = LocalDate.now();
        v.status = versionStatus;
        v.triggerType = "MANUAL";
        v.persist();
        versionId = v.id;

        MaterialPriceReview r = new MaterialPriceReview();
        r.versionId = v.id;
        r.customerNo = v.customerNo;
        r.materialNo = "TEST-MAT";
        r.status = reviewStatus;
        r.budgetStatus = budgetStatus;
        r.persist();
        reviewId = r.id;
    }

    @Test
    void reject_withoutReason_throws400() {
        seedReview(MaterialPriceReview.STATUS_PENDING, MaterialPriceReview.BUDGET_READY, ElementPriceVersion.STATUS_PENDING);
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of(reviewId);
        req.reason = null;
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.reject(req, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    void reject_withBlankReason_throws400() {
        seedReview(MaterialPriceReview.STATUS_PENDING, MaterialPriceReview.BUDGET_READY, ElementPriceVersion.STATUS_PENDING);
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of(reviewId);
        req.reason = "   ";
        assertThrows(BusinessException.class, () -> reviewService.reject(req, null));
    }

    @Test
    void reject_withReason_setsRejectedAndDoesNotAdvancePointer() {
        seedReview(MaterialPriceReview.STATUS_PENDING, MaterialPriceReview.BUDGET_READY, ElementPriceVersion.STATUS_PENDING);
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of(reviewId);
        req.reason = "行情尚未确认";
        reviewService.reject(req, null);

        MaterialPriceReview r = fetchReview(reviewId);
        assertEquals(MaterialPriceReview.STATUS_REJECTED, r.status);
        assertEquals("行情尚未确认", r.reviewComment);
        assertEquals(0, com.cpq.priceadjust.entity.MaterialPriceVersionRef.count("materialNo", "TEST-MAT"));
    }

    @Transactional
    MaterialPriceReview fetchReview(UUID id) {
        return MaterialPriceReview.findById(id);
    }

    @Test
    void approve_budgetNotReady_throwsReviewNotReadyException_withCorrectErrorCode() {
        seedReview(MaterialPriceReview.STATUS_PENDING, MaterialPriceReview.BUDGET_FAILED, ElementPriceVersion.STATUS_PENDING);
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of(reviewId);
        ReviewNotReadyException ex = assertThrows(ReviewNotReadyException.class, () -> reviewService.approve(req, null));
        assertEquals("REVIEW_BUDGET_NOT_READY", ex.getErrorCode());
        assertEquals(1, ex.getInvalidItems().size());
    }

    @Test
    void approve_statusNotPending_throwsReviewNotReadyException_withStatusChangedCode() {
        seedReview(MaterialPriceReview.STATUS_APPROVED, MaterialPriceReview.BUDGET_READY, ElementPriceVersion.STATUS_PENDING);
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of(reviewId);
        ReviewNotReadyException ex = assertThrows(ReviewNotReadyException.class, () -> reviewService.approve(req, null));
        assertEquals("REVIEW_STATUS_CHANGED", ex.getErrorCode());
    }

    @Test
    void approve_versionSuperseded_throwsReviewNotReadyException() {
        seedReview(MaterialPriceReview.STATUS_PENDING, MaterialPriceReview.BUDGET_READY, ElementPriceVersion.STATUS_SUPERSEDED);
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of(reviewId);
        ReviewNotReadyException ex = assertThrows(ReviewNotReadyException.class, () -> reviewService.approve(req, null));
        assertEquals("REVIEW_STATUS_CHANGED", ex.getErrorCode());
    }

    @Test
    void approve_emptyReviewIds_throws400() {
        ApproveRejectRequest req = new ApproveRejectRequest();
        req.reviewIds = List.of();
        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.approve(req, null));
        assertEquals(400, ex.getCode());
    }
}
