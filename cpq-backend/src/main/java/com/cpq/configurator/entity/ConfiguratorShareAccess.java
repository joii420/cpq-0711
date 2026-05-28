package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "product_config_share_access")
public class ConfiguratorShareAccess extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "share_id", nullable = false)
    public UUID shareId;

    @Column(name = "accessed_at", nullable = false)
    public OffsetDateTime accessedAt = OffsetDateTime.now();

    @Column(length = 64)
    public String ip;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    public String userAgent;

    @Column(length = 255)
    public String action;
}
