package com.cpq.auth.service;

import com.cpq.auth.entity.PasswordResetToken;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.entity.User;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Handles password reset token generation, email dispatch, and token validation.
 */
@ApplicationScoped
public class PasswordResetService {

    private static final Logger LOG = Logger.getLogger(PasswordResetService.class);

    @Inject
    Mailer mailer;

    /**
     * Generate a password reset token for the user with the given email.
     * Silently does nothing if the email is not found (to avoid user enumeration).
     */
    @Transactional
    public void requestReset(String email) {
        User user = User.<User>find("email", email).firstResult();
        if (user == null) {
            LOG.debugf("Password reset requested for unknown email: %s (silently ignored)", email);
            return;
        }

        // Generate raw token and hash it for storage
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = sha256Hex(rawToken);

        // Invalidate previous active tokens for this user
        PasswordResetToken.update(
                "usedAt = ?1 WHERE userId = ?2 AND usedAt IS NULL",
                OffsetDateTime.now(), user.id);

        // Persist new token
        PasswordResetToken token = new PasswordResetToken();
        token.userId = user.id;
        token.tokenHash = tokenHash;
        token.expiresAt = OffsetDateTime.now().plusHours(1);
        token.persist();

        // Send email
        String resetLink = "http://localhost:5174/reset-password?token=" + rawToken;
        mailer.send(Mail.withHtml(
                email,
                "CPQ系统 - 密码重置",
                "<p>您好 " + user.fullName + "，</p>" +
                "<p>您请求了密码重置。请点击以下链接在1小时内完成重置：</p>" +
                "<p><a href=\"" + resetLink + "\">" + resetLink + "</a></p>" +
                "<p>如果您没有请求此操作，请忽略此邮件。</p>"
        ));

        LOG.infof("Password reset email sent to: %s", email);
    }

    /**
     * Validate a reset token and update the user's password.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256Hex(rawToken);

        PasswordResetToken token = PasswordResetToken
                .<PasswordResetToken>find("tokenHash", tokenHash)
                .firstResult();

        if (token == null) {
            throw new BusinessException(400, "无效的重置令牌");
        }
        if (token.usedAt != null) {
            throw new BusinessException(400, "重置令牌已被使用");
        }
        if (token.expiresAt.isBefore(OffsetDateTime.now())) {
            throw new BusinessException(400, "重置令牌已过期");
        }

        User user = User.findById(token.userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        // Update password
        user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        user.isFirstLogin = false;

        // Mark token as used
        token.usedAt = OffsetDateTime.now();

        LOG.infof("Password reset completed for user: %s", user.username);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
