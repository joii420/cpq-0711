package com.cpq.common.security;

import com.cpq.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Provider
@Priority(Priorities.AUTHENTICATION - 10) // Run before auth
public class RateLimitFilter implements ContainerRequestFilter {

    @Inject ObjectMapper objectMapper;

    private static final int MAX_PER_MINUTE = 20;
    private static final ConcurrentHashMap<String, long[]> requestLog = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getPath();
        Set<String> rateLimitedPaths = Set.of(
            "api/cpq/auth/login",
            "api/cpq/auth/forgot-password",
            "api/cpq/auth/reset-password"
        );
        if (!rateLimitedPaths.contains(path)) return;
        if (!"POST".equalsIgnoreCase(ctx.getMethod())) return;

        String ip = ctx.getHeaderString("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = "unknown";
        else ip = ip.split(",")[0].trim();

        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;

        requestLog.compute(ip, (k, timestamps) -> {
            if (timestamps == null) return new long[]{now};
            long[] filtered = java.util.Arrays.stream(timestamps).filter(t -> t > windowStart).toArray();
            long[] result = new long[filtered.length + 1];
            System.arraycopy(filtered, 0, result, 0, filtered.length);
            result[filtered.length] = now;
            return result;
        });

        long[] ts = requestLog.get(ip);
        if (ts != null && java.util.Arrays.stream(ts).filter(t -> t > windowStart).count() > MAX_PER_MINUTE) {
            ctx.abortWith(Response.status(429)
                .entity(objectMapper.writeValueAsString(ApiResponse.error(429, "请求过于频繁，请稍后重试")))
                .type("application/json").build());
        }
    }
}
