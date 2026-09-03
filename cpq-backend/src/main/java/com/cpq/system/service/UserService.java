package com.cpq.system.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.notification.service.NotificationService;
import com.cpq.system.dto.CreateUserRequest;
import com.cpq.system.dto.UpdateUserRequest;
import com.cpq.system.dto.UserDTO;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class UserService {

    private static final Logger LOG = Logger.getLogger(UserService.class);

    @Inject
    NotificationService notificationService;

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$";
    private static final int PASSWORD_LENGTH = 10;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * <b>用户列表过滤条件的唯一出处</b>（task-260902 · B-3 抽取，条件文本逐字沿用改造前的 list()）。
     *
     * <p>列表分页查询与<b>导出</b>共用本方法 —— 🚫 导出侧不许照着描述另写一套 JPQL，
     * 否则「页面筛出 N 条、导出文件 M 条」这种对不上的现象迟早出现。
     *
     * @param params 出参：把绑定参数填进这个 Map（调用方原样交给 Panache）
     * @return JPQL where 片段（恒以 {@code 1=1} 开头，可直接拼 {@code " ORDER BY ..."}）
     */
    static String buildFilter(String role, String status, String keyword, Map<String, Object> params) {
        StringBuilder query = new StringBuilder("1=1");
        if (role != null && !role.isBlank()) {
            query.append(" AND role = :role");
            params.put("role", role);
        }
        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.append(" AND (username LIKE :kw OR fullName LIKE :kw OR email LIKE :kw)");
            params.put("kw", "%" + keyword + "%");
        }
        return query.toString();
    }

    public PageResult<UserDTO> list(int page, int size, String role, String status, String keyword) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder query = new StringBuilder(buildFilter(role, status, keyword, params));

        long total = User.count(query.toString(), params);
        List<UserDTO> content = User
                .find(query + " ORDER BY createdAt ASC", params)
                .page(page, size)
                .list()
                .stream()
                .map(u -> UserDTO.from((User) u))
                .collect(Collectors.toList());

        LOG.debugf("list users page=%d size=%d total=%d", page, size, total);
        return new PageResult<>(content, page, size, total);
    }

    @Transactional
    public UserDTO create(CreateUserRequest request) {
        // Check username uniqueness
        if (User.count("username = ?1", request.username) > 0) {
            throw new BusinessException("用户名已存在: " + request.username);
        }
        // Check email uniqueness
        if (User.count("email = ?1", request.email) > 0) {
            throw new BusinessException("邮箱已存在: " + request.email);
        }

        String rawPassword = generatePassword();
        String hash = hashPassword(rawPassword);

        User user = new User();
        user.username = request.username;
        user.fullName = request.fullName;
        user.email = request.email;
        user.role = request.role;
        user.regionId = request.regionId;
        user.departmentId = request.departmentId;
        user.passwordHash = hash;
        user.isFirstLogin = true;
        user.status = "ACTIVE";
        user.initialPasswordExpiresAt = OffsetDateTime.now().plusHours(24);
        user.persist();

        LOG.infof("Created user username=%s role=%s", user.username, user.role);

        UserDTO dto = UserDTO.from(user);
        dto.initialPassword = rawPassword;
        return dto;
    }

    @Transactional
    public UserDTO update(UUID id, UpdateUserRequest request) {
        User user = User.findById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + id);
        }

        // Check email uniqueness on change
        if (request.email != null && !request.email.equals(user.email)) {
            if (User.count("email = ?1 AND id != ?2", request.email, id) > 0) {
                throw new BusinessException("邮箱已被使用: " + request.email);
            }
            user.email = request.email;
        }

        // Protect last SYSTEM_ADMIN from role downgrade
        if (request.role != null && !request.role.equals(user.role)) {
            if ("SYSTEM_ADMIN".equals(user.role)) {
                long adminCount = User.count("role = 'SYSTEM_ADMIN' AND status = 'ACTIVE'");
                if (adminCount <= 1) {
                    throw new BusinessException("不能修改最后一个系统管理员的角色");
                }
            }
            String oldRole = user.role;
            user.role = request.role;
            // Notify user about role change
            notificationService.create(id, "ROLE_CHANGED",
                    "您的系统角色已变更为" + request.role,
                    "您的账号角色已由 " + oldRole + " 变更为 " + request.role + "，如有疑问请联系管理员。",
                    null, "User", id);
        }

        if (request.fullName != null) {
            user.fullName = request.fullName;
        }
        if (request.regionId != null) {
            user.regionId = request.regionId;
        }
        if (request.departmentId != null) {
            user.departmentId = request.departmentId;
        }
        if (request.status != null) {
            // Protect last ACTIVE SYSTEM_ADMIN from being disabled via PUT
            if (!"ACTIVE".equals(request.status) && "SYSTEM_ADMIN".equals(user.role) && "ACTIVE".equals(user.status)) {
                long activeAdminCount = User.count("role = 'SYSTEM_ADMIN' AND status = 'ACTIVE'");
                if (activeAdminCount <= 1) {
                    throw new BusinessException("系统至少保留一个 ACTIVE 系统管理员，不可禁用最后一位");
                }
            }
            user.status = request.status;
        }

        LOG.infof("Updated user id=%s username=%s", id, user.username);
        return UserDTO.from(user);
    }

    @Transactional
    public UserDTO updateStatus(UUID id, String status) {
        User user = User.findById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + id);
        }

        // Protect last ACTIVE SYSTEM_ADMIN from being disabled
        if (!"ACTIVE".equals(status) && "SYSTEM_ADMIN".equals(user.role) && "ACTIVE".equals(user.status)) {
            long activeAdminCount = User.count("role = 'SYSTEM_ADMIN' AND status = 'ACTIVE'");
            if (activeAdminCount <= 1) {
                throw new BusinessException("不能禁用最后一个活跃的系统管理员");
            }
        }

        user.status = status;
        LOG.infof("Updated user status id=%s status=%s", id, status);
        return UserDTO.from(user);
    }

    @Transactional
    public UserDTO resetPassword(UUID id) {
        User user = User.findById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在: " + id);
        }

        String rawPassword = generatePassword();
        user.passwordHash = hashPassword(rawPassword);
        user.isFirstLogin = true;
        user.initialPasswordExpiresAt = OffsetDateTime.now().plusHours(24);

        LOG.infof("Reset password for user id=%s username=%s", id, user.username);

        // Notify user that their password was reset by admin
        notificationService.create(id, "PASSWORD_RESET", "您的密码已被管理员重置",
                "请使用新密码登录，初始密码有效期24小时。", null, "User", id);

        UserDTO dto = UserDTO.from(user);
        dto.initialPassword = rawPassword;
        return dto;
    }

    /**
     * <b>初始密码生成的唯一实现</b>（新建用户 / 重置密码 / task-260902 批量导入 三处共用）。
     * 🚫 不许在导入侧另写一套 —— 两套实现＝两套强度，且以后改密码策略只会改到一边。
     * task-260902：由 private 放宽到<b>包可见</b>供同包的 {@code UserExportImportService} 调用。
     */
    String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(SECURE_RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * <b>口令哈希的唯一实现</b>（BCrypt cost 12），同上由三处共用。
     * task-260902：从 create()/resetPassword() 原地抽出，参数与轮数逐字不变。
     */
    String hashPassword(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
    }
}
