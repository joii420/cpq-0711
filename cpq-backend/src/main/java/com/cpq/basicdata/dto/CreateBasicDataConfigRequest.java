package com.cpq.basicdata.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CreateBasicDataConfigRequest {

    @NotBlank
    public String sheetName;

    public Integer sheetIndex;
    public Integer headerRowIndex;
    public Integer dataStartRowIndex;
    public String description;
    public UUID parentConfigId;
    public List<String> joinColumns;
    public Integer sortOrder;
    public String status;

    /**
     * V58: 行数据写入的物理表名（mat_part / mat_bom 等）。
     * null 表示该 sheet 不参与导入。
     */
    public String targetTable;

    /**
     * V58: 写入物理表时附加的固定列值（如 {"bom_type":"INCOMING"}）。
     * 前端传 Map，Service 层序列化为 JSON String 存入 JSONB 列。
     */
    public Map<String, Object> targetDiscriminator;

    /** V79: 模板类型分类 — QUOTATION / COSTING / BOTH（缺省 BOTH） */
    public String templateKind;
}
