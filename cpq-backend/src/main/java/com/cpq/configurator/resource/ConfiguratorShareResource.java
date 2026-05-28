package com.cpq.configurator.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configurator.entity.ConfiguratorShare;
import com.cpq.configurator.entity.ConfiguratorShareAccess;
import com.cpq.configurator.service.ConfiguratorShareService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/configurator/shares")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SYSTEM_ADMIN", "SALES_MANAGER", "SALES_REP", "PRICING_MANAGER"})
public class ConfiguratorShareResource {

    @Inject
    ConfiguratorShareService service;

    @GET
    public ApiResponse<PageResult<ConfiguratorShare>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("status") String status,
            @QueryParam("shareType") String shareType,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(service.list(page, size, status, shareType, keyword));
    }

    @GET
    @Path("/stats")
    public ApiResponse<Map<String, Long>> stats() {
        return ApiResponse.success(service.stats());
    }

    @POST
    public ApiResponse<ConfiguratorShare> create(Map<String, Object> body) {
        UUID instanceId = UUID.fromString(body.get("instance_id").toString());
        String email = body.get("email") == null ? null : body.get("email").toString();
        String type = body.get("share_type") == null ? null : body.get("share_type").toString();
        Integer days = body.get("days") instanceof Number ? ((Number) body.get("days")).intValue() : 7;
        Boolean canModify = body.get("can_modify") instanceof Boolean ? (Boolean) body.get("can_modify") : Boolean.FALSE;
        return ApiResponse.success(service.create(instanceId, type, email, days, canModify));
    }

    // 公网客户访问 token（无认证，由全局过滤器例外）
    @GET
    @Path("/by-token/{token}")
    @RoleAllowed({ "SYSTEM_ADMIN", "SALES_MANAGER", "SALES_REP", "PRICING_MANAGER" })
    public ApiResponse<ConfiguratorShare> getByToken(@PathParam("token") String token) {
        ConfiguratorShare s = ConfiguratorShare.find("shareToken", token).firstResult();
        if (s == null) throw new jakarta.ws.rs.NotFoundException("Share not found");
        return ApiResponse.success(s);
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ConfiguratorShare> get(@PathParam("id") UUID id) {
        return ApiResponse.success(service.getById(id));
    }

    @GET
    @Path("/{id}/access-log")
    public ApiResponse<List<ConfiguratorShareAccess>> accessLog(@PathParam("id") UUID id) {
        return ApiResponse.success(service.listAccess(id));
    }

    @POST
    @Path("/{id}/extend")
    public ApiResponse<ConfiguratorShare> extend(@PathParam("id") UUID id, Map<String, Object> body) {
        int days = 7;
        if (body != null && body.get("days") instanceof Number) days = ((Number) body.get("days")).intValue();
        return ApiResponse.success(service.extend(id, days));
    }

    @POST
    @Path("/{id}/revoke")
    public ApiResponse<ConfiguratorShare> revoke(@PathParam("id") UUID id, Map<String, Object> body) {
        String reason = body != null && body.get("reason") != null ? body.get("reason").toString() : null;
        return ApiResponse.success(service.revoke(id, reason, null));
    }
}
