package com.cpq.priceadjust.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * task-0729 料号版本指针（仅 QUOTE 侧维度，裁决 40）。f_material_element_price 的热路径查询表。
 */
@Entity
@Table(name = "material_price_version_ref")
public class MaterialPriceVersionRef extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "customer_no", nullable = false, length = 64)
    public String customerNo;

    @Column(name = "material_no", nullable = false, length = 50)
    public String materialNo;

    @Column(name = "version_id", nullable = false)
    public UUID versionId;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();

    public static MaterialPriceVersionRef findRef(String customerNo, String materialNo) {
        return find("customerNo = ?1 and materialNo = ?2", customerNo, materialNo).firstResult();
    }
}
