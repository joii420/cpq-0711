package com.cpq.datasource.resolver;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.Set;

/**
 * I2/I3: 数据源解析统一端点 — 调试 / 公式编辑器预览 / 字段配置预览用.
 *
 * <p>请求体: {@code {type, config, driverRow}}.
 * <p>响应: 解析后的标量值或 null.
 */
@Path("/api/cpq/data-sources")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class DataSourceResolverResource {

    @Inject
    DataSourceResolverRegistry registry;

    @GET
    @Path("/types")
    public ApiResponse<Set<String>> listTypes() {
        return ApiResponse.success(registry.registeredTypes());
    }

    @POST
    @Path("/resolve")
    public ApiResponse<Object> resolve(ResolveRequest req) {
        if (req == null || req.type == null) {
            return ApiResponse.error(400, "type 必填");
        }
        try {
            return ApiResponse.success(registry.resolve(req.type, req.config, req.driverRow));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    public static class ResolveRequest {
        public String type;
        public Map<String, Object> config;
        public Map<String, Object> driverRow;
    }
}
