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
