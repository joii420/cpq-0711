package com.cpq.basicdata.v6.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** §4 某组数据（当前版 / 历史版）。rows 为动态列 Map（列名→值，含 NAME 列由后端 join 带出）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RowsDTO {

    public String sheetKey;
    public String materialNo;
    public String version;       // 单版本组=版本号；ELEMENT_BOM 合并展示多材质料号时为 null
    public boolean isCurrent;
    public boolean editable;     // isCurrent && 当前版（历史版恒 false，C7）
    public List<Map<String, Object>> rows;
    public Map<String, Object> masterInfo;  // 主从 BOM 主表信息（bom_type/production_no 等），可空
}
