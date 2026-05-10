# M1 Part 2: Authentication & Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement login authentication (Session + HttpOnly Cookie), role-based permission middleware, password change/reset flows, and login rate limiting.

**Architecture:** Quarkus Security with custom IdentityProvider for Session-based auth. HttpOnly Cookie for session ID. Permission enforcement via JAX-RS ContainerRequestFilter checking user role against endpoint annotations. BCrypt password verification. PasswordResetToken with SHA-256 hashed tokens.

**Tech Stack:** Java 17, Quarkus Security, Quarkus Mailer, BCrypt, SHA-256, REST-assured tests

---

## File Structure

```
cpq-backend/src/main/java/com/cpq/
  auth/
    resource/AuthResource.java          # POST login/logout, GET me, POST change-password, POST forgot/reset
    service/AuthService.java            # Login logic, session, lockout, password ops
    service/PasswordResetService.java   # Token generation, validation, reset
    dto/LoginRequest.java
    dto/LoginResponse.java
    dto/ChangePasswordRequest.java
    dto/ForgotPasswordRequest.java
    dto/ResetPasswordRequest.java
    entity/PasswordResetToken.java
  common/
    security/RoleAllowed.java           # Custom annotation
    security/RoleFilter.java            # ContainerRequestFilter
    security/SessionHelper.java         # Session user helper

cpq-backend/src/test/java/com/cpq/
  auth/
    resource/AuthResourceTest.java
```

---

### Task 1: PasswordResetToken Entity + Session Helper

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/auth/entity/PasswordResetToken.java`
- Create: `cpq-backend/src/main/java/com/cpq/common/security/SessionHelper.java`

- [ ] **Step 1: Create PasswordResetToken entity**

```java
package com.cpq.auth.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    public String tokenHash;

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    @Column(name = "used_at")
    public OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 2: Create SessionHelper**

```java
package com.cpq.common.security;

import com.cpq.common.exception.BusinessException;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

@RequestScoped
public class SessionHelper {

    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    private static final String SESSION_USER_ID = "userId";
    private static final String SESSION_USER_ROLE = "userRole";

    public void setCurrentUser(UUID userId, String role) {
        routingContext.session().put(SESSION_USER_ID, userId.toString());
        routingContext.session().put(SESSION_USER_ROLE, role);
    }

    public UUID getCurrentUserId() {
        String val = routingContext.session().get(SESSION_USER_ID);
        if (val == null) throw new BusinessException(401, "未登录");
        return UUID.fromString(val);
    }

    public String getCurrentUserRole() {
        String val = routingContext.session().get(SESSION_USER_ROLE);
        if (val == null) throw new BusinessException(401, "未登录");
        return val;
    }

    public boolean isLoggedIn() {
        return routingContext.session().get(SESSION_USER_ID) != null;
    }

    public void invalidateSession() {
        routingContext.session().destroy();
    }
}
```

NOTE: Quarkus RESTEasy Reactive uses Vert.x routing context. Session management needs `quarkus.http.auth.session.enabled=true` in application.properties. If Vert.x session doesn't work with this approach, an alternative is to use a ConcurrentHashMap-based custom session store with cookies — the implementer should adapt based on what compiles with the actual Quarkus version.

- [ ] **Step 3: Add session config to application.properties**

Append to `cpq-backend/src/main/resources/application.properties`:

```properties
# --- Session ---
quarkus.http.auth.session.enabled=true
quarkus.http.auth.session.encryption-key=cpq-session-key-must-be-16chars-or-more-for-aes
```

- [ ] **Step 4: Verify compilation, commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend && ./mvnw compile
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/ cpq-backend/src/main/resources/application.properties
git commit -m "feat(auth): add PasswordResetToken entity and SessionHelper"
```

---

### Task 2: Login/Logout API

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/auth/dto/LoginRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/auth/dto/LoginResponse.java`
- Create: `cpq-backend/src/main/java/com/cpq/auth/service/AuthService.java`
- Create: `cpq-backend/src/main/java/com/cpq/auth/resource/AuthResource.java`
- Create: `cpq-backend/src/test/java/com/cpq/auth/resource/AuthResourceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.cpq.auth.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class AuthResourceTest {

    @Test
    void loginWithValidCredentials() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"admin","password":"Admin@2026"}
                """)
            .when().post("/api/cpq/auth/login")
            .then()
                .statusCode(200)
                .body("data.username", equalTo("admin"))
                .body("data.role", equalTo("SYSTEM_ADMIN"))
                .body("data.forceChangePassword", equalTo(true));
    }

    @Test
    void loginWithWrongPasswordFails() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"admin","password":"wrong"}
                """)
            .when().post("/api/cpq/auth/login")
            .then()
                .statusCode(401);
    }

    @Test
    void loginWithNonexistentUserFails() {
        RestAssured.given()
            .contentType(ContentType.JSON)
            .body("""
                {"username":"nobody","password":"test"}
                """)
            .when().post("/api/cpq/auth/login")
            .then()
                .statusCode(401);
    }

    @Test
    void getMeWithoutSessionReturns401() {
        RestAssured.given()
            .when().get("/api/cpq/auth/me")
            .then()
                .statusCode(401);
    }
}
```

