package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** task-0729 更新批次（屏 6 + 常驻更新任务页）。 */
@Entity
@Table(name = "material_price_update_job")
public class MaterialPriceUpdateJob extends PanacheEntityBase {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String STALE = "STALE";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "version_id")
    public UUID versionId;

    @Column(name = "version_no", length = 20)
    public String versionNo;

    @Column(name = "triggered_by")
    public UUID triggeredBy;

    @Column(name = "triggered_at", nullable = false)
    public OffsetDateTime triggeredAt = OffsetDateTime.now();

    @Column(name = "status", nullable = false, length = 20)
    public String status = RUNNING;

    @Column(name = "total_count", nullable = false)
    public Integer totalCount = 0;

    @Column(name = "success_count", nullable = false)
    public Integer successCount = 0;

    @Column(name = "failed_count", nullable = false)
    public Integer failedCount = 0;

    @Column(name = "conflict_count", nullable = false)
    public Integer conflictCount = 0;

    @Column(name = "stale_count", nullable = false)
    public Integer staleCount = 0;

    @Column(name = "finished_at")
    public OffsetDateTime finishedAt;

    @Column(name = "notified", nullable = false)
    public Boolean notified = false;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
