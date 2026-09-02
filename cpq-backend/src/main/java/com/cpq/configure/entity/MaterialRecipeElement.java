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

    /**
     * task-260901（M-0）：元素行归属由「材质」下沉到「含量配置」。
     * 材质维度经 {@code config_id → material_recipe_config.recipe_id} 两跳获得。
     * <p>旧列 {@code recipe_id} 已于 V399 改为可空、新行不再写入，待 V401（红线，需用户批准）删除。
     */
    @Column(name = "config_id")
    public UUID configId;

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
