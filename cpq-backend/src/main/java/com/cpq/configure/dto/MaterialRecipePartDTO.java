package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 「材质管理 → 关联料号」Tab 中展示的料号精简 DTO.
 *
 * <p>来源: mat_part LEFT JOIN material_recipe (取本材质下 + 可选未绑料号场景)
 *
 * <p>区别于 ProductDTO/InternalMaterialDTO: 本 DTO 直接面向 mat_part(物料主表),
 * 不引用 product / internal_material 旧表;字段聚焦"绑材质"场景所需(料号/品名/规格/状态/重量).
 */
public class MaterialRecipePartDTO {
    public String partNo;
    public String partName;
    public String specification;
    public String sizeInfo;
    public String productType;        // SIMPLE / COMPOSITE
    public String statusCode;         // Y / N
    public BigDecimal unitWeight;
    public UUID materialRecipeId;     // 当前绑定的材质 id(可空)
    public String materialRecipeCode; // JOIN material_recipe.code
    public String materialRecipeSymbol;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
