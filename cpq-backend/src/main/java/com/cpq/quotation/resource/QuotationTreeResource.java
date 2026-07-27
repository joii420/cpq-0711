package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.quotation.dto.BomTreeAddLeafRequest;
import com.cpq.quotation.dto.BomTreeDeleteRequest;
import com.cpq.quotation.service.CardSnapshotService;
import com.cpq.quotation.service.QuotationTreeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

/**
 * task-0721 B6/B7 — 报价单 BOM 树上编辑端点（api.md §3/§4/§5）。
 *
 * <p>沿用 {@code QuotationResource} 类级鉴权（本需求不新增鉴权代码，需求说明 §7）。
 */
@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class QuotationTreeResource {

    @Inject
    QuotationTreeService treeService;

    /** repair-0727 B3.3：树删除执行后，作为独立第二事务补物化 row_data + 出 componentData 投影
     * （与 delete-driver-row 端点同一"tx1 写墓碑提交 → tx2 物化+投影"模式，避免锁互等，见
     * QuotationTreeService#executeDelete 方法体内的性能事故复盘注释）。 */
    @Inject
    CardSnapshotService cardSnapshotService;

    /** api.md §3 — 树上加叶子。 */
    @POST
    @Path("/{quotationId}/line-items/{lineItemId}/tree/add-leaf")
    public ApiResponse<Map<String, Object>> addLeaf(@PathParam("quotationId") UUID quotationId,
                                                     @PathParam("lineItemId") UUID lineItemId,
                                                     BomTreeAddLeafRequest req) {
        if (req == null || req.componentId == null || req.hostNodeId == null || req.partNo == null) {
            throw new BusinessException(400, "componentId / hostNodeId / partNo 均不能为空");
        }
        return ApiResponse.success(
                treeService.addLeaf(quotationId, lineItemId, req.componentId, req.hostNodeId, req.partNo));
    }

    /** api.md §4 — 删除影响面预览。 */
    @POST
    @Path("/{quotationId}/line-items/{lineItemId}/tree/delete-preview")
    public ApiResponse<Map<String, Object>> deletePreview(@PathParam("quotationId") UUID quotationId,
                                                           @PathParam("lineItemId") UUID lineItemId,
                                                           BomTreeDeleteRequest req) {
        if (req == null || req.componentId == null || req.mode == null || req.nodeId == null) {
            throw new BusinessException(400, "componentId / mode / nodeId 均不能为空");
        }
        return ApiResponse.success(
                treeService.previewDelete(quotationId, lineItemId, req.componentId, req.mode, req.nodeId, req.rowKey));
    }

    /**
     * api.md §5 — 执行删除。
     *
     * <p>repair-0727 B3.2/B3.3：两段式，与 {@code delete-driver-row}/{@code restore-driver-rows}
     * 同一模式（"tx1 写墓碑提交 → tx2 物化+投影"，见 {@code QuotationResource} 对应端点注释）——
     * tx1（{@link QuotationTreeService#executeDelete}）写墓碑 + 重算 {@code quoteCardValues} 并提交；
     * tx2（{@link CardSnapshotService#materializeRowDataAndProject}）在 tx1 已提交的基础上补物化
     * {@code row_data} + 出 {@code componentData} 投影。**不得**把两者合并回同一个 {@code @Transactional}
     * 方法——合并会导致 tx1 里原生 UPDATE 持有的行锁与 tx2 内部 {@code REQUIRES_NEW} 写同一行互相
     * 等待，实测在 DAG 级联删除场景撞 JTA 60s 事务超时（{@code ARJUNA016102}）。
     */
    @POST
    @Path("/{quotationId}/line-items/{lineItemId}/tree/delete")
    public ApiResponse<Map<String, Object>> deleteExecute(@PathParam("quotationId") UUID quotationId,
                                                           @PathParam("lineItemId") UUID lineItemId,
                                                           BomTreeDeleteRequest req) {
        if (req == null || req.componentId == null || req.mode == null || req.nodeId == null) {
            throw new BusinessException(400, "componentId / mode / nodeId 均不能为空");
        }
        Map<String, Object> result = treeService.executeDelete(quotationId, lineItemId, req.componentId, req.mode,
                req.nodeId, req.rowKey, req.previewToken);
        // tx2：独立事务补物化 row_data + 出 componentData 投影（仅 DRAFT；非 DRAFT/失败返回 null →
        // 不覆盖 result 里 tx1 已经填好的 quoteCardValues，前端据此回落旧行为，api.md §1.2 约定）。
        Map<String, Object> projection = cardSnapshotService.materializeRowDataAndProject(lineItemId);
        if (projection != null) {
            result.putAll(projection);
        }
        return ApiResponse.success(result);
    }
}
