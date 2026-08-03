package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 元素价格版本批次（屏 1）。版本本身不谈生效，生效的是料号指针（§11.3.3）。
 * 🔒 只有两态：PENDING / SUPERSEDED。
 */
@Entity
@Table(name = "element_price_version")
public class ElementPriceVersion extends PanacheEntityBase {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "version_no", nullable = false, length = 20)
    public String versionNo;

    @Column(name = "base_date", nullable = false)
    public LocalDate baseDate;

    @Column(name = "status", nullable = false, length = 20)
    public String status = STATUS_PENDING;

    @Column(name = "trigger_type", nullable = false, length = 20)
    public String triggerType;

    @Column(name = "scheduled_slot")
    public OffsetDateTime scheduledSlot;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by")
    public UUID createdBy;

    public static ElementPriceVersion findPending(String customerNo) {
        return find("customerNo = ?1 and status = ?2", customerNo, STATUS_PENDING).firstResult();
    }

    /** 该客户最新一条版本（不分状态），用于取"上一版"价做涨跌幅/无价沿用比对。 */
    public static ElementPriceVersion findLatest(String customerNo) {
        return find("customerNo = ?1 order by createdAt desc", customerNo).firstResult();
    }
}
