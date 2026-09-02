package com.cpq.configure.dto;

import com.cpq.common.DecimalStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.util.List;

public class PartRequest {
    public String name;                    // '配件 1' / '产品'
    public String partMode;                // 'existing' | 'custom'
    public String existingHfPartNo;        // existing 时必填
    public String recipeCode;              // custom 时必填
    /**
     * ★task-260901（api.md §2.4）：选中的<b>标准含量配置</b>编号（如 '00006-01'）。
     * 与 {@link #elements} <b>必须恰好给一个</b>，两个都给或都不给 → 400 MATERIAL_SOURCE_AMBIGUOUS。
     */
    public String configNo;
    /** custom 模式的自定义含量；给了它就要求材质 allowCustomContent=true（M-5） */
    public List<ElementOverride> elements;
    /**
     * 内部标记：材质来源已解析（configNo → elements 已物化 / 自定义含量已校验并归一到 100 制）。
     * 解析必须发生在指纹计算之前，且要幂等 —— 物化之后 configNo 与 elements 会同时非空，
     * 再跑一次互斥校验就会误报 MATERIAL_SOURCE_AMBIGUOUS。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean materialResolved;
    /**
     * task-0712 缺口1(工序 id 契约修复, 方案A): 工序编号顺序数组(命中复用时忽略)。
     * 值 = {@code process_master.process_no}(如 "MRO-LP-0001" / 孤儿 "TP10")，
     * 不再是 {@code process}(V4) 表的 UUID —— 候选端点 / sel_template.allowed_value_key /
     * 指纹 PRC / unit_price.operation_no / quotation_line_process.process_no 全链同一标识。
     */
    public List<String> processNos;
    @JsonDeserialize(using = DecimalStringDeserializer.class)
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
