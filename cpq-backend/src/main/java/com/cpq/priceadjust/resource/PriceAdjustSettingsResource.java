package com.cpq.priceadjust.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.dto.PriceAdjustSettingsDTO;
import com.cpq.priceadjust.service.PriceAdjustSettingsService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * task-0729 · api.md §6.1 系统参数。
 *
 * <p>🔒 响应体裸 DTO，不套 {@code ApiResponse} 信封（同本包其余 Resource，详见
 * {@link PriceAdjustStrategyResource} 类注释）。
 *
 * <p>读权限放宽到 {@code PRICING_MANAGER}：屏 6 更新任务页要显示"当前阈值"才能解释某单为何被拦；
 * 写权限按 api.md §6.1 收紧到 {@code SYSTEM_ADMIN} 一家。
 */
@Path("/api/cpq/price-adjust/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceAdjustSettingsResource {

    @Inject PriceAdjustSettingsService service;
    @Inject SessionHelper sessionHelper;
    @Context HttpServerRequest httpRequest;

    @GET
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public PriceAdjustSettingsDTO get() {
        return service.get();
    }

    /** 仅 SYSTEM_ADMIN 可写（api.md §6.1）。即时生效，无需重启（验收 #70④）。 */
    @PUT
    @RoleAllowed({"SYSTEM_ADMIN"})
    public PriceAdjustSettingsDTO put(PriceAdjustSettingsDTO req) {
        UUID actorId = sessionHelper.getCurrentUserId(httpRequest);
        return service.put(req, actorId);
    }
}
