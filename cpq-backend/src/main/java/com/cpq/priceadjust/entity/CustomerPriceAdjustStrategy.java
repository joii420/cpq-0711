package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 客户调价策略主体（屏 1）。一客户一条。
 */
@Entity
@Table(name = "customer_price_adjust_strategy")
public class CustomerPriceAdjustStrategy extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "enabled", nullable = false)
    public Boolean enabled = true;

    @Column(name = "cycle_type", nullable = false, length = 20)
    public String cycleType = "MONTHLY_DAY";

    @Column(name = "cycle_weekday")
    public Short cycleWeekday;

    @Column(name = "cycle_day_of_month")
    public Short cycleDayOfMonth;

    @Column(name = "cycle_nth_week")
    public Short cycleNthWeek;

    @Column(name = "execute_time", nullable = false)
    public LocalTime executeTime = LocalTime.of(9, 0);

    @Column(name = "material_scope_mode", nullable = false, length = 20)
    public String materialScopeMode = "ALL";

    @Column(name = "cost_diff_threshold", precision = 18, scale = 4, nullable = false)
    public BigDecimal costDiffThreshold = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;

    public static CustomerPriceAdjustStrategy findByCustomerNo(String customerNo) {
        return find("customerNo", customerNo).firstResult();
    }
}
