package com.cpq.basicdata.entity;

import com.cpq.common.exception.BusinessException;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "product_category")
public class ProductCategory extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 50)
    public String code;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "parent_id")
    public UUID parentId;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // task-0712 update-071501: 客户/选配模板兜底"默认分类"的唯一权威来源（DRY，代码评审 Minor #2）。
    // 按 name='默认分类' 查（非 code）：seed(product_category 表 WHERE NOT EXISTS name='默认分类')/
    // backfill/前端全链均按 name 判定业务身份；若既存默认分类的 code 不是约定值，按 code 查会误判查不到。
    public static UUID requireDefaultId() {
        ProductCategory pc = ProductCategory.find("name", "默认分类").firstResult();
        if (pc == null) {
            throw new BusinessException(500, "系统缺少「默认分类」，请先在产品分类维护中创建");
        }
        return pc.id;
    }
}
