package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.BatchExpandDriverRequest;
import com.cpq.component.dto.BatchExpandDriverRequest.Task;
import com.cpq.component.dto.BatchExpandDriverResponse;
import com.cpq.component.dto.BatchExpandDriverResponse.Result;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.dto.ExpandDriverRequest;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.component.service.ComponentService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Path("/api/cpq/components")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ComponentResource {

    private static final Logger LOG = Logger.getLogger(ComponentResource.class);

    @Inject
    ComponentService componentService;

    @Inject
    ComponentDriverService componentDriverService;

    @GET
    public ApiResponse<List<ComponentDTO>> list(
            @QueryParam("directoryId") UUID directoryId,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(componentService.list(directoryId, keyword));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ComponentDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(componentService.getById(id));
    }

    @POST
    public ApiResponse<ComponentDTO> create(CreateComponentRequest request) {
        return ApiResponse.success(componentService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ComponentDTO> update(
            @PathParam("id") UUID id,
            CreateComponentRequest request) {
        return ApiResponse.success(componentService.update(id, request));
    }

    @PATCH
    @Path("/{id}/toggle-status")
    public ApiResponse<ComponentDTO> toggleStatus(@PathParam("id") UUID id) {
        return ApiResponse.success(componentService.toggleStatus(id));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        componentService.delete(id);
        return ApiResponse.success();
    }

    /**
     * Y1.5 行驱动展开 — 按组件 dataDriverPath 取 N 行,
     * 每行隐式 JOIN 求值所有 BASIC_DATA 字段。
     *
     * 无 dataDriverPath → 返回 rowCount=0 (前端按单行渲染兜底)
     */
    @POST
    @Path("/{id}/expand-driver")
    public ApiResponse<ExpandDriverResponse> expandDriver(
            @PathParam("id") UUID id,
            ExpandDriverRequest req) {
        UUID customerId = req != null ? req.customerId : null;
        String partNo = req != null ? req.partNo : null;
        return ApiResponse.success(componentDriverService.expand(id, customerId, partNo));
    }

    /**
     * 批量行驱动展开 — 一次 HTTP 请求服务多个 (componentId, customerId, partNo) 组合。
     *
     * <p>每个 task 独立 try-catch，单个失败不影响其他结果。
     * 自动复用进程级缓存（{@link ComponentDriverService#expand} 内部处理）。
     * 单次 batch 上限 100 个 task，防止滥用。
     *
     * <p>Response key 格式：componentId:customerId:partNo（null 用 "_" 占位），
     * 与前端 expand-driver 缓存 key 格式一致。
     */
    @POST
    @Path("/batch-expand")
    public ApiResponse<BatchExpandDriverResponse> batchExpand(BatchExpandDriverRequest req) {
        BatchExpandDriverResponse resp = new BatchExpandDriverResponse();
        resp.results = new ArrayList<>();
        if (req == null || req.tasks == null) {
            return ApiResponse.success(resp);
        }
        if (req.tasks.size() > 100) {
            throw new BusinessException(400, "batch tasks 上限 100，当前 " + req.tasks.size());
        }
        for (Task t : req.tasks) {
            Result r = new Result();
            r.key = ComponentDriverService.cacheKey(t.componentId, t.customerId, t.partNo);
            try {
                r.data = componentDriverService.expand(t.componentId, t.customerId, t.partNo);
                r.status = "OK";
            } catch (Exception e) {
                r.status = "ERROR";
                r.error = e.getMessage();
                LOG.warnf("batch-expand: task %s failed: %s", r.key, e.getMessage());
            }
            resp.results.add(r);
        }
        return ApiResponse.success(resp);
    }
}
