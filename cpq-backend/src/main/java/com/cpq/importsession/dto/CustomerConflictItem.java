package com.cpq.importsession.dto;

/**
 * 客户料号冲突项（Step 2 区块 B，对应 V5 的 CustomerDataConflictDTO 的精简 V6 版）。
 *
 * <p>冲突场景举例：
 *   Excel 中 mat_process.unit_price=120.5，DB 中当前版本 is_current=true 的行 unit_price=115.0
 *   → conflictType="process"，excelValue="120.5"，dbValue="115.0"
 *   → 用户可选 USE_EXCEL（用 Excel 值）或 USE_DB（保留 DB 值）
 */
public class CustomerConflictItem {

    /**
     * 业务唯一键，格式："{conflictType}|{primaryKey}"。
     * 与 import_session_decision.decision_key（CUSTOMER_CONFLICT 类型）一致。
     */
    public String key;

    /**
     * 冲突发生在哪张表/业务类型。
     * 取值：process / fee / plating_fee / mapping 等。
     */
    public String conflictType;

    /** 客户料号 */
    public String customerProductNo;

    /** HF 内部料号 */
    public String hfPartNo;

    /** Excel 中的新值（字符串化，多字段时用 JSON 表示） */
    public String excelValue;

    /** DB 中当前版本的旧值（字符串化） */
    public String dbValue;

    /**
     * 默认建议动作。
     * USE_EXCEL：采用 Excel 中的新值（默认，用户上传即表示意图更新）
     * USE_DB：保留 DB 旧值，丢弃 Excel 中的对应字段
     * SKIP：跳过整行
     */
    public String defaultAction;
}
