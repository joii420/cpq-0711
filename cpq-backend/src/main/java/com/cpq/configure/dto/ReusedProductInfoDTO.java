package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * task-260902 · B-11（api.md §1.3）：指纹命中复用时带出的「已有销售产品信息」。
 *
 * <p>AC-7 状态 C：用户配完一个与既有产品完全相同的零件时，前端要能明示
 * 「该配置已存在，沿用料号 X」并展示它的品名/规格/材质构成，而不是静默复用。
 */
public class ReusedProductInfoDTO {

    public String hfPartNo;
    public String partName;
    public String specification;
    public String dimension;
    public BigDecimal unitWeight;
    public List<Material> materials = new ArrayList<>();
    /** 该销售料号在 {@code sel_part_signature} 的首次登记时间。 */
    public OffsetDateTime firstCreatedAt;
    /** 最近一次报价单价；当前无权威取数来源，恒为 null（契约允许空，🚫 不臆造数值）。 */
    public BigDecimal lastQuotedPrice;

    public static class Material {
        public String recipeCode;
        public String name;
        public BigDecimal ratio;

        public Material() {}

        public Material(String recipeCode, String name, BigDecimal ratio) {
            this.recipeCode = recipeCode;
            this.name = name;
            this.ratio = ratio;
        }
    }
}
