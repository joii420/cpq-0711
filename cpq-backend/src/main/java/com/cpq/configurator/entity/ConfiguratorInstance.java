package com.cpq.configurator.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 选配实例 — 用户选配的运行时数据。
 *
 * <p>状态机：DRAFT → SUBMITTED → LINKED；30 天未操作 → EXPIRED
 *
 * <p>编号规则：{@code instance_code = "CI-" + yyyyMM + "-" + lpad(seq, 4, '0')}
 *
 * <p>详见 docs/3D产品选配方案.md §7.8
 */
@Entity
@Table(name = "product_config_instance")
public class ConfiguratorInstance extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "instance_code", nullable = false, unique = true, length = 40)
    public String instanceCode;

    @Column(name = "template_id", nullable = false)
    public UUID templateId;

    @Column(name = "template_version")
    public Integer templateVersion;

    @Column(length = 128)
    public String name;

    @Column(name = "customer_id")
    public UUID customerId;

    @Column(name = "customer_lead_id")
    public UUID customerLeadId;

    @Column(name = "user_id")
    public UUID userId;

    @Column(name = "share_token", length = 64)
    public String shareToken;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selected_values", nullable = false, columnDefinition = "JSONB")
    public Map<String, Object> selectedValues;

    @Column(name = "config_fingerprint", length = 64)
    public String configFingerprint;

    @Column(name = "computed_total_price", precision = 18, scale = 4)
    public BigDecimal computedTotalPrice;

    @Column(name = "base_price", precision = 18, scale = 4)
    public BigDecimal basePrice;

    @Column(nullable = false, length = 16)
    public String status = "DRAFT";

    @Column(name = "linked_quotation_id")
    public UUID linkedQuotationId;

    @Column(name = "linked_at")
    public OffsetDateTime linkedAt;

    @Column(name = "linked_by")
    public UUID linkedBy;

    @Column(name = "generated_part_no", length = 64)
    public String generatedPartNo;

    @Column(name = "generated_quotation_id")
    public UUID generatedQuotationId;

    @Column(name = "generated_line_item_id")
    public UUID generatedLineItemId;

    @Column(name = "expires_at")
    public OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
