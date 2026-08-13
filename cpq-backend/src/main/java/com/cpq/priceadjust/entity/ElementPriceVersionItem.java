package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** task-0729 版本明细：版本 × 元素一组价（E12 报价核价共用，不分侧）。 */
@Entity
@Table(name = "element_price_version_item")
public class ElementPriceVersionItem extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "version_id", nullable = false)
    public UUID versionId;

    @Column(name = "element_code", nullable = false, length = 32)
    public String elementCode;

    @Column(name = "current_price", precision = 26, scale = 12)
    public BigDecimal currentPrice;

    @Column(name = "previous_price", precision = 26, scale = 12)
    public BigDecimal previousPrice;

    @Column(name = "change_rate", precision = 18, scale = 12)
    public BigDecimal changeRate;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "price_unit", length = 20)
    public String priceUnit;

    @Column(name = "no_price", nullable = false)
    public Boolean noPrice = false;

    @Column(name = "inherited_from_previous", nullable = false)
    public Boolean inheritedFromPrevious = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    public static List<ElementPriceVersionItem> listByVersion(UUID versionId) {
        return list("versionId", versionId);
    }
}
