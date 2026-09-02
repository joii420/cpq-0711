package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "material_recipe")
public class MaterialRecipe extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    public String code;
    public String symbol;
    public String name;

    @Column(name = "spec_label")
    public String specLabel;

    @Column(name = "recipe_type")
    public String recipeType;

    @Column(name = "sort_order")
    public int sortOrder;

    /**
     * task-260901（M-5）：选配是否允许自定义含量。<b>优先于元素级 is_locked</b> ——
     * false 直接拒绝任何自定义含量、不进元素级校验；true 时 is_locked 不再单独生效。
     * 新建材质（含导入自动创建的）一律默认 false。
     */
    @Column(name = "allow_custom_content")
    public boolean allowCustomContent;

    public String status;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;

    @Column(name = "created_by")
    public UUID createdBy;

    @Column(name = "updated_by")
    public UUID updatedBy;

    public static MaterialRecipe findByCodeOrThrow(String code) {
        MaterialRecipe r = find("code = ?1 AND status = 'ACTIVE'", code).firstResult();
        if (r == null) throw new IllegalArgumentException("材质未找到或未激活: " + code);
        return r;
    }
}
