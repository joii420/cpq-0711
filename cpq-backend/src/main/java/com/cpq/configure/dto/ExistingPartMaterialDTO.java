package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 选配 Step2 锁定路径返回 DTO — 兼容字典派 + BOM 派两种数据源.
 *
 * <p>对应前端 cpq-frontend/src/services/materialRecipeService.ts ExistingPartMaterial 接口.
 *
 * <p>字段分支语义见 docs/选配与基础数据料号材质关系.md 第五节决策树:
 * <ul>
 *   <li>{@code recipeBound=true} (字典派): material_recipe + material_recipe_element
 *       — recipeCode/Symbol/Name/Spec 全有, elements 来自 material_recipe_element</li>
 *   <li>{@code recipeBound=false} (BOM 派): mat_part.material_recipe_id IS NULL
 *       — recipe 字段全 null, elements 来自 mat_bom (bom_type='ELEMENT', latest part_version)
 *       — elements 中的 minPct/maxPct 留 null, isLocked=true (只读)</li>
 * </ul>
 */
public class ExistingPartMaterialDTO {
    public String hfPartNo;
    public boolean recipeBound;
    public String recipeCode;
    public String recipeSymbol;
    public String recipeName;
    public String recipeSpec;
    /** locked / editable / partial — BOM 派固定 "locked" */
    public String recipeType;
    public List<Element> elements = new ArrayList<>();

    public static class Element {
        public String elementCode;
        public String elementName;
        public BigDecimal pct;
        /** 字典派 editable/partial 时有值; locked / BOM 派为 null */
        public BigDecimal minPct;
        public BigDecimal maxPct;
        public boolean isLocked;

        public Element() {}

        public Element(String code, String name, BigDecimal pct,
                       BigDecimal minPct, BigDecimal maxPct, boolean isLocked) {
            this.elementCode = code;
            this.elementName = name;
            this.pct = pct;
            this.minPct = minPct;
            this.maxPct = maxPct;
            this.isLocked = isLocked;
        }
    }
}
