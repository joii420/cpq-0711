package com.cpq.template.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.template.dto.TemplateGvBindingDTO;
import com.cpq.template.dto.UpdateBindingsRequest;
import com.cpq.template.service.TemplateGvBindingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * V212: 模板全局变量绑定端点.
 *
 * <p>端点 1: GET  /api/cpq/templates/{tid}/global-variable-bindings → List&lt;TemplateGvBindingDTO&gt;
 * <p>端点 2: PUT  /api/cpq/templates/{tid}/global-variable-bindings → List&lt;TemplateGvBindingDTO&gt;
 *
 * <p>权限: PRICING_MANAGER + SYSTEM_ADMIN 可写; 其余角色只读.
 */
@Path("/api/cpq/templates/{tid}/global-variable-bindings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class TemplateGvBindingResource {

    @Inject
    TemplateGvBindingService templateGvBindingService;

    /**
     * 获取模板的全局变量绑定列表.
     *
     * @param tid 模板 ID
     * @return 按 display_order 升序的绑定列表 (含 GVD 名称/类型/单位)
     */
    @GET
    public ApiResponse<List<TemplateGvBindingDTO>> list(@PathParam("tid") UUID tid) {
        return ApiResponse.success(templateGvBindingService.listByTemplateId(tid));
    }

    /**
     * 全量替换模板的全局变量绑定 (PUT 语义).
     *
     * <p>仅 DRAFT 模板可操作 (否则返 403).
     *
     * @param tid 模板 ID
     * @param req 绑定列表 (全量替换)
     * @return 更新后的绑定列表
     */
    @PUT
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<TemplateGvBindingDTO>> updateBindings(
            @PathParam("tid") UUID tid,
            UpdateBindingsRequest req) {
        return ApiResponse.success(templateGvBindingService.updateBindings(tid, req));
    }
}
