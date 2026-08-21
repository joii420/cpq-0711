package com.cpq.builder.resource;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.dto.BuilderDTOs.*;
import com.cpq.builder.service.BuilderService;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * 取数配置器 builder 端点族（task-260819 B-11/B-12/B-13/B-15/B-20，api.md §2）。
 *
 * <p>路径 {@code /api/cpq/components/{componentId}/builder}。角色 {@code SYSTEM_ADMIN} +
 * {@code PRICING_MANAGER}——与组件管理现有口径一致（backtask.md §3 要求开工前复核；本轮复核
 * 结论：{@code ComponentResource}/{@code ComponentSqlViewResource} 现网口径不完全统一——前者对
 * 全部 4 角色开放读写，后者写操作限 {@code SALES_MANAGER}+{@code SYSTEM_ADMIN}。取数配置器改的
 * 是 SQL 取数逻辑，风险层级更接近后者，故本资源族按 api.md 已写明的
 * {@code SYSTEM_ADMIN + PRICING_MANAGER} 执行，不再额外放宽）。
 *
 * <p>🚫 全部响应裸体，不套 {@code ApiResponse} 信封（api.md §1.5③）。
 */
@Path("/api/cpq/components/{componentId}/builder")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER"})
public class BuilderResource {

    @Inject BuilderService service;
    @Inject SessionHelper sessionHelper;
    @Context HttpServerRequest vertxRequest;

    @GET
    public GetBuilderResponse get(@PathParam("componentId") UUID componentId) {
        return service.get(componentId);
    }

    @POST
    @Path("/compile")
    public CompileResponse compile(@PathParam("componentId") UUID componentId, BuilderConfig cfg) {
        return service.compile(componentId, cfg);
    }

    @POST
    @Path("/preview")
    public PreviewResponse preview(@PathParam("componentId") UUID componentId, PreviewRequest req) {
        return service.preview(componentId, req);
    }

    @POST
    @Path("/inspect")
    public InspectResponse inspect(@PathParam("componentId") UUID componentId, BuilderConfig cfg) {
        return service.inspect(componentId, cfg);
    }

    @PUT
    public SaveResponse save(@PathParam("componentId") UUID componentId, SaveRequest req) {
        return service.save(componentId, req, currentOperator());
    }

    @POST
    @Path("/detach")
    public void detach(@PathParam("componentId") UUID componentId) {
        service.detach(componentId);
    }

    private String currentOperator() {
        try {
            return sessionHelper.getCurrentUserIdOrFallback(vertxRequest).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
