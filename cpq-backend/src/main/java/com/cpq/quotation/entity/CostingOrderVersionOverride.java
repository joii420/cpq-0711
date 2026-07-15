package com.cpq.quotation.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 核价单版本 override（task-0713 B1）—— 记「这张核价单里，哪个页签(component)的哪个销售料号
 * 被切到哪个版本」。生命周期归单张核价单独立：报价单驳回→改→重提生成的新核价单
 * <b>不继承</b>旧核价单的 override（新单从 is_current 起，见需求说明 §B1）。
 *
 * <p>唯一键 {@code (costing_order_id, component_id, part_no)}：同一料号在同一页签只有一条生效
 * override（切换 = upsert，不追加历史）。
 */
@Entity
@Table(name = "costing_order_version_override")
public class CostingOrderVersionOverride extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "costing_order_id", nullable = false)
    public UUID costingOrderId;

    @Column(name = "component_id", nullable = false)
    public UUID componentId;

    @Column(name = "part_no", nullable = false, length = 40)
    public String partNo;

    @Column(name = "view_version", nullable = false, length = 40)
    public String viewVersion;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public static List<CostingOrderVersionOverride> findByCostingOrder(UUID costingOrderId) {
        return list("costingOrderId", costingOrderId);
    }

    public static CostingOrderVersionOverride find(UUID costingOrderId, UUID componentId, String partNo) {
        return find("costingOrderId = ?1 and componentId = ?2 and partNo = ?3",
                costingOrderId, componentId, partNo).firstResult();
    }
}
