package com.cpq.importexcel.dto;

import java.util.List;
import java.util.UUID;

/**
 * 客户资料冲突（mat_process / mat_fee / plating_fee 客户级表，与库中当前版本对比）。
 *
 * <p>UI-2 使用此 DTO 展示"本次导入将新建版本的字段冲突"。
 */
public class CustomerDataConflictDTO {

    /** 客户 ID */
    public UUID customerId;

    /** HF 料号 */
    public String hfPartNo;

    /** 物理表名（snake_case），如 "mat_process" */
    public String tableName;

    /**
     * 行业务键，如 "customer_id:hf_part_no:seq_no" 或
     * "customer_id:hf_part_no:fee_type:seq_no"
     */
    public String rowKey;

    /** 冲突字段列表（仅包含本次 Excel 值与库值不同的字段） */
    public List<ConflictFieldDTO> fields;
}
