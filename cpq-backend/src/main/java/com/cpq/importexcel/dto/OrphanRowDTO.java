package com.cpq.importexcel.dto;

import java.util.Map;

/**
 * V5 import preview 阶段检测出的"孤儿行"——
 * DB 中有 (customer_id, hf_part_no, fee_type/etc) 同三元组下的现存 is_current=true row,
 * 但其完整业务键不在本次 Excel 任何 row 里。前端 UI 让用户选 DELETE_ORPHAN / KEEP_ORPHAN.
 */
public class OrphanRowDTO {
    /** 物理表名 (mat_fee / mat_process) */
    public String tableName;

    /**
     * 完整业务键拼接, 用于 ResolutionDTO.rowKey 回传。
     * mat_fee:    customer_id:hf_part_no:fee_type:seq_no:dim_input_no:dim_input_name:dim_element:dim_assembly:dim_sub_seq
     * mat_process: customer_id:hf_part_no:seq_no:sub_seq_no
     */
    public String rowKey;

    /** 料号 (用户视角分组) */
    public String partNo;

    /** 用户友好显示, 如 "INCOMING_FIXED 项次=3 投入料号名称=XXXAg触点 (Excel 中无此行)" */
    public String displayLabel;

    /** 完整 row 字段值快照, 供前端表格预览 */
    public Map<String, Object> rowSnapshot;

    /** 重要程度: NORMAL (孤儿一般不影响公式) */
    public String importance;
}
