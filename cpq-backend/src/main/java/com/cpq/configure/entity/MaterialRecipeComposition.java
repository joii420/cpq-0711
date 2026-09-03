package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 材质的元素组成（task-260901 · B-1，V399 建表）。
 *
 * <p><b>M-0</b>：元素组成是材质的<b>显式属性</b>，不是从配置派生的。每条含量配置的元素集合
 * 必须与它<b>逐个相等</b>（集合相等，不是子集）；配置矩阵的列 = 元素组成、列顺序 = {@code sortOrder}。
 * ⇒ 0 条配置的材质照样有明确的元素组成，列表与抽屉都能正常显示。
 *
 * <p><b>M-0b</b>：该材质无任何 ACTIVE 配置时可自由增删改；一旦有 ACTIVE 配置就转为只读
 * （换元素集合 = 换了另一个材质，该走「新建材质」）。
 */
@Entity
@Table(name = "material_recipe_composition")
public class MaterialRecipeComposition extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "recipe_id")
    public UUID recipeId;

    /** 权威元素链 → element.element_no */
    @Column(name = "element_no")
    public String elementNo;

    /** 符号快照（矩阵列头） */
    @Column(name = "element_code")
    public String elementCode;

    /** 中文名快照 */
    @Column(name = "element_name")
    public String elementName;

    /** 决定配置矩阵的列顺序 */
    @Column(name = "sort_order")
    public int sortOrder;

    @Column(name = "created_at")
    public OffsetDateTime createdAt;
}
