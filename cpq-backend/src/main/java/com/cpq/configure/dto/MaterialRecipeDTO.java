package com.cpq.configure.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 材质 DTO（列表项 + 详情共用，task-260901 改造）。
 *
 * <p><b>BC-1</b>：{@code elements} 已移除，改为 {@link #configs}（元素挂在每个配置下）。
 * <b>BC-2b</b>：{@link #elementCodes} 的语义源改为 {@code material_recipe_composition}
 * ——「0 配置的材质现在也有值」，前端不能再假设「无配置 ⇒ 无元素」。
 */
public class MaterialRecipeDTO {
    public UUID id;
    public String code;
    public String symbol;
    public String name;
    public String specLabel;
    public String recipeType;
    public String status;
    public Integer sortOrder;
    /** 创建时间（task-0708 列表新增列，排序依据之一） */
    public OffsetDateTime createdAt;
    /** 修改时间（task-0708 列表新增列，排序依据之一） */
    public OffsetDateTime updatedAt;

    /** ★task-260901：是否支持自定义含量（M-5，默认 false） */
    public boolean allowCustomContent;

    /**
     * ★task-260901：元素组成的符号，按 sortOrder。
     * 来源是 {@code material_recipe_composition}，<b>与配置无关</b>：0 配置的材质照样有值（BC-2b）。
     */
    public List<String> elementCodes;

    /** ★task-260901：ACTIVE 配置数；0 = 未配置含量 */
    public long configCount;

    /** ★task-260901（详情）：材质的元素组成 —— 配置矩阵列的权威来源 */
    public List<CompositionItemDTO> composition;

    /** ★task-260901（详情）：false = 该材质已有 ACTIVE 配置，元素组成只读（M-0b） */
    public Boolean compositionEditable;

    /** ★task-260901（详情）：取代原 elements；默认只含 ACTIVE，见 GET 参数 includeInactiveConfigs */
    public List<MaterialRecipeConfigDTO> configs;

    /**
     * 该材质下绑定的料号数 — 仅 list 端点带 ?withCount=true 时填充, 其他场景为 null.
     * 来源: COUNT(*) FROM material_master WHERE material_recipe_id = id
     */
    public Long boundPartsCount;
}
