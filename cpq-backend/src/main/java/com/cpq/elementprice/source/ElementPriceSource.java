package com.cpq.elementprice.source;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 元素价格源（task-0722 · B4）。复用 V44 既有表 {@code element_price_source}。
 *
 * <p>{@code sourceType} 本期固定 {@code MANUAL}（不做自动抓价，§11.13）；不提供物理删除，
 * 只有 {@code status} 切换（§11.13.1）。
 */
@Entity
@Table(name = "element_price_source")
public class ElementPriceSource extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "source_name")
    public String sourceName;

    @Column(name = "source_url")
    public String sourceUrl;

    @Column(name = "source_type")
    public String sourceType;

    public String description;

    public String status;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;
}
