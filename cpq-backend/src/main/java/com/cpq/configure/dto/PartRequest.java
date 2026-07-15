package com.cpq.configure.dto;

import java.math.BigDecimal;
import java.util.List;

public class PartRequest {
    public String name;                    // '配件 1' / '产品'
    public String partMode;                // 'existing' | 'custom'
    public String existingHfPartNo;        // existing 时必填
    public String recipeCode;              // custom 时必填
    public List<ElementOverride> elements; // custom 时必填
    /**
     * task-0712 缺口1(工序 id 契约修复, 方案A): 工序编号顺序数组(命中复用时忽略)。
     * 值 = {@code process_master.process_no}(如 "MRO-LP-0001" / 孤儿 "TP10")，
     * 不再是 {@code process}(V4) 表的 UUID —— 候选端点 / sel_template.allowed_value_key /
     * 指纹 PRC / unit_price.operation_no / quotation_line_process.process_no 全链同一标识。
     */
    public List<String> processNos;
    public BigDecimal unitWeightGrams;     // 仅未命中指纹时填(命中复用时忽略)
    /**
     * Bug B 修复: 前端在创建 lineItem 时生成的 tempId (crypto.randomUUID)。
     * existing + processNos 路径写 mat_process 时作 quotation_line_item_id，
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
