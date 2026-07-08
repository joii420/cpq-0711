package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 销售侧客户维度指纹去重登记。
 *
 * 与生产侧 {@code material_master.config_fingerprint}（全局唯一）不同：本表按
 * (customer_no, structure_version, config_fingerprint) 客户维度唯一，用于同一客户
 * 同一选配复用同一报价料号（quote_part_no）。
 */
@Entity
@Table(name = "sel_part_signature")
public class SelPartSignature extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 50)
    public String customerNo;

    @Column(name = "structure_version", nullable = false, length = 10)
    public String structureVersion;

    @Column(name = "config_fingerprint", nullable = false, length = 64)
    public String configFingerprint;

    @Column(name = "config_signature_text", nullable = false)
    public String configSignatureText;

    @Column(name = "quote_part_no", nullable = false, length = 32)
    public String quotePartNo;

    @Column(name = "product_type", nullable = false, length = 16)
    public String productType = "SIMPLE";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
