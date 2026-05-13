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

    @Column(name = "element_code")
    public String elementCode;

    @Column(name = "element_name")
    public String elementName;

    @Column(name = "default_pct")
    public BigDecimal defaultPct;

    @Column(name = "min_pct")
    public BigDecimal minPct;

    @Column(name = "max_pct")
    public BigDecimal maxPct;

    @Column(name = "is_locked")
    public boolean isLocked;

    @Column(name = "sort_order")
    public int sortOrder;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;
}
