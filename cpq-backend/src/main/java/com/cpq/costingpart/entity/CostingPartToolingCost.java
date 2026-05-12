package com.cpq.costingpart.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "costing_part_tooling_cost")
public class CostingPartToolingCost extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;

    @Column(name = "hf_part_no", nullable = false, length = 100)
    public String hfPartNo;

    /** 料号版本管理 (V153): 与 hf_part_no 共同组成业务唯一键, 默认 2000 */
    @Column(name = "part_version", nullable = false)
    public Integer partVersion = 2000;

    @Column(name = "process_no", nullable = false, length = 50)
    public String processNo;

    @Column(name = "process_name", length = 200)
    public String processName;

    @Column(name = "seq_no", nullable = false)
    public Integer seqNo;

    @Column(name = "tooling_no", length = 100)
    public String toolingNo;

    @Column(name = "tooling_unit_cost", nullable = false, precision = 18, scale = 4)
    public BigDecimal toolingUnitCost;

    @Column(name = "process_count")
    public Integer processCount;

    @Column(name = "cycle_count")
    public Integer cycleCount;

    @Column(name = "unit_price", precision = 18, scale = 6)
    public BigDecimal unitPrice;  // 应用层算 I/J/K 后落库

    @Column(nullable = false, length = 10)
    public String currency = "CNY";

    @Column(nullable = false, length = 20)
    public String unit = "PCS";

    @Column(name = "is_active", nullable = false)
    public Boolean isActive = true;

    @Column(columnDefinition = "TEXT")
    public String notes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist void prePersist() {
        var n = OffsetDateTime.now();
        if (createdAt == null) createdAt = n;
        updatedAt = n;
        // 自动算 unit_price = I/J/K
        if (unitPrice == null && toolingUnitCost != null
                && processCount != null && processCount > 0
                && cycleCount != null && cycleCount > 0) {
            unitPrice = toolingUnitCost
                .divide(BigDecimal.valueOf(processCount), 6, java.math.RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(cycleCount), 6, java.math.RoundingMode.HALF_UP);
        }
    }
    @PreUpdate void preUpdate() {
        updatedAt = OffsetDateTime.now();
        if (toolingUnitCost != null
                && processCount != null && processCount > 0
                && cycleCount != null && cycleCount > 0) {
            unitPrice = toolingUnitCost
                .divide(BigDecimal.valueOf(processCount), 6, java.math.RoundingMode.HALF_UP)
                .divide(BigDecimal.valueOf(cycleCount), 6, java.math.RoundingMode.HALF_UP);
        }
    }
}
