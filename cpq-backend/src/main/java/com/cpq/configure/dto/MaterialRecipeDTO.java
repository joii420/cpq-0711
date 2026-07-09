package com.cpq.configure.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    /** Only populated in detail endpoint (GET /{id}); list endpoint leaves it null. */
    public List<MaterialRecipeElementDTO> elements;
    /**
     * 该材质下绑定的料号数 — 仅 list 端点带 ?withCount=true 时填充, 其他场景为 null.
     * 来源: COUNT(*) FROM mat_part WHERE material_recipe_id = id
     */
    public Long boundPartsCount;
}
