package com.cpq.importexcel.dto;

/**
 * 用户对单个 diff/conflict 的决策（前端 confirm 时随表单提交）。
 */
public class ResolutionDTO {

    /**
     * 决策类型：
     * BASIC_DIFF        — 对应 UI-1 基础资料差异（mat_part / mat_bom / plating_plan）
     * CUSTOMER_CONFLICT — 对应 UI-2 客户资料冲突（mat_process / mat_fee / plating_fee）
     * ORPHAN_ROW        — 对应 UI-3 孤儿行（DB 有但本次 Excel 无的 is_current=true 行）
     */
    public String type;

    /** 物理表名（snake_case） */
    public String tableName;

    /** 行业务键（与 BasicDataDiffDTO.rowKey / CustomerDataConflictDTO.rowKey 一致） */
    public String rowKey;

    /** 字段名（snake_case） */
    public String fieldName;

    /**
     * 决策（按 type）：
     * BASIC_DIFF / CUSTOMER_CONFLICT:
     *   ACCEPT_NEW    — 使用 Excel 新值覆盖
     *   KEEP_OLD      — 保留数据库现有值，跳过该字段/行写入
     * ORPHAN_ROW:
     *   DELETE_ORPHAN — 删除该孤儿行（rowKey 解析后执行 DELETE）
     *   KEEP_ORPHAN   — 保留孤儿行（跳过，默认行为）
     */
    public String decision;

    /**
     * 用户填写的变更说明（CRITICAL 字段选择 ACCEPT_NEW 时必填）。
     */
    public String note;

    /**
     * preview 时记录的旧值（前端原样回传，后端在 confirm 时做乐观锁校验）。
     * 如果 DB 在 preview→confirm 期间被其他人修改，则抛 409。
     */
    public String oldValueAtPreview;
}
