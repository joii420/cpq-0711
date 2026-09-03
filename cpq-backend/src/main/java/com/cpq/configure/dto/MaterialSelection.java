package com.cpq.configure.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * task-260902（api.md §1.2）：三层模型里「零件 → 材质 1..N」的一项。
 *
 * <p>取代 {@link PartRequest} 上的单值 {@code recipeCode / configNo / elements}
 * （老字段保留并标 {@code @Deprecated}，解析时若 {@code materials} 为空则回落成
 * 一项本类实例、{@code ratio} 默认 100）。
 *
 * <p>⚠️ {@link #materialResolved} 必须<b>下沉到 material 级</b>（评审 P2-15）：
 * {@code lookupFingerprint} 与 {@code configure} 各会调一次
 * {@code prepareMaterialSelection}，物化之后 {@code configNo} 与 {@code elements} 会同时非空，
 * 不带幂等标志再跑一次互斥校验就会误报 {@code MATERIAL_SOURCE_AMBIGUOUS}。
 */
public class MaterialSelection {

    /** 材质库编号（{@code material_recipe.code}，如 "00006"）。 */
    public String recipeCode;

    /** 标准含量配置编号（{@code material_recipe_config.config_no}，如 "00006-01"）。与 {@link #elements} 恰好给一个。 */
    public String configNo;

    /** 材质占比 %（同一零件内各材质合计必须正好 100，判等用 {@code BigDecimal.compareTo}）。 */
    @JsonDeserialize(using = DecimalStringDeserializer.class)
    public BigDecimal ratio;

    /** 自定义含量（要求材质 {@code allow_custom_content=true}）。与 {@link #configNo} 恰好给一个。 */
    public List<ElementOverride> elements;

    /** 内部标记：材质来源已解析（configNo → elements 已物化 / 自定义含量已校验并归一到 100 制）。幂等用。 */
    @JsonIgnore
    public boolean materialResolved;

    public MaterialSelection() {}

    public MaterialSelection(String recipeCode, String configNo, BigDecimal ratio, List<ElementOverride> elements) {
        this.recipeCode = recipeCode;
        this.configNo = configNo;
        this.ratio = ratio;
        this.elements = elements;
    }
}
