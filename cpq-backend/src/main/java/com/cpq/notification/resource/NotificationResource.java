package com.cpq.notification.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.notification.entity.Notification;
import com.cpq.notification.service.NotificationService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class NotificationResource {

    @Inject
    NotificationService notificationService;

    @Inject
    SessionHelper sessionHelper;

    @GET
    public ApiResponse<List<Notification>> list(
            @Context HttpServerRequest request,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        UUID userId = sessionHelper.getCurrentUserId(request);
        return ApiResponse.success(notificationService.listByRecipient(userId, page, size));
    }

    @GET
    @Path("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(@Context HttpServerRequest request) {
        UUID userId = sessionHelper.getCurrentUserId(request);
        long count = notificationService.getUnreadCount(userId);
        return ApiResponse.success(Map.of("count", count));
    }

    @PUT
    @Path("/{id}/read")
    public ApiResponse<Void> markRead(@Context HttpServerRequest request,
                                       @PathParam("id") UUID id) {
        sessionHelper.getCurrentUserId(request); // auth check
        notificationService.markRead(id);
        return ApiResponse.success();
    }

    /** API.md alias: POST /notifications/{id}/mark-read */
    @POST
    @Path("/{id}/mark-read")
    public ApiResponse<Void> markReadAlias(@Context HttpServerRequest request,
                                            @PathParam("id") UUID id) {
        return markRead(request, id);
    }

    @PUT
    @Path("/read-all")
    public ApiResponse<Void> markAllRead(@Context HttpServerRequest request) {
        UUID userId = sessionHelper.getCurrentUserId(request);
        notificationService.markAllRead(userId);
        return ApiResponse.success();
    }

    /** API.md alias: POST /notifications/mark-all-read */
    @POST
    @Path("/mark-all-read")
    public ApiResponse<Void> markAllReadAlias(@Context HttpServerRequest request) {
        return markAllRead(request);
    }
}
