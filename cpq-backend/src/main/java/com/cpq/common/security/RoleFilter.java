package com.cpq.common.security;

import com.cpq.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class RoleFilter implements ContainerRequestFilter {

    @Context ResourceInfo resourceInfo;
    @Context io.vertx.core.http.HttpServerRequest vertxRequest;
    @Inject SessionHelper sessionHelper;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "cpq.security.rbac.enabled", defaultValue = "true")
    boolean rbacEnabled;

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "api/cpq/health",
        "api/cpq/auth/login",
        "api/cpq/auth/forgot-password",
        "api/cpq/auth/reset-password"
    );

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        if (!rbacEnabled) return;

        // Normalize path: JAX-RS spec says no leading "/", but RestEasy Reactive may include it
        String path = ctx.getUriInfo().getPath();
        if (path == null) return;
        if (path.startsWith("/")) path = path.substring(1);

        // Skip non-API paths. Both /api/cpq/* (business APIs) and /api/system/*
        // (system management APIs — system_config, locks, DDL extension) flow
        // through the @RoleAllowed annotation gate.
        if (!path.startsWith("api/cpq/") && !path.startsWith("api/system/")) return;

        // Skip public paths (login / forgot-password / reset-password / health)
        final String normalizedPath = path;
        if (PUBLIC_PATHS.stream().anyMatch(normalizedPath::startsWith)) return;

        // Check role annotation (Option B: skip entirely if no @RoleAllowed)
        RoleAllowed methodAnno = resourceInfo.getResourceMethod() != null ?
            resourceInfo.getResourceMethod().getAnnotation(RoleAllowed.class) : null;
        RoleAllowed classAnno = resourceInfo.getResourceClass() != null ?
            resourceInfo.getResourceClass().getAnnotation(RoleAllowed.class) : null;
        RoleAllowed anno = methodAnno != null ? methodAnno : classAnno;

        if (anno == null) return; // No role restriction — skip auth check too

        // Check authentication
        if (!sessionHelper.isLoggedIn(vertxRequest)) {
            ctx.abortWith(Response.status(401)
                .entity(objectMapper.writeValueAsString(ApiResponse.error(401, "未登录")))
                .type("application/json").build());
            return;
        }

        // Check role authorization
        String role = sessionHelper.getCurrentUserRole(vertxRequest);
        if (!Arrays.asList(anno.value()).contains(role)) {
            ctx.abortWith(Response.status(403)
                .entity(objectMapper.writeValueAsString(ApiResponse.error(403, "无权限访问")))
                .type("application/json").build());
        }
    }
}
