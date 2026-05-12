package com.cpq.importexcel.dto;

import com.cpq.partversion.dto.PartVersionPreviewDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * V5 导入结果 DTO。
 */
public class ImportResultDTO {

    public UUID importRecordId;
    public String status;           // PREVIEW_OK / PREVIEW_BLOCKED / SUCCESS / FAILED
    public int totalRows;
    public int processedRows;
    public ValidationResult validation;
    public String errorSummary;

    // ── UI-1/UI-2 差异与冲突（仅 preview 阶段，hasErrors=false 时填充）────
    /** 基础资料字段差异列表（全局表：mat_part / mat_bom / plating_plan） */
    public List<BasicDataDiffDTO> basicDataDiffs = new ArrayList<>();

    /** 客户资料冲突列表（客户级表：mat_process / mat_fee / plating_fee） */
    public List<CustomerDataConflictDTO> customerDataConflicts = new ArrayList<>();

    /** UI-3: 孤儿行列表 — DB 有但本次 Excel 无的 is_current=true 行，前端让用户选 DELETE_ORPHAN / KEEP_ORPHAN */
    public List<OrphanRowDTO> orphanRows = new ArrayList<>();

    /**
     * 料号版本管理 B1: 本次 Excel 涉及的料号版本预览.
     * 每个 (customer_product_no, hf_part_no) 一项, 含 current_version + 建议 newVersion.
     * 前端展示并允许用户为每个料号选 BUMP / NO_CHANGE / SKIP.
     * confirm 阶段, 前端通过新参数 partVersionDecisions 把决策传回.
     */
    public List<PartVersionPreviewDTO> partVersionPreview = new ArrayList<>();

    // 统计
    public int matPartCreated;
    public int matPartUpdated;
    public int matBomCreated;
    public int matBomUpdated;
    public int matProcessCreated;
    public int matProcessVersioned;
    public int matFeeCreated;
    public int matFeeVersioned;
    public int platingPlanCreated;
    public int platingFeeCreated;
    public int platingFeeVersioned;
    public int mappingCreated;
    public int mappingUpdated;
    /** V90: 核价料号级数据写入计数（8 张 costing_part_* 表汇总）*/
    public int costingPartRowsWritten;
    public int changeLogRows;
}