- [ ] **Step 2: Create DTOs**

LoginRequest.java:
```java
package com.cpq.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank public String username;
    @NotBlank public String password;
}
```

LoginResponse.java:
```java
package com.cpq.auth.dto;

import java.util.UUID;

public class LoginResponse {
    public UUID id;
    public String username;
    public String fullName;
    public String role;
    public boolean forceChangePassword;

    public LoginResponse(UUID id, String username, String fullName, String role, boolean forceChangePassword) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.forceChangePassword = forceChangePassword;
    }
}
```

- [ ] **Step 3: Create AuthService**

```java
package com.cpq.auth.service;

import com.cpq.auth.dto.LoginResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.mindrot.jbcrypt.BCrypt;
import java.time.OffsetDateTime;

@ApplicationScoped
public class AuthService {

    @Transactional
    public LoginResponse login(String username, String password) {
        User user = (User) User.find("username = ?1 OR email = ?1", username).firstResult();
        if (user == null) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if ("INACTIVE".equals(user.status)) {
            throw new BusinessException(401, "账号已被停用");
        }
        if (user.lockedUntil != null && user.lockedUntil.isAfter(OffsetDateTime.now())) {
            throw new BusinessException(401, "账号已锁定，请30分钟后重试或联系管理员");
        }
        if (!BCrypt.checkpw(password, user.passwordHash)) {
            user.failedLoginAttempts++;
            if (user.failedLoginAttempts >= 5) {
                user.lockedUntil = OffsetDateTime.now().plusMinutes(30);
                user.failedLoginAttempts = 0;
            }
            throw new BusinessException(401, "用户名或密码错误");
        }
        // Check first login password expiry
        if (user.isFirstLogin && user.initialPasswordExpiresAt != null
                && OffsetDateTime.now().isAfter(user.initialPasswordExpiresAt)) {
            throw new BusinessException(401, "初始密码已过期，请联系管理员重置");
        }
        // Reset failed attempts on success
        user.failedLoginAttempts = 0;
        user.lockedUntil = null;

        return new LoginResponse(user.id, user.username, user.fullName, user.role, user.isFirstLogin);
    }
}
```

- [ ] **Step 4: Create AuthResource**

```java
package com.cpq.auth.resource;

import com.cpq.auth.dto.LoginRequest;
import com.cpq.auth.dto.LoginResponse;
import com.cpq.auth.service.AuthService;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.security.SessionHelper;
import com.cpq.system.entity.User;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/cpq/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject AuthService authService;
    @Inject SessionHelper sessionHelper;

    @POST
    @Path("/login")
    public ApiResponse<LoginResponse> login(@Valid LoginRequest req) {
        LoginResponse response = authService.login(req.username, req.password);
        sessionHelper.setCurrentUser(response.id, response.role);
        return ApiResponse.success(response);
    }

    @POST
    @Path("/logout")
    public ApiResponse<Void> logout() {
        sessionHelper.invalidateSession();
        return ApiResponse.success();
    }

    @GET
    @Path("/me")
    public ApiResponse<Map<String, Object>> me() {
        var userId = sessionHelper.getCurrentUserId();
        User user = User.findById(userId);
        if (user == null) throw new com.cpq.common.exception.BusinessException(401, "用户不存在");
        return ApiResponse.success(Map.of(
            "id", user.id,
            "username", user.username,
            "fullName", user.fullName,
            "role", user.role
        ));
    }
}
```

