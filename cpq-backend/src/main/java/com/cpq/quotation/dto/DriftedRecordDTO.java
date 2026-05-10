package com.cpq.quotation.dto;

/**
 * 单条漂移记录 DTO，描述某张表中一行业务键已升版。
 *
 * <p>v5.1 §6.6 DRAFT 漂移检测：报价保存时快照的 version 与当前 is_current=true 行的 version 不一致时产生。
 */
public class DriftedRecordDTO {

    /** 表名，如 mat_process / mat_fee / plating_fee / element_price */
    public String tableName;

    /** 业务键（格式：hfPartNo|customerId），element_price 格式：elementName|customerId */
    public String businessKey;

    /** 报价快照记录的 version */
    public int referencedVersion;

    /** 数据库当前 is_current=true 行的 version */
    public int currentVersion;

    /** 用于展示的标识，如 hf_part_no 或 element_name */
    public String displayName;

    public DriftedRecordDTO() {}

    public DriftedRecordDTO(String tableName, String businessKey,
                             int referencedVersion, int currentVersion, String displayName) {
        this.tableName = tableName;
        this.businessKey = businessKey;
        this.referencedVersion = referencedVersion;
        this.currentVersion = currentVersion;
        this.displayName = displayName;
    }
}
