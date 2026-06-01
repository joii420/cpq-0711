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
    /**
     * Bug B 修复: 前端在创建 lineItem 时生成的 tempId (crypto.randomUUID)。
     * existing + processIds 路径写 mat_process 时作 quotation_line_item_id，
     * 使同 hf_part_no 不同 lineItem 的工序互不干扰。
     * null = 老路径（无 lineItemId 维度），行为与 V205 之前一致。
     */
    public String quotationLineItemId;     // 前端 tempId (UUID 字符串，optional)
    /**
     * 配件组成用量（仅 COMPOSITE 子件用）。写入 material_bom_item.composition_qty。
     * 正整数，前端默认 1；null 时后端兜底为 1。SIMPLE 场景忽略。
     */
    public Integer quantity;
}
