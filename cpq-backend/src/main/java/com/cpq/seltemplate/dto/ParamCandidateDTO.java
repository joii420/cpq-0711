package com.cpq.seltemplate.dto;

public class ParamCandidateDTO {
    public String key;    // 存入 allowed_value_key（如材质 code / 工序 code）
    public String label;  // 展示（如「304 不锈钢」）
    public ParamCandidateDTO() {}
    public ParamCandidateDTO(String key, String label) { this.key = key; this.label = label; }
}
