package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 选配分享链接（§17.2）。
 */
@Entity
@Table(name = "product_config_share")
public class ConfiguratorShare extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "instance_id", nullable = false)
    public UUID instanceId;

    @Column(name = "share_type", nullable = false, length = 32)
    public String shareType;  // CUSTOMER_SELF / INTERNAL / PUBLIC_PRESET

    @Column(name = "share_token", nullable = false, unique = true, length = 64)
    public String shareToken;

    @Column(name = "shared_by")
    public UUID sharedBy;

    @Column(name = "shared_to_user_id")
    public UUID sharedToUserId;

    @Column(name = "shared_to_email", length = 128)
    public String sharedToEmail;

    @Column(name = "expires_at")
    public OffsetDateTime expiresAt;

    @Column(name = "access_count", nullable = false)
    public Integer accessCount = 0;

    @Column(name = "last_accessed_at")
    public OffsetDateTime lastAccessedAt;

    @Column(name = "can_modify", nullable = false)
    public Boolean canModify = false;

    @Column(nullable = false, length = 16)
    public String status = "ACTIVE";

    @Column(name = "revoked_at")
    public OffsetDateTime revokedAt;

    @Column(name = "revoked_by")
    public UUID revokedBy;

    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    public String revokeReason;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