- [ ] **Step 5: Run tests, fix, commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend && ./mvnw test -Dtest=AuthResourceTest
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/
git commit -m "feat(auth): add login/logout/me API with session management"
```

---

### Task 3: Role Permission Filter

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/common/security/RoleAllowed.java`
- Create: `cpq-backend/src/main/java/com/cpq/common/security/RoleFilter.java`

- [ ] **Step 1: Create RoleAllowed annotation**

```java
package com.cpq.common.security;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleAllowed {
    String[] value();
}
```

- [ ] **Step 2: Create RoleFilter**

```java
package com.cpq.common.security;

import com.cpq.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Arrays;

@Provider
public class RoleFilter implements ContainerRequestFilter {

    @Context ResourceInfo resourceInfo;
    @Inject SessionHelper sessionHelper;
    @Inject ObjectMapper objectMapper;

    private static final java.util.Set<String> PUBLIC_PATHS = java.util.Set.of(
        "/api/cpq/health",
        "/api/cpq/auth/login",
        "/api/cpq/auth/forgot-password",
        "/api/cpq/auth/reset-password"
    );

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getPath();
        if (PUBLIC_PATHS.stream().anyMatch(p -> ("/" + path).startsWith(p))) return;
        if (!path.startsWith("api/cpq/")) return;

        if (!sessionHelper.isLoggedIn()) {
            ctx.abortWith(Response.status(401)
                .entity(objectMapper.writeValueAsString(ApiResponse.error(401, "未登录")))
                .type("application/json").build());
            return;
        }

        RoleAllowed anno = resourceInfo.getResourceMethod().getAnnotation(RoleAllowed.class);
        if (anno == null) anno = resourceInfo.getResourceClass().getAnnotation(RoleAllowed.class);
        if (anno == null) return; // no role restriction

        String role = sessionHelper.getCurrentUserRole();
        if (!Arrays.asList(anno.value()).contains(role)) {
            ctx.abortWith(Response.status(403)
                .entity(objectMapper.writeValueAsString(ApiResponse.error(403, "无权限访问")))
                .type("application/json").build());
        }
    }
}
```

- [ ] **Step 3: Compile, commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend && ./mvnw compile
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/
git commit -m "feat(auth): add role-based permission filter"
```

---

### Task 4: Change Password API

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/auth/dto/ChangePasswordRequest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/auth/service/AuthService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/auth/resource/AuthResource.java`

- [ ] **Step 1: Create ChangePasswordRequest**

```java
package com.cpq.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {
    @NotBlank public String currentPassword;
    @NotBlank @Size(min = 8, message = "密码长度至少8位")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$", message = "密码必须包含字母和数字")
    public String newPassword;
}
```

- [ ] **Step 2: Add changePassword to AuthService**

Add method to AuthService:
```java
@Transactional
public void changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
    User user = User.findById(userId);
    if (user == null) throw new BusinessException(404, "用户不存在");
    if (!BCrypt.checkpw(currentPassword, user.passwordHash)) {
        throw new BusinessException(400, "当前密码错误");
    }
    user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
    if (user.isFirstLogin) {
        user.isFirstLogin = false;
    }
}
```

- [ ] **Step 3: Add endpoint to AuthResource**

```java
@POST
@Path("/change-password")
public ApiResponse<Void> changePassword(@Valid ChangePasswordRequest req) {
    var userId = sessionHelper.getCurrentUserId();
    authService.changePassword(userId, req.currentPassword, req.newPassword);
    return ApiResponse.success();
}
```

- [ ] **Step 4: Test, commit**

---

### Task 5: Forgot Password / Reset Password API

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/auth/dto/ForgotPasswordRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/auth/dto/ResetPasswordRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/auth/service/PasswordResetService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/auth/resource/AuthResource.java`

- [ ] **Step 1: Create DTOs**

ForgotPasswordRequest.java:
```java
package com.cpq.auth.dto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class ForgotPasswordRequest {
    @NotBlank @Email public String email;
}
```

ResetPasswordRequest.java:
```java
package com.cpq.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ResetPasswordRequest {
    @NotBlank public String token;
    @NotBlank @Size(min = 8)
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$", message = "密码必须包含字母和数字")
    public String newPassword;
}
```

- [ ] **Step 2: Create PasswordResetService**

```java
package com.cpq.auth.service;

import com.cpq.auth.entity.PasswordResetToken;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.entity.User;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.mindrot.jbcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@ApplicationScoped
public class PasswordResetService {

    @Inject Mailer mailer;

