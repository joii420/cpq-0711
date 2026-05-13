package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class PartRequest {
    public String name;                    // '配件 1' / '产品'
    public String partMode;                // 'existing' | 'custom'
    public String existingHfPartNo;        // existing 时必填
    public String recipeCode;              // custom 时必填
    public List<ElementOverride> elements; // custom 时必填
    public List<UUID> processIds;          // 工序 id 顺序数组(命中复用时忽略)
    public BigDecimal unitWeightGrams;     // 仅未命中指纹时填(命中复用时忽略)
}
