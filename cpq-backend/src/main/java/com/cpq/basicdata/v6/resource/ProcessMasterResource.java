package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.ProcessMasterDTO;
import com.cpq.basicdata.v6.service.ProcessMasterReadService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/**
 * V6 工序主数据查询端点（只读）。
 *
 * <p>路由：{@code GET /api/cpq/v6/process-master}
 */
@Path("/api/cpq/v6/process-master")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProcessMasterResource {

    @Inject
    ProcessMasterReadService service;

    /**
     * 分页查询工序主数据列表，支持 processNo / processName 模糊搜索。
     *
     * @param page    页码，从 0 开始（默认 0）
     * @param size    每页条数（默认 20，最大 200）
     * @param keyword 关键字，可为空
     */
    @GET
    @RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<ProcessMasterDTO>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(service.list(page, size, keyword));
    }
}
