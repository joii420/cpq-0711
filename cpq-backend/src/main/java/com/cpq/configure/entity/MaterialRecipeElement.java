package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "material_recipe_element")
public class MaterialRecipeElement extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "recipe_id")
    public UUID recipeId;

    /** 权威元素链 → element.element_no（task-0709 · B2）；element_code/name 为符号锁保证恒一致的快照 */
    @Column(name = "element_no")
    public String elementNo;

    @Column(name = "element_code")
    public String elementCode;

    @Column(name = "element_name")
    public String elementName;

    /** task-0813：此前 @Column 未声明 precision/scale；本次按目标类型 (16,12) 补齐声明，
     *  使 T6 反射一致性测试可覆盖该列。 */
    @Column(name = "default_pct", precision = 16, scale = 12)
    public BigDecimal defaultPct;

    @Column(name = "min_pct", precision = 16, scale = 12)
    public BigDecimal minPct;

    @Column(name = "max_pct", precision = 16, scale = 12)
    public BigDecimal maxPct;

    @Column(name = "is_locked")
    public boolean isLocked;

    @Column(name = "sort_order")
    public int sortOrder;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;
}
