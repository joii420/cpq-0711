package com.cpq.component.dto;

import java.util.List;
import java.util.Map;

/** POST /{id}/row-key-candidates 入参。fields 用前端当前编辑态（支持未保存）。 */
public class RowKeyCandidatesRequest {
    /** 组件当前的 driver 路径（编辑态）。 */
    public String dataDriverPath;
    /** 当前字段列表（loose：至少含 name / field_type / basic_data_path）。 */
    public List<Map<String, Object>> fields;
}
