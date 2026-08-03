package com.cpq.priceadjust.resource;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.priceadjust.dto.JobDTO;
import com.cpq.priceadjust.dto.JobItemDTO;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJob;
import com.cpq.priceadjust.entity.MaterialPriceUpdateJobItem;
import com.cpq.priceadjust.service.PriceAdjustJobExecutionService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * task-0729 B5 · 更新任务端点（api.md §3，屏 6 + 常驻更新任务页）。
 *
 * <p>🔒 2026-08-03 修正：响应体裸 DTO，不套 {@code ApiResponse} 信封（详见
 * {@code PriceAdjustStrategyResource} 类注释）。
 */
@Path("/api/cpq/price-adjust")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceAdjustJobResource {

    @Inject PriceAdjustJobExecutionService jobExecutionService;
    @Inject ManagedExecutor managedExecutor;

    @GET
    @Path("/jobs")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public PageResult<JobDTO> listJobs(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("customerNo") String customerNo) {
        // 🔒 Parameters 不能塞未在查询串里出现的 key（Panache 会校验全部命中）——两个过滤条件都是
        // 可选的，不能像 review 列表那样借一个恒有效字段起手，故按"是否有任何条件"分叉查询方法，
        // 不用 "1=1"/占位 key 这种反模式（真实联调时 "1=1"+dummy 组合复现为 400，已修正）。
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasCustomerNo = customerNo != null && !customerNo.isBlank();
        Sort sort = Sort.by("triggeredAt").descending();

        long total;
        List<MaterialPriceUpdateJob> rows;
        if (!hasStatus && !hasCustomerNo) {
            total = MaterialPriceUpdateJob.count();
            rows = MaterialPriceUpdateJob.findAll(sort).page(Page.of(Math.max(page - 1, 0), size)).list();
        } else {
            StringBuilder q = new StringBuilder();
            Parameters params;
            if (hasStatus) {
                q.append("status = :status");
                params = Parameters.with("status", status);
                if (hasCustomerNo) { q.append(" and customerNo = :cno"); params = params.and("cno", customerNo); }
            } else {
                q.append("customerNo = :cno");
                params = Parameters.with("cno", customerNo);
            }
            total = MaterialPriceUpdateJob.count(q.toString(), params);
            rows = MaterialPriceUpdateJob.find(q.toString(), sort, params)
                .page(Page.of(Math.max(page - 1, 0), size)).list();
        }

        List<JobDTO> content = new ArrayList<>();
        for (MaterialPriceUpdateJob j : rows) content.add(toDto(j));
        return new PageResult<>(content, page, size, total);
    }

    @GET
    @Path("/jobs/{jobId}")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public JobDTO getJob(@PathParam("jobId") UUID jobId) {
        MaterialPriceUpdateJob j = MaterialPriceUpdateJob.findById(jobId);
        if (j == null) throw new BusinessException(404, "job 不存在: " + jobId);
        return toDto(j);
    }

    @GET
    @Path("/jobs/{jobId}/items")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public PageResult<JobItemDTO> getJobItems(
            @PathParam("jobId") UUID jobId,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("50") int size,
            @QueryParam("status") String status) {
        String q = status != null && !status.isBlank() ? "jobId = ?1 and status = ?2" : "jobId = ?1";
        long total = status != null && !status.isBlank()
            ? MaterialPriceUpdateJobItem.count(q, jobId, status) : MaterialPriceUpdateJobItem.count(q, jobId);
        List<MaterialPriceUpdateJobItem> rows = (status != null && !status.isBlank()
            ? MaterialPriceUpdateJobItem.find(q, jobId, status)
            : MaterialPriceUpdateJobItem.find(q, jobId))
            .page(Page.of(Math.max(page - 1, 0), size)).list();

        List<JobItemDTO> content = new ArrayList<>();
        for (MaterialPriceUpdateJobItem it : rows) content.add(toItemDto(it));
        return new PageResult<>(content, page, size, total);
    }

    /** §3.4 批量重试该批次全部 FAILED + CONFLICT 项（不含 STALE）。 */
    @POST
    @Path("/jobs/{jobId}/retry")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response retryJob(@PathParam("jobId") UUID jobId) {
        MaterialPriceUpdateJob j = MaterialPriceUpdateJob.findById(jobId);
        if (j == null) throw new BusinessException(404, "job 不存在: " + jobId);
        managedExecutor.runAsync(() -> jobExecutionService.retryJob(jobId));
        return Response.status(202).build();
    }

    /** §3.5 单条重试。STALE 项调用返回 409。 */
    @POST
    @Path("/job-items/{itemId}/retry")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response retryJobItem(@PathParam("itemId") UUID itemId) {
        MaterialPriceUpdateJobItem item = MaterialPriceUpdateJobItem.findById(itemId);
        if (item == null) throw new BusinessException(404, "job item 不存在: " + itemId);
        if (MaterialPriceUpdateJobItem.STALE.equals(item.status)) {
            throw new BusinessException(409, "所属版本已被取代，STALE 项不可重试");
        }
        managedExecutor.runAsync(() -> jobExecutionService.retryJobItem(itemId));
        return Response.status(202).build();
    }

    private JobDTO toDto(MaterialPriceUpdateJob j) {
        JobDTO dto = new JobDTO();
        dto.jobId = j.id;
        dto.customerNo = j.customerNo;
        dto.versionNo = j.versionNo;
        dto.triggeredBy = j.triggeredBy != null ? j.triggeredBy.toString() : null;
        dto.triggeredAt = j.triggeredAt;
        dto.status = j.status;
        dto.total = j.totalCount;
        dto.success = j.successCount;
        dto.failed = j.failedCount;
        dto.conflict = j.conflictCount;
        dto.stale = j.staleCount;
        dto.finishedAt = j.finishedAt;
        dto.notified = Boolean.TRUE.equals(j.notified);
        return dto;
    }

    private JobItemDTO toItemDto(MaterialPriceUpdateJobItem it) {
        JobItemDTO dto = new JobItemDTO();
        dto.itemId = it.id;
        dto.quotationId = it.quotationId;
        com.cpq.quotation.entity.Quotation q = com.cpq.quotation.entity.Quotation.findById(it.quotationId);
        dto.quotationNo = q != null ? q.quotationNumber : null;
        dto.materialNo = it.materialNo;
        dto.lineItemId = it.lineItemId;
        dto.status = it.status;
        dto.errorCode = it.errorCode;
        dto.errorMessage = it.errorMessage;
        dto.diffValue = it.diffValue;
        dto.retryCount = it.retryCount;
        dto.updatedAt = it.updatedAt;
        return dto;
    }
}
