package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** task-0729 策略参与调价元素清单。为空/全无价 → 不生成版本（E14-10）。 */
@Entity
@Table(name = "customer_price_adjust_element")
public class CustomerPriceAdjustElement extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "strategy_id", nullable = false)
    public UUID strategyId;

    @Column(name = "element_code", nullable = false, length = 32)
    public String elementCode;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    public static List<CustomerPriceAdjustElement> listByStrategy(UUID strategyId) {
        return list("strategyId", strategyId);
    }
}
