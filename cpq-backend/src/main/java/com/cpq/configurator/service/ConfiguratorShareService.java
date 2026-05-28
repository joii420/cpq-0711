package com.cpq.configurator.service;

import com.cpq.common.dto.PageResult;
import com.cpq.configurator.entity.ConfiguratorShare;
import com.cpq.configurator.entity.ConfiguratorShareAccess;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * §17 分享链接服务。
 */
@ApplicationScoped
public class ConfiguratorShareService {

    /**
     * 创建分享链接（销售从选配页点「🔗 分享给客户」）
     */
    @Transactional
    public ConfiguratorShare create(UUID instanceId, String shareType, String email, Integer days, Boolean canModify) {
        ConfiguratorShare s = new ConfiguratorShare();
        s.instanceId = instanceId;
        s.shareType = shareType == null ? "CUSTOMER_SELF" : shareType;
        s.shareToken = "shr-" + java.util.UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        s.sharedToEmail = email;
        s.canModify = canModify == null ? Boolean.FALSE : canModify;
        s.status = "ACTIVE";
        s.accessCount = 0;
        s.expiresAt = OffsetDateTime.now().plusDays(days == null ? 7 : days);
        s.persist();
        return s;
    }

    public PageResult<ConfiguratorShare> list(int page, int size, String status, String shareType, String keyword) {
        StringBuilder q = new StringBuilder("1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            params.add(status);
            q.append(" and status = ?").append(params.size());
        }
        if (shareType != null && !shareType.isBlank()) {
            params.add(shareType);
            q.append(" and shareType = ?").append(params.size());
        }
        var pq = ConfiguratorShare.find(q.toString(), Sort.by("createdAt").descending(), params.toArray());
        long total = pq.count();
        List<ConfiguratorShare> rows = pq.page(Page.of(page, size)).list();
        return new PageResult<>(rows, page, size, total);
    }

    public ConfiguratorShare getById(UUID id) {
        ConfiguratorShare s = ConfiguratorShare.findById(id);
        if (s == null) throw new NotFoundException("Share not found: " + id);
        return s;
    }

    /** 统计 4 状态 + 已访问数 */
    public Map<String, Long> stats() {
        Map<String, Long> ret = new HashMap<>();
        ret.put("ACTIVE", ConfiguratorShare.count("status", "ACTIVE"));
        ret.put("EXPIRED", ConfiguratorShare.count("status", "EXPIRED"));
        ret.put("REVOKED", ConfiguratorShare.count("status", "REVOKED"));
        ret.put("ACCESSED", ConfiguratorShare.count("accessCount > 0"));
        return ret;
    }

    @Transactional
    public ConfiguratorShare extend(UUID id, int days) {
        ConfiguratorShare s = getById(id);
        OffsetDateTime base = s.expiresAt != null && s.expiresAt.isAfter(OffsetDateTime.now())
            ? s.expiresAt : OffsetDateTime.now();
        s.expiresAt = base.plusDays(days);
        if ("EXPIRED".equals(s.status)) s.status = "ACTIVE";  // 重新激活
        return s;
    }

    public List<ConfiguratorShareAccess> listAccess(UUID shareId) {
        return ConfiguratorShareAccess.find("shareId", Sort.by("accessedAt").descending(), shareId).list();
    }

    @Transactional
    public ConfiguratorShare revoke(UUID id, String reason, UUID revokedBy) {
        ConfiguratorShare s = getById(id);
        s.status = "REVOKED";
        s.revokedAt = OffsetDateTime.now();
        s.revokedBy = revokedBy;
        s.revokeReason = reason;
        return s;
    }
}
