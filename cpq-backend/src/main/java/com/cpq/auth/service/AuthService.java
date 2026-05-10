package com.cpq.auth.service;

import com.cpq.auth.dto.LoginResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Handles authentication logic: credential verification, lockout tracking,
 * and first-login / expired-initial-password checks.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;

    /**
     * Validate credentials and return a {@link LoginResponse} on success.
     *
     * <p>Failure cases throw {@link BusinessException} with HTTP 401.
     *
     * <p><b>Important:</b> {@code dontRollbackOn = BusinessException.class} keeps the
     * failed-login-counter / lockout updates committed when we throw 401.
     * Without this, the @Transactional default would roll back the counter increment
     * on every wrong-password attempt and the lockout policy would never trigger.
     */
    @Transactional(dontRollbackOn = BusinessException.class)
    public LoginResponse login(String username, String password) {
        // Look up by username OR email
        User user = User.<User>find("username = ?1 OR email = ?1", username).firstResult();
        if (user == null) {
            LOG.warnf("Login attempt for unknown user: %s", username);
            throw new BusinessException(401, "用户名或密码错误");
        }

        // Account disabled
        if ("INACTIVE".equals(user.status)) {
            LOG.warnf("Login attempt for inactive user: %s", username);
            throw new BusinessException(401, "账号已被停用");
        }

        // Account locked
        if (user.lockedUntil != null && user.lockedUntil.isAfter(OffsetDateTime.now())) {
            LOG.warnf("Login attempt for locked user: %s, locked until %s", username, user.lockedUntil);
            throw new BusinessException(401, "账号已锁定，请30分钟后重试或联系管理员");
        }

        // Initial password expired (only for first-login users)
        if (Boolean.TRUE.equals(user.isFirstLogin)
                && user.initialPasswordExpiresAt != null
                && user.initialPasswordExpiresAt.isBefore(OffsetDateTime.now())) {
            LOG.warnf("Login attempt with expired initial password for user: %s", username);
            throw new BusinessException(401, "初始密码已过期，请联系管理员重置");
        }

        // Password check — wrap to defend against malformed bcrypt hashes ($2b$ etc.)
        boolean matches;
        try {
            matches = BCrypt.checkpw(password, user.passwordHash);
        } catch (IllegalArgumentException e) {
            // jBCrypt only supports $2a$ revision; legacy $2b$/$2y$ or corrupted hash → treat as wrong password
            LOG.warnf("BCrypt verification failed for user %s due to invalid hash format: %s", username, e.getMessage());
            matches = false;
        }
        if (!matches) {
            user.failedLoginAttempts = (user.failedLoginAttempts == null ? 0 : user.failedLoginAttempts) + 1;
            if (user.failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
                user.lockedUntil = OffsetDateTime.now().plusMinutes(LOCK_DURATION_MINUTES);
                user.failedLoginAttempts = 0;
                LOG.warnf("User account locked after %d failed attempts: %s", MAX_FAILED_ATTEMPTS, username);
            }
            LOG.warnf("Wrong password for user: %s (attempts=%d)", username, user.failedLoginAttempts);
            throw new BusinessException(401, "用户名或密码错误");
        }

        // Success: reset lockout counters
        user.failedLoginAttempts = 0;
        user.lockedUntil = null;

        LOG.infof("User logged in: %s role=%s", user.username, user.role);

        return new LoginResponse(
                user.id,
                user.username,
                user.fullName,
                user.role,
                Boolean.TRUE.equals(user.isFirstLogin)
        );
    }

    /**
     * Change the password for an authenticated user.
     * Verifies the current password before updating to the new one.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = User.findById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        boolean matches;
        try {
            matches = BCrypt.checkpw(currentPassword, user.passwordHash);
        } catch (IllegalArgumentException e) {
            matches = false;
        }
        if (!matches) {
            throw new BusinessException(400, "当前密码错误");
        }
        user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        if (user.isFirstLogin) user.isFirstLogin = false;
    }
}
