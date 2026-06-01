package com.cpq.component.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CreateComponentRequest {
    public String name;
    public String code;
    public UUID directoryId;
    public String componentType;
    /** Y1.5 行驱动 BNF 路径,可选 */
    public String dataDriverPath;
    public String status;
    // Accept fields/formulas as either parsed list or raw JSON string
    public List<Map<String, Object>> fields;
    public List<Map<String, Object>> formulas;

    /**
     * 行键配置（报价单整份快照 Phase 1 §5.1）。
     * 字符串数组（fields[].name 中存在的名称），如 ["子件","元素"] 或哨兵 ["__seq_no__"]。
     * null = 未配置（新建时若需要则被硬拦，更新时仅告警）。
     */
    public List<String> rowKeyFields;
}
