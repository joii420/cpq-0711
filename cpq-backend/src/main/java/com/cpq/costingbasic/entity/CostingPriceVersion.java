package com.cpq.costingbasic.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 核价基础数据版本主表 —— 元素/材料/汇率 三种 kind 共用一张表。
 * Q2 = A：每个 kind 的 version_number 独立编号（element=2000 / material=2000 / exchange=2000 互不影响）。
 */
@Entity
@Table(name = "costing_price_version")
public class CostingPriceVersion extends PanacheEntityBase {

    /** kind 取值 */
    public static final String KIND_ELEMENT  = "ELEMENT";
    public static final String KIND_MATERIAL = "MATERIAL";
    public static final String KIND_EXCHANGE = "EXCHANGE";

    /** status 取值 */
    public static final String STATUS_DRAFT     = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED  = "ARCHIVED";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "version_kind", nullable = false, length = 20)
    public String versionKind;

    @Column(name = "version_number", nullable = false, length = 50)
    public String versionNumber;

    @Column(nullable = false, length = 20)
    public String status = STATUS_DRAFT;

    @Column(columnDefinition = "TEXT")
    public String notes;

    /** 该 kind 下"全公司当前生效"的默认版本（partial unique 强保证最多 1 份且必须 PUBLISHED） */
    @Column(name = "is_default", nullable = false)
    public Boolean isDefault = false;

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

    @Column(name = "published_by")
    public UUID publishedBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
