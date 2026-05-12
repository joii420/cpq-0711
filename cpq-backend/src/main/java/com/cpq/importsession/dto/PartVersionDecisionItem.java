package com.cpq.importsession.dto;

import java.util.List;
import java.util.Map;

/**
 * 料号版本变更决策项（Step 2 区块 A 中每行对应一条）。
 *
 * <p>设计说明：
 *   - isNew=true 时 currentVersion=null，action 固定为 NEW，前端禁用 NO_BUMP 按钮
 *   - sheetDiffs 提供 sheet 级变更行数摘要（如 bom:3 表示 BOM sheet 有 3 行变更）
 *   - rowLevelDiff 可展开查看行级 field-by-field 差异（前端「查看详情」展开渲染）
 */
public class PartVersionDecisionItem {

    /**
     * 业务唯一键，格式："{customerProductNo}|{hfPartNo}"。
     * 与 import_session_decision.decision_key 一致，前端 PUT decisions 时用此 key 定位。
     */
    public String key;

    /** 客户料号（客户侧命名） */
    public String customerProductNo;

    /** HF 内部料号 */
    public String hfPartNo;

    /**
     * 当前 DB 版本号。
     * 新料号（isNew=true）时为 null（DB 中无 mapping 记录）。
     */
    public Integer currentVersion;

    /**
     * 建议的新版本号。
     * BUMP → currentVersion + 1；NEW → 2000（基线版本）。
     */
    public Integer suggestedVersion;

    /**
     * 是否为新料号（mat_customer_part_mapping 中无对应记录）。
     * true 时：action 固定 NEW，前端禁用 NO_BUMP 按钮。
     */
    public boolean isNew;

    /**
     * 默认决策动作。
     * BUMP：升版（旧料号有实质变更，默认推荐）
     * NO_BUMP：不升版（旧料号无实质变更，沿用当前 DB 版本）
     * NEW：新料号（强制，不可改）
     */
    public String action;

    /**
     * Sheet 级别变更行数摘要。
     * key = sheet 代码（bom / process / fee / plating_fee / plating_plan）
     * value = 变更行数（新增 + 修改 + 删除之和）；0 表示无变更。
     */
    public Map<String, Integer> sheetDiffs;

    /**
     * 行级差异详情（按 sheet 代码分组）。
     * key = sheet 代码；value = 该 sheet 的变更行列表。
     * 前端「查看详情」展开时渲染此数据。
     */
    public Map<String, List<RowDiff>> rowLevelDiff;

    // ── 静态内部类：行级差异 ────────────────────────────────────────────────

    /**
     * 单字段变更记录（行级 diff 中的每一个 field 变更）。
     */
    public static class RowDiff {
        /** 行业务键（如 "seq_no:1" 或 "input_material_no:MAT-001"） */
        public String rowKey;
        /** 变更字段名（英文，如 "gross_qty"、"unit_price"） */
        public String field;
        /** DB 中旧值（null 表示新增行） */
        public String oldValue;
        /** Excel 中新值（null 表示删除行） */
        public String newValue;

        public RowDiff() {}

        public RowDiff(String rowKey, String field, String oldValue, String newValue) {
            this.rowKey = rowKey;
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }
}