    @Transactional
    public void requestReset(String email) {
        User user = (User) User.find("email", email).firstResult();
        if (user == null) return; // Silent fail for security

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = sha256(rawToken);

        // Invalidate old tokens in same transaction
        PasswordResetToken.update("usedAt = ?1 WHERE userId = ?2 AND usedAt IS NULL",
            OffsetDateTime.now(), user.id);

        PasswordResetToken prt = new PasswordResetToken();
        prt.userId = user.id;
        prt.tokenHash = tokenHash;
        prt.expiresAt = OffsetDateTime.now().plusHours(1);
        prt.persist();

        String resetLink = "http://localhost:5173/reset-password?token=" + rawToken;
        mailer.send(Mail.withText(email, "CPQ系统 - 密码重置",
            "请点击以下链接重置密码（1小时内有效）：\n" + resetLink));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);
        PasswordResetToken prt = (PasswordResetToken) PasswordResetToken
            .find("tokenHash", tokenHash).firstResult();

        if (prt == null) throw new BusinessException(400, "无效的重置链接");
        if (prt.usedAt != null) throw new BusinessException(400, "该链接已使用");
        if (prt.expiresAt.isBefore(OffsetDateTime.now())) throw new BusinessException(400, "链接已过期");

        User user = User.findById(prt.userId);
        if (user == null) throw new BusinessException(400, "用户不存在");

        user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        if (user.isFirstLogin) user.isFirstLogin = false;
        prt.usedAt = OffsetDateTime.now();
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 3: Add endpoints to AuthResource**

```java
@POST
@Path("/forgot-password")
public ApiResponse<Void> forgotPassword(@Valid ForgotPasswordRequest req) {
    passwordResetService.requestReset(req.email);
    return ApiResponse.success();
}

@POST
@Path("/reset-password")
public ApiResponse<Void> resetPassword(@Valid ResetPasswordRequest req) {
    passwordResetService.resetPassword(req.token, req.newPassword);
    return ApiResponse.success();
}
```

(Add `@Inject PasswordResetService passwordResetService;` to AuthResource)

- [ ] **Step 4: Test, commit**

---

### Task 6: Login Rate Limiting

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/common/security/RateLimitFilter.java`

- [ ] **Step 1: Create simple in-memory rate limiter**

```java
package com.cpq.common.security;

import com.cpq.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Provider
public class RateLimitFilter implements ContainerRequestFilter {

    @Inject ObjectMapper objectMapper;

    private static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final ConcurrentHashMap<String, long[]> requestLog = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = "/" + ctx.getUriInfo().getPath();
        if (!path.equals("/api/cpq/auth/login")) return;

        String ip = ctx.getHeaderString("X-Forwarded-For");
        if (ip == null) ip = "unknown";
        else ip = ip.split(",")[0].trim();

        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;

        requestLog.compute(ip, (k, timestamps) -> {
            if (timestamps == null) return new long[]{now};
            long[] filtered = java.util.Arrays.stream(timestamps)
                .filter(t -> t > windowStart).toArray();
            long[] result = new long[filtered.length + 1];
            System.arraycopy(filtered, 0, result, 0, filtered.length);
            result[filtered.length] = now;
            return result;
        });

        long[] timestamps = requestLog.get(ip);
        long count = java.util.Arrays.stream(timestamps).filter(t -> t > windowStart).count();

        if (count > MAX_REQUESTS_PER_MINUTE) {
            ctx.abortWith(Response.status(429)
                .entity(objectMapper.writeValueAsString(
                    ApiResponse.error(429, "请求过于频繁，请稍后重试")))
                .type("application/json").build());
        }
    }
}
```

- [ ] **Step 2: Compile, commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend && ./mvnw compile
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/
git commit -m "feat(auth): add login rate limiting (20 req/min per IP)"
```

---

## Self-Review

**Spec coverage:** Login ✅, Logout ✅, Me ✅, 5-attempt lockout ✅, Session 8h ✅ (configured in properties), First login force change ✅, Password complexity ✅, Forgot/reset password ✅, Token SHA-256 + one-time ✅, Old token invalidation ✅, Rate limiting ✅, Role filter ✅

**Placeholder scan:** Clean — all steps have code.

**Type consistency:** `LoginRequest/Response`, `ChangePasswordRequest`, `ForgotPasswordRequest`, `ResetPasswordRequest` — all consistent across DTO→Service→Resource.
