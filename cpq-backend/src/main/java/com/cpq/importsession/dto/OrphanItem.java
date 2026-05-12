package com.cpq.importsession.dto;

import java.util.Map;

/**
 * 孤儿行项（Step 2 区块 C，对应 V5 的 OrphanRowDTO 的精简 V6 版）。
 *
 * <p>孤儿行定义：Excel 中某行数据的 hfPartNo 在 DB 的 mat_customer_part_mapping 中无记录，
 * 即该行数据无法关联到已知客户料号。
 */
public class OrphanItem {

    /**
     * 业务唯一键，格式："{sheetCode}|{rowIndex}"。
     * 与 import_session_decision.decision_key（ORPHAN 类型）一致。
     */
    public String key;

    /**
     * 来源 sheet 代码。
     * 取值：bom / process / fee / plating_fee / plating_plan 等。
     */
    public String sheetCode;

    /** Excel 中 1-based 行号（与校验错误行号对齐） */
    public int rowIndex;

    /**
     * 该行的原始数据快照（字段名 → 值的字符串化）。
     * 前端展示孤儿行详情时使用，如 {"hf_part_no":"HF-B100","seq_no":"1"}。
     */
    public Map<String, Object> rowSnapshot;

    /** 孤儿原因说明（中文，如"hf_part_no 在 mat_customer_part_mapping 中无记录"） */
    public String reason;

    /**
     * 默认建议动作。
     * DISCARD：丢弃该孤儿行（默认，最安全）
     * CREATE_NEW：作为新料号新建
     * LINK_EXISTING：关联到已有料号（需前端提供 targetPartId）
     */
    public String defaultAction;
}
