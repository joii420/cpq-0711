package com.cpq.quotation.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.quotation.dto.BomTreeAddLeafRequest;
import com.cpq.quotation.dto.BomTreeDeleteRequest;
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

    /** api.md §5 — 执行删除。 */
    @POST
    @Path("/{quotationId}/line-items/{lineItemId}/tree/delete")
    public ApiResponse<Map<String, Object>> deleteExecute(@PathParam("quotationId") UUID quotationId,
                                                           @PathParam("lineItemId") UUID lineItemId,
                                                           BomTreeDeleteRequest req) {
        if (req == null || req.componentId == null || req.mode == null || req.nodeId == null) {
            throw new BusinessException(400, "componentId / mode / nodeId 均不能为空");
        }
        return ApiResponse.success(
                treeService.executeDelete(quotationId, lineItemId, req.componentId, req.mode, req.nodeId,
                        req.rowKey, req.previewToken));
    }
}
