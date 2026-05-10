package com.cpq.auth.resource;

import com.cpq.auth.dto.ChangePasswordRequest;
import com.cpq.auth.dto.ForgotPasswordRequest;
import com.cpq.auth.dto.LoginRequest;
import com.cpq.auth.dto.LoginResponse;
import com.cpq.auth.dto.ResetPasswordRequest;
import com.cpq.auth.service.AuthService;
import com.cpq.auth.service.PasswordResetService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.SessionHelper;
import com.cpq.system.entity.User;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication endpoints: login, logout, and current-user info.
 */
@Path("/api/cpq/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class);

    @Inject
    AuthService authService;

    @Inject
    SessionHelper sessionHelper;

    @Inject
    PasswordResetService passwordResetService;

    @Inject
    com.cpq.auth.service.LoginRateLimiter loginRateLimiter;

    @Context
    HttpServerRequest request;

    @Context
    HttpServerResponse response;

    /**
     * POST /api/cpq/auth/login
     * Validate credentials, create a session, and return user info.
     *
     * <p>Rate-limited (T4 P2): per-IP and per-username sliding windows in Redis
     * reject requests above the threshold with 429.
     */
    @POST
    @Path("/login")
    public ApiResponse<LoginResponse> login(@Valid LoginRequest loginRequest) {
        loginRateLimiter.check(loginRequest.username, request);
        LoginResponse loginResponse = authService.login(loginRequest.username, loginRequest.password);
        sessionHelper.createSession(loginResponse.id, loginResponse.role, request, response);
        LOG.infof("Session created for user: %s", loginResponse.username);
        return ApiResponse.success(loginResponse);
    }

    /**
     * POST /api/cpq/auth/logout
     * Destroy the current session.
     * No request body expected, so we accept any (or no) content type.
     */
    @POST
    @Path("/logout")
    @Consumes(MediaType.WILDCARD)
    public ApiResponse<Void> logout() {
        sessionHelper.invalidateSession(request, response);
        LOG.info("Session invalidated");
        return ApiResponse.success();
    }

    /**
     * POST /api/cpq/auth/change-password
     * Change the current user's password after verifying the current password.
     */
    @POST
    @Path("/change-password")
    public ApiResponse<Void> changePassword(@Valid ChangePasswordRequest req) {
        UUID userId = sessionHelper.getCurrentUserId(request);
        authService.changePassword(userId, req.currentPassword, req.newPassword);
        return ApiResponse.success();
    }

    /**
     * POST /api/cpq/auth/forgot-password
     * Request a password reset link via email. Always returns success (no user enumeration).
     */
    @POST
    @Path("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid ForgotPasswordRequest req) {
        passwordResetService.requestReset(req.email);
        return ApiResponse.success();
    }

    /**
     * POST /api/cpq/auth/reset-password
     * Reset the password using a valid reset token.
     */
    @POST
    @Path("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token, req.newPassword);
        return ApiResponse.success();
    }

    /**
     * GET /api/cpq/auth/me
     * Return basic information about the currently authenticated user.
     * Throws 401 if not logged in (via SessionHelper).
     */
    @GET
    @Path("/me")
    public ApiResponse<Map<String, Object>> me() {
        UUID userId = sessionHelper.getCurrentUserId(request);
        User user = User.findById(userId);
        if (user == null) {
            // Session refers to a deleted user — treat as not logged in
            sessionHelper.invalidateSession(request, response);
            throw new com.cpq.common.exception.BusinessException(401, "未登录");
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", user.id);
        info.put("username", user.username);
        info.put("fullName", user.fullName);
        info.put("role", user.role);
        info.put("forceChangePassword", Boolean.TRUE.equals(user.isFirstLogin));

        return ApiResponse.success(info);
    }
}
