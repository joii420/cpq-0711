package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** task-0729 策略指定料号清单，仅 material_scope_mode=SPECIFIED 时生效。 */
@Entity
@Table(name = "customer_price_adjust_material")
public class CustomerPriceAdjustMaterial extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "strategy_id", nullable = false)
    public UUID strategyId;

    @Column(name = "material_no", nullable = false, length = 50)
    public String materialNo;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    public static List<CustomerPriceAdjustMaterial> listByStrategy(UUID strategyId) {
        return list("strategyId", strategyId);
    }
}
